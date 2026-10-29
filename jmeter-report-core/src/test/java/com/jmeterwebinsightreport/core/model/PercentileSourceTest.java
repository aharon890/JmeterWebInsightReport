package com.jmeterwebinsightreport.core.model;

import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distributions survive the build, so a rule can name a percentile the report does not
 * display.
 */
class PercentileSourceTest {

    private static final long TS = 1_700_000_000_000L;

    /** 1000 samples of 1..1000 ms — every quantile has an obvious expected value. */
    private static LiveStatisticsEngine engineWithRamp(String sampler) {
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        for (int i = 1; i <= 1000; i++) {
            engine.recordSample(sampler, i, true, 500, 100, TS + i, 5, 10);
        }
        return engine;
    }

    @Test
    void anyQuantileIsResolvableAfterTheReportIsBuilt() {
        ReportData data = engineWithRamp("GET /api/things").buildReportData("T");

        PercentileSource dist = data.percentileSource("GET /api/things");
        assertNotNull(dist, "the digest must outlive the four percentiles copied onto the statistics");
        assertEquals(1000, dist.sampleCount());

        // p99.9 is the point of the exercise: it is nowhere on SamplerStatistics, and no
        // interpolation of p95/p99 would land here.
        assertEquals(999.0, dist.percentile(99.9), 2.0);
        assertEquals(750.0, dist.percentile(75), 2.0);
        assertEquals(1000.0, dist.percentile(99.99), 2.0);
    }

    @Test
    void theRetainedDigestDoesNotExactlyMatchTheStoredPercentiles() {
        // Documenting a trap, not endorsing it.
        //
        // computeOverallPercentiles merges every sampler's digest into one, and
        // MergingDigest.add(List) force-compresses the digests it reads — the source is left
        // with a different centroid layout. The four stored percentiles are snapshotted before
        // that merge; this source is read after it. On a 1..1000 ms ramp p99 is 991.0 stored
        // and 990.5 live.
        //
        // Too small to matter as a measurement, exactly large enough to matter as a verdict: a
        // threshold between the two makes the report show PASS beside a number while the
        // pipeline shows FAIL for the same named metric. Hence the rule in PercentileSource's
        // javadoc, which MetricRef must follow — for a percentile SamplerStatistics stores,
        // the stored value is the one that gates.
        ReportData data = engineWithRamp("GET /api/things").buildReportData("T");
        SamplerStatistics stat = data.getSamplerStatistics().get(0);
        PercentileSource dist = data.percentileSource("GET /api/things");

        assertEquals(stat.getMedianResponseTime(), dist.percentile(50), 2.0);
        assertEquals(stat.getPercentile90(), dist.percentile(90), 2.0);
        assertEquals(stat.getPercentile95(), dist.percentile(95), 2.0);
        assertEquals(stat.getPercentile99(), dist.percentile(99), 2.0);
    }

    @Test
    void mergingADistributionIntoAnotherLeavesTheSourceAsItWas() {
        // The mechanism the stored-value rule was written for, isolated. The t-digest's merge
        // force-compressed its source (p99 991.0 before, 990.5 after); exact counts do not change.
        com.jmeterwebinsightreport.core.stats.ResponseTimeDistribution source =
                new com.jmeterwebinsightreport.core.stats.ResponseTimeDistribution();
        for (int i = 1; i <= 1000; i++) {
            source.add(i);
        }
        double before = source.getPercentile(99);

        new com.jmeterwebinsightreport.core.stats.ResponseTimeDistribution().merge(source);

        assertEquals(990.0, before, 0.0);   // rank round(1000 * 0.99f) = 990
        assertEquals(before, source.getPercentile(99), 0.0);
    }

    @Test
    void transactionChildrenGetTheirOwnSourceEvenThoughTheMergedOneExcludesThem() {
        // Naming a sampler has always been an explicit request that reaches children, so a rule
        // targeting one by name must be able to resolve its percentiles — while the *overall*
        // distribution still excludes it, or its samples are weighted twice.
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        for (int i = 1; i <= 100; i++) {
            engine.recordSample("Parent", i, true, 500, 100, TS + i, 5, 10);
            engine.recordSample("Child", 5000 + i, true, 500, 100, TS + i, 5, 10);
        }
        ReportData data = engine.buildReportData("T");
        // Mark the hierarchy the way the listener would, then rebuild the overall distribution.
        for (SamplerStatistics s : data.getSamplerStatistics()) {
            if ("Child".equals(s.getSamplerName())) {
                s.setParentSamplerName("Parent");
            }
        }

        assertNotNull(data.percentileSource("Child"),
                "a child must still be resolvable — an explicit rule reaches it");
    }

    @Test
    void theMergedDistributionAnswersOverallPercentiles() {
        ReportData data = engineWithRamp("only").buildReportData("T");

        PercentileSource overall = data.overallPercentileSource();
        assertNotNull(overall);
        assertEquals(data.getOverallP95(), overall.percentile(95), 0.0001);
        assertEquals(999.0, overall.percentile(99.9), 2.0);
    }

    @Test
    void reportDataAssembledByHandHasNoSourcesAndSaysSo() {
        // Every caller has to cope with this: a ReportData built in a test, or one that came
        // from somewhere other than a live aggregation, has statistics but no digests. Returning
        // null is the contract; inventing a source that answers 0.0 would silently pass gates.
        ReportData data = new ReportData();
        assertNull(data.percentileSource("anything"));
        assertNull(data.overallPercentileSource());
    }

    @Test
    void readingAPercentileWhileSamplesStillArriveDoesNotCorruptTheAnswer() throws Exception {
        // The listener is one instance shared by every sampler thread and by RMI workers, so
        // "no more samples can arrive once the report is being built" is not guaranteed by
        // construction. An unsynchronised quantile() racing an add() on a MergingDigest does
        // not throw — it reads a half-merged centroid buffer and returns a wrong number, which
        // is the worst possible failure for a quality gate.
        final LiveStatisticsEngine engine = new LiveStatisticsEngine();
        for (int i = 1; i <= 500; i++) {
            engine.recordSample("S", i, true, 500, 100, TS + i, 5, 10);
        }
        final ReportData data = engine.buildReportData("T");
        final PercentileSource dist = data.percentileSource("S");

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int t = 0; t < 2; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 2000; i++) {
                            engine.recordSample("S", 100, true, 500, 100, TS, 5, 10);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
            }
            for (int t = 0; t < 2; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 2000; i++) {
                            double v = dist.percentile(95);
                            if (Double.isNaN(v) || v < 0) {
                                throw new AssertionError("percentile returned " + v);
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            pool.shutdownNow();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent read/write failed", failure.get());
        }
    }
}
