package com.jmeterwebinsightreport.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The run as a whole, expressed once.
 *
 * <p>Every one of these numbers already existed — scattered. Overall Apdex was a
 * count-weighted mean computed inline in {@code ThymeleafReportRenderer} and never left the
 * template context. Total throughput was computed there too, differently from the per-sampler
 * rates. The error rate was derived independently in the renderer, in
 * {@code CiCdJsonSerializer} and in the payload builder. Nothing forced them to agree.
 *
 * <p>That was survivable while the numbers were only displayed. It stops being survivable
 * once a rule can gate a build on {@code {"scope": "overall"}}: a verdict computed from one
 * definition and rendered next to another is a bug report waiting to be filed, and the
 * report is where it would be filed <em>from</em>.
 *
 * <h2>Population</h2>
 *
 * <p>Top-level samplers only — transaction children are excluded, because their time is
 * already counted inside their parent and including them weights those samples twice. This
 * is the same population the header tiles use, so a percentage "of all samples" means the
 * same thing everywhere on the page.
 */
public final class OverallMetrics {

    private final long totalSamples;
    private final long totalErrors;
    private final double errorRate;
    private final double mean;
    private final double median;
    private final double p90;
    private final double p95;
    private final double p99;
    private final double throughput;
    private final double apdex;
    private final double durationSeconds;
    private final int maxThreads;
    private final PercentileSource percentiles;

    private OverallMetrics(long totalSamples, long totalErrors, double errorRate, double mean,
                           double median, double p90, double p95, double p99, double throughput,
                           double apdex, double durationSeconds, int maxThreads,
                           PercentileSource percentiles) {
        this.totalSamples = totalSamples;
        this.totalErrors = totalErrors;
        this.errorRate = errorRate;
        this.mean = mean;
        this.median = median;
        this.p90 = p90;
        this.p95 = p95;
        this.p99 = p99;
        this.throughput = throughput;
        this.apdex = apdex;
        this.durationSeconds = durationSeconds;
        this.maxThreads = maxThreads;
        this.percentiles = percentiles;
    }

    public static OverallMetrics of(ReportData data) {
        List<SamplerStatistics> topLevel = new ArrayList<>();
        if (data.getSamplerStatistics() != null) {
            for (SamplerStatistics s : data.getSamplerStatistics()) {
                if (s.getParentSamplerName() == null) {
                    topLevel.add(s);
                }
            }
        }

        long samples = 0;
        long errors = 0;
        double weightedResponseTime = 0;
        double weightedApdex = 0;
        double satisfied = 0;
        double tolerating = 0;
        boolean apdexCounted = true;
        for (SamplerStatistics s : topLevel) {
            samples += s.getSampleCount();
            errors += s.getErrorCount();
            weightedResponseTime += s.getMeanResponseTime() * s.getSampleCount();
            weightedApdex += s.getApdexScore() * s.getSampleCount();
            if (Double.isNaN(s.getApdexSatisfied()) || Double.isNaN(s.getApdexTolerating())) {
                apdexCounted = false;
            } else {
                satisfied += s.getApdexSatisfied();
                tolerating += s.getApdexTolerating();
            }
        }
        // From the counts, rounded once (B4). The count-weighted mean of the per-sampler scores
        // rounds every score to 2 dp first, so the whole run was rounded twice; it stays only
        // for statistics assembled without their counts.
        double apdex = samples <= 0 ? 0
                : apdexCounted ? (satisfied + tolerating / 2.0) / samples
                : weightedApdex / samples;

        TestMetadata meta = data.getMetadata();
        double durationSeconds = meta != null ? meta.getDurationMillis() / 1000.0 : 0;

        // Throughput is samples over the *test* duration, not a sum of per-sampler rates: each
        // of those is computed over its own first-to-last-sample window, so adding them up
        // over-counts whenever the samplers did not all run for the whole test. Summing is the
        // fallback only when the duration is unknown.
        double throughput;
        if (durationSeconds > 0) {
            throughput = samples / durationSeconds;
        } else {
            double sum = 0;
            for (SamplerStatistics s : topLevel) {
                sum += s.getThroughput();
            }
            throughput = sum;
        }

        // Percentiles of the merged distribution when it survived, which is the true overall
        // percentile. The count-weighted average of per-sampler percentiles is a different and
        // wrong number — the mean of two p95s is not the p95 of the union — but it is what is
        // available for a ReportData that was assembled rather than aggregated, and it beats
        // reporting zero.
        double median = data.getOverallMedian() > 0 ? data.getOverallMedian() : weighted(topLevel, samples, Metric.MEDIAN);
        double p90 = data.getOverallP90() > 0 ? data.getOverallP90() : weighted(topLevel, samples, Metric.P90);
        double p95 = data.getOverallP95() > 0 ? data.getOverallP95() : weighted(topLevel, samples, Metric.P95);
        double p99 = data.getOverallP99() > 0 ? data.getOverallP99() : weighted(topLevel, samples, Metric.P99);

        return new OverallMetrics(
                samples,
                errors,
                samples > 0 ? errors * 100.0 / samples : 0,
                samples > 0 ? weightedResponseTime / samples : 0,
                median, p90, p95, p99,
                throughput,
                // Rounded to 2 dp like the per-sampler scores beside it, once.
                Math.round(apdex * 100.0) / 100.0,
                durationSeconds,
                meta != null ? meta.getTotalThreads() : 0,
                data.overallPercentileSource());
    }

    private enum Metric { MEDIAN, P90, P95, P99 }

    private static double weighted(List<SamplerStatistics> topLevel, long samples, Metric metric) {
        if (samples <= 0) {
            return 0;
        }
        double sum = 0;
        for (SamplerStatistics s : topLevel) {
            double v;
            switch (metric) {
                case MEDIAN: v = s.getMedianResponseTime(); break;
                case P90:    v = s.getPercentile90(); break;
                case P95:    v = s.getPercentile95(); break;
                default:     v = s.getPercentile99(); break;
            }
            sum += v * s.getSampleCount();
        }
        return sum / samples;
    }

    /**
     * Any quantile of the merged distribution.
     *
     * <p>With no digest, only the four stored percentiles can be answered — and only when the
     * caller asked for one of them exactly. Anything else is NaN.
     *
     * <p>This used to return the nearest stored value, which meant p99.9 was answered with p99
     * and p85 with p90: a tail-latency question answered with a number that is not about the
     * tail, and a rule gating on it reporting PASS or FAIL from the wrong measurement. The
     * method's own comment already argued against interpolating, and then did the same thing
     * by rounding. The per-sampler path ({@code MetricRef.resolvePercentile}) has always
     * returned NaN here, which the evaluator renders INVALID — "this run cannot answer that"
     * is a true statement, and a plainly wrong number is not.
     */
    public double percentile(double percentile) {
        if (percentiles != null && percentiles.sampleCount() > 0) {
            return percentiles.percentile(percentile);
        }
        if (percentile == 50.0) return median;
        if (percentile == 90.0) return p90;
        if (percentile == 95.0) return p95;
        if (percentile == 99.0) return p99;
        return Double.NaN;
    }

    /** True when {@link #percentile} is answering from a real distribution rather than the fallback. */
    public boolean hasDistribution() {
        return percentiles != null && percentiles.sampleCount() > 0;
    }

    public long getTotalSamples() { return totalSamples; }
    public long getTotalErrors() { return totalErrors; }
    public double getErrorRate() { return errorRate; }
    public double getMean() { return mean; }
    public double getMedian() { return median; }
    public double getP90() { return p90; }
    public double getP95() { return p95; }
    public double getP99() { return p99; }
    public double getThroughput() { return throughput; }
    public double getApdex() { return apdex; }
    public double getDurationSeconds() { return durationSeconds; }
    public int getMaxThreads() { return maxThreads; }
}
