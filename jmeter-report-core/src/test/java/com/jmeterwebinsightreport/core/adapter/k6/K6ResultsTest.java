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
