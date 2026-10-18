package com.jmeterwebinsightreport.core.model;

import java.time.Instant;

/**
 * Metadata about the test execution — name, start/end times, duration, thread counts, etc.
 */
public class TestMetadata {

    private String testName;
    private Instant startTime;
    private Instant endTime;
    private long durationMillis;
    private int totalThreads;
    /** long, not int: (int) casting the engine's AtomicLong overflowed silently past
     *  2^31 samples — reachable on a multi-day soak at high rate. */
    private long totalSamples;
    private long totalErrors;
    private String jmeterVersion;
    private String reportGeneratedAt;
    private String reportGeneratedAtIso;
    private long rampUpDurationMillis;

    public TestMetadata() {
    }

    // TODO: Populate from JMeter's SampleContext / ReportGenerator data

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public int getTotalThreads() {
        return totalThreads;
    }

    public void setTotalThreads(int totalThreads) {
        this.totalThreads = totalThreads;
    }

    public long getTotalSamples() {
        return totalSamples;
    }

    public void setTotalSamples(long totalSamples) {
        this.totalSamples = totalSamples;
    }

    public long getTotalErrors() {
        return totalErrors;
    }

    public void setTotalErrors(long totalErrors) {
        this.totalErrors = totalErrors;
    }

    public String getJmeterVersion() {
        return jmeterVersion;
    }

    public void setJmeterVersion(String jmeterVersion) {
        this.jmeterVersion = jmeterVersion;
    }

    public String getReportGeneratedAt() {
        return reportGeneratedAt;
    }

    public void setReportGeneratedAt(String reportGeneratedAt) {
        this.reportGeneratedAt = reportGeneratedAt;
    }

    /**
     * The same moment as {@link #getReportGeneratedAt()}, as an ISO-8601 instant
     * ("2026-10-03T13:10:16.123Z"). The formatted string is in the generating JVM's zone and is the
     * CI/CD JSON's {@code generatedAt}, so it stays as it is; the page reads this one to write the
     * header in the reader's zone (SF38). Not part of the run's identity, and not in the JSON.
     */
    public String getReportGeneratedAtIso() {
        return reportGeneratedAtIso;
    }

    public void setReportGeneratedAtIso(String reportGeneratedAtIso) {
        this.reportGeneratedAtIso = reportGeneratedAtIso;
    }

    public long getRampUpDurationMillis() {
        return rampUpDurationMillis;
    }

    public void setRampUpDurationMillis(long rampUpDurationMillis) {
        this.rampUpDurationMillis = rampUpDurationMillis;
    }
}
