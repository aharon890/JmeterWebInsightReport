package com.jmeterwebinsightreport.report.exporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.generator.ReportArtifacts;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReportRebuild} is the one rebuild {@code jmeter -g} and the listener panel's Rebuild button
 * both run, so what it writes from the goldens' committed inputs must be what the {@code -g} route
 * writes from them, whole: the collected payload and the CI/CD JSON. The route it is held to is
 * assembled here from the pieces the exporter always had - {@code JtlParser}, the exporter's own
 * {@code collectServerCsvIfGiven}, {@code WebInsightReportGenerator} - rather than through
 * {@code ReportRebuild} again, so a step the service drops or reorders shows up as a difference.
 *
 * <p>It also owns the cleanup of its scratch: the payload a CSV is collected into is deleted once
 * the report has read it, and a payload somebody gave as server data is theirs and never is.
 */
class ReportRebuildTest {

    private static final File JTL = new File("../test-plans/goldens/plain.jtl");
    private static final File CSV = new File("../test-plans/goldens/server-metrics.csv");
    private static final File ANNOTATIONS = new File("../test-plans/goldens/gate-server.json");
    private static final File PAYLOAD = new File("../test-plans/goldens/server-metrics.json");

    private static final Function<String, String> PROPS = JMeterUtils::getProperty;
    private static final Map<String, String> NO_ENV = Collections.emptyMap();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path work;

    /** As in {@code WebInsightDataExporterTest}: JMeterUtils' properties are null until loaded. */
    @BeforeAll
    static void initJMeterProperties() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File empty = File.createTempFile("wir-test-", ".properties");
            empty.deleteOnExit();
            JMeterUtils.loadJMeterProperties(empty.getAbsolutePath());
        }
    }

    /** Console lines a caller hands in, kept to be read back. */
    private static final class Captured {
        final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(outBytes, true);
        final PrintStream err = new PrintStream(errBytes, true);
        final List<String> logged = new ArrayList<>();

        String out() {
            return new String(outBytes.toByteArray(), StandardCharsets.UTF_8);
        }

        String err() {
            return new String(errBytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private ReportConfiguration config(String dir, File serverData, File annotations) throws IOException {
        File outputDir = Files.createDirectories(work.resolve(dir)).toFile();
        ReportConfiguration config = new WebInsightDataExporter().buildConfiguration(outputDir);
        config.setAnnotationsFile(annotations);
        config.setServerMetricsFile(serverData);
        return config;
    }

    @Test
    void anAssertNamingNoComponentIsRefusedAndTheReportIsStillWritten() throws Exception {
        // It reached CsvSource.nearMiss with a null component, whose NullPointerException left
        // jmeter -g with no report at all - a server-file fault costing the client report.
        File annotations = work.resolve("orphan.json").toFile();
        Files.write(annotations.toPath(), ("{\"serverMetrics\":{\"components\":{\"db\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"orphan\",\"query\":\"cpu.pct\",\"reduce\":{\"kind\":\"max\"},"
                + "\"fail\":{\"gt\":90}}]}}").getBytes(StandardCharsets.UTF_8));
        ReportConfiguration config = config("orphan", CSV, annotations);
        Captured captured = new Captured();
        ReportData parsed = ReportRebuild.parse(JTL, config, PROPS);
        ReportRebuild.readCompanionFiles(config, config.getOutputDirectory(), JTL, parsed,
                PROPS, captured.out, captured.err, NO_ENV, ReportRebuild.Route.LISTENER_PANEL);
        assertNull(config.getServerDataProblem(), captured.err());
        ReportRebuild.generateAndAnnounce(config, parsed, JTL.getPath().replace(File.separatorChar, '/'),
                captured.out, captured.logged::add);
        JsonNode json = JSON.readTree(new File(config.getOutputDirectory(), "web-insight-report.json"));
        JsonNode orphan = json.get("serverAsserts").get(0);
        assertEquals("orphan", orphan.get("id").asText(), json.toString());
        assertEquals("INVALID_RULE", orphan.get("notJudged").asText(), orphan.toString());
    }

    @Test
    void theServiceWritesWhatTheGRouteWritesFromTheGoldensInputs() throws Exception {
        // The service, as the button runs it: its streams, its log, no JMeter console.
        ReportConfiguration service = config("service", CSV, ANNOTATIONS);
        Captured captured = new Captured();
        ReportData parsed = ReportRebuild.parse(JTL, service, PROPS);
        ReportRebuild.readCompanionFiles(service, service.getOutputDirectory(), JTL, parsed,
                PROPS, captured.out, captured.err, NO_ENV, ReportRebuild.Route.LISTENER_PANEL);
        assertNull(service.getServerDataProblem(), captured.err());
        File collected = service.getCollectedServerPayload();
        assertNotNull(collected, "a CSV was collected into a payload of the rebuild's own");
        assertEquals(collected, service.getServerMetricsFile());
        JsonNode servicePayload = JSON.readTree(collected);
        ReportArtifacts serviceArtifacts = ReportRebuild.generateAndAnnounce(service, parsed,
                JTL.getPath().replace(File.separatorChar, '/'), captured.out, captured.logged::add);

        // The -g route, from the exporter's own pieces.
        ReportConfiguration g = config("g", CSV, ANNOTATIONS);
        ReportData gParsed = new JtlParser().parse(JTL, g.getReportTitle());
        new WebInsightDataExporter().collectServerCsvIfGiven(g, JTL, gParsed);
        assertNull(g.getServerDataProblem());
        File gCollected = g.getServerMetricsFile();
        JsonNode gPayload = JSON.readTree(gCollected);
        ReportArtifacts gArtifacts;
        try {
            gArtifacts = new WebInsightReportGenerator(g).generateReport(gParsed);
        } finally {
            Files.deleteIfExists(gCollected.toPath());
        }

        // The same run, the same collection, the same report. The payload is compared whole: both
        // are stamped with the CSV's own time, so even the collectionId agrees.
        assertEquals(RunIdentity.of(gParsed), RunIdentity.of(parsed));
        assertEquals(RunIdentity.of(parsed), servicePayload.get("runId").asText());
        assertEquals(gPayload, servicePayload, "the collected payload, whole");

        JsonNode serviceJson = JSON.readTree(serviceArtifacts.file(ReportArtifacts.Kind.JSON));
        JsonNode gJson = JSON.readTree(gArtifacts.file(ReportArtifacts.Kind.JSON));
        assertEquals(gJson.get("runId"), serviceJson.get("runId"));
        assertEquals("FAIL", serviceJson.get("serverStatus").asText(), "the server side was judged");
        assertEquals(canonical(gJson), canonical(serviceJson), "the CI/CD JSON, whole");

        // The collector spoke through the streams it was handed, in its own words, and the report's
        // block followed on the same stream; the marker went to the log as well.
        assertTrue(captured.out().contains("wir-collect: run window "), captured.out());
        assertTrue(captured.out().contains("wir-collect: wrote "), captured.out());
        assertTrue(captured.out().contains(serviceArtifacts.successMarker()), captured.out());
        assertTrue(captured.out().indexOf("wir-collect: wrote ")
                < captured.out().indexOf(serviceArtifacts.successMarker()), captured.out());
        assertEquals(serviceArtifacts.successMarker(), captured.logged.get(0));

        // The scratch payload is gone once the report has read it, and forgotten.
        assertFalse(collected.exists(), "the temporary payload outlived the report: " + collected);
        assertNull(service.getCollectedServerPayload());
    }

    @Test
    void aPayloadGivenAsServerDataIsReadAndKept() throws Exception {
        // A copy, so a regression deletes a scratch file rather than a committed golden.
        File given = work.resolve("server-metrics.json").toFile();
        Files.copy(PAYLOAD.toPath(), given.toPath());
        ReportConfiguration config = config("payload", given, ANNOTATIONS);
        Captured captured = new Captured();

        ReportData parsed = ReportRebuild.parse(JTL, config, PROPS);
        ReportRebuild.readCompanionFiles(config, config.getOutputDirectory(), JTL, parsed,
                PROPS, captured.out, captured.err, NO_ENV, ReportRebuild.Route.LISTENER_PANEL);
        assertNull(config.getCollectedServerPayload(), "nothing was collected: the file is a payload");
        assertEquals(given, config.getServerMetricsFile());
        ReportArtifacts artifacts = ReportRebuild.generateAndAnnounce(config, parsed, null,
                captured.out, null);

        assertTrue(given.isFile(), "the user's payload was deleted");
        assertFalse(captured.out().contains("wir-collect:"), captured.out());
        assertEquals("FAIL", JSON.readTree(artifacts.file(ReportArtifacts.Kind.JSON)).get("serverStatus").asText(),
                "the given payload was read and judged");
    }

    @Test
    void aRefusedCollectionLeavesNoScratchFileAndSaysWhyOnTheStreamsItWasGiven() throws Exception {
        // An annotations file with client rules only: the collector refuses, in its words, on err.
        File clientOnly = new File("../test-plans/goldens/gate-mixed.json");
        ReportConfiguration config = config("refused", CSV, clientOnly);
        Captured captured = new Captured();
        Set<String> before = scratchPayloads();

        ReportData parsed = ReportRebuild.parse(JTL, config, PROPS);
        ReportRebuild.readCompanionFiles(config, config.getOutputDirectory(), JTL, parsed,
                PROPS, captured.out, captured.err, NO_ENV, ReportRebuild.Route.COMMAND_LINE);

        assertTrue(captured.err().contains("wir-collect: "), captured.err());
        // D5. The collector's reason, carried into the problem the page, the JSON and JUnit print - it was
        // "the wir-collect lines on the console say why", and a CI artifact may keep no console. The
        // line names the annotations file by its absolute path; the problem names it by its file name,
        // as the published page names no local path.
        String problem = config.getServerDataProblem();
        assertTrue(problem.startsWith("the server CSV server-metrics.csv could not be collected: gate-mixed.json"
                + " declares no serverMetrics.asserts and no component of a catalogue type ("), problem);
        assertTrue(problem.endsWith(") - nothing to collect"), problem);
        assertTrue(captured.err().contains(clientOnly.getAbsolutePath()), "the console keeps the path: " + captured.err());
        assertFalse(problem.contains(clientOnly.getAbsoluteFile().getParent()), problem);
        assertNull(config.getServerMetricsFile());
        assertNull(config.getCollectedServerPayload());
        Set<String> after = scratchPayloads();
        after.removeAll(before);
        assertEquals(Collections.emptySet(), after, "the refused collection's temporary file was left behind");
    }

    /**
     * A reader property past step reaches the collection as its flag does on the command line: only
     * step and columns were ever passed through -g or Rebuild by a test, and C39-C41 run the other
     * flags through wir-collect.sh alone (Stage 6, the audit). E27's Datadog export, as C41 reads it:
     * {@code from=datadog} through the properties must write the payload {@code --from datadog} writes.
     */
    @Test
    void aFromPropertyCollectsWhatWirCollectsFromFlagCollects() throws Exception {
        File csv = new File("../test-plans/server-catalogue/e27-datadog.csv");
        File annotations = new File("../test-plans/server-catalogue/e27-server.json");
        Function<String, String> props = name -> "webinsight.report.servermetrics.from".equals(name) ? "datadog"
                : "webinsight.report.servermetrics.step".equals(name) ? "1s" : PROPS.apply(name);
        ReportConfiguration config = config("from-datadog", csv, annotations);
        Captured captured = new Captured();
        ReportRebuild.collectServerCsvIfGiven(config, JTL, null, props, captured.out, captured.err, NO_ENV,
                ReportRebuild.Route.COMMAND_LINE);
        assertNull(config.getServerDataProblem(), captured.err());

        File standalone = work.resolve("from-datadog-standalone.json").toFile();
        Captured quiet = new Captured();
        assertEquals(0, com.jmeterwebinsightreport.WirCollect.run(new String[] {"--jtl", JTL.getPath(),
                "--annotations", annotations.getPath(), "--out", standalone.getPath(), "--csv", csv.getPath(),
                "--from", "datadog", "--step", "1s", "--no-wait", "--test-name", config.getReportTitle(),
                "--collected-at", java.time.Instant.ofEpochMilli(csv.lastModified()).toString()},
                quiet.out, quiet.err, NO_ENV), quiet.err());

        JsonNode inProcess = JSON.readTree(config.getServerMetricsFile());
        JsonNode fromFlag = JSON.readTree(standalone);
        assertEquals("datadog", inProcess.get("sources").get(0).get("from").asText(), inProcess.get("sources").toString());
        boolean converted = false;
        for (JsonNode series : inProcess.get("series")) {
            converted |= series.hasNonNull("readAs");
        }
        assertTrue(converted, "a series read under Datadog's name: " + inProcess.get("series"));
        assertEquals(fromFlag.get("series"), inProcess.get("series"));
        assertEquals(fromFlag, inProcess, "the whole payload");
    }

    @Test
    void aCollectorRefusalNamesItsReasonInTheProblemText() throws Exception {
        // D5 (S4 W3): a long CSV given webinsight.report.servermetrics.columns without .layout. The
        // collector refuses on the console; the page, the JSON finding and JUnit said only that its
        // lines say why.
        File columns = work.resolve("columns.csv").toFile();
        Files.write(columns.toPath(), "header,series\n".getBytes(StandardCharsets.UTF_8));
        Function<String, String> props = name -> "webinsight.report.servermetrics.columns".equals(name)
                ? columns.getAbsolutePath() : PROPS.apply(name);
        ReportConfiguration config = config("columns-no-layout", CSV, ANNOTATIONS);
        Captured captured = new Captured();
        ReportRebuild.collectServerCsvIfGiven(config, JTL, null, props, captured.out, captured.err, NO_ENV,
                ReportRebuild.Route.COMMAND_LINE);
        assertTrue(captured.err().contains("wir-collect: --columns goes with --layout wide"), captured.err());
        assertEquals("the server CSV server-metrics.csv could not be collected: --columns goes with --layout wide"
                + " - a long file's column names are --csv-columns", config.getServerDataProblem());
        assertNull(config.getServerMetricsFile());
    }

    @Test
    void theRefusalIsTheCollectorsLastLineWithEveryPathItWasGivenReducedToItsFileName() {
        List<String> paths = Arrays.asList("D:\\runs\\r1\\annotations.json", "/home/ci/run/results.jtl");
        // Findings come first, the refusal last; the usage lines that follow a flag refusal are not ours.
        assertEquals("annotations.json declares nothing, results.jtl has samples",
                ReportRebuild.refusalOf("wir-collect: WARNING a finding\n"
                        + "wir-collect: D:\\runs\\r1\\annotations.json declares nothing, /home/ci/run/results.jtl has samples\r\n"
                        + "usage: wir-collect --jtl <results.jtl>\n", paths));
        assertNull(ReportRebuild.refusalOf("usage: wir-collect\n", paths), "no line of the collector's: none");
    }

    @Test
    void eachRouteIsSentWhereItsExplanationIs() throws Exception {
        File clientOnly = new File("../test-plans/goldens/gate-mixed.json");
        ReportConfiguration refused = config("panel-refused", CSV, clientOnly);
        Captured captured = new Captured();
        ReportRebuild.readCompanionFiles(refused, refused.getOutputDirectory(), JTL,
                ReportRebuild.parse(JTL, refused, PROPS), PROPS, captured.out, captured.err, NO_ENV,
                ReportRebuild.Route.LISTENER_PANEL);
        // The button captured the collector's lines for its dialog, and the problem carries the reason
        // itself, as -g's does.
        assertTrue(refused.getServerDataProblem().startsWith("the server CSV server-metrics.csv could not be"
                + " collected: gate-mixed.json declares no serverMetrics.asserts"), refused.getServerDataProblem());

        // A CSV with no annotations file anywhere: -g names the property, the button its own field.
        ReportConfiguration g = config("g-no-annotations", CSV, null);
        ReportRebuild.collectServerCsvIfGiven(g, JTL, null, PROPS, captured.out, captured.err, NO_ENV,
                ReportRebuild.Route.COMMAND_LINE);
        assertTrue(g.getServerDataProblem().endsWith(" - give webinsight.report.annotations too"),
                g.getServerDataProblem());
        ReportConfiguration panel = config("panel-no-annotations", CSV, null);
        ReportRebuild.collectServerCsvIfGiven(panel, JTL, null, PROPS, captured.out, captured.err, NO_ENV,
                ReportRebuild.Route.LISTENER_PANEL);
        assertTrue(panel.getServerDataProblem().endsWith(" - name one in the listener's Annotations File field"
                + " (or webinsight.report.annotations)"), panel.getServerDataProblem());
    }

    private static Set<String> scratchPayloads() {
        String[] names = new File(System.getProperty("java.io.tmpdir"))
                .list((dir, name) -> name.startsWith("wir-server-metrics-"));
        return new HashSet<>(names == null ? Collections.<String>emptyList() : Arrays.asList(names));
    }

    /**
     * The JSON with what two reports of one run written into two directories legitimately differ
     * in taken out, as {@code canonicalise-artifact.py} takes it out for the goldens: when it was
     * generated, where, and each artifact's path reduced to its file name.
     */
    private static JsonNode canonical(JsonNode json) {
        ObjectNode copy = json.deepCopy();
        copy.remove("generatedAt");
        JsonNode artifacts = copy.get("artifacts");
        if (artifacts instanceof ObjectNode) {
            ((ObjectNode) artifacts).remove("outputDirectory");
            for (Iterator<JsonNode> it = artifacts.elements(); it.hasNext(); ) {
                JsonNode entry = it.next();
                if (entry instanceof ArrayNode) {
                    for (JsonNode item : entry) {
                        stripPath(item);
                    }
                } else {
                    stripPath(entry);
                }
            }
        }
        return copy;
    }

    private static void stripPath(JsonNode node) {
        if (node instanceof ObjectNode && node.has("path")) {
            String path = node.get("path").asText();
            ((ObjectNode) node).put("path", path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1));
        }
    }
}
