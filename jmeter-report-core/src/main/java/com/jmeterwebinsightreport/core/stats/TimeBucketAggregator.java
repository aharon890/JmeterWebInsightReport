package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.TimeSeriesBucket;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Time-bucket aggregator for 1-second granularity time-series.
 * Thread-safe via synchronized methods.
 *
 * <p>The second's totals (the Σ Total lines) pool its top-level samplers only, as the Summary tiles
 * and the Σ Total row count: a transaction's requests are inside it, and counting both made the Σ
 * throughput line add up to 450 requests in a run whose tiles said 50 (P11, T1, campaign F6). Which
 * samplers are nested is known only once the run is over, so it is applied when the bucket is built.
 */
class TimeBucketAggregator {

    private final long timestampMs;
    private int maxActiveThreads;
    private final Map<String, SamplerBucketAccumulator> perSamplerAccum = new LinkedHashMap<>();
    private final Map<String, Long> responseCodes = new TreeMap<>();

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
        perSamplerAccum.computeIfAbsent(samplerName, k -> new SamplerBucketAccumulator())
                .add(responseTime, receivedBytes, sentBytes, connectTime, latency, samples, errors);
    }

    /** A response with this code ended in this second (T5). */
    synchronized void addResponseCode(String code) {
        responseCodes.merge(code, 1L, Long::sum);
    }

    /** {@code samples} of {@code samplerName} started in this second (T7). */
    synchronized void addHits(String samplerName, long samples) {
        perSamplerAccum.computeIfAbsent(samplerName, k -> new SamplerBucketAccumulator()).addHits(samples);
    }

    synchronized void updateActiveThreads(int activeThreads) {
        this.maxActiveThreads = Math.max(this.maxActiveThreads, activeThreads);
    }

    synchronized TimeSeriesBucket toBucket() {
        return toBucket(Collections.emptySet());
    }

    /** @param nested the samplers inside another's samples (transaction children), left out of the totals */
    synchronized TimeSeriesBucket toBucket(Set<String> nested) {
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(Instant.ofEpochMilli(timestampMs));
        bucket.setActiveThreads(maxActiveThreads);
        bucket.setResponseCodes(new LinkedHashMap<>(responseCodes));

        Map<String, TimeSeriesBucket.SamplerBucketData> perSampler = new LinkedHashMap<>();
        SamplerBucketAccumulator pool = new SamplerBucketAccumulator();
        for (Map.Entry<String, SamplerBucketAccumulator> entry : perSamplerAccum.entrySet()) {
            perSampler.put(entry.getKey(), entry.getValue().toBucketData());
            if (!nested.contains(entry.getKey())) {
                entry.getValue().mergeInto(pool);
            }
        }
        bucket.setPerSamplerData(perSampler);

        TimeSeriesBucket.SamplerBucketData total = pool.toBucketData();
        bucket.setTotal(total);
        bucket.setSampleCount(total.getSampleCount());
        bucket.setMeanResponseTime(total.getMeanResponseTime());
        bucket.setThroughput(total.getThroughput()); // samples started per second (1s bucket), T7
        bucket.setErrorRate(total.getErrorRate());
        return bucket;
    }
}
