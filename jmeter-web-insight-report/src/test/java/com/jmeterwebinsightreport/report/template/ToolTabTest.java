package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.adapter.k6.K6Results;
import com.jmeterwebinsightreport.core.model.ToolMeasures;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.rundata.RunDataReader;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The k6 tab's data, {@code chartData.tool} (spec 002, data-model.md, task T042): what report.js draws the thresholds,
 * timings, checks and group durations from, each figure written once in Java.
 */
class ToolTabTest {

    private static final File RUN_DATA = new File("../test-plans/run-data");

    @TempDir
    Path tmp;

    private ToolMeasures measures(String id) throws Exception {
        File dir = new File(RUN_DATA, id);
        File runData = tmp.resolve(id + ".jsonl.gz").toFile();
        K6Results.toRunData(new File(dir, "results.csv.gz"), new File(dir, "summary.json"), id, runData);
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        engine.setApdexThreshold(JtlParser.DEFAULT_APDEX_THRESHOLD);
        return new RunDataReader().read(runData, engine).measures;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> threshold(Map<String, Object> block, String metric, String expression) {
        for (Map<String, Object> t : (List<Map<String, Object>>) block.get("thresholds")) {
            if (metric.equals(t.get("metric")) && expression.equals(t.get("expression"))) {
                return t;
            }
        }
        throw new AssertionError("no threshold " + metric + " " + expression);
    }

    @Test
    void aThresholdIsItsMetricTagsRequestAndTheValueK6JudgedItOn() throws Exception {
        Map<String, Object> block = ToolTab.block("k6", null, measures("K2"));
        Map<String, Object> login = threshold(block, "http_req_duration{name:Login}", "p(95)<=600");
        assertEquals("http_req_duration", login.get("base"));
        assertEquals("name:Login", login.get("tags"));
        assertEquals("Login", login.get("request"));
        assertEquals("p(95)", login.get("statistic"));
        assertEquals("<=", login.get("operator"));
        assertEquals(600.0, login.get("limit"));
        assertEquals(779.71796, login.get("measured"));
        assertEquals("trend", login.get("metricType"));
        assertEquals("time", login.get("metricContains"));
        assertEquals(false, login.get("passed"));
        assertEquals(false, login.get("alwaysTrue"));

        Map<String, Object> count = threshold(block, "http_reqs{name:Login}", "count>=0");
        assertEquals(true, count.get("alwaysTrue"), "k6's keep-the-numbers threshold");

        Map<String, Object> wholeRun = threshold(ToolTab.block("k6", null, measures("K1")), "http_req_failed", "rate<0.01");
        assertNull(wholeRun.get("request"), "tied to no single request");
        assertEquals("", wholeRun.get("tags"));
    }

    @Test
    void k2HasFiftyThresholdsOneFailedAndFortyOneAlwaysTrue() throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) ToolTab.block("k6", null, measures("K2"))
                .get("thresholds");
        assertEquals(50, all.size());
        assertEquals(1, all.stream().filter(t -> Boolean.FALSE.equals(t.get("passed"))).count());
        assertEquals(41, all.stream().filter(t -> Boolean.TRUE.equals(t.get("alwaysTrue"))).count());
    }

    @Test
    void tagsAreReadAsK6WritesThem() {
        assertEquals("Login", ToolTab.tagValue("name:Login", "name"));
        assertEquals("::Checkout", ToolTab.tagValue("group:::Checkout,name:Pay", "group"));
        assertEquals("Pay", ToolTab.tagValue("group:::Checkout,name:Pay", "name"));
        assertNull(ToolTab.tagValue("scenario:checkout", "name"));
        assertNull(ToolTab.tagValue("", "name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void timingsArePerRequestWithEveryStatisticAndK6sDuration() throws Exception {
        Map<String, Object> block = ToolTab.block("k6", null, measures("K2"));
        assertEquals(List.of("avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"), block.get("trendStatistics"),
                "K2's summary lists p(99)");
        List<Map<String, Object>> timings = (List<Map<String, Object>>) block.get("timings");
        assertEquals(12, timings.size(), "one row per request K2 timed");
        Map<String, Object> login = timings.stream().filter(t -> "Login".equals(t.get("request"))).findFirst()
                .orElseThrow();
        Map<String, Map<String, Object>> parts = (Map<String, Map<String, Object>>) login.get("parts");
        assertEquals(List.of("blocked", "connecting", "tlsHandshaking", "sending", "waiting", "receiving", "duration"),
                List.copyOf(parts.keySet()));
        Map<String, Object> waiting = parts.get("waiting");
        assertEquals(6L, waiting.get("count"));
        assertTrue(waiting.containsKey("p(99)") && waiting.containsKey("avg") && waiting.containsKey("p(90)"));
        Map<String, Object> every = (Map<String, Object>) block.get("timingsEveryRequest");
        assertEquals(57L, ((Map<String, Map<String, Object>>) every.get("parts")).get("duration").get("count"));

        List<String> k1 = (List<String>) ToolTab.block("k6", null, measures("K1")).get("trendStatistics");
        assertFalse(k1.contains("p(99)"), "K1's summary does not list p(99)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void checksGroupDurationsAndCountersAreThere() throws Exception {
        Map<String, Object> block = ToolTab.block("k6", "2.4.0", measures("K1"));
        assertEquals("k6", block.get("name"));
        assertEquals("2.4.0", block.get("version"));
        List<Map<String, Object>> checks = (List<Map<String, Object>>) block.get("checks");
        assertEquals(2, checks.size());
        assertTrue(checks.stream().allMatch(c -> c.containsKey("group") && c.containsKey("passes")));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) block.get("groupDurations");
        assertTrue(groups.stream().anyMatch(g -> "Complete Checkout Flow".equals(g.get("name"))));
        Map<String, Object> counters = (Map<String, Object>) block.get("counters");
        assertEquals(0.0, counters.get("dropped_iterations"), "k6 writes it only when it drops one: 0");
    }
}
