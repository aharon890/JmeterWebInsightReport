package com.jmeterwebinsightreport.core.model;

/**
 * Aggregated statistics for a single sampler (request type).
 */
public class SamplerStatistics {

    private String samplerName;
    private long sampleCount;
    private long errorCount;
    private double errorRate;
    private double meanResponseTime;
    private double medianResponseTime;
    private double percentile90;
    private double percentile95;
    private double percentile99;
    private double minResponseTime;
    private double maxResponseTime;
    private double standardDeviation;
    private double throughput;
    private double receivedBytesPerSec;
    private double sentBytesPerSec;
    /**
     * NaN until a sample actually carried a Connect time — <em>not</em> zero.
     *
     * <p>JMeter's Connect and Latency columns are optional, and a JTL saved without them left
     * these primitives at their default 0.0. That is a legitimate-looking measurement, so
     * {@code MetricRef.valueFor} handed 0.0 to the predicate and every "connect time under X"
     * rule reported PASS — on every run, forever, with no finding, no INVALID and no unmatched.
     * A gate silently switched off by a saveservice setting is exactly the failure the assert
     * engine exists to prevent, and {@code MetricRef}'s own comment ("NaN rather than 0: zero is
     * a legitimate measurement and a predicate would happily judge it") already stated the rule
     * one layer above the layer that was breaking it.
     *
     * <p>NaN propagates to {@code MetricRef.valueFor}, which the evaluator turns into INVALID —
     * "the metric is real but this run cannot produce it" — and INVALID counts as a failure.
     * {@link com.jmeterwebinsightreport.core.stats.SamplerAggregator} only assigns these when
     * its guarded count is above zero, so the default is what carries the absence.
     */
    private double meanConnectTime = Double.NaN;
    private double meanLatency = Double.NaN;
    private double totalReceivedBytes;
    private double totalSentBytes;
    private boolean transactionController;
    private String parentSamplerName;
    private java.util.List<String> childSamplerNames;
    private double apdexScore;

    public SamplerStatistics() {
    }

    // TODO: Build from JMeter's MapResultData or from live aggregation

    public String getSamplerName() {
        return samplerName;
    }

    public void setSamplerName(String samplerName) {
        this.samplerName = samplerName;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(long sampleCount) {
        this.sampleCount = sampleCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(long errorCount) {
        this.errorCount = errorCount;
    }

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        this.errorRate = errorRate;
    }

    public double getMeanResponseTime() {
        return meanResponseTime;
    }

    public void setMeanResponseTime(double meanResponseTime) {
        this.meanResponseTime = meanResponseTime;
    }

    public double getMedianResponseTime() {
        return medianResponseTime;
    }

    public void setMedianResponseTime(double medianResponseTime) {
        this.medianResponseTime = medianResponseTime;
    }

    public double getPercentile90() {
        return percentile90;
    }

    public void setPercentile90(double percentile90) {
        this.percentile90 = percentile90;
    }

    public double getPercentile95() {
        return percentile95;
    }

    public void setPercentile95(double percentile95) {
        this.percentile95 = percentile95;
    }

    public double getPercentile99() {
        return percentile99;
    }

    public void setPercentile99(double percentile99) {
        this.percentile99 = percentile99;
    }

    public double getMinResponseTime() {
        return minResponseTime;
    }

    public void setMinResponseTime(double minResponseTime) {
        this.minResponseTime = minResponseTime;
    }

    public double getMaxResponseTime() {
        return maxResponseTime;
    }

    public void setMaxResponseTime(double maxResponseTime) {
        this.maxResponseTime = maxResponseTime;
    }

    public double getStandardDeviation() {
        return standardDeviation;
    }

    public void setStandardDeviation(double standardDeviation) {
        this.standardDeviation = standardDeviation;
    }

    public double getThroughput() {
        return throughput;
    }

    public void setThroughput(double throughput) {
        this.throughput = throughput;
    }

    public double getReceivedBytesPerSec() {
        return receivedBytesPerSec;
    }

    public void setReceivedBytesPerSec(double receivedBytesPerSec) {
        this.receivedBytesPerSec = receivedBytesPerSec;
    }

    public double getSentBytesPerSec() {
        return sentBytesPerSec;
    }

    public void setSentBytesPerSec(double sentBytesPerSec) {
        this.sentBytesPerSec = sentBytesPerSec;
    }

    public double getMeanConnectTime() {
        return meanConnectTime;
    }

    public void setMeanConnectTime(double meanConnectTime) {
        this.meanConnectTime = meanConnectTime;
    }

    public double getMeanLatency() {
        return meanLatency;
    }

    public void setMeanLatency(double meanLatency) {
        this.meanLatency = meanLatency;
    }

    public double getTotalReceivedBytes() {
        return totalReceivedBytes;
    }

    public void setTotalReceivedBytes(double totalReceivedBytes) {
        this.totalReceivedBytes = totalReceivedBytes;
    }

    public double getTotalSentBytes() {
        return totalSentBytes;
    }

    public void setTotalSentBytes(double totalSentBytes) {
        this.totalSentBytes = totalSentBytes;
    }

    public boolean isTransactionController() {
        return transactionController;
    }

    public void setTransactionController(boolean transactionController) {
        this.transactionController = transactionController;
    }

    public String getParentSamplerName() {
        return parentSamplerName;
    }

    public void setParentSamplerName(String parentSamplerName) {
        this.parentSamplerName = parentSamplerName;
    }

    public java.util.List<String> getChildSamplerNames() {
        return childSamplerNames;
    }

    public void setChildSamplerNames(java.util.List<String> childSamplerNames) {
        this.childSamplerNames = childSamplerNames;
    }

    public double getApdexScore() {
        return apdexScore;
    }

    public void setApdexScore(double apdexScore) {
        this.apdexScore = apdexScore;
    }
}
