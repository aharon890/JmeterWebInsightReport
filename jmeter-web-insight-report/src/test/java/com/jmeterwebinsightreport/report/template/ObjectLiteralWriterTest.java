package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The sidecar's object-literal writer (N2): a {@code __proto__} KEY becomes {@code ["__proto__"]:},
 * and every other byte passes through as it came.
 *
 * <p>The fixture pair under {@code src/test/resources/template/} is the contract with the browser:
 * this test pins the writer to turn {@code object-literal-input.json} into exactly
 * {@code object-literal-expected.js}, and {@code test-plans/js-unit/external-data-literal.test.js}
 * evaluates that very text in a vm and holds it to what JSON.parse makes of the input.
 */
class ObjectLiteralWriterTest {

    private static final String PREFIX = "window.__WIR_DATA__=";

    private static String resource(String name) throws IOException {
        try (InputStream in = ObjectLiteralWriterTest.class.getResourceAsStream("/template/" + name)) {
            assertNotNull(in, "missing test resource " + name);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        }
    }

    /** The whole sidecar, as either generator path writes it. */
    private static String sidecar(String json) throws IOException {
        StringWriter out = new StringWriter();
        ObjectLiteralWriter.assignment(PREFIX, w -> w.write(json)).writeTo(out);
        return out.toString();
    }

    /** Only the literal, written {@code step} characters at a time — a key split across writes. */
    private static String literal(String json, int step) throws IOException {
        StringWriter out = new StringWriter();
        // Closed, as any writer is; finish() is the call under test, and close() finishing again
        // is a no-op once nothing is held.
        try (ObjectLiteralWriter w = new ObjectLiteralWriter(out)) {
            for (int at = 0; at < json.length(); at += step) {
                String piece = json.substring(at, Math.min(json.length(), at + step));
                if (piece.length() == 1) {
                    w.write(piece.charAt(0));
                } else {
                    w.write(piece.toCharArray(), 0, piece.length());
                }
                w.flush();   // a flush mid-key must not decide it
            }
            w.finish();
        }
        return out.toString();
    }

    @Test
    void theFixtureIsWrittenExactlyAsTheBrowserTestReadsIt() throws IOException {
        assertEquals(resource("object-literal-expected.js"), sidecar(resource("object-literal-input.json")));
    }

    @Test
    void howTheTextIsChunkedChangesNothing() throws IOException {
        String json = resource("object-literal-input.json");
        String whole = literal(json, json.length());
        for (int step : new int[]{1, 2, 3, 5, 7, 11, 64}) {
            assertEquals(whole, literal(json, step), "written " + step + " characters at a time");
        }
    }

    @Test
    void aStringValueThatReadsProtoIsLeftAlone() throws IOException {
        assertEquals("{\"a\":\"__proto__\",\"b\":[\"__proto__\"],\"c\":{\"d\":\"__proto__\"}}",
                literal("{\"a\":\"__proto__\",\"b\":[\"__proto__\"],\"c\":{\"d\":\"__proto__\"}}", 4096));
    }

    @Test
    void aKeyThatOnlyContainsTheTokenIsLeftAlone() throws IOException {
        // The raw text "\"__proto__": holds the bare token; a find-and-replace would break it.
        String json = "{\"\\\"__proto__\":1,\"x\\\"__proto__\\\":\":2}";
        assertEquals(json, literal(json, 4096));
    }

    @Test
    void whitespaceBeforeTheColonIsKept() throws IOException {
        assertEquals("{ [\"__proto__\"] \n: 1 }", literal("{ \"__proto__\" \n: 1 }", 4096));
    }

    @Test
    void anEscapedSpellingIsTheKeyToo() throws IOException {
        // The literal reads a key after its escapes, so this one sets the prototype as well.
        assertEquals("{[\"\\u005f_proto__\"]:1}", literal("{\"\\u005f_proto__\":1}", 4096));
    }

    @Test
    void aLongStringPassesStraightThrough() throws IOException {
        String longKey = String.join("", Collections.nCopies(40, "__proto__"));
        String json = "{\"" + longKey + "\":\"" + longKey + "\",\"__proto__\":0}";
        assertEquals("{\"" + longKey + "\":\"" + longKey + "\",[\"__proto__\"]:0}", literal(json, 13));
    }

    @Test
    void aRealPayloadWithoutTheKeyIsByteIdentical() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ReportData data = new ReportData();
        ArrayList<SamplerStatistics> stats = new ArrayList<>();
        for (String name : new String[]{"GET /a?q=\"x\"", "POST /b\\c", "proto __proto__ name"}) {
            SamplerStatistics s = new SamplerStatistics();
            s.setSamplerName(name);
            s.setSampleCount(10);
            s.setPercentile95(150);
            stats.add(s);
        }
        data.setSamplerStatistics(stats);
        TestMetadata m = new TestMetadata();
        m.setTestName("Literal");
        m.setDurationMillis(60_000);
        data.setMetadata(m);
        ReportAnnotations a = json.readValue("{\"testNotes\":\"a \\\"__proto__\\\": note\","
                + "\"assertions\":[{\"id\":\"r\",\"target\":{\"sampler\":\"POST /b\\\\c\"},"
                + "\"metric\":\"p95\",\"fail\":{\"gt\":100}}]}", ReportAnnotations.class);
        String payload = new ReportDataSerializer().serializeChartData(data, a, null, GateEvaluation.of(data, a),
                null, null);

        assertEquals(PREFIX + payload + ";", sidecar(payload));
    }
}
