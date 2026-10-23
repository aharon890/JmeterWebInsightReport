package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.filter.SamplerFilterConfig;

import java.io.File;

/**
 * Configuration for the Web Insight Report — output directory, title, and the rest.
 * Populated from JMeter properties or GUI configuration panel.
 */
public class ReportConfiguration {

    private File outputDirectory;
    private String reportTitle = "JMeter Web Insight Report";
    private String reportFilename;
    private SamplerFilterConfig filterConfig = new SamplerFilterConfig();
    private File annotationsFile;
    private File baselineJtlFile;
    private long rampUpDurationMillis;
    private boolean externalDataMode;
    private long externalDataThreshold = 10_000_000;
    private boolean generateJunitXml = false;
    public ReportConfiguration() {
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public String getReportTitle() {
        return reportTitle;
    }

    public void setReportTitle(String reportTitle) {
        this.reportTitle = reportTitle;
    }

    public String getReportFilename() {
        return reportFilename;
    }

    public void setReportFilename(String reportFilename) {
        this.reportFilename = reportFilename;
    }

    public SamplerFilterConfig getFilterConfig() {
        return filterConfig;
    }

    public void setFilterConfig(SamplerFilterConfig filterConfig) {
        this.filterConfig = filterConfig;
    }

    public File getAnnotationsFile() {
        return annotationsFile;
    }

    public void setAnnotationsFile(File annotationsFile) {
        this.annotationsFile = annotationsFile;
    }

    public File getBaselineJtlFile() {
        return baselineJtlFile;
    }

    public void setBaselineJtlFile(File baselineJtlFile) {
        this.baselineJtlFile = baselineJtlFile;
    }

    /**
     * The {@code server-metrics.json} a collector wrote for this run, or null.
     *
     * <p>From {@code -Jwebinsight.report.servermetrics} only. Unlike the annotations and the
     * baseline there is no auto-detection: under {@code -g -o} the output directory must be
     * empty, so there is nowhere a payload could be found, and a path that is wrong must be
     * loud rather than silently absent.
     */
    private File serverMetricsFile;

    /**
     * Why the server data given to this -g run could not be collected in-process (a CSV
     * WirCollect refused), or null. Becomes a refused payload: said, never fatal by default.
     */
    private String serverDataProblem;

    /**
     * The temporary payload the in-process collection of a CSV wrote for this report, or null - also
     * null when the server data is a payload somebody gave us, which is theirs and never deleted.
     *
     * <p>Kept apart from {@link #getServerMetricsFile()}, which points at the same file once the
     * collection succeeded, because that one cannot say whose the file is. The rebuild deletes this
     * one as soon as the report has read it: {@code deleteOnExit} alone kept one file per rebuild for
     * as long as the JVM lived, which in a GUI JMeter is the whole working day.
     */
    private File collectedServerPayload;

    public File getCollectedServerPayload() {
        return collectedServerPayload;
    }

    public void setCollectedServerPayload(File collectedServerPayload) {
        this.collectedServerPayload = collectedServerPayload;
    }

    public String getServerDataProblem() {
        return serverDataProblem;
    }

    public void setServerDataProblem(String serverDataProblem) {
        this.serverDataProblem = serverDataProblem;
    }

    public File getServerMetricsFile() {
        return serverMetricsFile;
    }

    public void setServerMetricsFile(File serverMetricsFile) {
        this.serverMetricsFile = serverMetricsFile;
    }

    public long getRampUpDurationMillis() {
        return rampUpDurationMillis;
    }

    public void setRampUpDurationMillis(long rampUpDurationMillis) {
        this.rampUpDurationMillis = rampUpDurationMillis;
    }

    public boolean isExternalDataMode() {
        return externalDataMode;
    }

    public void setExternalDataMode(boolean externalDataMode) {
        this.externalDataMode = externalDataMode;
    }

    public long getExternalDataThreshold() {
        return externalDataThreshold;
    }

    public void setExternalDataThreshold(long externalDataThreshold) {
        this.externalDataThreshold = externalDataThreshold;
    }

    /**
     * Apply a {@code webinsight.report.externaldata.threshold} value, keeping the default when it
     * is unusable.
     *
     * <p>Lives here rather than in each ingestion path so the listener and the {@code -g -o}
     * exporter cannot disagree about what a bad value means. A zero or negative threshold would
     * force external mode for every run, including an empty one, so it is rejected the way
     * {@code webinsight.error.body.maxsize} rejects negatives: warn and keep the default.
     *
     * @return a warning to log, or null when the value was absent or applied cleanly
     */
    public String applyExternalDataThreshold(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(configured.trim());
            if (value <= 0) {
                return "Ignoring webinsight.report.externaldata.threshold '" + configured
                        + "': it must be greater than 0 — keeping " + externalDataThreshold;
            }
            this.externalDataThreshold = value;
            return null;
        } catch (NumberFormatException e) {
            return "Ignoring invalid webinsight.report.externaldata.threshold '" + configured
                    + "' — keeping " + externalDataThreshold;
        }
    }

    public boolean isGenerateJunitXml() {
        return generateJunitXml;
    }

    public void setGenerateJunitXml(boolean generateJunitXml) {
        this.generateJunitXml = generateJunitXml;
    }

    /**
     * The warning for an annotations file picked up from the output directory nobody named.
     *
     * <p>At WARN, and worded the way the baseline auto-detect already is, because this is the
     * more consequential of the two. An annotations file is an <em>input</em> being read out of
     * an <em>output</em> directory: in a shared directory, someone else's thresholds silently
     * decide your run's gate verdict. The report then states PASS or FAIL on rules the operator
     * never wrote and cannot see, which violates the governing invariant more quietly than wrong
     * charts do — a wrong number at least looks like a number, whereas a wrong verdict looks
     * exactly like a right one.
     *
     * <p>Shared between the two ingestion paths so the wording cannot drift, and returned as a
     * String rather than logged here because that is the only form a unit test can assert on.
     */
    public static String annotationsAutoDetectWarning(File annotationsFile) {
        return "Auto-detected an annotations file and applying it: "
                + annotationsFile.getAbsolutePath()
                + " — its assertions will decide this run's gate verdict. Delete it or set "
                + "-Jwebinsight.report.annotations to control this.";
    }
}
