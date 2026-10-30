package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.TimeSeriesBucket;

/**
 * Accumulates per-sampler data within a single time bucket.
 *
 * <p>Every response time of the second is kept as an exact count per millisecond
 * ({@link ResponseTimeDistribution}), so the second's percentiles are the Samplers table's rule
 * applied to that second and its histogram bins are true counts. It used to keep a random 128 of
 * them: above 128 requests per second the percentiles and the heatmap cells changed from one
 * build of a file to the next (S11 "Browse Catalog", up to 166/s: 651 of 1,204 per-second
 * P50-P99 values equal between two builds; T6, campaign F16). One second of one sampler holds a
 * handful of distinct values, fewer than the 128 the reservoir always allocated.
 */
class SamplerBucketAccumulator {

    private long sampleCount;
    private long errorCount;
    private long sumResponseTime;
    private long sumConnectTime;
    private long sumLatency;
    private long sumReceivedBytes;
    private long sumSentBytes;
    private boolean hasConnectTime;
    private boolean hasLatency;
    /** For a pool only: the samples its connect time and latency are means over, which are the pooled
     *  samplers' that had them. A sampler's own are over its every sample. */
    private long connectCount = -1;
    private long latencyCount = -1;
    private long maxResponseTime = Long.MIN_VALUE;
    private final ResponseTimeDistribution responseTimes = new ResponseTimeDistribution();

    void add(long responseTime, boolean isSuccess, long receivedBytes, long sentBytes,
             long connectTime, long latency) {
        add(responseTime, receivedBytes, sentBytes, connectTime, latency, 1, isSuccess ? 0 : 1);
    }

    /** A result standing for {@code samples} samples of {@code responseTime / samples} ms each (B6). */
    void add(long responseTime, long receivedBytes, long sentBytes,
             long connectTime, long latency, long samples, long errors) {
        long each = responseTime / samples;
        sampleCount += samples;
        errorCount += errors;
        sumResponseTime += responseTime;
        sumReceivedBytes += receivedBytes;
        sumSentBytes += sentBytes;
        if (each > maxResponseTime) {
            maxResponseTime = each;
        }

        responseTimes.add(each, samples);

        if (connectTime >= 0) {
            sumConnectTime += connectTime;
            hasConnectTime = true;
        }
        if (latency >= 0) {
            sumLatency += latency;
            hasLatency = true;
        }
    }

    /** Add this sampler's second to {@code pool}: the Σ Total of the second is its top-level samplers pooled. */
    void mergeInto(SamplerBucketAccumulator pool) {
        pool.sampleCount += sampleCount;
        pool.errorCount += errorCount;
        pool.sumResponseTime += sumResponseTime;
        pool.sumReceivedBytes += sumReceivedBytes;
        pool.sumSentBytes += sumSentBytes;
        if (hasConnectTime) {
            pool.sumConnectTime += sumConnectTime;
            pool.connectCount = Math.max(0, pool.connectCount) + sampleCount;
            pool.hasConnectTime = true;
        }
        if (hasLatency) {
            pool.sumLatency += sumLatency;
            pool.latencyCount = Math.max(0, pool.latencyCount) + sampleCount;
            pool.hasLatency = true;
        }
        pool.maxResponseTime = Math.max(pool.maxResponseTime, maxResponseTime);
        pool.responseTimes.merge(responseTimes);
    }

    TimeSeriesBucket.SamplerBucketData toBucketData() {
        TimeSeriesBucket.SamplerBucketData data = new TimeSeriesBucket.SamplerBucketData();
        data.setSampleCount(sampleCount);
        data.setMeanResponseTime(sampleCount > 0 ? (double) sumResponseTime / sampleCount : 0);
        data.setErrorRate(sampleCount > 0 ? (errorCount * 100.0) / sampleCount : 0);
        data.setThroughput(sampleCount);
        data.setReceivedBytes(sumReceivedBytes);
        data.setSentBytes(sumSentBytes);

        long connectOver = connectCount >= 0 ? connectCount : sampleCount;
        long latencyOver = latencyCount >= 0 ? latencyCount : sampleCount;
        if (hasConnectTime && connectOver > 0) {
            data.setConnectTime((double) sumConnectTime / connectOver);
        }
        if (hasLatency && latencyOver > 0) {
            data.setLatency((double) sumLatency / latencyOver);
        }

        // The second's percentiles, by the Samplers table's rule, and its histogram, in true counts
        if (responseTimes.getCount() > 0) {
            data.setP50(responseTimes.getPercentile(50));
            data.setP90(responseTimes.getPercentile(90));
            data.setP95(responseTimes.getPercentile(95));
            data.setP99(responseTimes.getPercentile(99));
            data.setMax(maxResponseTime);

            // Histogram bins: [0-50, 50-100, 100-200, 200-500, 500-1000, 1000-2000, 2000-5000, 5000+]
            long[] boundaries = TimeSeriesBucket.SamplerBucketData.BIN_BOUNDARIES;
            int[] bins = new int[boundaries.length + 1];
            long from = Long.MIN_VALUE;
            for (int b = 0; b < boundaries.length; b++) {
                bins[b] = (int) responseTimes.countBetween(from, boundaries[b]);
                from = boundaries[b];
            }
            bins[boundaries.length] = (int) responseTimes.countBetween(from, Long.MAX_VALUE);
            data.setHistogramBins(bins);
        }
        return data;
    }
}
