package com.jmeterwebinsightreport.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a failure was decided, inferred from a response code.
 *
 * <p>The bug this exists for: errors are collected on {@code success == false} and
 * {@code errorType} is the response code verbatim, so a sample failed by a Response Assertion
 * lands in the error set as <em>200 / OK</em> — a row that reads like a pass, counted as an
 * error. Confirmed against a real {@code -g} run before any of this was written.
 */
class ErrorClassTest {

    @Test
    void a2xxThatFailedIsNotAProtocolFailure() {
        // The whole point. JMeter said this sample failed; HTTP said it succeeded.
        assertEquals(ErrorClass.NON_PROTOCOL, ErrorClass.of("200"));
        assertEquals(ErrorClass.NON_PROTOCOL, ErrorClass.of("201"));
        assertEquals(ErrorClass.NON_PROTOCOL, ErrorClass.of("204"));
        assertEquals(ErrorClass.NON_PROTOCOL, ErrorClass.of("299"));
    }

    @Test
    void anHttpStatusThatSaidNoIsProtocol() {
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("400"));
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("404"));
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("500"));
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("503"));
    }

    @Test
    void threeXxSitsWithProtocol() {
        // A redirect reaching the error set means redirect handling was off or the target
        // failed — an HTTP-level story either way. Filing it with 2xx would put "redirect not
        // followed" beside "the body was wrong".
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("301"));
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("302"));
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("399"));
    }

    @Test
    void aCodeFieldHoldingAnExceptionIsTransport() {
        // JMeter writes the exception into the code field when there was no status line at all.
        assertEquals(ErrorClass.TRANSPORT,
                ErrorClass.of("Non HTTP response code: java.net.ConnectException"));
        assertEquals(ErrorClass.TRANSPORT,
                ErrorClass.of("Non HTTP response code: java.net.SocketTimeoutException"));
    }

    @Test
    void anAbsentOrBlankCodeIsTransport() {
        // No status reached at all is the same situation as an exception in the field.
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of(null));
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of(""));
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of("   "));
    }

    @Test
    void aCodeIsTrimmedBeforeItIsRead() {
        assertEquals(ErrorClass.NON_PROTOCOL, ErrorClass.of(" 200 "));
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("\t500"));
    }

    @Test
    void anAbsurdlyLongDigitStringIsTransportRatherThanAnOverflow() {
        // Not a status, and must not throw on the way to saying so.
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of("99999999999999999999"));
    }

    /**
     * "000" is transport, and this is the case the whole three-way split was built for.
     *
     * <p>JMeter writes {@code 000} when no status line was ever read — connection refused, a
     * timeout before the first byte, a TLS handshake that failed. It shipped classified as
     * PROTOCOL, labelled "HTTP failure", for a request that never received an HTTP anything.
     *
     * <p>Which made the failure mode {@code errorRate:class=transport} exists to gate on the one
     * it could not see: a run whose errors were all connection resets counted zero transport
     * failures and reported them as protocol. The rule looked correct and was silent.
     */
    @Test
    void tripleZeroIsTransport_becauseNoStatusLineWasEverRead() {
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of("000"));
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of("0"));
        assertEquals("Transport failure (no HTTP response)", ErrorClass.labelOf("000"));
    }

    /**
     * Digits alone do not make a status. 100-599 is the range a status line can carry; anything
     * outside it came from something that was not speaking HTTP — a JDBC, JMS or TCP sampler
     * writing a code of its own, or a driver's error number.
     *
     * <p>The boundaries are pinned rather than the middle: an off-by-one here silently reclassifies
     * every 100 or every 599 in a run, and nothing downstream would look wrong.
     */
    @Test
    void aNumberOutsideTheStatusRangeIsTransport() {
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of("99"));
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("100"));
        assertEquals(ErrorClass.PROTOCOL, ErrorClass.of("599"));
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of("600"));
        assertEquals(ErrorClass.TRANSPORT, ErrorClass.of("1064"));   // MySQL syntax error
    }

    /**
     * The token and the label are separate from the outset, because they want different
     * registers: {@code errorRate:class=nonProtocol} is a fine selector token and a poor thing
     * to print in a table cell. Deriving one from the other later is how camelCase ends up in a
     * report and someone "fixes" it by letting the two diverge.
     */
    @Test
    void everyClassHasBothATokenAndAReadableLabel() {
        for (ErrorClass c : ErrorClass.values()) {
            assertFalse(c.token().isEmpty(), c.name());
            assertFalse(c.label().isEmpty(), c.name());
            assertNotEquals(c.token(), c.label(), c + " must not use one string for both");
            assertFalse(c.label().equals(c.label().toLowerCase()),
                    c + " label reads like a token: " + c.label());
        }
        assertEquals("nonProtocol", ErrorClass.NON_PROTOCOL.token());
        assertTrue(ErrorClass.NON_PROTOCOL.label().toLowerCase().contains("assertion"),
                "the label has to hint at the cause without claiming we read one");
    }

    @Test
    void theMessageIsMeaninglessOnlyForA2xx() {
        // "OK" is the HTTP reason phrase, not a failure reason. The real one lives in
        // failureMessage, which neither ingestion path captures — so the honest render is
        // nothing rather than a message known to be misleading.
        ErrorSummary functional = summary("200", "OK");
        ErrorSummary protocolFailure = summary("500", "Internal Server Error");
        ErrorSummary transportFailure = summary("Non HTTP response code: x", "Connection refused");

        assertTrue(functional.isMessageMeaningless());
        assertFalse(protocolFailure.isMessageMeaningless(),
                "a 500's message is real and must not be suppressed");
        assertFalse(transportFailure.isMessageMeaningless(),
                "an exception message is the most useful thing on the row");
    }

    @Test
    void theClassRidesOnBothErrorShapes() {
        // The payload carries it on summaries and records alike, so nothing client-side has to
        // re-derive it and disagree.
        assertEquals("nonProtocol", summary("200", "OK").getErrorClass());
        assertEquals("Non-protocol failure (assertion or script)",
                summary("200", "OK").getErrorClassLabel());

        ErrorRecord record = new ErrorRecord();
        record.setResponseCode("503");
        assertEquals("protocol", record.getErrorClass());
        assertEquals("HTTP failure", record.getErrorClassLabel());
    }

    private static ErrorSummary summary(String code, String message) {
        ErrorSummary s = new ErrorSummary();
        s.setResponseCode(code);
        s.setResponseMessage(message);
        return s;
    }
}
