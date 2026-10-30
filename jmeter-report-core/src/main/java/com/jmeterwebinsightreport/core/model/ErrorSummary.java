package com.jmeterwebinsightreport.core.model;

/**
 * Aggregated error summary — groups errors by type/code with occurrence counts.
 */
public class ErrorSummary {

    private String samplerName;
    private String errorType;
    private String responseCode;
    private String responseMessage;
    private long occurrenceCount;
    private double percentageOfErrors;
    private double percentageOfAllSamples;
    /** When the group's earliest and latest error started, over every error of it — not only the
     *  first 500 the report keeps in detail (E2). 0 when not known. */
    private long firstTimestamp;
    private long lastTimestamp;
    /** A Transaction Controller's own failed results (their message is "Number of samples in
     *  transaction ..."). The asserts count them, as the Aggregate Report counts a failed transaction;
     *  the Errors tab does not, as JMeter's HTML report does not (E3). */
    private boolean transactionRow;
    /** The group's first failed assertion message, or null (E1). */
    private String failureMessage;

    public ErrorSummary() {
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public boolean isTransactionRow() {
        return transactionRow;
    }

    public void setTransactionRow(boolean transactionRow) {
        this.transactionRow = transactionRow;
    }

    public String getSamplerName() {
        return samplerName;
    }

    public void setSamplerName(String samplerName) {
        this.samplerName = samplerName;
    }

    /** JMeter's "Type of error" of the group ({@link ErrorType}): what the engine grouped by, else
     *  worked out from the group's code, message and failure message. */
    public String getErrorType() {
        return errorType != null ? errorType : ErrorType.of(responseCode, responseMessage, failureMessage);
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(String responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public void setResponseMessage(String responseMessage) {
        this.responseMessage = responseMessage;
    }

    public long getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(long occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public double getPercentageOfErrors() {
        return percentageOfErrors;
    }

    public void setPercentageOfErrors(double percentageOfErrors) {
        this.percentageOfErrors = percentageOfErrors;
    }

    public double getPercentageOfAllSamples() {
        return percentageOfAllSamples;
    }

    public long getFirstTimestamp() {
        return firstTimestamp;
    }

    public void setFirstTimestamp(long firstTimestamp) {
        this.firstTimestamp = firstTimestamp;
    }

    public long getLastTimestamp() {
        return lastTimestamp;
    }

    public void setLastTimestamp(long lastTimestamp) {
        this.lastTimestamp = lastTimestamp;
    }

    public void setPercentageOfAllSamples(double percentageOfAllSamples) {
        this.percentageOfAllSamples = percentageOfAllSamples;
    }

    /**
     * Where this failure was decided — derived from the response code, never stored.
     *
     * <p>Derived so it cannot drift from the code beside it, and so a JTL re-parsed by {@code -g}
     * classifies identically to the live listener run. See {@link ErrorClass}: this says WHERE
     * the failure was decided, never why.
     */
    public String getErrorClass() {
        return ErrorClass.tokenOf(responseCode);
    }

    /** The same thing in the register a table cell wants. */
    public String getErrorClassLabel() {
        return ErrorClass.labelOf(responseCode);
    }

    /**
     * True when the response message says nothing about the failure.
     *
     * <p>A 2xx that a sampler failed carries the HTTP reason phrase — "OK" — which is not a
     * failure reason and is actively misleading in an error table. The real reason is the failed
     * assertion's message, the group's {@link #getErrorType()} since E1, which the tab shows instead.
     */
    public boolean isMessageMeaningless() {
        return ErrorClass.of(responseCode) == ErrorClass.NON_PROTOCOL;
    }
}
