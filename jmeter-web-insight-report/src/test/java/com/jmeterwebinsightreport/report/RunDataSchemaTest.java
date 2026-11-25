package com.jmeterwebinsightreport.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ToolMeasures;
import com.jmeterwebinsightreport.core.rundata.RunDataFormat;
import com.jmeterwebinsightreport.core.rundata.RunDataWriter;
import com.jmeterwebinsightreport.core.rundata.RunHeader;
import com.jmeterwebinsightreport.core.rundata.RunSample;
import com.jmeterwebinsightreport.core.rundata.ToolRecords;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published run data schema is what the producer writes (spec 001, FR-010, FR-014, research R9, task T059), as
 * {@code AnnotationsSchemaTest} holds the annotations schema to its beans: every record's fields in
 * {@code static/schema/run-data.schema.json} are exactly the fields {@link RunDataWriter} writes when every one is set,
 * and its version pattern is {@link RunDataFormat}'s. A field added on one side only fails here.
 */
class RunDataSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode schema() throws Exception {
        try (InputStream in = RunDataSchemaTest.class.getResourceAsStream("/static/schema/run-data.schema.json")) {
            assertNotNull(in, "the schema ships in the JAR");
            return JSON.readTree(in);
        }
    }

    /** One record of each type with every field set, as the writer writes them: type -> record. */
    private static Map<String, JsonNode> everyFieldWritten() throws Exception {
        RunHeader h = new RunHeader("k6", "2.4.0", "every field");
        h.producerVersion = "9.9.9";
        h.timeResolution = "s";
        h.notMeasured.addAll(List.of("bytes", "sentBytes", "latency", "connect", "user", "iteration"));
        h.trendStatistics = List.of("avg", "min", "med", "max", "p(90)", "p(95)", "p(99)", "count");
        RunSample s = new RunSample("Login", 1_795_452_359_384L, 41, false);
        s.id = "s1";
        s.parent = "t1";
        s.link = RunSample.Link.SUBRESULT;
        s.kind = RunSample.Kind.TRANSACTION;
        s.idle = 3L;
        s.wallTime = 50L;
        s.code = "500";
        s.message = "Internal Server Error";
        s.failureMessage = "an assertion's message";
        s.user = "VU 1";
        s.iteration = 0;
        s.topLevel = false;
        s.samples = 2;
        s.errors = 1L;
        s.bytes = 10L;
        s.sentBytes = 11L;
        s.latency = 12L;
        s.connect = 13L;
        s.threads = 1;
        s.url = "http://x/login";
        s.method = "POST";
        for (String part : ToolMeasures.TIMING_PARTS) {
            s.timing(part, 0.15);
        }
        s.responseBody = "{\"error\":true}";
        ToolRecords.Check c = new ToolRecords.Check();
        c.time = 1_795_452_359_400L;
        c.name = "login is 200";
        c.group = "::Checkout";
        c.passed = true;
        c.user = "VU 1";
        c.iteration = 0;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (RunDataWriter w = new RunDataWriter(bytes, h)) {
            w.sample(s);
            w.users(1_795_452_359_384L, 1);
            w.check(c);
            ToolRecords.Threshold t = new ToolRecords.Threshold("http_req_duration{name:Login}", "p(95)<500", true);
            t.statistic = "p(95)";
            t.measuredValue = 412.5;
            t.metricType = "trend";
            t.metricContains = "time";
            w.threshold(t);
            w.counter("iterations", 11);
        }
        Map<String, JsonNode> byType = new TreeMap<>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                JsonNode r = JSON.readTree(line);
                byType.put(r.path("type").asText(), r);
            }
        }
        return byType;
    }

    private static Set<String> keys(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void everyRecordsFieldsAreTheOnesTheProducerWrites() throws Exception {
        JsonNode defs = schema().path("definitions");
        Map<String, JsonNode> written = everyFieldWritten();
        assertEquals(new TreeSet<>(List.of(RunDataFormat.TYPE_RUN, RunDataFormat.TYPE_SAMPLE, RunDataFormat.TYPE_USERS,
                RunDataFormat.TYPE_CHECK, RunDataFormat.TYPE_THRESHOLD, RunDataFormat.TYPE_COUNTER)), written.keySet());
        for (Map.Entry<String, JsonNode> e : written.entrySet()) {
            JsonNode def = defs.path(e.getKey());
            assertFalse(def.isMissingNode(), "the schema defines the " + e.getKey() + " record");
            assertEquals(keys(e.getValue()), keys(def.path("properties")), e.getKey() + ": the schema's fields");
            assertEquals(e.getKey(), def.path("properties").path("type").path("const").asText());
            assertFalse(def.path("additionalProperties").asBoolean(true), e.getKey() + ": a typo is caught");
            for (JsonNode required : def.path("required")) {
                assertTrue(e.getValue().has(required.asText()), e.getKey() + " requires " + required.asText());
            }
        }
        JsonNode run = written.get(RunDataFormat.TYPE_RUN);
        JsonNode runDef = defs.path("run").path("properties");
        assertEquals(keys(run.path("tool")), keys(runDef.path("tool").path("properties")));
        assertEquals(keys(run.path("producer")), keys(runDef.path("producer").path("properties")));
        assertEquals(new TreeSet<>(ToolMeasures.TIMING_PARTS),
                keys(defs.path("sample").path("properties").path("timings").path("properties")),
                "the timing parts are the tool tab's");
        assertEquals(0.15, written.get(RunDataFormat.TYPE_SAMPLE).path("timings").path("blocked").asDouble(),
                "to the hundredth");
    }

    @Test
    void theVersionPatternIsTheFormatsOwn() throws Exception {
        Pattern p = Pattern.compile(schema().path("definitions").path("run").path("properties")
                .path("formatVersion").path("pattern").asText());
        assertTrue(p.matcher(RunDataFormat.VERSION).matches(), "the version written");
        for (String major : RunDataFormat.READS) {
            assertTrue(p.matcher(major + ".7").matches(), "a later minor of a major read");
        }
        assertFalse(p.matcher("2.0").matches(), "a major version not read");
        assertFalse(p.matcher("1").matches());
    }

    @Test
    void theNotMeasuredFieldsAreSampleFields() throws Exception {
        JsonNode defs = schema().path("definitions");
        Set<String> sampleFields = keys(defs.path("sample").path("properties"));
        for (JsonNode f : defs.path("run").path("properties").path("notMeasured").path("items").path("enum")) {
            assertTrue(sampleFields.contains(f.asText()), f.asText() + " is a sample field");
        }
    }

    @Test
    void theCommittedRunDataUsesOnlyTheSchemasFields() throws Exception {
        JsonNode defs = schema().path("definitions");
        List<String> problems = new ArrayList<>();
        File dir = new File("../test-plans/run-data");
        for (File f : listRunData(dir)) {
            int n = 0;
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                n++;
                if (line.isBlank()) {
                    continue;
                }
                JsonNode r = JSON.readTree(line);
                JsonNode props = defs.path(r.path("type").asText()).path("properties");
                Iterator<String> names = r.fieldNames();
                while (names.hasNext()) {
                    String k = names.next();
                    if (!props.has(k)) {
                        problems.add(f.getName() + ":" + n + " " + k);
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    private static List<File> listRunData(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                out.addAll(listRunData(f));
            } else if (f.getName().endsWith(".jsonl")) {
                out.add(f);
            }
        }
        return out;
    }
}
