package com.jmeterwebinsightreport.report.annotation;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.Across;
import com.jmeterwebinsightreport.core.servermetrics.Durations;
import com.jmeterwebinsightreport.core.servermetrics.KpiUsability;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped JSON Schema ({@code static/schema/report-annotations.schema.json}) against what the
 * loader actually reads.
 *
 * <p>The schema is what an editor completes and squiggles from, so a key it lacks is a key nobody
 * is offered, and a key it offers that the mapper never reads is worse: a file written from the
 * completion list that silently judges nothing. Both drifted once: the schema had no
 * {@code serverMetrics} at all, and {@code assertion.target} was a {@code $ref} to a definition
 * that did not exist, while {@code samplers}, {@code match}, {@code exclude} and {@code scope} sat
 * one level too high, where the mapper never looks.
 *
 * <p>The source of truth for each object's keys is the Jackson bean the loader binds it to
 * ({@link ServerMetricsJson} and its nested classes, {@link ReportAnnotations} and its), read
 * through Jackson's own introspection rather than a list typed here. Two shapes have no bean: a
 * server assert's {@code reduce} and its {@code over} are maps {@code ServerMetricsMapper.readReducer}
 * picks keys out of, so their keys are an explicit list below and must move with that method. A
 * band's keys are {@link Predicate.Op}'s.
 *
 * <p>No validator dependency: the shaded JAR's allowlist forbids casual ones, and what this needs
 * is the key structure, which a plain walk over the schema answers.
 */
class AnnotationsSchemaTest {

    private static final String RESOURCE = "static/schema/report-annotations.schema.json";

    private static final ObjectMapper MAPPER = AnnotationLoader.newObjectMapper();

    /**
     * Bound by {@link ServerMetricsJson} and deliberately NOT offered: {@code sources} is retired
     * (C8) and refused whatever it holds, and is bound only so {@code publishable()} can mask a
     * credential left in it.
     */
    private static final Set<String> BOUND_BUT_RETIRED_BLOCK_KEYS = Set.of("sources");

    /**
     * Bound by {@link ReportAnnotations} and deliberately NOT offered: {@code loadValidity} is the
     * legacy block {@code LoadValidityMigration} folds into {@code assertions} at load.
     */
    private static final Set<String> LEGACY_TOP_LEVEL_KEYS = Set.of("loadValidity");

    /** What {@code readReducer} reads from {@code reduce}, and from {@code reduce.over}. */
    private static final Set<String> REDUCE_KEYS = Set.of("kind", "over");
    private static final Set<String> OVER_KEYS = Set.of("sustainedFor", "breachBudget");

    private static JsonNode schema() throws IOException {
        // From the classpath, as the JAR ships it - not from src/main by path.
        try (InputStream in = AnnotationsSchemaTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " is not on the classpath");
            return new ObjectMapper().readTree(in);
        }
    }

    // ---- (a) every $ref resolves --------------------------------------------------------------

    @Test
    void everyRefResolvesInsideTheSchema() throws IOException {
        JsonNode root = schema();
        List<String> refs = new ArrayList<>();
        collectRefs(root, refs);
        assertFalse(refs.isEmpty(), "the schema has no $ref at all, so this test checked nothing");
        for (String ref : refs) {
            assertTrue(ref.startsWith("#/"), "a $ref outside this file cannot be checked here: " + ref);
            assertFalse(root.at(ref.substring(1)).isMissingNode(), "dangling $ref: " + ref);
        }
    }

    private static void collectRefs(JsonNode node, List<String> into) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                if ("$ref".equals(f.getKey()) && f.getValue().isTextual()) {
                    into.add(f.getValue().asText());
                } else {
                    collectRefs(f.getValue(), into);
                }
            }
        } else if (node.isArray()) {
            node.forEach(n -> collectRefs(n, into));
        }
    }

    // ---- (b) the component type vocabulary ------------------------------------------------------

    @Test
    void componentTypesAreTheCatalogueInIndexOrderThenCustom() throws IOException {
        List<String> expected = new ArrayList<>(Catalogue.builtIn().types().keySet());
        expected.add(Catalogue.CUSTOM);
        List<String> declared = new ArrayList<>();
        definition("serverComponent").path("properties").path("type").path("enum")
                .forEach(n -> declared.add(n.asText()));
        assertEquals(expected, declared, "serverComponent.type's enum is not the catalogue's vocabulary");
        // The sentence the mapper refuses an unknown type with lists the same words, in this order.
        assertEquals(Catalogue.builtIn().vocabulary(), String.join(", ", declared));
    }

    @Test
    void acrossIsTheMappersVocabulary() throws IOException {
        List<String> declared = new ArrayList<>();
        definition("serverAssert").path("properties").path("across").path("enum")
                .forEach(n -> declared.add(n.asText()));
        assertEquals(Across.vocabulary(), String.join(", ", declared));
    }

    // ---- (c) every key the loader reads, and only those ----------------------------------------

    @Test
    void theServerMetricsBlockDeclaresExactlyTheKeysTheLoaderBinds() throws IOException {
        Set<String> expected = beanKeys(ServerMetricsJson.class);
        assertTrue(expected.containsAll(BOUND_BUT_RETIRED_BLOCK_KEYS),
                "an exemption names a key the bean no longer binds: " + BOUND_BUT_RETIRED_BLOCK_KEYS);
        expected.removeAll(BOUND_BUT_RETIRED_BLOCK_KEYS);
        assertEquals(expected, schemaKeys("serverMetrics"));
    }

    @Test
    void aServerAssertDeclaresExactlyTheKeysTheLoaderBinds() throws IOException {
        assertEquals(beanKeys(ServerMetricsJson.KpiJson.class), schemaKeys("serverAssert"));
    }

    @Test
    void aComponentAnEnvironmentAndTheCollectionDeclareExactlyTheirBeansKeys() throws IOException {
        assertEquals(beanKeys(ServerMetricsJson.ComponentJson.class), schemaKeys("serverComponent"));
        assertEquals(beanKeys(ServerMetricsJson.EnvironmentJson.class), schemaKeys("serverEnvironment"));
        assertEquals(beanKeys(ServerMetricsJson.CollectionJson.class), schemaKeys("serverCollection"));
    }

    @Test
    void reduceAndItsToleranceDeclareTheKeysReadReducerReads() throws IOException {
        assertEquals(new TreeSet<>(REDUCE_KEYS), schemaKeys("serverReduce"));
        assertEquals(new TreeSet<>(OVER_KEYS), schemaKeys("serverTolerance"));
    }

    @Test
    void aBandDeclaresExactlyThePredicateOperators() throws IOException {
        Set<String> ops = new TreeSet<>();
        for (Predicate.Op op : Predicate.Op.values()) {
            ops.add(op.key());
        }
        assertEquals(ops, schemaKeys("predicate"));
        // A server band is a client band: one definition, so the two cannot drift apart.
        JsonNode server = definition("serverAssert").path("properties");
        assertEquals("#/definitions/predicate", server.path("warn").path("$ref").asText());
        assertEquals("#/definitions/predicate", server.path("fail").path("$ref").asText());
    }

    @Test
    void theClientShapesDeclareExactlyTheirBeansKeys() throws IOException {
        Set<String> top = beanKeys(ReportAnnotations.class);
        assertTrue(top.containsAll(LEGACY_TOP_LEVEL_KEYS),
                "an exemption names a key the bean no longer binds: " + LEGACY_TOP_LEVEL_KEYS);
        top.removeAll(LEGACY_TOP_LEVEL_KEYS);
        // "$schema" is the editor's own key: the schema declares it, the loader keeps it as unknown.
        top.add("$schema");
        assertEquals(top, keysOf(schema().path("properties")));

        assertEquals(beanKeys(ReportAnnotations.AssertionConfig.class), schemaKeys("assertion"));
        assertEquals(beanKeys(ReportAnnotations.TargetConfig.class), schemaKeys("target"));
        assertEquals(beanKeys(ReportAnnotations.TimelineMarker.class), schemaKeys("timelineMarker"));
        assertEquals(beanKeys(ReportAnnotations.ComparisonThresholdConfig.class), schemaKeys("comparisonThresholds"));
    }

    @Test
    void noRetiredKeyIsOffered() throws Exception {
        assertNoneDeclared("serverMetrics", ServerMetricsMapper.RETIRED_BLOCK_KEYS.keySet());
        assertNoneDeclared("serverMetrics", BOUND_BUT_RETIRED_BLOCK_KEYS);
        assertNoneDeclared("serverAssert", retiredKeys(ServerMetricsMapper.class, "RETIRED_KPI_KEYS"));
        assertNoneDeclared("serverComponent", Set.of("failsBuild"));
        assertNoneDeclared("assertion", retiredKeys(AssertionRuleMapper.class, "RETIRED_RULE_KEYS"));
        Set<String> top = keysOf(schema().path("properties"));
        for (String legacy : LEGACY_TOP_LEVEL_KEYS) {
            assertFalse(top.contains(legacy), "the schema offers the legacy key " + legacy);
        }
    }

    // ---- what a requirement or a pattern decides, against the mapper --------------------------

    /**
     * A {@code reduce} written on an assert replaces the catalogue's reducer whole
     * ({@code ServerMetricsMapper.readKpi} never merges the two), so one that names no
     * {@code kind} - {@code {}}, or a tolerance alone, which reads like "add a tolerance to the
     * catalogue's reducer" - is NOT_JUDGED / INVALID_RULE. The schema requires {@code kind} so the
     * editor says so before the run does.
     */
    @Test
    void aWrittenReduceMustNameItsKind() throws Exception {
        assertNull(KpiUsability.refusal(catalogueAssertWithReduce(null)),
                "the control: the same assert with the catalogue's reducer is judged");
        for (String reduce : new String[] {"{}", "{\"over\":{\"sustainedFor\":\"30s\"}}"}) {
            assertNotNull(KpiUsability.refusal(catalogueAssertWithReduce(reduce)),
                    "the mapper judged an assert whose reduce is " + reduce);
        }
        List<String> required = new ArrayList<>();
        definition("serverReduce").path("required").forEach(n -> required.add(n.asText()));
        assertEquals(List.of("kind"), required, "serverReduce does not require kind");
    }

    /**
     * A {@code query} assert has no catalogue metric to take a reducer from, so one that writes no
     * {@code reduce} is NOT_JUDGED / INVALID_RULE ("reduce.kind is missing"). The schema makes
     * {@code query} depend on {@code reduce}, so the editor says so before the run does.
     */
    @Test
    void aQueryAssertMustWriteItsReduce() throws Exception {
        String document = "{\"serverMetrics\":{\"components\":{\"app\":{\"type\":\"custom\"}},\"asserts\":["
                + "{\"component\":\"app\",\"query\":\"up\",\"fail\":{\"lt\":1}}]}}";
        ServerMetricsMapper.Result r = new ServerMetricsMapper().map(
                new ObjectMapper().readValue(document, ReportAnnotations.class).getServerMetrics());
        assertEquals(1, r.config().kpis().size(), r.findings().toString());
        assertNotNull(KpiUsability.refusal(r.config().kpis().get(0)),
                "the control: the mapper judged a query assert that writes no reduce");
        List<String> needs = new ArrayList<>();
        definition("serverAssert").path("dependencies").path("query").forEach(n -> needs.add(n.asText()));
        assertEquals(List.of("reduce"), needs, "serverAssert does not make query depend on reduce");
    }

    private static ServerKpi catalogueAssertWithReduce(String reduce) throws IOException {
        String document = "{\"serverMetrics\":{\"components\":{\"db\":{\"type\":\"postgres\"}},\"asserts\":["
                + "{\"component\":\"db\",\"metric\":\"connections.used.pct\","
                + (reduce == null ? "" : "\"reduce\":" + reduce + ",") + "\"fail\":{\"gt\":90}}]}}";
        ServerMetricsMapper.Result r = new ServerMetricsMapper().map(
                new ObjectMapper().readValue(document, ReportAnnotations.class).getServerMetrics());
        assertEquals(1, r.config().kpis().size(), r.findings().toString());
        return r.config().kpis().get(0);
    }

    /**
     * A pattern that accepts what the mapper refuses offers a file that cannot run, with no
     * squiggle on it; one that refuses what the mapper accepts squiggles a working file. So each
     * is held to the mapper's own parser over the edges: {@code reduce.kind} to
     * {@link Reducer#parse} (a percentile is 0 &lt; N &lt; 100, so p0 and p100 are refused and
     * p0.5 is not), {@code sustainedFor} to {@link Durations#parseMillis} and the mapper's
     * "at least 1ms" ("0s" is a duration, and refused). Lowercase only: the parser forgives case,
     * the schema offers the spelling a save writes. A sub-millisecond length that rounds to 0
     * ("0.0004s") is beyond a pattern and is left to the mapper's sentence.
     */
    @Test
    void theSchemasPatternsAcceptExactlyWhatTheMapperAccepts() throws IOException {
        JsonNode kind = definition("serverReduce").path("properties").path("kind");
        for (String k : new String[] {"max", "min", "avg", "last", "slope", "p95", "p50", "p1", "p99.9",
                "p99.99", "p0.5", "p099", "p0", "p00", "p0.0", "p100", "p100.0", "p150", "p", "p-5", "p1e1",
                "p95.", "p.5", "p 95", "median", "mean"}) {
            assertEquals(Reducer.parse(k) != null, schemaAcceptsKind(kind, k), "reduce.kind \"" + k + "\"");
        }
        Pattern duration = Pattern.compile(
                definition("serverTolerance").path("properties").path("sustainedFor").path("pattern").asText());
        for (String d : new String[] {"30s", "500ms", "2m", "1h", "1.5m", "10s", "0.001s", " 30s ", "0s", "0ms",
                "0.0s", "00s", "30", "30 s", "30S", "-1s", ".5s", "1.s", "1d"}) {
            Long millis = Durations.parseMillis(d);
            assertEquals(millis != null && millis > 0, duration.matcher(d).find(), "sustainedFor \"" + d + "\"");
        }
    }

    /** {@code kind} is an anyOf of an enum and a pattern. */
    private static boolean schemaAcceptsKind(JsonNode kind, String k) {
        assertTrue(kind.path("anyOf").size() > 0, "reduce.kind is no longer an anyOf");
        for (JsonNode option : kind.path("anyOf")) {
            for (JsonNode e : option.path("enum")) {
                if (e.asText().equals(k)) return true;
            }
            if (option.has("pattern") && Pattern.compile(option.get("pattern").asText()).matcher(k).find()) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoneDeclared(String definition, Set<String> retired) throws IOException {
        Set<String> declared = schemaKeys(definition);
        for (String key : retired) {
            assertFalse(declared.contains(key), definition + " offers the retired key " + key);
        }
    }

    /** A mapper's private map of retired keys, by reflection: the test must not widen its visibility. */
    @SuppressWarnings("unchecked")
    private static Set<String> retiredKeys(Class<?> owner, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        Set<String> keys = ((Map<String, String>) f.get(null)).keySet();
        assertFalse(keys.isEmpty(), owner.getSimpleName() + "." + field + " is empty");
        return keys;
    }

    /** The JSON keys Jackson binds on this class for reading, any-setter excluded. */
    private static Set<String> beanKeys(Class<?> type) {
        BeanDescription description = MAPPER.getDeserializationConfig().introspect(MAPPER.constructType(type));
        Set<String> keys = new TreeSet<>();
        for (BeanPropertyDefinition p : description.findProperties()) {
            if (p.couldDeserialize()) {
                keys.add(p.getName());
            }
        }
        assertFalse(keys.isEmpty(), "Jackson found no properties on " + type.getName());
        return keys;
    }

    private static JsonNode definition(String name) throws IOException {
        JsonNode d = schema().path("definitions").path(name);
        assertFalse(d.isMissingNode(), "the schema has no definitions." + name);
        return d;
    }

    private static Set<String> schemaKeys(String definitionName) throws IOException {
        return keysOf(definition(definitionName).path("properties"));
    }

    private static Set<String> keysOf(JsonNode properties) {
        Set<String> keys = new TreeSet<>();
        properties.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    // ---- (d) every committed annotations file uses only declared keys --------------------------

    /**
     * Keys a committed file carries on purpose that the schema does not declare, by the file's
     * path from the repository root, each as the walk names it. A key beginning with {@code _} is
     * a comment by this repository's convention, and is not reported where its object takes any
     * key. In a closed object it is: a {@code "_why"} inside a band makes it a two-key band, which
     * the mapper refuses and an editor squiggles.
     */
    private static final Map<String, Set<String>> DELIBERATE = new LinkedHashMap<>();
    static {
        // E25: the legacy block is folded into assertions at load, which is what the fixture
        // shows; "owner" is the unknown key the round-trip guarantee must keep through a save.
        DELIBERATE.put("test-plans/annotations/asserts.json", Set.of("loadValidity", "assertions[11].owner"));
        // G3, the config-error golden: every shape of rule that cannot decide anything, retired
        // keys and the retired "outside" operator included.
        DELIBERATE.put("test-plans/goldens/gate-broken.json",
                Set.of("assertions[2].observe", "assertions[3].onMissing", "assertions[4].fail.outside"));
    }

    private static Path repo() {
        // Surefire runs with the module directory as the working directory.
        return Paths.get("..").toAbsolutePath().normalize();
    }

    /**
     * The annotations files the repository commits: test-plans/annotations/*.json, E26's and E27's,
     * the goldens' gate-*.json (goldens/server-metrics.json beside them is a collected payload, not
     * annotations) and the published example's.
     */
    private static List<Path> committedAnnotationsFiles() throws IOException {
        Path root = repo();
        List<Path> files = new ArrayList<>();
        files.addAll(glob(root.resolve("test-plans/annotations"), "*.json"));
        files.add(root.resolve("test-plans/server-metrics/e26-server.json"));
        files.add(root.resolve("test-plans/server-catalogue/e27-server.json"));
        files.addAll(glob(root.resolve("test-plans/goldens"), "gate-*.json"));
        files.add(root.resolve("reportExample/report-annotations.json"));
        return files;
    }

    private static List<Path> glob(Path dir, String pattern) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, pattern)) {
            s.forEach(out::add);
        }
        out.sort(null);
        // Not dir.resolve(pattern): '*' is not a legal path character on Windows.
        assertFalse(out.isEmpty(), "nothing matches " + pattern + " in " + dir + ", so this test checked nothing");
        return out;
    }

    @Test
    void theFilesThisTestWalksAreWhereItThinksTheyAre() throws IOException {
        List<Path> files = committedAnnotationsFiles();
        for (Path f : files) {
            assertTrue(Files.isReadable(f), "missing: " + f);
        }
        for (String path : DELIBERATE.keySet()) {
            assertTrue(files.contains(repo().resolve(path)), "an exemption names a file this test does not walk: " + path);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyCommittedAnnotationsFileUsesOnlyDeclaredKeys() throws IOException {
        JsonNode schema = schema();
        List<DynamicTest> tests = new ArrayList<>();
        for (Path file : committedAnnotationsFiles()) {
            String name = repo().relativize(file).toString().replace('\\', '/');
            tests.add(DynamicTest.dynamicTest(name, () -> {
                JsonNode doc = new ObjectMapper().readTree(Files.readAllBytes(file));
                Set<String> undeclared = new TreeSet<>();
                walk(doc, schema, schema, "", undeclared);
                assertEquals(new TreeSet<>(DELIBERATE.getOrDefault(name, Set.of())), undeclared,
                        name + " carries keys the schema does not declare (or no longer carries a deliberate one)");
            }));
        }
        return tests.stream();
    }

    /**
     * Every key of every object in {@code value} that {@code schema} does not declare, as a path.
     * Structural only: a key is declared by {@code properties}, or by an object-valued
     * {@code additionalProperties} (a map such as {@code components}); types and values are not
     * checked. An undeclared key is reported and not descended into. A {@code _} comment is
     * skipped only where the object is open ({@code additionalProperties} absent or true): in a
     * closed one it is a key the mapper reads, and in a map it is an entry, walked as one.
     */
    private static void walk(JsonNode value, JsonNode schema, JsonNode root, String path, Set<String> undeclared) {
        JsonNode s = resolve(schema, root);
        if (value.isObject()) {
            JsonNode properties = s.path("properties");
            JsonNode additional = resolve(s.path("additionalProperties"), root);
            boolean open = additional.isMissingNode() || (additional.isBoolean() && additional.booleanValue());
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                String key = f.getKey();
                String at = path.isEmpty() ? key : path + "." + key;
                if (properties.has(key)) {
                    walk(f.getValue(), properties.get(key), root, at, undeclared);
                } else if (additional.isObject()) {
                    walk(f.getValue(), additional, root, at, undeclared);
                } else if (!(open && key.startsWith("_"))) {
                    undeclared.add(at);
                }
            }
        } else if (value.isArray() && s.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                walk(value.get(i), s.get("items"), root, path + "[" + i + "]", undeclared);
            }
        }
    }

    private static JsonNode resolve(JsonNode schema, JsonNode root) {
        JsonNode s = schema;
        while (s.has("$ref")) {
            s = root.at(s.get("$ref").asText().substring(1));
        }
        return s;
    }

    @Test
    void theWalkReportsAnUndeclaredKeyAtEveryLevelItChecks() throws IOException {
        // The walk is this test's own code, so it is shown to see what it is meant to see.
        JsonNode schema = schema();
        // A "_" comment passes where its object is open (the top level, a rule, an assert) and is
        // reported inside a closed one (a band, a tolerance), where the mapper reads it.
        JsonNode doc = new ObjectMapper().readTree("{\"nope\":1,\"_note\":1,"
                + "\"assertions\":[{\"_why\":\"x\",\"target\":{\"sampler\":\"a\",\"sampelr\":\"b\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":1,\"over\":2,\"_why\":\"x\"}}],"
                + "\"serverMetrics\":{\"gates\":{},\"components\":{\"db\":{\"type\":\"postgres\",\"failsBuild\":true}},"
                + "\"collection\":{\"floor\":1},\"environments\":{\"T\":{\"vars\":{\"a\":\"b\"},\"x\":1}},"
                + "\"asserts\":[{\"_why\":\"x\",\"component\":\"db\",\"metric\":\"up\",\"scope\":\"x\","
                + "\"reduce\":{\"kind\":\"max\",\"over\":{\"sustainedFor\":\"30s\",\"for\":\"1s\",\"_c\":1}}}]}}");
        Set<String> undeclared = new TreeSet<>();
        walk(doc, schema, schema, "", undeclared);
        assertEquals(new TreeSet<>(Arrays.asList(
                "nope",
                "assertions[0].target.sampelr",
                "assertions[0].fail.over",
                "assertions[0].fail._why",
                "serverMetrics.gates",
                "serverMetrics.components.db.failsBuild",
                "serverMetrics.collection.floor",
                "serverMetrics.environments.T.x",
                "serverMetrics.asserts[0].scope",
                "serverMetrics.asserts[0].reduce.over.for",
                "serverMetrics.asserts[0].reduce.over._c")), undeclared);
    }
}
