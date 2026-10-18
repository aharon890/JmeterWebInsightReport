package com.jmeterwebinsightreport.core.assertion;

import com.jmeterwebinsightreport.core.model.OverallMetrics;
import com.jmeterwebinsightreport.core.model.PercentileSource;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A metric name, resolved to a number.
 *
 * <p>Names are case-insensitive. {@code p95} and {@code p95.0} are the same metric, and so
 * are {@code median} and {@code p50} — a tester should not have to know which spelling the
 * implementation happens to prefer.
 *
 * <h2>Where percentiles come from</h2>
 *
 * <p>For p50/p90/p95/p99 the value is read from {@link SamplerStatistics}, <em>not</em> from
 * the retained digest, even though the digest could answer. Computing the overall percentiles
 * merges every sampler's digest into one, and that merge force-compresses the sources: on a
 * 1..1000 ms ramp the same digest reports p99 as 991.0 before and 990.5 after. The stored
 * values were taken before the merge; the digest is read after it. Half a millisecond is
 * nothing as a measurement and decisive as a verdict — a threshold between the two makes the
 * report display PASS beside a number while the pipeline reports FAIL for the same named
 * metric. Reading the stored value keeps "what the report shows is what the gate judged" true
 * by construction, and gives up nothing: the quantiles only the digest can answer are exactly
 * the ones the report never displays.
 */
public final class MetricRef {

    /** What the number means, so the report can label it and the editor can seed a band. */
    public enum Unit {
        MILLISECONDS("ms"), PERCENT("%"), PER_SECOND("/s"), SECONDS("s"), COUNT(""), SCORE(""),
        /**
         * Kilobytes per second, because that is what the report displays.
         *
         * <p>The underlying figure is bytes/sec and the samplers table renders it as
         * {@code receivedBytesPerSec / 1024}. Naming the metric in bytes would have meant a
         * tester reading "976" off the Recv KB/s column and writing a threshold of 999424 —
         * assertable and displayed have to be the same number or the ⊕ beside that cell is a
         * trap. The division happens in {@link #valueFor}, once, on both sides.
         */
        KILOBYTES_PER_SECOND("KB/s");

        private final String suffix;

        Unit(String suffix) {
            this.suffix = suffix;
        }

        public String suffix() {
            return suffix;
        }
    }

    /** Grouping for the editor's metric picker. */
    public enum Family { LATENCY, ERRORS, VOLUME, QUALITY }

    private final String canonicalName;
    private final Unit unit;
    private final Family family;
    private final Double percentile;
    /** {@code class} or {@code code} for a qualified error metric; null otherwise. */
    private final String selectorKey;
    /** The class token, or the code glob. Null when there is no selector. */
    private final String selectorValue;
    /**
     * The compiled {@code code=} glob, compiled once at parse time.
     *
     * <p>Not compiled where it is used. {@code selectedErrors} iterates every error summary, so
     * compiling inside that loop rebuilt the same pattern once per (sampler, code) pair — tens of
     * thousands of times for one blanket rule. Worse, it was the only selector error that could
     * not be rejected at parse: {@code code=regex:[} passed validation, then threw
     * {@link java.util.regex.PatternSyntaxException} out of the evaluator, which catches that
     * only around target resolution. Report generation died — and only on runs that HAD errors,
     * because a clean run never enters the loop. A config typo that takes the report down on
     * precisely the runs worth reporting, in a feature whose stated principle is that a typo is
     * NOT_JUDGED / INVALID_RULE rather than anything else.
     */
    private final java.util.regex.Pattern selectorPattern;

    private MetricRef(String canonicalName, Unit unit, Family family, Double percentile) {
        this(canonicalName, unit, family, percentile, null, null, null);
    }

    private MetricRef(String canonicalName, Unit unit, Family family, Double percentile,
                      String selectorKey, String selectorValue,
                      java.util.regex.Pattern selectorPattern) {
        this.canonicalName = canonicalName;
        this.unit = unit;
        this.family = family;
        this.percentile = percentile;
        this.selectorKey = selectorKey;
        this.selectorValue = selectorValue;
        this.selectorPattern = selectorPattern;
    }

    /** True when this metric counts a subset of the errors rather than all of them. */
    public boolean hasSelector() {
        return selectorKey != null;
    }

    public String selectorKey() {
        return selectorKey;
    }

    public String selectorValue() {
        return selectorValue;
    }

    // ------------------------------------------------------------------ vocabulary

    private static final Map<String, MetricRef> NAMED = new LinkedHashMap<>();

    private static void define(String name, Unit unit, Family family) {
        NAMED.put(name.toLowerCase(Locale.ROOT), new MetricRef(name, unit, family, null));
    }

    static {
        define("sampleCount", Unit.COUNT, Family.VOLUME);
        define("errorCount", Unit.COUNT, Family.ERRORS);
        define("errorRate", Unit.PERCENT, Family.ERRORS);
        define("mean", Unit.MILLISECONDS, Family.LATENCY);
        define("min", Unit.MILLISECONDS, Family.LATENCY);
        define("max", Unit.MILLISECONDS, Family.LATENCY);
        define("stdDev", Unit.MILLISECONDS, Family.LATENCY);
        define("throughput", Unit.PER_SECOND, Family.VOLUME);
        define("apdex", Unit.SCORE, Family.QUALITY);
        // Already measured, already in the payload, already drawn on the timeline charts — and
        // until now unnameable, so "TTFB under 200 ms" could be watched and not gated. `latency`
        // is JMeter's Latency field (time to first byte) and `connectTime` its Connect; both are
        // means, like `mean`, because that is what the engine retains per sampler. A connect-time
        // regression is the signature of a TLS, DNS or pool problem and is invisible to a rule
        // that can only see `elapsed`.
        define("latency", Unit.MILLISECONDS, Family.LATENCY);
        define("connectTime", Unit.MILLISECONDS, Family.LATENCY);
        define("receivedKbPerSec", Unit.KILOBYTES_PER_SECOND, Family.VOLUME);
        define("sentKbPerSec", Unit.KILOBYTES_PER_SECOND, Family.VOLUME);
        define("totalSamples", Unit.COUNT, Family.VOLUME);
        define("duration", Unit.SECONDS, Family.VOLUME);
        define("maxThreads", Unit.COUNT, Family.VOLUME);
    }

    /**
     * Metrics an overall-scoped rule may name, excluding the percentile grid.
     *
     * <p>{@code median} is deliberately not here: {@link #vocabulary} appends the grid, which
     * already contributes {@code p50}, and the two are the same quantile. Listing both offered
     * the editor two names for one metric — pick the wrong one and the rule drew no chart line.
     */
    private static final List<String> OVERALL_NAMES = Collections.unmodifiableList(Arrays.asList(
            "totalSamples", "errorRate", "duration", "maxThreads", "mean", "throughput", "apdex"));

    /** Metrics a sampler-scoped rule may name, excluding the percentile grid. See above. */
    private static final List<String> SAMPLER_NAMES = Collections.unmodifiableList(Arrays.asList(
            "sampleCount", "errorCount", "errorRate", "mean", "min", "max", "stdDev",
            "throughput", "apdex", "latency", "connectTime",
            "receivedKbPerSec", "sentKbPerSec"));

    /** The percentile grid the payload always carries, so the editor can offer them. */
    public static final List<Double> STANDARD_GRID = Collections.unmodifiableList(Arrays.asList(
            50.0, 75.0, 90.0, 95.0, 99.0, 99.9));

    /**
     * Parse a metric name, or return {@code null} when it is not one.
     *
     * <p>Null rather than a throw: an unknown metric is a validation finding that names the
     * rule and suggests a correction, not an exception that stops the report being generated.
     * The report is the artifact you need in order to fix the config.
     */
    public static MetricRef parse(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);

        // A qualified error metric: errorRate:class=nonProtocol, errorCount:code=5*
        if (name.indexOf(':') >= 0) {
            return parseQualified(name);
        }

        if ("median".equals(lower)) {
            // Canonicalised to p50, not kept as "median". Both spellings are accepted — that
            // is the point of the alias — but they have to resolve to ONE name, because the
            // report's charts filter rules by `rule.metric === chartedMetric(...)` and a
            // string comparison cannot know the two are the same quantile. A rule written
            // `p50` simply never drew a line, while one written `median` did.
            return percentile(50.0, "p50");
        }
        MetricRef named = NAMED.get(lower);
        if (named != null) {
            return named;
        }
        if (lower.length() > 1 && lower.charAt(0) == 'p') {
            String number = lower.substring(1);
            try {
                double p = Double.parseDouble(number);
                // Below 0 or at/above 100 is not a percentile; p100 is max, which has its own
                // name and a t-digest cannot answer it reliably anyway.
                if (p > 0 && p < 100) {
                    return percentile(p, canonicalPercentileName(p));
                }
            } catch (NumberFormatException ignored) {
                // falls through to null — "pineapple" is not p-something
            }
        }
        return null;
    }

    /** Metrics that accept a selector, and the unit each keeps when it has one. */
    private static final List<String> SELECTABLE = Collections.unmodifiableList(
            Arrays.asList("errorRate", "errorCount"));

    /** The selector keys, in the spelling a message should suggest. */
    private static final List<String> SELECTOR_KEYS = Collections.unmodifiableList(
            Arrays.asList("class", "code"));

    /**
     * {@code errorRate:class=nonProtocol}, {@code errorCount:code=5*}.
     *
     * <p>Null on anything malformed, and {@link #problemWith} says which thing. Never a partial
     * match and never a fall-through to "all errors": a mistyped selector that quietly widened
     * the rule to every error would loosen a gate silently, which is the failure this whole
     * feature exists to prevent. A typo here is INVALID, and INVALID counts as a failure.
     */
    private static MetricRef parseQualified(String name) {
        int colon = name.indexOf(':');
        String base = name.substring(0, colon).trim();
        String qualifier = name.substring(colon + 1).trim();

        MetricRef baseRef = NAMED.get(base.toLowerCase(Locale.ROOT));
        if (baseRef == null || !SELECTABLE.contains(baseRef.canonicalName)) {
            return null;
        }
        int equals = qualifier.indexOf('=');
        if (equals <= 0 || equals == qualifier.length() - 1) {
            return null;
        }
        String key = qualifier.substring(0, equals).trim().toLowerCase(Locale.ROOT);
        String value = qualifier.substring(equals + 1).trim();
        if (value.isEmpty() || !SELECTOR_KEYS.contains(key)) {
            return null;
        }
        java.util.regex.Pattern pattern = null;
        if ("class".equals(key)) {
            // The class has a closed vocabulary, so a name outside it is a typo and nothing else.
            // A code glob cannot be checked the same way: "504" and "5*" are both legitimate and
            // a run with neither is a real answer, not a mistake — but it CAN be checked for
            // being a usable pattern, and must be, here rather than in the matching loop.
            String token = classToken(value);
            if (token == null) {
                return null;
            }
            value = token;
        } else {
            try {
                pattern = AssertionTarget.compile(value);
            } catch (java.util.regex.PatternSyntaxException e) {
                return null;
            }
        }
        return new MetricRef(baseRef.canonicalName + ":" + key + "=" + value,
                baseRef.unit, Family.ERRORS, null, key, value, pattern);
    }

    /** The canonical class token for a value written in any case, or null when unknown. */
    private static String classToken(String value) {
        for (com.jmeterwebinsightreport.core.model.ErrorClass c
                : com.jmeterwebinsightreport.core.model.ErrorClass.values()) {
            if (c.token().equalsIgnoreCase(value)) {
                return c.token();
            }
        }
        return null;
    }

    /**
     * Why a qualified metric name could not be read, in words that name the fix.
     *
     * <p>Separate from {@link #parse} because parse answers a yes/no question and a rejection
     * needs a sentence. Without this a mistyped selector inherits the generic "unknown metric"
     * message and a list of every valid name, which buries the one thing that is wrong. Returns
     * null when the name is not selector-shaped at all — then the generic message is right.
     */
    public static String problemWith(String raw) {
        if (raw == null || raw.indexOf(':') < 0) {
            return null;
        }
        String name = raw.trim();
        int colon = name.indexOf(':');
        String base = name.substring(0, colon).trim();
        String qualifier = name.substring(colon + 1).trim();

        // parse(), not NAMED.get(). NAMED holds only the fixed names — percentiles are resolved
        // arithmetically — so "p95:class=protocol" reported "'p95' is not a metric", which is
        // false and sends the reader to fix the wrong half of the expression. The browser said
        // "does not take a selector" and was right. Both sides now say that.
        //
        // The parity vector did not catch it: it asserted the substring "take a selector",
        // which "is not a metric, so it cannot take a selector" also contains. A vector that
        // passes against both the right answer and the wrong one is not testing anything.
        // `base` has no colon, so this cannot recurse.
        MetricRef baseRef = parse(base);
        if (baseRef == null) {
            return "'" + base + "' is not a metric, so it cannot take a selector. "
                    + "Only " + String.join(" and ", SELECTABLE) + " can.";
        }
        if (!SELECTABLE.contains(baseRef.canonicalName)) {
            return "'" + baseRef.canonicalName + "' does not take a selector — only "
                    + String.join(" and ", SELECTABLE) + " do, because only they count errors.";
        }
        int equals = qualifier.indexOf('=');
        if (equals <= 0 || equals == qualifier.length() - 1
                || qualifier.substring(equals + 1).trim().isEmpty()) {
            return "a selector must be written key=value, e.g. "
                    + baseRef.canonicalName + ":class=nonProtocol";
        }
        String key = qualifier.substring(0, equals).trim().toLowerCase(Locale.ROOT);
        String value = qualifier.substring(equals + 1).trim();
        if (!SELECTOR_KEYS.contains(key)) {
            return "unknown selector '" + qualifier.substring(0, equals).trim()
                    + "' — use class= or code=";
        }
        if ("code".equals(key)) {
            try {
                AssertionTarget.compile(value);
            } catch (java.util.regex.PatternSyntaxException e) {
                return "'" + value + "' is not a valid pattern: " + e.getDescription()
                        + ". A plain value is a glob where * is the only special character; "
                        + "prefix with regex: for a full expression.";
            }
            return null;
        }
        if ("class".equals(key) && classToken(value) == null) {
            return "unknown error class '" + value + "' — one of "
                    + com.jmeterwebinsightreport.core.model.ErrorClass.PROTOCOL.token() + ", "
                    + com.jmeterwebinsightreport.core.model.ErrorClass.NON_PROTOCOL.token() + ", "
                    + com.jmeterwebinsightreport.core.model.ErrorClass.TRANSPORT.token();
        }
        return null;
    }

    private static MetricRef percentile(double p, String canonical) {
        return new MetricRef(canonical, Unit.MILLISECONDS, Family.LATENCY, p);
    }

    /**
     * {@code 95.0 -> "p95"}, {@code 99.9 -> "p99.9"}, {@code 1.0E-10 -> "p1.0E-10"} — so p95 and
     * p95.0 print identically, and every name reads back as the percentile it names.
     *
     * <p>No trailing zero is trimmed. A trim "for a zero that came from parsing 99.90" stood here,
     * but the double 99.90 parses to is 99.9, and {@code Double.toString} writes the shortest
     * decimal that reads back as its double (JDK 19 and later, and this build targets 21): that
     * never ends in a zero but for the ".0" of a whole number, which returns above. The trim fired
     * only on an exponent ending in 0 and cut it: {@code p0.0000000001} was named {@code p1.0E-1},
     * which parses back as p0.1 (RB8 b) - the name the payload, the CI/CD JSON and JUnit carry.
     */
    private static String canonicalPercentileName(double p) {
        if (p == Math.rint(p)) {
            return "p" + (long) p;
        }
        return "p" + p;
    }

    /** Metric names valid for the given scope, for validation messages and the editor. */
    public static List<String> vocabulary(boolean overallScope) {
        List<String> names = new ArrayList<>(overallScope ? OVERALL_NAMES : SAMPLER_NAMES);
        for (Double p : STANDARD_GRID) {
            names.add(canonicalPercentileName(p));
        }
        return names;
    }

    public String canonicalName() {
        return canonicalName;
    }

    public Unit unit() {
        return unit;
    }

    public Family family() {
        return family;
    }

    /** The quantile this metric names, or {@code null} when it is not a percentile. */
    public Double percentile() {
        return percentile;
    }

    /** True when this metric can be asked of a whole run rather than one sampler. */
    public boolean validForOverall() {
        // A qualified metric inherits its base's scope. errorRate works whole-run, so
        // errorRate:class=transport does — and that is the rule most people will write, because
        // transport errors are not really a per-sampler concern.
        return percentile != null || OVERALL_NAMES.contains(baseName());
    }

    /** True when this metric can be asked of a single sampler. */
    public boolean validForSampler() {
        return percentile != null || SAMPLER_NAMES.contains(baseName());
    }

    // ------------------------------------------------------------------ resolution

    /**
     * The value for one sampler, or {@link Double#NaN} when this metric does not apply to it.
     *
     * <p>NaN rather than 0: zero is a legitimate measurement (no errors, no bytes) and a
     * predicate would happily judge it. NaN propagates as "no value", which the evaluator
     * turns into an unmatched result rather than a silent pass.
     */
    public double valueFor(SamplerStatistics stat, ReportData data) {
        if (stat == null) {
            return Double.NaN;
        }
        if (percentile != null) {
            return resolvePercentile(stat, data);
        }
        if (selectorKey != null) {
            return selectedErrors(stat.getSamplerName(), stat.getSampleCount(), data);
        }
        switch (canonicalName) {
            case "sampleCount": return stat.getSampleCount();
            case "errorCount":  return stat.getErrorCount();
            case "errorRate":   return stat.getErrorRate();
            case "mean":        return stat.getMeanResponseTime();
            case "min":         return stat.getMinResponseTime();
            case "max":         return stat.getMaxResponseTime();
            case "stdDev":      return stat.getStandardDeviation();
            case "throughput":  return stat.getThroughput();
            case "apdex":       return stat.getApdexScore();
            case "latency":     return stat.getMeanLatency();
            case "connectTime": return stat.getMeanConnectTime();
            // Divided here so the number a rule judges is the number the samplers table shows.
            case "receivedKbPerSec": return stat.getReceivedBytesPerSec() / 1024.0;
            case "sentKbPerSec":     return stat.getSentBytesPerSec() / 1024.0;
            default:            return Double.NaN;
        }
    }

    private double resolvePercentile(SamplerStatistics stat, ReportData data) {
        // Stored values win where they exist — see the class comment.
        double p = percentile;
        if (p == 50.0) return stat.getMedianResponseTime();
        if (p == 90.0) return stat.getPercentile90();
        if (p == 95.0) return stat.getPercentile95();
        if (p == 99.0) return stat.getPercentile99();

        PercentileSource dist = data == null ? null : data.percentileSource(stat.getSamplerName());
        if (dist != null && dist.sampleCount() > 0) {
            return dist.percentile(p);
        }
        // No digest and no stored value: this run genuinely cannot answer the question. Saying
        // so beats interpolating from p99, which would answer a tail-latency question with a
        // number that is not about the tail.
        return Double.NaN;
    }

    /** The value for the run as a whole. */
    public double valueForOverall(OverallMetrics overall, ReportData data) {
        if (overall == null) {
            return Double.NaN;
        }
        if (selectorKey != null) {
            // Top-level only, matching the population OverallMetrics counts. A transaction
            // child's errors are already inside its parent's, so summing every summary would
            // divide a child-inclusive numerator by a child-free denominator and could push the
            // rate past 100 — the same mistake buildErrorSummaries documents avoiding.
            return selectedErrors(null, overall.getTotalSamples(), data);
        }
        if (percentile != null) {
            return overall.percentile(percentile);
        }
        switch (canonicalName) {
            case "totalSamples": return overall.getTotalSamples();
            case "errorRate":    return overall.getErrorRate();
            case "duration":     return overall.getDurationSeconds();
            case "maxThreads":   return overall.getMaxThreads();
            case "mean":         return overall.getMean();
            case "throughput":   return overall.getThroughput();
            case "apdex":        return overall.getApdex();
            default:             return Double.NaN;
        }
    }

    /**
     * Errors matching this metric's selector, as a count or a rate.
     *
     * <p><strong>Zero is an answer, not a failure to answer.</strong> {@code code=404} on a run
     * with no 404s is 0, and a {@code lt} rule over it should pass cleanly — the question was
     * asked and answered "none". That is a different outcome from a malformed selector (INVALID,
     * refused at parse) and different again from a sampler that recorded nothing (routed through
     * the unmatched branch before this is ever called). All three look like "nothing there" and must not
     * collapse into one.
     *
     * <p>Reads {@code errorSummaries}, which is aggregated from an unbounded counter map — not
     * {@code errorRecords}, which is capped at 500. Using the capped list would make the number
     * depend on how many errors happened to be retained, and it is the same shape of bug as the
     * 200-match rule cap: correct until something unrelated changes it.
     *
     * @param samplerName the sampler to count for, or null for every top-level sampler
     */
    private double selectedErrors(String samplerName, long sampleCount, ReportData data) {
        if (data == null || data.getErrorSummaries() == null) {
            return 0;   // no errors at all is a real zero, for a count and for a rate alike
        }
        java.util.Set<String> topLevel = null;
        if (samplerName == null) {
            topLevel = new java.util.HashSet<>();
            if (data.getSamplerStatistics() != null) {
                for (SamplerStatistics s : data.getSamplerStatistics()) {
                    if (s.getParentSamplerName() == null) {
                        topLevel.add(s.getSamplerName());
                    }
                }
            }
        }

        long matched = 0;
        for (com.jmeterwebinsightreport.core.model.ErrorSummary es : data.getErrorSummaries()) {
            if (samplerName != null) {
                if (!samplerName.equals(es.getSamplerName())) {
                    continue;
                }
            } else if (!topLevel.contains(es.getSamplerName())) {
                continue;
            }
            if (matches(es)) {
                matched += es.getOccurrenceCount();
            }
        }

        if ("errorCount".equals(baseName())) {
            return matched;
        }
        return sampleCount > 0 ? (matched * 100.0) / sampleCount : 0;
    }

    /** The metric this one selects a subset of — {@code errorRate} or {@code errorCount}. */
    private String baseName() {
        int colon = canonicalName.indexOf(':');
        return colon < 0 ? canonicalName : canonicalName.substring(0, colon);
    }

    private boolean matches(com.jmeterwebinsightreport.core.model.ErrorSummary es) {
        if ("class".equals(selectorKey)) {
            return selectorValue.equals(es.getErrorClass());
        }
        // code=: a glob over the code string, because errorType IS the response code and JMeter
        // writes "Non HTTP response code: java.net.ConnectException" into it verbatim. Anything
        // richer would be the plugin inventing a taxonomy JMeter does not provide.
        return selectorPattern
                .matcher(es.getResponseCode() == null ? "" : es.getResponseCode()).matches();
    }

    /**
     * The closest known metric name to {@code raw}, for a "did you mean" message, or null when
     * nothing is close enough to be worth suggesting.
     */
    public static String nearestName(String raw, boolean overallScope) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String probe = raw.trim().toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : vocabulary(overallScope)) {
            int d = levenshtein(probe, candidate.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        // A suggestion further than a third of the typed length away is noise — "did you mean
        // apdex?" for "cpuUsage" helps nobody and undermines the messages that are right.
        return best != null && bestDistance <= Math.max(2, probe.length() / 3) ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    @Override
    public String toString() {
        return canonicalName;
    }
}
