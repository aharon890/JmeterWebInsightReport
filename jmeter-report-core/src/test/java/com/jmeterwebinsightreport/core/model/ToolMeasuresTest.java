package com.jmeterwebinsightreport.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.adapter.k6.K6Results;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.rundata.RunDataReader;
import com.jmeterwebinsightreport.core.rundata.RunSample;
import com.jmeterwebinsightreport.core.rundata.ToolRecords;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool's own measures, the k6 tab's numbers (spec 001, FR-008, task T054).
 *
 * <p>Each distribution goes through the engine's exact aggregator, so a percentile is the Aggregate Report's: the value
 * at rank {@code round(n * p)}. The columns are k6's summary columns: avg, min, med, max, p(90), p(95). The hand-built
 * cases pin that rule; the K1 cases hold the figures that do not depend on it (counts, min, max, avg, checks, thresholds,
 * counters) to k6's own end-of-test summary.
 */
class ToolMeasuresTest {

    private static final File K1 = new File("../test-plans/run-data/K1");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private static RunSample pass(String group, long wallTime) {
        RunSample s = new RunSample();
        s.name = group;
        s.kind = RunSample.Kind.TRANSACTION;
        s.start = 1_795_452_359_384L;
        s.elapsed = 1L;
        s.success = true;
        s.wallTime = wallTime;
        return s;
    }

    private static RunSample request(String name, double blocked, double waiting) {
        RunSample s = new RunSample(name, 1_795_452_359_384L, Math.round(waiting), true);
        s.timing("blocked", blocked);
        s.timing("connecting", 0);
        s.timing("tlsHandshaking", 0);
        s.timing("sending", 0.02);
        s.timing("waiting", waiting);
        s.timing("receiving", 0.08);
        return s;
    }

    @Test
    void groupWallTimeIsSummarisedInK6sColumnsByTheAggregateReportsRule() {
        ToolMeasures m = new ToolMeasures(new ToolRecords());
        for (long w : new long[] {50, 10, 40, 20, 30}) {
            m.add(pass("Checkout", w));
        }
        ToolMeasures.Trend t = m.groupDurations().get("Checkout");
        assertNotNull(t, "a group's passes give it a row");
        assertEquals(5, t.count());
        assertEquals(30.0, t.avg(), 1e-9);
        assertEquals(10.0, t.min());
        assertEquals(30.0, t.med(), "the 3rd of 5: round(5 x 0.5)");
        assertEquals(50.0, t.max());
        assertEquals(40.0, t.p90(), "the 4th of 5: round(5 x 0.9f), and 0.9f is just under 0.9");
        assertEquals(50.0, t.p95(), "the 5th of 5");
    }

    @Test
    void onlyAToolsGroupPassesWithAWallTimeAreGroupDurations() {
        ToolMeasures m = new ToolMeasures(new ToolRecords());
        RunSample cutOff = pass("Checkout", 70);
        cutOff.kind = RunSample.Kind.REQUEST; // a pass the test's end cut off: k6 wrote no group_duration for it
        m.add(cutOff);
        RunSample jmeterTransaction = pass("Checkout", 0);
        jmeterTransaction.wallTime = null; // JMeter's Transaction Controller gives elapsed and idle, not a wall time
        m.add(jmeterTransaction);
        assertTrue(m.groupDurations().isEmpty(), "neither is a group duration: " + m.groupDurations().keySet());
        assertTrue(m.isEmpty());
    }

    @Test
    void theSixTimingPartsKeepHundredthsOfAMillisecondPerRequestAndOverAll() {
        ToolMeasures m = new ToolMeasures(new ToolRecords());
        m.add(request("Login", 0.15, 31.29));
        m.add(request("Login", 0.25, 31.40));
        m.add(request("Login", 0.00, 31.44));
        m.add(request("Logout", 0.50, 2.00));

        Map<String, ToolMeasures.Trend> login = m.timingsByRequest().get("Login");
        assertEquals(ToolMeasures.TIMING_PARTS, new ArrayList<>(login.keySet()), "k6's order, every part");
        ToolMeasures.Trend waiting = login.get("waiting");
        assertEquals(3, waiting.count());
        assertEquals((31.29 + 31.40 + 31.44) / 3, waiting.avg(), 1e-9);
        assertEquals(31.29, waiting.min(), 1e-9);
        assertEquals(31.40, waiting.med(), 1e-9, "the 2nd of 3: round(1.5)");
        assertEquals(31.44, waiting.p90(), 1e-9);
        assertEquals(31.44, waiting.p95(), 1e-9);
        assertEquals(31.44, waiting.max(), 1e-9);
        assertEquals(0.15, login.get("blocked").med(), 1e-9, "a part below one millisecond is not read as 0");

        ToolMeasures.Trend all = m.timingsOverAll().get("blocked");
        assertEquals(4, all.count(), "every request, whatever its name");
        assertEquals(0.50, all.max(), 1e-9);
        assertEquals((0.15 + 0.25 + 0.00 + 0.50) / 4, all.avg(), 1e-9);
    }

    @Test
    void checksArePassesAndFailsPerGroupAndName() {
        ToolRecords records = new ToolRecords();
        records.addCheck(check("::A", "status is 200", true));
        records.addCheck(check("::A", "status is 200", true));
        records.addCheck(check("::A", "status is 200", false));
        records.addCheck(check("::B", "status is 200", false));
        ToolMeasures m = new ToolMeasures(records);
        List<ToolRecords.CheckCount> checks = m.records().checks();
        assertEquals(2, checks.size(), "one row per group and name");
        assertEquals("::A", checks.get(0).group);
        assertEquals(2, checks.get(0).passes);
        assertEquals(1, checks.get(0).fails);
        assertEquals(0, checks.get(1).passes);
        assertEquals(1, checks.get(1).fails);
        assertFalse(m.isEmpty());
    }

    private static ToolRecords.Check check(String group, String name, boolean passed) {
        ToolRecords.Check c = new ToolRecords.Check();
        c.group = group;
        c.name = name;
        c.passed = passed;
        return c;
    }

    // ----- K1 against k6's own end-of-test summary -----

    private ToolMeasures k1() throws Exception {
        File runData = tmp.resolve("K1-run-data.jsonl.gz").toFile();
        K6Results.toRunData(new File(K1, "results.csv.gz"), new File(K1, "summary.json"), "K1", runData);
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        engine.setApdexThreshold(JtlParser.DEFAULT_APDEX_THRESHOLD);
        return new RunDataReader().read(runData, engine).measures;
    }

    private static JsonNode summary() throws Exception {
        return JSON.readTree(new File(K1, "summary.json"));
    }

    @Test
    void k1sThresholdsAreK6sOwnEachWithItsResult() throws Exception {
        ToolMeasures m = k1();
        Map<String, Boolean> ours = new HashMap<>();
        for (ToolRecords.Threshold t : m.records().thresholds()) {
            ours.put(t.metric + " " + t.expression, t.passed);
        }
        Map<String, Boolean> k6 = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> metrics = summary().path("metrics").fields();
        while (metrics.hasNext()) {
            Map.Entry<String, JsonNode> e = metrics.next();
            e.getValue().path("thresholds").fields()
                    .forEachRemaining(t -> k6.put(e.getKey() + " " + t.getKey(), t.getValue().path("ok").asBoolean()));
        }
        assertEquals(k6, ours);
        assertTrue(ours.containsValue(false), "K1 fails one threshold on purpose");
    }

    @Test
    void k1sChecksAreK6sCountsPerGroupAndName() throws Exception {
        Map<String, String> ours = new HashMap<>();
        for (ToolRecords.CheckCount c : k1().records().checks()) {
            ours.put(c.group + "::" + c.name, c.passes + "/" + c.fails);
        }
        Map<String, String> k6 = new HashMap<>();
        collectChecks(summary().path("root_group"), k6);
        assertEquals(k6, ours);
    }

    private static void collectChecks(JsonNode group, Map<String, String> into) {
        for (JsonNode c : group.path("checks")) {
            into.put(c.path("path").asText(), c.path("passes").asLong() + "/" + c.path("fails").asLong());
        }
        for (JsonNode g : group.path("groups")) {
            collectChecks(g, into);
        }
    }

    @Test
    void k1sIterationsDroppedIterationsAndVusMaxAreK6s() throws Exception {
        ToolMeasures m = k1();
        JsonNode metrics = summary().path("metrics");
        assertEquals(metrics.path("iterations").path("values").path("count").asDouble(), m.counter("iterations"));
        assertEquals(0.0, m.counter("dropped_iterations"), "k6 leaves the metric out when nothing was dropped");
        assertEquals(metrics.path("vus_max").path("values").path("max").asDouble(), m.counter("vus_max"));
        assertNull(m.counter("no_such_counter"));
    }

    @Test
    void k1sTimingPartsOverEveryRequestAreK6sToTheHundredth() throws Exception {
        ToolMeasures m = k1();
        JsonNode metrics = summary().path("metrics");
        long requests = metrics.path("http_reqs").path("values").path("count").asLong();
        String[][] parts = {{"blocked", "http_req_blocked"}, {"connecting", "http_req_connecting"},
                {"tlsHandshaking", "http_req_tls_handshaking"}, {"sending", "http_req_sending"},
                {"waiting", "http_req_waiting"}, {"receiving", "http_req_receiving"}};
        for (String[] p : parts) {
            ToolMeasures.Trend t = m.timingsOverAll().get(p[0]);
            assertNotNull(t, p[0]);
            JsonNode k6 = metrics.path(p[1]).path("values");
            assertEquals(requests, t.count(), p[0] + ": every request k6 counted");
            // Each value is kept to the hundredth, so each figure k6 does not interpolate is within half of one
            assertEquals(k6.path("min").asDouble(), t.min(), 0.005, p[0] + " min");
            assertEquals(k6.path("max").asDouble(), t.max(), 0.005, p[0] + " max");
            assertEquals(k6.path("avg").asDouble(), t.avg(), 0.005, p[0] + " avg");
        }
    }

    @Test
    void k1sGroupDurationsAreItsGroupPassesOnTheirWallTime() throws Exception {
        ToolMeasures m = k1();
        long passes = 0;
        double min = Double.MAX_VALUE;
        double max = 0;
        double sum = 0;
        for (ToolMeasures.Trend t : m.groupDurations().values()) {
            passes += t.count();
            min = Math.min(min, t.min());
            max = Math.max(max, t.max());
            sum += t.avg() * t.count();
        }
        assertEquals(groupDurationRows(), passes, "one per group_duration row k6 wrote: no cut-off pass");
        JsonNode k6 = summary().path("metrics").path("group_duration").path("values");
        // A pass's wall time is whole milliseconds of the run's clock: within a millisecond either way of k6's figure
        assertEquals(k6.path("min").asDouble(), min, 1.0);
        assertEquals(k6.path("max").asDouble(), max, 1.0);
        assertEquals(k6.path("avg").asDouble(), sum / passes, 1.0);
        assertTrue(m.groupDurations().containsKey("Complete Checkout Flow"));
        assertTrue(m.groupDurations().containsKey("Inner Payment"), "a nested group has its own row");
    }

    /** The group_duration rows in K1's CSV, counted without the adapter. */
    private static long groupDurationRows() throws Exception {
        long n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(new File(K1, "results.csv.gz"))), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("group_duration,")) {
                    n++;
                }
            }
        }
        return n;
    }
}
