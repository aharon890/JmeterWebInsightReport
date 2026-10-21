package com.jmeterwebinsightreport.report.generator;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settings that reach JMeter under a name nothing reads. Each case is one seen in a real run.
 */
class PropertyCheckTest {

    private static List<PropertyCheck.Problem> check(String... nameValue) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameValue.length; i += 2) {
            m.put(nameValue[i], nameValue[i + 1]);
        }
        return PropertyCheck.check(m);
    }

    @Test
    void thePowerShellSplitIsAConfigErrorThatNamesTheFix() {
        // jmeter.log of the run that found it: "Setting JMeter property: webinsight=.report.annotations=a.json"
        List<PropertyCheck.Problem> p = check("webinsight", ".report.annotations=report-annotations.json");
        assertEquals(1, p.size());
        assertEquals(PropertyCheck.Severity.ERROR, p.get(0).severity());
        String m = p.get(0).message();
        assertTrue(m.contains("PowerShell"), m);
        assertTrue(m.contains("-Jwebinsight.report.annotations=..."), m);
        assertTrue(m.contains("-q run.properties"), m);
    }

    @Test
    void everyNameThePluginReadsIsKnownAndSaysNothing() {
        for (String name : PropertyCheck.KNOWN) {
            assertTrue(check(name, "x").isEmpty(), name);
        }
        assertTrue(check("webinsight.report.servermetrics.timezone", "UTC").isEmpty(),
                "the reader options are a family read by prefix");
        assertTrue(check("jmeter.save.saveservice.output_format", "csv").isEmpty(), "not ours");
    }

    @Test
    void aTypoIsAWarningWithTheNearestName() {
        List<PropertyCheck.Problem> p = check("webinsight.report.servermetric", "s.json");
        assertEquals(1, p.size());
        assertEquals(PropertyCheck.Severity.WARNING, p.get(0).severity());
        assertTrue(p.get(0).message().endsWith("did you mean webinsight.report.servermetrics?"), p.get(0).message());
        assertNull(PropertyCheck.nearest("webinsight.something.else.entirely"));
    }

    @Test
    void aBackslashMangledPathIsNamedWithTheFix() {
        // "C:\tests\run\a.json" in a -q file: Properties.load reads \t as a tab and \r as a CR.
        List<PropertyCheck.Problem> p = check("webinsight.report.annotations", "C:\tests\run\u0007.json");
        assertEquals(1, p.size(), p.toString());
        assertTrue(p.get(0).message().contains("forward slashes"), p.get(0).message());
        assertTrue(check("webinsight.report.annotations", "C:/tests/run/a.json").isEmpty());
    }

    @Test
    void emitReturnsTheErrorsOnlyAndNullWhenThereAreNone() {
        assertNull(PropertyCheck.emit(Collections.<PropertyCheck.Problem>emptyList(), null));
        assertNull(PropertyCheck.emit(check("webinsight.report.servermetric", "x"), null));
        assertNotNull(PropertyCheck.emit(check("webinsight", ".report.title=x"), null));
    }
}
