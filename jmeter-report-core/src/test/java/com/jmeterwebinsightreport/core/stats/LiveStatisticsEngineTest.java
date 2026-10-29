package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LiveStatisticsEngineTest {

    private LiveStatisticsEngine engine;

    @BeforeEach
    void setUp() {
        engine = new LiveStatisticsEngine();
    }

    @Test
    void recordSample_incrementsSampleCount() {
        long ts = 1700000000000L;
        for (int i = 0; i < 10; i++) {
            engine.recordSample("Sampler1", 100 + i, true, 500, 100, ts + i * 100, 5, 10);
        }

        List<SamplerStatistics> stats = engine.getStatisticsSnapshot();
        assertEquals(1, stats.size());
        assertEquals(10, stats.get(0).getSampleCount());
    }

    @Test
    void recordSample_tracksMinMaxResponseTime() {
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 50, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler1", 200, true, 500, 100, ts + 100, 5, 10);
        engine.recordSample("Sampler1", 100, true, 500, 100, ts + 200, 5, 10);

        SamplerStatistics stat = engine.getStatisticsSnapshot().get(0);
        assertEquals(50.0, stat.getMinResponseTime(), 0.01);
        assertEquals(200.0, stat.getMaxResponseTime(), 0.01);
    }

    /**
     * X1, the listener's path. Every sample at 0 ms gave a max of 4.9E-324 - Double.MIN_VALUE, the
     * smallest positive double, taken for the most negative - so {@code max gt 0} FAILed a sampler
     * that never took a millisecond. Compared exactly: a tolerance would pass the bug.
     */
    @Test
    void recordSample_allZeroSamplerHasMaxZero() {
        long ts = 1700000000000L;
        for (int i = 0; i < 3; i++) {
            engine.recordSample("Zero", 0, true, 500, 100, ts + i * 100, 0, 0);
        }

        SamplerStatistics stat = engine.getStatisticsSnapshot().get(0);
        assertEquals(0.0, stat.getMaxResponseTime(), "no sample took 4.9E-324 ms");
        assertEquals(0.0, stat.getMinResponseTime());
    }

    @Test
    void recordSample_calculatesMean() {
        long ts = 1700000000000L;
        // Response times: 100, 200, 300 → mean = 200
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler1", 200, true, 500, 100, ts + 100, 5, 10);
        engine.recordSample("Sampler1", 300, true, 500, 100, ts + 200, 5, 10);

        SamplerStatistics stat = engine.getStatisticsSnapshot().get(0);
        assertEquals(200.0, stat.getMeanResponseTime(), 0.01);
    }

    @Test
    void recordSample_tracksErrorCount() {
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler1", 100, true, 500, 100, ts + 100, 5, 10);
        engine.recordSample("Sampler1", 100, true, 500, 100, ts + 200, 5, 10);
        engine.recordSample("Sampler1", 100, false, 500, 100, ts + 300, 5, 10);
        engine.recordSample("Sampler1", 100, false, 500, 100, ts + 400, 5, 10);

        SamplerStatistics stat = engine.getStatisticsSnapshot().get(0);
        assertEquals(2, stat.getErrorCount());
        assertEquals(40.0, stat.getErrorRate(), 0.01); // 2/5 = 40%
    }

    @Test
    void recordSample_tracksBytesReceivedSent() {
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler1", 100, true, 700, 200, ts + 100, 5, 10);
        engine.recordSample("Sampler1", 100, true, 300, 50, ts + 200, 5, 10);

        SamplerStatistics stat = engine.getStatisticsSnapshot().get(0);
        assertEquals(1500.0, stat.getTotalReceivedBytes(), 0.01);
        assertEquals(350.0, stat.getTotalSentBytes(), 0.01);
    }

    @Test
    void recordSample_tracksConnectTimeAndLatency() {
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 10, 20);
        engine.recordSample("Sampler1", 100, true, 500, 100, ts + 100, 30, 40);

        SamplerStatistics stat = engine.getStatisticsSnapshot().get(0);
        assertEquals(20.0, stat.getMeanConnectTime(), 0.01); // (10+30)/2
        assertEquals(30.0, stat.getMeanLatency(), 0.01); // (20+40)/2
    }

    @Test
    void recordSample_createsTimeBuckets() {
        // Samples 2 seconds apart should land in different 1-second buckets
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler1", 100, true, 500, 100, ts + 2000, 5, 10);

        List<TimeSeriesBucket> buckets = engine.getTimeSeries();
        assertTrue(buckets.size() >= 2, "Expected at least 2 time buckets for samples 2 seconds apart");
    }

    @Test
    void recordSample_multipleSamplers() {
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler2", 200, true, 600, 200, ts + 100, 5, 10);

        List<SamplerStatistics> stats = engine.getStatisticsSnapshot();
        assertEquals(2, stats.size());
    }

    /**
     * SF38: the page writes the header's "Generated" in the reader's zone from an ISO instant, and
     * the formatted text — the CI/CD JSON's generatedAt — stays in the generating JVM's zone. One
     * moment, two spellings: the instant is the same second the text names.
     */
    @Test
    void buildReportData_stampsTheGenerationInstantAsIsoBesideTheFormattedText() {
        engine.recordSample("Sampler1", 100, true, 500, 100, 1700000000000L, 5, 10);
        java.time.Instant before = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        TestMetadata meta = engine.buildReportData("test").getMetadata();
        java.time.Instant after = java.time.Instant.now();

        java.time.Instant iso = java.time.Instant.parse(meta.getReportGeneratedAtIso());
        assertFalse(iso.isBefore(before), "the instant is the build's");
        assertFalse(iso.isAfter(after), "the instant is the build's");
        assertEquals(0, iso.getNano() % 1_000_000, "millisecond precision, which every Date.parse takes");
        assertEquals(com.jmeterwebinsightreport.core.util.FormatUtil.formatTimestamp(iso), meta.getReportGeneratedAt(),
                "the formatted text names the same moment");
    }

    @Test
    void recordSample_detectsTransactionControllers() {
        long ts = 1700000000000L;
        // Real transaction relationships come from sample sub-results (recorded by the listener).
        engine.recordTransaction("TC", "TC-0");
        engine.recordTransaction("TC", "TC-1");
        engine.recordSample("TC-0", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("TC-1", 120, true, 600, 120, ts + 100, 5, 10);
        engine.recordSample("TC", 220, true, 1100, 220, ts + 200, 5, 10);

        ReportData data = engine.buildReportData("test");
        Map<String, List<String>> hierarchy = data.getTransactionHierarchy();
        assertTrue(hierarchy.containsKey("TC"));
        assertTrue(hierarchy.get("TC").contains("TC-0"));
        assertTrue(hierarchy.get("TC").contains("TC-1"));

        SamplerStatistics parent = findSampler(data, "TC");
        assertTrue(parent.isTransactionController());
        assertEquals("TC", findSampler(data, "TC-0").getParentSamplerName());
    }

    @Test
    void recordSample_noFalseTransactionFromNamePrefix() {
        long ts = 1700000000000L;
        // REST-style names share a prefix but are independent samplers — must NOT be nested (B8).
        engine.recordSample("GET /api/users", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("GET /api/users/{id}", 120, true, 600, 120, ts + 100, 5, 10);
        engine.recordSample("GET /api/users/{id}/orders", 130, true, 600, 120, ts + 200, 5, 10);

        ReportData data = engine.buildReportData("test");
        assertTrue(data.getTransactionHierarchy().isEmpty(),
                "name prefixes must not create a transaction hierarchy");
        for (SamplerStatistics s : data.getSamplerStatistics()) {
            assertNull(s.getParentSamplerName());
            assertFalse(s.isTransactionController());
        }
    }

    @Test
    void recordTransaction_subResultsThatAreNotTransactionsNestButAreNotBadged() {
        // "Retrieve all embedded resources", follow-redirect chains and retries all produce
        // sub-results. Nesting them is right — the parent's elapsed time already contains
        // them — but the parent is a plain HTTP sampler, not a Transaction Controller.
        long ts = 1700000000000L;
        engine.recordTransaction("Home", "https://cdn/app.css", false);
        engine.recordTransaction("Home", "https://cdn/app.js", false);
        engine.recordSample("Home", 400, true, 5000, 200, ts, 5, 10);
        engine.recordSample("https://cdn/app.css", 50, true, 1000, 50, ts + 1, 5, 10);
        engine.recordSample("https://cdn/app.js", 60, true, 2000, 50, ts + 2, 5, 10);

        ReportData data = engine.buildReportData("test");

        assertEquals(List.of("https://cdn/app.css", "https://cdn/app.js"),
                data.getTransactionHierarchy().get("Home"),
                "embedded resources still nest under their parent");
        assertEquals("Home", findSampler(data, "https://cdn/app.css").getParentSamplerName());
        assertFalse(findSampler(data, "Home").isTransactionController(),
                "a plain HTTP sampler must not be presented as a Transaction Controller");
    }

    @Test
    void recordTransaction_realTransactionControllersAreStillBadged() {
        long ts = 1700000000000L;
        engine.recordTransaction("Login Flow", "POST /login", true);
        engine.recordSample("Login Flow", 300, true, 100, 10, ts, 5, 10);
        engine.recordSample("POST /login", 150, true, 50, 5, ts + 1, 5, 10);

        ReportData data = engine.buildReportData("test");

        assertTrue(findSampler(data, "Login Flow").isTransactionController());
    }

    @Test
    void recordTransaction_badgesOnlyTheRealControllerWhenBothArePresent() {
        long ts = 1700000000000L;
        engine.recordTransaction("Login Flow", "POST /login", true);
        engine.recordTransaction("Home", "https://cdn/app.css", false);
        engine.recordSample("Login Flow", 300, true, 100, 10, ts, 5, 10);
        engine.recordSample("POST /login", 150, true, 50, 5, ts + 1, 5, 10);
        engine.recordSample("Home", 400, true, 100, 10, ts + 2, 5, 10);
        engine.recordSample("https://cdn/app.css", 50, true, 50, 5, ts + 3, 5, 10);

        ReportData data = engine.buildReportData("test");

        assertTrue(findSampler(data, "Login Flow").isTransactionController());
        assertFalse(findSampler(data, "Home").isTransactionController());
        // Both still nest, so neither child is left at top level to be counted twice.
        long topLevel = data.getSamplerStatistics().stream()
                .filter(s -> s.getParentSamplerName() == null)
                .mapToLong(SamplerStatistics::getSampleCount)
                .sum();
        assertEquals(2, topLevel);
    }

    private static SamplerStatistics findSampler(ReportData data, String name) {
        return data.getSamplerStatistics().stream()
                .filter(s -> name.equals(s.getSamplerName()))
                .findFirst().orElse(null);
    }

    // ---- Audit-fix regression tests ----

    @Test
    void apdex_failuresAreFrustratedRegardlessOfSpeed() {
        // A4: fast but failing samples must NOT count as satisfied.
        long ts = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            engine.recordSample("fast-fail", 40, false, 10, 10, ts + i, 1, 1);
        }
        SamplerStatistics s = findSampler(engine.buildReportData("t"), "fast-fail");
        assertEquals(0.0, s.getApdexScore(), 0.001, "all-fast-failures must score Apdex 0");
    }

    @Test
    void apdex_fastSuccessesAreSatisfied() {
        long ts = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            engine.recordSample("fast-ok", 40, true, 10, 10, ts + i, 1, 1);
        }
        SamplerStatistics s = findSampler(engine.buildReportData("t"), "fast-ok");
        assertEquals(1.0, s.getApdexScore(), 0.001, "all-fast-successes must score Apdex 1");
    }

    @Test
    void errorSummaries_notCappedByDetailRecordLimit() {
        // A3: error summary counts/percentages must reflect ALL errors, not the 500 detail cap.
        long ts = 1700000000000L;
        int n = 1500;
        for (int i = 0; i < n; i++) {
            engine.recordSample("api", 50, false, 10, 10, ts + i, 1, 1);
            engine.recordError("api", "500", "err", ts + i, "t", null, null, null);
        }
        ReportData data = engine.buildReportData("t");
        long summed = data.getErrorSummaries().stream()
                .mapToLong(ErrorSummary::getOccurrenceCount).sum();
        assertEquals(n, summed, "summary count must equal total errors, not the detail cap");
        double pct = data.getErrorSummaries().stream()
                .mapToDouble(ErrorSummary::getPercentageOfErrors).sum();
        assertEquals(100.0, pct, 0.01, "percentage-of-errors must sum to ~100%");
        assertTrue(data.getErrorRecords().size() <= 500, "detailed records stay capped");
        assertTrue(data.isErrorRecordsTruncated(), "truncation flag set when errors exceed cap");
    }

    @Test
    void overallPercentiles_areTrueMergedPercentile() {
        // B11: overall p95 is the percentile of the merged distribution, not an average.
        long ts = 1700000000000L;
        // Sampler A: uniformly 100ms; Sampler B: uniformly 1000ms; equal counts.
        for (int i = 0; i < 500; i++) {
            engine.recordSample("A", 100, true, 10, 10, ts + i, 1, 1);
            engine.recordSample("B", 1000, true, 10, 10, ts + i, 1, 1);
        }
        ReportData data = engine.buildReportData("t");
        // Merged distribution is half 100 / half 1000; p95 must be up in the 1000 band,
        // far above the average of the two per-sampler p95s (~550).
        assertTrue(data.getOverallP95() > 900,
                "overall p95 should reflect the merged tail, got " + data.getOverallP95());
    }

    @Test
    void recordSample_hidesUtilitySamplers() {
        long ts = 1700000000000L;
        engine.recordSample("JSR223 Sampler", 10, true, 100, 50, ts, 1, 2);
        engine.recordSample("Debug Sampler", 5, true, 50, 25, ts + 100, 1, 2);
        engine.recordSample("HTTP Request", 100, true, 500, 100, ts + 200, 5, 10);

        ReportData data = engine.buildReportData("test");
        List<String> hidden = data.getHiddenSamplers();
        assertTrue(hidden.contains("JSR223 Sampler"));
        assertTrue(hidden.contains("Debug Sampler"));
        assertFalse(hidden.contains("HTTP Request"));
    }

    @Test
    void recordSample_limitsErrorRecords() {
        long ts = 1700000000000L;
        for (int i = 0; i < 600; i++) {
            engine.recordError("Sampler1", "500", "Error", ts + i, "Thread-1",
                    null, null, null);
        }

        List<ErrorRecord> errors = engine.getErrorRecords();
        assertEquals(500, errors.size());
    }

    @Test
    void recordSample_truncatesErrorResponseBody() {
        long ts = 1700000000000L;
        // DEFAULT_MAX_BODY_LENGTH = 16384 (16KB)
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            longBody.append('x');
        }

        engine.recordError("Sampler1", "500", "Error", ts, "Thread-1",
                null, null, longBody.toString());

        List<ErrorRecord> errors = engine.getErrorRecords();
        assertEquals(1, errors.size());
        assertEquals(16384 + 3, errors.get(0).getResponseBody().length());
        assertTrue(errors.get(0).getResponseBody().endsWith("..."));
    }

    @Test
    void recordError_capturesResponseCodeAndMessage() {
        long ts = 1700000000000L;
        engine.recordError("LoginAPI", "503", "Service Unavailable", ts, "Thread-1",
                "http://example.com/login", null, null);

        List<ErrorRecord> errors = engine.getErrorRecords();
        assertEquals(1, errors.size());
        ErrorRecord err = errors.get(0);
        assertEquals("LoginAPI", err.getSamplerName());
        assertEquals("503", err.getResponseCode());
        assertEquals("Service Unavailable", err.getResponseMessage());
        assertEquals("Thread-1", err.getThreadName());
        assertEquals("http://example.com/login", err.getRequestUrl());
    }

    @Test
    void buildReportData_returnsCompleteData() {
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler1", 200, false, 500, 100, ts + 1000, 5, 10);
        engine.recordError("Sampler1", "500", "Internal Error", ts + 1000, "Thread-1",
                null, null, null);

        ReportData data = engine.buildReportData("MyTest");

        assertNotNull(data.getMetadata());
        assertNotNull(data.getSamplerStatistics());
        assertNotNull(data.getTimeSeries());
        assertNotNull(data.getErrorRecords());
        assertNotNull(data.getErrorSummaries());
        assertNotNull(data.getHiddenSamplers());
        assertNotNull(data.getTransactionHierarchy());
        assertFalse(data.getSamplerStatistics().isEmpty());
        assertFalse(data.getTimeSeries().isEmpty());
        assertFalse(data.getErrorRecords().isEmpty());
    }

    @Test
    void buildReportData_calculatesTestMetadata() {
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler1", 200, false, 500, 100, ts + 5000, 5, 10);

        ReportData data = engine.buildReportData("MyTest");

        TestMetadata meta = data.getMetadata();
        assertEquals("MyTest", meta.getTestName());
        // The window ends when the LAST SAMPLE ENDS, not when it started: the second sample
        // starts at +5000 ms and takes 200 ms. Per-sampler throughput uses the same window,
        // so the duration the total-throughput tile divides by has to agree with it.
        assertEquals(5200, meta.getDurationMillis());
        assertEquals(meta.getDurationMillis(),
                meta.getEndTime().toEpochMilli() - meta.getStartTime().toEpochMilli(),
                "endTime - startTime must equal durationMillis");
        assertEquals(2, meta.getTotalSamples());
        assertEquals(1, meta.getTotalErrors());
        assertNotNull(meta.getStartTime());
        assertNotNull(meta.getEndTime());
    }

    @Test
    void buildReportData_calculatesThroughput() {
        long ts = 1700000000000L;
        // 10 samples over 5 seconds = 2.0 req/sec
        for (int i = 0; i < 10; i++) {
            engine.recordSample("Sampler1", 100, true, 500, 100, ts + i * 555, 5, 10);
        }

        ReportData data = engine.buildReportData("ThroughputTest");
        SamplerStatistics stat = data.getSamplerStatistics().get(0);
        assertTrue(stat.getThroughput() > 0, "Throughput should be positive");
    }

    @Test
    void threadSafety_concurrentRecording() throws InterruptedException {
        int threadCount = 10;
        int samplesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        long ts = 1700000000000L;
        for (int t = 0; t < threadCount; t++) {
            final int threadNum = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < samplesPerThread; i++) {
                        engine.recordSample("ConcurrentSampler", 100 + i, true, 500, 100,
                                ts + threadNum * 1000 + i, 5, 10);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads");
        executor.shutdown();

        ReportData data = engine.buildReportData("ConcurrencyTest");
        assertEquals(threadCount * samplesPerThread,
                data.getMetadata().getTotalSamples());
    }

    @Test
    void reset_clearsAllData() {
        long ts = 1700000000000L;
        engine.recordSample("Sampler1", 100, true, 500, 100, ts, 5, 10);
        engine.recordSample("Sampler1", 200, false, 500, 100, ts + 1000, 5, 10);
        engine.recordError("Sampler1", "500", "Error", ts + 1000, "Thread-1",
                null, null, null);

        // Verify data exists
        assertFalse(engine.getStatisticsSnapshot().isEmpty());
        assertFalse(engine.getTimeSeries().isEmpty());
        assertFalse(engine.getErrorRecords().isEmpty());

        // Reset
        engine.reset();

        // Verify all cleared
        assertTrue(engine.getStatisticsSnapshot().isEmpty());
        assertTrue(engine.getTimeSeries().isEmpty());
        assertTrue(engine.getErrorRecords().isEmpty());

        ReportData data = engine.buildReportData("AfterReset");
        assertEquals(0, data.getMetadata().getTotalSamples());
        assertEquals(0, data.getMetadata().getTotalErrors());
    }

    // --- Apdex threshold boundary (CORE-2) ---

    @Test
    void apdex_samplesExactlyAtThresholdAreSatisfied() {
        // Apdex defines satisfied as t <= T. A t-digest CDF interpolates, so mass sitting
        // exactly on T contributed only half a sample and this scored 0.75 instead of 1.00.
        // Constant-time mocked services and coarse timers land on T routinely.
        long ts = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            engine.recordSample("exactly-at-T", 500, true, 10, 10, ts + i, 1, 1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "exactly-at-T");
        assertEquals(1.0, s.getApdexScore(), 0.001,
                "every sample is at exactly the 500 ms threshold, so Apdex must be 1.00");
    }

    @Test
    void apdex_singleSampleExactlyAtThresholdIsSatisfied() {
        long ts = 1700000000000L;
        engine.recordSample("one-at-T", 500, true, 10, 10, ts, 1, 1);

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "one-at-T");
        assertEquals(1.0, s.getApdexScore(), 0.001);
    }

    @Test
    void apdex_samplesExactlyAtFourTAreTolerating() {
        // 4T is the tolerating/frustrated boundary, and is inclusive on the same rule.
        long ts = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            engine.recordSample("at-4T", 2000, true, 10, 10, ts + i, 1, 1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "at-4T");
        assertEquals(0.5, s.getApdexScore(), 0.001, "all tolerating scores 0.5");
    }

    @Test
    void apdex_justOverThresholdIsTolerating() {
        long ts = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            engine.recordSample("just-over-T", 501, true, 10, 10, ts + i, 1, 1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "just-over-T");
        assertEquals(0.5, s.getApdexScore(), 0.001);
    }

    @Test
    void apdex_justOverFourTIsFrustrated() {
        long ts = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            engine.recordSample("just-over-4T", 2001, true, 10, 10, ts + i, 1, 1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "just-over-4T");
        assertEquals(0.0, s.getApdexScore(), 0.001);
    }

    @Test
    void apdex_honoursACustomThreshold() {
        engine.setApdexThreshold(1000);
        long ts = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            engine.recordSample("custom-T", 1000, true, 10, 10, ts + i, 1, 1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "custom-T");
        assertEquals(1.0, s.getApdexScore(), 0.001, "1000 ms is satisfied when T = 1000");
    }

    @Test
    void errorRecordsAreListedByStartThenSamplerThenThread() {
        // E4: records arrive as samples finish, which the listener and the results file do not see in
        // one order. Listed by start, then sampler, then thread, two builds of one run read alike.
        long ts = 1700000000000L;
        engine.recordError("B", "500", "x", ts + 5, "T 1-2");
        engine.recordError("B", "500", "x", ts + 5, "T 1-1");
        engine.recordError("A", "500", "x", ts + 5, "T 1-9");
        engine.recordError("C", "503", "y", ts, "T 1-3");

        List<String> order = new java.util.ArrayList<>();
        for (ErrorRecord r : engine.getErrorRecords()) {
            order.add(r.getSamplerName() + "/" + r.getThreadName());
        }
        assertEquals(List.of("C/T 1-3", "A/T 1-9", "B/T 1-1", "B/T 1-2"), order);
    }

    @Test
    void aSampleThatEndsAfterItsElapsedTimeIsWindowedToItsEnd() {
        // B7: a Transaction Controller's parent sample with "include timers" off. Its elapsed time is
        // its children's 200 ms; it really ran 1 s, the rest think time. Two of them back to back
        // ran 2 s, so 1/s, where windowing to start + elapsed read 2 over 1.2 s.
        long ts = 1700000000000L;
        engine.recordSample("Checkout", 200, true, 10, 10, ts, ts + 1000, -1, -1);
        engine.recordSample("Checkout", 200, true, 10, 10, ts + 1000, ts + 2000, -1, -1);

        ReportData data = engine.buildReportData("t");
        assertEquals(1.0, findSampler(data, "Checkout").getThroughput(), 1e-9);
        assertEquals(2000, data.getMetadata().getDurationMillis());
        assertEquals(200.0, findSampler(data, "Checkout").getMeanResponseTime(), 1e-9, "the elapsed time is untouched");
    }

    @Test
    void apdex_wholeRunIsComputedFromTheCountsAndRoundedOnce() {
        // "slow": 3 frustrated, scores 0.00. "mixed": 2 satisfied + 1 frustrated, 0.6667, shown
        // as 0.67. The mean of the shown scores is (0 * 3 + 0.67 * 3) / 6 = 0.335, which a second
        // rounding made 0.34; the run's own counts give 2 / 6 = 0.333, 0.33 (B4, pilot-C 0.90 vs
        // 0.906).
        long ts = 1700000000000L;
        for (int i = 0; i < 3; i++) {
            engine.recordSample("slow", 5000, true, 10, 10, ts + i, 1, 1);
        }
        engine.recordSample("mixed", 100, true, 10, 10, ts, 1, 1);
        engine.recordSample("mixed", 100, true, 10, 10, ts + 1, 1, 1);
        engine.recordSample("mixed", 5000, true, 10, 10, ts + 2, 1, 1);

        ReportData data = engine.buildReportData("t");
        assertEquals(0.0, findSampler(data, "slow").getApdexScore(), 1e-9);
        assertEquals(0.67, findSampler(data, "mixed").getApdexScore(), 1e-9);
        assertEquals(2.0, findSampler(data, "mixed").getApdexSatisfied(), 1e-9);
        assertEquals(0.0, findSampler(data, "mixed").getApdexTolerating(), 1e-9);
        assertEquals(0.33, OverallMetrics.of(data).getApdex(), 1e-9);
    }

    @Test
    void apdex_mixedBucketsScoreProportionally() {
        long ts = 1700000000000L;
        // 50 satisfied (at T), 50 tolerating (at 4T) → (50 + 25) / 100 = 0.75
        for (int i = 0; i < 50; i++) {
            engine.recordSample("mixed", 500, true, 10, 10, ts + i, 1, 1);
        }
        for (int i = 50; i < 100; i++) {
            engine.recordSample("mixed", 2000, true, 10, 10, ts + i, 1, 1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "mixed");
        assertEquals(0.75, s.getApdexScore(), 0.001);
    }

    @Test
    void apdexThreshold_defaultIs500() {
        assertEquals(500, engine.getApdexThreshold());
    }

    @Test
    void apdexThreshold_setAndGet() {
        engine.setApdexThreshold(1000);
        assertEquals(1000, engine.getApdexThreshold());
    }

    @Test
    void buildReportData_includesApdexScore() {
        long ts = 1700000000000L;
        // Record samples with response times well under the default 500ms threshold
        // so apdex should be high (close to 1.0)
        for (int i = 0; i < 50; i++) {
            engine.recordSample("FastAPI", 100 + i, true, 500, 100,
                    ts + i * 100, 5, 10);
        }

        ReportData data = engine.buildReportData("ApdexTest");
        SamplerStatistics stat = data.getSamplerStatistics().get(0);

        assertTrue(stat.getApdexScore() > 0,
                "Apdex score should be positive for samples well under threshold");
    }

    @Test
    void maxResponseBodyLength_setAndGet() {
        long ts = 1700000000000L;
        engine.setMaxResponseBodyLength(8192);

        // Create a 10000-character response body
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            longBody.append('x');
        }

        engine.recordError("Sampler1", "500", "Error", ts, "Thread-1",
                null, null, longBody.toString());

        List<ErrorRecord> errors = engine.getErrorRecords();
        assertEquals(1, errors.size());
        // Body should be truncated to 8192 + "..." (3 chars)
        assertEquals(8192 + 3, errors.get(0).getResponseBody().length());
        assertTrue(errors.get(0).getResponseBody().endsWith("..."));
    }


    // --- Thread safety of transaction registration (CORE-1) ---

    @Test
    void recordTransaction_concurrentRegistrationKeepsEveryChild() throws Exception {
        // Every sampler thread finishing the same Transaction Controller registers a child
        // on the same set. An unsynchronized HashMap resize under that load silently drops
        // registrations, leaving children unmarked and double-counted in tiles/percentiles.
        final int threads = 32;
        final int childrenPerThread = 40;
        final int expectedChildren = threads * childrenPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadNo = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < childrenPerThread; i++) {
                        engine.recordTransaction("Login Flow", "child-" + threadNo + "-" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
        pool.shutdown();

        // Every child must also exist as a sampler to appear in the hierarchy.
        long ts = 1700000000000L;
        engine.recordSample("Login Flow", 500, true, 1000, 200, ts, 5, 10);
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < childrenPerThread; i++) {
                engine.recordSample("child-" + t + "-" + i, 10, true, 100, 10, ts + 1, 5, 10);
            }
        }

        ReportData data = engine.buildReportData("test");
        List<String> children = data.getTransactionHierarchy().get("Login Flow");
        assertNotNull(children, "parent must be recognised as a transaction controller");
        assertEquals(expectedChildren, children.size(),
                "no child registration may be lost under concurrency");
        assertEquals(expectedChildren, new java.util.HashSet<>(children).size(),
                "children must be unique");
    }

    @Test
    void recordTransaction_preservesRegistrationOrder() {
        engine.recordTransaction("TC", "third");
        engine.recordTransaction("TC", "first");
        engine.recordTransaction("TC", "second");
        engine.recordTransaction("TC", "first"); // duplicate must not reorder

        long ts = 1700000000000L;
        engine.recordSample("TC", 100, true, 100, 10, ts, 1, 1);
        engine.recordSample("third", 10, true, 10, 1, ts, 1, 1);
        engine.recordSample("first", 10, true, 10, 1, ts, 1, 1);
        engine.recordSample("second", 10, true, 10, 1, ts, 1, 1);

        ReportData data = engine.buildReportData("test");
        assertEquals(java.util.Arrays.asList("third", "first", "second"),
                data.getTransactionHierarchy().get("TC"));
    }

    // --- Configuration validation (LIST-12) ---

    @Test
    void setMaxResponseBodyLength_rejectsNegative() {
        // A negative limit made the truncating substring in recordError throw for every
        // failed sample; JMeter swallows listener exceptions per event, so error details
        // vanished while the run still looked healthy.
        assertThrows(IllegalArgumentException.class, () -> engine.setMaxResponseBodyLength(-1));

        long ts = 1700000000000L;
        engine.recordError("Sampler1", "500", "Error", ts, "Thread-1", null, null, "body");
        assertEquals(1, engine.getErrorRecords().size(), "default limit still in effect");
    }

    @Test
    void setMaxResponseBodyLength_zeroIsAllowed() {
        engine.setMaxResponseBodyLength(0);

        long ts = 1700000000000L;
        engine.recordError("Sampler1", "500", "Error", ts, "Thread-1", null, null, "body");

        List<ErrorRecord> errors = engine.getErrorRecords();
        assertEquals(1, errors.size());
        assertEquals("...", errors.get(0).getResponseBody());
    }

    // --- Error-percentage population (CORE-9) ---

    @Test
    void errorSummaryPercentages_useThePopulationTheRowsAreDrawnFrom() {
        long ts = 1700000000000L;
        // A transaction controller with two children: 100 parent samples wrapping
        // 100 + 100 child samples. Only the 100 parent samples are top-level.
        engine.recordTransaction("Login Flow", "POST /login");
        engine.recordTransaction("Login Flow", "GET /profile");
        for (int i = 0; i < 100; i++) {
            boolean ok = i >= 10; // 10 failures
            engine.recordSample("Login Flow", 300, ok, 100, 10, ts + i, 1, 1);
            engine.recordSample("POST /login", 150, true, 50, 5, ts + i, 1, 1);
            engine.recordSample("GET /profile", 150, true, 50, 5, ts + i, 1, 1);
            if (!ok) {
                engine.recordError("Login Flow", "500", "Server Error", ts + i, "Thread-1");
            }
        }

        ReportData data = engine.buildReportData("test");

        // The tiles and cicd.json use the top-level population...
        assertEquals(100, data.getTopLevelSampleCount(), "only the TC parent is top-level");
        assertEquals(10, data.getTopLevelErrorCount());
        assertEquals(300, data.getMetadata().getTotalSamples(), "metadata counts every sample");

        // ...but the error rows include transaction children, so their percentage must divide
        // by the population those rows come from. Mixing a child-inclusive numerator with a
        // child-free denominator can push a row past 100% and makes rows disagree with their
        // own total.
        List<ErrorSummary> summaries = data.getErrorSummaries();
        assertEquals(1, summaries.size());
        assertEquals(10.0 / 300.0 * 100.0, summaries.get(0).getPercentageOfAllSamples(), 0.001);
        assertEquals(100.0, summaries.get(0).getPercentageOfErrors(), 0.001);
    }

    @Test
    void errorSummaryPercentages_neverExceedOneHundredPercent() {
        // A transaction controller whose child runs three times per iteration: 50 parent
        // samples, 150 child samples, all failing. The child's 150 errors measured against a
        // top-level population of 50 reads 300% — a child-inclusive numerator over a
        // child-free denominator.
        long ts = 1700000000000L;
        engine.recordTransaction("Login Flow", "POST /login");
        for (int i = 0; i < 50; i++) {
            engine.recordSample("Login Flow", 300, false, 100, 10, ts + i, 1, 1);
            engine.recordError("Login Flow", "500", "Server Error", ts + i, "T1");
            for (int c = 0; c < 3; c++) {
                engine.recordSample("POST /login", 50, false, 50, 5, ts + i, 1, 1);
                engine.recordError("POST /login", "500", "Server Error", ts + i, "T1");
            }
        }

        ReportData data = engine.buildReportData("test");
        assertEquals(50, data.getTopLevelSampleCount());
        assertEquals(200, data.getMetadata().getTotalSamples());

        long summed = 0;
        for (ErrorSummary s : data.getErrorSummaries()) {
            assertTrue(s.getPercentageOfAllSamples() <= 100.0,
                    s.getSamplerName() + " reports " + s.getPercentageOfAllSamples()
                            + "% of all samples");
            summed += s.getOccurrenceCount();
        }
        assertEquals(data.getMetadata().getTotalErrors(), summed,
                "the error rows must sum to the error count they are shown against");
    }

    @Test
    void topLevelCounts_matchTotalsWhenThereIsNoHierarchy() {
        long ts = 1700000000000L;
        for (int i = 0; i < 20; i++) {
            engine.recordSample("GET /api/users", 100, i < 18, 100, 10, ts + i, 1, 1);
        }

        ReportData data = engine.buildReportData("test");

        assertEquals(20, data.getTopLevelSampleCount());
        assertEquals(2, data.getTopLevelErrorCount());
        assertEquals(data.getMetadata().getTotalSamples(), data.getTopLevelSampleCount());
    }

    // --- Per-sampler throughput window (CORE-10) ---

    @Test
    void throughput_windowRunsToTheEndOfTheLastSample() {
        long ts = 1700000000000L;
        // Two samples: starts 1000 ms apart, the second takes 200 ms.
        engine.recordSample("GET /api", 100, true, 0, 0, ts, -1, -1);
        engine.recordSample("GET /api", 200, true, 0, 0, ts + 1000, -1, -1);

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "GET /api");
        // Window is 1200 ms, not 1000 ms — the last sample's elapsed time counts.
        assertEquals(2 / 1.2, s.getThroughput(), 0.0001);
    }

    @Test
    void throughput_singleSampleUsesItsOwnDuration() {
        long ts = 1700000000000L;
        // One 200 ms request. The old code hit a zero-length window and reported
        // "sampleCount per second" — 1/s — regardless of how long the request took.
        engine.recordSample("GET /slow", 200, true, 0, 0, ts, -1, -1);

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "GET /slow");
        assertEquals(5.0, s.getThroughput(), 0.0001);
    }

    @Test
    void throughput_bytesRatesUseTheSameWindow() {
        long ts = 1700000000000L;
        engine.recordSample("GET /api", 200, true, 1000, 500, ts, -1, -1);

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "GET /api");
        assertEquals(5000.0, s.getReceivedBytesPerSec(), 0.0001);
        assertEquals(2500.0, s.getSentBytesPerSec(), 0.0001);
    }

    @Test
    void throughput_zeroElapsedSamplesInOneInstantDoNotDivideByZero() {
        long ts = 1700000000000L;
        for (int i = 0; i < 200; i++) {
            engine.recordSample("instant", 0, true, 0, 0, ts, -1, -1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "instant");
        assertTrue(Double.isFinite(s.getThroughput()), "throughput must stay finite");
        assertTrue(s.getThroughput() > 0);
    }
    // --- Numerical stability and bucket bookkeeping (CORE-13, CORE-17) ---

    @Test
    void standardDeviation_survivesLargeValuesWithSmallSpread() {
        // sumSq/n - mean^2 subtracts two nearly equal huge numbers. Measured, it holds up at
        // hour- and day-scale response times and collapses to exactly 0.0 from about 1e9 ms
        // (~11.5 days) upward — so this is the magnitude where the old formula actually
        // failed, not the hour-scale one the audit estimated. Welford is exact throughout.
        long ts = 1700000000000L;
        long base = 1_000_000_000L;
        long[] values = {base - 2, base - 1, base, base + 1, base + 2};
        for (int i = 0; i < values.length; i++) {
            engine.recordSample("huge", values[i], true, 0, 0, ts + i, -1, -1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "huge");
        assertEquals(Math.sqrt(2.0), s.getStandardDeviation(), 0.0001);
        assertTrue(s.getStandardDeviation() > 0, "a real spread must not collapse to zero");
    }

    @Test
    void standardDeviation_matchesTheTextbookValueForOrdinaryData() {
        long ts = 1700000000000L;
        long[] values = {2, 4, 4, 4, 5, 5, 7, 9};   // population std dev = 2
        for (int i = 0; i < values.length; i++) {
            engine.recordSample("classic", values[i], true, 0, 0, ts + i, -1, -1);
        }

        SamplerStatistics s = findSampler(engine.buildReportData("t"), "classic");
        assertEquals(2.0, s.getStandardDeviation(), 0.0001);
    }

    @Test
    void updateActiveThreads_keepsTheValueEvenWithoutAnExistingBucket() {
        // Only pre-existing buckets used to be updated, so a thread count arriving before its
        // sample — or landing in the next second — was silently dropped.
        long ts = 1700000000000L;
        engine.updateActiveThreads(42, ts);

        List<TimeSeriesBucket> buckets = engine.getTimeSeries();
        assertEquals(1, buckets.size(), "the bucket must be created on demand");
        assertEquals(42, buckets.get(0).getActiveThreads());
    }
}
