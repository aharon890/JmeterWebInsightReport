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

    public ErrorSummary() {
    }

    public String getSamplerName() {
        return samplerName;
    }

    public void setSamplerName(String samplerName) {
        this.samplerName = samplerName;
    }

    public String getErrorType() {
        return errorType;
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
     * failure reason and is actively misleading in an error table. The real reason lives in
     * {@code failureMessage}, which neither ingestion path captures, so the honest render is
     * nothing at all rather than a message we know is meaningless.
     */
    public boolean isMessageMeaningless() {
        return ErrorClass.of(responseCode) == ErrorClass.NON_PROTOCOL;
    }
}
