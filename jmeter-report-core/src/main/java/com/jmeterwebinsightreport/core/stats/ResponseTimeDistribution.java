package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.PercentileSource;

import java.util.Arrays;

/**
 * Every response time a sampler recorded, kept exactly: how many samples took each whole
 * millisecond value.
 *
 * <p>This is what JMeter's Aggregate Report keeps ({@code StatCalculator}, a sorted map of
 * value to count), and it answers a percentile the way the Aggregate Report does: the value
 * at rank {@code round(n * p)} in the sorted samples. So a percentile is always a response
 * time some request really had, and the Samplers table equals the Aggregate Report's.
 *
 * <p>It replaces a t-digest (compression 100) and, per second, a 128-value reservoir (B2 and
 * T6 of the Aggregate Report check). The digest interpolated between centroids, so across a
 * gap in the distribution it answered a time no request took: S24's overall P99 read 1,211 ms
 * where every request took about 20 ms or 3-8 s, and JMeter's is 27 ms. The reservoir kept a
 * random 128 of a busy second, so two builds of one file drew different per-second
 * percentiles. Response times are whole milliseconds, so the counts hold one entry per
 * distinct value: a few thousand for a sampler at most, a handful for one second of one.
 *
 * <p>Not thread-safe: {@link SamplerAggregator} and {@link SamplerBucketAccumulator} guard it.
 */
public final class ResponseTimeDistribution implements PercentileSource {

    /** Distinct values, ascending, in {@code [0, size)}; {@code counts[i]} samples took {@code values[i]}. */
    private long[] values = new long[8];
    private long[] counts = new long[8];
    private int size;
    private long total;

    public void add(long value) {
        add(value, 1);
    }

    /** {@code count} samples that each took {@code value} ms. */
    public void add(long value, long count) {
        if (count <= 0) {
            return;
        }
        int i = Arrays.binarySearch(values, 0, size, value);
        if (i >= 0) {
            counts[i] += count;
        } else {
            i = -i - 1;
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
                counts = Arrays.copyOf(counts, size * 2);
            }
            System.arraycopy(values, i, values, i + 1, size - i);
            System.arraycopy(counts, i, counts, i + 1, size - i);
            values[i] = value;
            counts[i] = count;
            size++;
        }
        total += count;
    }

    /** Adds every sample of {@code other}: the union of the two runs of values, exactly. */
    public void merge(ResponseTimeDistribution other) {
        if (other == null) {
            return;
        }
        for (int i = 0; i < other.size; i++) {
            add(other.values[i], other.counts[i]);
        }
    }

    /**
     * The value at the given percentile (0-100), as JMeter's Aggregate Report answers it.
     *
     * <p>{@code StatCalculator.getPercentPoint}: the k-th smallest value with
     * {@code k = Math.round(n * p)}, the smallest when k is 0, the largest at p >= 1. The
     * fraction is computed in {@code float} first because the Aggregate Report's columns compute
     * it so ({@code StatGraphVisualizer}: {@code Float.parseFloat("90") / 100}), and the float
     * decides some ties: 0.9f is just under 0.9, so the P90 of 5 values is the 4th, where
     * {@code 4.5} would round to the 5th. Every percentile goes through the same rule, so a rule
     * on p90 judges the number the table shows.
     *
     * @return 0 when nothing was recorded
     */
    public double getPercentile(double percentile) {
        if (total == 0) {
            return 0.0;
        }
        double fraction = (float) percentile / 100f;
        if (fraction >= 1.0) {
            return values[size - 1];
        }
        long target = Math.round(total * fraction);
        for (int i = 0; i < size; i++) {
            target -= counts[i];
            if (target <= 0) {
                return values[i];
            }
        }
        return values[size - 1];
    }

    /** The fraction of samples at or below {@code value} (0.0 to 1.0); 0 when nothing was recorded. */
    public double getCdf(double value) {
        if (total == 0) {
            return 0.0;
        }
        long atOrBelow = 0;
        for (int i = 0; i < size && values[i] <= value; i++) {
            atOrBelow += counts[i];
        }
        return (double) atOrBelow / total;
    }

    /** How many samples fall in {@code [from, to)}. */
    public long countBetween(long from, long to) {
        long n = 0;
        for (int i = 0; i < size; i++) {
            if (values[i] >= from && values[i] < to) {
                n += counts[i];
            }
        }
        return n;
    }

    public long getCount() {
        return total;
    }

    @Override
    public double percentile(double percentile) {
        return getPercentile(percentile);
    }

    @Override
    public long sampleCount() {
        return getCount();
    }
}
