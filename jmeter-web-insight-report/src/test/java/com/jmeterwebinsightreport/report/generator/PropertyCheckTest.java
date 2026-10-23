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
        assertTrue(check("webinsight.report.servermetrics.timezone", "UTC").isEmpty(), "a reader option");
        assertTrue(check("jmeter.save.saveservice.output_format", "csv").isEmpty(), "not ours");
    }

    @Test
    void aMisspeltReaderOptionIsAWarningWithTheOptionItMeant() {
        // The CLI flag's hyphen, and a doubled letter: both were accepted by a prefix and never read.
        List<PropertyCheck.Problem> p = check("webinsight.report.servermetrics.time-zone", "Europe/Berlin");
        assertEquals(1, p.size(), p.toString());
        assertEquals(PropertyCheck.Severity.WARNING, p.get(0).severity());
        assertTrue(p.get(0).message().startsWith("the JMeter property webinsight.report.servermetrics.time-zone is"
                + " not one the Web Insight plugin reads"), p.get(0).message());
        assertTrue(p.get(0).message().endsWith("did you mean webinsight.report.servermetrics.timezone?"),
                p.get(0).message());
        assertEquals("webinsight.report.servermetrics.step", PropertyCheck.nearest("webinsight.report.servermetrics.stepp"));
    }

    /**
     * The reader options are the exporter's {@code CSV_OPTIONS}, read reflectively because that
     * constant is package-private in another package: an option added there and not here would be
     * read by the exporter and warned about as inert, and one here and not there would be passed
     * by the check and never read.
     */
    @Test
    void theReaderOptionsAreTheOnesTheExporterReads() throws Exception {
        java.lang.reflect.Field f = com.jmeterwebinsightreport.report.exporter.WebInsightDataExporter.class
                .getDeclaredField("CSV_OPTIONS");
        f.setAccessible(true);
        List<String> read = new java.util.ArrayList<>();
        for (String[] option : (String[][]) f.get(null)) {
            read.add(option[0]);
        }
        assertEquals(read, PropertyCheck.SERVER_DATA_OPTIONS);
        for (String option : read) {
            assertTrue(PropertyCheck.KNOWN.contains("webinsight.report.servermetrics." + option), option);
        }
    }

    /**
     * Every {@code "webinsight.…"} string literal in either module's main sources and resources is
     * a name {@link PropertyCheck} knows, so a property read somewhere new and not added to KNOWN
     * fails here rather than printing "does nothing" on every run that sets it. Two literals are
     * not property names and are exempt by name: {@code webinsight.report.servermetrics.}, the
     * prefix the exporter concatenates with each reader option (pinned by the test above), and
     * {@code webinsight.runId}, a JUnit XML {@code <property>} the plugin writes, never reads.
     */
    @Test
    void everyPropertyNameInTheSourcesIsKnown() throws Exception {
        java.util.Set<String> exempt = new java.util.HashSet<>(java.util.Arrays.asList(
                "webinsight.report.servermetrics.", "webinsight.runId"));
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile("([\"'])(webinsight\\.[a-zA-Z0-9.]+)\\1");
        java.nio.file.Path root = repoRoot();
        java.util.Set<String> unknown = new java.util.TreeSet<>();
        int scanned = 0;
        for (String module : new String[] {"jmeter-report-core", "jmeter-web-insight-report"}) {
            for (String tree : new String[] {"src/main/java", "src/main/resources"}) {
                java.nio.file.Path dir = root.resolve(module).resolve(tree);
                if (!java.nio.file.Files.isDirectory(dir)) {
                    continue;
                }
                List<java.nio.file.Path> files;
                try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
                    files = walk.filter(java.nio.file.Files::isRegularFile)
                            .filter(p -> p.toString().matches(".*\\.(java|js|html|properties)$"))
                            .collect(java.util.stream.Collectors.toList());
                }
                for (java.nio.file.Path file : files) {
                    String text = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher m = literal.matcher(text);
                    while (m.find()) {
                        scanned++;
                        String name = m.group(2);
                        if (!exempt.contains(name) && !PropertyCheck.KNOWN.contains(name)) {
                            unknown.add(name + " (" + root.relativize(file) + ")");
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 20, "the scan found the literals it exists for: " + scanned);
        assertTrue(unknown.isEmpty(), "read by the plugin and missing from PropertyCheck.KNOWN: " + unknown);
    }

    /** The checkout's root, found above the module the test runs in. */
    private static java.nio.file.Path repoRoot() {
        java.nio.file.Path start = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (java.nio.file.Path d = start; d != null; d = d.getParent()) {
            if (java.nio.file.Files.isDirectory(d.resolve("jmeter-report-core/src/main/java"))
                    && java.nio.file.Files.isDirectory(d.resolve("jmeter-web-insight-report/src/main/java"))) {
                return d;
            }
        }
        throw new AssertionError("no checkout above " + start + ": this test reads both modules' main sources");
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
