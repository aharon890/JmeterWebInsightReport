package com.jmeterwebinsightreport.core.adapter.k6;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.rundata.RunDataReader;
import com.jmeterwebinsightreport.core.rundata.ToolRecords;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The k6 adapter (spec 001, decision 8, tasks T038-T040). Each committed k6 fixture's report, through run data, is
 * held row by row to the report its JMeter-format copy gives: that copy is written by the research's reference
 * converter, an independent translation that matched JMeter's own Aggregate Report in 198 of 198 referee checks.
 */
class K6ResultsTest {

    private static final File RUN_DATA_FIXTURES = new File("../test-plans/run-data");

    @TempDir
    Path tmp;

    private static class Read {
        ReportData data;
        RunDataReader.Result result;
        K6Results.Result conversion;
    }

    private Read throughTheAdapter(String id, boolean summary) throws Exception {
        File dir = new File(RUN_DATA_FIXTURES, id);
        File runData = tmp.resolve(id + "-run-data.jsonl.gz").toFile();
        Read r = new Read();
        r.conversion = K6Results.toRunData(new File(dir, "results.csv.gz"),
                summary ? new File(dir, "summary.json") : null, id, runData);
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        engine.setApdexThreshold(JtlParser.DEFAULT_APDEX_THRESHOLD);
        r.result = new RunDataReader().read(runData, engine);
        r.data = engine.buildReportData(id);
        return r;
    }

    private static ReportData throughTheReferenceCopy(String id) throws Exception {
        return new JtlParser().parse(new File(new File(RUN_DATA_FIXTURES, id), "results.jtl"), id);
    }

    private static Map<String, String> rows(ReportData d) {
        Map<String, String> m = new TreeMap<>();
        for (SamplerStatistics s : d.getSamplerStatistics()) {
            m.put(s.getSamplerName(), String.format(java.util.Locale.ROOT,
                    "n=%d err=%d mean=%.4f min=%.0f max=%.0f med=%.1f p90=%.1f p95=%.1f p99=%.1f rate=%.6f parent=%s",
                    s.getSampleCount(), s.getErrorCount(), s.getMeanResponseTime(), s.getMinResponseTime(),
                    s.getMaxResponseTime(), s.getMedianResponseTime(), s.getPercentile90(), s.getPercentile95(),
                    s.getPercentile99(), s.getThroughput(), s.getParentSamplerName()));
        }
        return m;
    }

    private static List<String> differences(ReportData expected, ReportData actual) {
        List<String> out = new ArrayList<>();
        Map<String, String> e = rows(expected);
        Map<String, String> a = rows(actual);
        if (!e.keySet().equals(a.keySet())) {
            out.add("rows: reference " + e.keySet() + " adapter " + a.keySet());
        }
        for (String name : e.keySet()) {
            if (a.containsKey(name) && !e.get(name).equals(a.get(name))) {
                out.add(name + ": reference [" + e.get(name) + "] adapter [" + a.get(name) + "]");
            }
        }
        if (expected.getTopLevelSampleCount() != actual.getTopLevelSampleCount()
                || expected.getTopLevelErrorCount() != actual.getTopLevelErrorCount()) {
            out.add("whole run: reference " + expected.getTopLevelSampleCount() + "/" + expected.getTopLevelErrorCount()
                    + " adapter " + actual.getTopLevelSampleCount() + "/" + actual.getTopLevelErrorCount());
        }
        if (!expected.getMetadata().getStartTime().equals(actual.getMetadata().getStartTime())
                || expected.getMetadata().getDurationMillis() != actual.getMetadata().getDurationMillis()) {
            out.add("window: reference " + expected.getMetadata().getStartTime() + " +"
                    + expected.getMetadata().getDurationMillis() + " adapter " + actual.getMetadata().getStartTime()
                    + " +" + actual.getMetadata().getDurationMillis());
        }
        return out;
    }

    @Test
    void everyShapeOfK1GivesTheReferenceCopysRows() throws Exception {
        Read k1 = throughTheAdapter("K1", true);
        List<String> diff = differences(throughTheReferenceCopy("K1"), k1.data);
        assertTrue(diff.isEmpty(), String.join("\n", diff));
        assertEquals(132, k1.conversion.requests);
        assertEquals(1, k1.conversion.cutOffPasses, "gracefulStop 0s cut one pass");
        assertEquals(0, k1.result.skipped, String.valueOf(k1.result.problems));
    }

    @Test
    void k1sGroupPassesAreTransactionsOutOfTheErrorsTab() throws Exception {
        ReportData d = throughTheAdapter("K1", true).data;
        // I2: no error record of a group pass, and the request count leaves the passes out
        assertTrue(d.getErrorRecords().stream().noneMatch(e -> e.getSamplerName().equals("Inner Payment")),
                "a complete pass's failure is its requests'");
        SamplerStatistics pay = d.getSamplerStatistics().stream().filter(s -> s.getSamplerName().equals("Pay"))
                .findFirst().orElseThrow();
        assertEquals("Inner Payment", pay.getParentSamplerName());
        SamplerStatistics inner = d.getSamplerStatistics().stream()
                .filter(s -> s.getSamplerName().equals("Inner Payment")).findFirst().orElseThrow();
        assertEquals("Complete Checkout Flow", inner.getParentSamplerName());
    }

    @Test
    void k1sOwnRecordsReachTheToolsTab() throws Exception {
        Read k1 = throughTheAdapter("K1", true);
        ToolRecords tool = k1.result.tool;
        assertEquals(3, tool.thresholds().size());
        assertTrue(tool.thresholds().stream().anyMatch(t -> t.metric.equals("http_req_failed") && !t.passed),
                "the failing threshold");
        assertEquals(2, tool.checks().size(), "two checks, by group and name");
        long passes = tool.checks().stream().mapToLong(c -> c.passes).sum();
        long fails = tool.checks().stream().mapToLong(c -> c.fails).sum();
        assertEquals(24, passes + fails, "one check record per check per pass");
        assertTrue(tool.counters().containsKey("iterations") && tool.counters().containsKey("vus_max"));
        assertEquals("k6", k1.result.header.toolName);
        assertTrue(k1.result.header.notMeasured.contains("bytes"));
        assertFalse(k1.result.header.notMeasured.contains("user"), "K1 has vu and iter");
    }

    private static ToolRecords.Threshold threshold(ToolRecords tool, String metric, String expression) {
        return tool.thresholds().stream().filter(t -> t.metric.equals(metric) && t.expression.equals(expression))
                .findFirst().orElseThrow(() -> new AssertionError("no threshold " + metric + " " + expression));
    }

    @Test
    void eachThresholdCarriesTheValueK6JudgedItOn() throws Exception {
        // spec 002, FR-050 and SC-006: the number k6 wrote, never one worked out again from the samples
        ToolRecords k2 = throughTheAdapter("K2", true).result.tool;
        ToolRecords.Threshold login = threshold(k2, "http_req_duration{name:Login}", "p(95)<=600");
        assertFalse(login.passed);
        assertEquals("p(95)", login.statistic);
        assertEquals(779.71796, login.measuredValue, 0.0);
        assertEquals("trend", login.metricType);
        assertEquals("time", login.metricContains);

        ToolRecords.Threshold count = threshold(k2, "http_reqs{name:Login}", "count>=0");
        assertEquals("count", count.statistic);
        assertEquals(6.0, count.measuredValue, 0.0);
        assertEquals("counter", count.metricType);
        assertEquals("default", count.metricContains);

        ToolRecords.Threshold gauge = threshold(k2, "request_window_ms{name:Login}", "value>=0");
        assertEquals("gauge", gauge.metricType);
        assertEquals(1795456091718.0, gauge.measuredValue, 0.0, "a custom gauge's bare number");

        ToolRecords k1 = throughTheAdapter("K1", true).result.tool;
        ToolRecords.Threshold failed = threshold(k1, "http_req_failed", "rate<0.01");
        assertEquals("rate", failed.statistic);
        assertEquals(0.18181818181818182, failed.measuredValue, 0.0);
        assertEquals("rate", failed.metricType);
        assertEquals("default", failed.metricContains);
    }

    @Test
    void aValueTheSummaryDoesNotHaveIsLeftOutNeverZero(@TempDir Path dir) throws Exception {
        File summary = dir.resolve("summary.json").toFile();
        java.nio.file.Files.writeString(summary.toPath(), "{\"metrics\":{"
                + "\"http_req_duration\":{\"type\":\"trend\",\"contains\":\"time\","
                + "\"values\":{\"avg\":5,\"p(95)\":9},\"thresholds\":{\"p(99)<300\":{\"ok\":true},\"p( 9 )<1\":{\"ok\":true}}},"
                + "\"custom\":{\"thresholds\":{\"value<1\":{\"ok\":false}}}}}");
        File runData = dir.resolve("r.jsonl").toFile();
        K6Results.toRunData(new File(new File(RUN_DATA_FIXTURES, "K1"), "results.csv.gz"), summary, "x", runData);
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        RunDataReader.Result r = new RunDataReader().read(runData, engine);
        ToolRecords.Threshold p99 = threshold(r.tool, "http_req_duration", "p(99)<300");
        assertEquals("p(99)", p99.statistic, "the statistic is the expression's, whatever the summary lists");
        assertEquals(null, p99.measuredValue, "summaryTrendStats did not list p(99)");
        assertEquals("trend", p99.metricType);
        ToolRecords.Threshold unreadable = threshold(r.tool, "http_req_duration", "p( 9 )<1");
        assertEquals(null, unreadable.statistic, "k6's grammar refuses it, so nothing is guessed");
        assertEquals(null, unreadable.measuredValue);
        ToolRecords.Threshold bare = threshold(r.tool, "custom", "value<1");
        assertEquals("value", bare.statistic);
        assertEquals(null, bare.measuredValue);
        assertEquals(null, bare.metricType);
        assertEquals(null, bare.metricContains);
        java.util.List<String> lines = java.nio.file.Files.readAllLines(runData.toPath());
        assertTrue(lines.stream().noneMatch(l -> l.contains("\"measuredValue\":0")), String.join("\n", lines));
    }

    @Test
    void theRunRecordListsTheStatisticsK6Summarised() throws Exception {
        assertEquals(List.of("avg", "min", "med", "max", "p(90)", "p(95)", "p(99)", "count"),
                throughTheAdapter("K2", true).result.header.trendStatistics);
        assertEquals(List.of("avg", "min", "med", "max", "p(90)", "p(95)"),
                throughTheAdapter("K1", true).result.header.trendStatistics, "K1's summary has no p(99)");
        assertEquals(null, throughTheAdapter("K1", false).result.header.trendStatistics, "no summary, no list");
        assertEquals(com.jmeterwebinsightreport.core.rundata.RunDataFormat.VERSION,
                throughTheAdapter("K1", false).result.header.formatVersion);
    }

    @Test
    void anOlderFileGivesRequestRowsOnlyAndSaysWhy() throws Exception {
        // K3: whole seconds and no vu/iter (spec, Edge Cases "Older data"; U2)
        Read k3 = throughTheAdapter("K3", true);
        List<String> diff = differences(throughTheReferenceCopy("K3"), k3.data);
        assertTrue(diff.isEmpty(), String.join("\n", diff));
        assertTrue(k3.conversion.wholeSeconds && k3.conversion.withoutUsers);
        assertEquals("s", k3.result.header.timeResolution);
        assertTrue(k3.result.header.notMeasured.containsAll(List.of("user", "iteration")));
        assertTrue(k3.data.getSamplerStatistics().stream().allMatch(s -> s.getParentSamplerName() == null),
                "no groups without vu and iter");
    }

    @Test
    void aSummaryIsOptional() throws Exception {
        Read k1 = throughTheAdapter("K1", false);
        assertTrue(k1.result.tool.thresholds().isEmpty());
        assertEquals(2, k1.result.tool.checks().size(), "checks come from the CSV");
    }

    @Test
    void csvFieldsMayBeQuoted() {
        assertEquals(List.of("a", "b,c", "d\"e", ""), K6Results.splitCsv("a,\"b,c\",\"d\"\"e\","));
    }
}
