package com.jmeterwebinsightreport.report.listener.rebuild;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which file the panel's Rebuild button reads, and every sentence it says instead of reading one: one
 * test per rule of the plan (L1, L3, R4, L6, R8) and per refusal.
 */
class RebuildRequestTest {

    @TempDir
    Path work;

    private final Map<String, String> props = new HashMap<>();
    private File jtl;
    private File csv;
    private File outputDir;

    private static final UnaryOperator<String> AS_GIVEN = name -> name;

    @BeforeAll
    static void initJMeterProperties() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File empty = File.createTempFile("wir-test-", ".properties");
            empty.deleteOnExit();
            JMeterUtils.loadJMeterProperties(empty.getAbsolutePath());
        }
    }

    @BeforeEach
    void files() throws IOException {
        jtl = write("run/results.jtl", "timeStamp,elapsed,label\n");
        csv = write("run/server.csv", "time,component,metric,instance,value\n");
        outputDir = Files.createDirectories(work.resolve("report")).toFile();
    }

    private File write(String path, String content) throws IOException {
        Path file = work.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file.toFile();
    }

    private static String slashes(File f) {
        return f.getPath().replace(File.separatorChar, '/');
    }

    private RebuildRequest.Panel panel(String results, String serverData) {
        return new RebuildRequest.Panel("Panel title", "panel.html", "", "", results, serverData, false);
    }

    private LastRun.Builder run() {
        return LastRun.builder().jtl(jtl).outputDirectory(outputDir).htmlFilename("checkout-20261023-140500.html")
                .title("Checkout").rampUpMillis(5000).junit(true).externalData(false, 10_000_000L)
                .apdexThreshold("750").runId("0123456789abcdef").endedAt(csv.lastModified() - 60_000L);
    }

    private RebuildRequest resolve(RebuildRequest.Panel panel, LastRun last, boolean running) {
        return RebuildRequest.resolve(panel, last, props::get, running, AS_GIVEN, FieldValue::evaluate, ZoneOffset.UTC);
    }

    // ---- L3: the run's own inputs ----

    @Test
    void theLastRunsJtlTitleFilenameAndOutputDirectoryAreTheOnesRebuilt() {
        // The panel has moved on since the run - another title, another JTL - and none of it is read.
        RebuildRequest r = resolve(panel("D:/elsewhere/results-${__time(HHmmss)}.jtl", slashes(csv)),
                run().build(), false);
        assertNull(r.refusal());
        assertEquals(jtl, r.jtl());
        assertEquals("Checkout", r.title(), "the title is hashed into runId: the run's, not the panel's");
        assertEquals("checkout-20261023-140500.html", r.reportFilename(), "already stamped: overwritten, not re-stamped");
        assertEquals(outputDir, r.outputDirectory());
        assertEquals(5000, r.rampUpMillis());
        assertTrue(r.junit());
        assertEquals("750", r.apdexThreshold());
        assertFalse(r.warnings().contains(RebuildRequest.NO_RUN));
        assertTrue(r.warnings().contains(RebuildRequest.NO_BODIES), "R5: said whenever the run's report is replaced");
    }

    @Test
    void withNoRunInThisSessionTheFieldsAreReadNowAndTheDialogSaysSo() {
        RebuildRequest r = resolve(new RebuildRequest.Panel("Panel title", "", "", "", slashes(jtl), slashes(csv), true),
                null, false);
        assertNull(r.refusal());
        assertTrue(r.warnings().contains(RebuildRequest.NO_RUN), r.warnings().toString());
        assertFalse(r.warnings().contains(RebuildRequest.NO_BODIES), "no run, no report of its being replaced");
        assertEquals(jtl.getAbsoluteFile(), r.jtl());
        assertEquals("Panel title", r.title());
        assertNull(r.reportFilename());
        assertTrue(r.junit(), "the checkbox, with no property to beat it");
        assertEquals(jtl.getAbsoluteFile().getParentFile(), r.outputDirectory(),
                "no directory configured: beside the results file, where the listener's default puts it first");
    }

    @Test
    void withNoRunTheGeneratorsOwnTimestampTokenIsLeftForTheGenerator() {
        // The README's documented per-run name: not a test-plan variable, and ${__P(timestamp)} is no fix.
        RebuildRequest r = resolve(new RebuildRequest.Panel("Panel title", "report_${timestamp}.html", "", "",
                slashes(jtl), slashes(csv), false), null, false);
        assertNull(r.refusal(), r.refusal());
        assertEquals("report_${timestamp}.html", r.reportFilename());

        // Beside a function: the function is evaluated now, the token still left alone.
        JMeterUtils.setProperty("wir.rebuild.prefix", "checkout");
        try {
            RebuildRequest both = resolve(new RebuildRequest.Panel("Panel title",
                    "${__P(wir.rebuild.prefix)}-${timestamp}.html", "", "", slashes(jtl), slashes(csv), false), null, false);
            assertNull(both.refusal(), both.refusal());
            assertEquals("checkout-${timestamp}.html", both.reportFilename());
        } finally {
            JMeterUtils.getJMeterProperties().remove("wir.rebuild.prefix");
        }

        // A real test-plan variable beside it is still refused - and the refusal names the token as typed.
        RebuildRequest variable = resolve(new RebuildRequest.Panel("Panel title", "${runName}-${timestamp}.html",
                "", "", slashes(jtl), slashes(csv), false), null, false);
        assertEquals("Report Filename: ${runName} is a test-plan variable, which exists only while a test runs - set it"
                + " as a property and write ${__P(runName)} instead", variable.refusal());
    }

    @Test
    void withNoRunAPropertyStillBeatsItsField() {
        props.put("webinsight.report.title", "From -J");
        props.put("webinsight.report.output", outputDir.getPath());
        RebuildRequest r = resolve(panel(slashes(jtl), slashes(csv)), null, false);
        assertEquals("From -J", r.title());
        assertEquals(outputDir.getAbsoluteFile(), r.outputDirectory());
    }

    @Test
    void withNoRunARelativeResultsFileResolvesAsResultCollectorResolvesIt() {
        UnaryOperator<String> besideThePlan = name -> new File(work.toFile(), name).getPath();
        RebuildRequest r = RebuildRequest.resolve(panel("run/results.jtl", slashes(csv)), null, props::get, false,
                besideThePlan, FieldValue::evaluate, ZoneOffset.UTC);
        assertNull(r.refusal());
        assertEquals(jtl.getAbsoluteFile(), r.jtl());
    }

    // ---- L1: the property beats the field ----

    @Test
    void thePropertyBeatsTheFieldAndTheStatusLineSaysSo() throws IOException {
        File other = write("ci/s.csv", "time,component,metric,instance,value\n");
        props.put(RebuildRequest.SERVER_DATA_PROPERTY, slashes(other));
        RebuildRequest r = resolve(panel("", slashes(csv)), run().build(), false);
        assertNull(r.refusal());
        assertTrue(r.serverDataOverridden());
        assertEquals(other.getAbsoluteFile(), r.serverData());
        assertTrue(r.statusLine().startsWith("Overridden by webinsight.report.servermetrics = " + slashes(other)
                + " - this is the file Rebuild reads \u00b7 found, "), r.statusLine());
        assertTrue(r.warnings().contains("webinsight.report.servermetrics = " + slashes(other)
                + " overrides the Server data (CSV) field."), r.warnings().toString());
    }

    @Test
    void theAnnotationsPropertyBeatsItsFieldToo() {
        props.put(RebuildRequest.ANNOTATIONS_PROPERTY, "D:/ci/asserts.json");
        RebuildRequest r = resolve(new RebuildRequest.Panel("", "", "", "C:/mine.json", "", slashes(csv), false),
                run().build(), false);
        assertEquals(new File("D:/ci/asserts.json"), r.annotations());
        assertTrue(r.warnings().contains("webinsight.report.annotations = D:/ci/asserts.json overrides the"
                + " Annotations File field."), r.warnings().toString());
    }

    // ---- R4: an unchanged field reads as the run read it ----

    @Test
    void aFieldUnchangedSinceTheRunReadsAsTheRunReadItAndAnEditedOneIsEvaluatedNow() {
        // ${runsDir} resolved on the run-clone (User Defined Variables) and cannot resolve now.
        LastRun last = run().serverData("${runsDir}/server.csv", slashes(csv))
                .annotations("${runsDir}/asserts.json", "D:/runs/asserts.json").build();
        RebuildRequest unchanged = resolve(new RebuildRequest.Panel("", "", "", "${runsDir}/asserts.json", "",
                "${runsDir}/server.csv", false), last, false);
        assertNull(unchanged.refusal(), unchanged.refusal());
        assertEquals(csv.getAbsoluteFile(), unchanged.serverData());
        assertEquals(new File("D:/runs/asserts.json"), unchanged.annotations());
        assertTrue(unchanged.statusLine().startsWith("Resolves to " + slashes(csv.getAbsoluteFile()) + " \u00b7 found, "),
                unchanged.statusLine());

        RebuildRequest edited = resolve(new RebuildRequest.Panel("", "", "", "", "", "${runsDir}/other.csv", false),
                last, false);
        assertEquals("Server data (CSV): ${runsDir} is a test-plan variable, which exists only while a test runs - set it"
                + " as a property and write ${__P(runsDir)} instead", edited.refusal());
        assertTrue(edited.refusedOnServerData());
        assertEquals(edited.refusal(), edited.statusLine(), "the status line says it before anyone clicks");
    }

    // ---- L6: a stale file ----

    @Test
    void serverDataSavedBeforeTheRunEndedIsWarnedAboutAndStillRebuilt() {
        LastRun last = run().endedAt(csv.lastModified() + 55 * 60_000L).build();
        RebuildRequest r = resolve(panel("", slashes(csv)), last, false);
        assertNull(r.refusal(), "warned about, not refused: the collector's window check is the real one");
        assertTrue(r.statusLine().contains(", before this run ended ("), r.statusLine());
        assertTrue(r.statusLine().endsWith(") - the previous run's export?"), r.statusLine());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("Server data saved ")
                && w.endsWith("- the previous run's export?")), r.warnings().toString());

        RebuildRequest fresh = resolve(panel("", slashes(csv)), run().build(), false);
        assertFalse(fresh.statusLine().contains("before this run ended"), fresh.statusLine());
    }

    @Test
    void aRelativeServerDataPathIsLookedUpBesideTheJtlAsJmeterGLooksItUp() {
        RebuildRequest r = resolve(panel("", "server.csv"), run().build(), false);
        assertNull(r.refusal());
        assertEquals(csv.getAbsoluteFile(), r.serverData());
    }

    @Test
    void aFunctionEvaluatedAtTheClickIsSaidToBeTheClicks() {
        JMeterUtils.setProperty("wir.request.dir", slashes(csv.getParentFile()));
        try {
            RebuildRequest r = resolve(panel("", "${__P(wir.request.dir)}/server.csv"), run().build(), false);
            assertNull(r.refusal());
            assertEquals(csv.getAbsoluteFile(), r.serverData());
            assertTrue(r.statusLine().endsWith(" \u00b7 evaluated now, not when the run started"), r.statusLine());
        } finally {
            JMeterUtils.getJMeterProperties().remove("wir.request.dir");
        }
    }

    // ---- refusals ----

    @Test
    void refusedWhileATestIsRunning() {
        RebuildRequest r = resolve(panel("", slashes(csv)), run().build(), true);
        assertEquals("A test is running - its results file is still being written. Rebuild once the test has ended.",
                r.refusal());
        assertFalse(r.refusedOnServerData(), "the panel shows it under the status line");
    }

    @Test
    void refusedWithNoResultsFile() {
        RebuildRequest none = resolve(panel("", slashes(csv)), null, false);
        assertEquals("No results file: set Results file (JTL) and run the test - Rebuild reads the JTL that run writes."
                + " For a JTL written by -l or another listener, use Tools > Generate HTML report.", none.refusal());

        RebuildRequest runWroteNone = resolve(panel("", slashes(csv)), run().jtl(null).build(), false);
        assertEquals("The last run wrote no results file: the Results file (JTL) field was empty. Set it and run the"
                + " test again, or, for a JTL written by -l or another listener, use Tools > Generate HTML report.",
                runWroteNone.refusal());
    }

    @Test
    void refusedWhenTheResultsFileIsMissing() {
        File gone = new File(work.toFile(), "run/gone.jtl");
        RebuildRequest notWritten = resolve(panel(slashes(gone), slashes(csv)), null, false);
        assertEquals("The results file " + slashes(gone.getAbsoluteFile()) + " does not exist - it is written while the"
                + " test runs, so run the test first.", notWritten.refusal());

        RebuildRequest deleted = resolve(panel("", slashes(csv)), run().jtl(gone).build(), false);
        assertEquals("The results file the last run wrote, " + slashes(gone) + ", is not there any more.",
                deleted.refusal());
    }

    @Test
    void refusedWithNoServerData() {
        RebuildRequest r = resolve(panel("", ""), run().build(), false);
        assertEquals("No server data: name the CSV (or a server-metrics.json) in Server data (CSV).", r.refusal());
        assertEquals("Name the CSV your export tool wrote for this run, or a server-metrics.json.", r.statusLine());
    }

    @Test
    void refusedWhenTheServerDataIsMissing() {
        File missing = new File(work.toFile(), "run/not-yet.csv");
        RebuildRequest r = resolve(panel("", slashes(missing)), run().build(), false);
        assertEquals("The server data " + slashes(missing.getAbsoluteFile()) + " does not exist - export it first: the"
                + " run's NEXT line and the report's Server tab say what to export.", r.refusal());
        assertEquals("Resolves to " + slashes(missing.getAbsoluteFile()) + " \u00b7 not found", r.statusLine());
        assertTrue(r.refusedOnServerData());
    }

    @Test
    void refusedWhenTheServerDataIsAFolder() {
        RebuildRequest r = resolve(panel("", slashes(csv.getParentFile())), run().build(), false);
        assertTrue(r.refusal().endsWith(" is a folder - name the CSV file in it."), r.refusal());
    }

    @Test
    void refusedOnAnUnresolvedVariableInAFieldReadNow() {
        RebuildRequest r = resolve(panel("${runsDir}/results.jtl", slashes(csv)), null, false);
        assertEquals("Results file (JTL): ${runsDir} is a test-plan variable, which exists only while a test runs -"
                + " set it as a property and write ${__P(runsDir)} instead", r.refusal());
        assertFalse(r.refusedOnServerData());
    }

    @Test
    void sizesAreWrittenAsAPersonReadsThem() {
        assertEquals("12 bytes", RebuildRequest.size(12));
        assertEquals("1 byte", RebuildRequest.size(1));
        assertEquals("340 KB", RebuildRequest.size(340 * 1024));
        assertEquals("2.1 MB", RebuildRequest.size((long) (2.1 * 1024 * 1024)));
    }
}
