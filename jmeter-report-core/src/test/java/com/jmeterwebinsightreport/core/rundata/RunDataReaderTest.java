package com.jmeterwebinsightreport.core.rundata;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading run data (spec 001, data-model.md "Reading order and memory" and "Validation", tasks T008 and T010). */
class RunDataReaderTest {

    private static final String RUN = "{\"type\":\"run\",\"formatVersion\":\"1.0\",\"tool\":{\"name\":\"k6\",\"version\":"
            + "\"2.4.0\"},\"testName\":\"checkout (k6)\",\"producer\":{\"name\":\"jmeter-web-insight-report\"}}";
    private static final long T = 1_700_000_000_000L;

    @TempDir
    Path tmp;

    private File file(String name, String... records) throws IOException {
        String text = String.join("\n", records) + "\n";
        Path p = tmp.resolve(name);
        if (name.endsWith(".gz")) {
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(p))) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        }
        return p.toFile();
    }

    private static String sample(String fields) {
        return "{\"type\":\"sample\"," + fields + "}";
    }

    private static ReportData read(File f, RunDataReader.Result[] out) throws IOException {
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        RunDataReader.Result r = new RunDataReader().read(f, engine);
        if (out != null) {
            out[0] = r;
        }
        return engine.buildReportData(r.header.testName);
    }

    private static SamplerStatistics row(ReportData d, String name) {
        return d.getSamplerStatistics().stream().filter(s -> s.getSamplerName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + name));
    }

    /** One group pass (no elapsed, no success) holding a nested pass and two requests, in the given order. */
    private static String[] checkoutPass(boolean childrenFirst) {
        String pass = sample("\"id\":\"t1\",\"kind\":\"transaction\",\"name\":\"Checkout\",\"start\":" + T
                + ",\"wallTime\":2000,\"user\":\"VU 1\",\"iteration\":0");
        String inner = sample("\"id\":\"t2\",\"parent\":\"t1\",\"kind\":\"transaction\",\"name\":\"Payment\",\"start\":"
                + (T + 100) + ",\"wallTime\":500");
        String login = sample("\"parent\":\"t1\",\"name\":\"Login\",\"start\":" + T + ",\"elapsed\":40,\"success\":true,"
                + "\"code\":\"200\"");
        String pay = sample("\"parent\":\"t2\",\"name\":\"Pay\",\"start\":" + (T + 100) + ",\"elapsed\":120,"
                + "\"success\":false,\"code\":\"500\",\"message\":\"Internal Server Error\"");
        return childrenFirst ? new String[]{RUN, pay, login, inner, pass} : new String[]{RUN, pass, login, inner, pay};
    }

    @Test
    void aGroupPassTakesItsTimeAndVerdictFromItsRequests() throws IOException {
        ReportData d = read(file("pass.jsonl", checkoutPass(false)), null);

        assertEquals(120, row(d, "Payment").getMeanResponseTime(), 1e-9, "the nested pass: its one request");
        assertEquals(160, row(d, "Checkout").getMeanResponseTime(), 1e-9, "Login 40 + Payment 120");
        assertEquals(1, row(d, "Checkout").getErrorCount(), "a failed request fails its passes");
        assertEquals(1, d.getTopLevelSampleCount(), "the whole run counts the outer pass once");
        assertEquals(1, d.getTopLevelErrorCount());
        assertEquals("Checkout", row(d, "Login").getParentSamplerName());
        assertEquals("Payment", row(d, "Pay").getParentSamplerName());
        assertEquals(2000, d.getMetadata().getDurationMillis(), "the pass's wall time ends the run, think time included");
    }

    @Test
    void recordsMayComeInAnyOrder() throws IOException {
        // A k6 pass is written when its group_duration arrives, after its requests (U1)
        ReportData parentFirst = read(file("a.jsonl", checkoutPass(false)), null);
        ReportData childrenFirst = read(file("b.jsonl", checkoutPass(true)), null);
        for (String n : new String[]{"Checkout", "Payment", "Login", "Pay"}) {
            assertEquals(row(parentFirst, n).getMeanResponseTime(), row(childrenFirst, n).getMeanResponseTime(), 1e-9, n);
            assertEquals(row(parentFirst, n).getErrorCount(), row(childrenFirst, n).getErrorCount(), n);
        }
        assertEquals(parentFirst.getTopLevelSampleCount(), childrenFirst.getTopLevelSampleCount());
    }

    @Test
    void aGzipFileReadsAsThePlainOne() throws IOException {
        ReportData plain = read(file("p.jsonl", checkoutPass(false)), null);
        ReportData gz = read(file("p.jsonl.gz", checkoutPass(false)), null);
        assertEquals(plain.getTopLevelSampleCount(), gz.getTopLevelSampleCount());
        assertEquals(row(plain, "Checkout").getMeanResponseTime(), row(gz, "Checkout").getMeanResponseTime(), 1e-9);
    }

    @Test
    void usersRecordsAreTheActiveThreads() throws IOException {
        // G1: a k6 run's vus. Without them Max VUs read 0.
        ReportData d = read(file("u.jsonl", RUN,
                "{\"type\":\"users\",\"time\":" + T + ",\"active\":3}",
                "{\"type\":\"users\",\"time\":" + (T + 1000) + ",\"active\":4}",
                sample("\"name\":\"A\",\"start\":" + T + ",\"elapsed\":10,\"success\":true")), null);
        assertEquals(4, d.getMetadata().getTotalThreads());
    }

    @Test
    void theFirstRecordMustBeTheRunRecord() throws IOException {
        File f = file("no-run.jsonl", sample("\"name\":\"A\",\"start\":" + T + ",\"elapsed\":10,\"success\":true"));
        IOException e = assertThrows(IOException.class, () -> read(f, null));
        assertTrue(e.getMessage().contains("not the \"run\" record"), e.getMessage());
    }

    @Test
    void aMajorVersionItDoesNotReadIsRefusedNamingBothVersions() throws IOException {
        // FR-011
        File f = file("v2.jsonl", RUN.replace("\"1.0\"", "\"2.0\""),
                sample("\"name\":\"A\",\"start\":" + T + ",\"elapsed\":10,\"success\":true"));
        IOException e = assertThrows(IOException.class, () -> read(f, null));
        assertTrue(e.getMessage().contains("\"2.0\"") && e.getMessage().contains("1.x")
                && e.getMessage().contains("No numbers are shown"), e.getMessage());
    }

    @Test
    void aLaterMinorVersionWithNewFieldsAndTypesIsRead() throws IOException {
        // R9: a minor version only adds; a 1.0 reader skips what it does not know, in silence
        RunDataReader.Result[] r = new RunDataReader.Result[1];
        ReportData d = read(file("v13.jsonl", RUN.replace("\"1.0\"", "\"1.3\""),
                "{\"type\":\"heatmap\",\"cells\":[1,2]}",
                sample("\"name\":\"A\",\"start\":" + T + ",\"elapsed\":10,\"success\":true,\"tcpRetries\":2")), r);
        assertEquals(1, d.getTopLevelSampleCount());
        assertEquals(0, r[0].skipped, String.valueOf(r[0].problems));
    }

    @Test
    void aBadLineIsSkippedAndNamedByItsLine() throws IOException {
        RunDataReader.Result[] r = new RunDataReader.Result[1];
        ReportData d = read(file("bad.jsonl", RUN,
                sample("\"name\":\"A\",\"start\":" + T + ",\"elapsed\":10,\"success\":true"),
                "{not json",
                sample("\"name\":\"B\",\"start\":" + T + ",\"success\":true")), r);
        assertEquals(1, d.getTopLevelSampleCount());
        assertEquals(2, r[0].skipped);
        assertTrue(r[0].problems.get(0).startsWith("line 3:"), r[0].problems.toString());
        assertTrue(r[0].problems.get(1).startsWith("line 4:") && r[0].problems.get(1).contains("elapsed time"),
                r[0].problems.toString());
    }

    @Test
    void secondsAndMicrosecondsAreRefusedByLine() throws IOException {
        RunDataReader.Result[] r = new RunDataReader.Result[1];
        read(file("t.jsonl", RUN,
                sample("\"name\":\"ok\",\"start\":" + T + ",\"elapsed\":10,\"success\":true"),
                sample("\"name\":\"s\",\"start\":1700000000,\"elapsed\":10,\"success\":true"),
                sample("\"name\":\"us\",\"start\":1700000000000000,\"elapsed\":10,\"success\":true")), r);
        assertEquals(2, r[0].skipped);
        assertTrue(r[0].problems.get(0).contains("seconds"), r[0].problems.toString());
        assertTrue(r[0].problems.get(1).contains("micro"), r[0].problems.toString());
    }

    @Test
    void aParentNamingNoSampleIsReportedAndTheSampleCountedAtTheTop() throws IOException {
        RunDataReader.Result[] r = new RunDataReader.Result[1];
        ReportData d = read(file("orphan.jsonl", RUN,
                sample("\"parent\":\"nobody\",\"name\":\"A\",\"start\":" + T + ",\"elapsed\":10,\"success\":true")), r);
        assertEquals(1, d.getTopLevelSampleCount());
        assertFalse(r[0].problems.isEmpty());
        assertTrue(r[0].problems.get(0).contains("\"nobody\""), r[0].problems.toString());
    }

    @Test
    void aFileWithNoUsableSampleIsRefused() throws IOException {
        File f = file("empty.jsonl", RUN, "{\"type\":\"users\",\"time\":" + T + ",\"active\":1}");
        IOException e = assertThrows(IOException.class, () -> read(f, null));
        assertTrue(e.getMessage().contains("no usable sample"), e.getMessage());
    }

    @Test
    void aToolsOwnRecordsAreKeptForItsTab() throws IOException {
        RunDataReader.Result[] r = new RunDataReader.Result[1];
        read(file("tool.jsonl", RUN,
                sample("\"name\":\"A\",\"start\":" + T + ",\"elapsed\":10,\"success\":true"),
                "{\"type\":\"check\",\"time\":" + T + ",\"name\":\"is 200\",\"group\":\"::Checkout\",\"passed\":true}",
                "{\"type\":\"check\",\"time\":" + T + ",\"name\":\"is 200\",\"group\":\"::Checkout\",\"passed\":false}",
                "{\"type\":\"threshold\",\"metric\":\"http_req_failed\",\"expression\":\"rate<0.01\",\"passed\":false}",
                "{\"type\":\"counter\",\"name\":\"dropped_iterations\",\"value\":0}"), r);
        ToolRecords tool = r[0].tool;
        assertEquals(1, tool.checks().size());
        assertEquals(1, tool.checks().get(0).passes);
        assertEquals(1, tool.checks().get(0).fails);
        assertFalse(tool.thresholds().get(0).passed);
        assertEquals(0.0, tool.counters().get("dropped_iterations"));
        assertEquals("k6", r[0].header.toolName);
        assertEquals("2.4.0", r[0].header.toolVersion);
    }
}
