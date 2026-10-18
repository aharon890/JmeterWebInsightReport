package com.jmeterwebinsightreport.core.model;

/**
 * A response-time distribution that can still be asked for an arbitrary quantile after the
 * run has finished.
 *
 * <p>{@link SamplerStatistics} carries four fixed percentiles — median, p90, p95, p99 —
 * because those are the ones the report displays. Assertion rules are not limited to what
 * the report displays: a tail-latency tool that cannot gate on p99.9 is answering a
 * different question than the one being asked. The digest that produced those four numbers
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
 * <p>Not a stylistic preference. Computing the overall percentiles merges every sampler's
 * digest into one, and {@code MergingDigest.add(List)} force-compresses the digests it reads:
 * the source is left with a different centroid layout and answers subsequent queries slightly
 * differently. Measured on a 1..1000 ms ramp, p99 is 991.0 before the merge and 990.5 after.
 * The four stored percentiles are snapshotted before it, this source is read after it, so they
 * disagree by a fraction of a millisecond.
 *
 * <p>The difference is far too small to matter as a measurement and exactly large enough to
 * matter as a verdict: a threshold sitting between the two values makes the report show PASS
 * beside a number, and the pipeline show FAIL against the same named metric. Preferring the
 * stored value keeps "what the report displays is what the gate judged" true by construction,
 * and costs nothing — the quantiles only this source can answer are precisely the ones the
 * report does not display.
 */
public interface PercentileSource {

    /**
     * The estimated value at the given percentile.
     *
     * @param percentile 0–100, fractional allowed ({@code 99.9}, {@code 99.99})
     * @return the estimate, or {@code 0.0} when nothing was recorded
     */
    double percentile(double percentile);

    /** How many values back the estimate. Zero means every quantile is meaningless. */
    long sampleCount();
}
