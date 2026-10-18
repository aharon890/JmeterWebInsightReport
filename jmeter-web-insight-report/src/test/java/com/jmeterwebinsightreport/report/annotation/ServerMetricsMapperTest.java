package com.jmeterwebinsightreport.report.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.Across;
import com.jmeterwebinsightreport.core.servermetrics.KpiUsability;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;
import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.core.servermetrics.ServerMetricsConfig;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapper under the same discipline as {@code AssertionRuleMapper}: every fault is named
 * against the file's own label, recorded on the KPI so it refuses, and the rest of the file
 * still maps. Plus the rules only the mapper can apply — components and their catalogue
 * (plans/server-metrics-components.md C1–C9), the masking of credentials before publication, and
 * the retired block keys ({@code kpis}, {@code packs}, {@code sources}, {@code gates}).
 */
class ServerMetricsMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One custom component — every assert on it writes its own query. */
    private static final String COMP = "\"components\":{\"k\":{\"type\":\"custom\",\"description\":\"Event bus\"}}";

    /** A retired sources block, as a file written before C8 still carries it. */
    private static final String PROM = "\"sources\":{\"prom\":{\"type\":\"prometheus\","
            + "\"url\":\"https://prom.corp/api/v1\",\"auth\":{\"bearerTokenEnv\":\"PROM_TOKEN\"},"
            + "\"step\":\"15s\",\"clockOffsetMs\":-1200}}";

    private static String kpi(String extra) {
        return "{\"id\":\"lag\",\"component\":\"k\",\"query\":\"max by (p) (lag)\",\"unit\":\"msgs\","
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":800}" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static ReportAnnotations parse(String document) throws Exception {
        return JSON.readValue(document, ReportAnnotations.class);
    }

    private static ServerMetricsMapper.Result map(String serverMetrics) throws Exception {
        return new ServerMetricsMapper().map(parse("{\"serverMetrics\":" + serverMetrics + "}").getServerMetrics());
    }

    private static ServerKpi only(ServerMetricsMapper.Result r) {
        assertEquals(1, r.config().kpis().size(), r.findings().toString());
        return r.config().kpis().get(0);
    }

    private static boolean hasReason(ServerKpi kpi, KpiUsability.Reason reason) {
        for (KpiUsability u : kpi.declarationProblems()) {
            if (u.reason() == reason) return true;
        }
        return false;
    }

    @Test
    void aFileWithNoBlockMapsToNone() throws Exception {
        assertNull(parse("{\"testNotes\":\"n\"}").getServerMetrics());
        ServerMetricsMapper.Result r = new ServerMetricsMapper().map(null);
        assertTrue(r.config().kpis().isEmpty());
        assertFalse(r.config().declaresAnything());
        assertTrue(r.findings().isEmpty());
        assertNotNull(r.config().gate(ServerGate.DEFAULT_ID), "the implicit gate exists even on none()");
    }

    @Test
    void aWellFormedCustomAssertMapsOntoItsComponentAndTheCollectionSource() throws Exception {
        // A tolerance rides on max (or min): on a percentile there is no level for it to hold.
        ServerMetricsMapper.Result r = map("{" + COMP + ",\"asserts\":[" + kpi("\"warn\":{\"gt\":640},"
                + "\"across\":\"avg\",\"requiresLoad\":\"load:throughput\",\"reduce\":{\"kind\":\"max\","
                + "\"over\":{\"sustainedFor\":\"30s\"}},\"pack\":\"kafka@1\"") + "]}");

        assertTrue(r.findings().isEmpty(), r.findings().toString());
        ServerKpi k = only(r);
        assertNull(KpiUsability.refusal(k));
        assertEquals("lag", k.id());
        assertTrue(k.idDeclared());
        // C8: no source in the file — every assert names the collection's one source.
        assertEquals(ServerSource.COLLECTION_ID, k.source());
        assertTrue(r.config().sources().isEmpty(), "the collector injects the source; the file declares none");
        assertNull(k.metric(), "a custom assert has no catalogue metric");
        assertEquals("max by (p) (lag)", k.query());
        assertEquals("msgs", k.unit());
        assertEquals(Reducer.Kind.MAX, k.reducer().kind());
        assertEquals(30_000L, k.reducer().over().sustainedForMillis());
        assertEquals(Across.AVG, k.across());
        assertEquals("load:throughput", k.requiresLoad());
        assertEquals("kafka@1", k.pack());
        assertEquals(640.0, k.warn().value());
        assertEquals(800.0, k.fail().value());

        // The component is the group: a declared gate, always building (C6), described.
        assertEquals("k", k.gate());
        assertTrue(r.config().gatesDeclared());
        assertNull(r.config().gate(ServerGate.DEFAULT_ID), "there is no implicit component");
        ServerGate k8 = r.config().gate("k");
        assertEquals("custom", k8.type());
        assertEquals("Event bus", k8.label());

        ServerKpi p95 = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"reduce\":{\"kind\":\"p95\"}") + "]}"));
        assertEquals(Reducer.Kind.PERCENTILE, p95.reducer().kind());
        assertEquals(95.0, p95.reducer().percentile());
    }

    @Test
    void aCatalogueMetricBringsItsQueryUnitReducerAndAcross() throws Exception {
        ServerMetricsMapper.Result r = map("{\"components\":{\"orders-db\":{\"type\":\"postgres\"}},\"asserts\":["
                + "{\"component\":\"orders-db\",\"metric\":\"connections.used.pct\",\"fail\":{\"gt\":85}}]}");
        assertTrue(r.findings().isEmpty(), r.findings().toString());
        ServerKpi k = only(r);
        assertNull(KpiUsability.refusal(k));
        assertEquals("connections.used.pct", k.metric());
        assertEquals("orders-db-connections-used-pct", k.id(), "named after what it judges");
        assertFalse(k.idDeclared());
        assertEquals("100 * sum by (instance) (pg_stat_activity_count) / max by (instance) "
                + "(pg_settings_max_connections)", k.query());
        assertEquals("%", k.unit());
        assertEquals(Reducer.Kind.MAX, k.reducer().kind());
        assertEquals(Across.MAX, k.across());
    }

    @Test
    void aComponentsMatchIsExpandedIntoItsCatalogueQueries() throws Exception {
        ServerKpi k = only(map("{\"components\":{\"orders-db\":{\"type\":\"postgres\","
                + "\"match\":\"instance=\\\"db-1:9187\\\"\"}},\"asserts\":["
                + "{\"component\":\"orders-db\",\"metric\":\"connections.active\",\"fail\":{\"gt\":100}}]}"));
        assertEquals("sum by (instance) (pg_stat_activity_count{state=\"active\",instance=\"db-1:9187\"})", k.query());
    }

    /**
     * A catalogue metric with no prometheus query - only a tool's name for it - is still a series
     * the collector can fetch from a CSV, so its assert resolves to the neutral identity
     * {@code <type>:<metric>}, the component's match in braces, rather than to no query at all.
     */
    @Test
    void aMetricWithNoPrometheusQueryResolvesToItsNeutralIdentity() throws Exception {
        Catalogue acme = Catalogue.fromFiles(java.util.Collections.singletonMap("acme", JSON.readTree(
                "{\"type\":\"acme\",\"version\":1,\"exporter\":\"acme_exporter\",\"metrics\":["
                        + "{\"name\":\"pool.used.pct\",\"unit\":\"%\",\"reduce\":\"avg\",\"names\":{\"telegraf\":null,"
                        + "\"datadog\":{\"metric\":\"acme.pool.pct\",\"kind\":\"gauge\",\"scale\":100}}}]}")));
        ServerMetricsMapper mapper = new ServerMetricsMapper(acme);
        ServerMetricsMapper.Result r = mapper.map(parse("{\"serverMetrics\":{\"components\":{\"db\":{\"type\":\"acme\","
                + "\"match\":\" instance=\\\"db-1\\\" \"}},\"asserts\":[{\"component\":\"db\",\"metric\":\"pool.used.pct\","
                + "\"fail\":{\"gt\":90}}]}}").getServerMetrics());
        assertTrue(r.findings().isEmpty(), r.findings().toString());
        ServerKpi k = only(r);
        assertNull(KpiUsability.refusal(k));
        assertEquals("acme:pool.used.pct{instance=\"db-1\"}", k.query());
        assertEquals("pool.used.pct", k.metric());
        assertEquals("%", k.unit());
        assertEquals(Reducer.Kind.AVG, k.reducer().kind());

        // No match, no braces.
        ServerKpi bare = only(mapper.map(parse("{\"serverMetrics\":{\"components\":{\"db\":{\"type\":\"acme\"}},"
                + "\"asserts\":[{\"component\":\"db\",\"metric\":\"pool.used.pct\",\"fail\":{\"gt\":90}}]}}")
                .getServerMetrics()));
        assertEquals("acme:pool.used.pct", bare.query());
    }

    @Test
    void whatTheAssertWritesOverridesTheCatalogue() throws Exception {
        ServerKpi k = only(map("{\"components\":{\"db\":{\"type\":\"postgres\"}},\"asserts\":["
                + "{\"component\":\"db\",\"metric\":\"up\",\"unit\":\"state\",\"reduce\":{\"kind\":\"last\"},"
                + "\"across\":\"avg\",\"fail\":{\"lt\":1}}]}"));
        assertNull(KpiUsability.refusal(k));
        assertEquals("state", k.unit());
        assertEquals(Reducer.Kind.LAST, k.reducer().kind());
        assertEquals(Across.AVG, k.across());
    }

    @Test
    void theMetricOrQueryRulesAreRefusalsNamingWhatToWrite() throws Exception {
        String db = "\"components\":{\"db\":{\"type\":\"postgres\"},\"c\":{\"type\":\"custom\"}}";

        ServerKpi unknown = only(map("{" + db + ",\"asserts\":[{\"id\":\"x\",\"component\":\"db\",\"metric\":\"nope\",\"fail\":{\"gt\":1}}]}"));
        assertTrue(hasReason(unknown, KpiUsability.Reason.UNKNOWN_METRIC));
        assertTrue(KpiUsability.refusal(unknown).reasonText().contains("postgres@1 — it offers: up, connections.used.pct"),
                KpiUsability.refusal(unknown).reasonText());
        assertEquals(1, KpiUsability.check(unknown).size(), "one finding for one mistake, not also 'no query'");

        ServerKpi both = only(map("{" + db + ",\"asserts\":[{\"id\":\"x\",\"component\":\"db\",\"metric\":\"up\","
                + "\"query\":\"q\",\"fail\":{\"lt\":1}}]}"));
        assertTrue(hasReason(both, KpiUsability.Reason.METRIC_OR_QUERY));

        ServerKpi neither = only(map("{" + db + ",\"asserts\":[{\"id\":\"x\",\"component\":\"db\","
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}"));
        assertTrue(KpiUsability.refusal(neither).reasonText().contains("names neither a \"metric\" from postgres@1"));

        ServerKpi metricOnCustom = only(map("{" + db + ",\"asserts\":[{\"id\":\"x\",\"component\":\"c\",\"metric\":\"up\","
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}"));
        assertTrue(KpiUsability.refusal(metricOnCustom).reasonText().contains("is custom, which has no catalogue"));

        ServerKpi customNoQuery = only(map("{" + db + ",\"asserts\":[{\"id\":\"x\",\"component\":\"c\","
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}"));
        assertTrue(KpiUsability.refusal(customNoQuery).reasonText().contains("custom component writes its own"));

        // A custom assert still needs its own reducer: there is no catalogue to default from.
        ServerKpi noReduce = only(map("{" + db + ",\"asserts\":[{\"id\":\"x\",\"component\":\"c\",\"query\":\"q\","
                + "\"fail\":{\"gt\":1}}]}"));
        assertTrue(hasReason(noReduce, KpiUsability.Reason.UNKNOWN_REDUCER));
    }

    @Test
    void componentsAreTheGroupsAndAnAssertMustNameADeclaredOne() throws Exception {
        ServerMetricsMapper.Result r = map("{\"components\":{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},"
                + "\"jvm\":{\"type\":\"custom\"}},\"asserts\":["
                + kpi("\"component\":\"kafka\"").replace("\"component\":\"k\",", "") + ","
                + "{\"id\":\"unnamed\",\"query\":\"q\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}},"
                + kpi("\"id\":\"typo\",\"component\":\"kafkaa\"").replace("\"id\":\"lag\",\"component\":\"k\",", "") + "]}");

        assertEquals("Kafka", r.config().gate("kafka").label());
        assertEquals("jvm", r.config().gate("jvm").label(), "the label falls back to the id");

        List<ServerKpi> kpis = r.config().kpis();
        assertEquals(3, kpis.size(), "every assert is kept and counted, the broken ones included");
        assertNull(KpiUsability.refusal(kpis.get(0)), kpis.get(0).declarationProblems().toString());
        assertEquals("kafka", kpis.get(0).gate());
        assertTrue(hasReason(kpis.get(1), KpiUsability.Reason.UNDECLARED_COMPONENT));
        assertNull(kpis.get(1).gate(), "named none: no component, by contract");
        assertTrue(KpiUsability.refusal(kpis.get(1)).reasonText().startsWith("names no component"));
        assertTrue(hasReason(kpis.get(2), KpiUsability.Reason.UNDECLARED_COMPONENT));
        assertEquals("kafkaa", kpis.get(2).gate(), "a typo is reported under the name it asked for");
        assertTrue(KpiUsability.refusal(kpis.get(2)).reasonText().contains("(declared: kafka, jvm)"),
                "the sentence names what IS declared");
        assertEquals("typo", r.findings().get(1).ruleId(), "findings use the file's own label");

        // No components at all: there is no implicit one to fall back to.
        ServerKpi orphan = only(map("{\"asserts\":[{\"id\":\"o\",\"query\":\"q\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}"));
        assertTrue(KpiUsability.refusal(orphan).reasonText().contains("none are declared"),
                KpiUsability.refusal(orphan).reasonText());
    }

    @Test
    void aComponentOfUnknownTypeIsOneBlockProblemAndRefusesItsAsserts() throws Exception {
        ServerMetricsMapper.Result r = map("{\"components\":{\"ora\":{\"type\":\"nosuchdb\"}},\"asserts\":["
                + "{\"id\":\"a\",\"component\":\"ora\",\"query\":\"q\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}},"
                + "{\"id\":\"b\",\"component\":\"ora\",\"query\":\"q\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}");
        assertEquals(1, r.config().errorCount(), "counted once, at the component: " + r.config().problems());
        assertEquals("serverMetrics.components.ora", r.config().problems().get(0).ruleId());
        for (ServerKpi k : r.config().kpis()) {
            assertEquals(KpiUsability.Reason.UNKNOWN_TYPE, KpiUsability.refusal(k).reason());
            assertEquals("component 'ora' has type 'nosuchdb', which is not one of postgres, kafka, redis, host-linux,"
                            + " aws-ec2, oracle, influxdb2, influxdb3, jvm, http-service, kubernetes-pod, mysql, custom",
                    KpiUsability.refusal(k).reasonText());
        }
        assertNotNull(r.config().gate("ora"), "kept, so its asserts get this sentence rather than 'not declared'");
    }

    @Test
    void failsBuildOnAComponentIsRefusedBecauseEveryComponentBuilds() throws Exception {
        ServerMetricsMapper.Result r = map("{\"components\":{\"k\":{\"type\":\"custom\",\"failsBuild\":false}},"
                + "\"asserts\":[" + kpi("") + "]}");
        assertEquals(1, r.config().errorCount(), r.config().problems().toString());
        assertTrue(r.config().problems().get(0).message().contains("only a warn band"));
        // Refused, never obeyed: the component is still declared, and there is nothing left on
        // the model that could make it observe-only.
        assertNotNull(r.config().gate("k"), "the key is refused, the component kept");
    }

    @Test
    void aComponentIdTheJunitReportHasAlreadySpentIsRefused() throws Exception {
        // The JUnit writer names a suite "<testName> - <componentId>". A component called
        // "assertions" would produce a second suite with the client rule suite's exact name.
        for (String reserved : new String[] {"assertions", "(no gate)"}) {
            ServerMetricsMapper.Result r = map("{\"components\":{\"" + reserved + "\":{\"type\":\"custom\"},"
                    + "\"k\":{\"type\":\"custom\"}},\"asserts\":[" + kpi("") + "]}");
            assertNull(r.config().gate(reserved), reserved + " must not become a component");
            assertNotNull(r.config().gate("k"), "the components beside it still map");
            assertTrue(r.findings().get(0).message().contains("reserved"), r.findings().get(0).message());
            assertTrue(r.config().errorCount() > 0, "and it counts as a block-level config error");
        }
    }

    @Test
    void aBandThatOverflowsToInfinityIsUnreadableEvenAsABareJsonNumber() throws Exception {
        ServerKpi k = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"fail\":{\"gt\":1e400}") + "]}"));
        assertNull(k.fail(), "an infinite threshold is not a threshold");
        assertNotNull(KpiUsability.refusal(k), "and the KPI is refused rather than passing silently");
    }

    @Test
    void aSourcesBlockAndAGatesBlockAreRetiredAndNeverRead() throws Exception {
        ServerMetricsMapper.Result r = map("{" + PROM + ",\"gates\":{\"g\":{}}," + COMP + ",\"asserts\":[" + kpi("") + "]}");
        assertEquals(2, r.config().errorCount(), r.config().problems().toString());
        String problems = r.config().problems().toString();
        assertTrue(problems.contains("--prometheus-url"), "names where the connection goes now: " + problems);
        assertTrue(problems.contains("\"components\""), "names what a gate became: " + problems);
        assertTrue(r.config().sources().isEmpty(), "a source in the file must not take effect anywhere");
        assertNull(r.config().gate("g"));
        assertNull(KpiUsability.refusal(only(r)), "the asserts beside them still map");

        ServerKpi oldKeys = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"source\":\"prom\",\"gate\":\"g\"") + "]}"));
        assertTrue(hasReason(oldKeys, KpiUsability.Reason.RETIRED_KEY));
        assertTrue(KpiUsability.refusal(oldKeys).reasonText().startsWith("\"source\" is not a key of a server assert"));
    }

    @Test
    void theDocumentMasksALiteralSecretAndLeavesReferencesAlone() throws Exception {
        // A retired sources block is still a place a credential can be left, and the document is
        // published whole: refusing the block tells the author, masking keeps the value out.
        ReportAnnotations a = parse("{\"serverMetrics\":{\"sources\":{"
                + "\"p\":{\"type\":\"prometheus\",\"url\":\"u\",\"auth\":{\"bearerToken\":\"CANARY\",\"apiKeyEnv\":\"K\"},\"note\":\"n\"},"
                + "\"f\":{\"type\":\"file\",\"path\":\"r/\"}},\"asserts\":[" + kpi("") + "]}}");
        ServerMetricsJson published = a.getServerMetrics().publishable();
        JsonNode out = JSON.readTree(JSON.writeValueAsString(published));

        assertEquals(ServerMetricsJson.MASK, out.get("sources").get("p").get("auth").get("bearerToken").asText());
        assertEquals("K", out.get("sources").get("p").get("auth").get("apiKeyEnv").asText());
        assertEquals("n", out.get("sources").get("p").get("note").asText(), "unknown keys survive the copy");
        assertEquals("r/", out.get("sources").get("f").get("path").asText());
        assertEquals(1, out.get("asserts").size());
        assertFalse(out.toString().contains("CANARY"));
        assertEquals("CANARY", a.getServerMetrics().getSources().get("p").getAuth().get("bearerToken"),
                "the original is untouched — the editor still writes back what was loaded");
        ReportAnnotations clean = parse("{\"serverMetrics\":{" + COMP + "}}");
        assertSame(clean.getServerMetrics(), clean.getServerMetrics().publishable(),
                "a clean block is published as itself, not copied");
    }

    @Test
    void everyCredentialShapeIsMaskedInThePublishedDocument() throws Exception {
        ReportAnnotations a = parse("{\"serverMetrics\":{\"sources\":{"
                + "\"u\":{\"type\":\"prometheus\",\"url\":\"https://alice:CANARY-PW@prom.corp:9090/api/v1\"},"
                + "\"t\":{\"type\":\"prometheus\",\"url\":\"https://prom.corp/api/v1\",\"token\":\"CANARY-TOKEN\","
                + "\"headers\":{\"Authorization\":\"Bearer CANARY-HEADER\"},\"note\":\"kept\"},"
                + "\"ph\":{\"type\":\"prometheus\",\"url\":\"https://${env.USER}:CANARY-PH@${env.HOST}/api\"},"
                + "\"ok\":{\"type\":\"prometheus\",\"url\":\"https://prom.corp/api/v1?match=a@b\","
                + "\"auth\":{\"bearerTokenEnv\":\"PROM_TOKEN\"}}},\"asserts\":[" + kpi("") + "]}}");

        ServerMetricsJson published = a.getServerMetrics().publishable();
        String out = JSON.writeValueAsString(published);
        assertFalse(out.contains("CANARY"), "no credential may reach the published document: " + out);
        JsonNode sources = JSON.readTree(out).get("sources");
        assertEquals("https://***@prom.corp:9090/api/v1", sources.get("u").get("url").asText());
        assertEquals(ServerMetricsJson.MASK, sources.get("t").get("token").asText());
        assertEquals(ServerMetricsJson.MASK, sources.get("t").get("headers").asText());
        assertEquals("kept", sources.get("t").get("note").asText(), "a merely unknown key survives");
        assertEquals("https://***@${env.HOST}/api", sources.get("ph").get("url").asText());
        assertEquals("https://prom.corp/api/v1?match=a@b", sources.get("ok").get("url").asText());
        assertSame(a.getServerMetrics().getSources().get("ok"), published.getSources().get("ok"));

        // Saved from the report and loaded again, the block is still refused - as retired.
        ServerMetricsMapper.Result reloaded = new ServerMetricsMapper().map(JSON.readValue(out, ServerMetricsJson.class));
        assertEquals(1, reloaded.config().errorCount(), reloaded.config().problems().toString());
        assertEquals("CANARY-TOKEN", a.getServerMetrics().getSources().get("t").getUnknownKeys().get("token"));
    }

    @Test
    void aCredentialQueryParameterAndACredentialHeldInVarsAreRefusedAndMasked() throws Exception {
        ReportAnnotations a = parse("{\"serverMetrics\":{\"sources\":{"
                + "\"q\":{\"type\":\"prometheus\",\"url\":\"https://prom.corp/api/v1?api_key=CANARY-QUERY&step=1\"},"
                + "\"e\":{\"type\":\"prometheus\",\"url\":\"${env.PROM}\"},"
                + "\"r\":{\"type\":\"prometheus\",\"url\":\"https://prom.corp/api/v1?token=${env.TOK}\"},"
                + "\"ok\":{\"type\":\"prometheus\",\"url\":\"https://prom.corp/api/v1?api_key=${secret.PROM_KEY}&ns=${env.NS}\"}},"
                + "\"environments\":{\"prod\":{\"vars\":{\"PROM\":\"https://bob:CANARY-ENV@prom.corp/api/v1\","
                + "\"TOK\":\"CANARY-REF\",\"NS\":\"shop\",\"OWNER\":\"ops@corp.example\","
                + "\"Q\":\"https://prom.corp/api/v1?sig=CANARY-SIG\"}}},"
                + "\"defaultEnv\":\"prod\"," + COMP + ",\"asserts\":[" + kpi("") + "]}}");

        // The vars are still read (queries interpolate them), so a credential there is refused.
        ServerMetricsMapper.Result result = new ServerMetricsMapper().map(a.getServerMetrics());
        String findings = result.findings().toString();
        assertTrue(findings.contains("vars.PROM carries credentials in a URL"), findings);
        assertTrue(findings.contains("vars.Q carries a credential in the URL parameter 'sig'"), findings);
        assertFalse(findings.contains("vars.OWNER"), "an address is not a credential: " + findings);
        assertFalse(findings.contains("vars.NS"), findings);

        String out = JSON.writeValueAsString(a.getServerMetrics().publishable());
        assertFalse(out.contains("CANARY"), "no credential may reach the published document: " + out);
        JsonNode doc = JSON.readTree(out);
        JsonNode vars = doc.get("environments").get("prod").get("vars");
        assertEquals("https://prom.corp/api/v1?api_key=***&step=1", doc.get("sources").get("q").get("url").asText(),
                "only the credential's value is masked");
        assertEquals("https://***@prom.corp/api/v1", vars.get("PROM").asText());
        assertEquals(ServerMetricsJson.MASK, vars.get("TOK").asText(), "the var a credential position reaches is masked");
        assertEquals("https://prom.corp/api/v1?sig=***", vars.get("Q").asText());
        assertEquals("shop", vars.get("NS").asText(), "a var no credential position reaches is not touched");
        assertEquals("ops@corp.example", vars.get("OWNER").asText());
        assertEquals("https://prom.corp/api/v1?api_key=${secret.PROM_KEY}&ns=${env.NS}",
                doc.get("sources").get("ok").get("url").asText(), "a reference is a name, not a secret");
        assertEquals("CANARY-REF", a.getServerMetrics().getEnvironments().get("prod").getVars().get("TOK"));
    }

    @Test
    void credentialParametersAreFoundByRedactionsNameRuleAndOnlyInTheQuery() {
        assertEquals("api_key", ServerMetricsJson.credentialParam("https://h/p?api_key=x"));
        assertEquals("access_token", ServerMetricsJson.credentialParam("https://h/p?a=1&access_token=x#f"));
        assertNull(ServerMetricsJson.credentialParam("https://h/p?token=${secret.T}"), "a secret reference");
        assertNull(ServerMetricsJson.credentialParam("https://h/p?token=${secret.A}${secret.B}"));
        assertEquals("token", ServerMetricsJson.credentialParam("https://h/p?token=${secret.T}x"), "not ONLY a reference");
        assertEquals("token", ServerMetricsJson.credentialParam("https://h/p?token=${env.T}"));
        assertNull(ServerMetricsJson.credentialParam("https://h/p?token="), "an empty value carries nothing");
        assertNull(ServerMetricsJson.credentialParam("https://h/token=x"), "a path is not a query");
        assertNull(ServerMetricsJson.credentialParam("https://h/p?match=up&step=15s"));
        assertNull(ServerMetricsJson.credentialParam(null));

        assertEquals("https://h/p?a=1&api_key=***&&b=2#frag",
                ServerMetricsJson.maskUrl("https://h/p?a=1&api_key=s3cret&&b=2#frag"), "separators and fragment kept");
        assertEquals("https://***@h/p?sig=***", ServerMetricsJson.maskUrl("https://u:p@h/p?sig=s"));
        assertEquals("https://h/p", ServerMetricsJson.maskUrl("https://h/p"));

        assertNotNull(ServerMetricsJson.varCredential("bob:pw@prom.corp:9090"), "a password half needs no scheme");
        assertNotNull(ServerMetricsJson.varCredential("https://bob@prom.corp"), "in a URL any userinfo counts");
        assertNull(ServerMetricsJson.varCredential("ops@corp.example"));
        assertNull(ServerMetricsJson.varCredential("shop"));
        assertNull(ServerMetricsJson.varCredential(null));

        // The collector's --prometheus-url check reads the authority textually too.
        assertTrue(ServerMetricsJson.authorityHasUserInfo("https://bob:p@ssw0rd@prom.corp/api"));
        assertFalse(ServerMetricsJson.authorityHasUserInfo("https://prom.corp/api?match=a@b"));
    }

    @Test
    void userInfoIsMaskedTextuallyAndOnlyInTheAuthority() {
        assertEquals("https://***@h/p", ServerMetricsJson.maskUserInfo("https://u:p@h/p"));
        assertEquals("https://***@h", ServerMetricsJson.maskUserInfo("https://u@h"));
        assertEquals("https://***@h?q=1", ServerMetricsJson.maskUserInfo("https://u:p@h?q=1"));
        assertEquals("//***@h/p", ServerMetricsJson.maskUserInfo("//u:p@h/p"), "scheme-relative");
        assertEquals("***@h:9090/p", ServerMetricsJson.maskUserInfo("u:p@h:9090/p"), "no scheme at all");
        assertEquals("https://***@h/p", ServerMetricsJson.maskUserInfo("https://u:p@x@h/p"), "the LAST @ ends userinfo");
        assertEquals("https://h/p@q", ServerMetricsJson.maskUserInfo("https://h/p@q"), "an @ in the path is not userinfo");
        assertEquals("https://h/p", ServerMetricsJson.maskUserInfo("https://h/p"));
        assertNull(ServerMetricsJson.maskUserInfo(null));
    }

    @Test
    void packsIsAConfigLevelErrorThatSaysPacksAreNotInThisVersion() throws Exception {
        ServerMetricsMapper.Result r = map("{\"packs\":[\"kafka\"]," + COMP + ",\"asserts\":[" + kpi("") + "]}");
        assertEquals(1, r.config().problems().size());
        assertEquals(1, r.config().errorCount());
        AssertionValidator.Finding f = r.config().problems().get(0);
        assertEquals(ServerMetricsMapper.BLOCK_ID, f.ruleId());
        assertEquals(AssertionValidator.Severity.ERROR, f.severity());
        assertFalse(f.message().contains("Add from pack"), "no button by that name exists: " + f.message());
        assertTrue(f.message().contains("not available in this version"), f.message());
        assertNull(KpiUsability.refusal(only(r)), "the asserts beside it still map");
    }

    @Test
    void aLeftoverKpisKeyIsRefusedNamingAsserts() throws Exception {
        ServerMetricsMapper.Result r = map("{" + COMP + ",\"kpis\":[{\"id\":\"old\",\"component\":\"k\","
                + "\"query\":\"q\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}],\"asserts\":[" + kpi("") + "]}");
        assertEquals(1, r.config().problems().size(), r.findings().toString());
        AssertionValidator.Finding f = r.config().problems().get(0);
        assertTrue(f.message().startsWith("\"kpis\" is not a serverMetrics key"), f.message());
        assertTrue(r.findings().contains(f), "the author is told, not only the exit code");
        assertNull(KpiUsability.refusal(only(r)), "the asserts beside it still map");
        assertFalse(r.config().kpis().stream().anyMatch(k -> "old".equals(k.id())), "nothing under kpis is read");

        for (String value : new String[] {"[]", "{}", "null", "\"x\""}) {
            ServerMetricsMapper.Result alone = map("{" + COMP + ",\"kpis\":" + value + "}");
            assertEquals(1, alone.config().errorCount(), "kpis: " + value);
            assertTrue(alone.config().kpis().isEmpty(), "kpis: " + value);
        }
    }

    @Test
    void retiredCanvasKeysRefuseTheKpiAndNameTheReplacement() throws Exception {
        ServerKpi k = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"assert\":{\"type\":\"range\"},"
                + "\"warnAt\":0.8,\"onMissing\":\"fail\",\"onLoadShortfall\":\"scaleBand\","
                + "\"loadValidity\":{},\"required\":true,\"scope\":\"steadyState\",\"overlayOn\":\"throughput\","
                + "\"range\":{\"min\":0,\"max\":800},\"note\":\"kept\"") + "]}"));

        int retired = 0;
        for (KpiUsability u : k.declarationProblems()) {
            if (u.reason() == KpiUsability.Reason.RETIRED_KEY) retired++;
        }
        assertEquals(9, retired, k.declarationProblems().toString());

        ServerKpi misplaced = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"sustainedFor\":\"30s\"") + "]}"));
        assertTrue(hasReason(misplaced, KpiUsability.Reason.RETIRED_KEY));
        assertTrue(KpiUsability.refusal(misplaced).reasonText().contains("reduce.over"));
        assertTrue(KpiUsability.refusal(k).reasonText().contains("\"reduce\""), "the first sentence says what to write");
        for (KpiUsability u : KpiUsability.check(k)) {
            assertFalse(u.reasonText().contains("note"), "a merely unknown key is ignored in silence");
        }
    }

    @Test
    void reduceAndItsToleranceAreReadOrRefusedWithTheGrammar() throws Exception {
        ServerKpi mean = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"reduce\":{\"kind\":\"mean\"}") + "]}"));
        assertTrue(hasReason(mean, KpiUsability.Reason.UNKNOWN_REDUCER));
        assertTrue(KpiUsability.refusal(mean).reasonText().contains("p95"), "the vocabulary is in the sentence");

        for (String canvasKind : new String[] {"deltaPre", "deltaBaseline"}) {
            ServerKpi delta = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"reduce\":{\"kind\":\"" + canvasKind + "\"}") + "]}"));
            assertTrue(hasReason(delta, KpiUsability.Reason.UNKNOWN_REDUCER), canvasKind + ": " + delta.declarationProblems());
            assertNull(delta.reducer(), canvasKind + " must not map to any reducer");
        }

        ServerKpi budget = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"reduce\":{\"kind\":\"avg\","
                + "\"over\":{\"breachBudget\":0.02}}") + "]}"));
        assertEquals(0.02, budget.reducer().over().breachBudget());

        ServerKpi both = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"reduce\":{\"kind\":\"avg\","
                + "\"over\":{\"breachBudget\":0.02,\"sustainedFor\":\"30s\"}}") + "]}"));
        assertTrue(hasReason(both, KpiUsability.Reason.BAD_TOLERANCE));

        ServerKpi bare = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"reduce\":{\"kind\":\"avg\","
                + "\"over\":{\"sustainedFor\":30}}") + "]}"));
        assertTrue(hasReason(bare, KpiUsability.Reason.BAD_TOLERANCE), "a bare number is not a duration");

        ServerKpi percent = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"reduce\":{\"kind\":\"avg\","
                + "\"over\":{\"breachBudget\":2}}") + "]}"));
        assertTrue(KpiUsability.refusal(percent).reasonText().contains("0.02"), "2% is written 0.02");

        ServerKpi across = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"across\":\"worst\"") + "]}"));
        assertTrue(hasReason(across, KpiUsability.Reason.UNREADABLE_FIELD));
    }

    @Test
    void bandsFollowTheClientGrammarAndAThresholdIsALiteral() throws Exception {
        ServerKpi two = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"fail\":{\"gt\":800,\"lt\":50}") + "]}"));
        assertTrue(hasReason(two, KpiUsability.Reason.BAND_UNREADABLE));
        assertNull(two.fail(), "an unreadable band yields no band — and the recorded fact refuses the KPI");

        ServerKpi interpolated = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"fail\":{\"gt\":\"${env.lagLimit}\"}") + "]}"));
        assertTrue(KpiUsability.refusal(interpolated).reasonText().contains("literal"));

        ServerKpi corridor = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"fail\":{\"outside\":[0,800]}") + "]}"));
        assertTrue(KpiUsability.refusal(corridor).reasonText().contains("two bands"));

        ServerKpi quoted = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"fail\":{\"gt\":\"800\"}") + "]}"));
        assertEquals(800.0, quoted.fail().value(), "a quoted number is a common hand-edit and unambiguous");

        for (String bad : new String[] {"NaN", "Infinity", "-Infinity"}) {
            ServerKpi nan = only(map("{" + COMP + ",\"asserts\":[" + kpi("\"fail\":{\"gt\":\"" + bad + "\"}") + "]}"));
            assertNull(nan.fail(), bad);
            assertTrue(hasReason(nan, KpiUsability.Reason.BAND_UNREADABLE), bad);
        }
        ServerMetricsMapper.Result nanFloor = map("{\"collection\":{\"coverageFloor\":\"NaN\"}}");
        assertEquals(ServerMetricsConfig.DEFAULT_COVERAGE_FLOOR, nanFloor.config().coverageFloor(),
                "a NaN floor would switch coverage checking off");
        assertEquals(1, nanFloor.findings().size());
    }

    @Test
    void environmentsCollectionAndRunLevelDefaultsAreCarried() throws Exception {
        ServerMetricsConfig c = map("{\"environments\":{\"DEV\":{\"vars\":{\"ns\":\"dev\"}},\"TEST\":{}},"
                + "\"defaultEnv\":\"TEST\",\"requiresLoad\":\"load:throughput\","
                + "\"collection\":{\"coverageFloor\":0.8,\"excludeRampUp\":false}}").config();
        assertEquals("dev", c.environments().get("DEV").get("ns"));
        assertTrue(c.environments().get("TEST").isEmpty());
        assertEquals("TEST", c.defaultEnv());
        assertEquals("load:throughput", c.requiresLoad());
        assertEquals(0.8, c.coverageFloor());
        assertFalse(c.excludeRampUp());

        ServerMetricsMapper.Result bad = map("{\"collection\":{\"coverageFloor\":90}}");
        assertEquals(ServerMetricsConfig.DEFAULT_COVERAGE_FLOOR, bad.config().coverageFloor());
        assertEquals(AssertionValidator.Severity.WARNING, bad.findings().get(0).severity());

        ServerMetricsMapper.Result typo = map("{\"environments\":{\"DEV\":{}},\"defaultEnv\":\"PORD\"}");
        assertEquals(1, typo.config().errorCount(), typo.findings().toString());
        assertEquals(ServerMetricsMapper.BLOCK_ID + ".defaultEnv", typo.findings().get(0).ruleId());
    }

    @Test
    void theBlockRoundTripsWithItsUnknownKeysAndWithoutInventedNulls() throws Exception {
        // The report writes this file back. Whatever this version does not understand — at every
        // level, a retired block included — must come back untouched, and a field the author never
        // wrote must not come back as null. The round-trip of an unedited file is an empty diff.
        String document = "{\"serverMetrics\":{\"prometheus\":\"http://x\"," + PROM + ","
                + "\"gates\":{\"g\":{\"failsBuild\":false}}," + COMP + ",\"collection\":{\"excludeRampUp\":false},"
                + "\"asserts\":[" + kpi("\"note\":\"kept\",\"enabled\":false,\"unit\":\"\",\"description\":null") + "]}}";
        ReportAnnotations a = parse(document);
        JsonNode out = JSON.readTree(JSON.writeValueAsString(a)).get("serverMetrics");

        assertEquals("http://x", out.get("prometheus").asText(), "block-level unknown key survives");
        assertEquals("kept", out.get("asserts").get(0).get("note").asText(), "assert-level unknown key survives");
        assertEquals("PROM_TOKEN", out.get("sources").get("prom").get("auth").get("bearerTokenEnv").asText());
        assertFalse(out.get("gates").get("g").get("failsBuild").asBoolean(), "a retired block comes back as written");
        assertEquals("Event bus", out.get("components").get("k").get("description").asText());
        assertFalse(out.has("environments"), "never written: " + out);
        assertFalse(out.get("asserts").get(0).has("warn"), "never written: " + out);
        assertFalse(out.get("collection").get("excludeRampUp").asBoolean(), "a written false survives");
        assertFalse(out.get("asserts").get(0).get("enabled").asBoolean(), "a written false survives");
        assertEquals("", out.get("asserts").get(0).get("unit").asText(), "a written empty string survives");
        assertFalse(out.get("asserts").get(0).has("description"), "a written null is absent: " + out);
        assertFalse(a.getUnknownKeys().containsKey("serverMetrics"));
    }

    @Test
    void aSharedIdIsOneWarningAndRefusesNothing() throws Exception {
        ServerMetricsMapper.Result r = map("{" + COMP + ",\"asserts\":[" + kpi("") + "," + kpi("\"across\":\"avg\"")
                + ",{\"id\":\" lag \",\"component\":\"k\",\"query\":\"q\",\"reduce\":{\"kind\":\"min\"},\"fail\":{\"lt\":1}}]}");
        assertEquals(3, r.config().kpis().size());
        assertTrue(r.config().problems().isEmpty(), "a shared id is not a config-level problem: " + r.config().problems());
        for (ServerKpi k : r.config().kpis()) {
            assertNull(KpiUsability.refusal(k), "a shared id refuses no assert: " + k);
        }
        assertEquals(1, r.findings().size(), r.findings().toString());
        AssertionValidator.Finding f = r.findings().get(0);
        assertEquals(AssertionValidator.Severity.WARNING, f.severity());
        assertEquals("lag", f.ruleId(), "filed under the id, trimmed as it is judged");
        assertTrue(f.message().startsWith("3 server asserts are judged under the id 'lag'"), f.message());
        assertTrue(f.message().endsWith("named after its component and its metric or query)"), f.message());
    }

    @Test
    void aNumberIsQuotedByItsValueNeverItsLiteral() {
        assertEquals("2", ServerMetricsMapper.numberText(2));
        assertEquals("2", ServerMetricsMapper.numberText(2.0));
        assertEquals("0", ServerMetricsMapper.numberText(-0.0));
        assertEquals("1.5", ServerMetricsMapper.numberText(1.5));
        assertEquals("1.0E-7", ServerMetricsMapper.numberText(1e-7));
        assertEquals("1.23456785E7", ServerMetricsMapper.numberText(12345678.5));
        assertEquals("999999999999999", ServerMetricsMapper.numberText(999999999999999L));
        assertEquals("1.0E15", ServerMetricsMapper.numberText(1000000000000000L));
    }
}
