package com.jmeterwebinsightreport.core.model;

/**
 * A single error occurrence with details.
 */
public class ErrorRecord {

    private String samplerName;
    private String responseCode;
    private String responseMessage;
    private long timestamp;
    private String threadName;
    private String requestUrl;
    private String requestHeaders;
    private String responseBody;
    /** The first failed assertion's message, or null: the reason a 2xx failed (E1). */
    private String failureMessage;

    public ErrorRecord() {
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    /** JMeter's "Type of error" for this failure, which the Errors tab groups by ({@link ErrorType}). */
    public String getErrorType() {
        return ErrorType.of(responseCode, responseMessage, failureMessage);
    }

    public String getSamplerName() {
        return samplerName;
    }

    public void setSamplerName(String samplerName) {
        this.samplerName = samplerName;
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

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getThreadName() {
        return threadName;
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public void setRequestUrl(String requestUrl) {
        this.requestUrl = requestUrl;
    }

    public String getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(String requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
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
     * assertion's message, which both paths read since E1: the Errors tab shows JMeter's type of
     * error instead ({@link #getErrorType()}: that message, or "Assertion failed").
     */
    public boolean isMessageMeaningless() {
        return ErrorClass.of(responseCode) == ErrorClass.NON_PROTOCOL;
    }
}
