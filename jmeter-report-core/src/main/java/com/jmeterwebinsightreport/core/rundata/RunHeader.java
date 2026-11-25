package com.jmeterwebinsightreport.core.rundata;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code run} record: the first line of every run data file (data-model.md "Run").
 */
public final class RunHeader {

    public static final String TOOL_JMETER = "jmeter";
    public static final String PRODUCER = "jmeter-web-insight-report";

    public String formatVersion = RunDataFormat.VERSION;
    /** Lower case: {@code jmeter}, {@code k6}. It names the tool's tab. */
    public String toolName;
    public String toolVersion;
    /** The report title. It enters the run's identity. */
    public String testName;
    public String producerName = PRODUCER;
    public String producerVersion;
    /** {@code ms}, or {@code s} when the tool's file had whole seconds. */
    public String timeResolution = "ms";
    /** Sample fields the tool does not record: only these read "not measured" on the page. */
    public List<String> notMeasured = new ArrayList<>();
    /**
     * Since format 1.1 (spec 002): the statistics the tool's summary lists for a trend (k6's
     * {@code options.summaryTrendStats}), so the page offers p(99) only when the run's summary has it. Null when the
     * tool did not say.
     */
    public List<String> trendStatistics;

    public RunHeader() {
    }

    public RunHeader(String toolName, String toolVersion, String testName) {
        this.toolName = toolName;
        this.toolVersion = toolVersion;
        this.testName = testName;
    }

    public boolean isJMeter() {
        return TOOL_JMETER.equals(toolName);
    }

    public boolean wholeSeconds() {
        return "s".equals(timeResolution);
    }
}
