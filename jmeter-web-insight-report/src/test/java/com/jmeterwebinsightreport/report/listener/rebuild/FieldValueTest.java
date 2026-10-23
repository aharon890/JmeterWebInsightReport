package com.jmeterwebinsightreport.report.listener.rebuild;

import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The listener panel evaluates a field at the click as JMeter evaluates a property when a test starts,
 * and refuses, in words, every way that evaluation would quietly produce a wrong path (R3). These run
 * JMeter's real function parser and functions ({@code ApacheJMeter_functions} at test scope): a
 * hand-written stand-in would pin the stand-in.
 */
class FieldValueTest {

    @BeforeAll
    static void initJMeterProperties() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File empty = File.createTempFile("wir-test-", ".properties");
            empty.deleteOnExit();
            JMeterUtils.loadJMeterProperties(empty.getAbsolutePath());
        }
    }

    @AfterEach
    void forgetProperties() {
        JMeterUtils.getJMeterProperties().remove("wir.fieldvalue.dir");
    }

    @Test
    void textWithNoFunctionIsReturnedExactlyAsTyped() {
        // The function parser reads a backslash as an escape: a UNC path must never reach it.
        FieldValue unc = FieldValue.evaluate("\\\\host\\share\\x.csv");
        assertNull(unc.refusal());
        assertEquals("\\\\host\\share\\x.csv", unc.value());
        assertFalse(unc.wasEvaluated());

        assertEquals("C:/runs/server.csv", FieldValue.evaluate("  C:/runs/server.csv ").value(), "trimmed");
        assertEquals("", FieldValue.evaluate("").value());
        assertEquals("", FieldValue.evaluate(null).value());
        assertFalse(FieldValue.evaluate(null).isRefused());
    }

    @Test
    void aSetPropertyIsEvaluated() {
        JMeterUtils.setProperty("wir.fieldvalue.dir", "D:/runs/checkout");
        FieldValue value = FieldValue.evaluate("${__P(wir.fieldvalue.dir)}/server.csv");
        assertNull(value.refusal());
        assertEquals("D:/runs/checkout/server.csv", value.value());
        assertTrue(value.wasEvaluated(), "a function ran: the value is the click's");
    }

    @Test
    void aFunctionIsEvaluatedAtTheClick() {
        FieldValue value = FieldValue.evaluate("server-${__time(yyyy-MM-dd)}.csv");
        assertNull(value.refusal());
        assertEquals("server-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv", value.value());
    }

    @Test
    void anUnsetPropertyIsRefusedRatherThanReadAsOne() {
        FieldValue p = FieldValue.evaluate("${__P(wir.fieldvalue.dir)}/server.csv");
        assertTrue(p.isRefused());
        assertEquals("", p.value());
        assertTrue(p.refusal().startsWith("${__P(wir.fieldvalue.dir)} reads the property wir.fieldvalue.dir, which is"
                + " not set, so it would read as 1"), p.refusal());

        FieldValue property = FieldValue.evaluate("${__property(wir.fieldvalue.dir)}");
        assertTrue(property.refusal().contains("would read as its own name, wir.fieldvalue.dir"), property.refusal());

        // A default is the author saying what an unset property means: evaluated, not refused.
        FieldValue withDefault = FieldValue.evaluate("${__P(wir.fieldvalue.dir,C:/runs)}/server.csv");
        assertNull(withDefault.refusal());
        assertEquals("C:/runs/server.csv", withDefault.value());
    }

    @Test
    void aTestPlanVariableIsRefusedNamingItAndPointingAtAProperty() {
        FieldValue value = FieldValue.evaluate("${runsDir}/server.csv");
        assertTrue(value.isRefused());
        assertEquals("${runsDir} is a test-plan variable, which exists only while a test runs - set it as a"
                + " property and write ${__P(runsDir)} instead", value.refusal());
    }

    @Test
    void aMalformedFunctionIsRefusedNotReadAsAnEmptyPath() {
        // The String constructor swallows this and yields "": a path to nowhere, said nowhere.
        FieldValue value = FieldValue.evaluate("${__P(wir.fieldvalue.dir}/server.csv");
        assertTrue(value.isRefused(), value.value());
        assertEquals("", value.value());

        FieldValue unknown = FieldValue.evaluate("${__noSuchFunction(x)}/server.csv");
        assertTrue(unknown.isRefused(), unknown.value());
    }

    @Test
    void aBackslashBeforeAReferenceIsRefusedAsTheCauseNotAsAMissingFunction() {
        JMeterUtils.setProperty("wir.fieldvalue.dir", "mon");
        // Typed by hand on Windows: "\$" is an escape to the function parser, so __P never runs.
        FieldValue typed = FieldValue.evaluate("D:\\runs\\${__P(wir.fieldvalue.dir)}\\server.csv");
        assertTrue(typed.isRefused(), typed.value());
        assertEquals("D:\\runs\\${__P(wir.fieldvalue.dir)}\\server.csv: the backslash before"
                + " ${__P(wir.fieldvalue.dir)} stops JMeter evaluating it - write the path with / instead:"
                + " D:/runs/${__P(wir.fieldvalue.dir)}/server.csv", typed.refusal());
        assertFalse(typed.refusal().contains("no such function"), typed.refusal());

        // A test-plan variable after a backslash: the backslash is still the first thing to fix.
        FieldValue variable = FieldValue.evaluate("D:\\runs\\${runsDir}\\server.csv");
        assertTrue(variable.refusal().contains("the backslash before ${runsDir}"), variable.refusal());

        // The suggestion is the fix: written with /, it evaluates.
        assertEquals("D:/runs/mon/server.csv", FieldValue.evaluate("D:/runs/${__P(wir.fieldvalue.dir)}/server.csv").value());
        // A backslash not directly before the reference escapes nothing, and an escaped backslash
        // ("\\\\" in the field) leaves the reference live: neither is refused.
        assertEquals("D:\\runs\\x-mon.csv", FieldValue.evaluate("D:\\runs\\x-${__P(wir.fieldvalue.dir)}.csv").value());
        assertNull(FieldValue.escapedReference("D:\\runs\\\\${__P(wir.fieldvalue.dir)}"));
    }

    @Test
    void theThreadsVariablesAreRestoredAndNotUsed() {
        JMeterVariables mine = new JMeterVariables();
        mine.put("runsDir", "D:/from-the-function-helper");
        JMeterVariables saved = JMeterContextService.getContext().getVariables();
        JMeterContextService.getContext().setVariables(mine);
        try {
            // A variable that happens to be on this thread is not one the field can rely on.
            assertTrue(FieldValue.evaluate("${runsDir}/server.csv").isRefused());
            assertSame(mine, JMeterContextService.getContext().getVariables(), "the thread's variables were put back");
        } finally {
            JMeterContextService.getContext().setVariables(saved);
        }
    }

    @Test
    void aValueResolvedByTheRunIsTakenAsItIs() {
        FieldValue run = FieldValue.resolved(" D:/runs/${odd}.csv ");
        assertFalse(run.isRefused());
        assertEquals("D:/runs/${odd}.csv", run.value());
        assertFalse(run.wasEvaluated());
    }
}
