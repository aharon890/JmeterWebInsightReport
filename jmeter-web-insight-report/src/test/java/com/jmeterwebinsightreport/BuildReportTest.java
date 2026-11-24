package com.jmeterwebinsightreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.rundata.RunDataWriter;
import com.jmeterwebinsightreport.core.rundata.RunHeader;
import com.jmeterwebinsightreport.core.rundata.RunSample;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code build-report}, the report with no JMeter (spec 001, contracts/build-report-cli.md, task T013). */
class BuildReportTest {

    private static final File PLAIN = new File("../test-plans/goldens/plain.jtl");
    private static final long T = 1_700_000_000_000L;

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String... args) {
        return BuildReport.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8), Map.of());
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    /** The goldens' JTL as JMeter run data, written through the JTL parser's tap. */
    private File jmeterRunData(String testName) throws IOException {
        File f = tmp.resolve("plain-run-data.jsonl.gz").toFile();
        JtlParser parser = new JtlParser();
        try (RunDataWriter w = new RunDataWriter(f, new RunHeader(RunHeader.TOOL_JMETER, "5.6.3", testName))) {
            parser.setSampleTap(s -> {
                try {
                    w.sample(s);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            parser.parse(PLAIN, testName);
        }
        return f;
    }

    /** A small k6 run: one group pass with two requests, one failed. */
    private File k6RunData() throws IOException {
        File f = tmp.resolve("k6-run-data.jsonl").toFile();
        RunHeader h = new RunHeader("k6", "2.4.0", "checkout - DEV #7 (k6)");
        h.notMeasured.add("bytes");
        try (RunDataWriter w = new RunDataWriter(f, h)) {
            RunSample pass = new RunSample();
            pass.id = "t1";
            pass.kind = RunSample.Kind.TRANSACTION;
            pass.name = "Complete Checkout Flow";
            pass.start = T;
            pass.wallTime = 1000L;
            w.sample(pass);
            RunSample login = new RunSample("Login", T, 40, true);
            login.parent = "t1";
            login.code = "200";
            w.sample(login);
            RunSample pay = new RunSample("Pay", T + 100, 120, false);
            pay.parent = "t1";
            pay.code = "500";
            w.sample(pay);
        }
        return f;
    }

    private JsonNode json(File dir) throws IOException {
        return new ObjectMapper().readTree(new File(dir, "web-insight-report.json"));
    }

    @Test
    void itBuildsTheReportFromRunDataWithNoJMeterProperties() throws Exception {
        File out = tmp.resolve("report").toFile();
        int code = run("--run-data", jmeterRunData("plain").getPath(), "--out", out.getPath(),
                "--property", "webinsight.report.junit=true");
        assertEquals(0, code, err());
        assertTrue(new File(out, "web-insight-report.html").isFile());
        assertTrue(new File(out, "web-insight-report.xml").isFile(), "junit=true writes JUnit");
        JsonNode j = json(out);
        assertEquals("plain", j.path("testName").asText());
        assertFalse(j.has("tool"), "a JMeter run's JSON is as it was: no tool field (decision 12)");
        // The run's identity is its JTL's (FR-013)
        assertEquals(RunIdentity.of(new JtlParser().parse(PLAIN, "plain")), j.path("runId").asText());
    }

    @Test
    void aNonJMeterRunNamesItsToolInTheJson() throws Exception {
        File out = tmp.resolve("k6").toFile();
        assertEquals(0, run("--run-data", k6RunData().getPath(), "--out", out.getPath()), err());
        JsonNode j = json(out);
        assertEquals("k6", j.path("tool").path("name").asText());
        assertEquals("2.4.0", j.path("tool").path("version").asText());
        assertEquals("checkout - DEV #7 (k6)", j.path("testName").asText());
    }

    @Test
    void aPropertyOnTheCommandLineWinsOverThePropertiesFile() throws Exception {
        Path props = tmp.resolve("run.properties");
        Files.writeString(props, "webinsight.report.junit=true\nwebinsight.report.filename=from-file.html\n");
        File out = tmp.resolve("p").toFile();
        assertEquals(0, run("--run-data", k6RunData().getPath(), "--out", out.getPath(), "--properties",
                props.toString(), "--property", "webinsight.report.filename=from-option.html"), err());
        assertTrue(new File(out, "from-option.html").isFile());
        assertTrue(new File(out, "from-option.xml").isFile(), "the file's junit=true still applies");
        assertFalse(new File(out, "from-file.html").exists());
    }

    @Test
    void aTitleThatIsNotTheRunsTestNameIsRefused() throws Exception {
        // It would give the report another run's identity (A1)
        int code = run("--run-data", k6RunData().getPath(), "--out", tmp.resolve("t").toString(),
                "--property", "webinsight.report.title=Another run");
        assertEquals(1, code);
        assertTrue(err().contains("is not this run's test name"), err());
    }

    @Test
    void aBaselineOnAnotherToolsRunIsLeftOut() throws Exception {
        // Compare for runs of other tools, and across tools, is later (decision 6, FR-017)
        File out = tmp.resolve("b").toFile();
        assertEquals(0, run("--run-data", k6RunData().getPath(), "--out", out.getPath(), "--baseline",
                PLAIN.getPath()), err());
        assertTrue(err().contains("Compare baseline plain.jtl is left out"), err());
        String html = Files.readString(new File(out, "web-insight-report.html").toPath(), StandardCharsets.UTF_8);
        assertFalse(compared(html), "no comparison was built");

        // and on a JMeter run the same baseline is compared, as on jmeter -g (the control for the line above)
        File jmeter = tmp.resolve("j").toFile();
        assertEquals(0, run("--run-data", jmeterRunData("plain").getPath(), "--out", jmeter.getPath(), "--baseline",
                PLAIN.getPath()), err());
        String compared = Files.readString(new File(jmeter, "web-insight-report.html").toPath(),
                StandardCharsets.UTF_8);
        assertTrue(compared(compared), "a JMeter run's baseline is compared");
    }

    /** Whether the page's payload carries a comparison: report.js names the key on every page, the payload only when built. */
    private static boolean compared(String html) {
        return html.replace("\\\"", "\"").contains("\"hasComparison\":true");
    }

    @Test
    void fromK6ItReadsK6sOwnFilesAndKeepsTheRunDataWhenAsked() throws Exception {
        // Decision 8: the report's k6 adapter
        File k1 = new File("../test-plans/run-data/K1");
        File out = tmp.resolve("k6-files").toFile();
        assertEquals(0, run("--from", "k6", "--k6-results", new File(k1, "results.csv.gz").getPath(),
                "--k6-summary", new File(k1, "summary.json").getPath(), "--out", out.getPath(), "--write-run-data",
                "--property", "webinsight.report.title=K1 (k6)"), err());
        JsonNode j = json(out);
        assertEquals("k6", j.path("tool").path("name").asText());
        assertEquals("K1 (k6)", j.path("testName").asText(), "the title is the run's test name");
        assertTrue(new File(out, "web-insight-report-run-data.jsonl.gz").isFile(), "--write-run-data keeps it");
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("132 requests"),
                outBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void fromTakesK6Only() {
        assertEquals(1, run("--from", "gatling", "--k6-results", "x.csv", "--out", tmp.toString()));
        assertTrue(err().contains("--from takes k6"), err());
        errBytes.reset();
        assertEquals(1, run("--from", "k6", "--k6-results", "x.csv", "--run-data", "y.jsonl", "--out", tmp.toString()));
        assertTrue(err().contains("give the run once"), err());
    }

    @Test
    void usageFaultsExit1WithTheUsage() {
        assertEquals(1, run("--out", tmp.toString()));
        assertTrue(err().contains("is required") && err().contains("usage: build-report"), err());
        assertEquals(1, run("--run-data", "missing.jsonl", "--out", tmp.toString()));
        assertTrue(err().contains("does not exist"), err());
    }

    @Test
    void helpListsEveryOption() {
        assertEquals(0, run("--help"));
        String help = outBytes.toString(StandardCharsets.UTF_8);
        for (String option : new String[]{"--run-data", "--out", "--annotations", "--servermetrics", "--baseline",
                "--property", "--properties"}) {
            assertTrue(help.contains(option), option);
        }
    }
}
