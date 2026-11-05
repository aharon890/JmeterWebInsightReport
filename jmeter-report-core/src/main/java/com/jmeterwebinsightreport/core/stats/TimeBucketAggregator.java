package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.TimeSeriesBucket;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Time-bucket aggregator for 1-second granularity time-series.
 * Thread-safe via synchronized methods.
 *
 * <p>The second's totals (the Σ Total lines) pool the second's samples of the whole run only, as the
 * Summary tiles and the Σ Total row count: a transaction's requests are inside it, and counting both
 * made the Σ throughput line add up to 450 requests in a run whose tiles said 50 (P11, T1, campaign
 * F6). They are pooled as each sample arrives, by its top-level flag, not from the names once the run
 * is over: a name that ran both on its own and inside a transaction is a child name, so pooling by
 * name drew none of its standalone samples (tails, 2026-11: the Σ error line was 0 in every second).
 */
class TimeBucketAggregator {

    private final long timestampMs;
    private int maxActiveThreads;
    private final Map<String, SamplerBucketAccumulator> perSamplerAccum = new LinkedHashMap<>();
    private final Map<String, Long> responseCodes = new TreeMap<>();
    /** The second's samples of the whole run: the Σ Total lines. */
    private final SamplerBucketAccumulator wholeRun = SamplerBucketAccumulator.forTheWholeRun();

    TimeBucketAggregator(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    synchronized void addSample(long responseTime, boolean isSuccess, String samplerName,
                                 long receivedBytes, long sentBytes,
                                 long connectTime, long latency) {
        addSample(responseTime, samplerName, receivedBytes, sentBytes, connectTime, latency,
                1, isSuccess ? 0 : 1);
    }

    /** A result standing for {@code samples} samples, {@code errors} of them failed (B6). */
    synchronized void addSample(long responseTime, String samplerName,
                                 long receivedBytes, long sentBytes,
                                 long connectTime, long latency, long samples, long errors) {
        addSample(responseTime, samplerName, receivedBytes, sentBytes, connectTime, latency, samples, errors, true);
    }

    /** @param topLevel whether the sample is the whole run's ({@code LiveStatisticsEngine.recordSample}) */
    synchronized void addSample(long responseTime, String samplerName,
                                 long receivedBytes, long sentBytes,
                                 long connectTime, long latency, long samples, long errors, boolean topLevel) {
        perSamplerAccum.computeIfAbsent(samplerName, k -> new SamplerBucketAccumulator())
                .add(responseTime, receivedBytes, sentBytes, connectTime, latency, samples, errors);
        if (topLevel) {
            wholeRun.add(responseTime, receivedBytes, sentBytes, connectTime, latency, samples, errors);
        }
    }

    /** A response with this code ended in this second (T5). */
    synchronized void addResponseCode(String code) {
        responseCodes.merge(code, 1L, Long::sum);
    }

    /** {@code samples} of {@code samplerName} started in this second (T7). */
    synchronized void addHits(String samplerName, long samples) {
        addHits(samplerName, samples, true);
    }

    synchronized void addHits(String samplerName, long samples, boolean topLevel) {
        perSamplerAccum.computeIfAbsent(samplerName, k -> new SamplerBucketAccumulator()).addHits(samples);
        if (topLevel) {
            wholeRun.addHits(samples);
        }
    }

    synchronized void updateActiveThreads(int activeThreads) {
        this.maxActiveThreads = Math.max(this.maxActiveThreads, activeThreads);
    }

    synchronized TimeSeriesBucket toBucket() {
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(Instant.ofEpochMilli(timestampMs));
        bucket.setActiveThreads(maxActiveThreads);
        bucket.setResponseCodes(new LinkedHashMap<>(responseCodes));

        Map<String, TimeSeriesBucket.SamplerBucketData> perSampler = new LinkedHashMap<>();
        for (Map.Entry<String, SamplerBucketAccumulator> entry : perSamplerAccum.entrySet()) {
            perSampler.put(entry.getKey(), entry.getValue().toBucketData());
        }
        bucket.setPerSamplerData(perSampler);

        TimeSeriesBucket.SamplerBucketData total = wholeRun.toBucketData();
        bucket.setTotal(total);
        bucket.setSampleCount(total.getSampleCount());
        bucket.setMeanResponseTime(total.getMeanResponseTime());
        bucket.setThroughput(total.getThroughput()); // samples started per second (1s bucket), T7
        bucket.setErrorRate(total.getErrorRate());
        return bucket;
    }
}
