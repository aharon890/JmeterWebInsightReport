package com.jmeterwebinsightreport.core.comparison;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComparisonResultTest {

    private SamplerStatistics createStats(String name, double mean, double p95, double p99,
                                          double errorRate, double throughput) {
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName(name);
        s.setMeanResponseTime(mean);
        s.setPercentile95(p95);
        s.setPercentile99(p99);
        s.setErrorRate(errorRate);
        s.setThroughput(throughput);
        return s;
    }

    private ReportData createReportData(SamplerStatistics... stats) {
        ReportData data = new ReportData();
        data.setSamplerStatistics(Arrays.asList(stats));
        return data;
    }

    @Test
    void compare_detectsP95Regression() {
        // baseline P95=100, current P95=120 → 20% increase > 10% threshold → regression
        ReportData baseline = createReportData(
                createStats("Login", 80, 100, 150, 1.0, 10.0));
        ReportData current = createReportData(
                createStats("Login", 90, 120, 170, 1.0, 10.0));

        ComparisonResult result = new ComparisonResult(baseline, current);

        assertTrue(result.hasRegressions());
        ComparisonResult.SamplerDiff diff = result.getSamplerDiffs().get(0);
        assertTrue(diff.isRegression());
        assertEquals(20.0, diff.getDeltaP95(), 0.01);
    }

    @Test
    void compare_detectsErrorRateRegression() {
        // baseline error=1%, current error=5% → 4% increase > 2% threshold → regression
        ReportData baseline = createReportData(
                createStats("Login", 80, 100, 150, 1.0, 10.0));
        ReportData current = createReportData(
                createStats("Login", 80, 100, 150, 5.0, 10.0));

        ComparisonResult result = new ComparisonResult(baseline, current);

        assertTrue(result.hasRegressions());
        ComparisonResult.SamplerDiff diff = result.getSamplerDiffs().get(0);
        assertTrue(diff.isRegression());
    }

    @Test
    void compare_noRegressionWhenWithinThreshold() {
        // P95 increased from 100 to 105 → 5% increase < 10% threshold → no regression
        // Error rate the same → no regression
        ReportData baseline = createReportData(
                createStats("Login", 80, 100, 150, 1.0, 10.0));
        ReportData current = createReportData(
                createStats("Login", 82, 105, 155, 1.5, 10.0));

        ComparisonResult result = new ComparisonResult(baseline, current);

        assertFalse(result.hasRegressions());
        ComparisonResult.SamplerDiff diff = result.getSamplerDiffs().get(0);
        assertFalse(diff.isRegression());
    }

    @Test
    void compare_marksNewSamplers() {
        // "NewAPI" exists in current but not in baseline → isNew=true
        ReportData baseline = createReportData(
                createStats("Login", 80, 100, 150, 1.0, 10.0));
        ReportData current = createReportData(
                createStats("Login", 80, 100, 150, 1.0, 10.0),
                createStats("NewAPI", 50, 70, 90, 0.0, 20.0));

        ComparisonResult result = new ComparisonResult(baseline, current);

        List<ComparisonResult.SamplerDiff> diffs = result.getSamplerDiffs();
        assertEquals(2, diffs.size());

        ComparisonResult.SamplerDiff newDiff = diffs.stream()
                .filter(d -> d.getSamplerName().equals("NewAPI"))
                .findFirst().orElse(null);
        assertNotNull(newDiff);
        assertTrue(newDiff.isNew());
        assertFalse(newDiff.isRegression()); // new samplers are not regressions
        assertNull(newDiff.getDeltaP95()); // no deltas for new samplers
    }

    @Test
    void compare_calculatesDeltaValues() {
        ReportData baseline = createReportData(
                createStats("API", 100, 200, 300, 2.0, 50.0));
        ReportData current = createReportData(
                createStats("API", 120, 220, 330, 3.0, 45.0));

        ComparisonResult result = new ComparisonResult(baseline, current);
        ComparisonResult.SamplerDiff diff = result.getSamplerDiffs().get(0);

        assertEquals(20.0, diff.getDeltaMean(), 0.01);
        assertEquals(20.0, diff.getDeltaP95(), 0.01);
        assertEquals(30.0, diff.getDeltaP99(), 0.01);
        assertEquals(1.0, diff.getDeltaErrorRate(), 0.01);
        assertEquals(-5.0, diff.getDeltaThroughput(), 0.01);
    }

    @Test
    void compare_handlesZeroBaseline() {
        // baseline P95=0, current P95=100 → should not throw, should flag regression
        ReportData baseline = createReportData(
                createStats("API", 0, 0, 0, 0.0, 0.0));
        ReportData current = createReportData(
                createStats("API", 100, 100, 150, 0.0, 10.0));

        ComparisonResult result = new ComparisonResult(baseline, current);
        ComparisonResult.SamplerDiff diff = result.getSamplerDiffs().get(0);

        // When baseline P95=0 and deltaP95>0, pctChange=100% which > 10% → regression
        assertNotNull(diff);
        assertTrue(diff.isRegression());
    }

    @Test
    void compare_identifiesImprovement() {
        // Lower P95 in current → no regression
        ReportData baseline = createReportData(
                createStats("API", 200, 400, 500, 5.0, 10.0));
        ReportData current = createReportData(
                createStats("API", 150, 300, 400, 3.0, 15.0));

        ComparisonResult result = new ComparisonResult(baseline, current);

        assertFalse(result.hasRegressions());
        ComparisonResult.SamplerDiff diff = result.getSamplerDiffs().get(0);
        assertFalse(diff.isRegression());
        assertTrue(diff.getDeltaP95() < 0); // improvement
        assertTrue(diff.getDeltaMean() < 0); // improvement
    }

    @Test
    void compare_emptyBaseline() {
        // Empty baseline → all current samplers are "new"
        ReportData baseline = createReportData();
        ReportData current = createReportData(
                createStats("API1", 100, 200, 300, 1.0, 10.0),
                createStats("API2", 50, 80, 100, 0.0, 20.0));

        ComparisonResult result = new ComparisonResult(baseline, current);

        assertEquals(2, result.getSamplerDiffs().size());
        for (ComparisonResult.SamplerDiff diff : result.getSamplerDiffs()) {
            assertTrue(diff.isNew());
            assertNull(diff.getDeltaP95());
        }
        assertFalse(result.hasRegressions()); // new samplers don't count as regressions
    }

    @Test
    void compare_customThresholds_detectsRegression() {
        // P95 threshold set to 5%. Baseline P95=100, current P95=108 → 8% increase > 5% → regression
        ComparisonThresholds thresholds = new ComparisonThresholds();
        thresholds.setP95PctChangeThreshold(5.0);

        ReportData baseline = createReportData(
                createStats("API", 80, 100, 150, 1.0, 10.0));
        ReportData current = createReportData(
                createStats("API", 85, 108, 160, 1.0, 10.0));

        ComparisonResult result = new ComparisonResult(baseline, current, thresholds);

        assertTrue(result.hasRegressions());
        ComparisonResult.SamplerDiff diff = result.getSamplerDiffs().get(0);
        assertTrue(diff.isRegression());
    }

    @Test
    void compare_disabledThreshold_noRegression() {
        // P95 threshold disabled (-1). Even with 50% P95 increase, no regression on p95.
        // Error rate threshold also disabled.
        ComparisonThresholds thresholds = new ComparisonThresholds();
        thresholds.setP95PctChangeThreshold(-1);
        thresholds.setErrorRateChangeThreshold(-1);

        ReportData baseline = createReportData(
                createStats("API", 100, 100, 150, 1.0, 10.0));
        ReportData current = createReportData(
                createStats("API", 150, 150, 225, 1.0, 10.0));

        ComparisonResult result = new ComparisonResult(baseline, current, thresholds);

        assertFalse(result.hasRegressions());
        ComparisonResult.SamplerDiff diff = result.getSamplerDiffs().get(0);
        assertFalse(diff.isRegression());
    }

    @Test
    void compare_customErrorRateThreshold() {
        // Error rate threshold set to 5%
        ComparisonThresholds thresholds = new ComparisonThresholds();
        thresholds.setP95PctChangeThreshold(-1); // disable p95 check
        thresholds.setErrorRateChangeThreshold(5.0);

        // 4% error increase → under 5% threshold → no regression
        ReportData baseline1 = createReportData(
                createStats("API", 100, 200, 300, 1.0, 10.0));
        ReportData current1 = createReportData(
                createStats("API", 100, 200, 300, 5.0, 10.0));

        ComparisonResult result1 = new ComparisonResult(baseline1, current1, thresholds);
        assertFalse(result1.hasRegressions(),
                "4% error increase should not trigger regression at 5% threshold");

        // 6% error increase → over 5% threshold → regression
        ReportData baseline2 = createReportData(
                createStats("API", 100, 200, 300, 1.0, 10.0));
        ReportData current2 = createReportData(
                createStats("API", 100, 200, 300, 7.0, 10.0));

        ComparisonResult result2 = new ComparisonResult(baseline2, current2, thresholds);
        assertTrue(result2.hasRegressions(),
                "6% error increase should trigger regression at 5% threshold");
    }

    // --- Samplers that disappeared from the current run (CORE-15) ---

    @Test
    void baselineOnlySamplerIsReportedAsRemoved() {
        // Diffs were built only over `current`, so a sampler whose requests all failed to
        // dispatch simply vanished: no diff, no flag, and hasRegressions() none the wiser.
        ReportData baseline = reportWith(sampler("GET /api/users", 100, 200),
                                         sampler("POST /api/checkout", 300, 500));
        ReportData current = reportWith(sampler("GET /api/users", 110, 210));

        ComparisonResult result = new ComparisonResult(baseline, current, new ComparisonThresholds());

        ComparisonResult.SamplerDiff gone = result.getSamplerDiffs().stream()
                .filter(d -> "POST /api/checkout".equals(d.getSamplerName()))
                .findFirst().orElse(null);

        assertNotNull(gone, "a baseline-only sampler must still produce a diff");
        assertTrue(gone.isRemoved());
        assertFalse(gone.isNew());
        assertNull(gone.getCurrent());
        assertNotNull(gone.getBaseline());
        assertTrue(result.hasRegressions(), "an endpoint that stopped running is a regression");
    }

    @Test
    void currentOnlySamplerIsStillReportedAsNew() {
        ReportData baseline = reportWith(sampler("GET /api/users", 100, 200));
        ReportData current = reportWith(sampler("GET /api/users", 110, 210),
                                        sampler("GET /api/new", 50, 90));

        ComparisonResult result = new ComparisonResult(baseline, current, new ComparisonThresholds());

        ComparisonResult.SamplerDiff added = result.getSamplerDiffs().stream()
                .filter(d -> "GET /api/new".equals(d.getSamplerName()))
                .findFirst().orElseThrow(() -> new AssertionError("new sampler missing"));
        assertTrue(added.isNew());
        assertFalse(added.isRemoved());
    }

    private static SamplerStatistics sampler(String name, double mean, double p95) {
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName(name);
        s.setMeanResponseTime(mean);
        s.setPercentile95(p95);
        s.setPercentile99(p95 * 1.2);
        s.setThroughput(10);
        s.setSampleCount(100);
        return s;
    }

    private static ReportData reportWith(SamplerStatistics... stats) {
        ReportData d = new ReportData();
        d.setSamplerStatistics(new ArrayList<>(Arrays.asList(stats)));
        return d;
    }
}