package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.jmeterwebinsightreport.core.servermetrics.ServerSource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@code prometheus} source: {@code GET <url>/query_range?query&start&end&step}, with the
 * settle loop the canvas specified (§3) instead of a fixed sleep.
 *
 * <p>A fixed sleep is the wrong primitive: it breaks the day a scrape lags. So the source
 * waits until the data is <em>demonstrably complete</em>, with a hard cap:
 * <ol>
 *   <li>a floor of {@code max(2 × step, 30 s)} after the window's end — below one scrape
 *       interval there is nothing to fetch;</li>
 *   <li>poll, and require the last point to reach the window's end (within a step);</li>
 *   <li>require two consecutive polls to return the same tail — late-arriving and back-filled
 *       points are common. An <em>empty</em> answer has no tail to reach the end, so two
 *       identical empty polls after the floor settle it as EMPTY: a selector that matches
 *       nothing is an answer, and waiting {@code maxWait} for it would cost ten minutes per
 *       typo, serially;</li>
 *   <li>cap at {@code maxWait}, then return what arrived. Whether that is PARTIAL or
 *       INSUFFICIENT is the collector's call against the coverage floor. A poll that fails
 *       <em>after</em> an earlier one answered returns that earlier answer for the same reason
 *       — what arrived is not discarded because the network blinked once.</li>
 * </ol>
 *
 * <p>The window is requested in the source's own clock: the declared {@code clockOffsetMs} is
 * what must be added to a source timestamp to get JTL time, so the request subtracts it and
 * {@link PrometheusResponse#parse} adds it back. Requesting the JTL window unshifted lost exactly
 * {@code offset} ms of coverage at one edge — 10% of a five-minute run under the 30 s skew the
 * field's own javadoc uses as its example.
 *
 * <p>HTTP, the clock and sleeping are injectable so the loop is testable without a network or
 * wall-clock time; the default wiring is {@link HttpClient} over JDK 11 — no dependency, nothing
 * for {@code verify_shaded_jar} to allow.
 *
 * <p>Authentication is by reference only: the source's {@code auth.*Env} names are resolved
 * against the secrets the collector was given (the process environment), and the values go
 * into headers — never into the URL, never into the payload. Which references exist is
 * {@link ServerSource#AUTH_KEYS}, and {@link ServerSource#authProblem} is asked here before each
 * series is fetched: the annotations file declares no source to check at load time (C8).
 */
public final class PrometheusSource implements SeriesSource {

    /** One HTTP round trip, injectable. */
    public interface Http {
        Response get(URI uri, Map<String, String> headers) throws IOException;
    }

    public static final class Response {
        private final int status;
        private final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public int status() { return status; }
        public String body() { return body; }
    }

    /** Wall-clock and sleep, injectable so the settle loop runs in a test in no time at all. */
    public interface Timing {
        long nowMillis();
        void sleep(long millis) throws InterruptedException;
    }

    /** The knobs of the settle loop. */
    public static final class Settle {
        public static final long DEFAULT_MAX_WAIT_MILLIS = 10 * 60_000L;
        public static final long DEFAULT_POLL_MILLIS = 15_000L;
        public static final long MIN_FLOOR_MILLIS = 30_000L;
        public static final int DEFAULT_STABLE_FOR = 2;

        private final long maxWaitMillis;
        private final long pollMillis;
        private final int stableFor;

        public Settle(long maxWaitMillis, long pollMillis, int stableFor) {
            this.maxWaitMillis = maxWaitMillis;
            this.pollMillis = pollMillis;
            this.stableFor = Math.max(1, stableFor);
        }

        public static Settle defaults() {
            return new Settle(DEFAULT_MAX_WAIT_MILLIS, DEFAULT_POLL_MILLIS, DEFAULT_STABLE_FOR);
        }

        /** No waiting at all — for replaying a window that ended long ago, and for tests. */
        public static Settle none() {
            return new Settle(0, 0, 1);
        }
    }

    private final Http http;
    private final Timing timing;
    private final Map<String, String> secrets;
    private final Settle settle;

    public PrometheusSource(Http http, Timing timing, Map<String, String> secrets, Settle settle) {
        this.http = http;
        this.timing = timing;
        this.secrets = secrets == null ? new LinkedHashMap<String, String>() : secrets;
        this.settle = settle == null ? Settle.defaults() : settle;
    }

    /** The production wiring: JDK HttpClient, the system clock, Thread.sleep. */
    public static PrometheusSource live(Map<String, String> secrets, Settle settle) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Http http = (uri, headers) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET();
            for (Map.Entry<String, String> h : headers.entrySet()) {
                b.header(h.getKey(), h.getValue());
            }
            try {
                HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for " + uri.getHost(), e);
            }
        };
        Timing timing = new Timing() {
            @Override public long nowMillis() { return System.currentTimeMillis(); }
            @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        };
        return new PrometheusSource(http, timing, secrets, settle);
    }

    @Override
    public Answer collect(ServerSource source, String resolvedQuery, ServerMetricsPayload.Window window) {
        return collect(source, resolvedQuery, window, settle);
    }

    /**
     * A one-shot request (the catalogue metrics no assert names, collected for the page's picker:
     * D1) is fetched once, without the settle loop: its numbers inform, they judge nothing, and
     * settling each of a type's metrics would cost a poll interval apiece.
     *
     * <p>A neutral request — a catalogue metric with no prometheus query, its "query" the
     * identity {@code <type>:<metric>} — is answered {@link Answer#noQuery} and never sent: it is
     * not PromQL, and asking for it would read as a selector that matched nothing.
     */
    @Override
    public Answer collect(ServerSource source, Request request, ServerMetricsPayload.Window window) {
        if (request.neutral()) {
            return Answer.noQuery(request);
        }
        return collect(source, request.resolvedQuery(), window, request.oneShot() ? Settle.none() : settle);
    }

    private Answer collect(ServerSource source, String resolvedQuery, ServerMetricsPayload.Window window,
                           Settle settle) {
        Map<String, String> headers;
        try {
            headers = headersFor(source);
        } catch (IllegalArgumentException e) {
            return Answer.failed(e.getMessage());
        }
        URI uri;
        try {
            // In the source's clock: JTL time = source time + offset, so the request subtracts it.
            long offset = source.clockOffsetMs();
            ServerMetricsPayload.Window requested = new ServerMetricsPayload.Window(
                    window.getStart() - offset, window.getEnd() - offset, null);
            uri = queryRangeUri(source.url(), resolvedQuery, requested, source.stepMillis());
        } catch (IllegalArgumentException e) {
            return Answer.failed("not a URL: " + Redaction.url(source.url()) + " - " + e.getMessage());
        }

        long floor = Math.max(2 * source.stepMillis(), Settle.MIN_FLOOR_MILLIS);
        long started = timing.nowMillis();
        long deadline = started + settle.maxWaitMillis;
        List<ServerMetricsPayload.InstanceRecord> last = null;   // the latest answer, kept across a failed poll
        try {
            // 1. The floor: below one scrape interval after the window there is nothing to fetch.
            long sinceEnd = started - window.getEnd();
            if (settle.maxWaitMillis > 0 && sinceEnd < floor) {
                timing.sleep(Math.min(floor - sinceEnd, settle.maxWaitMillis));
            }

            String lastTail = null;
            int stable = 0;
            while (true) {
                Response r = http.get(uri, headers);
                if (r.status() != 200) {
                    if (last != null) {
                        return Answer.of(last);   // what arrived; the floor decides what it is worth
                    }
                    return Answer.failed("HTTP " + r.status() + " from " + Redaction.url(uri.toString())
                            + snippet(r.body()));
                }
                PrometheusResponse.Parsed parsed = PrometheusResponse.parse(r.body(), source.clockOffsetMs());
                if (parsed.failed()) {
                    return Answer.failed(parsed.error());
                }
                last = parsed.instances();

                // 2 + 3. Reached the end of the window, and the tail has stopped moving. An empty
                // answer has no tail: two identical empty polls are the evidence instead.
                boolean reachesEnd = lastTimestamp(last) >= window.getEnd() - source.stepMillis();
                String tail = tailSignature(last, window, source.stepMillis());
                stable = tail.equals(lastTail) ? stable + 1 : 1;
                lastTail = tail;
                boolean settled = last.isEmpty()
                        ? stable >= settle.stableFor
                        : reachesEnd && stable >= settle.stableFor;
                if (settled) {
                    return Answer.of(last);
                }
                // 4. The cap.
                long now = timing.nowMillis();
                if (settle.maxWaitMillis <= 0 || now + settle.pollMillis > deadline) {
                    return Answer.of(last);
                }
                timing.sleep(settle.pollMillis);
            }
        } catch (IOException e) {
            if (last != null) {
                return Answer.of(last);
            }
            return Answer.failed("GET " + Redaction.url(uri.toString()) + " failed: " + describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.failed("interrupted while waiting for " + Redaction.url(uri.toString()));
        }
    }

    /**
     * An I/O failure as a reader can use it: the exception's type, then the first message
     * anywhere in the cause chain — or, when nothing in the chain carries one, the type and what
     * it means.
     *
     * <p>The JDK client reports the commonest source failure there is, a refused connection, as a
     * {@code ConnectException} whose message is null, caused by another null-message
     * {@code ConnectException}, caused by a {@code ClosedChannelException} with none either. So
     * {@code "failed: " + e.getMessage()} wrote "failed: null" into the payload, the Server tab's
     * collection panel, the SOURCE_FAILED reason row and the CI/CD JSON — explaining nothing at the
     * one moment a reader needed it.
     *
     * <p>The type always leads, message or not. Which of those chains the JDK produces depends on
     * the platform — on Linux the first connect usually fails with "Connection refused", which the
     * client copies onto the {@code ConnectException} it throws — and a reader (or a test) should
     * not get "ConnectException" on one machine and a bare "Connection refused" on another.
     */
    static String describe(IOException e) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                return e.getClass().getSimpleName() + ": " + message;
            }
        }
        if (e instanceof java.net.ConnectException) {
            return "could not connect (ConnectException) - nothing is listening at that address, or it "
                    + "is unreachable from here";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "timed out (" + e.getClass().getSimpleName() + ")";
        }
        return e.getClass().getSimpleName() + " (the HTTP client gave no detail)";
    }

    /**
     * {@code <url>/query_range?query=…&start=…&end=…&step=…}, times in seconds as Prometheus wants
     * them — {@code 1700000000.123}, never {@code 1.700000000123E9}, which Prometheus happens to
     * parse but nothing compatible is obliged to, and which no one can read in an error line.
     * The step is whole seconds when it can be ({@code 15s}) and milliseconds otherwise
     * ({@code 1500ms}): Prometheus's duration grammar has no fractions. A base URL that already
     * carries a query string keeps it.
     */
    static URI queryRangeUri(String baseUrl, String query, ServerMetricsPayload.Window window, long stepMillis) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        int q = base.indexOf('?');
        String path = q < 0 ? base : base.substring(0, q);
        String carried = q < 0 ? "" : base.substring(q + 1) + "&";
        String qs = "query=" + encode(query)
                + "&start=" + formatSeconds(window.getStart())
                + "&end=" + formatSeconds(window.getEnd())
                + "&step=" + formatStep(stepMillis);
        return URI.create(path + "/query_range?" + carried + qs);
    }

    static String formatSeconds(long millis) {
        return millis % 1000 == 0
                ? String.valueOf(millis / 1000)
                : String.format(Locale.ROOT, "%d.%03d", millis / 1000, millis % 1000);
    }

    static String formatStep(long stepMillis) {
        return stepMillis % 1000 == 0 ? (stepMillis / 1000) + "s" : stepMillis + "ms";
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required of every Java platform", e);
        }
    }

    /**
     * The auth headers for a source, from its {@code auth.*Env} references.
     *
     * <p>{@code bearerTokenEnv} → {@code Authorization: Bearer}, {@code usernameEnv} +
     * {@code passwordEnv} → {@code Authorization: Basic}. {@code wir-collect} only ever sets
     * {@code bearerTokenEnv}; anything else is refused here through
     * {@link ServerSource#authProblem}, so a source built some other way cannot silently ignore a
     * reference and fail with a 401 that sends the reader to their credentials.
     */
    Map<String, String> headersFor(ServerSource source) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        Map<String, String> auth = source.authEnv();
        if (auth.isEmpty()) {
            return headers;
        }
        String problem = ServerSource.authProblem(auth);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        if (auth.containsKey("bearerTokenEnv")) {
            headers.put("Authorization", "Bearer " + secret(auth.get("bearerTokenEnv")));
        } else {
            String pair = secret(auth.get("usernameEnv")) + ":" + secret(auth.get("passwordEnv"));
            headers.put("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        return headers;
    }

    private String secret(String envVar) {
        String value = secrets.get(envVar);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("environment variable " + envVar + " is not set");
        }
        return value;
    }

    private static long lastTimestamp(List<ServerMetricsPayload.InstanceRecord> instances) {
        long last = Long.MIN_VALUE;
        for (ServerMetricsPayload.InstanceRecord inst : instances) {
            List<ServerMetricsPayload.Point> points = inst.getPoints();
            if (!points.isEmpty()) {
                last = Math.max(last, points.get(points.size() - 1).getTs());
            }
        }
        return last;
    }

    /** The last few slots of every instance, as text, so two polls can be compared cheaply. */
    private static String tailSignature(List<ServerMetricsPayload.InstanceRecord> instances,
                                        ServerMetricsPayload.Window window, long stepMillis) {
        long from = window.getEnd() - 4 * stepMillis;
        StringBuilder sb = new StringBuilder();
        for (ServerMetricsPayload.InstanceRecord inst : instances) {
            sb.append(inst.getLabels()).append(':');
            for (ServerMetricsPayload.Point p : inst.getPoints()) {
                if (p.getTs() >= from) {
                    sb.append(p.getTs()).append('=').append(p.getValue()).append(',');
                }
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String one = body.replaceAll("\\s+", " ").trim();
        return ": " + (one.length() > 200 ? one.substring(0, 200) + "..." : one);
    }
}
