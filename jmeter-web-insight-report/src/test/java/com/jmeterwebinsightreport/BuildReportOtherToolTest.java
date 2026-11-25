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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A new tool by writing samples only (spec 001, US4, FR-010): D1, a run of an imaginary tool {@code demo} written by
 * hand from the Run data docs page alone, builds a report with the common tabs, {@code demo} in the header, neutral
 * words, and a {@code demo} tab only because it carries the tool's own records.
 */
class BuildReportOtherToolTest {

    private static final File D1 = new File("../test-plans/run-data/D1/run-data.jsonl");

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String... args) {
        return BuildReport.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8), Map.of());
    }

    private String log() {
        return errBytes.toString(StandardCharsets.UTF_8) + outBytes.toString(StandardCharsets.UTF_8);
    }

    private String build(File runData, String folder) throws Exception {
        File out = tmp.resolve(folder).toFile();
        assertEquals(0, run("--run-data", runData.getPath(), "--out", out.getPath()), log());
        return Files.readString(new File(out, "web-insight-report.html").toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void aRunOfAnyToolGetsTheReportWithItsNameAndNeutralWords() throws Exception {
        String page = build(D1, "d1");
        assertTrue(page.contains(">D1</h1>"), "its test name is the title");
        assertTrue(page.contains(" | demo 0.1"), "the header names the tool and its version");
        for (String tab : List.of("summary", "timeline", "samplers", "errors", "asserts", "server", "notes")) {
            assertTrue(page.contains("data-tab=\"" + tab + "\""), "the common tab " + tab);
        }
        assertTrue(page.contains("data-tab=\"samplers\">Requests</button>"), "Requests, not Samplers");
        assertTrue(page.contains(">Max Users</div>"), "Max Users, not Max Threads");
        assertTrue(page.contains(">not measured</td>"), "what demo does not record reads not measured");
        JsonNode json = new ObjectMapper().readTree(tmp.resolve("d1/web-insight-report.json").toFile());
        assertEquals("demo", json.path("tool").path("name").asText());
        assertEquals(4, json.path("totalSamples").asInt(), "two Checkout passes and two Health calls");
        assertEquals(1, json.path("totalErrors").asInt(), "one Checkout pass failed, because its Pay failed");
    }

    @Test
    void itsOwnRecordsGiveItsTabWithOnlyWhatTheRunCarries() throws Exception {
        File out = tmp.resolve("d1-tab").toFile();
        assertEquals(0, run("--run-data", D1.getPath(), "--out", out.getPath(),
                "--property", "webinsight.report.externaldata=true"), log());
        String page = Files.readString(new File(out, "web-insight-report.html").toPath(), StandardCharsets.UTF_8);
        assertTrue(page.contains("data-tab=\"tool\" id=\"toolTabBtn\">demo</button>"), "a tab named demo");
        assertTrue(page.contains("data-counter=\"iterations\""), "its counter, under its own name");
        assertFalse(page.contains("data-counter=\"vus_max\""), "not k6's counters");
        // spec 002: the rest of the tab is report.js's, from chartData.tool
        String js = Files.readString(new File(out, "web-insight-data.js").toPath(), StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode tool = new ObjectMapper()
                .readTree(js.substring(js.indexOf('{'), js.lastIndexOf('}') + 1)).path("tool");
        assertEquals("demo", tool.path("name").asText());
        assertEquals(1, tool.path("thresholds").size(), "its threshold");
        assertTrue(tool.path("checks").size() > 0, "and checks");
        assertEquals(0, tool.path("timings").size(), "no timing parts: no section it has nothing for");
        assertFalse(tool.path("k6").asBoolean());
        boolean checkout = false;
        for (com.fasterxml.jackson.databind.JsonNode g : tool.path("groupDurations")) {
            checkout |= "Checkout".equals(g.path("name").asText());
        }
        assertTrue(checkout, "its transactions' wall time");
    }

    @Test
    void withoutItsOwnRecordsThereIsNoToolTab() throws Exception {
        File samplesOnly = tmp.resolve("samples-only.jsonl").toFile();
        List<String> lines = Files.readAllLines(D1.toPath(), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.contains("\"type\":\"check\"") && !l.contains("\"type\":\"threshold\"")
                        && !l.contains("\"type\":\"counter\""))
                .map(l -> l.replaceAll(",\"wallTime\":[0-9]+", ""))
                .collect(Collectors.toList());
        Files.write(samplesOnly.toPath(), lines, StandardCharsets.UTF_8);
        String page = build(samplesOnly, "samples-only");
        assertFalse(page.contains("data-tab=\"tool\""), "a run of samples alone has the common tabs only");
        assertTrue(page.contains(" | demo 0.1"), "and still names its tool");
    }
}
