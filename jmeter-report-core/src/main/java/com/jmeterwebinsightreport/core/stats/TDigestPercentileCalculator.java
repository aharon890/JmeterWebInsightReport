package com.jmeterwebinsightreport.core.stats;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.TDigest;

/**
 * Percentile calculator backed by T-Digest for memory-efficient streaming percentile estimation.
 */
public class TDigestPercentileCalculator implements com.jmeterwebinsightreport.core.model.PercentileSource {

    private final TDigest digest;

    public TDigestPercentileCalculator() {
        this(100); // default compression
    }

    public TDigestPercentileCalculator(double compression) {
        this.digest = MergingDigest.createDigest(compression);
    }

    /**
     * Add a value to the digest.
     */
    public void addValue(double value) {
        digest.add(value);
    }

    /**
     * Merge another calculator's distribution into this one — used to compute a true overall
     * percentile across samplers (the percentile of the merged distribution, not an average of
     * per-sampler percentiles).
     */
    public void merge(TDigestPercentileCalculator other) {
        if (other != null && other.digest.size() > 0) {
            this.digest.add(java.util.Collections.singletonList(other.digest));
        }
    }

    /**
     * Get the estimated value at the given percentile (0-100).
     */
    public double getPercentile(double percentile) {
        if (digest.size() == 0) {
            return 0.0;
        }
        return digest.quantile(percentile / 100.0);
    }

    /**
     * Get the cumulative distribution function value at the given threshold.
     * Returns the fraction of values less than or equal to the given value (0.0 to 1.0).
     */
    public double getCdf(double value) {
        if (digest.size() == 0) {
            return 0.0;
        }
        return digest.cdf(value);
    }

    /**
     * Get the number of values added.
     */
    public long getCount() {
        return digest.size();
    }

    // --- PercentileSource: the same two numbers under the names assertion rules ask for. ---

    @Override
    public double percentile(double percentile) {
        return getPercentile(percentile);
    }

    @Override
    public long sampleCount() {
        return getCount();
    }

    /**
     * Reset the digest.
     */
    public void reset() {
        // TDigest doesn't have a reset — create logic will be handled by caller
        // TODO: Consider creating a new instance or tracking state
    }
}
