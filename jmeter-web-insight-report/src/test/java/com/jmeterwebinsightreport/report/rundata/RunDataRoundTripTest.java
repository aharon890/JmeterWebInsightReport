package com.jmeterwebinsightreport.report.rundata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.rundata.RunDataReader;
import com.jmeterwebinsightreport.core.rundata.RunDataWriter;
import com.jmeterwebinsightreport.core.rundata.RunHeader;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.template.ReportDataSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JMeter run written as run data and read back gives the report its JTL gives, identity included (spec 001,
 * FR-006 and FR-013, task T009, finding I1): every baseline input, the cut-off controllers, sub-results and
 * statistical rows included.
 */
class RunDataRoundTripTest {

    @TempDir
    Path tmp;

    @Test
    void aJtlAndItsRunDataGiveOneReport() throws Exception {
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, File> e : BaselineInputs.jtls(tmp).entrySet()) {
            File runData = tmp.resolve(e.getKey() + "-run-data.jsonl.gz").toFile();
            JtlParser parser = new JtlParser();
            ReportData fromJtl;
            try (RunDataWriter writer = new RunDataWriter(runData,
                    new RunHeader(RunHeader.TOOL_JMETER, "5.6.3", e.getKey()))) {
                parser.setSampleTap(s -> {
                    try {
                        writer.sample(s);
                    } catch (IOException x) {
                        throw new UncheckedIOException(x);
                    }
                });
                fromJtl = parser.parse(e.getValue(), e.getKey());
            }

            LiveStatisticsEngine engine = new LiveStatisticsEngine();
            engine.setRampUpDurationMillis(0);
            engine.setApdexThreshold(500);
            RunDataReader.Result read = new RunDataReader().read(runData, engine);
            ReportData fromRunData = engine.buildReportData(read.header.testName);

            assertEquals(0, read.skipped, e.getKey() + ": " + read.problems);
            if (!RunIdentity.of(fromJtl).equals(RunIdentity.of(fromRunData))) {
                differences.add(e.getKey() + ": runId " + RunIdentity.of(fromJtl) + " from the JTL, "
                        + RunIdentity.of(fromRunData) + " from its run data");
            }
            String a = json(fromJtl);
            String b = json(fromRunData);
            if (!a.equals(b)) {
                differences.add(e.getKey() + ": the report data differs" + firstDifference(a, b));
            }
        }
        assertTrue(differences.isEmpty(), String.join("\n", differences));
    }

    private static String json(ReportData data) throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode tree = m.readTree(new ReportDataSerializer().serializeToJson(data));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("metadata")).remove(
                List.of("reportGeneratedAt", "reportGeneratedAtIso"));
        return m.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(tree, m)).replace("\r\n", "\n");
    }

    private static JsonNode sorted(JsonNode node, ObjectMapper m) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode out = m.createObjectNode();
            java.util.TreeMap<String, JsonNode> fields = new java.util.TreeMap<>();
            node.fields().forEachRemaining(f -> fields.put(f.getKey(), sorted(f.getValue(), m)));
            fields.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = m.createArrayNode();
            node.forEach(c -> out.add(sorted(c, m)));
            return out;
        }
        return node;
    }

    private static String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        for (int i = 0; i < Math.min(e.length, a.length); i++) {
            if (!e[i].equals(a[i])) {
                return " at line " + (i + 1) + ": JTL [" + e[i].trim() + "] run data [" + a[i].trim() + "]";
            }
        }
        return " in length";
    }
}
