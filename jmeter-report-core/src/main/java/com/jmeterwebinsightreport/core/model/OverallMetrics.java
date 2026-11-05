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
 * <p>Every sample a listener in the test plan received, and only those: a transaction's requests
 * are inside it, and including them weights those samples twice. It is the whole run's own row
 * ({@link ReportData#getWholeRun()}), counted per sample as JMeter's Aggregate Report feeds its
 * TOTAL row, so a name that runs both on its own and inside a transaction counts its own runs.
 * Data assembled without that row falls back to the top-level samplers. This is the same
 * population the header tiles use, so a percentage "of all samples" means the same thing
 * everywhere on the page.
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
    private final double receivedBytesPerSec;
    private final double sentBytesPerSec;
    private final double apdex;
    private final double durationSeconds;
    private final int maxThreads;
    private final PercentileSource percentiles;
    /** The fastest and slowest sample of the run, 0 with no sample: the Σ Total row's Min and Max. */
    private final double min;
    private final double max;

    private OverallMetrics(long totalSamples, long totalErrors, double errorRate, double mean,
                           double median, double p90, double p95, double p99, double throughput,
                           double receivedBytesPerSec, double sentBytesPerSec,
                           double apdex, double durationSeconds, int maxThreads,
                           PercentileSource percentiles, double min, double max) {
        this.totalSamples = totalSamples;
        this.totalErrors = totalErrors;
        this.errorRate = errorRate;
        this.mean = mean;
        this.median = median;
        this.p90 = p90;
        this.p95 = p95;
        this.p99 = p99;
        this.throughput = throughput;
        this.receivedBytesPerSec = receivedBytesPerSec;
        this.sentBytesPerSec = sentBytesPerSec;
        this.apdex = apdex;
        this.durationSeconds = durationSeconds;
        this.maxThreads = maxThreads;
        this.percentiles = percentiles;
        this.min = min;
        this.max = max;
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

        // The population: the whole run's own row, counted per sample as the Aggregate Report feeds
        // its TOTAL row, when the data carries one. Adding up the top-level rows is the fallback for
        // data assembled without it, and it is wrong whenever one name runs both on its own and inside
        // a transaction: such a name is a child row, so all its samples left the whole run (2026-11).
        SamplerStatistics whole = data.getWholeRun();
        List<SamplerStatistics> population = whole != null ? java.util.Collections.singletonList(whole) : topLevel;

        long samples = 0;
        long errors = 0;
        double weightedResponseTime = 0;
        double weightedApdex = 0;
        double satisfied = 0;
        double tolerating = 0;
        boolean apdexCounted = true;
        double min = Double.POSITIVE_INFINITY;
        double max = 0;
        for (SamplerStatistics s : population) {
            if (s.getSampleCount() > 0) {
                min = Math.min(min, s.getMinResponseTime());
                max = Math.max(max, s.getMaxResponseTime());
            }
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
        // The bytes rates likewise: all bytes over the test duration, as JMeter's TOTAL row
        // divides them (B1).
        double throughput;
        double receivedBytesPerSec;
        double sentBytesPerSec;
        if (durationSeconds > 0) {
            double received = 0;
            double sent = 0;
            for (SamplerStatistics s : population) {
                received += s.getTotalReceivedBytes();
                sent += s.getTotalSentBytes();
            }
            throughput = samples / durationSeconds;
            receivedBytesPerSec = received / durationSeconds;
            sentBytesPerSec = sent / durationSeconds;
        } else {
            throughput = 0;
            receivedBytesPerSec = 0;
            sentBytesPerSec = 0;
            for (SamplerStatistics s : population) {
                throughput += s.getThroughput();
                receivedBytesPerSec += s.getReceivedBytesPerSec();
                sentBytesPerSec += s.getSentBytesPerSec();
            }
        }

        // Percentiles of the merged distribution when it survived, which is the true overall
        // percentile. The count-weighted average of per-sampler percentiles is a different and
        // wrong number — the mean of two p95s is not the p95 of the union — but it is what is
        // available for a ReportData that was assembled rather than aggregated, and it beats
        // reporting zero.
        double median = data.getOverallMedian() > 0 ? data.getOverallMedian() : weighted(population, samples, Metric.MEDIAN);
        double p90 = data.getOverallP90() > 0 ? data.getOverallP90() : weighted(population, samples, Metric.P90);
        double p95 = data.getOverallP95() > 0 ? data.getOverallP95() : weighted(population, samples, Metric.P95);
        double p99 = data.getOverallP99() > 0 ? data.getOverallP99() : weighted(population, samples, Metric.P99);

        return new OverallMetrics(
                samples,
                errors,
                samples > 0 ? errors * 100.0 / samples : 0,
                samples > 0 ? weightedResponseTime / samples : 0,
                median, p90, p95, p99,
                throughput, receivedBytesPerSec, sentBytesPerSec,
                // Rounded to 2 dp like the per-sampler scores beside it, once.
                Math.round(apdex * 100.0) / 100.0,
                durationSeconds,
                meta != null ? meta.getTotalThreads() : 0,
                data.overallPercentileSource(),
                min == Double.POSITIVE_INFINITY ? 0 : min, max);
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
    public double getReceivedBytesPerSec() { return receivedBytesPerSec; }
    public double getSentBytesPerSec() { return sentBytesPerSec; }
    public double getApdex() { return apdex; }
    public double getDurationSeconds() { return durationSeconds; }
    public int getMaxThreads() { return maxThreads; }
    public double getMin() { return min; }
    public double getMax() { return max; }
}
