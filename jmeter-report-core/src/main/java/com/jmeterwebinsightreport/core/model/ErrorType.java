package com.jmeterwebinsightreport.core.model;

/**
 * A failure's "Type of error", as JMeter's HTML report words it in its Errors table and groups its
 * failures by ({@code ErrorsSummaryConsumer.getErrorKey}, JMeter 5.6.3):
 * <ul>
 *   <li>the response code, then {@code /} and the response message when there is one —
 *       {@code 500/Internal Server Error}, {@code 500/Database timeout};</li>
 *   <li>but for a response JMeter counts as a success (a code of 200 to 399) or one with no code and a
 *       failure message, the first failed assertion's message — {@code Test failed: text expected to
 *       contain /order-id/} — or {@code Assertion failed} when none was saved.</li>
 * </ul>
 * The report grouped by response code alone, so one sampler's 500s with two different messages were
 * one row, and every failed assertion on a 200 read "200 / OK" (pilot-D: "Payment | 500 x69" is
 * JMeter's "500/Internal Server Error x44" and "500/Database timeout x25"; its 295 "Search Assert"
 * failures are "Test failed: text expected to contain /order-id/" to JMeter, E1). JMeter escapes the
 * text for its HTML page; the report escapes where it renders, so the text here is the raw one.
 */
public final class ErrorType {

    /** JMeter's type of an assertion failure that saved no message ({@code MetricUtils.ASSERTION_FAILED}). */
    public static final String ASSERTION_FAILED = "Assertion failed";

    private ErrorType() {
    }

    /**
     * @param responseCode   the failed sample's response code
     * @param responseMessage its response message
     * @param failureMessage its first failed assertion's message ({@code failureMessage} in a results
     *                       file, {@code SampleResult.getFirstAssertionFailureMessage()} in a listener), or null
     */
    public static String of(String responseCode, String responseMessage, String failureMessage) {
        String code = responseCode == null ? "" : responseCode;
        if (isSuccessCode(code) || (code.isEmpty() && isNotBlank(failureMessage))) {
            return isNotBlank(failureMessage) ? failureMessage : ASSERTION_FAILED;
        }
        return responseMessage == null || responseMessage.isEmpty() ? code : code + "/" + responseMessage;
    }

    /** {@code MetricUtils.isSuccessCode}: digits only, and 200 to 399. */
    static boolean isSuccessCode(String code) {
        if (code.isEmpty()) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) {
                return false;
            }
        }
        try {
            int status = Integer.parseInt(code);
            return status >= 200 && status <= 399;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@code StringUtils.isNotBlank}. */
    private static boolean isNotBlank(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
