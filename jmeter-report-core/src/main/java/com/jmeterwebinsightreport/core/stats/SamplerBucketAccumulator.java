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
    private long maxResponseTime = Long.MIN_VALUE;
    private final ResponseTimeDistribution responseTimes = new ResponseTimeDistribution();

    void add(long responseTime, boolean isSuccess, long receivedBytes, long sentBytes,
             long connectTime, long latency) {
        sampleCount++;
        if (!isSuccess) errorCount++;
        sumResponseTime += responseTime;
        sumReceivedBytes += receivedBytes;
        sumSentBytes += sentBytes;
        if (responseTime > maxResponseTime) {
            maxResponseTime = responseTime;
        }

        responseTimes.add(responseTime);

        if (connectTime >= 0) {
            sumConnectTime += connectTime;
            hasConnectTime = true;
        }
        if (latency >= 0) {
            sumLatency += latency;
            hasLatency = true;
        }
    }

    TimeSeriesBucket.SamplerBucketData toBucketData() {
        TimeSeriesBucket.SamplerBucketData data = new TimeSeriesBucket.SamplerBucketData();
        data.setSampleCount(sampleCount);
        data.setMeanResponseTime(sampleCount > 0 ? (double) sumResponseTime / sampleCount : 0);
        data.setErrorRate(sampleCount > 0 ? (errorCount * 100.0) / sampleCount : 0);
        data.setThroughput(sampleCount);
        data.setReceivedBytes(sumReceivedBytes);
        data.setSentBytes(sumSentBytes);

        if (hasConnectTime && sampleCount > 0) {
            data.setConnectTime((double) sumConnectTime / sampleCount);
        }
        if (hasLatency && sampleCount > 0) {
            data.setLatency((double) sumLatency / sampleCount);
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
