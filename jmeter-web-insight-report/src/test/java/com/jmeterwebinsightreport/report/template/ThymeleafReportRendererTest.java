package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jmeterwebinsightreport.core.model.*;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ThymeleafReportRendererTest {

    private ThymeleafReportRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ThymeleafReportRenderer();
    }

    // --- Helper methods ---

    private static ReportData createReportData(String testName) {
        ReportData data = new ReportData();
        TestMetadata meta = new TestMetadata();
        meta.setTestName(testName);
        meta.setDurationMillis(120000);
        meta.setTotalSamples(1000);
        meta.setTotalErrors(5);
        meta.setReportGeneratedAt("2024-01-15 10:30:00");
        meta.setJmeterVersion("5.6.3");
        data.setMetadata(meta);
        return data;
    }

    private static ReportData createReportDataWithSamplers() {
        ReportData data = createReportData("Performance Test");

        List<SamplerStatistics> stats = new ArrayList<>();
        SamplerStatistics s1 = new SamplerStatistics();
        s1.setSamplerName("GET /api/users");
        s1.setSampleCount(500);
        s1.setMeanResponseTime(150.0);
        s1.setMedianResponseTime(130.0);
        s1.setPercentile90(280.0);
        s1.setPercentile95(300.0);
        s1.setPercentile99(500.0);
        s1.setMinResponseTime(20.0);
        s1.setMaxResponseTime(800.0);
        s1.setErrorRate(0.5);
        s1.setErrorCount(3);
        s1.setThroughput(8.33);
        s1.setStandardDeviation(75.0);
        s1.setReceivedBytesPerSec(2048.0);
        s1.setSentBytesPerSec(512.0);
        s1.setMeanConnectTime(5.0);
        s1.setMeanLatency(120.0);
        stats.add(s1);

        SamplerStatistics s2 = new SamplerStatistics();
        s2.setSamplerName("POST /api/login");
        s2.setSampleCount(500);
        s2.setMeanResponseTime(200.0);
        s2.setMedianResponseTime(180.0);
        s2.setPercentile90(380.0);
        s2.setPercentile95(400.0);
        s2.setPercentile99(600.0);
        s2.setMinResponseTime(30.0);
        s2.setMaxResponseTime(1200.0);
        s2.setErrorRate(0.4);
        s2.setErrorCount(2);
        s2.setThroughput(8.33);
        s2.setStandardDeviation(90.0);
        s2.setReceivedBytesPerSec(4096.0);
        s2.setSentBytesPerSec(1024.0);
        s2.setMeanConnectTime(8.0);
        s2.setMeanLatency(160.0);
        stats.add(s2);

        data.setSamplerStatistics(stats);

        // Time series with per-sampler data
        Instant base = Instant.ofEpochMilli(1700000000000L);
        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TimeSeriesBucket bucket = new TimeSeriesBucket();
            bucket.setTimestamp(base.plusSeconds(i));
            bucket.setSampleCount(20);
            bucket.setMeanResponseTime(150.0 + i * 10);
            bucket.setThroughput(10.0);
            bucket.setErrorRate(0.5);
            bucket.setActiveThreads(5);

            Map<String, TimeSeriesBucket.SamplerBucketData> perSampler = new LinkedHashMap<>();
            TimeSeriesBucket.SamplerBucketData sd1 = new TimeSeriesBucket.SamplerBucketData();
            sd1.setSampleCount(10);
            sd1.setMeanResponseTime(120.0 + i * 10);
            sd1.setThroughput(5.0);
            sd1.setErrorRate(0.0);
            perSampler.put("GET /api/users", sd1);

            TimeSeriesBucket.SamplerBucketData sd2 = new TimeSeriesBucket.SamplerBucketData();
            sd2.setSampleCount(10);
            sd2.setMeanResponseTime(180.0 + i * 10);
            sd2.setThroughput(5.0);
            sd2.setErrorRate(1.0);
            perSampler.put("POST /api/login", sd2);

            bucket.setPerSamplerData(perSampler);
            timeSeries.add(bucket);
        }
        data.setTimeSeries(timeSeries);

        return data;
    }

    private String renderWithData(ReportData data) throws JsonProcessingException {
        String chartDataJson = ChartDataJson.of(data);
        return renderer.render(data, chartDataJson);
    }

    // --- Tests ---

    /**
     * The header badge appears for a gate whose every rule went unmatched.
     *
     * <p>It did not. The renderer sets {@code overallAssertStatus} only when
     * {@code evaluation.hasGate()}, and that required a non-empty per-sampler verdict map — which
     * an unmatched rule cannot produce, because it judged no sampler. So the loudest surface in
     * the report said "no asserts configured" about a run the same file's {@code gate}
     * block was failing.
     *
     * <p>The badge reads "ASSERTS FAIL": the product uses one word for
     * one mechanism now. The model variable is still {@code overallAssertStatus} and the CSS class
     * is still {@code badge-fail} — internals no reader sees, whose renaming would be churn
     * every browser spec pays for.
     */
    @Test
    void render_showsTheBadgeWhenEveryRuleWentUnmatched() throws Exception {
        ReportData data = createReportDataWithSamplers();
        ReportAnnotations annotations = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"assertions\":[{\"id\":\"ghost\","
                        + "\"target\":{\"sampler\":\"GET /not-in-this-run\"},"
                        + "\"metric\":\"p95\",\"fail\":{\"gt\":1}}]}", ReportAnnotations.class);

        String html = renderer.render(data, ChartDataJson.of(data), "", "",
                annotations);

        // Rules were declared; none of them judged anything. The badge must say exactly that
        // — not "ASSERTS FAIL" (nothing failed) and not "NO ASSERTS DECLARED" (they were).
        assertTrue(html.contains("ASSERTS NOT EVALUATED"),
                "a declared gate that judged nothing must say so, not claim a verdict");
        assertFalse(html.contains("NO ASSERTS DECLARED"),
                "rules WERE declared — the badge must not deny it");
        // ERRORS DETECTED is gone from this slot entirely, not merely absent here. An error
        // count is not an assert verdict, and the badge answers exactly one question: what did
        // the declared asserts decide. It used to appear on a gateless run that happened to
        // record errors, and on a run whose gate had just FAILED it read as a lesser problem
        // than the one that actually happened.
        assertFalse(html.contains("ERRORS DETECTED"),
                "an error count must never occupy the verdict slot");
    }

    /**
     * A run that declared nothing must not claim a pass, however clean it was.
     *
     * <p>The fallback badge rendered {@code badge-pass} / "ALL PASSED" whenever the run recorded
     * no errors — and {@code overallAssertStatus} is null in exactly the cases worth catching: a
     * typo'd {@code -Jwebinsight.report.annotations} path, a file that failed to parse, and a
     * file whose every rule was parked with {@code enabled: false}. All three are a gate that
     * silently stopped gating, and all three came out green on the surface an executive reads.
     */
    @Test
    void render_doesNotClaimAPassWhenNothingWasDeclared() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();
        data.getMetadata().setTotalErrors(0);

        String html = renderWithData(data);

        assertTrue(html.contains("NO ASSERTS DECLARED"),
                "a run that declared nothing must say so");
        assertFalse(html.contains("ALL PASSED"),
                "declared is not judged is not passed — the badge must not invent a verdict");
        assertFalse(html.contains("badge-pass"),
                "and it must not be green: the neutral ramp is what NOT EVALUATED looks like");
    }

    /**
     * SF38: the header carries the generation instant as an ISO attribute for the page to write in
     * the reader's zone, beside the text Java wrote (which is the JSON's generatedAt). A report built
     * without one has no attribute at all, so the page keeps Java's text — never "null".
     */
    @Test
    void render_carriesTheGenerationInstantForThePageToWriteInTheReadersZone() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();
        data.getMetadata().setReportGeneratedAtIso("2024-01-15T08:30:00.123Z");

        String html = renderWithData(data);

        assertTrue(html.contains("id=\"reportGeneratedAt\""), "the header's Generated span is addressable");
        assertTrue(html.contains("data-generated-at=\"2024-01-15T08:30:00.123Z\""), "the instant rides the span");
        assertTrue(html.contains("Generated: 2024-01-15 10:30:00"), "Java's own text is still the span's text");

        data.getMetadata().setReportGeneratedAtIso(null);
        String bare = renderWithData(data);
        // The attribute as markup: report.js, inlined in the page, names it too.
        assertFalse(bare.contains("data-generated-at=\""), "no instant, no attribute");
        assertTrue(bare.contains("Generated: 2024-01-15 10:30:00"));
    }

    @Test
    void render_producesHtmlOutput() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();

        String html = renderWithData(data);

        assertNotNull(html);
        assertTrue(html.contains("<!DOCTYPE html>") || html.contains("<html"),
                "Output should be HTML");
    }

    @Test
    void render_containsTestTitle() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();

        String html = renderWithData(data);

        assertTrue(html.contains("Performance Test"),
                "HTML should contain the test name");
    }

    @Test
    void render_containsSamplerNames() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();

        String html = renderWithData(data);

        assertTrue(html.contains("GET /api/users"),
                "HTML should contain sampler name 'GET /api/users'");
        assertTrue(html.contains("POST /api/login"),
                "HTML should contain sampler name 'POST /api/login'");
    }

    @Test
    void render_containsChartData() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();
        String chartDataJson = ChartDataJson.of(data);

        String html = renderer.render(data, chartDataJson);

        // The chart data JSON is embedded in the HTML for ECharts
        assertTrue(html.contains("timestamps") || html.contains("meanResponseTimes"),
                "HTML should contain embedded chart data");
    }

    @Test
    void render_handlesEmptyReportData() throws JsonProcessingException {
        ReportData data = new ReportData();
        TestMetadata meta = new TestMetadata();
        meta.setTestName("Empty Test");
        data.setMetadata(meta);
        data.setSamplerStatistics(new ArrayList<>());
        data.setTimeSeries(new ArrayList<>());

        String html = renderWithData(data);

        assertNotNull(html);
        assertTrue(html.contains("<!DOCTYPE html>") || html.contains("<html"),
                "Empty data should still render valid HTML");
    }

    @Test
    void render_includesInlinedCss() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();
        String chartDataJson = ChartDataJson.of(data);
        String cssContent = "body { font-family: sans-serif; }";

        String html = renderer.render(data, chartDataJson, cssContent, "");

        assertTrue(html.contains("<style>") || html.contains("<style "),
                "HTML should contain a <style> tag");
    }

    @Test
    void render_includesInlinedJs() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();
        String chartDataJson = ChartDataJson.of(data);
        String jsContent = "function initCharts() { console.log('init'); }";

        String html = renderer.render(data, chartDataJson, "", jsContent);

        assertTrue(html.contains("<script>") || html.contains("<script "),
                "HTML should contain a <script> tag");
    }

    @Test
    void render_containsGeneratedTimestamp() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();

        String html = renderWithData(data);

        assertTrue(html.contains("2024-01-15 10:30:00"),
                "HTML should contain the report generation timestamp");
    }

    @Test
    void render_twoArgOverloadProducesHtml() throws JsonProcessingException {
        ReportData data = createReportDataWithSamplers();
        String chartDataJson = ChartDataJson.of(data);

        String html = renderer.render(data, chartDataJson);

        assertNotNull(html);
        assertTrue(html.contains("<!DOCTYPE html>"));
    }

    /**
     * A 2xx counted as an error must not render as a bare code beside "OK".
     *
     * <p>Errors are collected on {@code success == false} and {@code errorType} is the response
     * code verbatim, so a sample failed by a Response Assertion reaches the Errors tab as
     * <em>200 / OK</em> — a row that reads like a pass while being counted as half the errors.
     * Confirmed against a real {@code -g} run before this was written.
     */
    @Test
    void render_labelsA2xxErrorAsNonProtocolAndSuppressesItsMeaninglessMessage() throws Exception {
        ReportData data = createReportDataWithSamplers();

        ErrorSummary assertionFailure = new ErrorSummary();
        assertionFailure.setSamplerName("GET /api/items");
        assertionFailure.setResponseCode("200");
        assertionFailure.setResponseMessage("OK");
        assertionFailure.setOccurrenceCount(2);
        ErrorSummary serverFailure = new ErrorSummary();
        serverFailure.setSamplerName("GET /api/items");
        serverFailure.setResponseCode("500");
        serverFailure.setResponseMessage("Internal Server Error");
        serverFailure.setOccurrenceCount(1);
        data.setErrorSummaries(java.util.Arrays.asList(assertionFailure, serverFailure));

        ErrorRecord record = new ErrorRecord();
        record.setSamplerName("GET /api/items");
        record.setResponseCode("200");
        record.setResponseMessage("OK");
        record.setTimestamp(1785949469000L);
        record.setThreadName("tg 1-1");
        data.setErrorRecords(java.util.Collections.singletonList(record));

        String html = renderer.render(data, ChartDataJson.of(data), "", "");

        assertTrue(html.contains("Non-protocol failure"),
                "a 200 in the error set must say it failed for a non-HTTP reason");
        assertTrue(html.contains("HTTP failure"),
                "the 500 must still be labelled as an HTTP-level failure");
        // The em-dash replaces the reason phrase. The real reason lives in failureMessage, which
        // neither ingestion path captures, so printing "OK" asserts something we cannot know.
        assertTrue(html.contains("error-message-none"),
                "the meaningless message must be rendered as nothing, not as \"OK\"");
        assertTrue(html.contains("Internal Server Error"),
                "a real failure message must survive — only the 2xx reason phrase is suppressed");
    }

    /** The class rides in the payload so nothing client-side re-derives it and disagrees. */
    @Test
    void render_carriesTheErrorClassInThePayloadForTheClientSurfaces() throws Exception {
        ReportData data = createReportDataWithSamplers();
        ErrorSummary assertionFailure = new ErrorSummary();
        assertionFailure.setSamplerName("GET /api/items");
        assertionFailure.setResponseCode("200");
        assertionFailure.setResponseMessage("OK");
        assertionFailure.setOccurrenceCount(2);
        data.setErrorSummaries(java.util.Collections.singletonList(assertionFailure));

        String payload = ChartDataJson.of(data);

        assertTrue(payload.contains("\"errorClass\":\"nonProtocol\""), payload);
        assertTrue(payload.contains("\"messageMeaningless\":true"), payload);
    }
}
