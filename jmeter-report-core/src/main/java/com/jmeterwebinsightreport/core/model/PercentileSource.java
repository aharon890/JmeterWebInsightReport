package com.jmeterwebinsightreport.core.model;

/**
 * A response-time distribution that can still be asked for an arbitrary quantile after the
 * run has finished.
 *
 * <p>{@link SamplerStatistics} carries four fixed percentiles — median, p90, p95, p99 —
 * because those are the ones the report displays. Assertion rules are not limited to what
 * the report displays: a tail-latency tool that cannot gate on p99.9 is answering a
 * different question than the one being asked. The distribution that produced those four numbers
 * can answer any of them, but it used to be read four times and dropped on the floor
 * inside {@code SamplerAggregator}.
 *
 * <p>So it is kept, reachable from {@link ReportData}, and any quantile is resolved on
 * demand at evaluation time. The alternative — deciding the percentile set before the
 * statistics are built — would mean loading the annotations file before {@code ReportData}
 * exists, which is an initialisation-order change in both the listener and the {@code -g -o}
 * exporter, for no benefit beyond saving a few kilobytes per sampler.
 *
 * <p>Implementations must be safe to call after the test has ended, from a thread other
 * than the one that fed them.
 *
 * <h2>Which number wins</h2>
 *
 * <p><strong>For a percentile that {@link SamplerStatistics} already stores — p50/median,
 * p90, p95, p99 — resolve the stored value, not this source.</strong> Use this source only
 * for quantiles that have no stored counterpart.
 *
 * <p>It began as a workaround: the overall percentiles were computed by merging every
 * sampler's t-digest, the merge force-compressed the digests it read, and a source read after it
 * answered p99 990.5 where the stored value read 991.0 — a PASS beside a number on the page and
 * a FAIL in the pipeline for one threshold. The distributions are exact counts now
 * ({@code ResponseTimeDistribution}, B2): a merge leaves its source as it was, and the stored
 * value and this source answer the same number. The rule stays because it costs nothing and
 * keeps "what the report displays is what the gate judged" true by construction.
 */
public interface PercentileSource {

    /**
     * The value at the given percentile.
     *
     * @param percentile 0–100, fractional allowed ({@code 99.9}, {@code 99.99})
     * @return the value, or {@code 0.0} when nothing was recorded
     */
    double percentile(double percentile);

    /** How many values back the answer. Zero means every quantile is meaningless. */
    long sampleCount();
}
