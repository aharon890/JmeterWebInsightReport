package com.jmeterwebinsightreport.core.servermetrics.collect;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure pieces of collection: parsing, coverage, redaction, interpolation. */
class CollectPureTest {

    static final String MATRIX = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
            + "{\"metric\":{\"pod\":\"a\"},\"values\":[[1700000000,\"1\"],[1700000015,\"2\"],[1700000030,\"NaN\"],[1700000045,\"4\"]]},"
            + "{\"metric\":{\"pod\":\"b\"},\"values\":[[1700000000,\"10\"],[1700000015.5,\"+Inf\"]]}]}}";

    @Test
    void aMatrixResponseBecomesInstancesAndNonFiniteSamplesBecomeGaps() {
        PrometheusResponse.Parsed p = PrometheusResponse.parse(MATRIX, 0);
        assertFalse(p.failed());
        assertEquals(2, p.instances().size());
        ServerMetricsPayload.InstanceRecord a = p.instances().get(0);
        assertEquals("a", a.getLabels().get("pod"));
        assertEquals(3, a.getPoints().size(), "the NaN sample is a gap, not a number");
        assertEquals(1700000000000L, a.getPoints().get(0).getTs());
        assertEquals(4.0, a.getPoints().get(2).getValue());
        ServerMetricsPayload.InstanceRecord b = p.instances().get(1);
        assertEquals(1, b.getPoints().size(), "+Inf is a gap");

        // The source's clock offset is applied on read, never guessed.
        assertEquals(1700000000000L - 1200, PrometheusResponse.parse(MATRIX, -1200)
                .instances().get(0).getPoints().get(0).getTs());
    }

    @Test
    void aPointIsWrittenAsAnArrayWithAWholeTimestampAndReadBack() throws Exception {
        // [ts, value] on the wire, the ts as a long: a double's 1.7E12 is exact but is neither
        // legible nor greppable, and a golden would freeze it.
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        ServerMetricsPayload.InstanceRecord inst = instance(1_700_000_001_000L, 2.5);
        String text = json.writeValueAsString(inst);
        assertTrue(text.contains("[[1700000001000,2.5]]"), text);
        ServerMetricsPayload.InstanceRecord back = json.readValue(text, ServerMetricsPayload.InstanceRecord.class);
        assertEquals(1_700_000_001_000L, back.getPoints().get(0).getTs());
        assertEquals(2.5, back.getPoints().get(0).getValue());
        // A payload written before the fix carried doubles; it still reads.
        ServerMetricsPayload.InstanceRecord old = json.readValue("{\"points\":[[1.7E12,1.0]]}",
                ServerMetricsPayload.InstanceRecord.class);
        assertEquals(1_700_000_000_000L, old.getPoints().get(0).getTs());
    }

    @Test
    void whatIsNotASuccessfulMatrixIsAFailureThatSaysWhy() {
        assertTrue(PrometheusResponse.parse("not json", 0).error().contains("not JSON"));
        // One line: the parser's sentence, never Jackson's "\n at [Source: ...]" location block.
        String truncated = PrometheusResponse.parse("{\"status\": \"success\", \"data\": [ broken", 0).error();
        assertFalse(truncated.contains("\n"), truncated);
        assertFalse(truncated.contains("[Source:"), truncated);
        assertTrue(PrometheusResponse.parse("[]", 0).error().contains("not a JSON object"));
        assertTrue(PrometheusResponse.parse("{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}", 0)
                .error().contains("bad_data: parse error"));
        assertTrue(PrometheusResponse.parse("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}", 0)
                .error().contains("query_range, not query"));
        PrometheusResponse.Parsed empty = PrometheusResponse.parse(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}", 0);
        assertFalse(empty.failed());
        assertTrue(empty.instances().isEmpty(), "no series matched: an answer, not a failure");
    }

    private static ServerMetricsPayload.InstanceRecord instance(double... points) {
        ServerMetricsPayload.InstanceRecord i = new ServerMetricsPayload.InstanceRecord();
        i.setLabels(new LinkedHashMap<String, String>());
        List<ServerMetricsPayload.Point> ps = new java.util.ArrayList<>();
        for (int k = 0; k < points.length; k += 2) {
            ps.add(new ServerMetricsPayload.Point((long) points[k], points[k + 1]));
        }
        i.setPoints(ps);
        return i;
    }

    @Test
    void coverageCountsDistinctStepSlotsInsideTheWindowAcrossInstances() {
        ServerMetricsPayload.Window w = new ServerMetricsPayload.Window(0, 60_000, null);
        // 5 expected slots at 15 s: 0, 15, 30, 45, 60.
        assertEquals(1.0, Coverage.of(Collections.singletonList(instance(0, 1, 15_000, 1, 30_000, 1, 45_000, 1, 60_000, 1)), w, 15_000));
        assertEquals(0.6, Coverage.of(Collections.singletonList(instance(0, 1, 15_000, 1, 30_000, 1)), w, 15_000));
        // Two instances covering different halves add up; the same slot twice counts once.
        assertEquals(1.0, Coverage.of(Arrays.asList(instance(0, 1, 15_000, 1, 30_000, 1), instance(30_000, 1, 45_000, 1, 60_000, 1)), w, 15_000));
        // Finer than declared cannot exceed 100%; points outside the window are ignored.
        assertEquals(1.0, Coverage.of(Collections.singletonList(instance(0, 1, 5_000, 1, 15_000, 1, 30_000, 1, 45_000, 1, 60_000, 1, 90_000, 1)), w, 15_000));
        assertEquals(0.0, Coverage.of(Collections.singletonList(instance(90_000, 1)), w, 15_000));
        assertTrue(Double.isNaN(Coverage.of(Collections.singletonList(instance(0, 1)), w, 0)));
        // Nearest slot, not floor: a grid that is a few hundred ms off the window's edges still
        // covers the edge slots — the JTL's edges are milliseconds, a scrape grid is seconds.
        ServerMetricsPayload.Window offGrid = new ServerMetricsPayload.Window(123, 60_456, null);
        assertEquals(1.0, Coverage.of(Collections.singletonList(instance(0, 1, 15_000, 1, 30_000, 1, 45_000, 1, 60_000, 1)), offGrid, 15_000));
        // But a full step away is not the edge slot: beyond the window, or a gap.
        assertEquals(0.8, Coverage.of(Collections.singletonList(instance(0, 1, 15_000, 1, 30_000, 1, 45_000, 1, 75_000, 1)), offGrid, 15_000));
    }

    @Test
    void theLongestGapIsTwoOrMoreEmptyStepsInARowNamedFromTheCoveredSlotBeforeToTheOneAfter() {
        // B1 (5.6): what a LOW_COVERAGE reason names. Window 0..120 s at 15 s: nine slots.
        ServerMetricsPayload.Window w = new ServerMetricsPayload.Window(0, 120_000, null);
        // One missed step is noise, as Grafana waits two evaluation intervals before NoData.
        assertNull(Coverage.longestGap(Collections.singletonList(
                instance(0, 1, 15_000, 1, 45_000, 1, 60_000, 1, 75_000, 1, 90_000, 1, 105_000, 1, 120_000, 1)), w, 15_000));
        // Slots 2..4 empty: from slot 1 (15 s) to slot 5 (75 s).
        assertArrayEquals(new long[] {15_000, 75_000}, Coverage.longestGap(Collections.singletonList(
                instance(0, 1, 15_000, 1, 75_000, 1, 90_000, 1, 105_000, 1, 120_000, 1)), w, 15_000));
        // At the start: from the window's start; at the end: to the window's end.
        assertArrayEquals(new long[] {0, 45_000}, Coverage.longestGap(Collections.singletonList(
                instance(45_000, 1, 60_000, 1, 75_000, 1, 90_000, 1, 105_000, 1, 120_000, 1)), w, 15_000));
        assertArrayEquals(new long[] {75_000, 120_000}, Coverage.longestGap(Collections.singletonList(
                instance(0, 1, 15_000, 1, 30_000, 1, 45_000, 1, 60_000, 1, 75_000, 1)), w, 15_000));
        // No point at all: the whole window.
        assertArrayEquals(new long[] {0, 120_000}, Coverage.longestGap(
                Collections.<ServerMetricsPayload.InstanceRecord>emptyList(), w, 15_000));
        // The union of instances: b fills what a leaves.
        assertNull(Coverage.longestGap(Arrays.asList(instance(0, 1, 30_000, 1, 60_000, 1, 90_000, 1, 120_000, 1),
                instance(15_000, 1, 45_000, 1, 75_000, 1, 105_000, 1)), w, 15_000));
        // Memory by points, not by window/step: 30 days at 1 ms is 2.6e9 slots, past an int (the review).
        ServerMetricsPayload.Window month = new ServerMetricsPayload.Window(0, 30L * 24 * 3600 * 1000, null);
        assertArrayEquals(new long[] {1, month.getEnd()}, Coverage.longestGap(
                Collections.singletonList(instance(0, 1, 1, 1)), month, 1));
    }

    @Test
    void aSeriesSpacingIsItsMostCommonGapTheSmallestOverItsInstances() {
        // What the step-against-spacing warning compares with --step: an export at the panel's
        // interval has every point a minute apart, however few gaps it has.
        assertEquals(60_000, Coverage.spacingMillis(Collections.singletonList(
                instance(0, 1, 60_000, 1, 120_000, 1, 240_000, 1, 300_000, 1))));
        // Per instance, then the smallest (5.9, N6): coverage counts the union of a series' slots, so a
        // 60 s instance beside a 15 s one leaves no slot empty - as Grafana's interval follows the
        // finest series, a sparse one does not coarsen the rest.
        assertEquals(15_000, Coverage.spacingMillis(Arrays.asList(
                instance(0, 1, 15_000, 1, 30_000, 1, 45_000, 1), instance(0, 1, 60_000, 1, 120_000, 1))));
        // A tie keeps the smaller spacing, as the export's period does (Conversion.period).
        assertEquals(15_000, Coverage.spacingMillis(Collections.singletonList(instance(0, 1, 15_000, 1, 45_000, 1))));
        // No two points, no spacing.
        assertEquals(0, Coverage.spacingMillis(Collections.singletonList(instance(0, 1))));
        assertEquals(0, Coverage.spacingMillis(Collections.<ServerMetricsPayload.InstanceRecord>emptyList()));
    }

    @Test
    void theCollectorsStatusFollowsTheFloor() {
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.CollectionStatus.OK, Collector.statusFor(1.0, 0.9));
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.CollectionStatus.PARTIAL, Collector.statusFor(0.95, 0.9));
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.CollectionStatus.INSUFFICIENT, Collector.statusFor(0.62, 0.9));
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.CollectionStatus.EMPTY, Collector.statusFor(Double.NaN, 0.9));
    }

    @Test
    void redactionStripsUserinfoAndMasksSecretLookingParameters() {
        assertEquals("https://prom.corp/api/v1", Redaction.url("https://alice:s3cret@prom.corp/api/v1"));
        assertEquals("https://prom.corp/api?token=***&q=up", Redaction.url("https://prom.corp/api?token=abc&q=up"));
        assertEquals("https://p/api?q=up&api_key=***&x=1", Redaction.url("https://p/api?q=up&api_key=K&x=1"));
        assertEquals("https://p/api?q=up", Redaction.url("https://p/api?q=up"), "nothing to redact, nothing changed");
        assertEquals("http://host/${env.x}?sig=***", Redaction.url("http://user:pw@host/${env.x}?sig=abc"),
                "a placeholder is not a URI yet; the textual rule still applies");
        assertNull(Redaction.url(null));
    }

    @Test
    void redactionStripsUserinfoUpToTheLastAtOfTheAuthority() {
        // A second '@' makes java.net.URI read the authority as registry-based, so getUserInfo() is
        // null — and this URL went into resolvedUrl whole, password included (M7d X review).
        assertEquals("https://prom.corp/api/v1", Redaction.url("https://bob:p@ssw0rd@prom.corp/api/v1"));
        // Not a URI at all: the textual rule cut at the FIRST '@', which left "ssw0rd@" behind.
        assertEquals("https://prom.corp/${env.X}", Redaction.url("https://bob:p@ssw0rd@prom.corp/${env.X}"));
        // java.net.URI throws NumberFormatException for an IPv4 byte past an int in an IPv6 literal.
        assertEquals("http://[::1.2.3.99999999999]/x", Redaction.url("http://u:pw@[::1.2.3.99999999999]/x"));
        // An '@' in the path or the query is not userinfo, on either path.
        assertEquals("https://h/a@b?owner=ops@corp.com", Redaction.url("https://h/a@b?owner=ops@corp.com"));
        assertEquals("https://h/${env.X}/a@b", Redaction.url("https://h/${env.X}/a@b"));
    }

    @Test
    void redactionByValueMasksASecretWhereverItIsWhateverTheParameterIsCalled() {
        // The name rule cannot see a secret in the path or under a parameter called `t`; the
        // collector knows the values it substituted, and this is the rule it applies with them.
        List<String> secrets = Arrays.asList("s3cr3t-canary", "", null, "pw");
        assertEquals("https://h/***/api?t=***&token=***", Redaction.url(
                Redaction.values("https://h/s3cr3t-canary/api?t=s3cr3t-canary&token=s3cr3t-canary", secrets)));
        assertEquals("HTTP 500 from https://h/?x=*** : body echoed ***",
                Redaction.values("HTTP 500 from https://h/?x=pw : body echoed s3cr3t-canary", secrets));
        assertEquals("untouched", Redaction.values("untouched", secrets));
        assertEquals("untouched", Redaction.values("untouched", Collections.<String>emptyList()));
        assertNull(Redaction.values(null, secrets));
    }

    @Test
    void theQueryRangeUrlIsLegibleToAPersonAndToEveryPrometheusCompatibleBackend() {
        ServerMetricsPayload.Window edges = new ServerMetricsPayload.Window(1_700_000_000_123L, 1_700_000_060_456L, null);
        String uri = PrometheusSource.queryRangeUri("https://p/api/v1/", "up{a=\"b\"}", edges, 15_000).toString();
        // Millisecond edges as decimals, never 1.700000000123E9; braces and quotes encoded; the
        // trailing slash folded.
        assertEquals("https://p/api/v1/query_range?query=up%7Ba%3D%22b%22%7D&start=1700000000.123&end=1700000060.456&step=15s", uri);
        // A step Prometheus's duration grammar cannot say in seconds is said in milliseconds.
        assertTrue(PrometheusSource.queryRangeUri("https://p", "up", edges, 1_500).toString().endsWith("&step=1500ms"));
        // A base URL that carries its own query string keeps it.
        assertEquals("https://p/api/v1/query_range?tenant=x&query=up&start=1700000000.123&end=1700000060.456&step=15s",
                PrometheusSource.queryRangeUri("https://p/api/v1?tenant=x", "up", edges, 15_000).toString());
        assertEquals("1700000000", PrometheusSource.formatSeconds(1_700_000_000_000L));
        assertEquals("1700000000.005", PrometheusSource.formatSeconds(1_700_000_000_005L));
    }

    @Test
    void interpolationResolvesThreeScopesAndRefusesASecretInAQuery() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("cluster", "eu-test");
        Map<String, String> run = new LinkedHashMap<>();
        run.put("runId", "abc");
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("TOKEN", "t0k");
        Interpolation.Scope scope = new Interpolation.Scope(env, run, secrets);

        assertEquals("up{cluster=\"eu-test\",run=\"abc\"}",
                Interpolation.resolve("up{cluster=\"${env.cluster}\",run=\"${run.runId}\"}", scope, Interpolation.Site.QUERY).text());
        Interpolation.Result url = Interpolation.resolve("https://h/?t=${secret.TOKEN}", scope, Interpolation.Site.URL);
        assertEquals("https://h/?t=t0k", url.text());
        assertEquals(Collections.singletonList("t0k"), url.secretsUsed(), "handed back so the collector can mask it by value");
        assertTrue(Interpolation.resolve("plain", scope, Interpolation.Site.URL).secretsUsed().isEmpty());
        assertTrue(Interpolation.resolve("up{t=\"${secret.TOKEN}\"}", scope, Interpolation.Site.QUERY).problem()
                .contains("not allowed in a query"));
        assertTrue(Interpolation.resolve("up{c=\"${env.nope}\"}", scope, Interpolation.Site.QUERY).problem()
                .contains("declared: cluster"));
        assertTrue(Interpolation.resolve("${run.nope}", scope, Interpolation.Site.QUERY).problem().contains("run variable"));
        assertTrue(Interpolation.resolve("${secret.MISSING}", scope, Interpolation.Site.URL).problem().contains("not set"));
        assertEquals("plain", Interpolation.resolve("plain", scope, Interpolation.Site.QUERY).text());
        // Anything else shaped like a placeholder is refused, not sent to the source as a literal
        // that matches nothing: a typo'd scope, the wrong case, a nested placeholder.
        assertTrue(Interpolation.resolve("up{c=\"${evn.cluster}\"}", scope, Interpolation.Site.QUERY).problem()
                .contains("${evn.cluster} is not a placeholder this collector resolves"));
        assertTrue(Interpolation.resolve("${ENV.cluster}", scope, Interpolation.Site.QUERY).failed());
        assertTrue(Interpolation.resolve("${env.${run.runId}}", scope, Interpolation.Site.QUERY).failed());
    }
}
