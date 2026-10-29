package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.TimeSeriesBucket;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Time-bucket aggregator for 1-second granularity time-series.
 * Thread-safe via synchronized methods.
 */
class TimeBucketAggregator {

    private final long timestampMs;
    private long sampleCount;
    private long errorCount;
    private double sumResponseTime;
    private int maxActiveThreads;
    private final Map<String, SamplerBucketAccumulator> perSamplerAccum = new LinkedHashMap<>();

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
        sampleCount += samples;
        errorCount += errors;
        sumResponseTime += responseTime;

        perSamplerAccum.computeIfAbsent(samplerName, k -> new SamplerBucketAccumulator())
                .add(responseTime, receivedBytes, sentBytes, connectTime, latency, samples, errors);
    }

    synchronized void updateActiveThreads(int activeThreads) {
        this.maxActiveThreads = Math.max(this.maxActiveThreads, activeThreads);
    }

    synchronized TimeSeriesBucket toBucket() {
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(Instant.ofEpochMilli(timestampMs));
        bucket.setSampleCount(sampleCount);
        bucket.setMeanResponseTime(sampleCount > 0 ? sumResponseTime / sampleCount : 0);
        bucket.setThroughput(sampleCount); // samples per second (1s bucket)
        bucket.setErrorRate(sampleCount > 0 ? (errorCount * 100.0) / sampleCount : 0);
        bucket.setActiveThreads(maxActiveThreads);

        Map<String, TimeSeriesBucket.SamplerBucketData> perSampler = new LinkedHashMap<>();
        for (Map.Entry<String, SamplerBucketAccumulator> entry : perSamplerAccum.entrySet()) {
            perSampler.put(entry.getKey(), entry.getValue().toBucketData());
        }
        bucket.setPerSamplerData(perSampler);

        return bucket;
    }
}
