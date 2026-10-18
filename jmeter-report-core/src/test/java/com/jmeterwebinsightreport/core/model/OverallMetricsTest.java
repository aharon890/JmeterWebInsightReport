package com.jmeterwebinsightreport.core.model;

import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One definition of "the run as a whole", so a {@code scope: "overall"} verdict and the number
 * rendered beside it cannot disagree.
 */
class OverallMetricsTest {

    private static SamplerStatistics stat(String name, long count, long errors, double mean,
                                          double p95, double throughput, double apdex) {
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName(name);
        s.setSampleCount(count);
        s.setErrorCount(errors);
        s.setErrorRate(count > 0 ? errors * 100.0 / count : 0);
        s.setMeanResponseTime(mean);
        s.setMedianResponseTime(mean * 0.9);
        s.setPercentile90(p95 * 0.9);
        s.setPercentile95(p95);
        s.setPercentile99(p95 * 1.3);
        s.setThroughput(throughput);
        s.setApdexScore(apdex);
        return s;
    }

    private static ReportData data(long durationMillis, int threads, SamplerStatistics... stats) {
        ReportData d = new ReportData();
        d.setSamplerStatistics(new ArrayList<>(Arrays.asList(stats)));
        TestMetadata m = new TestMetadata();
        m.setDurationMillis(durationMillis);
        m.setTotalThreads(threads);
        m.setStartTime(Instant.ofEpochMilli(1_700_000_000_000L));
        d.setMetadata(m);
        return d;
    }

    @Test
    void transactionChildrenAreExcludedFromEveryFigure() {
        // A child's time is already inside its parent. Counting it again inflates the sample
        // count, and therefore the throughput and the weights of both means — which is how the
        // header tiles and the CI/CD JSON used to be able to disagree.
        SamplerStatistics parent = stat("TC", 100, 0, 200, 400, 5, 0.9);
        SamplerStatistics child = stat("TC-0", 100, 50, 1000, 2000, 5, 0.1);
        child.setParentSamplerName("TC");

        OverallMetrics m = OverallMetrics.of(data(10_000, 4, parent, child));

        assertEquals(100, m.getTotalSamples());
        assertEquals(0, m.getTotalErrors());
        assertEquals(0.0, m.getErrorRate(), 0.0001);
        assertEquals(200.0, m.getMean(), 0.0001);
        assertEquals(0.9, m.getApdex(), 0.0001);
    }

    @Test
    void throughputIsSamplesOverTestDurationNotASumOfPerSamplerRates() {
        // Each per-sampler rate is computed over its own first-to-last-sample window, so adding
        // them over-counts whenever the samplers did not all run for the whole test. Here the
        // sum of the rates is 20/s and the truth is 6/s.
        OverallMetrics m = OverallMetrics.of(data(50_000, 4,
                stat("a", 200, 0, 100, 200, 10, 1.0),
                stat("b", 100, 0, 100, 200, 10, 1.0)));

        assertEquals(6.0, m.getThroughput(), 0.0001);
    }

    @Test
    void throughputFallsBackToSummingRatesOnlyWhenTheDurationIsUnknown() {
        OverallMetrics m = OverallMetrics.of(data(0, 4,
                stat("a", 200, 0, 100, 200, 10, 1.0),
                stat("b", 100, 0, 100, 200, 3, 1.0)));

        assertEquals(13.0, m.getThroughput(), 0.0001);
    }

    @Test
    void apdexIsSampleWeightedAndRoundedLikeThePerSamplerScores() {
        // (0.9*300 + 0.5*100) / 400 = 0.8 exactly; the rounding matters because every
        // per-sampler score on the page is already 2 dp, and an unrounded overall beside them
        // reads as a defect.
        OverallMetrics m = OverallMetrics.of(data(10_000, 4,
                stat("a", 300, 0, 100, 200, 5, 0.9),
                stat("b", 100, 0, 100, 200, 5, 0.5)));

        assertEquals(0.8, m.getApdex(), 0.0001);

        OverallMetrics rounded = OverallMetrics.of(data(10_000, 4,
                stat("a", 1, 0, 100, 200, 5, 0.333),
                stat("b", 2, 0, 100, 200, 5, 0.777)));
        assertEquals(0.63, rounded.getApdex(), 0.0001);
    }

    @Test
    void percentilesComeFromTheMergedDistributionWhenThereIsOne() {
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        for (int i = 1; i <= 1000; i++) {
            engine.recordSample("s", i, true, 500, 100, 1_700_000_000_000L + i, 5, 10);
        }
        ReportData d = engine.buildReportData("T");

        OverallMetrics m = OverallMetrics.of(d);
        assertTrue(m.hasDistribution());
        assertEquals(950.0, m.getP95(), 5.0);
        // The whole reason the digest is retained: this quantile is on no model field.
        assertEquals(999.0, m.percentile(99.9), 2.0);
    }

    @Test
    void withoutADistributionOnlyTheStoredPercentilesCanBeAnswered() {
        // Snapping to the nearest stored value answered "p99.9" with p99 and "p85" with p90 —
        // a tail-latency question answered with a number that is not about the tail, and a rule
        // gating on it reporting a verdict reached from the wrong measurement. NaN is what the
        // per-sampler path has always returned here, and what the evaluator renders INVALID:
        // "this run cannot answer that" is true, and a plainly wrong number is not.
        ReportData d = data(10_000, 4, stat("a", 100, 0, 100, 400, 5, 0.9));
        d.setOverallMedian(90);
        d.setOverallP90(360);
        d.setOverallP95(400);
        d.setOverallP99(520);

        OverallMetrics m = OverallMetrics.of(d);
        assertFalse(m.hasDistribution());
        assertEquals(90.0, m.percentile(50), 0.0001);
        assertEquals(360.0, m.percentile(90), 0.0001);
        assertEquals(400.0, m.percentile(95), 0.0001);
        assertEquals(520.0, m.percentile(99), 0.0001);

        assertTrue(Double.isNaN(m.percentile(99.9)), "p99.9 answered from p99");
        assertTrue(Double.isNaN(m.percentile(85)), "p85 answered from p90");
        assertTrue(Double.isNaN(m.percentile(75)), "p75 answered from p90");
    }

    @Test
    void anEmptyRunProducesZeroesRatherThanNaN() {
        // Division by a zero sample count is the obvious way to put NaN into the CI/CD JSON,
        // where it is not even valid JSON in some parsers.
        OverallMetrics m = OverallMetrics.of(data(10_000, 0));

        assertEquals(0, m.getTotalSamples());
        assertEquals(0.0, m.getErrorRate(), 0.0001);
        assertEquals(0.0, m.getMean(), 0.0001);
        assertEquals(0.0, m.getApdex(), 0.0001);
        assertEquals(0.0, m.getThroughput(), 0.0001);
    }

    @Test
    void theFiguresMatchWhatTheRendererUsedToComputeInline() {
        // Guards the extraction itself: these are the formulas that were in
        // ThymeleafReportRenderer, checked against an independent hand calculation.
        List<SamplerStatistics> stats = Arrays.asList(
                stat("a", 400, 8, 120, 300, 8, 0.95),
                stat("b", 600, 42, 480, 900, 12, 0.70));
        OverallMetrics m = OverallMetrics.of(
                data(20_000, 25, stats.get(0), stats.get(1)));

        assertEquals(1000, m.getTotalSamples());
        assertEquals(50, m.getTotalErrors());
        assertEquals(5.0, m.getErrorRate(), 0.0001);           // 50 / 1000
        assertEquals(336.0, m.getMean(), 0.0001);              // (120*400 + 480*600) / 1000
        assertEquals(50.0, m.getThroughput(), 0.0001);         // 1000 / 20s
        assertEquals(0.8, m.getApdex(), 0.0001);               // (0.95*400 + 0.70*600) / 1000
        assertEquals(20.0, m.getDurationSeconds(), 0.0001);
        assertEquals(25, m.getMaxThreads());
        // No merged distribution here, so p95 is the count-weighted average of the two.
        assertEquals(660.0, m.getP95(), 0.0001);               // (300*400 + 900*600) / 1000
    }
}
