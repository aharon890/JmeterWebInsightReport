package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.CollectionStatus;
import com.jmeterwebinsightreport.core.servermetrics.KpiResult;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;
import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpiEvaluator;
import com.jmeterwebinsightreport.core.servermetrics.ServerMetricsConfig;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The file source, the Prometheus source's settle loop over a fake HTTP, and the collector. */
class SourcesAndCollectorTest {

    @TempDir
    Path dir;

    private static String matrix(String pod, long... secondsAndValues) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < secondsAndValues.length; i += 2) {
            if (values.length() > 0) values.append(',');
            values.append('[').append(secondsAndValues[i]).append(",\"").append(secondsAndValues[i + 1]).append("\"]");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"pod\":\""
                + pod + "\"},\"values\":[" + values + "]}]}}";
    }

    private static final ServerMetricsPayload.Window WINDOW =
            new ServerMetricsPayload.Window(1_700_000_000_000L, 1_700_000_060_000L, null);

    // ---- file source -----------------------------------------------------------

    @Test
    void aFileSourceReadsTheRecordingNamedByTheQuerysFingerprint() throws IOException {
        String query = "max by (pod) (lag)";
        Files.write(dir.resolve(FileSource.filenameFor(query)),
                matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3, 1_700_000_045L, 4, 1_700_000_060L, 5)
                        .getBytes(StandardCharsets.UTF_8));
        ServerSource src = ServerSource.file("rec", dir.toString(), 15_000, 0);

        SeriesSource.Answer a = new FileSource().collect(src, query, WINDOW);
        assertFalse(a.failed());
        assertEquals(1, a.instances().size());
        assertEquals(5, a.instances().get(0).getPoints().size());

        // A query nothing was recorded for is EMPTY (an answer), a missing directory is FAILED.
        assertTrue(new FileSource().collect(src, "other", WINDOW).instances().isEmpty());
        assertFalse(new FileSource().collect(src, "other", WINDOW).failed());
        assertTrue(new FileSource().collect(ServerSource.file("x", dir.resolve("nope").toString(), 15_000, 0), query, WINDOW)
                .error().contains("does not exist"));

        // An unreadable recording is FAILED and names the file.
        Files.write(dir.resolve(FileSource.filenameFor("broken")), "{".getBytes(StandardCharsets.UTF_8));
        assertTrue(new FileSource().collect(src, "broken", WINDOW).error().contains(FileSource.filenameFor("broken")));
    }

    // ---- prometheus source -----------------------------------------------------

    /** A scripted HTTP: one body per call, then the last one forever. */
    private static final class FakeHttp implements PrometheusSource.Http {
        final List<String> bodies;
        final List<URI> calls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        int status = 200;
        /** From this call on (1-based), the network is gone. */
        int failFrom = Integer.MAX_VALUE;

        FakeHttp(String... bodies) {
            this.bodies = new ArrayList<>(java.util.Arrays.asList(bodies));
        }

        @Override
        public PrometheusSource.Response get(URI uri, Map<String, String> h) throws IOException {
            calls.add(uri);
            headers.add(h);
            if (calls.size() >= failFrom) {
                throw new IOException("connection reset");
            }
            String body = bodies.get(Math.min(calls.size() - 1, bodies.size() - 1));
            return new PrometheusSource.Response(status, body);
        }
    }

    private static final String EMPTY_MATRIX = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";

    /** A clock that advances only when slept. */
    private static final class FakeTiming implements PrometheusSource.Timing {
        long now;
        final List<Long> sleeps = new ArrayList<>();

        FakeTiming(long now) { this.now = now; }

        @Override public long nowMillis() { return now; }
        @Override public void sleep(long millis) { sleeps.add(millis); now += millis; }
    }

    private static ServerSource prom(Map<String, String> authEnv) {
        return ServerSource.prometheus("prom", "https://prom.corp/api/v1", 15_000, 0, authEnv);
    }

    @Test
    void theSettleLoopWaitsTheFloorThenPollsUntilTheTailIsStable() {
        String early = matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3);
        String full = matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3, 1_700_000_045L, 4, 1_700_000_060L, 5);
        FakeHttp http = new FakeHttp(early, full, full);
        // Collection starts 5 s after the window ended: the floor is max(2×15s, 30s) = 30 s.
        FakeTiming timing = new FakeTiming(WINDOW.getEnd() + 5_000);
        PrometheusSource src = new PrometheusSource(http, timing, Collections.<String, String>emptyMap(),
                new PrometheusSource.Settle(600_000, 15_000, 2));

        SeriesSource.Answer a = src.collect(prom(Collections.<String, String>emptyMap()), "up", WINDOW);

        assertFalse(a.failed());
        assertEquals(5, a.instances().get(0).getPoints().size());
        assertEquals(25_000L, (long) timing.sleeps.get(0), "slept up to the floor first");
        // Poll 1 did not reach the end; poll 2 did but was the first sighting; poll 3 matched it.
        assertEquals(3, http.calls.size());
        assertEquals(java.util.Arrays.asList(25_000L, 15_000L, 15_000L), timing.sleeps);
        String uri = http.calls.get(0).toString();
        assertTrue(uri.startsWith("https://prom.corp/api/v1/query_range?query=up&start=1700000000&end=1700000060&step=15s"), uri);
    }

    @Test
    void theCapReturnsWhatArrivedAndTheCollectorCallsItPartial() {
        String early = matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3);
        FakeHttp http = new FakeHttp(early);
        FakeTiming timing = new FakeTiming(WINDOW.getEnd() + 60_000);   // past the floor already
        PrometheusSource src = new PrometheusSource(http, timing, Collections.<String, String>emptyMap(),
                new PrometheusSource.Settle(40_000, 15_000, 2));

        SeriesSource.Answer a = src.collect(prom(Collections.<String, String>emptyMap()), "up", WINDOW);
        assertFalse(a.failed(), "a cap is not a failure");
        assertEquals(3, a.instances().get(0).getPoints().size());
        assertTrue(http.calls.size() >= 2 && http.calls.size() <= 3, "polled until the deadline: " + http.calls.size());
        assertEquals(CollectionStatus.INSUFFICIENT, Collector.statusFor(Coverage.of(a.instances(), WINDOW, 15_000), 0.9));
    }

    @Test
    void maxWaitIsTheSourcesWholeBudgetNotEachSeriess() {
        // A target that goes away before the run's end (petstore F2's deleted pod, F5's database
        // outage) leaves every series of it short of the window's end, so none settles. With the cap
        // per series the collection waited --max-wait once per asserted series - 10 min each, serially:
        // Stage 3.4 stopped one. The docs promise "how long a source may take to settle" (Stage 6).
        String early = matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3);
        FakeHttp http = new FakeHttp(early);
        FakeTiming timing = new FakeTiming(WINDOW.getEnd() + 300_000);   // five minutes after the run
        PrometheusSource src = new PrometheusSource(http, timing, Collections.<String, String>emptyMap(),
                new PrometheusSource.Settle(600_000, 15_000, 2));
        ServerSource source = prom(Collections.<String, String>emptyMap());

        for (String query : new String[] {"a", "b", "c"}) {
            SeriesSource.Answer a = src.collect(source, query, WINDOW);
            assertFalse(a.failed(), query);
            assertEquals(3, a.instances().get(0).getPoints().size(), "what arrived, for " + query);
        }
        long waited = 0;
        for (long s : timing.sleeps) waited += s;
        assertTrue(waited <= 600_000, "three series waited " + waited + " ms in all; --max-wait is 600000");

        // Another source has its own budget.
        FakeHttp other = new FakeHttp(early);
        int before = timing.sleeps.size();
        new PrometheusSource(other, timing, Collections.<String, String>emptyMap(),
                new PrometheusSource.Settle(600_000, 15_000, 2))
                .collect(ServerSource.prometheus("other", "https://other.corp/api/v1", 15_000, 0,
                        Collections.<String, String>emptyMap()), "a", WINDOW);
        assertTrue(timing.sleeps.size() > before, "a fresh source polls again");
    }

    @Test
    void anEmptyAnswerSettlesAfterTwoIdenticalPollsInsteadOfWaitingOutTheCap() {
        // A selector that matches nothing has no tail to reach the window's end. Without this,
        // every typo'd query cost the full maxWait — ten minutes, serially.
        FakeHttp http = new FakeHttp(EMPTY_MATRIX);
        FakeTiming timing = new FakeTiming(WINDOW.getEnd() + 60_000);   // past the floor
        SeriesSource.Answer a = new PrometheusSource(http, timing, Collections.<String, String>emptyMap(),
                new PrometheusSource.Settle(600_000, 15_000, 2)).collect(prom(Collections.<String, String>emptyMap()), "up", WINDOW);
        assertFalse(a.failed());
        assertTrue(a.instances().isEmpty());
        assertEquals(2, http.calls.size(), "stable for two polls, then done");
        assertEquals(Collections.singletonList(15_000L), timing.sleeps);
    }

    @Test
    void aPollThatFailsAfterAnEarlierOneAnsweredReturnsWhatArrived() {
        String full = matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3, 1_700_000_045L, 4, 1_700_000_060L, 5);
        FakeHttp http = new FakeHttp(full);
        http.failFrom = 2;
        SeriesSource.Answer a = new PrometheusSource(http, new FakeTiming(WINDOW.getEnd() + 60_000),
                Collections.<String, String>emptyMap(), new PrometheusSource.Settle(600_000, 15_000, 2))
                .collect(prom(Collections.<String, String>emptyMap()), "up", WINDOW);
        assertFalse(a.failed(), "the network blinking once does not discard poll 1");
        assertEquals(5, a.instances().get(0).getPoints().size());

        // But a failure on the first poll is a failure: nothing arrived.
        FakeHttp dead = new FakeHttp(full);
        dead.failFrom = 1;
        assertTrue(new PrometheusSource(dead, new FakeTiming(WINDOW.getEnd() + 60_000), Collections.<String, String>emptyMap(),
                PrometheusSource.Settle.none()).collect(prom(Collections.<String, String>emptyMap()), "up", WINDOW)
                .error().contains("connection reset"));
    }

    @Test
    void aWindowEndingAfterThisMachinesClockIsRefusedBeforeAskingNamingTheOffset() {
        // The JMeter host's clock is ahead: every query would wait out --max-wait (10 min each) and
        // come back empty, the wait Stage 3.4 saw. Every other test here builds its clock at or past
        // the window's end, so the refusal, its tolerance and the offset it names had no pin (Stage 6).
        String full = matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3, 1_700_000_045L, 4, 1_700_000_060L, 5);
        Map<String, String> none = Collections.<String, String>emptyMap();

        FakeHttp http = new FakeHttp(full);
        SeriesSource.Answer a = new PrometheusSource(http, new FakeTiming(WINDOW.getEnd() - 300_000), none,
                PrometheusSource.Settle.none()).collect(prom(none), "up", WINDOW);
        assertTrue(a.failed());
        assertEquals("the run's window ends 5m after this machine's clock - the JMeter host's clock is ahead;"
                + " pass --clock-offset 5m or collect after the window has passed", a.error());
        assertEquals(0, http.calls.size(), "refused before asking");

        // That offset given, the same window is in the past of the source's clock, and asked for.
        FakeHttp offset = new FakeHttp(full);
        assertFalse(new PrometheusSource(offset, new FakeTiming(WINDOW.getEnd() - 300_000), none,
                PrometheusSource.Settle.none())
                .collect(ServerSource.prometheus("prom", "https://prom.corp/api/v1", 15_000, 300_000, none), "up", WINDOW)
                .failed());
        assertEquals(1, offset.calls.size());

        // A minute ahead is tolerated (clocks drift), and asked for; a second more is not.
        FakeHttp minute = new FakeHttp(full);
        assertFalse(new PrometheusSource(minute, new FakeTiming(WINDOW.getEnd() - 60_000), none,
                PrometheusSource.Settle.none()).collect(prom(none), "up", WINDOW).failed());
        assertEquals(1, minute.calls.size());
        FakeHttp past = new FakeHttp(full);
        assertTrue(new PrometheusSource(past, new FakeTiming(WINDOW.getEnd() - 61_000), none,
                PrometheusSource.Settle.none()).collect(prom(none), "up", WINDOW).failed());
        assertEquals(0, past.calls.size());
    }

    @Test
    void theWindowIsRequestedInTheSourcesOwnClock() {
        // clockOffsetMs is what must be ADDED to a source timestamp to get JTL time, so the
        // request subtracts it; otherwise the source answers its own [start, end], which after
        // the offset lands [start+30s, end+30s] and the first 30 s of the window is uncovered.
        ServerSource skewed = ServerSource.prometheus("prom", "https://prom.corp/api/v1", 15_000, 30_000,
                Collections.<String, String>emptyMap());
        FakeHttp http = new FakeHttp(matrix("a", 1_699_999_970L, 1, 1_699_999_985L, 2, 1_700_000_000L, 3,
                1_700_000_015L, 4, 1_700_000_030L, 5));
        SeriesSource.Answer a = new PrometheusSource(http, new FakeTiming(WINDOW.getEnd() + 60_000),
                Collections.<String, String>emptyMap(), PrometheusSource.Settle.none()).collect(skewed, "up", WINDOW);
        String uri = http.calls.get(0).toString();
        assertTrue(uri.contains("start=1699999970&end=1700000030"), uri);
        assertEquals(1.0, Coverage.of(a.instances(), WINDOW, 15_000), "the whole JTL window, after the offset");
    }

    @Test
    void theLiveWiringSpeaksHttpToARealServer() throws Exception {
        // The production Http: JDK HttpClient against the JDK's own server, so the header loop,
        // the Accept header and the query string are exercised by a real socket, not a fake.
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        List<String> seenAuth = new ArrayList<>();
        List<String> seenQuery = new ArrayList<>();
        server.createContext("/api/v1/query_range", exchange -> {
            seenAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            seenQuery.add(exchange.getRequestURI().getRawQuery());
            byte[] body = matrix("a", 1_700_000_000L, 1).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Map<String, String> secrets = new LinkedHashMap<>();
            secrets.put("PROM_TOKEN", "t0k3n");
            Map<String, String> bearer = new LinkedHashMap<>();
            bearer.put("bearerTokenEnv", "PROM_TOKEN");
            ServerSource src = ServerSource.prometheus("prom",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", 15_000, 0, bearer);

            SeriesSource.Answer a = PrometheusSource.live(secrets, PrometheusSource.Settle.none()).collect(src, "up", WINDOW);

            assertFalse(a.failed(), a.error());
            assertEquals(1, a.instances().size());
            assertEquals(Collections.singletonList("Bearer t0k3n"), seenAuth);
            assertEquals(Collections.singletonList("query=up&start=1700000000&end=1700000060&step=15s"), seenQuery);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRefusedConnectionSaysSoInsteadOfFailedNull() throws Exception {
        // R3-16. The JDK client reports a refused connection as a ConnectException with a null
        // message, caused by another, caused by a ClosedChannelException with none either - so
        // "failed: " + getMessage() wrote "failed: null" into the payload, the Server tab and the
        // CI/CD JSON for the commonest source failure there is. A real socket, because the null
        // chain is the JDK's own and a fake would only test what we think it throws.
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        ServerSource closed = ServerSource.prometheus("dead", "http://127.0.0.1:" + port + "/api/v1", 15_000, 0,
                Collections.<String, String>emptyMap());
        SeriesSource.Answer a = PrometheusSource.live(Collections.<String, String>emptyMap(), PrometheusSource.Settle.none())
                .collect(closed, "up", WINDOW);

        // Only what holds on every platform: which chain the JDK builds for a refused connection is
        // the OS's choice (a message-less chain here on Windows; "Connection refused" copied onto
        // the outer exception on Linux), and describe() names the type either way.
        assertTrue(a.failed());
        assertTrue(a.error().contains(" failed: "), a.error());
        String reason = a.error().substring(a.error().indexOf(" failed: ") + " failed: ".length());
        assertFalse(reason.trim().isEmpty(), a.error());
        assertFalse(reason.equals("null") || a.error().endsWith("null"), a.error());
        assertTrue(reason.contains("Exception"), "the failure names its kind: " + a.error());

        // And the rule in full, on the chains it has to read: the outer type always leads, then the
        // first message anywhere in the chain; none at all names the type and what it means.
        assertEquals("ConnectException: Connection refused", PrometheusSource.describe(
                new java.net.ConnectException("Connection refused")), "the Linux shape names its kind too");
        assertEquals("IOException: connection reset", PrometheusSource.describe(new IOException("connection reset")));
        assertEquals("IOException: Connection refused", PrometheusSource.describe(
                new IOException(null, new java.net.ConnectException("Connection refused"))));
        assertTrue(PrometheusSource.describe(new java.net.ConnectException()).startsWith("could not connect (ConnectException)"));
        assertEquals("IOException (the HTTP client gave no detail)", PrometheusSource.describe(new IOException((String) null)));
    }

    @Test
    void noWaitMeansOneFetch() {
        FakeHttp http = new FakeHttp(matrix("a", 1_700_000_000L, 1));
        FakeTiming timing = new FakeTiming(WINDOW.getEnd());
        SeriesSource.Answer a = new PrometheusSource(http, timing, Collections.<String, String>emptyMap(),
                PrometheusSource.Settle.none()).collect(prom(Collections.<String, String>emptyMap()), "up", WINDOW);
        assertFalse(a.failed());
        assertEquals(1, http.calls.size());
        assertTrue(timing.sleeps.isEmpty());
    }

    @Test
    void authIsByReferenceIntoHeadersAndNeverIntoTheUrl() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("PROM_TOKEN", "t0k3n");
        secrets.put("U", "alice");
        secrets.put("P", "pw");
        FakeHttp http = new FakeHttp(matrix("a", 1_700_000_000L, 1));
        PrometheusSource src = new PrometheusSource(http, new FakeTiming(WINDOW.getEnd()), secrets,
                PrometheusSource.Settle.none());

        Map<String, String> bearer = new LinkedHashMap<>();
        bearer.put("bearerTokenEnv", "PROM_TOKEN");
        src.collect(prom(bearer), "up", WINDOW);
        assertEquals("Bearer t0k3n", http.headers.get(0).get("Authorization"));
        assertFalse(http.calls.get(0).toString().contains("t0k3n"));

        Map<String, String> basic = new LinkedHashMap<>();
        basic.put("usernameEnv", "U");
        basic.put("passwordEnv", "P");
        src.collect(prom(basic), "up", WINDOW);
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("alice:pw".getBytes(StandardCharsets.UTF_8)),
                http.headers.get(1).get("Authorization"));

        Map<String, String> unset = new LinkedHashMap<>();
        unset.put("bearerTokenEnv", "NOPE");
        assertTrue(src.collect(prom(unset), "up", WINDOW).error().contains("NOPE is not set"));
        // The mapper refuses these at load time through the same ServerSource.authProblem; a
        // source built some other way is refused here with the same sentence.
        Map<String, String> unknown = new LinkedHashMap<>();
        unknown.put("cookieEnv", "U");
        assertTrue(src.collect(prom(unknown), "up", WINDOW).error().contains("cookieEnv is not understood"));
        Map<String, String> half = new LinkedHashMap<>();
        half.put("usernameEnv", "U");
        assertTrue(src.collect(prom(half), "up", WINDOW).error().contains("both usernameEnv and passwordEnv"));
    }

    @Test
    void aSecretInterpolatedIntoTheUrlReachesTheRequestAndNothingElse() throws Exception {
        // The blocker the M3 review found: ${secret.*} in the path, or in a parameter not called
        // token/key/..., went into resolvedUrl and every error text verbatim, and from there into
        // every HTML report. The request must carry it; no record may.
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("T", "s3cr3t-canary");
        secrets.put("PW", "hunter2-canary");
        FakeHttp http = new FakeHttp("{\"status\":\"error\",\"error\":\"denied for s3cr3t-canary\"}");
        http.status = 500;
        Map<String, String> basic = new LinkedHashMap<>();
        basic.put("usernameEnv", "T");
        basic.put("passwordEnv", "PW");
        ServerMetricsConfig config = ServerMetricsConfig.builder()
                .source(ServerSource.prometheus("prom", "https://h/${secret.T}/api/v1?t=${secret.T}", 15_000, 0, basic))
                .kpi(kpi("up", "prom", "up"))
                .build();
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.PROMETHEUS, new PrometheusSource(http, new FakeTiming(WINDOW.getEnd()),
                secrets, PrometheusSource.Settle.none()));

        ServerMetricsPayload p = new Collector(readers, secrets).collect(config, null,
                new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "2026-08-22T10:00:00Z");

        assertTrue(http.calls.get(0).toString().contains("/s3cr3t-canary/api/v1/query_range?t=s3cr3t-canary&"),
                "the request itself carries the secret: " + http.calls.get(0));
        String written = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p);
        assertFalse(written.contains("s3cr3t-canary"), written);
        assertFalse(written.contains("hunter2-canary"), written);
        ServerMetricsPayload.SourceRecord rec = p.getSources().get(0);
        assertEquals("https://h/***/api/v1?t=***", rec.getResolvedUrl());
        assertEquals("FAILED", rec.getStatus());
        // Prometheus's own error first, then the redacted URL it came from.
        assertTrue(rec.getError().startsWith("HTTP 500: "), rec.getError());
        assertTrue(rec.getError().contains("(https://h/***/api/v1/query_range?t=***"), rec.getError());
        assertTrue(rec.getError().contains("denied for ***"), "a body that echoes the secret is masked too: " + rec.getError());
        assertEquals(rec.getError(), p.find("prom", "up").getError());
    }

    @Test
    void aRunLongerThanPrometheusAnswersAtTheStepIsRefusedBeforeAskingWithTheStepThatFits() {
        // 4 h at --step 1s is 14,400 steps; Prometheus refuses more than 11,000 with a 400 after the
        // request. Said first, with the step that fits.
        ServerMetricsPayload.Window soak = new ServerMetricsPayload.Window(1_700_000_000_000L,
                1_700_000_000_000L + 4 * 3_600_000L, null);
        String why = PrometheusSource.live(java.util.Collections.<String, String>emptyMap(), PrometheusSource.Settle.none())
                .collect(ServerSource.prometheus(ServerSource.COLLECTION_ID, "http://127.0.0.1:9/api/v1", 1000, 0,
                        java.util.Collections.<String, String>emptyMap()),
                        new SeriesSource.Request("up", "c", null), soak).error();
        assertEquals("the run is 14400 steps long at --step 1s, and Prometheus answers at most 11000 steps per"
                + " range query - collect with --step 2s or coarser", why);
    }

    @Test
    void elevenThousandStepsAreAskedForAndOneMoreIsRefusedAsPrometheusRefusesIt() {
        // Prometheus refuses only when (end - start) / step > 11000, so 11,000 steps (11,001 points)
        // is answered: the pre-check refused it, in a sentence saying Prometheus would answer it.
        long start = 1_700_000_000_000L;
        for (long steps : new long[] {11_000, 11_001}) {
            ServerMetricsPayload.Window w = new ServerMetricsPayload.Window(start, start + steps * 1000, null);
            FakeHttp http = new FakeHttp(EMPTY_MATRIX);
            SeriesSource.Answer a = new PrometheusSource(http, new FakeTiming(w.getEnd() + 60_000),
                    Collections.<String, String>emptyMap(), PrometheusSource.Settle.none())
                    .collect(ServerSource.prometheus(ServerSource.COLLECTION_ID, "http://p/api/v1", 1000, 0,
                            Collections.<String, String>emptyMap()), new SeriesSource.Request("up", "c", null), w);
            if (steps == 11_000) {
                assertFalse(a.failed(), a.error());
                assertEquals(1, http.calls.size(), "asked");
            } else {
                assertEquals("the run is 11001 steps long at --step 1s, and Prometheus answers at most 11000 steps"
                        + " per range query - collect with --step 2s or coarser", a.error());
                assertTrue(http.calls.isEmpty(), "refused before asking");
            }
        }
    }

    @Test
    void aNon200SaysPrometheussOwnErrorAndA401NamesTheTokenFlag() throws Exception {
        java.net.URI uri = java.net.URI.create("http://p/api/v1/query_range?query=up");
        String bad = PrometheusSource.httpFailure(new PrometheusSource.Response(400,
                "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"1:5: parse error: unclosed left parenthesis\"}"), uri);
        assertTrue(bad.startsWith("HTTP 400: "), bad);
        assertTrue(bad.contains("unclosed left parenthesis"), bad);
        String auth = PrometheusSource.httpFailure(new PrometheusSource.Response(401, "Unauthorized"), uri);
        assertTrue(auth.contains("check the bearer token --prometheus-token-env names"), auth);
        assertTrue(auth.endsWith(": Unauthorized"), auth);
    }

    @Test
    void aFileSourcesRelativePathIsResolvedAgainstTheAnnotationsDirectory() throws IOException {
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        Files.write(recordings.resolve(FileSource.filenameFor("up")),
                matrix("a", 1_700_000_000L, 1).getBytes(StandardCharsets.UTF_8));
        ServerSource relative = ServerSource.file("rec", "rec", 15_000, 0);
        assertEquals(1, new FileSource(dir.toFile()).collect(relative, "up", WINDOW).instances().size(),
                "\"path\": \"rec\" means beside the annotations file, whatever the shell's cwd");
        assertTrue(new FileSource().collect(relative, "up", WINDOW).failed(), "against the cwd it is not there");
    }

    @Test
    void anHttpErrorIsAFailureThatNamesTheStatusAndARedactedUrl() {
        FakeHttp http = new FakeHttp("{\"status\":\"error\",\"error\":\"unauthorized\"}");
        http.status = 401;
        ServerSource withSecret = ServerSource.prometheus("prom", "https://prom.corp/api/v1?token=abc", 15_000, 0,
                Collections.<String, String>emptyMap());
        SeriesSource.Answer a = new PrometheusSource(http, new FakeTiming(WINDOW.getEnd()),
                Collections.<String, String>emptyMap(), PrometheusSource.Settle.none()).collect(withSecret, "up", WINDOW);
        assertTrue(a.failed());
        assertTrue(a.error().contains("HTTP 401"), a.error());
        assertTrue(a.error().contains("token=***"), a.error());
        assertFalse(a.error().contains("abc"), a.error());
    }

    // ---- collector ---------------------------------------------------------------

    private static ServerKpi kpi(String id, String source, String query) {
        return ServerKpi.builder(source, query).id(id).reducer(Reducer.parse("max")).gate("server")
                .fail(Predicate.of(Predicate.Op.GT, 800)).build();
    }

    @Test
    void theCollectorCollectsEachDistinctQueryOnceAndNamesEveryOutcome() throws Exception {
        String good = "max by (pod) (lag{cluster=\"eu-test\"})";
        Files.write(dir.resolve(FileSource.filenameFor(good)),
                matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3, 1_700_000_045L, 4, 1_700_000_060L, 5)
                        .getBytes(StandardCharsets.UTF_8));
        String sparse = "sparse";
        Files.write(dir.resolve(FileSource.filenameFor(sparse)),
                matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2).getBytes(StandardCharsets.UTF_8));

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("cluster", "eu-test");
        ServerMetricsConfig config = ServerMetricsConfig.builder()
                .source(ServerSource.file("rec", dir.toString(), 15_000, 0))
                .source(ServerSource.unusable("bad", "auth.bearerToken must name an env var"))
                .environment("TEST", vars)
                .kpi(kpi("lag", "rec", "max by (pod) (lag{cluster=\"${env.cluster}\"})"))
                .kpi(kpi("lag-again", "rec", "max by (pod) (lag{cluster=\"${env.cluster}\"})"))   // same query
                .kpi(kpi("sparse", "rec", sparse))
                .kpi(kpi("nothing", "rec", "unrecorded"))
                .kpi(kpi("typo", "rec", "up{c=\"${env.nope}\"}"))
                .kpi(kpi("on-bad", "bad", "up"))
                .kpi(kpi("unknown-src", "ghost", "up"))
                .kpi(ServerKpi.builder("rec", "parked").id("parked").enabled(false).reducer(Reducer.parse("max"))
                        .gate("server").fail(Predicate.of(Predicate.Op.GT, 1)).build())
                .build();

        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.FILE, new FileSource());
        Collector collector = new Collector(readers, Collections.<String, String>emptyMap());
        ServerMetricsPayload p = collector.collect(config, "TEST",
                new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "2026-08-22T10:00:00Z");

        assertEquals(ServerMetricsPayload.SCHEMA, p.getSchema());
        assertEquals("abcdef0123456789", p.getRunId());
        assertEquals("TEST", p.getEnvironment());
        assertNotNull(p.getCollectionId());
        assertEquals(16, p.getCollectionId().length());

        assertEquals(2, p.getSources().size());
        assertEquals("OK", p.getSources().get(0).getStatus());
        assertEquals("FAILED", p.getSources().get(1).getStatus());
        assertTrue(p.getSources().get(1).getError().contains("bearerToken"));

        // 5 distinct queries: the duplicate collapsed, the parked one skipped, the unknown source skipped.
        assertEquals(5, p.getSeries().size(), p.getSeries().toString());
        ServerMetricsPayload.SeriesRecord lag = p.find("rec", good);
        assertNotNull(lag, "found by (source, resolvedQuery)");
        assertEquals(CollectionStatus.OK, lag.collectionStatus());
        assertEquals(1.0, lag.getCoverage());
        assertEquals("max by (pod) (lag{cluster=\"${env.cluster}\"})", lag.getQuery(), "the query as written travels too");
        assertEquals(CollectionStatus.INSUFFICIENT, p.find("rec", sparse).collectionStatus());
        assertEquals(0.4, p.find("rec", sparse).getCoverage());
        assertEquals(CollectionStatus.EMPTY, p.find("rec", "unrecorded").collectionStatus());
        ServerMetricsPayload.SeriesRecord typo = p.find("rec", "up{c=\"${env.nope}\"}");
        assertEquals(CollectionStatus.FAILED, typo.collectionStatus());
        assertTrue(typo.getError().contains("${env.nope}"));
        ServerMetricsPayload.SeriesRecord onBad = p.find("bad", "up");
        assertEquals(CollectionStatus.FAILED, onBad.collectionStatus());
        assertNull(p.find("ghost", "up"), "nothing to ask an undeclared source");

        // Same inputs, same collectionId; a different collectedAt is a different collection.
        ServerMetricsPayload again = collector.collect(config, "TEST",
                new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "2026-08-22T10:00:00Z");
        assertEquals(p.getCollectionId(), again.getCollectionId());
        ServerMetricsPayload later = collector.collect(config, "TEST",
                new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "2026-08-22T11:00:00Z");
        assertFalse(p.getCollectionId().equals(later.getCollectionId()));
    }

    // ---- a KPI with no prometheus query --------------------------------------------

    /** What a source that fetches by query says of a KPI it has no query for. */
    static final String NO_QUERY = "acme.pool.used.pct has no prometheus query in the catalogue - export it to a"
            + " file and collect it with --csv";

    /**
     * One type, {@code acme}: {@code conns} has a prometheus query; {@code pool.used.pct} and
     * {@code waits} have none, only a tool's name for each, so their identity is the neutral one.
     */
    static Catalogue acme() throws IOException {
        return Catalogue.fromFiles(Collections.singletonMap("acme", new ObjectMapper().readTree(
                "{\"type\":\"acme\",\"version\":1,\"exporter\":\"acme_exporter\",\"metrics\":["
                        + "{\"name\":\"conns\",\"queries\":{\"prometheus\":\"acme_conns${match}\"}},"
                        + "{\"name\":\"pool.used.pct\",\"unit\":\"%\",\"names\":{\"telegraf\":null,"
                        + "\"datadog\":{\"metric\":\"acme.pool.pct\",\"kind\":\"gauge\",\"scale\":100}}},"
                        + "{\"name\":\"waits\",\"names\":{\"otel\":{\"metric\":\"acme.waits\",\"kind\":\"counter\"}}}]}")));
    }

    static final String DB_MATCH = "instance=\"db-1\"";

    /** An assert on component {@code db} naming {@code metric}, as the mapper builds one. */
    static ServerKpi catalogueKpi(String id, CatalogueMetric metric) {
        return ServerKpi.builder(ServerSource.COLLECTION_ID, metric.identity(DB_MATCH)).id(id).metric(metric.name())
                .unit(metric.unit()).reducer(metric.reducer()).gate("db")
                .fail(Predicate.of(Predicate.Op.GT, 90)).build();
    }

    @Test
    void aKpiWithNoPrometheusQueryIsFailedBySayingSoAndNeverSent() throws Exception {
        Catalogue acme = acme();
        ServerMetricsConfig config = ServerMetricsConfig.builder()
                .source(ServerSource.prometheus(ServerSource.COLLECTION_ID, "https://prom.corp/api/v1", 15_000, 0,
                        Collections.<String, String>emptyMap()))
                .gate(ServerGate.component("db", null, "acme", DB_MATCH))
                .kpi(catalogueKpi("db-conns", acme.type("acme").metric("conns")))
                .kpi(catalogueKpi("db-pool", acme.type("acme").metric("pool.used.pct")))
                .build();
        FakeHttp http = new FakeHttp(matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3,
                1_700_000_045L, 4, 1_700_000_060L, 5));
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.PROMETHEUS, new PrometheusSource(http, new FakeTiming(WINDOW.getEnd() + 60_000),
                Collections.<String, String>emptyMap(), PrometheusSource.Settle.none()));

        ServerMetricsPayload p = new Collector(readers, Collections.<String, String>emptyMap(), acme)
                .collect(config, null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");

        // One request went out: conns's query. Neither the asserted pool.used.pct nor waits, the
        // catalogue metric nobody asserted (D1), has anything to send.
        assertEquals(1, http.calls.size(), http.calls.toString());
        assertTrue(http.calls.get(0).toString().contains("query=acme_conns%7Binstance%3D%22db-1%22%7D&"),
                http.calls.get(0).toString());

        ServerMetricsPayload.SeriesRecord pool = p.findByQuery(ServerSource.COLLECTION_ID,
                "acme:pool.used.pct{instance=\"db-1\"}", "db");
        assertNotNull(pool, p.getSeries().toString());
        assertEquals(CollectionStatus.FAILED, pool.collectionStatus());
        assertEquals(NO_QUERY, pool.getError());
        assertEquals("pool.used.pct", pool.getMetric());
        // D27: one of two failed - the source is PARTIAL, and the payload is written all the same.
        assertEquals("PARTIAL", p.getSources().get(0).getStatus());
        assertEquals("1 of 2 series failed - first: " + NO_QUERY, p.getSources().get(0).getError());
        // waits failed too, and nobody asked for it: skipped as every D1 failure is.
        assertEquals(2, p.getSeries().size(), p.getSeries().toString());

        // The report judges the assert SOURCE_FAILED, in the collector's words.
        List<KpiResult> judged = new ServerKpiEvaluator().evaluate(config, p, null);
        assertEquals(NotJudged.SOURCE_FAILED, judged.get(1).notJudged());
        assertEquals(NO_QUERY, judged.get(1).notJudgedDetail());
    }

    @Test
    void aParkedCatalogueAssertsMetricIsStillCollectedOnceAndCostsTheSourceNothing() throws Exception {
        // "enabled": false parks an assert, never judged. Its catalogue metric is collected all the
        // same, as every metric of a declared component is (D1), for the picker; a parked custom
        // query is not (the TEST collection above), which is what the docs say of DISABLED.
        Catalogue acme = acme();
        CatalogueMetric conns = acme.type("acme").metric("conns");
        ServerMetricsConfig config = ServerMetricsConfig.builder()
                .source(ServerSource.prometheus(ServerSource.COLLECTION_ID, "https://prom.corp/api/v1", 15_000, 0,
                        Collections.<String, String>emptyMap()))
                .gate(ServerGate.component("db", null, "acme", DB_MATCH))
                .kpi(ServerKpi.builder(ServerSource.COLLECTION_ID, conns.identity(DB_MATCH)).id("db-conns")
                        .metric(conns.name()).unit(conns.unit()).reducer(conns.reducer()).gate("db").enabled(false)
                        .fail(Predicate.of(Predicate.Op.GT, 90)).build())
                .build();
        FakeHttp http = new FakeHttp(matrix("a", 1_700_000_000L, 1, 1_700_000_015L, 2, 1_700_000_030L, 3,
                1_700_000_045L, 4, 1_700_000_060L, 5));
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.PROMETHEUS, new PrometheusSource(http, new FakeTiming(WINDOW.getEnd() + 60_000),
                Collections.<String, String>emptyMap(), PrometheusSource.Settle.none()));

        ServerMetricsPayload p = new Collector(readers, Collections.<String, String>emptyMap(), acme)
                .collect(config, null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");

        assertEquals(1, http.calls.size(), http.calls.toString());
        assertTrue(http.calls.get(0).toString().contains("query=acme_conns%7Binstance%3D%22db-1%22%7D&"),
                http.calls.get(0).toString());
        assertEquals(1, p.getSeries().size(), p.getSeries().toString());
        assertEquals("conns", p.getSeries().get(0).getMetric());
        assertEquals("OK", p.getSources().get(0).getStatus(), p.getSources().get(0).getError());

        List<KpiResult> judged = new ServerKpiEvaluator().evaluate(config, p, null);
        assertEquals(NotJudged.DISABLED, judged.get(0).notJudged());
    }

    @Test
    void aRecordingCannotAnswerAKpiWithNoPrometheusQueryEvenUnderItsIdentitysDigest() throws IOException {
        // --recording replays what Prometheus answered a query. A neutral identity was never a
        // query, so a file named after its digest is not a recording of it.
        String identity = "acme:pool.used.pct";
        Files.write(dir.resolve(FileSource.filenameFor(identity)),
                matrix("a", 1_700_000_000L, 1).getBytes(StandardCharsets.UTF_8));
        ServerSource rec = ServerSource.file("rec", dir.toString(), 15_000, 0);
        SeriesSource.Request asked = new SeriesSource.Request(identity, "db", "pool.used.pct")
                .asNeutral("acme.pool.used.pct");
        assertTrue(asked.neutral());
        assertEquals(NO_QUERY, new FileSource().collect(rec, asked, WINDOW).error());
        // The same text asked as a query is replayed as ever.
        assertFalse(new SeriesSource.Request(identity, "db", "pool.used.pct").neutral());
        assertEquals(1, new FileSource().collect(rec, new SeriesSource.Request(identity, "db", "pool.used.pct"),
                WINDOW).instances().size());
    }

    @Test
    void anUnknownEnvironmentIsRefusedBeforeAnythingIsCollected() {
        ServerMetricsConfig config = ServerMetricsConfig.builder().environment("DEV", null).build();
        Collector collector = new Collector(new EnumMap<ServerSource.Type, SeriesSource>(ServerSource.Type.class),
                Collections.<String, String>emptyMap());
        Collector.CollectionException e = org.junit.jupiter.api.Assertions.assertThrows(
                Collector.CollectionException.class,
                () -> collector.collect(config, "PORD", new Collector.Run("r", "t", WINDOW), "now"));
        assertTrue(e.getMessage().contains("declared: DEV"));
    }
}
