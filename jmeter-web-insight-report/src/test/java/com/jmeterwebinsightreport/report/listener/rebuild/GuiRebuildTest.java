package com.jmeterwebinsightreport.report.listener.rebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.generator.NonZeroExitProbe;
import com.jmeterwebinsightreport.report.generator.ReportArtifacts;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The panel's rebuild over a listener-shaped interim report, from the goldens' committed inputs: the
 * report the run wrote is overwritten in place, under its own name and {@code runId}, with the Server
 * tab judged - and nothing it meets, a refused CSV included, becomes an exception or an exit code.
 */
class GuiRebuildTest {

    private static final File JTL = new File("../test-plans/goldens/plain.jtl").getAbsoluteFile();
    private static final File CSV = new File("../test-plans/goldens/server-metrics.csv").getAbsoluteFile();
    private static final File ANNOTATIONS = new File("../test-plans/goldens/gate-server.json").getAbsoluteFile();
    private static final File CLIENT_ONLY = new File("../test-plans/goldens/gate-mixed.json").getAbsoluteFile();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path work;

    private final Map<String, String> props = new HashMap<>();
    private File outputDir;
    private ReportArtifacts interim;
    private String interimRunId;

    @BeforeAll
    static void initJMeterProperties() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File empty = File.createTempFile("wir-test-", ".properties");
            empty.deleteOnExit();
            JMeterUtils.loadJMeterProperties(empty.getAbsolutePath());
        }
    }

    /** What the listener leaves behind: a report with the server asserts declared and nothing collected. */
    @BeforeEach
    void interimReport() throws IOException {
        outputDir = Files.createDirectories(work.resolve("report")).toFile();
        ReportConfiguration config = new ReportConfiguration();
        config.setReportTitle("Checkout");
        config.setOutputDirectory(outputDir);
        config.setReportFilename("checkout-${timestamp}.html");
        config.setAnnotationsFile(ANNOTATIONS);
        ReportData data = new JtlParser().parse(JTL, "Checkout");
        WebInsightReportGenerator.beginTestRun();
        interim = new WebInsightReportGenerator(config).generateReport(data);
        interimRunId = RunIdentity.of(data);
        assertFalse(JSON.readTree(interim.file(ReportArtifacts.Kind.JSON)).has("serverStatus"),
                "the interim report judged nothing on the server side");
    }

    private LastRun.Builder lastRun() {
        return LastRun.builder().jtl(JTL).outputDirectory(outputDir)
                .htmlFilename(interim.filename(ReportArtifacts.Kind.HTML)).title("Checkout")
                .externalData(false, 10_000_000L).runId(interimRunId).endedAt(0L)
                .annotations(ANNOTATIONS.getPath(), ANNOTATIONS.getPath());
    }

    private RebuildRequest request(LastRun last, File serverData) {
        RebuildRequest.Panel panel = new RebuildRequest.Panel("A title edited since the run", "", "",
                last.annotationsRaw(), "", serverData.getPath(), false);
        RebuildRequest request = RebuildRequest.resolve(panel, last, props::get, false, name -> name,
                FieldValue::evaluate, ZoneOffset.UTC);
        assertNull(request.refusal(), request.refusal());
        return request;
    }

    @Test
    void theRunsReportIsRebuiltInPlaceWithTheServerTabJudged() throws Exception {
        int arranged = NonZeroExitProbe.calls();
        // Even with the server side made fatal: an exit code means nothing in a GUI session.
        props.put("webinsight.report.server.failonerror", "true");
        props.put("webinsight.report.failonassert", "true");

        RebuildResult result = GuiRebuild.run(request(lastRun().build(), CSV), props::get,
                Collections.emptyMap(), Long.MAX_VALUE);

        assertNull(result.failure(), result.text());
        assertEquals(interim.html(), result.html(), "the same file, a ${timestamp} name not stamped again");
        String[] htmls = outputDir.list((dir, name) -> name.endsWith(".html"));
        assertEquals(1, htmls.length, "overwritten, not added beside");

        JsonNode json = JSON.readTree(result.artifacts().file(ReportArtifacts.Kind.JSON));
        assertEquals(interimRunId, json.get("runId").asText(), "the run's title, so the run's runId and drafts");
        assertEquals("FAIL", json.get("serverStatus").asText(), "the Server tab was judged");
        assertTrue(json.get("serverComponents").size() > 0);

        assertTrue(result.collectorLines().stream().anyMatch(l -> l.startsWith("wir-collect: wrote ")),
                result.collectorLines().toString());
        assertTrue(result.warnings().contains(RebuildRequest.NO_BODIES), result.warnings().toString());
        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("does not hold exactly the run")),
                result.warnings().toString());

        String text = result.text();
        assertTrue(text.startsWith("Report rebuilt: " + RebuildRequest.slashes(interim.html()) + "\n"), text);
        assertTrue(text.contains("\nVerdict\n" + result.artifacts().verdictLine() + "\n"), text);
        assertTrue(text.contains("\nServer data\nRead " + RebuildRequest.slashes(CSV) + " (saved "), text);
        assertTrue(text.contains("\nWhat the collector said\nwir-collect: "), text);
        assertFalse(text.contains("CONFIG ERROR"), "a console marker, about a process exit");
        assertFalse(text.contains("exit code"), text);

        assertEquals(arranged, NonZeroExitProbe.calls(), "the rebuild arranged a non-zero exit of the GUI's JMeter");
    }

    @Test
    void aJtlHoldingAnotherRunIsSaidAndStillRebuilt() {
        RebuildResult result = GuiRebuild.run(request(lastRun().runId("ffffffffffffffff").build(), CSV), props::get,
                Collections.emptyMap(), Long.MAX_VALUE);
        assertNotNull(result.html(), result.text());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("The results file does not hold exactly the"
                + " run this listener last reported (run id ffffffffffffffff then, " + interimRunId + " now)")),
                result.warnings().toString());
    }

    @Test
    void aRefusedCsvComesBackAsTheCollectorsSentenceNotAnException() throws Exception {
        int arranged = NonZeroExitProbe.calls();
        // Client rules only: the collector has nothing to collect, and says so in its own words.
        LastRun last = lastRun().annotations(CLIENT_ONLY.getPath(), CLIENT_ONLY.getPath()).build();
        RebuildResult result = GuiRebuild.run(request(last, CSV), props::get, Collections.emptyMap(), Long.MAX_VALUE);

        assertNull(result.failure(), "the report is still written: server data is a bonus on it");
        assertNotNull(result.html());
        assertFalse(result.collectorLines().isEmpty(), result.text());
        String said = result.collectorLines().get(0);
        assertTrue(said.startsWith("wir-collect: ") && said.endsWith(" - nothing to collect"), said);
        assertTrue(result.text().contains("\nWhat the collector said\n" + said + "\n"), result.text());
        // With no server assert declared a refused collection is a warning on the page, not a server
        // problem: the collector's sentence is the whole of what there is to say, and it is said.
        assertFalse(result.text().contains("Server side not judged"), result.text());
        assertEquals(arranged, NonZeroExitProbe.calls());
    }

    @Test
    void aRefusedCsvWithServerAssertsDeclaredPointsAtTheDialogNotAConsole() {
        // A reader option the collector refuses: nothing is collected for asserts that were declared,
        // so the server side is not judged - and the sentence saying so is read in the dialog and on the
        // rebuilt page, by a user whose JMeter may have no console at all.
        props.put("webinsight.report.servermetrics.step", "fortnight");
        RebuildResult result = GuiRebuild.run(request(lastRun().build(), CSV), props::get,
                Collections.emptyMap(), Long.MAX_VALUE);

        assertNotNull(result.html(), result.text());
        String text = result.text();
        assertTrue(text.contains("\nServer side not judged\n"), text);
        assertTrue(text.contains("could not be collected - the wir-collect lines in the Rebuild dialog say why"), text);
        assertFalse(text.contains("on the console"), text);
        assertFalse(result.collectorLines().isEmpty(), "the lines the sentence points at are in the dialog: " + text);
    }

    @Test
    void aMissingResultsFileComesBackAsASentence() {
        File gone = work.resolve("gone.jtl").toFile();
        RebuildRequest refused = RebuildRequest.resolve(new RebuildRequest.Panel("", "", "", "", "", CSV.getPath(), false),
                lastRun().jtl(gone).build(), props::get, false, name -> name, FieldValue::evaluate, ZoneOffset.UTC);
        RebuildResult result = GuiRebuild.run(refused, props::get, Collections.emptyMap(), Long.MAX_VALUE);
        assertNull(result.html());
        assertEquals("Rebuild failed", result.headline());
        assertTrue(result.text().startsWith("Rebuild failed: The results file the last run wrote, "), result.text());
    }

    @Test
    void aCsvTooBigForTheHeapIsWarnedAboutFirst() {
        List<String> warnings = new ArrayList<>();
        GuiRebuild.heapPreCheck(CSV, CSV.length() * 2, warnings);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("The server data is "), warnings.get(0));
        assertTrue(warnings.get(0).endsWith("give JMeter more heap (JVM_ARGS=-Xmx2g) or export a narrower window."),
                warnings.get(0));

        List<String> none = new ArrayList<>();
        GuiRebuild.heapPreCheck(CSV, Long.MAX_VALUE, none);
        assertTrue(none.isEmpty());
    }
}
