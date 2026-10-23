package com.jmeterwebinsightreport.report.exporter;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The -g -o exporter must decide "is the report already sitting here <em>this run's</em>?" — and
 * only a yes justifies writing nothing.
 *
 * <p>Two bugs live in this one decision. Deriving the expected name from the raw {@code -J}
 * property missed every configuration the generator rewrites, so both generators ran and the
 * second overwrote the first. And answering the question by <b>name</b> conflated "the listener
 * in this very execution already wrote it" with "an unrelated report is in the way" — opposite
 * answers, one of which is a run that silently produces nothing at all. The name finds the
 * candidates now; the run id decides.
 */
class WebInsightDataExporterTest {

    @TempDir
    Path outputDir;

    /**
     * JMeterUtils holds its properties in a static that only a real JMeter start-up fills, and
     * reading one before then is an NPE rather than a null. An empty file is enough: these tests
     * are about which properties the exporter reads, not about JMeter's defaults.
     */
    @BeforeAll
    static void initJMeterProperties() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File empty = File.createTempFile("wir-test-", ".properties");
            empty.deleteOnExit();
            JMeterUtils.loadJMeterProperties(empty.getAbsolutePath());
        }
    }

    private void touch(String name) throws IOException {
        Files.write(outputDir.resolve(name), "{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void filenameResolution_isSharedWithTheGenerator() {
        assertEquals("myreport.html", WebInsightReportGenerator.resolveReportFilename("myreport"));
        assertEquals("myreport.html", WebInsightReportGenerator.resolveReportFilename("myreport.html"));
        assertEquals("web-insight-report.html", WebInsightReportGenerator.resolveReportFilename(null));
        // A path is rejected and falls back, so the exporter must expect the default too.
        assertEquals("web-insight-report.html",
                WebInsightReportGenerator.resolveReportFilename("../../evil.html"));
    }

    @Test
    void timestampPatternBecomesAGlob() {
        assertEquals("ts_*.html", WebInsightReportGenerator.reportFilenameGlob("ts_${timestamp}.html"));
        assertEquals("run_*.html", WebInsightReportGenerator.reportFilenameGlob("run_${timestamp}"));
        assertEquals("plain.html", WebInsightReportGenerator.reportFilenameGlob("plain.html"));
    }

    // --- the exists policy, decided on identity -----------------------------------

    @Test
    void skipsWhenTheExistingReportIsThisVeryRun() throws IOException {
        // The one case that justifies writing nothing: a combined `-n -t … -e -o` run, where the
        // listener already produced this exact report during this execution.
        ReportData run = run("SHARED-RUN", 1_700_000_000_000L);
        writeJsonFor("myreport.json", run);

        assertTrue(WebInsightDataExporter.thisRunAlreadyReported(
                outputDir.toFile(), "myreport.html", run));
        // ...and the suffix-less form the generator would have completed.
        assertTrue(WebInsightDataExporter.thisRunAlreadyReported(
                outputDir.toFile(), "myreport", run));
    }

    @Test
    void generatesWhenTheExistingReportBelongsToAnotherRun() throws IOException {
        // The failure this replaces: a stale report from an unrelated run suppressed a fresh
        // one, and the only trace was a WARN in a log with no console appender. Overwriting is
        // the documented default on the listener path; the exporter now agrees with it instead
        // of contradicting it.
        writeJsonFor("myreport.json", run("EARLIER-RUN", 1_600_000_000_000L));

        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(
                outputDir.toFile(), "myreport.html", run("THIS-RUN", 1_700_000_000_000L)));
    }

    @Test
    void generatesWhenTheExistingReportCannotSayWhoseItIs() throws IOException {
        // No runId at all: a report from a version that predates run identity, or a .json
        // truncated by a crash. "Cannot prove it is mine" resolves to generate — a visible
        // overwrite is a far better failure than a run that produces nothing and says nothing.
        touch("myreport.json");

        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(
                outputDir.toFile(), "myreport.html", run("ANY", 1_700_000_000_000L)));
    }

    @Test
    void anHtmlWithNoJsonAtAllDoesNotSuppressTheReport() throws IOException {
        // Keying on the .html was how the old check covered a lost .json. Under identity that
        // case is unidentified and generates: an HTML that cannot say which run produced it is
        // not evidence that this run is already done.
        Files.write(outputDir.resolve("myreport.html"), "<html></html>".getBytes(StandardCharsets.UTF_8));

        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(
                outputDir.toFile(), "myreport.html", run("ANY", 1_700_000_000_000L)));
    }

    @Test
    void findsATimestampedReportStampedInAnotherSecond() throws IOException {
        // The listener stamped its name when IT ran; the exporter runs later, possibly in the
        // next second, so an exact-name comparison would miss it entirely and the run would end
        // with two differently-named reports of the same test.
        ReportData run = run("SHARED-RUN", 1_700_000_000_000L);
        writeJsonFor("ts_20260101_010101.json", run);

        assertTrue(WebInsightDataExporter.thisRunAlreadyReported(
                outputDir.toFile(), "ts_${timestamp}.html", run));
    }

    @Test
    void doesNotFalsePositiveOnAnUnrelatedFile() throws IOException {
        touch("statistics.json");
        ReportData run = run("ANY", 1_700_000_000_000L);

        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(
                outputDir.toFile(), "ts_${timestamp}.html", run));
        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(outputDir.toFile(), null, run));

        // '${timestamp}' as the WHOLE name degenerates to glob "*.html", hence json glob
        // "*.json", hence a regex of ".*\.json" — which matched JMeter's own statistics.json,
        // written by JsonExporter before any exporter runs. The check concluded a listener
        // report existed and skipped, so the run produced NO Web Insight report and exited 0.
        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(
                        outputDir.toFile(), "${timestamp}", run),
                "a whole-name timestamp must not match an unrelated .json");
    }

    @Test
    void aLiteralAsteriskIsNotTreatedAsAWildcard() throws IOException {
        touch("repXXXort.json");
        // 'rep*ort.html' is rejected by the filename validator, so the glob is the default
        // name — which this directory does not contain.
        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(
                outputDir.toFile(), "rep*ort.html", run("ANY", 1L)));
    }

    @Test
    void detectsTheDefaultName() throws IOException {
        ReportData run = run("DEFAULT-NAME", 1_700_000_000_000L);
        writeJsonFor("web-insight-report.json", run);

        assertTrue(WebInsightDataExporter.thisRunAlreadyReported(outputDir.toFile(), null, run));
        assertTrue(WebInsightDataExporter.thisRunAlreadyReported(outputDir.toFile(), "", run));
    }

    @Test
    void missingDirectoryIsNotACollision() {
        ReportData run = run("ANY", 1L);
        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(
                new File(outputDir.toFile(), "nope"), null, run));
        assertFalse(WebInsightDataExporter.thisRunAlreadyReported(null, null, run));
    }

    /**
     * A CI/CD JSON carrying the run id of the given run — the real artifact's shape, minus
     * every key the decision does not read.
     */
    private void writeJsonFor(String name, ReportData data) throws IOException {
        String json = "{\n  \"runId\" : \"" + RunIdentity.of(data) + "\",\n"
                + "  \"testName\" : \"" + data.getMetadata().getTestName() + "\"\n}\n";
        Files.write(outputDir.resolve(name), json.getBytes(StandardCharsets.UTF_8));
    }

    private static ReportData run(String name, long startMillis) {
        ReportData data = new ReportData();
        TestMetadata meta = new TestMetadata();
        meta.setTestName(name);
        meta.setStartTime(Instant.ofEpochMilli(startMillis));
        meta.setDurationMillis(5_000L);
        meta.setTotalSamples(10);
        meta.setTotalErrors(1);
        data.setMetadata(meta);
        return data;
    }

    /**
     * An unrecognised boolean warns and falls through to "unset" rather than meaning false.
     *
     * <p>{@code -Jwebinsight.report.junit=yes} used to match neither the {@code "true"} branch
     * nor the null/empty one, so it neither enabled JUnit XML nor let anything else decide — a
     * typo silently voided the setting with no message anywhere. There is no GUI surface on this
     * path, so "unset" resolves to off here; the warning is the part that matters.
     */
    /**
     * P4: {@code jmeter -g} given a CSV as its server data collects it in-process, with the
     * collector's own code, into a payload of THIS run - the identity matches by construction, so
     * the payload is accepted - and a rebuild from the same file keeps its collectionId.
     */
    @Test
    void aCsvGivenAsServerDataIsCollectedInProcessForThisRun() throws Exception {
        File jtl = new File("../test-plans/goldens/plain.jtl");
        ReportConfiguration config = new WebInsightDataExporter().buildConfiguration(outputDir.toFile());
        config.setAnnotationsFile(new File("../test-plans/goldens/gate-server.json"));
        config.setServerMetricsFile(new File("../test-plans/goldens/server-metrics.csv"));
        com.jmeterwebinsightreport.core.model.ReportData run =
                new com.jmeterwebinsightreport.core.parser.JtlParser().parse(jtl, config.getReportTitle());
        new WebInsightDataExporter().collectServerCsvIfGiven(config, jtl, run);
        assertNull(config.getServerDataProblem());
        File payload = config.getServerMetricsFile();
        assertTrue(payload.getName().startsWith("wir-server-metrics-"), payload.toString());
        com.jmeterwebinsightreport.report.servermetrics.ServerMetricsPayloadLoader.Result read =
                new com.jmeterwebinsightreport.report.servermetrics.ServerMetricsPayloadLoader()
                        .read(payload, com.jmeterwebinsightreport.core.util.RunIdentity.of(run));
        assertNull(read.refusal(), String.valueOf(read.problem()));
        assertFalse(read.payload().getSeries().isEmpty());

        // The same file again: the same collectionId (stamped with the CSV's own time).
        ReportConfiguration again = new WebInsightDataExporter().buildConfiguration(outputDir.toFile());
        again.setAnnotationsFile(new File("../test-plans/goldens/gate-server.json"));
        again.setServerMetricsFile(new File("../test-plans/goldens/server-metrics.csv"));
        new WebInsightDataExporter().collectServerCsvIfGiven(again, jtl, null);
        assertEquals(read.payload().getCollectionId(), new com.jmeterwebinsightreport.report.servermetrics
                .ServerMetricsPayloadLoader().read(again.getServerMetricsFile(),
                        com.jmeterwebinsightreport.core.util.RunIdentity.of(run)).payload().getCollectionId());

        // A payload is left as it is; a CSV with no annotations is a refusal that says why.
        ReportConfiguration json = new WebInsightDataExporter().buildConfiguration(outputDir.toFile());
        json.setServerMetricsFile(new File("../test-plans/goldens/server-metrics.json"));
        new WebInsightDataExporter().collectServerCsvIfGiven(json, jtl, run);
        assertEquals("server-metrics.json", json.getServerMetricsFile().getName());
        ReportConfiguration bare = new WebInsightDataExporter().buildConfiguration(outputDir.toFile());
        bare.setAnnotationsFile(null);
        bare.setServerMetricsFile(new File("../test-plans/goldens/server-metrics.csv"));
        new WebInsightDataExporter().collectServerCsvIfGiven(bare, jtl, run);
        assertTrue(bare.getServerDataProblem().contains("no annotations file"), bare.getServerDataProblem());
    }

    /**
     * The in-process collection reuses the report's own parse of the JTL rather than reading it again
     * (PR #19 review F22), and writes exactly the payload wir-collect writes from the file: the same
     * runId, window, collectionId and series. The report's parse carries its ramp-up and Apdex
     * settings; neither moves the window or the identity, and --rampup is still the collector's.
     */
    @Test
    void theInProcessCollectionReusesTheReportsParseAndWritesWhatWirCollectWritesFromTheFile() throws Exception {
        File jtl = new File("../test-plans/goldens/plain.jtl");
        File csv = new File("../test-plans/goldens/server-metrics.csv");
        File annotations = new File("../test-plans/goldens/gate-server.json");
        for (long rampUp : new long[] {0L, 5_000L}) {
            ReportConfiguration config = new WebInsightDataExporter().buildConfiguration(outputDir.toFile());
            config.setAnnotationsFile(annotations);
            config.setServerMetricsFile(csv);
            config.setRampUpDurationMillis(rampUp);
            // Parsed as execute() parses it.
            com.jmeterwebinsightreport.core.parser.JtlParser parser = new com.jmeterwebinsightreport.core.parser.JtlParser();
            if (rampUp > 0) {
                parser.setRampUpDurationMillis(rampUp);
            }
            parser.setApdexThreshold(250);
            ReportData parsed = parser.parse(jtl, config.getReportTitle());
            new WebInsightDataExporter().collectServerCsvIfGiven(config, jtl, parsed);
            assertNull(config.getServerDataProblem());

            File standalone = outputDir.resolve("standalone-" + rampUp + ".json").toFile();
            java.util.List<String> args = new java.util.ArrayList<>(java.util.Arrays.asList("--jtl", jtl.getPath(),
                    "--annotations", annotations.getPath(), "--out", standalone.getPath(), "--csv", csv.getPath(),
                    "--no-wait", "--test-name", config.getReportTitle(),
                    "--collected-at", Instant.ofEpochMilli(csv.lastModified()).toString()));
            if (rampUp > 0) {
                args.add("--rampup");
                args.add(String.valueOf(rampUp));
            }
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            java.io.PrintStream quiet = new java.io.PrintStream(sink, true);
            assertEquals(0, com.jmeterwebinsightreport.WirCollect.run(args.toArray(new String[0]), quiet, quiet,
                    java.util.Collections.<String, String>emptyMap()), sink.toString());

            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode inProcess = json.readTree(config.getServerMetricsFile());
            com.fasterxml.jackson.databind.JsonNode fromFile = json.readTree(standalone);
            assertEquals(RunIdentity.of(parsed), inProcess.get("runId").asText());
            assertEquals(fromFile.get("runId"), inProcess.get("runId"));
            assertEquals(fromFile.get("window"), inProcess.get("window"));
            assertEquals(rampUp > 0, inProcess.get("window").hasNonNull("rampUpEnd"), inProcess.get("window").toString());
            assertEquals(fromFile.get("collectionId"), inProcess.get("collectionId"));
            assertEquals(fromFile.get("series"), inProcess.get("series"));
            assertEquals(fromFile, inProcess, "the whole payload");
        }
    }

    /**
     * A CSV given beside an annotations file found only by auto-detection in the output directory
     * is collected: the annotations are looked for before the CSV is read, not after it was
     * refused as having none (PR #19 review F3).
     */
    @Test
    void aCsvGivenBesideAutoDetectedAnnotationsIsCollected() throws Exception {
        File jtl = new File("../test-plans/goldens/plain.jtl");
        Files.copy(new File("../test-plans/goldens/gate-server.json").toPath(),
                outputDir.resolve("report-annotations.json"));
        ReportConfiguration config = new WebInsightDataExporter().buildConfiguration(outputDir.toFile());
        config.setAnnotationsFile(null);
        config.setServerMetricsFile(new File("../test-plans/goldens/server-metrics.csv"));

        new WebInsightDataExporter().readCompanionFiles(config, outputDir.toFile(), jtl, null);

        assertEquals(outputDir.resolve("report-annotations.json").toFile(), config.getAnnotationsFile());
        assertNull(config.getServerDataProblem());
        assertTrue(config.getServerMetricsFile().getName().startsWith("wir-server-metrics-"),
                config.getServerMetricsFile().toString());
    }

    /**
     * Only server data that is there, or a sentence about why it could not be read, keeps this run
     * from being "already reported". A file one shared run.properties names before a later step
     * writes it does not: the combined -n -t -e -o run keeps the listener's interim report and its
     * NEXT line rather than replacing it with a refusal (PR #19 review F2).
     */
    @Test
    void onlyServerDataThatIsThereBypassesTheSkip() throws IOException {
        ReportConfiguration config = new WebInsightDataExporter().buildConfiguration(outputDir.toFile());
        config.setServerMetricsFile(null);
        assertFalse(WebInsightDataExporter.bringsServerData(config));

        config.setServerMetricsFile(outputDir.resolve("server.csv").toFile());
        assertFalse(WebInsightDataExporter.bringsServerData(config), "a configured file nobody has written yet");

        touch("server.csv");
        assertTrue(WebInsightDataExporter.bringsServerData(config));

        config.setServerMetricsFile(null);
        config.setServerDataProblem("the server data server.csv is a CSV, and no annotations file says what to read from it");
        assertTrue(WebInsightDataExporter.bringsServerData(config), "a refusal is said in the report");
    }

    @Test
    void anUnrecognisedBooleanIsReportedAndTreatedAsUnset() {
        String before = JMeterUtils.getProperty("webinsight.report.junit");
        try {
            JMeterUtils.setProperty("webinsight.report.junit", "yes");
            ReportConfiguration config =
                    new WebInsightDataExporter().buildConfiguration(outputDir.toFile());
            assertFalse(config.isGenerateJunitXml(),
                    "'yes' is not a boolean this plugin accepts, and it must not enable anything");

            JMeterUtils.setProperty("webinsight.report.junit", "TRUE");
            assertTrue(new WebInsightDataExporter().buildConfiguration(outputDir.toFile())
                            .isGenerateJunitXml(),
                    "...while a recognised value still works, whatever its case");
        } finally {
            JMeterUtils.setProperty("webinsight.report.junit", before == null ? "" : before);
        }
    }
}
