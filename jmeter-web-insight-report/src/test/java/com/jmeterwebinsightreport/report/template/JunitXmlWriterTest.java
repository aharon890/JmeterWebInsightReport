package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.assertion.SamplerVerdicts;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JunitXmlWriterTest {

    private JunitXmlWriter writer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writer = new JunitXmlWriter();
    }

    // --- Helper methods ---

    private static SamplerStatistics createSampler(String name, double mean, double p95,
                                                    double p99, double errorRate) {
        SamplerStatistics stat = new SamplerStatistics();
        stat.setSamplerName(name);
        stat.setMeanResponseTime(mean);
        stat.setPercentile95(p95);
        stat.setPercentile99(p99);
        stat.setErrorRate(errorRate);
        stat.setSampleCount(100);
        return stat;
    }

    private ReportData createReportData(SamplerStatistics... stats) {
        ReportData data = new ReportData();
        TestMetadata meta = new TestMetadata();
        meta.setTestName("Test Plan");
        meta.setDurationMillis(60000);
        data.setMetadata(meta);
        data.setSamplerStatistics(Arrays.asList(stats));
        return data;
    }

    private String writeAndRead(ReportData data, SamplerVerdicts assertResults,
                                String testName) throws IOException {
        File outputFile = tempDir.resolve("junit-report.xml").toFile();
        writer.write(data, assertResults, testName, outputFile);
        return new String(Files.readAllBytes(outputFile.toPath()), StandardCharsets.UTF_8);
    }

    // --- Tests ---

    @Test
    void write_producesValidXml() throws IOException {
        ReportData data = createReportData(createSampler("GET /api/users", 150, 300, 500, 1.0));

        String xml = writeAndRead(data, null, "My Test");

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<testsuites"));
        assertTrue(xml.contains("<testsuite"));
        assertTrue(xml.contains("</testsuite>"));
        assertTrue(xml.contains("</testsuites>"));
    }

    @Test
    void write_includesAllSamplers() throws IOException {
        ReportData data = createReportData(
                createSampler("GET /api/users", 100, 200, 300, 0),
                createSampler("POST /api/login", 200, 400, 600, 0),
                createSampler("DELETE /api/session", 50, 100, 150, 0));

        String xml = writeAndRead(data, null, "Multi Sampler Test");

        assertTrue(xml.contains("name=\"GET /api/users\""));
        assertTrue(xml.contains("name=\"POST /api/login\""));
        assertTrue(xml.contains("name=\"DELETE /api/session\""));
    }

    @Test
    void write_noSlaFailures_noFailureElements() throws IOException {
        ReportData data = createReportData(createSampler("GET /api/users", 100, 200, 300, 0));

        SamplerVerdicts.Builder assertResults = SamplerVerdicts.builder();
        Map<String, AssertionStatus> metricStatuses = new LinkedHashMap<>();
        metricStatuses.put("p95", AssertionStatus.PASS);
        assertResults.put("GET /api/users", AssertionStatus.PASS, metricStatuses);

        String xml = writeAndRead(data, assertResults.build(), "Test");

        assertFalse(xml.contains("<failure"));
    }

    @Test
    void write_assertFailures_includeFailureElement() throws IOException {
        SamplerStatistics stat = createSampler("GET /api/slow", 800, 2000, 3000, 5.0);
        ReportData data = createReportData(stat);

        SamplerVerdicts.Builder assertResults = SamplerVerdicts.builder();
        Map<String, AssertionStatus> metricStatuses = new LinkedHashMap<>();
        metricStatuses.put("p95", AssertionStatus.FAIL);
        assertResults.put("GET /api/slow", AssertionStatus.FAIL, metricStatuses);

        String xml = writeAndRead(data, assertResults.build(), "Test");

        assertTrue(xml.contains("<failure"));
        // "ASSERT FAIL". The old wording was pinned byte-for-byte by the golden fixtures,
        // which existed to prove the legacy translation was verdict-equivalent; with
        // The message says what the product says, in the product’s own vocabulary.
        assertTrue(xml.contains("ASSERT FAIL"), xml);
        // The one place the retired word may still appear in the tree: it is the thing asserted absent.
        assertFalse(xml.contains("SLA"), "the retired vocabulary must not survive in the JUnit XML");
    }

    @Test
    void write_correctTestCountAndFailures() throws IOException {
        ReportData data = createReportData(
                createSampler("API-A", 100, 200, 300, 0),
                createSampler("API-B", 800, 2000, 3000, 5.0),
                createSampler("API-C", 50, 100, 150, 0));

        SamplerVerdicts.Builder assertResults = SamplerVerdicts.builder();
        Map<String, AssertionStatus> passStatuses = new LinkedHashMap<>();
        passStatuses.put("p95", AssertionStatus.PASS);
        assertResults.put("API-A", AssertionStatus.PASS, passStatuses);

        Map<String, AssertionStatus> failStatuses = new LinkedHashMap<>();
        failStatuses.put("p95", AssertionStatus.FAIL);
        assertResults.put("API-B", AssertionStatus.FAIL, failStatuses);

        assertResults.put("API-C", AssertionStatus.PASS, passStatuses);

        String xml = writeAndRead(data, assertResults.build(), "Test");

        // 3 samplers, 1 failure
        assertTrue(xml.contains("tests=\"3\""));
        assertTrue(xml.contains("failures=\"1\""));
    }

    @Test
    void write_escapesXmlSpecialChars() throws IOException {
        SamplerStatistics stat = createSampler("GET /api?foo=1&bar=2", 100, 200, 300, 0);
        ReportData data = createReportData(stat);

        String xml = writeAndRead(data, null, "Test <Special> & 'Chars'");

        // The & should be escaped to &amp;
        assertTrue(xml.contains("&amp;"));
        // The < > should be escaped
        assertTrue(xml.contains("&lt;Special&gt;"));
    }

    @Test
    void write_nullSlaResults_allPass() throws IOException {
        ReportData data = createReportData(
                createSampler("API-A", 100, 200, 300, 0),
                createSampler("API-B", 150, 250, 350, 0));

        String xml = writeAndRead(data, null, "Test");

        // No assert results means no failures
        assertFalse(xml.contains("<failure"));
        assertTrue(xml.contains("failures=\"0\""));
    }

    @Test
    void write_excludesTransactionChildren() throws IOException {
        SamplerStatistics parent = createSampler("Login Flow", 500, 1000, 1500, 0);
        SamplerStatistics child1 = createSampler("Login Flow-0", 200, 400, 600, 0);
        child1.setParentSamplerName("Login Flow");
        SamplerStatistics child2 = createSampler("Login Flow-1", 300, 600, 900, 0);
        child2.setParentSamplerName("Login Flow");
        SamplerStatistics standalone = createSampler("GET /api/health", 50, 100, 150, 0);

        ReportData data = createReportData(parent, child1, child2, standalone);

        String xml = writeAndRead(data, null, "Test");

        // Parent and standalone should be included
        assertTrue(xml.contains("name=\"Login Flow\""));
        assertTrue(xml.contains("name=\"GET /api/health\""));

        // Children should be excluded (they have parentSamplerName set)
        assertFalse(xml.contains("name=\"Login Flow-0\""));
        assertFalse(xml.contains("name=\"Login Flow-1\""));

        // Should report 2 tests (parent + standalone), not 4
        assertTrue(xml.contains("tests=\"2\""));
    }

    // --- XML 1.0 well-formedness with hostile characters (SEC-3 / LIST-3, audit D30) ---

    /** Build characters from explicit code points so the source encoding can't alter them. */
    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** A single UTF-16 code unit — used to plant a deliberately unpaired surrogate. */
    private static String unit(int codeUnit) {
        return String.valueOf((char) codeUnit);
    }

    private static final String BACKSPACE = unit(0x08);
    private static final String ESC = unit(0x1B);

    /** Parse the file the way a CI JUnit ingester would; throws if not well-formed. */
    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void write_samplerNameWithControlChars_stillWellFormed() throws Exception {
        // 0x08 BACKSPACE and 0x1B ESC are illegal in XML 1.0 even as numeric entities.
        ReportData data = createReportData(
                createSampler("GET " + BACKSPACE + "/api" + ESC + "/users", 100, 200, 300, 0));

        String xml = writeAndRead(data, null, "Test");

        assertDoesNotThrow(() -> parseXml(xml), "JUnit XML must stay parseable");
        assertFalse(xml.contains(BACKSPACE), "backspace must not survive into the XML");
        assertFalse(xml.contains(ESC), "escape must not survive into the XML");
        assertTrue(xml.contains("name=\"GET /api/users\""), "the rest of the label is preserved");
    }

    @Test
    void write_testNameWithControlChars_stillWellFormed() throws Exception {
        ReportData data = createReportData(createSampler("GET /api/users", 100, 200, 300, 0));

        String xml = writeAndRead(data, null, "Nightly " + ESC + " Run");

        assertDoesNotThrow(() -> parseXml(xml));
        assertTrue(xml.contains("name=\"Nightly  Run\""));
    }

    @Test
    void write_keepsXmlLegalWhitespaceControlChars() throws Exception {
        // TAB, LF and CR are the three control characters XML 1.0 does allow.
        ReportData data = createReportData(createSampler("A\tB", 100, 200, 300, 0));

        String xml = writeAndRead(data, null, "Test");

        assertDoesNotThrow(() -> parseXml(xml));
        assertTrue(xml.contains("name=\"A\tB\""));
    }

    @Test
    void write_dropsUnpairedSurrogatesButKeepsValidPairs() throws Exception {
        // Lone high surrogate — not a valid code point, illegal in XML.
        SamplerStatistics orphan = createSampler("bad" + unit(0xD800) + "end", 100, 200, 300, 0);
        // Valid pair: U+1F600 GRINNING FACE.
        String emoji = cp(0x1F600);
        SamplerStatistics pair = createSampler("ok" + emoji + "end", 100, 200, 300, 0);
        ReportData data = createReportData(orphan, pair);

        String xml = writeAndRead(data, null, "Test");

        assertDoesNotThrow(() -> parseXml(xml));
        assertTrue(xml.contains("name=\"badend\""), "unpaired surrogate is dropped");
        assertTrue(xml.contains("name=\"ok" + emoji + "end\""), "valid surrogate pair is preserved");
    }

    @Test
    void write_dropsXmlNonCharacters() throws Exception {
        ReportData data = createReportData(
                createSampler("a" + unit(0xFFFE) + "b" + unit(0xFFFF) + "c", 100, 200, 300, 0));

        String xml = writeAndRead(data, null, "Test");

        assertDoesNotThrow(() -> parseXml(xml));
        assertTrue(xml.contains("name=\"abc\""));
    }

    @Test
    void write_controlCharsInFailurePath_stillWellFormed() throws Exception {
        String name = "GET " + ESC + "/api/slow";
        ReportData data = createReportData(createSampler(name, 800, 2000, 3000, 5.0));

        SamplerVerdicts.Builder assertResults = SamplerVerdicts.builder();
        Map<String, AssertionStatus> metricStatuses = new LinkedHashMap<>();
        metricStatuses.put("p95", AssertionStatus.FAIL);
        assertResults.put(name, AssertionStatus.FAIL, metricStatuses);

        String xml = writeAndRead(data, assertResults.build(), "Test");

        assertDoesNotThrow(() -> parseXml(xml));
        assertTrue(xml.contains("<failure"));
    }

    @Test
    void write_ordinaryReportIsWellFormed() throws Exception {
        ReportData data = createReportData(
                createSampler("GET /api?foo=1&bar=2", 100, 200, 300, 0),
                createSampler("POST <checkout>", 200, 400, 600, 0));

        String xml = writeAndRead(data, null, "Test <Special> & 'Chars'");

        assertDoesNotThrow(() -> parseXml(xml));
    }

    // --- Run identity (R5.3) ---

    /**
     * Every testsuite declares which run produced it, as a property, ahead of every testcase.
     *
     * <p>Placement is asserted structurally rather than by string match because it is the part
     * that can silently break a consumer. Both strict schemas for this format — Surefire's and
     * the Windyroad {@code JUnit.xsd} that Azure DevOps names — model {@code testsuite} as an
     * {@code xs:sequence} with {@code properties} before {@code testcase}, and Jenkins' xUnit
     * plugin validates against an XSD from 2.0.0 on. A block that drifts below the first
     * testcase does not degrade gracefully; the file is rejected and the quality gate silently
     * stops being enforced.
     */
    @Test
    void everySuiteDeclaresTheRunIdentityBeforeItsFirstTestcase() throws Exception {
        ReportData data = createReportData(createSampler("GET /api/users", 100, 200, 300, 0));
        data.getMetadata().setStartTime(java.time.Instant.ofEpochMilli(1_700_000_000_000L));

        String xml = writeAndRead(data, null, "Test");
        Document doc = parseXml(xml);

        org.w3c.dom.NodeList suites = doc.getElementsByTagName("testsuite");
        assertTrue(suites.getLength() >= 1, "sanity: there is a testsuite to inspect");

        String expected = com.jmeterwebinsightreport.core.util.RunIdentity.of(data);
        for (int i = 0; i < suites.getLength(); i++) {
            org.w3c.dom.Element suite = (org.w3c.dom.Element) suites.item(i);
            org.w3c.dom.Element first = firstElementChild(suite);
            assertNotNull(first, "suite " + i + " has no children at all");
            assertEquals("properties", first.getTagName(),
                    "<properties> must be the FIRST child of <testsuite>, before any <testcase> "
                            + "— an out-of-order block is a validation error, not a warning.\n"
                            + xml);
            org.w3c.dom.Element property = firstElementChild(first);
            assertNotNull(property, "the properties block is empty");
            assertEquals("webinsight.runId", property.getAttribute("name"));
            assertEquals(expected, property.getAttribute("value"),
                    "and it must be the same id the HTML, the payload and the CI/CD JSON carry");
        }
    }

    @Test
    void theAssertionsSuiteCarriesTheIdentityToo() throws Exception {
        // Windyroad's schema omits minOccurs on <properties>, so it defaults to 1 and the
        // element is effectively required in EVERY suite — not only the first one.
        ReportData data = createReportData(createSampler("GET /api/users", 900, 2000, 3000, 0));

        com.jmeterwebinsightreport.report.annotation.ReportAnnotations annotations =
                new com.jmeterwebinsightreport.report.annotation.ReportAnnotations();
        annotations.setAssertions(java.util.Collections.singletonList(
                assertionConfig("users-p95", "GET /api/users", "p95", 100.0)));

        com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data, annotations);
        assertTrue(evaluation.hasGate(), "sanity: the fixture declares a gate");

        File outputFile = tempDir.resolve("junit-with-gate.xml").toFile();
        writer.write(data, evaluation.verdicts(), "Test", outputFile, evaluation.gate());
        String xml = new String(Files.readAllBytes(outputFile.toPath()), StandardCharsets.UTF_8);

        Document doc = parseXml(xml);
        org.w3c.dom.NodeList suites = doc.getElementsByTagName("testsuite");
        assertEquals(2, suites.getLength(), "sanity: the gate adds a second suite\n" + xml);
        for (int i = 0; i < suites.getLength(); i++) {
            assertEquals("properties",
                    firstElementChild((org.w3c.dom.Element) suites.item(i)).getTagName(),
                    "suite " + i + " must lead with its properties\n" + xml);
        }
    }

    /** The first child that is an element, skipping the whitespace text nodes between tags. */
    private static org.w3c.dom.Element firstElementChild(org.w3c.dom.Element parent) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof org.w3c.dom.Element) {
                return (org.w3c.dom.Element) children.item(i);
            }
        }
        return null;
    }

    /** One hand-written rule, in the shape the annotations file declares it. */
    private static com.jmeterwebinsightreport.report.annotation.ReportAnnotations.AssertionConfig
            assertionConfig(String id, String sampler, String metric, double failAbove) {
        com.jmeterwebinsightreport.report.annotation.ReportAnnotations.AssertionConfig c =
                new com.jmeterwebinsightreport.report.annotation.ReportAnnotations.AssertionConfig();
        c.setId(id);
        com.jmeterwebinsightreport.report.annotation.ReportAnnotations.TargetConfig t =
                new com.jmeterwebinsightreport.report.annotation.ReportAnnotations.TargetConfig();
        t.setSampler(sampler);
        c.setTarget(t);
        c.setMetric(metric);
        java.util.Map<String, Object> fail = new java.util.LinkedHashMap<>();
        fail.put("gt", failAbove);
        c.setFail(fail);
        return c;
    }


    // --- One suite per server gate (M4) ---

    private static com.jmeterwebinsightreport.report.gate.ServerGateEvaluation serverGates() throws Exception {
        String components = "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},\"db\":{\"type\":\"custom\",\"description\":\"DB\"}}";
        return com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(
                com.jmeterwebinsightreport.report.gate.ServerFixtures.block(components,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, "kafka"),
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("down", "down", 1, "kafka"),
                        "{\"id\":\"warm\",\"component\":\"kafka\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"warn\":{\"gt\":600},\"fail\":{\"gt\":900}}",
                        "{\"id\":\"parked\",\"component\":\"db\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"enabled\":false}",
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("conn", "down", 1, "db")),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 700),
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.series("down", "FAILED", null,
                                "HTTP 503 from https://prom.corp", new double[0])),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.run());
    }

    @Test
    void eachEvaluatedServerGateIsItsOwnSuiteAndTheRootCountsAreUntouched() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String xml = writer.render(data, null, "Checkout", null, serverGates());
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        org.w3c.dom.NodeList suites = doc.getElementsByTagName("testsuite");
        assertEquals(3, suites.getLength(), "the sampler suite plus one per server gate");
        org.w3c.dom.Element root = doc.getDocumentElement();
        assertEquals("1", root.getAttribute("tests"), "root counts the sampler suite only (D8)");
        assertEquals("0", root.getAttribute("failures"));

        org.w3c.dom.Element kafka = (org.w3c.dom.Element) suites.item(1);
        assertEquals("Checkout - kafka", kafka.getAttribute("name"));
        assertEquals("3", kafka.getAttribute("tests"));
        // lag FAILED, down never ran (SOURCE_FAILED is a config error -> <failure>), warm WARNed.
        assertEquals("2", kafka.getAttribute("failures"));
        assertEquals("0", kafka.getAttribute("skipped"));
        assertEquals("webinsight.runId", ((org.w3c.dom.Element) kafka.getElementsByTagName("property").item(0)).getAttribute("name"));
        org.w3c.dom.NodeList cases = kafka.getElementsByTagName("testcase");
        assertEquals("server-metrics", ((org.w3c.dom.Element) cases.item(0)).getAttribute("classname"));
        assertEquals("lag", ((org.w3c.dom.Element) cases.item(0)).getAttribute("name"));
        String lagFailure = ((org.w3c.dom.Element) cases.item(0)).getElementsByTagName("failure").item(0)
                .getAttributes().getNamedItem("message").getNodeValue();
        assertTrue(lagFailure.startsWith("Server assert 'lag': max 700 breached > 500"), lagFailure);
        // The M1 follow-up: SOURCE_FAILED renders as a <failure>, never a <skipped>.
        org.w3c.dom.Element down = (org.w3c.dom.Element) cases.item(1);
        assertEquals(1, down.getElementsByTagName("failure").getLength());
        assertEquals(0, down.getElementsByTagName("skipped").getLength());
        String downMessage = down.getElementsByTagName("failure").item(0).getAttributes().getNamedItem("message").getNodeValue();
        // What happened to the series, and the detail for why (SF23): "because the source never
        // answered" was false beside a PARTIAL source whose other series all came back.
        assertEquals("Server assert could not be collected: HTTP 503 from https://prom.corp", downMessage);
        assertFalse(downMessage.contains("never answered"), downMessage);
        // D28 on the testcase body as on every surface: coverage is the collected window's (SF25).
        String lagBody = ((org.w3c.dom.Element) cases.item(0)).getElementsByTagName("failure").item(0).getTextContent();
        assertTrue(lagBody.contains("\ncoverage:  100% of the collected window\n"), lagBody);
        org.w3c.dom.Element warm = (org.w3c.dom.Element) cases.item(2);
        assertTrue(warm.getElementsByTagName("system-out").item(0).getTextContent().startsWith("WARN: Server assert 'warm'"));

        // Every component builds (C6): db's collection failure is a <failure> like kafka's, and a
        // parked assert is <skipped> as before. There is no observe-only note to write.
        org.w3c.dom.Element db = (org.w3c.dom.Element) suites.item(2);
        assertEquals("Checkout - db", db.getAttribute("name"));
        assertEquals("2", db.getAttribute("tests"));
        assertEquals("1", db.getAttribute("failures"), "conn's SOURCE_FAILED");
        assertEquals("1", db.getAttribute("skipped"), "the parked assert");
        assertEquals("testcase", lastElementChild(db).getTagName(), "no observe-only note on any component");
    }

    @Test
    void verdictsCollectionFailuresAndConfigErrorsAreFailuresOnEveryComponent() throws Exception {
        // Every component builds (C6), so D29's observe-only <skipped> path is unreachable from a
        // file: a FAIL, a SOURCE_FAILED, a LOW_COVERAGE and an INVALID_RULE are each a <failure>.
        String asserts = String.join(",",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("breach", "lag", 500, "G"),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("dead", "down", 1, "G"),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("sparse", "sparse", 1, "G"),
                "{\"id\":\"typo\",\"component\":\"G\",\"query\":\"lag\",\"reduce\":{\"kind\":\"maxx\"},\"fail\":{\"gt\":1}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("fine", "lag", 9999, "G"));
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 700),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.series("down", "FAILED", null, "HTTP 503", new double[0]),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.series("sparse", "INSUFFICIENT", 0.4, null, 1, 2));
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();

        org.w3c.dom.Element suite = serverSuite(data, asserts, payload);
        java.util.Map<String, org.w3c.dom.Element> cases = casesOf(suite);
        assertEquals("5", suite.getAttribute("tests"));
        assertEquals("4", suite.getAttribute("failures"), "breach, dead, sparse and typo");
        assertEquals("0", suite.getAttribute("skipped"));
        String breach = ((org.w3c.dom.Element) cases.get("breach").getElementsByTagName("failure").item(0)).getAttribute("message");
        assertTrue(breach.startsWith("Server assert 'breach': max 700 breached > 500"), breach);
        org.w3c.dom.Element fine = cases.get("fine");
        assertEquals(0, fine.getElementsByTagName("failure").getLength() + fine.getElementsByTagName("skipped").getLength()
                + fine.getElementsByTagName("system-out").getLength(), "a PASS is a bare testcase");
        assertEquals("testcase", lastElementChild(suite).getTagName(), "no observe note");
    }

    // --- SF16: a verdict number is written in the unit it is compared in ---

    /**
     * A load rule on {@code GET /a} (throughput 5 in {@link com.jmeterwebinsightreport.report.gate.ServerFixtures#run()})
     * for a {@code divideBy} to divide by, and the asserts given, on a custom component G.
     */
    private static String withLoadRule(String... asserts) {
        return "{\"assertions\":[{\"id\":\"load:throughput\",\"target\":{\"sampler\":\"GET /a\"},"
                + "\"metric\":\"throughput\",\"fail\":{\"lt\":1}}],"
                + "\"serverMetrics\":{\"components\":{\"G\":{\"type\":\"custom\"}},\"asserts\":["
                + String.join(",", asserts) + "]}}";
    }

    /** {@code heap} grows 15 B every 15 s - 60 B/min - and {@code lag} peaks at 700. */
    private static String growthPayload() {
        return com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("heap", 0, 15, 30, 45, 60),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 700));
    }

    private static String failureMessage(org.w3c.dom.Element testcase) {
        return ((org.w3c.dom.Element) testcase.getElementsByTagName("failure").item(0)).getAttribute("message");
    }

    private static String failureBody(org.w3c.dom.Element testcase) {
        return testcase.getElementsByTagName("failure").item(0).getTextContent();
    }

    @Test
    void aSlopeFailureReadsItsActualAndBandsPerMinute() throws Exception {
        // B9: a 60 MB/min growth read "slope 60000000.00 B breached > 50000000 B", a level the
        // band never judged. The unit stays the series' (B); the verdict is in B per minute.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        org.w3c.dom.Element suite = serverSuiteOf(data, withLoadRule(
                "{\"id\":\"growth\",\"component\":\"G\",\"query\":\"heap\",\"unit\":\"B\",\"reduce\":{\"kind\":\"slope\"},"
                        + "\"warn\":{\"gt\":40},\"fail\":{\"gt\":50}}"), growthPayload());
        org.w3c.dom.Element growth = casesOf(suite).get("growth");

        String message = failureMessage(growth);
        assertTrue(message.matches("Server assert 'growth': slope 60(\\.00)? B/min breached > 50 B/min"), message);
        String body = failureBody(growth);
        assertTrue(body.contains("criteria:  warn > 40 B/min  ·  fail > 50 B/min\n"), body);
        assertTrue(body.matches("(?s).*\nactual:    60(\\.00)? B/min\n.*"), body);
        assertFalse(body.contains("divideBy:"), "no divisor line without a divisor: " + body);
    }

    @Test
    void aRatioFailureNamesItsDivisorAndItsRatioUnit() throws Exception {
        // The F5 leftovers: the board read "140 msgs ÷ load:throughput" while JUnit read
        // "max 140 msgs breached > 100 msgs", and the body never said the number was a ratio.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        org.w3c.dom.Element suite = serverSuiteOf(data, withLoadRule(
                "{\"id\":\"lag-per-rps\",\"component\":\"G\",\"query\":\"lag\",\"unit\":\"msgs\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"divideBy\":\"load:throughput\",\"fail\":{\"gt\":100}}"), growthPayload());
        org.w3c.dom.Element ratio = casesOf(suite).get("lag-per-rps");

        assertEquals("Server assert 'lag-per-rps': max 140 msgs ÷ load:throughput breached > 100 msgs ÷ load:throughput",
                failureMessage(ratio));
        String body = failureBody(ratio);
        assertTrue(body.contains("reduce:    max across max\ndivideBy:  load:throughput\n"), body);
        assertTrue(body.contains("criteria:  fail > 100 msgs ÷ load:throughput\n"), body);
        assertTrue(body.contains("actual:    140 msgs ÷ load:throughput\n"), body);
    }

    @Test
    void aSlopeRatioFailureIsPerMinuteAndPerUnitOfLoad() throws Exception {
        // Slope + divideBy lost both: "slope 0.02 % breached > 0.01 %". 60 B/min over 5 is 12.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        org.w3c.dom.Element suite = serverSuiteOf(data, withLoadRule(
                "{\"id\":\"growth-per-rps\",\"component\":\"G\",\"query\":\"heap\",\"unit\":\"B\",\"reduce\":{\"kind\":\"slope\"},"
                        + "\"divideBy\":\"load:throughput\",\"warn\":{\"gt\":8},\"fail\":{\"gt\":10}}"), growthPayload());
        org.w3c.dom.Element both = casesOf(suite).get("growth-per-rps");

        String message = failureMessage(both);
        assertTrue(message.matches(
                "Server assert 'growth-per-rps': slope 12(\\.00)? B/min ÷ load:throughput breached > 10 B/min ÷ load:throughput"),
                message);
        String body = failureBody(both);
        assertTrue(body.contains("divideBy:  load:throughput\n"), body);
        assertTrue(body.contains("criteria:  warn > 8 B/min ÷ load:throughput  ·  fail > 10 B/min ÷ load:throughput\n"), body);
        assertTrue(body.matches("(?s).*\nactual:    12(\\.00)? B/min ÷ load:throughput\n.*"), body);
    }

    @Test
    void aSlopeWarningIsWrittenPerMinuteToo() throws Exception {
        // The WARN <system-out> shares kpiFailureMessage with the <failure>; pinned on its own so a
        // warning cannot fall back to the series' unit while every FAIL test stays green.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        org.w3c.dom.Element suite = serverSuiteOf(data, withLoadRule(
                "{\"id\":\"growth-warns\",\"component\":\"G\",\"query\":\"heap\",\"unit\":\"B\",\"reduce\":{\"kind\":\"slope\"},"
                        + "\"warn\":{\"gt\":40},\"fail\":{\"gt\":100}}"), growthPayload());
        org.w3c.dom.Element warns = casesOf(suite).get("growth-warns");
        assertEquals(0, warns.getElementsByTagName("failure").getLength(), "a WARN is not a failure");
        String out = warns.getElementsByTagName("system-out").item(0).getTextContent();
        assertTrue(out.matches("WARN: Server assert 'growth-warns': slope 60(\\.00)? B/min breached > 40 B/min"), out);
    }

    @Test
    void aRatioThatCouldNotBeJudgedStillNamesItsDivisorAndItsRatioUnit() throws Exception {
        // A LOW_COVERAGE ratio is a <failure> with no actual: its body is criteria and a problem,
        // and the criteria are still the ratio's, beside the divideBy line that says it is one.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.series("sparse", "INSUFFICIENT", 0.4, null, 1, 2));
        org.w3c.dom.Element suite = serverSuiteOf(data, withLoadRule(
                "{\"id\":\"sparse-per-rps\",\"component\":\"G\",\"query\":\"sparse\",\"unit\":\"msgs\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"divideBy\":\"load:throughput\",\"fail\":{\"gt\":10}}"), payload);
        org.w3c.dom.Element ratio = casesOf(suite).get("sparse-per-rps");
        assertTrue(failureMessage(ratio).startsWith("Server assert was collected below the coverage floor"), failureMessage(ratio));
        String body = failureBody(ratio);
        assertTrue(body.contains("reduce:    max across max\ndivideBy:  load:throughput\n"), body);
        assertTrue(body.contains("criteria:  fail > 10 msgs ÷ load:throughput\n"), body);
        assertTrue(body.contains("\nproblem:   Server assert was collected below the coverage floor"), body);
        assertFalse(body.contains("actual:"), "a verdict that was not taken has no actual: " + body);
    }

    @Test
    void aSmallActualIsNeverWrittenAsZero() throws Exception {
        // SF15 on JUnit: two decimals alone wrote 0.004 s as "max 0.00 s breached > 0.003 s", the
        // signature of an assert that measured nothing. Since item 3 that first case's own band
        // keeps 0.004 off 0.00 (Digits.beside over gt 0.003), so it would stay green with the band of
        // 0 dropped; the guard is pinned by the two below it, whose bands do not separate the value
        // from 0 - only the band of 0 does.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("tiny", 0.001, 0.004),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("small", 0.004, 0.005));
        org.w3c.dom.Element suite = serverSuite(data, String.join(",",
                "{\"id\":\"tiny\",\"component\":\"G\",\"query\":\"tiny\",\"unit\":\"s\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"fail\":{\"gt\":0.003}}",
                "{\"id\":\"small\",\"component\":\"G\",\"query\":\"small\",\"unit\":\"s\",\"reduce\":{\"kind\":\"min\"},"
                        + "\"fail\":{\"lt\":1}}"), payload);
        org.w3c.dom.Element tiny = casesOf(suite).get("tiny");
        assertEquals("Server assert 'tiny': max 0.004 s breached > 0.003 s", failureMessage(tiny));
        assertTrue(failureBody(tiny).contains("\nactual:    0.004 s\n"), failureBody(tiny));
        org.w3c.dom.Element small = casesOf(suite).get("small");
        assertEquals("Server assert 'small': min 0.004 s breached < 1 s", failureMessage(small));
        assertTrue(failureBody(small).contains("\nactual:    0.004 s\n"), failureBody(small));

        // The client's twin of CiCdJsonSerializerTest's trickle, which the JSON writes 0.0 (Q12): no
        // band of the rule separates 7.7e-6/s from 0, so here the band of 0 keeps its digits.
        SamplerStatistics trickle = createSampler("GET /trickle", 100, 0, 0, 0);
        trickle.setThroughput(0.0000077);
        org.w3c.dom.Element t = assertionCases(createReportData(trickle),
                "{\"assertions\":[{\"id\":\"trickle\",\"target\":{\"sampler\":\"GET /trickle\"},"
                        + "\"metric\":\"throughput\",\"fail\":{\"lt\":0.001}}]}").get("trickle");
        assertEquals("Assertion 'trickle': throughput 0.0000077 /s on GET /trickle breached < 0.001 /s",
                failureMessage(t));
        assertTrue(failureBody(t).contains("\n  [FAIL] GET /trickle = 0.0000077 /s"), failureBody(t));
    }

    // --- Item 3: an actual is never printed on a band it is not on, or across one ---

    /**
     * Two decimals printed a FAIL at 119.36094674556213 under {@code gt 119.36} as "119.36ms ...
     * breached &gt; 119.36 ms", the band it breached, and a WARN at 234.91166666666666 on the band it
     * crossed. The actual now gets the digits that keep it on its own side of every band the rule
     * declares (Q1), in the message and in each match line of the body alike: the number the CI/CD
     * JSON writes, through the same {@code Digits.beside}.
     */
    @Test
    void aClientActualIsNeverPrintedOnOrAcrossItsBand() throws Exception {
        java.util.Map<String, org.w3c.dom.Element> failing = assertionCases(
                createReportData(createSampler("GET /a", 119.36094674556213, 0, 0, 0)),
                "{\"assertions\":[{\"id\":\"x\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"mean\","
                        + "\"fail\":{\"gt\":119.36}}]}");
        org.w3c.dom.Element x = failing.get("x");
        assertEquals("Assertion 'x': mean 119.361 ms on GET /a breached > 119.36 ms", failureMessage(x));
        assertTrue(failureBody(x).contains("\n  [FAIL] GET /a = 119.361 ms"), failureBody(x));

        java.util.Map<String, org.w3c.dom.Element> warning = assertionCases(
                createReportData(createSampler("GET /a", 234.91166666666666, 0, 0, 0)),
                "{\"assertions\":[{\"id\":\"w\",\"target\":{\"scope\":\"overall\"},\"metric\":\"mean\","
                        + "\"warn\":{\"gt\":234.91},\"fail\":{\"gt\":100000}}]}");
        org.w3c.dom.Element w = warning.get("w");
        assertEquals(0, w.getElementsByTagName("failure").getLength(), "a WARN is not a failure");
        assertEquals("WARN: Assertion 'w': mean 234.912 ms on Whole run breached > 234.91 ms",
                w.getElementsByTagName("system-out").item(0).getTextContent());

        // A FAIL lists every match, and a sibling's line is printed beside the warn band as well as
        // the fail band (Q1): two decimals put GET /b's 100.004 on the warn band it crossed.
        org.w3c.dom.Element m = assertionCases(
                createReportData(createSampler("GET /a", 250, 0, 0, 0), createSampler("GET /b", 100.004, 0, 0, 0)),
                "{\"assertions\":[{\"id\":\"m\",\"target\":{\"samplers\":[\"GET /a\",\"GET /b\"]},\"metric\":\"mean\","
                        + "\"warn\":{\"gt\":100},\"fail\":{\"gt\":200}}]}").get("m");
        assertEquals("Assertion 'm': mean 250 ms on GET /a breached > 200 ms", failureMessage(m));
        assertTrue(failureBody(m).contains("\n  [WARN] GET /b = 100.004 ms"), failureBody(m));
    }

    // --- L10 / L11 / L13: the client message's wording ---

    /**
     * L10: the client message and every match line wrote the number and its unit as one word —
     * "p95 610.50ms on POST /checkout breached &gt; 20 ms" — while the band beside it, every server
     * line and the server's actual: line put a space between them. One unit is written one way.
     */
    @Test
    void aClientActualIsSeparatedFromItsUnitAsItsBandIs() throws Exception {
        SamplerStatistics s = createSampler("GET /a", 610.5, 0, 0, 2.5);
        s.setThroughput(8.25);
        java.util.Map<String, org.w3c.dom.Element> cases = assertionCases(createReportData(s),
                "{\"assertions\":["
                        + "{\"id\":\"lat\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"mean\",\"fail\":{\"gt\":20}},"
                        + "{\"id\":\"tput\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"throughput\",\"fail\":{\"lt\":30}},"
                        + "{\"id\":\"err\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"errorRate\",\"fail\":{\"gt\":1}}]}");
        assertEquals("Assertion 'lat': mean 610.50 ms on GET /a breached > 20 ms", failureMessage(cases.get("lat")));
        assertTrue(failureBody(cases.get("lat")).trim().endsWith("\n  [FAIL] GET /a = 610.50 ms"), failureBody(cases.get("lat")));
        assertEquals("Assertion 'tput': throughput 8.25 /s on GET /a breached < 30 /s", failureMessage(cases.get("tput")));
        assertTrue(failureBody(cases.get("tput")).trim().endsWith("\n  [FAIL] GET /a = 8.25 /s"), failureBody(cases.get("tput")));
        assertEquals("Assertion 'err': errorRate 2.50 % on GET /a breached > 1 %", failureMessage(cases.get("err")));
    }

    /**
     * L13: the message names {@code RuleResult.worstJudged()}, which is the match the CI/CD JSON's
     * attainment describes. It named the FIRST FAIL, so a rule failing on two samplers said
     * "mean 150ms on GET /a" here while the JSON's {@code attainment.actual} said 300.
     */
    @Test
    void aMultiMatchFailNamesTheWorstMatchNotTheFirst() throws Exception {
        org.w3c.dom.Element r = assertionCases(
                createReportData(createSampler("GET /a", 150, 0, 0, 0), createSampler("GET /b", 300, 0, 0, 0)),
                "{\"assertions\":[{\"id\":\"r\",\"target\":{\"match\":\"GET *\"},\"metric\":\"mean\","
                        + "\"fail\":{\"gt\":100}}]}").get("r");
        assertEquals("Assertion 'r': mean 300 ms on GET /b breached > 100 ms", failureMessage(r));
    }

    /**
     * L11: the per-sampler suite named a failing metric only when it was p95, p99, mean or
     * errorRate, so a sampler failed by a p50 or a throughput rule read "ASSERT FAIL:" and nothing
     * else, over a body that listed the passing p95. Every failing metric is named now, the four
     * legacy ones in their legacy words (pipelines grep them), any other from the rule's own match:
     * its number, its unit and the band it breached.
     */
    @Test
    void theSamplerSuiteNamesEveryFailingMetric() throws Exception {
        SamplerStatistics a = createSampler("GET /a", 50, 183, 0, 0);
        a.setMedianResponseTime(119.36094674556213);
        a.setThroughput(8.29892736363825);
        ReportData data = createReportData(a);
        com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations("{\"assertions\":["
                                + "{\"id\":\"p95-ok\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"p95\",\"fail\":{\"gt\":1000}},"
                                + "{\"id\":\"p50\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"p50\",\"fail\":{\"gt\":119.36}},"
                                + "{\"id\":\"tput\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"throughput\",\"fail\":{\"lt\":8.3}}]}"));
        Document doc = parseXml(writer.render(data, evaluation.verdicts(), "Checkout", evaluation.gate()));
        org.w3c.dom.Element sampler = (org.w3c.dom.Element) doc.getElementsByTagName("testcase").item(0);
        assertEquals("GET /a", sampler.getAttribute("name"));
        assertEquals("ASSERT FAIL: p50 119.361 ms breached > 119.36 ms; throughput 8.299 /s breached < 8.3 /s",
                failureMessage(sampler));
        assertEquals("P95: 183ms (PASS)\np50: 119.361 ms (FAIL)\nthroughput: 8.299 /s (FAIL)",
                failureBody(sampler).trim());

        // Verdicts assembled without a gate carry no match to quote: the metric is still named.
        SamplerVerdicts.Builder bare = SamplerVerdicts.builder();
        Map<String, AssertionStatus> metrics = new LinkedHashMap<>();
        metrics.put("p95", AssertionStatus.FAIL);
        metrics.put("max", AssertionStatus.FAIL);
        bare.put("GET /a", AssertionStatus.FAIL, metrics);
        Document plain = parseXml(writer.render(data, bare.build(), "Checkout", null));
        org.w3c.dom.Element only = (org.w3c.dom.Element) plain.getElementsByTagName("testcase").item(0);
        assertEquals("ASSERT FAIL: P95 183ms exceeds threshold; max breached its fail band", failureMessage(only));
        assertEquals("P95: 183ms (FAIL)\nmax: (FAIL)", failureBody(only).trim());
    }

    /**
     * L11, beside a legacy metric: the legacy words first, as they always were, then every other
     * failing metric. Two rules failing one metric on one sampler measure the same value, so it is
     * named once, beside the band of the first rule to fail it; a WARN on another metric is in the
     * body and not the message.
     */
    @Test
    void theSamplerSuiteNamesALegacyAndANewFailingMetricOnceEach() throws Exception {
        SamplerStatistics a = createSampler("GET /a", 50, 183, 0, 0);
        a.setMedianResponseTime(119.36094674556213);
        a.setMaxResponseTime(900);
        ReportData data = createReportData(a);
        com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations("{\"assertions\":["
                                + "{\"id\":\"p95\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"p95\",\"fail\":{\"gt\":100}},"
                                + "{\"id\":\"max-warns\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"max\",\"warn\":{\"gt\":500},\"fail\":{\"gt\":1000}},"
                                + "{\"id\":\"p50-on\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"p50\",\"fail\":{\"gt\":119.36}},"
                                + "{\"id\":\"p50-across\",\"target\":{\"match\":\"GET *\"},\"metric\":\"p50\",\"fail\":{\"gt\":119.3605}}]}"));
        Document doc = parseXml(writer.render(data, evaluation.verdicts(), "Checkout", evaluation.gate()));
        org.w3c.dom.Element sampler = (org.w3c.dom.Element) doc.getElementsByTagName("testcase").item(0);
        assertEquals("ASSERT FAIL: P95 183ms exceeds threshold; p50 119.361 ms breached > 119.36 ms",
                failureMessage(sampler));
        assertEquals("P95: 183ms (FAIL)\nmax: 900 ms (WARN)\np50: 119.361 ms (FAIL)", failureBody(sampler).trim());
    }

    /**
     * REV-6: a metric's number on the sampler suite is written beside the bands of every rule that
     * judged that metric on that sampler, not the deciding rule's alone. At 119.36094 a FAIL under
     * {@code gt 119.36} beside a PASS under {@code gt 119.361}: the FAIL's bands alone wrote
     * "119.361", on the band the other rule PASSed it under.
     */
    @Test
    void theSamplerSuiteWritesAMetricBesideEveryRuleThatJudgedIt() throws Exception {
        SamplerStatistics a = createSampler("GET /a", 50, 183, 0, 0);
        a.setMedianResponseTime(119.36094);
        ReportData data = createReportData(a);
        com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations("{\"assertions\":["
                                + "{\"id\":\"p50-fails\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"p50\",\"fail\":{\"gt\":119.36}},"
                                + "{\"id\":\"p50-passes\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"p50\",\"fail\":{\"gt\":119.361}}]}"));
        Document doc = parseXml(writer.render(data, evaluation.verdicts(), "Checkout", evaluation.gate()));
        org.w3c.dom.Element sampler = (org.w3c.dom.Element) doc.getElementsByTagName("testcase").item(0);
        assertEquals("GET /a", sampler.getAttribute("name"));
        assertEquals("ASSERT FAIL: p50 119.3609 ms breached > 119.36 ms", failureMessage(sampler));
        assertEquals("p50: 119.3609 ms (FAIL)", failureBody(sampler).trim());

        // Each rule's own testcase is written beside that rule's bands, as it always was.
        Map<String, org.w3c.dom.Element> rules = casesOf(suiteNamed(doc, "Checkout - assertions"));
        assertEquals("Assertion 'p50-fails': p50 119.361 ms on GET /a breached > 119.36 ms",
                failureMessage(rules.get("p50-fails")));
    }

    /**
     * L11's documented example (WIKI/Tutorial/11-Export-Integration.md, "JUnit XML Output"), held to
     * the writer: the sampler suite names a sampler's own failing metrics — POST /checkout's p95 in
     * its legacy words, then its p50 — and never a {@code scope: "overall"} rule's. That rule's match
     * is the whole run's, not a sampler's, so it is a testcase of the assertions suite only, and the
     * sampler suite has no "Whole run" testcase. The example once put run-throughput's 27.50 /s in
     * POST /checkout's message, which no run can produce.
     */
    @Test
    void aWholeRunRuleIsNeverNamedOnASamplerTestcase() throws Exception {
        SamplerStatistics products = createSampler("GET /api/products", 234, 410, 520, 0);
        SamplerStatistics checkout = createSampler("POST /checkout", 891, 2340, 3980, 0.8);
        checkout.setMedianResponseTime(812.5);
        checkout.setSampleCount(1550);   // 1650 samples over the 60 s run: 27.50 /s
        ReportData data = createReportData(products, checkout);
        com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations("{\"assertions\":["
                                + "{\"id\":\"checkout-p95\",\"target\":{\"sampler\":\"POST /checkout\"},\"metric\":\"p95\","
                                + "\"warn\":{\"gt\":650},\"fail\":{\"gt\":1000}},"
                                + "{\"id\":\"checkout-p50\",\"target\":{\"sampler\":\"POST /checkout\"},\"metric\":\"p50\","
                                + "\"fail\":{\"gt\":800}},"
                                + "{\"id\":\"checkout-errors\",\"target\":{\"sampler\":\"POST /checkout\"},\"metric\":\"errorRate\","
                                + "\"fail\":{\"gt\":1}},"
                                + "{\"id\":\"run-throughput\",\"target\":{\"scope\":\"overall\"},\"metric\":\"throughput\","
                                + "\"fail\":{\"lt\":30}}]}"));
        Document doc = parseXml(writer.render(data, evaluation.verdicts(), "Load Test", evaluation.gate()));
        org.w3c.dom.NodeList suites = doc.getElementsByTagName("testsuite");
        java.util.Map<String, org.w3c.dom.Element> samplerSuite = casesOf((org.w3c.dom.Element) suites.item(0));
        java.util.Map<String, org.w3c.dom.Element> assertionSuite = casesOf((org.w3c.dom.Element) suites.item(1));

        assertEquals(Arrays.asList("GET /api/products", "POST /checkout"), new ArrayList<>(samplerSuite.keySet()),
                "one testcase per top-level sampler, and no 'Whole run' among them");
        org.w3c.dom.Element post = samplerSuite.get("POST /checkout");
        assertEquals("ASSERT FAIL: P95 2340ms exceeds threshold; p50 812.50 ms breached > 800 ms", failureMessage(post));
        assertEquals("P95: 2340ms (FAIL)\nError Rate: 0.8% (PASS)\np50: 812.50 ms (FAIL)", failureBody(post).trim());
        assertFalse(failureBody(post).contains("throughput"), "a whole-run rule is not a sampler's metric");

        assertEquals("Assertion 'checkout-p50': p50 812.50 ms on POST /checkout breached > 800 ms",
                failureMessage(assertionSuite.get("checkout-p50")));
        assertEquals("target:   POST /checkout\nmetric:   p50\ncriteria: fail > 800 ms\n  [FAIL] POST /checkout = 812.50 ms",
                failureBody(assertionSuite.get("checkout-p50")).trim());
        assertEquals("Assertion 'run-throughput': throughput 27.50 /s on Whole run breached < 30 /s",
                failureMessage(assertionSuite.get("run-throughput")));
    }

    /**
     * The server side of the same rule: a 3.004 s FAIL under {@code gt 3} printed "max 3.00 s
     * breached &gt; 3 s" through a real {@code wir-collect --csv} payload, and a 2.996 s FAIL under
     * {@code lt 3} "min 3.00 s breached &lt; 3 s". The bands are the assert's own, already in the
     * verdict's unit. A negative slope keeps its sign and its digits, as it did.
     */
    @Test
    void aServerActualIsNeverPrintedOnOrAcrossItsBand() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lat", 1, 3.004),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("floor", 2.996, 5),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("queue", 0, -0.001, -0.002, -0.003, -0.004));
        org.w3c.dom.Element suite = serverSuite(data, String.join(",",
                "{\"id\":\"lat\",\"component\":\"G\",\"query\":\"lat\",\"unit\":\"s\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"fail\":{\"gt\":3}}",
                "{\"id\":\"floor\",\"component\":\"G\",\"query\":\"floor\",\"unit\":\"s\",\"reduce\":{\"kind\":\"min\"},"
                        + "\"fail\":{\"lt\":3}}",
                "{\"id\":\"warm\",\"component\":\"G\",\"query\":\"lat\",\"unit\":\"s\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"warn\":{\"gt\":3},\"fail\":{\"gt\":10}}",
                "{\"id\":\"drain\",\"component\":\"G\",\"query\":\"queue\",\"unit\":\"msgs\",\"reduce\":{\"kind\":\"slope\"},"
                        + "\"fail\":{\"lt\":0}}",
                "{\"id\":\"covered\",\"component\":\"G\",\"query\":\"lat\",\"unit\":\"s\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"warn\":{\"gt\":3},\"fail\":{\"gt\":2.99}}"), payload);
        java.util.Map<String, org.w3c.dom.Element> cases = casesOf(suite);

        assertEquals("Server assert 'lat': max 3.004 s breached > 3 s", failureMessage(cases.get("lat")));
        assertTrue(failureBody(cases.get("lat")).contains("\nactual:    3.004 s\n"), failureBody(cases.get("lat")));
        assertEquals("Server assert 'floor': min 2.996 s breached < 3 s", failureMessage(cases.get("floor")));
        assertTrue(failureBody(cases.get("floor")).contains("\nactual:    2.996 s\n"), failureBody(cases.get("floor")));
        org.w3c.dom.Element warm = cases.get("warm");
        assertEquals(0, warm.getElementsByTagName("failure").getLength(), "a WARN is not a failure");
        assertEquals("WARN: Server assert 'warm': max 3.004 s breached > 3 s",
                warm.getElementsByTagName("system-out").item(0).getTextContent());
        assertEquals("Server assert 'drain': slope -0.004 msgs/min breached < 0 msgs/min", failureMessage(cases.get("drain")));
        // A fail band that covers the warn band is a WARNING finding and is still judged, so a FAIL
        // can sit by its warn band: the actual: line counts that band too (Q1), where the fail band
        // alone would print 3.00, on the warn band it crossed.
        org.w3c.dom.Element covered = cases.get("covered");
        assertEquals("Server assert 'covered': max 3.004 s breached > 2.99 s", failureMessage(covered));
        assertTrue(failureBody(covered).contains("\nactual:    3.004 s\n"), failureBody(covered));
    }

    /**
     * Q19 in Java: the LOAD_NOT_ACHIEVED sentence quotes a FAILed load rule's measurement through
     * {@code ServerKpiEvaluator.formatValue}, never zero for a value that is not. A load of 0.004
     * req/s under {@code lt 1} is a band that does not separate it from 0, so only the band of 0
     * keeps "0.004/s" off "0.00/s". The page's twin, {@code javaMeasured}, is held to the same
     * spelling by server-editor.test.js's C38 table; this holds Java to it, through the real
     * evaluator and the {@code <skipped>} message that carries the sentence.
     */
    @Test
    void aLoadRulesQuotedMeasurementIsNeverZero() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        data.getSamplerStatistics().get(0).setThroughput(0.004);
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("cpu", 50, 60));
        org.w3c.dom.Element suite = serverSuiteOf(data, withLoadRule(
                "{\"id\":\"cpu\",\"component\":\"G\",\"query\":\"cpu\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"fail\":{\"gt\":90},\"requiresLoad\":\"load:throughput\"}"), payload);
        org.w3c.dom.Element cpu = casesOf(suite).get("cpu");
        assertEquals("Server assert was not judged because the load it depends on was not established: "
                        + "load rule 'load:throughput' FAILED: throughput 0.004/s breached < 1 /s",
                ((org.w3c.dom.Element) cpu.getElementsByTagName("skipped").item(0)).getAttribute("message"));
    }

    /**
     * J2 (Q14): at a decimal tie JUnit writes round2's number - the JSON's - then spells it as it
     * always has. {@code %.2f} rounds the decimal text half up, so 1.005, whose double sits below the
     * tie, printed "1.01" where the JSON wrote 1.0, and a negative tie rounded away from zero where
     * round2 rounds toward it. A value two decimals would print as 0 keeps its digits (the band of 0),
     * its sign included; a value that is not finite keeps the text it always had.
     */
    @Test
    void aDecimalTieIsRound2sNumberAndANonFiniteActualKeepsItsText() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("tie", 1.005),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("negtie", -280547.605),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("negtiny", -0.005),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("overflow", 1.7E308, 1.7E308));
        org.w3c.dom.Element suite = serverSuite(data, String.join(",",
                "{\"id\":\"tie\",\"component\":\"G\",\"query\":\"tie\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":0.5}}",
                "{\"id\":\"neg-tie\",\"component\":\"G\",\"query\":\"negtie\",\"reduce\":{\"kind\":\"min\"},\"fail\":{\"lt\":0}}",
                "{\"id\":\"neg-tiny\",\"component\":\"G\",\"query\":\"negtiny\",\"reduce\":{\"kind\":\"min\"},\"fail\":{\"lt\":0}}",
                "{\"id\":\"overflow\",\"component\":\"G\",\"query\":\"overflow\",\"reduce\":{\"kind\":\"avg\"},\"fail\":{\"gt\":1}}"),
                payload);
        java.util.Map<String, org.w3c.dom.Element> cases = casesOf(suite);

        assertEquals("Server assert 'tie': max 1.00 breached > 0.5", failureMessage(cases.get("tie")));
        assertEquals("Server assert 'neg-tie': min -280547.60 breached < 0", failureMessage(cases.get("neg-tie")));
        assertEquals("Server assert 'neg-tiny': min -0.005 breached < 0", failureMessage(cases.get("neg-tiny")));
        assertEquals("Server assert 'overflow': avg Infinity breached > 1", failureMessage(cases.get("overflow")));
    }

    /** The client rule suite's testcases by rule id, the gate built from {@code annotations} as a run builds it. */
    private java.util.Map<String, org.w3c.dom.Element> assertionCases(ReportData data, String annotations)
            throws Exception {
        com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(annotations));
        Document doc = parseXml(writer.render(data, evaluation.verdicts(), "Checkout", evaluation.gate()));
        org.w3c.dom.NodeList suites = doc.getElementsByTagName("testsuite");
        for (int i = 0; i < suites.getLength(); i++) {
            org.w3c.dom.Element s = (org.w3c.dom.Element) suites.item(i);
            if ("Checkout - assertions".equals(s.getAttribute("name"))) {
                return casesOf(s);
            }
        }
        throw new AssertionError("no assertions suite");
    }

    /** The one server suite, over a custom component G, of the asserts given. */
    private org.w3c.dom.Element serverSuite(com.jmeterwebinsightreport.core.model.ReportData data,
                                            String asserts, String payload) throws Exception {
        return serverSuiteOf(data,
                com.jmeterwebinsightreport.report.gate.ServerFixtures.block("{\"G\":{\"type\":\"custom\"}}", asserts),
                payload);
    }

    /** The server suite of component G, from a whole annotations document. */
    private org.w3c.dom.Element serverSuiteOf(com.jmeterwebinsightreport.core.model.ReportData data,
                                              String annotations, String payload) throws Exception {
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation e =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(annotations, payload, data);
        Document doc = parseXml(writer.render(data, null, "Checkout", null, e));
        org.w3c.dom.NodeList suites = doc.getElementsByTagName("testsuite");
        for (int i = 0; i < suites.getLength(); i++) {
            org.w3c.dom.Element s = (org.w3c.dom.Element) suites.item(i);
            if ("Checkout - G".equals(s.getAttribute("name"))) {
                return s;
            }
        }
        throw new AssertionError("no suite for gate G");
    }

    private static java.util.Map<String, org.w3c.dom.Element> casesOf(org.w3c.dom.Element suite) {
        java.util.Map<String, org.w3c.dom.Element> out = new java.util.LinkedHashMap<>();
        org.w3c.dom.NodeList cases = suite.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) cases.item(i);
            out.put(c.getAttribute("name"), c);
        }
        return out;
    }

    private static org.w3c.dom.Element lastElementChild(org.w3c.dom.Element parent) {
        org.w3c.dom.Element last = null;
        org.w3c.dom.NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof org.w3c.dom.Element) {
                last = (org.w3c.dom.Element) kids.item(i);
            }
        }
        return last;
    }

    @Test
    void aKpiBelongingToNoDeclaredGateStillGetsASuite() throws Exception {
        // An orphan is fatal under failonerror (D1) and is in serverKpis[]. Walking gates() alone
        // left it in no <testsuite> at all, so a dashboard reading the XML saw a green run while
        // the process exited 1 - the one shape where the two outputs disagreed.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation orphaned =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.block(
                                "{\"kafka\":{\"label\":\"Kafka\"}}",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("homeless", "lag", 1, "typo")),
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 0)),
                        data);
        String xml = writer.render(data, null, "Checkout", null, orphaned);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        org.w3c.dom.NodeList suites = doc.getElementsByTagName("testsuite");
        org.w3c.dom.Element noGate = null;
        for (int i = 0; i < suites.getLength(); i++) {
            if (("Checkout - " + JunitXmlWriter.NO_GATE_SUITE).equals(
                    ((org.w3c.dom.Element) suites.item(i)).getAttribute("name"))) {
                noGate = (org.w3c.dom.Element) suites.item(i);
            }
        }
        assertNotNull(noGate, "the orphan needs a suite of its own: " + xml);
        assertEquals("1", noGate.getAttribute("tests"));
        assertEquals("1", noGate.getAttribute("failures"), "an undeclared gate is INVALID_RULE");
        org.w3c.dom.Element c = (org.w3c.dom.Element) noGate.getElementsByTagName("testcase").item(0);
        assertEquals("homeless", c.getAttribute("name"));
        assertEquals("server-metrics", c.getAttribute("classname"));
        assertEquals(1, c.getElementsByTagName("failure").getLength());
        assertTrue(c.getTextContent().contains("component: typo\n"), "the name it asked for: " + c.getTextContent());
    }

    @Test
    void anAssertNamingNoComponentSaysSoInItsBodyAndItsSuiteKeepsTheWireId() throws Exception {
        // SF22. The suite name is the reserved id, "(no gate)" - a contract a CI dashboard is keyed
        // on. The body is for a reader, and printed "component: null".
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation orphaned =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.block("{\"kafka\":{\"type\":\"custom\"}}",
                                "{\"id\":\"nameless\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}"),
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 0)),
                        data);
        String xml = writer.render(data, null, "Checkout", null, orphaned);
        assertTrue(xml.contains("<testsuite name=\"Checkout - (no gate)\""), xml);
        assertTrue(xml.contains("component: (no component)\n"), xml);
        assertFalse(xml.contains("component: null"), xml);
    }

    /** The one suite whose name is {@code <testName> - <id>}, or null. */
    private static org.w3c.dom.Element suiteNamed(Document doc, String name) {
        org.w3c.dom.NodeList suites = doc.getElementsByTagName("testsuite");
        org.w3c.dom.Element found = null;
        for (int i = 0; i < suites.getLength(); i++) {
            org.w3c.dom.Element s = (org.w3c.dom.Element) suites.item(i);
            if (name.equals(s.getAttribute("name"))) {
                assertNull(found, "two suites named " + name);
                found = s;
            }
        }
        return found;
    }

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theInterimReportCarriesAServerSuiteThatSaysNothingWasCollectedAndFailsNothing() throws Exception {
        // SF47 (Q7). It had no server suite at all: a Jenkins junit step reading the step-1 XML
        // could not tell a run with server asserts waiting for wir-collect from one with none.
        // <skipped>, not <failure>: no payload is not an error (D11), and a failure there would
        // mark every step-1 build unstable over the step the choreography expects.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation interim =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, null)),
                        null, data);
        String xml = writer.render(data, null, "T", null, interim);
        Document doc = parse(xml);
        assertEquals(2, doc.getElementsByTagName("testsuite").getLength(), "the sampler suite and one more\n" + xml);
        org.w3c.dom.Element suite = suiteNamed(doc, "T - serverMetrics");
        assertNotNull(suite, xml);
        assertEquals("1", suite.getAttribute("tests"));
        assertEquals("0", suite.getAttribute("failures"));
        assertEquals("1", suite.getAttribute("skipped"));
        assertEquals("properties", firstElementChild(suite).getTagName(), "its identity before its testcase");
        org.w3c.dom.Element c = (org.w3c.dom.Element) suite.getElementsByTagName("testcase").item(0);
        assertEquals("serverMetrics.payload", c.getAttribute("name"));
        assertEquals("server-metrics", c.getAttribute("classname"));
        assertEquals(0, c.getElementsByTagName("failure").getLength());
        String skipped = ((org.w3c.dom.Element) c.getElementsByTagName("skipped").item(0)).getAttribute("message");
        assertTrue(skipped.startsWith("Nothing collected yet: no server-metrics payload was given, so none of the 1 "
                + "declared server assert(s) was judged."), skipped);
        assertTrue(skipped.contains("wir-collect"), "what to run next: " + skipped);
        // The root still counts the sampler suite only (D8).
        assertEquals(writer.render(data, null, "T", null).split("\n")[1], xml.split("\n")[1]);
    }

    @Test
    void aRefusedPayloadIsOneFailureNamingTheRefusalAsTheCiCdJsonDoes() throws Exception {
        // SF47 (Q7): a refused payload the declared asserts depended on is a CONFIG ERROR and exits
        // 1, and the XML said nothing - no suite at all. One <failure>, the CI/CD JSON's ERROR
        // finding word for word. G8 (run-goldens.sh) pins the same shape through a real process.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation refused =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluateRefused(
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.block("{\"kafka\":{\"type\":\"custom\"}}",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, "kafka"),
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("pool", "lag", 500, "kafka")),
                        data);
        assertNotNull(refused.fatalPayloadProblem(), "sanity: a fatal refusal");
        String xml = writer.render(data, null, "T", null, refused);
        Document doc = parse(xml);
        org.w3c.dom.Element suite = suiteNamed(doc, "T - serverMetrics");
        assertNotNull(suite, xml);
        assertEquals("1", suite.getAttribute("tests"));
        assertEquals("1", suite.getAttribute("failures"));
        assertEquals("0", suite.getAttribute("skipped"));
        org.w3c.dom.Element c = (org.w3c.dom.Element) suite.getElementsByTagName("testcase").item(0);
        assertEquals("serverMetrics.payload", c.getAttribute("name"));
        org.w3c.dom.Element failure = (org.w3c.dom.Element) c.getElementsByTagName("failure").item(0);
        assertNotNull(failure, xml);
        String finding = null;
        for (com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding f : refused.findings()) {
            if (com.jmeterwebinsightreport.report.gate.ServerGateEvaluation.PAYLOAD_FINDING_ID.equals(f.ruleId())) {
                finding = f.message();
            }
        }
        assertEquals(finding, failure.getAttribute("message"), "the CI/CD JSON's sentence");
        assertTrue(failure.getAttribute("message").startsWith("the server-metrics payload does not exist: 'wir-absent-"),
                failure.getAttribute("message"));
        assertFalse(xml.contains(System.getProperty("java.io.tmpdir")), "the file's name, never its path");
        assertTrue(failure.getTextContent().contains("payload:   refused (MISSING)"), failure.getTextContent());
        assertTrue(failure.getTextContent().contains("asserts:   2 declared, none judged"), failure.getTextContent());
        // No component suite: nothing was judged, so "T - kafka" would read as a passing component.
        assertNull(suiteNamed(doc, "T - kafka"), xml);
    }

    @Test
    void aRefusalNothingDependedOnIsSkippedAndNoServerAssertIsNoSuite() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        // Every assert parked: the refusal is a WARNING (A1-7), so it is a <skipped>, never a <failure>.
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation parked =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluateRefused(
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.block("{\"kafka\":{\"type\":\"custom\"}}",
                                "{\"id\":\"parked\",\"component\":\"kafka\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},"
                                        + "\"fail\":{\"gt\":1},\"enabled\":false}"),
                        data);
        assertNull(parked.fatalPayloadProblem(), "sanity: not fatal");
        org.w3c.dom.Element suite = suiteNamed(parse(writer.render(data, null, "T", null, parked)), "T - serverMetrics");
        assertNotNull(suite);
        assertEquals("0", suite.getAttribute("failures"));
        assertEquals("1", suite.getAttribute("skipped"));
        String skipped = ((org.w3c.dom.Element) suite.getElementsByTagName("skipped").item(0)).getAttribute("message");
        assertTrue(skipped.contains("Not a configuration error here"), skipped);

        // Components and no asserts, or no block: nothing was declared that could be judged, and the
        // XML is exactly the one a run without server metrics writes - as the header has no chip.
        for (String doc : new String[] {"{\"serverMetrics\":{\"components\":{\"kafka\":{\"type\":\"custom\"}}}}",
                "{\"testNotes\":\"n\"}"}) {
            com.jmeterwebinsightreport.report.gate.ServerGateEvaluation nothing =
                    com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluateRefused(doc, data);
            assertEquals(writer.render(data, null, "T", null), writer.render(data, null, "T", null, nothing), doc);
        }
    }

    @Test
    void onTheInterimReportAnAssertTheFileMakesUnrunnableIsAFailureAsAtStepThree() throws Exception {
        // SF47 / NF4: typo-reducer is INVALID_RULE on the interim report - a CONFIG ERROR, exit 1 -
        // and the XML had no suite to carry it. One <failure> per such assert, with the body step 3
        // writes for it; the payload testcase stays <skipped>, because no payload is not the error.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block("{\"db\":{\"type\":\"custom\"}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("fine", "lag", 500, "db"),
                "{\"id\":\"typo-reducer\",\"component\":\"db\",\"query\":\"lag\",\"reduce\":{\"kind\":\"maxx\"},\"fail\":{\"gt\":1}}");
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation interim =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc, null, data);
        assertTrue(interim.fatalConfigErrors() > 0, "sanity: the interim run exits 1");
        String xml = writer.render(data, null, "T", null, interim);
        org.w3c.dom.Element suite = suiteNamed(parse(xml), "T - serverMetrics");
        assertNotNull(suite, xml);
        assertEquals("2", suite.getAttribute("tests"));
        assertEquals("1", suite.getAttribute("failures"));
        assertEquals("1", suite.getAttribute("skipped"));
        org.w3c.dom.NodeList cases = suite.getElementsByTagName("testcase");
        assertEquals("serverMetrics.payload", ((org.w3c.dom.Element) cases.item(0)).getAttribute("name"));
        org.w3c.dom.Element typo = (org.w3c.dom.Element) cases.item(1);
        assertEquals("typo-reducer", typo.getAttribute("name"));
        org.w3c.dom.Element failure = (org.w3c.dom.Element) typo.getElementsByTagName("failure").item(0);
        assertNotNull(failure, xml);
        assertTrue(failure.getAttribute("message").startsWith("Server assert "), failure.getAttribute("message"));
        assertTrue(failure.getAttribute("message").contains("maxx"), "names what the file wrote: " + failure.getAttribute("message"));

        // ...the same testcase step 3 writes for it, message and body alike.
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation judged =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 0)), data);
        org.w3c.dom.Element db = suiteNamed(parse(writer.render(data, null, "T", null, judged)), "T - db");
        org.w3c.dom.Element atStep3 = null;
        org.w3c.dom.NodeList step3 = db.getElementsByTagName("testcase");
        for (int i = 0; i < step3.getLength(); i++) {
            if ("typo-reducer".equals(((org.w3c.dom.Element) step3.item(i)).getAttribute("name"))) {
                atStep3 = (org.w3c.dom.Element) ((org.w3c.dom.Element) step3.item(i)).getElementsByTagName("failure").item(0);
            }
        }
        assertNotNull(atStep3);
        assertEquals(atStep3.getAttribute("message"), failure.getAttribute("message"));
        assertEquals(atStep3.getTextContent(), failure.getTextContent());
        // No "reduce:    ? across max" (review of SF46): the reducer did not parse, so the body
        // has no reduce line — the Server tab's row has none — and its problem: line says why.
        assertFalse(failure.getTextContent().contains("reduce:"), failure.getTextContent());
        assertTrue(failure.getTextContent().contains("problem:   Server assert "), failure.getTextContent());
    }

    @Test
    void onTheInterimReportTheAssertsOnAComponentOfUnknownTypeAreFailuresAsAtStepThree() throws Exception {
        // Review of SF47: unrunnableResults() leaves out UNKNOWN_TYPE, because the CONFIG ERROR count
        // takes the component once - and the interim suite was built from it, so the run exited 1
        // with "1 problem(s)" while a junit step read 0 failures. Step 3 writes one <failure> per
        // assert for the same file; the interim suite now does too.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block("{\"kafka\":{\"type\":\"kafkaa\"}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag-spike", "lag", 500, "kafka"),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag-high", "lag", 900, "kafka"));
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation interim =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc, null, data);
        assertTrue(interim.fatalConfigErrors() > 0, "sanity: the interim run exits 1");
        assertEquals(1, interim.blockConfigErrors(), "the component of unknown type is still counted once");
        assertTrue(interim.unrunnableResults().isEmpty(), "and still not per assert in that count");

        org.w3c.dom.Element suite = suiteNamed(parse(writer.render(data, null, "T", null, interim)), "T - serverMetrics");
        assertNotNull(suite);
        assertEquals("3", suite.getAttribute("tests"));
        assertEquals("2", suite.getAttribute("failures"));
        assertEquals("1", suite.getAttribute("skipped"));
        java.util.Map<String, org.w3c.dom.Element> interimFailures = failuresByCase(suite);
        assertEquals(java.util.Arrays.asList("lag-spike", "lag-high"), new java.util.ArrayList<>(interimFailures.keySet()));
        for (org.w3c.dom.Element f : interimFailures.values()) {
            assertTrue(f.getAttribute("message").contains("has type 'kafkaa'"), f.getAttribute("message"));
        }

        // ...the testcases step 3 writes for the same file, message and body alike.
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation judged =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 0)), data);
        java.util.Map<String, org.w3c.dom.Element> step3 =
                failuresByCase(suiteNamed(parse(writer.render(data, null, "T", null, judged)), "T - kafka"));
        assertEquals(interimFailures.keySet(), step3.keySet());
        for (String id : step3.keySet()) {
            assertEquals(step3.get(id).getAttribute("message"), interimFailures.get(id).getAttribute("message"), id);
            assertEquals(step3.get(id).getTextContent(), interimFailures.get(id).getTextContent(), id);
        }
    }

    /** Each testcase's {@code <failure>}, by the testcase's name, in document order. */
    private static java.util.Map<String, org.w3c.dom.Element> failuresByCase(org.w3c.dom.Element suite) {
        java.util.Map<String, org.w3c.dom.Element> out = new java.util.LinkedHashMap<>();
        org.w3c.dom.NodeList cases = suite.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) cases.item(i);
            org.w3c.dom.Node f = c.getElementsByTagName("failure").item(0);
            if (f != null) {
                out.put(c.getAttribute("name"), (org.w3c.dom.Element) f);
            }
        }
        return out;
    }
}
