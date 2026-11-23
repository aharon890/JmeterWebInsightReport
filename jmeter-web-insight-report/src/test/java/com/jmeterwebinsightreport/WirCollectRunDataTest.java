package com.jmeterwebinsightreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.rundata.RunDataReader;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code wir-collect --run-data}: server data collected for any tool's run (spec 001, FR-005, task T033). */
class WirCollectRunDataTest {

    private static final File K6_RUN = new File("../test-plans/run-data/S1/k6-run-data.jsonl");
    private static final File ANNOTATIONS = new File("../test-plans/goldens/gate-server.json");
    private static final File CSV = new File("../test-plans/goldens/server-metrics.csv");

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int collect(String... args) {
        return WirCollect.run(args, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of());
    }

    @Test
    void theWindowIdentityAndTestNameComeFromTheRunData() throws Exception {
        File out = tmp.resolve("server-metrics.json").toFile();
        int code = collect("--run-data", K6_RUN.getPath(), "--annotations", ANNOTATIONS.getPath(),
                "--csv", CSV.getPath(), "--step", "1s", "--out", out.getPath(), "--no-wait");
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));

        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        RunDataReader.Result read = new RunDataReader().read(K6_RUN, engine);
        JsonNode payload = new ObjectMapper().readTree(out);
        assertEquals(RunIdentity.of(engine.buildReportData(read.header.testName)), payload.path("runId").asText(),
                "the payload is this run's, so build-report over the same run data accepts it");
    }

    @Test
    void theRunIsGivenOnceAndATestNameThatIsNotTheRunsIsRefused() {
        assertEquals(1, collect("--jtl", "a.jtl", "--run-data", K6_RUN.getPath(), "--annotations",
                ANNOTATIONS.getPath(), "--csv", CSV.getPath(), "--out", tmp.resolve("x.json").toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("not both"), err.toString(StandardCharsets.UTF_8));

        err.reset();
        assertEquals(1, collect("--run-data", K6_RUN.getPath(), "--annotations", ANNOTATIONS.getPath(),
                "--csv", CSV.getPath(), "--test-name", "another run", "--out", tmp.resolve("y.json").toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("is not the run's test name"),
                err.toString(StandardCharsets.UTF_8));
    }
}
