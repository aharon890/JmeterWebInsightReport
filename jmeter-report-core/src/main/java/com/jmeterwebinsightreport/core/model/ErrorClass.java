package com.jmeterwebinsightreport.core.model;

/**
 * Where a failure was decided, inferred from the response code of a sample JMeter already
 * judged failed.
 *
 * <h2>Why this has to be inferred</h2>
 *
 * <p>Errors are collected on {@code success == false}. A sample failed by a Response Assertion has
 * {@code success=false} and {@code responseCode=200}: a row that reads like a pass. Since E1 both
 * paths read the first failed assertion's message ({@code failureMessage}), which names the
 * assertion's complaint and becomes the error's {@link ErrorType}; the class stays the code's, so a
 * results file saved without that column classifies as the listener does.
 *
 * <p>This class therefore says <strong>where the failure was decided</strong>, never <em>why</em>.
 * It is derived from {@code (success == false, shape of the response code)} and nothing more.
 * That is deliberate: the same function runs on a live listener run and on a JTL re-parsed by
 * {@code -g}, so the same run judged twice gives one answer. A classification that read assertion
 * results would work under {@code -n -t} and silently find nothing under {@code -g} on a JTL saved
 * with default settings — a gate that answers differently depending on which path produced it.
 *
 * <h2>The three read as a set, or not at all</h2>
 *
 * <p>{@link #NON_PROTOCOL} is named against {@link #PROTOCOL}, and {@link #TRANSPORT} is also not
 * a protocol failure — so the set only coheres if PROTOCOL is read strictly as <em>the HTTP status
 * said no</em>. Defined together:
 *
 * <ul>
 *   <li><b>protocol</b> — the server answered, and the status was a failure. 404, 500, 503.</li>
 *   <li><b>nonProtocol</b> — the server answered successfully and the sampler failed it anyway:
 *       an assertion, or a script calling {@code setSuccessful(false)}. We cannot tell which.</li>
 *   <li><b>transport</b> — there is no HTTP status at all. The sampler threw before or instead of
 *       getting a response; JMeter writes the exception into the code field.</li>
 * </ul>
 */
public enum ErrorClass {

    /** The HTTP status said no. Any code in the range a status line can actually carry, outside 2xx. */
    PROTOCOL("protocol", "HTTP failure"),

    /**
     * A 2xx that JMeter still counted as a failure.
     *
     * <p>Called {@code nonProtocol} rather than {@code functional} on purpose: naming it for the
     * cause would claim we read an assertion result, and we did not. A JSR223 marking a sample
     * failed for any reason of its own lands here too.
     */
    NON_PROTOCOL("nonProtocol", "Non-protocol failure (assertion or script)"),

    /** No HTTP status at all — the code field holds an exception. */
    TRANSPORT("transport", "Transport failure (no HTTP response)");

    private final String token;
    private final String label;

    ErrorClass(String token, String label) {
        this.token = token;
        this.label = label;
    }

    /**
     * The machine-readable name, for a metric selector and a payload field.
     *
     * <p>Separate from {@link #label()} from the outset, because the two want different registers:
     * {@code errorRate:class=nonProtocol} is a fine token and a poor thing to print in a table.
     * Deriving one from the other later is how camelCase ends up in a report and someone "fixes"
     * it by letting the two diverge.
     */
    public String token() {
        return token;
    }

    /** What a person reads in a table cell or a chart legend. */
    public String label() {
        return label;
    }

    /**
     * Classify a failed sample by its response code.
     *
     * <p>Only ever called for samples already known to have failed — this does not decide whether
     * something is an error, only what kind. A blank or non-numeric code means no HTTP status was
     * reached at all.
     */
    public static ErrorClass of(String responseCode) {
        if (responseCode == null) {
            return TRANSPORT;
        }
        String code = responseCode.trim();
        if (code.isEmpty()) {
            return TRANSPORT;
        }
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') {
                // "Non HTTP response code: java.net.ConnectException" and anything else JMeter
                // puts here when there was no status line to read.
                return TRANSPORT;
            }
        }
        int status;
        try {
            status = Integer.parseInt(code);
        } catch (NumberFormatException e) {
            return TRANSPORT;   // longer than an int; still not a status
        }
        // Digits are not enough to make something a status. HTTP statuses are 100-599, and
        // JMeter writes "000" when no status line was ever read — a connection refused, a
        // timeout before the first byte, a TLS handshake that failed. Those are transport
        // failures by definition, and they were being filed as "HTTP failure" for a request
        // that never received an HTTP anything.
        //
        // That is the exact failure class errorRate:class=transport was built to gate on, so
        // the rule that most needed to fire counted zero while class=protocol claimed them.
        // Non-HTTP samplers that write numeric codes of their own land here too, which is the
        // same answer for the same reason: no HTTP status was involved.
        if (status < 100 || status > 599) {
            return TRANSPORT;
        }
        // 3xx sits with protocol: a redirect that reached the error set means redirect handling
        // was off or the target failed, which is an HTTP-level story either way. Filing it beside
        // an assertion failure would put "redirect not followed" next to "the body was wrong".
        return status >= 200 && status <= 299 ? NON_PROTOCOL : PROTOCOL;
    }

    /** The token, for callers holding a code rather than a class. */
    public static String tokenOf(String responseCode) {
        return of(responseCode).token();
    }

    /** The display label, for callers holding a code rather than a class. */
    public static String labelOf(String responseCode) {
        return of(responseCode).label();
    }
}
