package com.jmeterwebinsightreport.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** E1: JMeter's "Type of error" (ErrorsSummaryConsumer.getErrorKey, JMeter 5.6.3), case for case. */
class ErrorTypeTest {

    @Test
    void aFailedResponseIsItsCodeAndMessage() {
        assertEquals("500/Internal Server Error", ErrorType.of("500", "Internal Server Error", null));
        assertEquals("500/Database timeout", ErrorType.of("500", "Database timeout", null),
                "pilot-D's other 500: a group of its own");
        assertEquals("404", ErrorType.of("404", "", null), "no message, no slash");
        assertEquals("Non HTTP response code: java.net.ConnectException/Non HTTP response message: refused",
                ErrorType.of("Non HTTP response code: java.net.ConnectException", "Non HTTP response message: refused", null));
        assertEquals("500/Internal Server Error", ErrorType.of("500", "Internal Server Error", "ignored on a 500"),
                "an assertion failing on a 4xx/5xx stays code/message");
    }

    @Test
    void aFailedSuccessIsItsAssertionsMessage() {
        assertEquals("Test failed: text expected to contain /order-id/",
                ErrorType.of("200", "OK", "Test failed: text expected to contain /order-id/"));
        assertEquals("Test failed: x", ErrorType.of("302", "Found", "Test failed: x"), "JMeter's success codes run to 399");
        assertEquals("Assertion failed", ErrorType.of("200", "OK", null), "no failure message saved");
        assertEquals("Assertion failed", ErrorType.of("200", "OK", "   "), "a blank one is none");
    }

    @Test
    void noCodeWithAFailureMessageIsTheMessage() {
        assertEquals("JSR223 said no", ErrorType.of("", "", "JSR223 said no"));
        assertEquals("", ErrorType.of("", "", null), "no code, no message: JMeter's key is empty too");
        assertEquals("/boom", ErrorType.of(null, "boom", null));
    }

    @Test
    void onlyDigitsFrom200To399AreSuccessCodes() {
        assertTrue(ErrorType.isSuccessCode("200"));
        assertTrue(ErrorType.isSuccessCode("399"));
        assertFalse(ErrorType.isSuccessCode("199"));
        assertFalse(ErrorType.isSuccessCode("400"));
        assertFalse(ErrorType.isSuccessCode(" 200"));
        assertFalse(ErrorType.isSuccessCode("2O0"));
        assertFalse(ErrorType.isSuccessCode("99999999999"));
    }
}
