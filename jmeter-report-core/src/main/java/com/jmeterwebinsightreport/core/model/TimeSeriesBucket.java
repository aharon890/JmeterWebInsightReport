package com.jmeterwebinsightreport.core.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single time-series data point (bucket) for charting response times, throughput, etc.
 */
public class TimeSeriesBucket {

    private Instant timestamp;
    private long sampleCount;
    private double meanResponseTime;
    private double throughput;
    private double errorRate;
    private int activeThreads;

    /** Per-sampler breakdown within this time bucket. Key = sampler name. */
    private Map<String, SamplerBucketData> perSamplerData = new LinkedHashMap<>();
    /** The second's Σ Total: every top-level sampler's samples pooled, transaction children left out,
     *  the population of the Summary tiles and the Σ Total row (T1). Null on a bucket built by hand. */
    private SamplerBucketData total;
    /** Responses that ended in this second, per response code: every sample but a Transaction
     *  Controller's own result, successes included — JMeter's Codes per Second (T5). */
    private Map<String, Long> responseCodes = new LinkedHashMap<>();

    public TimeSeriesBucket() {
    }

    // TODO: Aggregate from JMeter time-series data or live SampleResults

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(long sampleCount) {
        this.sampleCount = sampleCount;
    }

    public double getMeanResponseTime() {
        return meanResponseTime;
    }

    public void setMeanResponseTime(double meanResponseTime) {
        this.meanResponseTime = meanResponseTime;
    }

    public double getThroughput() {
        return throughput;
    }

    public void setThroughput(double throughput) {
        this.throughput = throughput;
    }

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        this.errorRate = errorRate;
    }

    public int getActiveThreads() {
        return activeThreads;
    }

    public void setActiveThreads(int activeThreads) {
        this.activeThreads = activeThreads;
    }

    public Map<String, SamplerBucketData> getPerSamplerData() {
        return perSamplerData;
    }

    public void setPerSamplerData(Map<String, SamplerBucketData> perSamplerData) {
        this.perSamplerData = perSamplerData;
    }

    public Map<String, Long> getResponseCodes() {
        return responseCodes;
    }

    public void setResponseCodes(Map<String, Long> responseCodes) {
        this.responseCodes = responseCodes;
    }

    public SamplerBucketData getTotal() {
        return total;
    }

    public void setTotal(SamplerBucketData total) {
        this.total = total;
    }

    /**
     * Per-sampler data within a single time bucket.
     */
    public static class SamplerBucketData {
        private long sampleCount;
        private double meanResponseTime;
        private double errorRate;
        private double throughput;
        private double p50;
        private double p90;
        private double p95;
        private double p99;
        private double max;
        // Nullable: null means this bucket had no connect-time / latency data (vs a real 0),
        // so the client can skip it instead of averaging in a spurious zero.
        private Double connectTime;
        private Double latency;
        private double receivedBytes;
        private double sentBytes;
        private int[] histogramBins;

        /** Histogram bin boundaries in ms: [0-50, 50-100, 100-200, 200-500, 500-1000, 1000-2000, 2000-5000, 5000+] */
        public static final long[] BIN_BOUNDARIES = {50, 100, 200, 500, 1000, 2000, 5000};

        public SamplerBucketData() {
        }

        public long getSampleCount() { return sampleCount; }
        public void setSampleCount(long sampleCount) { this.sampleCount = sampleCount; }

        public double getMeanResponseTime() { return meanResponseTime; }
        public void setMeanResponseTime(double meanResponseTime) { this.meanResponseTime = meanResponseTime; }

        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }

        public double getThroughput() { return throughput; }
        public void setThroughput(double throughput) { this.throughput = throughput; }

        public double getP50() { return p50; }
        public void setP50(double p50) { this.p50 = p50; }

        public double getP90() { return p90; }
        public void setP90(double p90) { this.p90 = p90; }

        public double getP95() { return p95; }
        public void setP95(double p95) { this.p95 = p95; }

        public double getP99() { return p99; }
        public void setP99(double p99) { this.p99 = p99; }

        public double getMax() { return max; }
        public void setMax(double max) { this.max = max; }

        public Double getConnectTime() { return connectTime; }
        public void setConnectTime(Double connectTime) { this.connectTime = connectTime; }

        public Double getLatency() { return latency; }
        public void setLatency(Double latency) { this.latency = latency; }

        public double getReceivedBytes() { return receivedBytes; }
        public void setReceivedBytes(double receivedBytes) { this.receivedBytes = receivedBytes; }

        public double getSentBytes() { return sentBytes; }
        public void setSentBytes(double sentBytes) { this.sentBytes = sentBytes; }

        public int[] getHistogramBins() { return histogramBins; }
        public void setHistogramBins(int[] histogramBins) { this.histogramBins = histogramBins; }
    }
}
