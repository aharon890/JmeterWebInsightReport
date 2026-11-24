package com.jmeterwebinsightreport.report.rundata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.listener.WebInsightReportListener;
import com.jmeterwebinsightreport.report.template.ReportDataSerializer;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report data both JMeter paths build today, held as committed baselines (spec 001, task T006/T007, finding U6).
 *
 * <p>Spec 001 routes the JTL parser and the listener through one feed shared with run data. A test that compares the
 * new feed with itself proves nothing, so the baselines were written by the code before that change (4.4.1, run
 * with {@code -Dwir.baseline.write=true}) and every later build is held to them: every row, the whole run, the time
 * buckets, the errors (response bodies included), the transaction hierarchy and {@code RunIdentity}. Only the time
 * the report was generated is left out, because it is the clock's, not the run's.
 */
class PreChangeBaselineTest {

    private static final Path BASELINES = Path.of("src/test/resources/rundata/baseline-4.4.1");
    private static final boolean WRITE = Boolean.getBoolean("wir.baseline.write");

    @TempDir
    Path tmp;

    private static String tx(int n, int failing) {
        return "\"Number of samples in transaction : " + n + ", number of failing samples : " + failing + "\"";
    }

    /** Every committed JTL a JMeter run can produce that the JTL path reads, by baseline name. */
    private Map<String, File> jtls() throws IOException {
        return BaselineInputs.jtls(tmp);
    }

    @Test
    void theJtlPathBuildsTheReportDataItBuiltBeforeSpec001() throws Exception {
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, File> e : jtls().entrySet()) {
            ReportData data = new JtlParser().parse(e.getValue(), e.getKey());
            check(e.getKey(), data, differences);
        }
        assertTrue(differences.isEmpty(), String.join("\n", differences));
    }

    @Test
    void theListenerBuildsTheReportDataItBuiltBeforeSpec001() throws Exception {
        List<String> differences = new ArrayList<>();
        check("listener", listenerRun(), differences);
        assertTrue(differences.isEmpty(), String.join("\n", differences));
    }

    /**
     * A listener run with every shape the listener nests or keeps: a request with two redirect sub-results, a
     * Transaction Controller (parent sample on) with a failed child carrying a body, a failed request on its own with
     * a body, and threads reported on each sample.
     */
    static ReportData listenerRun() throws Exception {
        WebInsightReportListener listener = new WebInsightReportListener();
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        Field f = WebInsightReportListener.class.getDeclaredField("statsEngine");
        f.setAccessible(true);
        f.set(listener, engine);

        long t = 1_700_000_000_000L;
        SampleResult home = sample(t, 120, "Home", "200", "OK", true, null);
        home.addSubResult(sample(t, 40, "Home-0", "302", "Found", true, null));
        home.addSubResult(sample(t + 40, 80, "Home-1", "200", "OK", true, null));
        home.setAllThreads(2);
        listener.sampleOccurred(new SampleEvent(home, "Shop 1-1"));

        SampleResult login = sample(t + 200, 50, "Login", "200", "OK", true, null);
        SampleResult pay = sample(t + 250, 150, "Pay", "500", "Internal Server Error", false,
                "{\"error\":\"card declined\"}");
        SampleResult checkout = sample(t + 200, 200, "Checkout", "500",
                "Number of samples in transaction : 2, number of failing samples : 1", false, null);
        checkout.addSubResult(login, false);
        checkout.addSubResult(pay, false);
        checkout.setAllThreads(2);
        listener.sampleOccurred(new SampleEvent(checkout, "Shop 1-1"));

        SampleResult search = sample(t + 600, 90, "Search", "404", "Not Found", false, "<html>no such page</html>");
        search.setAllThreads(1);
        listener.sampleOccurred(new SampleEvent(search, "Shop 1-2"));
        return engine.buildReportData("listener");
    }

    private static SampleResult sample(long start, long elapsed, String label, String code, String message,
                                       boolean ok, String body) {
        SampleResult r = SampleResult.createTestSample(start, start + elapsed);
        r.setSampleLabel(label);
        r.setResponseCode(code);
        r.setResponseMessage(message);
        r.setSuccessful(ok);
        r.setThreadName("Shop 1-1");
        if (body != null) {
            r.setResponseData(body, StandardCharsets.UTF_8.name());
        }
        return r;
    }

    private void check(String name, ReportData data, List<String> differences) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode tree = sorted(mapper.readTree(new ReportDataSerializer().serializeToJson(data)), mapper);
        stripClock(tree);
        ((ObjectNode) tree).put("runId", RunIdentity.of(data));
        // Jackson indents with the platform's line separator; the files are LF on every OS
        String actual = mapper.writeValueAsString(tree).replace("\r\n", "\n") + "\n";
        Path file = BASELINES.resolve(name + ".json");
        if (WRITE) {
            Files.createDirectories(BASELINES);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        String expected = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!expected.equals(actual)) {
            differences.add(name + ": differs from " + file + firstDifference(expected, actual));
        }
    }

    private static String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        for (int i = 0; i < Math.min(e.length, a.length); i++) {
            if (!e[i].equals(a[i])) {
                return " at line " + (i + 1) + ": expected [" + e[i].trim() + "] but was [" + a[i].trim() + "]";
            }
        }
        return " in length: " + e.length + " lines expected, " + a.length + " found";
    }

    /**
     * The same tree with every object's keys sorted. Jackson's order of a bean's properties is not fixed from one JVM
     * to the next, and the meaning is in the keys, not their order.
     */
    private static JsonNode sorted(JsonNode node, ObjectMapper mapper) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            java.util.TreeMap<String, JsonNode> fields = new java.util.TreeMap<>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), sorted(e.getValue(), mapper)));
            fields.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
            node.forEach(child -> out.add(sorted(child, mapper)));
            return out;
        }
        return node;
    }

    /** The report's generation time is the clock's, not the run's. */
    private static void stripClock(JsonNode node) {
        if (node instanceof ObjectNode) {
            Iterator<String> names = node.fieldNames();
            List<String> drop = new ArrayList<>();
            while (names.hasNext()) {
                String n = names.next();
                if (n.startsWith("reportGeneratedAt")) {
                    drop.add(n);
                }
            }
            ((ObjectNode) node).remove(drop);
        }
        for (JsonNode child : node) {
            stripClock(child);
        }
    }

    @Test
    void theBaselinesExist() {
        if (!WRITE) {
            assertEquals(12, BASELINES.toFile().list().length, "one baseline per input, written before spec 001");
        }
    }
}
