package com.jmeterwebinsightreport.report.rundata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.rundata.RunDataRecorder;
import com.jmeterwebinsightreport.core.rundata.RunHeader;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.exporter.ReportRebuild;
import com.jmeterwebinsightreport.report.generator.PropertyCheck;
import com.jmeterwebinsightreport.report.generator.ReportArtifacts;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.listener.WebInsightReportListener;
import com.jmeterwebinsightreport.report.template.ReportDataSerializer;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code webinsight.report.rundata} on both JMeter paths (spec 001, decisions 9 and 11, task T014, finding G3). */
class RunDataSettingTest {

    private static final File PLAIN = new File("../test-plans/goldens/plain.jtl");

    @TempDir
    Path tmp;

    private ReportArtifacts minusG(File out, Map<String, String> settings) throws Exception {
        Function<String, String> props = settings::get;
        ReportConfiguration config = ReportRebuild.configuration(out, props);
        ReportData data = ReportRebuild.parse(PLAIN, config, props);
        return ReportRebuild.generateAndAnnounce(config, data, null,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), null);
    }

    @Test
    void offByDefaultTheGPathWritesNoRunData() throws Exception {
        File out = tmp.resolve("off").toFile();
        minusG(out, Map.of());
        assertFalse(new File(out, "web-insight-report-run-data.jsonl.gz").exists());
    }

    @Test
    void onTheGPathsRunDataRebuildsTheSameReport() throws Exception {
        File out = tmp.resolve("on").toFile();
        Map<String, String> settings = new HashMap<>();
        settings.put("webinsight.report.rundata", "true");
        ReportArtifacts artifacts = minusG(out, settings);
        File runData = new File(out, "web-insight-report-run-data.jsonl.gz");
        assertTrue(runData.isFile(), "written beside the report");

        String runId = new ObjectMapper().readTree(artifacts.file(ReportArtifacts.Kind.JSON)).path("runId").asText();
        ReportConfiguration config = ReportRebuild.configuration(tmp.resolve("rebuilt").toFile(), k -> null);
        ReportData rebuilt = ReportRebuild.parseRunData(runData, config, k -> null);
        assertEquals(runId, RunIdentity.of(rebuilt), "the file gives the run its identity back");
    }

    @Test
    void theSettingIsAKnownProperty() {
        assertTrue(PropertyCheck.check(Map.of("webinsight.report.rundata", "true")).isEmpty(),
                "no unknown-property warning for webinsight.report.rundata");
    }

    @Test
    void theListenersRunDataCarriesItsNestingAndTheBodiesTheReportCarries() throws Exception {
        WebInsightReportListener listener = new WebInsightReportListener();
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        engine.setMaxResponseBodyLength(10);
        RunDataRecorder recorder = RunDataRecorder.open(new RunHeader(RunHeader.TOOL_JMETER, "5.6.3", "listener"),
                engine.getMaxResponseBodyLength());
        set(listener, "statsEngine", engine);
        set(listener, "runDataRecorder", recorder);

        long t = 1_700_000_000_000L;
        SampleResult home = sample(t, 120, "Home", "200", "OK", true, null);
        home.addSubResult(sample(t, 40, "Home-0", "302", "Found", true, null));
        listener.sampleOccurred(new SampleEvent(home, "Shop 1-1"));
        SampleResult pay = sample(t + 250, 150, "Pay", "500", "Internal Server Error", false,
                "{\"error\":\"card declined\"}");
        SampleResult checkout = sample(t + 200, 150, "Checkout", "500",
                "Number of samples in transaction : 1, number of failing samples : 1", false, null);
        checkout.addSubResult(pay, false);
        listener.sampleOccurred(new SampleEvent(checkout, "Shop 1-1"));

        File runData = recorder.publish(tmp.toFile(), "web-insight-report.html");
        List<String> lines = lines(runData);
        assertTrue(lines.get(0).contains("\"type\":\"run\""), lines.get(0));
        String payLine = lines.stream().filter(l -> l.contains("\"name\":\"Pay\"")).findFirst().orElseThrow();
        assertTrue(payLine.contains("\"responseBody\":\"{\\\"error\\\":\\\"...\""),
                "the body is cut where the report cuts it (decision 11): " + payLine);
        assertTrue(payLine.contains("\"parent\":\"s"), "it names its transaction: " + payLine);
        String hopLine = lines.stream().filter(l -> l.contains("\"name\":\"Home-0\"")).findFirst().orElseThrow();
        assertTrue(hopLine.contains("\"link\":\"subresult\""), "a redirect hop is a sub-result: " + hopLine);

        // The file rebuilds the listener's report data
        ReportData fromListener = engine.buildReportData("listener");
        ReportConfiguration config = ReportRebuild.configuration(tmp.resolve("r").toFile(),
                k -> "webinsight.error.body.maxsize".equals(k) ? "10" : null);
        LiveStatisticsEngine again = new LiveStatisticsEngine();
        again.setMaxResponseBodyLength(10);
        new com.jmeterwebinsightreport.core.rundata.RunDataReader().read(runData, again);
        assertEquals(json(fromListener), json(again.buildReportData("listener")));
    }

    private static String json(ReportData d) throws Exception {
        ObjectMapper m = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode tree =
                (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(new ReportDataSerializer().serializeToJson(d));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("metadata"))
                .remove(List.of("reportGeneratedAt", "reportGeneratedAtIso"));
        return m.writeValueAsString(tree);
    }

    private static List<String> lines(File gz) throws Exception {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(gz.toPath())), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                out.add(l);
            }
        }
        return out;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = WebInsightReportListener.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static SampleResult sample(long start, long elapsed, String label, String code, String message,
                                       boolean ok, String body) {
        SampleResult r = SampleResult.createTestSample(start, start + elapsed);
        r.setSampleLabel(label);
        r.setResponseCode(code);
        r.setResponseMessage(message);
        r.setSuccessful(ok);
        r.setThreadName("Shop 1-1");
        if (body != null) {
            r.setResponseData(body, StandardCharsets.UTF_8.name());
        }
        return r;
    }
}
