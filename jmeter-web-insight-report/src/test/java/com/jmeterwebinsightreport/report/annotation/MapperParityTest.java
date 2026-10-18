package com.jmeterwebinsightreport.report.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionTarget;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PRODUCTION parser, against the same vectors the evaluators are pinned with.
 *
 * <p>{@code AssertionParityTest} proves the Java and JavaScript evaluators agree — but both
 * suites hand it a rule built by a test helper ({@code AssertionParityTest.rule()} on one side,
 * {@code ruleFrom()} in {@code asserts-parity.spec.ts} on the other). So the vectors pinned the
 * two <em>evaluators</em> while the two <em>parsers</em> were compared by nothing at all. A rule
 * read from a file by {@link AssertionRuleMapper} could differ from the same rule read by the
 * browser, or from the shape the vectors assume, and every suite would stay green — which is
 * precisely the bug class the vectors exist to make impossible.
 *
 * <p>This closes the Java half. Every vector goes through the real mapper, and the result has to
 * match the rule the parity suite builds by hand. {@code test-plans/js-unit/asserts-parity.test.js}
 * does the same on the browser side through {@code ruleFromJson}. Between them, both production
 * parsers are now driven by the file that defines what the engines must agree about.
 *
 * <p>The vector file lives in the core module's test resources and this test is in the report
 * module, because that is where the mapper is — core cannot depend on report. Rather than add a
 * test-jar dependency for one file, it is read by path from the reactor root, and
 * {@link #theVectorFileIsWhereThisTestThinksItIs} fails loudly if that ever stops being true. A
 * silently skipped parity suite would be worse than none.
 */
class MapperParityTest {

    /* The mapper the run reads an annotations file with — not a plain one: a number in a text
       field is read by its value there, and the vectors pin that (M7d X review follow-up). */
    private static final ObjectMapper MAPPER = AnnotationLoader.newObjectMapper();

    private static Path vectorFile() {
        // Surefire runs with the module directory as the working directory.
        return Paths.get("..", "jmeter-report-core", "src", "test", "resources",
                "assertion", "assert-parity-vectors.json").normalize();
    }

    @Test
    void theVectorFileIsWhereThisTestThinksItIs() {
        assertTrue(Files.isReadable(vectorFile()),
                "the parity vectors moved and this suite would have silently covered nothing: "
                        + vectorFile().toAbsolutePath());
    }

    @TestFactory
    Stream<DynamicTest> everyVectorSurvivesTheProductionParser() throws IOException {
        JsonNode root = MAPPER.readTree(Files.newInputStream(vectorFile()));
        List<DynamicTest> tests = new ArrayList<>();

        for (JsonNode c : root.get("cases")) {
            JsonNode declared = c.get("rule");
            String name = declared.path("id").asText(c.path("name").asText("case"));
            tests.add(DynamicTest.dynamicTest(name, () -> {
                AssertionRule expected = handBuilt(declared);
                AssertionRule actual = throughMapper(declared);

                assertEquals(expected.id(), actual.id(), "id");
                assertEquals(expected.idDeclared(), actual.idDeclared(), "idDeclared");
                assertEquals(expected.metricName(), actual.metricName(), "metric");
                assertEquals(expected.enabled(), actual.enabled(), "enabled");
                assertEquals(expected.target().kind(), actual.target().kind(), "target kind");
                assertEquals(expected.target().describe(), actual.target().describe(), "target");
                assertEquals(describe(expected.warn()), describe(actual.warn()), "warn band");
                assertEquals(describe(expected.fail()), describe(actual.fail()), "fail band");
                assertTrue(actual.declarationProblems().isEmpty(),
                        "a well-formed vector must not be refused by the mapper: "
                                + actual.declarationProblems());
                // The canonical name, read through the mapper (RB8): ruleFromJson is held to it.
                if (c.path("expect").hasNonNull("metric")) {
                    assertNotNull(actual.metric(), "the metric did not parse");
                    assertEquals(c.get("expect").get("metric").asText(),
                            actual.metric().canonicalName(), "canonical metric");
                }
            }));
        }
        assertTrue(tests.size() >= 50, "expected the full vector set, got " + tests.size());
        return tests.stream();
    }

    /**
     * How a TARGET is read, and so what an id-less rule is called (M7d X, review:C). The browser's
     * {@code ruleFromJson} derived ids for shapes this mapper refuses - {@code samplers: []},
     * {@code scope} beside {@code sampler}, a blank sampler - and refused {@code scope: "Overall"},
     * which this mapper reads. A server assert names its load rule by that id, so the two
     * disagreed about which rule it named. asserts-parity.test.js and asserts-parity.spec.ts run
     * the same cases through {@code ruleFromJson}.
     */
    @TestFactory
    Stream<DynamicTest> everyTargetCaseIsReadAsTheVectorSays() throws IOException {
        JsonNode root = MAPPER.readTree(Files.newInputStream(vectorFile()));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("targetCases")) {
            tests.add(DynamicTest.dynamicTest("target — " + c.get("name").asText(), () -> {
                int index = c.path("index").asInt(0);
                // The rule is the file's index-th entry: an INVALID target's rule is called by
                // its position when it has no id, so the position is part of the case.
                ObjectNode doc = MAPPER.createObjectNode();
                ArrayNode entries = doc.putArray("assertions");
                for (int i = 0; i < index; i++) {
                    entries.add(MAPPER.readTree("{\"id\":\"filler-" + i
                            + "\",\"target\":{\"sampler\":\"x\"},\"metric\":\"p95\",\"fail\":{\"gt\":1}}"));
                }
                entries.add(c.get("rule"));
                ReportAnnotations annotations = document(doc);
                AssertionRuleMapper.Result result = new AssertionRuleMapper().map(annotations.getAssertions());
                assertEquals(index + 1, result.rules().size(), "the mapper dropped a rule");
                AssertionRule rule = result.rules().get(index);
                JsonNode expect = c.get("expect");

                assertEquals(expect.get("kind").asText(), rule.target().kind().name().toLowerCase(java.util.Locale.ROOT),
                        "target kind");
                assertEquals(expect.get("id").asText(), rule.id(), "the id the rule is judged under");
                if (expect.has("problem")) {
                    assertEquals(AssertionTarget.Kind.INVALID, rule.target().kind());
                    assertEquals(expect.get("problem").asText(), rule.target().pattern(), "the target's problem");
                    boolean reported = result.findings().stream().anyMatch(f ->
                            f.ruleId().equals(rule.id()) && f.message().equals(expect.get("problem").asText()));
                    assertTrue(reported, "the refusal reached no finding: " + result.findings());
                }
            }));
        }
        assertTrue(tests.size() >= 10, "the target cases are gone: " + tests.size());
        return tests.stream();
    }

    /**
     * The whole INVALID_RULE sentence of a refusal, character for character (M7d X review
     * follow-up). It began with the refusals that print a number: {@code Usability.addIfUnreachable}
     * writes the band as {@code Predicate.describe} does, and the browser wrote it with the table's
     * display formatter ({@code < -1,234 ms} for -1234.5, {@code < -0.2 /s} for -0.25), so the board
     * and the CI/CD JSON printed different limits. It now holds every unreadable band too — a bare
     * {@code 1e400}, two operators, {@code outside}, an operator every JS object inherits — whose
     * sentence the browser wrote without {@code Usability.unreadableBand}'s ending.
     * asserts-parity.test.js and asserts-parity.spec.ts run the same cases through
     * {@code ruleFromJson} and {@code whyUnusable}.
     */
    @TestFactory
    Stream<DynamicTest> everyDetailCaseIsRefusedInTheVectorsWords() throws IOException {
        JsonNode root = MAPPER.readTree(Files.newInputStream(vectorFile()));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("detailCases")) {
            tests.add(DynamicTest.dynamicTest("detail — " + c.get("name").asText(), () -> {
                AssertionRule rule = throughMapper(c.get("rule"));
                com.jmeterwebinsightreport.core.assertion.Usability refusal =
                        com.jmeterwebinsightreport.core.assertion.Usability.refusal(rule);
                assertNotNull(refusal, "the rule was not refused");
                assertEquals(c.get("expect").get("detail").asText(), refusal.reasonText(), "the INVALID_RULE detail");
            }));
        }
        assertTrue(tests.size() >= 18, "the detail cases are gone: " + tests.size());
        return tests.stream();
    }

    /**
     * The legacy {@code loadValidity} block, migrated (M5 exploratory F1). The page read a file's
     * {@code assertions[]} and nothing else, so opening E25's own annotations file showed one rule
     * fewer than the report and a Download dropped the load check. The page now migrates the block
     * as {@link LoadValidityMigration} does; asserts-parity.test.js and asserts-parity.spec.ts run
     * these same documents through its {@code migrateLoadValidity}. Driven as the run drives it:
     * AnnotationLoader's mapper, then {@code migrateLegacyBlocks}.
     */
    @TestFactory
    Stream<DynamicTest> everyMigrationCaseEndsAsTheVectorSays() throws IOException {
        JsonNode root = MAPPER.readTree(Files.newInputStream(vectorFile()));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("migrationCases")) {
            tests.add(DynamicTest.dynamicTest("migration — " + c.get("name").asText(), () -> {
                ReportAnnotations annotations = document(c.get("document"));
                annotations.migrateLegacyBlocks();
                assertNull(annotations.getLoadValidity(), "the block must be consumed");
                // As documentOf writes it: a non-finite band is Jackson's text ("NaN"), which is
                // what the page is shipped, carries and downloads (L3).
                JsonNode written = MAPPER.readTree(MAPPER.writeValueAsString(annotations.getAssertions()));
                assertEquals(byValue(c.get("expect").get("assertions")), byValue(written),
                        "the assertions the migration ends with");
                // The migrated rule's refusal, through the mapper the run judges with (L3): a
                // non-finite target or tolerance is refused in a sentence naming the field, and
                // the finding the run reports names it too.
                if (c.get("expect").has("detail")) {
                    AssertionRuleMapper.Result mapped = new AssertionRuleMapper().map(annotations.getAssertions());
                    AssertionRule migrated = mapped.rules().get(mapped.rules().size() - 1);
                    com.jmeterwebinsightreport.core.assertion.Usability refusal =
                            com.jmeterwebinsightreport.core.assertion.Usability.refusal(migrated);
                    assertNotNull(refusal, "the migrated rule was judged");
                    assertEquals(c.get("expect").get("detail").asText(), refusal.reasonText(),
                            "the migrated rule's INVALID_RULE detail");
                    boolean named = mapped.findings().stream().anyMatch(f -> f.ruleId().equals(migrated.id())
                            && f.message().startsWith("loadValidity."));
                    assertTrue(named, "no finding names the field: " + mapped.findings());
                }
            }));
        }
        assertTrue(tests.size() >= 18, "the migration cases are gone: " + tests.size());
        return tests.stream();
    }

    /**
     * A band written as TEXT, read as the run reads it (L2, CN25): ServerMetricsMapper.asNumber,
     * String.trim and Double.parseDouble's grammar, NaN and the infinities refused. The page's
     * bandNumber read it with trim() and Number(), so "0x10" was a band of 16 there and a refusal
     * here; asserts-parity.test.js and asserts-parity.spec.ts run the same cases through
     * {@code ruleFromJson}.
     */
    @TestFactory
    Stream<DynamicTest> everyBandTextIsReadAsTheVectorSays() throws IOException {
        JsonNode root = MAPPER.readTree(Files.newInputStream(vectorFile()));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("bandTextCases")) {
            tests.add(DynamicTest.dynamicTest("band text — " + c.get("name").asText(), () -> {
                String text = c.get("text").asText();
                ObjectNode rule = MAPPER.createObjectNode();
                rule.put("id", "band-text");
                rule.putObject("target").put("sampler", "GET /api/products");
                rule.put("metric", "mean");
                rule.putObject("fail").put("gt", text);
                AssertionRule mapped = throughMapper(rule);
                JsonNode expect = c.get("expect");
                if (expect.has("value")) {
                    assertTrue(mapped.declarationProblems().isEmpty(),
                            "Java reads " + text + ": " + mapped.declarationProblems());
                    assertNotNull(mapped.fail(), "no fail band from " + text);
                    assertEquals(Predicate.Op.GT, mapped.fail().op());
                    assertEquals(expect.get("value").asDouble(), mapped.fail().value(), 0.0, "the band read from " + text);
                } else {
                    assertTrue(expect.get("refused").asBoolean());
                    assertNull(mapped.fail(), "a band was read from " + text);
                    com.jmeterwebinsightreport.core.assertion.Usability refusal =
                            com.jmeterwebinsightreport.core.assertion.Usability.refusal(mapped);
                    assertNotNull(refusal, "the rule was judged over " + text);
                    assertEquals("fail gt must be a number, not the text \"" + text + "\" — so this rule declares a "
                            + "limit it cannot apply, and would report PASS without ever testing fail",
                            refusal.reasonText());
                }
            }));
        }
        assertTrue(tests.size() >= 20, "the band-text cases are gone: " + tests.size());
        return tests.stream();
    }

    /**
     * A JSON value compared by meaning: keys sorted, a null field dropped (Jackson binds null and
     * absent alike, and writes every unset field as null), every number by its double value — the
     * migration's band is a {@code double}, written {@code 50.0} where the vector says {@code 50}.
     */
    private static String byValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isNumber()) {
            return Double.toString(node.asDouble());
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(n -> items.add(byValue(n)));
            return items.toString();
        }
        if (node.isObject()) {
            java.util.TreeMap<String, String> fields = new java.util.TreeMap<>();
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    fields.put(e.getKey(), byValue(e.getValue()));
                }
            });
            return fields.toString();
        }
        return node.toString();
    }

    /**
     * The node runner has no page and so no {@code assertMetrics} vocabulary; the vector file hands
     * it the unit of each metric a detail case uses. This keeps that copy honest: each must be the
     * unit Java prints, in either scope the metric is valid in.
     */
    @Test
    void theVectorUnitsAreTheUnitsJavaPrints() throws IOException {
        JsonNode units = MAPPER.readTree(Files.newInputStream(vectorFile())).path("units");
        assertTrue(units.size() >= 3, "the units are gone");
        units.fields().forEachRemaining(e -> {
            AssertionRule rule = AssertionRule.builder(AssertionTarget.overall(), e.getKey()).build();
            assertNotNull(rule.metric(), "not a metric: " + e.getKey());
            assertEquals(rule.metric().unit().suffix(), e.getValue().asText(), "the unit of " + e.getKey());
        });
    }

    /**
     * The vocabulary the node runner is handed in place of the page's {@code assertMetrics}: each
     * scope's list must be {@link com.jmeterwebinsightreport.core.assertion.MetricRef#vocabulary}, in
     * its order, because an unknown metric's "did you mean" is the first nearest name in it (RB14),
     * and every name in it must carry a unit above.
     */
    @Test
    void theVectorVocabularyIsMetricRefs() throws IOException {
        JsonNode root = MAPPER.readTree(Files.newInputStream(vectorFile()));
        JsonNode vocabulary = root.path("vocabulary");
        for (boolean overall : new boolean[] {false, true}) {
            List<String> names = new ArrayList<>();
            vocabulary.path(overall ? "overall" : "sampler").forEach(n -> names.add(n.asText()));
            assertEquals(com.jmeterwebinsightreport.core.assertion.MetricRef.vocabulary(overall), names,
                    "the " + (overall ? "overall" : "sampler") + " vocabulary");
            names.forEach(n -> assertTrue(root.path("units").has(n), "no unit for " + n));
        }
    }

    /** The mapper, driven exactly as {@code GateEvaluation} drives it. */
    private static AssertionRule throughMapper(JsonNode declared) throws IOException {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.putArray("assertions").add(declared);
        ReportAnnotations annotations = document(doc);
        AssertionRuleMapper.Result result =
                new AssertionRuleMapper().map(annotations.getAssertions());
        assertEquals(1, result.rules().size(), "the mapper dropped the rule");
        return result.rules().get(0);
    }

    /**
     * A vector document bound as the run binds a file — from the TREE, as {@code ServerParityTest}
     * binds its own, never from {@code toString()}. A JSON number past {@code Double.MAX_VALUE}
     * ({@code 1e400}) overflows to Infinity inside the parser, so the run's streaming parse hands the
     * mapper a Number; a text round trip writes that {@code DoubleNode(Infinity)} back as the quoted
     * {@code "Infinity"}, and the mapper's text arm then refuses it as text — the right verdict for
     * the wrong reason, and the wrong sentence. The overflow detailCases pin this: reverted to text,
     * they read {@code the text "Infinity"} and go red. The target and migration sections bind the
     * same way so that a vector added there reads as the run reads it: the migration case with a
     * {@code loadValidity} target of {@code 1e400} is Infinity here only because the tree is bound,
     * and refused naming {@code loadValidity.target} (L3).
     */
    private static ReportAnnotations document(JsonNode doc) throws IOException {
        return MAPPER.treeToValue(doc, ReportAnnotations.class);
    }

    /** The shape {@code AssertionParityTest} builds by hand, reproduced here to compare against. */
    private static AssertionRule handBuilt(JsonNode r) {
        JsonNode t = r.get("target");
        AssertionTarget target;
        if (t.hasNonNull("scope")) {
            target = AssertionTarget.overall();
        } else if (t.hasNonNull("sampler")) {
            target = AssertionTarget.sampler(t.get("sampler").asText());
        } else if (t.hasNonNull("samplers")) {
            List<String> names = new ArrayList<>();
            for (JsonNode n : t.get("samplers")) names.add(n.asText());
            target = AssertionTarget.samplers(names);
        } else {
            List<String> excludes = new ArrayList<>();
            if (t.hasNonNull("exclude")) {
                for (JsonNode n : t.get("exclude")) excludes.add(n.asText());
            }
            target = AssertionTarget.match(t.get("match").asText(), excludes);
        }

        AssertionRule.Builder b = AssertionRule.builder(target, r.get("metric").asText())
                .id(r.path("id").asText(null))
                .enabled(r.path("enabled").asBoolean(true));
        if (r.hasNonNull("warn")) b.warn(predicate(r.get("warn")));
        if (r.hasNonNull("fail")) b.fail(predicate(r.get("fail")));
        return b.build();
    }

    private static Predicate predicate(JsonNode band) {
        String key = band.fieldNames().next();
        return Predicate.of(Predicate.Op.fromKey(key), band.get(key).asDouble());
    }

    private static String describe(Predicate p) {
        return p == null ? "(none)" : p.describe("");
    }
}
