package com.jmeterwebinsightreport.report.servermetrics;

import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AnnotationLoader contract, on the server side: what was loaded, and what went wrong —
 * with the run-identity check that is the whole reason the payload carries a runId.
 */
class ServerMetricsPayloadLoaderTest {

    @TempDir
    Path dir;

    private File write(String name, String json) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    private static String payload(String schema, String runId) {
        return "{\"schema\":\"" + schema + "\",\"runId\":\"" + runId + "\",\"collectionId\":\"c0ffee\","
                + "\"window\":{\"start\":1,\"end\":2},\"sources\":[{\"id\":\"p\",\"type\":\"prometheus\",\"status\":\"OK\"}],"
                + "\"series\":[{\"source\":\"p\",\"query\":\"up\",\"resolvedQuery\":\"up\",\"status\":\"OK\","
                + "\"coverage\":1.0,\"instances\":[{\"labels\":{\"pod\":\"a\"},\"points\":[[1000,1.5]]}]}],"
                + "\"futureKey\":\"kept by FAIL_ON_UNKNOWN_PROPERTIES=false\"}";
    }

    @Test
    void nothingConfiguredIsNothingLoadedAndNoProblem() {
        ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader().read(null, "abc");
        assertFalse(r.present());
        assertNull(r.problem());
    }

    @Test
    void aConfiguredFileThatIsMissingIsAProblemNotASilentAbsence() {
        ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader()
                .read(dir.resolve("nope.json").toFile(), "abc");
        assertFalse(r.present());
        assertTrue(r.problem().contains("does not exist"), r.problem());
        assertTrue(r.problem().contains("nope.json"));
    }

    @Test
    void aGoodPayloadForThisRunLoads() throws Exception {
        ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader()
                .read(write("sm.json", payload(ServerMetricsPayload.SCHEMA, "abc")), "abc");
        assertTrue(r.present(), r.problem());
        assertNull(r.problem());
        ServerMetricsPayload p = r.payload();
        assertEquals("c0ffee", p.getCollectionId());
        assertEquals(1, p.getSeries().size());
        assertEquals(1.5, p.find("p", "up").getInstances().get(0).getPoints().get(0).getValue());
        assertEquals(1000L, p.find("p", "up").getInstances().get(0).getPoints().get(0).getTs());
        assertNotNull(p.getWindow());
    }

    @Test
    void whereAToolsExportWasReadFromTravelsAndAnOlderPayloadWithoutItStillLoads() throws Exception {
        // wir-collect --csv FILE --from TOOL: the source says which tool's export the file was, and a
        // series read under that tool's name says which name, what kind of number, and the scale.
        String withProvenance = payload(ServerMetricsPayload.SCHEMA, "abc")
                .replace("\"type\":\"prometheus\",", "\"type\":\"csv\",\"from\":\"datadog\",")
                .replace("\"query\":\"up\",", "\"query\":\"up\",\"readAs\":{\"tool\":\"datadog\","
                        + "\"metric\":\"postgresql.percent_usage_connections\",\"kind\":\"gauge\",\"scale\":100.0},");
        ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader().read(write("from.json", withProvenance), "abc");
        assertTrue(r.present(), r.problem());
        assertEquals("datadog", r.payload().getSources().get(0).getFrom());
        ServerMetricsPayload.ReadAs readAs = r.payload().find("p", "up").getReadAs();
        assertNotNull(readAs);
        assertEquals("datadog", readAs.getTool());
        assertEquals("postgresql.percent_usage_connections", readAs.getMetric());
        assertEquals("gauge", readAs.getKind());
        assertEquals(100.0, readAs.getScale());

        // Every payload collected before --from existed has neither, and loads as it always did.
        ServerMetricsPayloadLoader.Result old = new ServerMetricsPayloadLoader()
                .read(write("old.json", payload(ServerMetricsPayload.SCHEMA, "abc")), "abc");
        assertTrue(old.present(), old.problem());
        assertNull(old.payload().getSources().get(0).getFrom());
        assertNull(old.payload().find("p", "up").getReadAs());
    }

    @Test
    void howAWideCsvWasReadTravelsAndAnOlderPayloadWithoutItStillLoads() throws Exception {
        // wir-collect --csv FILE --layout wide --time-zone UTC: the source says the file was a wide
        // export, and which zone its zone-less times were read in.
        String wide = payload(ServerMetricsPayload.SCHEMA, "abc")
                .replace("\"type\":\"prometheus\",", "\"type\":\"csv\",\"layout\":\"wide\",\"timeZone\":\"Europe/Berlin\",");
        ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader().read(write("wide.json", wide), "abc");
        assertTrue(r.present(), r.problem());
        assertEquals("wide", r.payload().getSources().get(0).getLayout());
        assertEquals("Europe/Berlin", r.payload().getSources().get(0).getTimeZone());

        ServerMetricsPayloadLoader.Result old = new ServerMetricsPayloadLoader()
                .read(write("old.json", payload(ServerMetricsPayload.SCHEMA, "abc")), "abc");
        assertTrue(old.present(), old.problem());
        assertNull(old.payload().getSources().get(0).getLayout());
        assertNull(old.payload().getSources().get(0).getTimeZone());
    }

    @Test
    void howALongCsvsColumnsAndComponentWereGivenTravelsAndAnOlderPayloadWithoutThemStillLoads() throws Exception {
        // wir-collect --csv FILE --csv-columns SPEC --component ID: an InfluxDB or Dynatrace export,
        // whose columns had other names and whose rows named no component.
        String mapped = payload(ServerMetricsPayload.SCHEMA, "abc")
                .replace("\"type\":\"prometheus\",", "\"type\":\"csv\",\"columns\":\"time=_time,metric=_field,value=_value\","
                        + "\"component\":\"orders-db\",");
        ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader().read(write("mapped.json", mapped), "abc");
        assertTrue(r.present(), r.problem());
        assertEquals("time=_time,metric=_field,value=_value", r.payload().getSources().get(0).getColumns());
        assertEquals("orders-db", r.payload().getSources().get(0).getComponent());

        ServerMetricsPayloadLoader.Result old = new ServerMetricsPayloadLoader()
                .read(write("old.json", payload(ServerMetricsPayload.SCHEMA, "abc")), "abc");
        assertTrue(old.present(), old.problem());
        assertNull(old.payload().getSources().get(0).getColumns());
        assertNull(old.payload().getSources().get(0).getComponent());
    }

    @Test
    void aSeriesStatusThisVersionDoesNotKnowIsAProblemNotACrash() throws Exception {
        // A hand-edited file or a newer writer's word would otherwise surface as an
        // IllegalArgumentException from SeriesRecord.collectionStatus() inside the evaluator.
        ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader()
                .read(write("sm.json", payload(ServerMetricsPayload.SCHEMA, "abc").replace("\"status\":\"OK\",\"coverage\"",
                        "\"status\":\"ok\",\"coverage\"")), "abc");
        assertFalse(r.present());
        assertTrue(r.problem().contains("status 'ok'"), r.problem());
        assertTrue(r.problem().contains("sm.json"));
    }

    @Test
    void anotherRunsPayloadIsRefusedOutright() throws Exception {
        // Not rendered under a banner: a foreign payload drawn under this run's headings is the
        // bug RunIdentity exists to stop.
        ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader()
                .read(write("sm.json", payload(ServerMetricsPayload.SCHEMA, "other")), "abc");
        assertFalse(r.present());
        assertTrue(r.problem().contains("belongs to run other"), r.problem());
        assertTrue(r.problem().contains("this is run abc"));
        assertTrue(r.problem().contains("wir-collect"));
    }

    @Test
    void anotherSchemaAndAnUnreadableFileAreProblemsThatNameTheFile() throws Exception {
        ServerMetricsPayloadLoader.Result schema = new ServerMetricsPayloadLoader()
                .read(write("v2.json", payload("wir-server-metrics/2", "abc")), "abc");
        assertFalse(schema.present());
        assertTrue(schema.problem().contains("wir-server-metrics/2"), schema.problem());
        assertTrue(schema.problem().contains("different plugin version"));

        ServerMetricsPayloadLoader.Result broken = new ServerMetricsPayloadLoader()
                .read(write("broken.json", "{\"schema\":"), "abc");
        assertFalse(broken.present());
        assertTrue(broken.problem().contains("could not be read"), broken.problem());
        assertTrue(broken.problem().contains("broken.json"));
    }

    @Test
    void aPayloadIsOneJsonValueAndTrailingContentIsUnreadable() throws Exception {
        // SF4, the payload side: two payloads concatenated, or a stray brace after one, loaded the
        // first in silence.
        String good = payload(ServerMetricsPayload.SCHEMA, "abc");
        for (String trailing : new String[] {"\n" + payload(ServerMetricsPayload.SCHEMA, "abc") + "\n", "\n}"}) {
            ServerMetricsPayloadLoader.Result r = new ServerMetricsPayloadLoader()
                    .read(write("twice.json", good + trailing), "abc");
            assertFalse(r.present(), "trailing content must not load as the first payload: " + trailing);
            assertEquals(ServerMetricsPayloadLoader.Refusal.UNREADABLE, r.refusal());
            assertTrue(r.problem().startsWith("the server-metrics payload could not be read: "), r.problem());
            assertTrue(r.problem().contains("twice.json"), r.problem());
            // The published sentence is the fixed one: never the parser's token.
            assertEquals("the server-metrics payload could not be read: 'twice.json' — it is not a JSON payload "
                    + "wir-collect wrote. The parser's reason is in the run's console output", r.detail());
        }
        assertTrue(new ServerMetricsPayloadLoader().read(write("spaced.json", good + "\n\n"), "abc").present(),
                "trailing whitespace is not content");
    }

    @Test
    void aNullRunIdSkipsTheIdentityCheckOnly() throws Exception {
        // A caller with no run (a tool reading the file on its own) still gets schema checking.
        assertTrue(new ServerMetricsPayloadLoader()
                .read(write("sm.json", payload(ServerMetricsPayload.SCHEMA, "any")), null).present());
    }
}
