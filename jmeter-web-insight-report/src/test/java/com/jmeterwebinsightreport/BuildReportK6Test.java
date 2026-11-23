package com.jmeterwebinsightreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code build-report --from k6}: what a k6 run's report keeps from the JMeter report's promises, and what is k6's own
 * (spec 001, tasks T047 and T056).
 */
class BuildReportK6Test {

    private static final File K1 = new File("../test-plans/run-data/K1");
    private static final File K3 = new File("../test-plans/run-data/K3");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String... args) {
        return BuildReport.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8), Map.of());
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8) + outBytes.toString(StandardCharsets.UTF_8);
    }

    private static String html(File out) throws Exception {
        return Files.readString(new File(out, "web-insight-report.html").toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void externalDataWritesAndReferencesTheSidecarAsOnAJMeterRun() throws Exception {
        // FR-009: one self-contained page, with the external data file for large runs as today
        File out = tmp.resolve("k1-external").toFile();
        assertEquals(0, run("--from", "k6", "--k6-results", new File(K1, "results.csv.gz").getPath(),
                "--k6-summary", new File(K1, "summary.json").getPath(), "--out", out.getPath(),
                "--property", "webinsight.report.externaldata=true"), err());
        File sidecar = new File(out, "web-insight-data.js");
        assertTrue(sidecar.isFile(), "the payload is written beside the page");
        assertTrue(html(out).contains("src=\"web-insight-data.js\""), "and the page loads it");
        String payload = Files.readString(sidecar.toPath(), StandardCharsets.UTF_8);
        assertTrue(payload.startsWith("window.__WIR_DATA__"), payload.substring(0, 40));
    }

    @Test
    void aFailedK6ThresholdIsShownAndTheTestsAssertsGiveTheVerdict() throws Exception {
        // Decision 4: k6's thresholds are k6's; the verdict is the test's asserts. K1 fails rate<0.01 on purpose.
        File out = tmp.resolve("k1-asserts").toFile();
        assertEquals(0, run("--from", "k6", "--k6-results", new File(K1, "results.csv.gz").getPath(),
                "--k6-summary", new File(K1, "summary.json").getPath(), "--out", out.getPath(),
                "--annotations", new File(K1, "k1-annotations.json").getPath()), err());
        JsonNode j = JSON.readTree(new File(out, "web-insight-report.json"));
        assertEquals("PASS", j.path("status").asText(), "the asserts pass");
        String page = html(out);
        assertTrue(page.contains("id=\"toolThresholds\""), "the k6 tab lists the thresholds");
        assertTrue(page.contains("Thresholds (2 of 3 passed)"), "and says one failed");
        assertTrue(page.contains("class=\"wir-tool-failed\">failed</td>"), "marking it failed");
        assertTrue(page.contains("ASSERTS PASS"), "while the header's verdict is the asserts'");
    }

    @Test
    void anOlderK6FilesWholeSecondsAreSaidInTheNotes() throws Exception {
        // T056: a Notes line for Run.timeResolution s, and "not measured" for what k6 does not record
        File out = tmp.resolve("k3").toFile();
        assertEquals(0, run("--from", "k6", "--k6-results", new File(K3, "results.csv.gz").getPath(),
                "--k6-summary", new File(K3, "summary.json").getPath(), "--out", out.getPath()), err());
        String page = html(out);
        assertTrue(page.contains("id=\"runDataNotes\""), "the Notes tab says what the file lacks");
        assertTrue(page.contains("k6&#39;s results file has whole-second times")
                || page.contains("k6's results file has whole-second times"), "in k6's words");
        assertTrue(page.contains(">not measured</td>"), "bytes read not measured");
        assertTrue(page.contains("data-tab=\"tool\""), "its requests' timing parts and its summary still get the k6 tab");
    }

    @Test
    void aMillisecondK6FileHasNoWholeSecondsNote() throws Exception {
        File out = tmp.resolve("k1-ms").toFile();
        assertEquals(0, run("--from", "k6", "--k6-results", new File(K1, "results.csv.gz").getPath(),
                "--out", out.getPath()), err());
        String page = html(out);
        assertFalse(page.contains("id=\"runDataNotes\""), "nothing to say about a file with milliseconds");
        assertTrue(page.contains("data-tab=\"tool\""), "its checks and timings give the k6 tab without a summary");
        assertTrue(page.contains("data-counter=\"iterations\"") && page.contains(">not measured</div>"),
                "with no summary, the counters read not measured");
    }
}
