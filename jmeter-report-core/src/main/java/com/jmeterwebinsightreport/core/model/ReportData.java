package com.jmeterwebinsightreport.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level data model that holds everything needed to render the Web Insight Report.
 */
public class ReportData {

    private TestMetadata metadata;
    private List<SamplerStatistics> samplerStatistics = new ArrayList<>();
    private List<TimeSeriesBucket> timeSeries = new ArrayList<>();
    private List<ErrorSummary> errorSummaries = new ArrayList<>();
    private List<ErrorRecord> errorRecords = new ArrayList<>();
    private List<String> hiddenSamplers = new ArrayList<>();
    private java.util.Map<String, List<String>> transactionHierarchy = new java.util.LinkedHashMap<>();
    /** True when {@link #errorRecords} is a truncated sample (more errors occurred than the detail cap). */
    private boolean errorRecordsTruncated;
    /** Every recorded sample but a Transaction Controller's own results: what the Errors tab's
     *  "% of all samples" divides by, as JMeter's HTML report does (E3). */
    private long requestSampleCount;
    /** Overall response-time percentiles across the merged distribution of top-level samplers
     *  (the true percentile of all samples — not an average of per-sampler percentiles). */
    private double overallMedian;
    private double overallP90;
    private double overallP95;
    private double overallP99;
    /** The Apdex T the scores in {@link #samplerStatistics} were computed against. Carried with
     *  the data because an apdex assertion asserts this configuration as much as the score. */
    private double apdexThreshold = 500;
    /** The whole run as one row: every sample a listener in the test plan received, and only those,
     *  as JMeter's Aggregate Report feeds its TOTAL row. Null on data assembled without it; the
     *  whole-run figures then fall back to adding up the top-level rows. */
    private SamplerStatistics wholeRun;

    /**
     * Live distributions, keyed by sampler name, plus the merged top-level one.
     *
     * <p>Deliberately <em>not</em> bean properties: {@code ReportDataSerializer} serialises this
     * object by reflection over its getters, and a {@code getPercentileSources()} would put a
     * t-digest handle into the HTML payload — where it is meaningless at best. The accessors
     * below drop the {@code get} prefix so Jackson never discovers them, which is also a fair
     * signal that these are a computation aid rather than part of the data contract.
     */
    private transient java.util.Map<String, PercentileSource> percentileSources =
            java.util.Collections.emptyMap();
    private transient PercentileSource overallPercentileSource;

    public ReportData() {
    }

    /**
     * The response-time distribution of one sampler, or {@code null} when this data did not
     * come from a live aggregation.
     *
     * <p>Null is a real case, not an oversight: a {@code ReportData} assembled in a test, or
     * deserialised, has statistics but no digests. Callers resolving a percentile must fall
     * back to the fixed set on {@link SamplerStatistics} rather than assume a source exists.
     */
    public PercentileSource percentileSource(String samplerName) {
        return percentileSources.get(samplerName);
    }

    /** The merged distribution of top-level samplers, or {@code null}. See above. */
    public PercentileSource overallPercentileSource() {
        return overallPercentileSource;
    }

    public void attachPercentileSources(java.util.Map<String, PercentileSource> perSampler,
                                        PercentileSource overall) {
        this.percentileSources = perSampler != null ? perSampler : java.util.Collections.emptyMap();
        this.overallPercentileSource = overall;
    }

    /** The whole run's own row, or null. See {@link #wholeRun}. */
    public SamplerStatistics getWholeRun() { return wholeRun; }
    public void setWholeRun(SamplerStatistics wholeRun) { this.wholeRun = wholeRun; }

    public double getApdexThreshold() { return apdexThreshold; }
    public void setApdexThreshold(double apdexThreshold) { this.apdexThreshold = apdexThreshold; }

    /**
     * The whole run's samples: every sample a listener in the test plan received, a transaction's
     * requests inside it ({@link #getWholeRun()}), as JMeter's Aggregate Report counts its TOTAL row.
     *
     * <p>This is the population the header tiles use, so anything expressed as a percentage
     * "of all samples" must use it too. Dividing by {@code metadata.totalSamples} instead
     * (which counts transaction parents <em>and</em> their children) puts two different
     * error rates on the same report.
     *
     * <p>It is counted per sample, never per name. One name can run on its own and inside a
     * transaction in the same run, and the top-level rows are names: adding them up left every
     * sample of such a name out (tails-conversation-flows, 2026-11: 0 errors where JMeter counted
     * 17), and counted a transaction and its own request of the same name twice. Data assembled
     * without the whole-run row falls back to that sum, and with no rows to the metadata total.
     */
    public long getTopLevelSampleCount() {
        if (wholeRun != null) {
            return wholeRun.getSampleCount();
        }
        if (samplerStatistics == null || samplerStatistics.isEmpty()) {
            return metadata != null ? metadata.getTotalSamples() : 0;
        }
        return samplerStatistics.stream()
                .filter(s -> s.getParentSamplerName() == null)
                .mapToLong(SamplerStatistics::getSampleCount)
                .sum();
    }

    /** Errors across top-level samplers only. See {@link #getTopLevelSampleCount()}. */
    public long getTopLevelErrorCount() {
        if (wholeRun != null) {
            return wholeRun.getErrorCount();
        }
        if (samplerStatistics == null || samplerStatistics.isEmpty()) {
            return metadata != null ? metadata.getTotalErrors() : 0;
        }
        return samplerStatistics.stream()
                .filter(s -> s.getParentSamplerName() == null)
                .mapToLong(SamplerStatistics::getErrorCount)
                .sum();
    }

    public boolean isErrorRecordsTruncated() {
        return errorRecordsTruncated;
    }

    public long getRequestSampleCount() {
        return requestSampleCount;
    }

    public void setRequestSampleCount(long requestSampleCount) {
        this.requestSampleCount = requestSampleCount;
    }

    /**
     * The Errors tab's groups: the failed requests, a Transaction Controller's own results left out —
     * JMeter's HTML report's Errors population ({@code Sample.isController}). A failed transaction is
     * its requests' failure, and counting both listed it twice (pilot-D "Checkout Flow | 500 x69"
     * beside "Payment | 500 x69", E3). Not a bean property: the asserts read
     * {@link #getErrorSummaries()}, every group.
     */
    public List<ErrorSummary> requestErrorSummaries() {
        List<ErrorSummary> requests = new ArrayList<>();
        if (errorSummaries != null) {
            for (ErrorSummary s : errorSummaries) {
                if (!s.isTransactionRow()) {
                    requests.add(s);
                }
            }
        }
        return requests;
    }

    /** The failed requests: the sum of {@link #requestErrorSummaries()}' counts. */
    public long requestErrorCount() {
        long n = 0;
        for (ErrorSummary s : requestErrorSummaries()) {
            n += s.getOccurrenceCount();
        }
        return n;
    }

    public void setErrorRecordsTruncated(boolean errorRecordsTruncated) {
        this.errorRecordsTruncated = errorRecordsTruncated;
    }

    public double getOverallMedian() { return overallMedian; }
    public void setOverallMedian(double overallMedian) { this.overallMedian = overallMedian; }

    public double getOverallP90() { return overallP90; }
    public void setOverallP90(double overallP90) { this.overallP90 = overallP90; }

    public double getOverallP95() { return overallP95; }
    public void setOverallP95(double overallP95) { this.overallP95 = overallP95; }

    public double getOverallP99() { return overallP99; }
    public void setOverallP99(double overallP99) { this.overallP99 = overallP99; }

    // TODO: Builder methods to populate from JMeter's SampleContext data

    public TestMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(TestMetadata metadata) {
        this.metadata = metadata;
    }

    public List<SamplerStatistics> getSamplerStatistics() {
        return samplerStatistics;
    }

    public void setSamplerStatistics(List<SamplerStatistics> samplerStatistics) {
        this.samplerStatistics = samplerStatistics;
    }

    public List<TimeSeriesBucket> getTimeSeries() {
        return timeSeries;
    }

    public void setTimeSeries(List<TimeSeriesBucket> timeSeries) {
        this.timeSeries = timeSeries;
    }

    public List<ErrorSummary> getErrorSummaries() {
        return errorSummaries;
    }

    public void setErrorSummaries(List<ErrorSummary> errorSummaries) {
        this.errorSummaries = errorSummaries;
    }

    public List<ErrorRecord> getErrorRecords() {
        return errorRecords;
    }

    public void setErrorRecords(List<ErrorRecord> errorRecords) {
        this.errorRecords = errorRecords;
    }

    public List<String> getHiddenSamplers() {
        return hiddenSamplers;
    }

    public void setHiddenSamplers(List<String> hiddenSamplers) {
        this.hiddenSamplers = hiddenSamplers;
    }

    public java.util.Map<String, List<String>> getTransactionHierarchy() {
        return transactionHierarchy;
    }

    public void setTransactionHierarchy(java.util.Map<String, List<String>> transactionHierarchy) {
        this.transactionHierarchy = transactionHierarchy;
    }
}
