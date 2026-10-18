package com.jmeterwebinsightreport.core.comparison;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;

import java.util.*;

/**
 * Holds comparison data between a baseline and current test run.
 * Computes per-sampler deltas and flags regressions.
 */
public class ComparisonResult {

    private final ReportData baseline;
    private final ReportData current;
    private final List<SamplerDiff> samplerDiffs;
    private final ComparisonThresholds thresholds;

    public ComparisonResult(ReportData baseline, ReportData current) {
        this(baseline, current, new ComparisonThresholds());
    }

    public ComparisonResult(ReportData baseline, ReportData current, ComparisonThresholds thresholds) {
        this.baseline = baseline;
        this.current = current;
        this.thresholds = thresholds != null ? thresholds : new ComparisonThresholds();
        this.samplerDiffs = computeDiffs();
    }

    public ReportData getBaseline() { return baseline; }
    public ReportData getCurrent() { return current; }
    public List<SamplerDiff> getSamplerDiffs() { return samplerDiffs; }
    public ComparisonThresholds getThresholds() { return thresholds; }

    public boolean hasRegressions() {
        return samplerDiffs.stream().anyMatch(SamplerDiff::isRegression);
    }

    private List<SamplerDiff> computeDiffs() {
        Map<String, SamplerStatistics> baseMap = new LinkedHashMap<>();
        if (baseline.getSamplerStatistics() != null) {
            for (SamplerStatistics s : baseline.getSamplerStatistics()) {
                baseMap.put(s.getSamplerName(), s);
            }
        }

        List<SamplerDiff> diffs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (current.getSamplerStatistics() != null) {
            for (SamplerStatistics curr : current.getSamplerStatistics()) {
                SamplerStatistics base = baseMap.get(curr.getSamplerName());
                diffs.add(new SamplerDiff(curr.getSamplerName(), base, curr, thresholds));
                seen.add(curr.getSamplerName());
            }
        }

        // Samplers present in the baseline but absent from the current run. Iterating only
        // over `current` dropped them entirely: a sampler whose requests all failed to
        // dispatch simply disappeared, with no diff, no flag, and no way for
        // hasRegressions() to notice that a whole endpoint stopped being exercised.
        for (Map.Entry<String, SamplerStatistics> entry : baseMap.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                diffs.add(new SamplerDiff(entry.getKey(), entry.getValue(), null, thresholds));
            }
        }
        return diffs;
    }

    /**
     * Per-sampler comparison showing deltas between baseline and current.
     */
    public static class SamplerDiff {
        private final String samplerName;
        private final SamplerStatistics baseline;
        private final SamplerStatistics current;

        // Computed deltas (current - baseline), null if no baseline
        private final Double deltaMean;
        private final Double deltaP95;
        private final Double deltaP99;
        private final Double deltaErrorRate;
        private final Double deltaThroughput;
        private final boolean regression;
        private final boolean isNew;
        /** Present in the baseline but gone from the current run. */
        private final boolean removed;

        public SamplerDiff(String samplerName, SamplerStatistics baseline, SamplerStatistics current,
                           ComparisonThresholds thresholds) {
            this.samplerName = samplerName;
            this.baseline = baseline;
            this.current = current;
            this.isNew = (baseline == null);
            this.removed = (current == null);

            if (baseline != null && current != null) {
                this.deltaMean = current.getMeanResponseTime() - baseline.getMeanResponseTime();
                this.deltaP95 = current.getPercentile95() - baseline.getPercentile95();
                this.deltaP99 = current.getPercentile99() - baseline.getPercentile99();
                this.deltaErrorRate = current.getErrorRate() - baseline.getErrorRate();
                this.deltaThroughput = current.getThroughput() - baseline.getThroughput();

                // Check each enabled threshold for regression
                boolean regressed = false;

                if (thresholds.isEnabled(thresholds.getP95PctChangeThreshold())) {
                    double p95PctChange = baseline.getPercentile95() > 0
                            ? (deltaP95 / baseline.getPercentile95()) * 100.0
                            : (deltaP95 > 0 ? 100.0 : 0);
                    if (p95PctChange > thresholds.getP95PctChangeThreshold()) regressed = true;
                }
                if (thresholds.isEnabled(thresholds.getErrorRateChangeThreshold())) {
                    double errorIncrease = deltaErrorRate != null ? deltaErrorRate : 0;
                    if (errorIncrease > thresholds.getErrorRateChangeThreshold()) regressed = true;
                }
                if (thresholds.isEnabled(thresholds.getMeanPctChangeThreshold())) {
                    double meanPctChange = baseline.getMeanResponseTime() > 0
                            ? (deltaMean / baseline.getMeanResponseTime()) * 100.0
                            : (deltaMean > 0 ? 100.0 : 0);
                    if (meanPctChange > thresholds.getMeanPctChangeThreshold()) regressed = true;
                }
                if (thresholds.isEnabled(thresholds.getP99PctChangeThreshold())) {
                    double p99PctChange = baseline.getPercentile99() > 0
                            ? (deltaP99 / baseline.getPercentile99()) * 100.0
                            : (deltaP99 > 0 ? 100.0 : 0);
                    if (p99PctChange > thresholds.getP99PctChangeThreshold()) regressed = true;
                }
                if (thresholds.isEnabled(thresholds.getThroughputPctChangeThreshold())) {
                    double tpPctChange = baseline.getThroughput() > 0
                            ? ((baseline.getThroughput() - current.getThroughput()) / baseline.getThroughput()) * 100.0
                            : 0;
                    if (tpPctChange > thresholds.getThroughputPctChangeThreshold()) regressed = true;
                }
                this.regression = regressed;
            } else {
                // New (no baseline) or removed (no current): no deltas to compute. A removed
                // sampler counts as a regression — an endpoint that stopped being exercised
                // at all is precisely the kind of change a pipeline gate should catch.
                this.deltaMean = null;
                this.deltaP95 = null;
                this.deltaP99 = null;
                this.deltaErrorRate = null;
                this.deltaThroughput = null;
                this.regression = (current == null);
            }
        }

        public String getSamplerName() { return samplerName; }
        public SamplerStatistics getBaseline() { return baseline; }
        public SamplerStatistics getCurrent() { return current; }
        public Double getDeltaMean() { return deltaMean; }
        public Double getDeltaP95() { return deltaP95; }
        public Double getDeltaP99() { return deltaP99; }
        public Double getDeltaErrorRate() { return deltaErrorRate; }
        public Double getDeltaThroughput() { return deltaThroughput; }
        public boolean isRegression() { return regression; }
        public boolean isNew() { return isNew; }
        public boolean isRemoved() { return removed; }

        /**
         * Get percentage change for a delta. Positive = worse (for response times).
         */
        // The deltas are null for BOTH a new sampler (no baseline) and a removed one (no
        // current), so these must guard on the delta itself — checking only `baseline` let a
        // removed sampler through and unboxed a null Double.
        public double getP95PctChange() {
            if (deltaP95 == null || baseline == null || baseline.getPercentile95() == 0) return 0;
            return (deltaP95 / baseline.getPercentile95()) * 100.0;
        }

        public double getMeanPctChange() {
            if (deltaMean == null || baseline == null || baseline.getMeanResponseTime() == 0) return 0;
            return (deltaMean / baseline.getMeanResponseTime()) * 100.0;
        }
    }
}
