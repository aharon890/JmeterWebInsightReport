package com.jmeterwebinsightreport.report.gate;

import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.servermetrics.KpiResult;
import org.junit.jupiter.api.Test;

import static com.jmeterwebinsightreport.report.gate.ServerFixtures.block;
import static com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate;
import static com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluateRefused;
import static com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi;
import static com.jmeterwebinsightreport.report.gate.ServerFixtures.ok;
import static com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson;
import static com.jmeterwebinsightreport.report.gate.ServerFixtures.run;
import static com.jmeterwebinsightreport.report.gate.ServerFixtures.series;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server components as one evaluation: every declared component gets a summary, the asserts
 * that belong to none are the block's, and the fold answers failonerror and failonassert without
 * a rollup. Every component builds (plans/server-metrics-components.md C6), so there is no
 * observe-only half left to test here.
 */
class ServerGateEvaluationTest {

    private static final String THREE = "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},"
            + "\"db\":{\"type\":\"custom\",\"description\":\"DB\"},\"idle\":{\"type\":\"custom\",\"description\":\"Idle\"}}";

    /** A raw assert on {@code component}, for the shapes kpi() does not build. */
    private static String raw(String id, String component, String rest) {
        return "{\"id\":\"" + id + "\",\"component\":\"" + component + "\",\"query\":\"lag\"," + rest + "}";
    }

    @Test
    void noBlockIsNothingDeclared() throws Exception {
        ServerGateEvaluation e = evaluate("{\"testNotes\":\"n\"}", null, run());
        assertFalse(e.declared());
        assertFalse(e.evaluated());
        assertTrue(e.gates().isEmpty());
        assertEquals(0, e.fatalConfigErrors());
        assertFalse(e.failsBuild());
        assertNull(e.fatalConfigReason());
        assertEquals(0, ServerGateEvaluation.none().fatalConfigErrors());
    }

    @Test
    void declaredWithoutAPayloadIsNotEvaluatedAndNotAnError() throws Exception {
        ServerGateEvaluation e = evaluate(block(null, kpi("lag", "lag", 500, null)), null, run());
        assertTrue(e.declared());
        assertFalse(e.evaluated());
        assertTrue(e.gates().isEmpty());
        assertTrue(e.results().isEmpty());
        assertEquals(0, e.fatalConfigErrors(), "no payload is not a config error");
        assertFalse(e.failsBuild());
    }

    @Test
    void theBlocksOwnFaultsCountWithoutAPayload() throws Exception {
        // A retired sources block is wrong whether or not anything was collected.
        String retired = "{\"serverMetrics\":{\"sources\":{\"prom\":{\"type\":\"prometheus\",\"url\":\"u\"}},"
                + "\"components\":{\"server\":{\"type\":\"custom\"}},\"asserts\":[" + kpi("lag", "lag", 500, null) + "]}}";
        ServerGateEvaluation e = evaluate(retired, null, run());
        assertFalse(e.evaluated());
        assertEquals(1, e.blockConfigErrors());
        assertEquals(1, e.fatalConfigErrors());
        // Named by the key the file has it under (SF22): "block" named nothing the file says, and
        // was also the console's word for the asserts that named no declared component.
        assertTrue(e.fatalConfigReason().contains("serverMetrics: 1 problem"), e.fatalConfigReason());
    }

    @Test
    void aFileThatStillSaysKpisIsFatalRatherThanReadAsDeclaringNothing() throws Exception {
        String oldName = "{\"serverMetrics\":{\"components\":{\"server\":{\"type\":\"custom\"}},"
                + "\"kpis\":[" + kpi("lag", "lag", 500, null) + "]}}";
        for (String payload : new String[] {null, payloadJson("r", ok("lag", 100, 700))}) {
            ServerGateEvaluation e = evaluate(oldName, payload, run());
            assertFalse(e.declared(), "nothing under kpis is read");
            assertEquals(1, e.blockConfigErrors(), "payload " + (payload != null));
            assertEquals(1, e.fatalConfigErrors(), "payload " + (payload != null));
            assertTrue(e.findings().get(0).message().contains("Rename \"kpis\" to \"asserts\""),
                    e.findings().toString());
        }
    }

    @Test
    void anUnrunnableKpiIsFatalEvenOnTheInterimReportWithNoPayload() throws Exception {
        String broken = block(null, raw("typo", "server", "\"reduce\":{\"kind\":\"nope\"},\"fail\":{\"gt\":1}"));
        ServerGateEvaluation e = evaluate(broken, null, run());
        assertTrue(e.declared());
        assertFalse(e.evaluated(), "no payload: nothing was judged");
        assertEquals(1, e.blockConfigErrors());
        assertEquals(1, e.fatalConfigErrors(), "a file defect is fatal whether or not anything was collected");
        // Under the component it is declared in, as step 3 counts it (SF22) - not "block".
        assertTrue(e.fatalConfigReason().contains("server: 1 invalid"), e.fatalConfigReason());

        ServerGateEvaluation load = evaluate(block(null, raw("needs", "server",
                "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"requiresLoad\":\"load:nope\"")), null, run());
        assertFalse(load.evaluated());
        assertEquals(1, load.fatalConfigErrors(), "requiresLoad: 'load:nope' names no client rule at step 1 too");

        ServerGateEvaluation parked = evaluate(block(null, raw("off", "server",
                "\"reduce\":{\"kind\":\"nope\"},\"fail\":{\"gt\":1},\"enabled\":false")), null, run());
        assertEquals(0, parked.fatalConfigErrors(), "disabled: the file is not asking for it");
    }

    @Test
    void anUnknownTypeIsCountedOnceAtTheComponentNotOncePerAssert() throws Exception {
        String doc = block("{\"ora\":{\"type\":\"nosuchdb\"}}",
                kpi("a", "lag", 500, "ora"), kpi("b", "lag", 500, "ora"));
        ServerGateEvaluation e = evaluate(doc, null, run());
        assertEquals(1, e.blockConfigErrors(), "one typo'd type, one problem: " + e.findings());
    }

    @Test
    void aLoadReferenceThatNamesNoUsableRuleIsFatalOnTheInterimReportAsAtStepThree() throws Exception {
        String client = "\"assertions\":[{\"id\":\"load:throughput\",\"target\":{\"scope\":\"overall\"},"
                + "\"metric\":\"throughput\",\"fail\":{\"lt\":0.001}},"
                + "{\"id\":\"latency\",\"target\":{\"scope\":\"overall\"},\"metric\":\"p95\",\"fail\":{\"gt\":99999}}],";
        String[] broken = {
            raw("needs-typo", "server", "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"requiresLoad\":\"load:nope\""),
            raw("needs-latency", "server", "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"requiresLoad\":\"latency\""),
            raw("per-typo", "server", "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"divideBy\":\"nope\"")
        };
        for (String kpi : broken) {
            String doc = block(null, kpi).replaceFirst("\\{", "{" + client);
            ServerGateEvaluation step1 = evaluate(doc, null, run());
            ServerGateEvaluation step3 = evaluate(doc, payloadJson("r", ok("lag", 1)), run());
            String id = step3.results().get(0).kpi().id();

            assertEquals(NotJudged.INVALID_RULE, step3.results().get(0).notJudged(), id + " at step 3");
            assertEquals(1, step3.fatalConfigErrors(), id + " is fatal at step 3");
            assertFalse(step1.evaluated());
            assertEquals(step3.fatalConfigErrors(), step1.fatalConfigErrors(),
                    id + ": step 1 and step 3 must agree about the file");
            String said = step3.results().get(0).notJudgedDetail();
            boolean found = false;
            for (AssertionValidator.Finding f : step1.findings()) {
                found |= f.severity() == AssertionValidator.Severity.ERROR && f.ruleId().equals(id)
                        && f.message().equals(said);
            }
            assertTrue(found, id + ": no step-1 finding saying '" + said + "' in " + step1.findings());
        }

        String fine = block(null, raw("needs-load", "server",
                "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"requiresLoad\":\"load:throughput\""))
                .replaceFirst("\\{", "{" + client);
        assertEquals(0, evaluate(fine, null, run()).fatalConfigErrors());
    }

    @Test
    void anOrphanThatIsMerelyDisabledIsNotACountedConfigError() throws Exception {
        String doc = block(THREE,
                raw("parked", "nosuch", "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"enabled\":false"),
                kpi("real", "lag", 1, "kafka"));
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 0)), run());
        assertEquals(1, e.orphans().size(), "the parked assert names a component the file never declared");
        assertEquals(NotJudged.DISABLED, e.orphans().get(0).notJudged());
        assertEquals(0, e.blockConfigErrors(), "DISABLED is not a config error, orphan or not");
        assertEquals(0, e.fatalConfigErrors());
    }

    @Test
    void theHeaderChipNamesTheWorstComponentAndCountsTheRest() throws Exception {
        String doc = block(THREE,
                kpi("k", "lag", 1, "kafka"),
                raw("d", "db", "\"reduce\":{\"kind\":\"max\"},\"warn\":{\"gt\":1},\"fail\":{\"gt\":9999}"));
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100)), run());
        ServerGateEvaluation.Chip chip = e.headerChip();
        assertNotNull(chip);
        assertEquals("kafka", chip.getGateId());
        assertEquals("Kafka", chip.getLabel());
        assertEquals("FAIL", chip.getStatus());
        assertEquals("badge-fail", chip.getBadgeClass());
        assertEquals(2, chip.getOthers(), "db and idle are counted, not named");
    }

    @Test
    void theHeaderChipSurvivesAComponentThatEvaluatedNothing() throws Exception {
        String doc = block("{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"}}",
                raw("parked", "kafka", "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"enabled\":false"));
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100)), run());
        ServerGateEvaluation.Chip chip = e.headerChip();
        assertNotNull(chip);
        assertEquals("kafka", chip.getGateId());
        assertEquals("NOT_EVALUATED", chip.getStatus());
        assertEquals("NOT EVALUATED", chip.getStatusLabel(), "spelled for a reader");
        assertEquals("badge-none", chip.getBadgeClass(), "no colour for a component with no verdict");
    }

    @Test
    void aComponentThatJudgedNothingOutranksASiblingsPass() throws Exception {
        String two = "{\"api\":{\"type\":\"custom\",\"description\":\"API\"},\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"}}";
        String doc = block(two, kpi("ok", "lag", 500, "api"), kpi("dead", "down", 1, "kafka"));
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100),
                series("down", "FAILED", null, "HTTP 503", new double[0])), run());

        assertEquals(AssertionStatus.PASS, e.gates().get("api").status());
        assertNull(e.gates().get("kafka").status(), "sanity: the dead component decided nothing");
        assertTrue(e.fatalConfigErrors() > 0, "sanity: and it exits 1 under failonerror");
        ServerGateEvaluation.Chip chip = e.headerChip();
        assertEquals("kafka", chip.getGateId(), "the component that decided nothing, not api's PASS");
        assertEquals("Server · Kafka NOT EVALUATED · 1 more", chip.getText());
        assertEquals("NOT_EVALUATED", e.serverStatus());
    }

    @Test
    void theFoldRanksFailThenNotEvaluatedThenWarnThenPass() throws Exception {
        String warnKpi = raw("w", "b", "\"reduce\":{\"kind\":\"max\"},\"warn\":{\"gt\":1},\"fail\":{\"gt\":9999}");
        String[][] cases = {
            {kpi("x", "lag", 9999, "a"), warnKpi, "WARN"},
            {warnKpi.replace("\"component\":\"b\"", "\"component\":\"a\""), kpi("x", "down", 1, "b"), "NOT_EVALUATED"},
            {kpi("x", "down", 1, "a"), kpi("y", "lag", 1, "b"), "FAIL"},
        };
        for (String[] c : cases) {
            String doc = block("{\"a\":{\"type\":\"custom\"},\"b\":{\"type\":\"custom\"}}", c[0], c[1]);
            ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100),
                    series("down", "FAILED", null, "HTTP 503", new double[0])), run());
            assertEquals(c[2], e.serverStatus(), c[0] + " vs " + c[1]);
            assertEquals("b", e.headerChip().getGateId(), c[2] + " is b's");
            assertEquals(c[2], e.headerChip().getStatus());
            assertEquals(1, e.headerChip().getOthers());
        }
        assertNull(evaluate(block(null, kpi("lag", "lag", 500, null)), null, run()).serverStatus());
        assertNull(ServerGateEvaluation.none().serverStatus());
    }

    @Test
    void assertsInNoDeclaredComponentAreReportedAsOneMoreBuildingGate() throws Exception {
        String doc = block("{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"}}",
                kpi("lag", "lag", 500, "kafka"), kpi("typo", "lag", 500, "kafak"));
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100)), run());
        assertEquals("[kafka]", e.gates().keySet().toString(), "the declared components are untouched");
        assertEquals("[kafka, (no gate)]", e.reportedGates().keySet().toString());
        assertTrue(e.builds(ServerGateEvaluation.NO_GATE_ID));
        assertEquals(1, e.reportedGates().get(ServerGateEvaluation.NO_GATE_ID).invalidRule());
        assertEquals("NOT_EVALUATED", e.serverStatus(), "kafka's PASS does not speak for a refused assert");
        // The wire keeps its id; a reader sees the word the Server tab's card uses (SF22).
        assertEquals(ServerGateEvaluation.NO_GATE_ID, e.headerChip().getGateId());
        assertEquals("(no component)", e.headerChip().getLabel());

        ServerGateEvaluation clean = evaluate(block("{\"kafka\":{\"type\":\"custom\"}}", kpi("lag", "lag", 500, "kafka")),
                payloadJson("r", ok("lag", 100)), run());
        assertEquals("[kafka]", clean.reportedGates().keySet().toString(), "no orphan, no entry");
    }

    /** The one finding a refused payload is reported under, or null — and never two. */
    private static AssertionValidator.Finding payloadFinding(ServerGateEvaluation e) {
        AssertionValidator.Finding found = null;
        for (AssertionValidator.Finding f : e.findings()) {
            if (f.ruleId().equals(ServerGateEvaluation.PAYLOAD_FINDING_ID)) {
                assertNull(found, "one refused payload, one finding: " + e.findings());
                found = f;
            }
        }
        return found;
    }

    @Test
    void aRefusedPayloadIsFatalWheneverAnEnabledAssertWasDeclared() throws Exception {
        // Every component builds (C6), so any enabled assert makes a refused payload fatal.
        String one = block(null, kpi("lag", "lag", 500, null));
        String two = block("{\"kafka\":{\"type\":\"custom\"},\"db\":{\"type\":\"custom\"}}",
                kpi("lag", "lag", 500, "kafka"), kpi("pool", "lag", 500, "db"));
        for (String doc : new String[] {one, two}) {
            ServerGateEvaluation e = ServerFixtures.evaluateRefused(doc, run());
            assertNotNull(e.payloadRefusal());
            assertNotNull(e.fatalPayloadProblem(), doc);
            assertTrue(e.fatalPayloadProblem().contains("does not exist"), e.fatalPayloadProblem());
            AssertionValidator.Finding error = payloadFinding(e);
            assertNotNull(error, "an ERROR finding: " + doc + " " + e.findings());
            assertEquals(AssertionValidator.Severity.ERROR, error.severity());
            assertTrue(error.message().startsWith("the server-metrics payload does not exist: 'wir-absent-"),
                    "the published sentence - the file's name, not its path: " + error.message());
            assertFalse(error.message().contains(System.getProperty("java.io.tmpdir")), error.message());
        }

        // Lenient: nothing the payload could have decided — only a parked assert, components with
        // no assert at all, or no server block.
        String parked = block("{\"kafka\":{\"type\":\"custom\"}}",
                raw("parked", "kafka", "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},\"enabled\":false"));
        String componentsOnly = "{\"serverMetrics\":{\"components\":{\"kafka\":{\"type\":\"custom\"}}}}";
        String noBlock = "{\"testNotes\":\"n\"}";
        for (String doc : new String[] {parked, componentsOnly, noBlock}) {
            ServerGateEvaluation e = ServerFixtures.evaluateRefused(doc, run());
            assertNotNull(e.payloadRefusal(), "the page still says it was refused: " + doc);
            assertNull(e.fatalPayloadProblem(), doc);
            assertEquals(0, e.fatalConfigErrors(), doc);
            AssertionValidator.Finding warning = payloadFinding(e);
            assertNotNull(warning, "a WARNING finding instead: " + doc + " " + e.findings());
            assertEquals(AssertionValidator.Severity.WARNING, warning.severity());
        }
    }

    @Test
    void theChipCountsTheStripsBadgesAndNeverTheReservedGate() throws Exception {
        String two = "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},\"db\":{\"type\":\"custom\",\"description\":\"DB\"}}";
        String typo = kpi("typo", "lag", 500, "kafak");

        ServerGateEvaluation named = evaluate(block(two, kpi("k", "lag", 1, "kafka"), kpi("d", "lag", 500, "db"), typo),
                payloadJson("r", ok("lag", 100)), run());
        assertEquals("[kafka, db, (no gate)]", named.reportedGates().keySet().toString(), "sanity: an orphan");
        assertEquals("kafka", named.headerChip().getGateId());
        assertEquals(1, named.headerChip().getOthers(), "db only - the strip has no (no gate) badge");
        assertEquals("Server · Kafka FAIL · 1 more", named.headerChip().getText());

        ServerGateEvaluation reserved = evaluate(block(two, kpi("k", "lag", 500, "kafka"), kpi("d", "lag", 500, "db"), typo),
                payloadJson("r", ok("lag", 100)), run());
        assertEquals(ServerGateEvaluation.NO_GATE_ID, reserved.headerChip().getGateId());
        assertEquals(2, reserved.headerChip().getOthers(), "kafka and db");
        assertEquals("Server · (no component) NOT EVALUATED · 2 more", reserved.headerChip().getText());
    }

    @Test
    void theAssertsInNoDeclaredComponentHaveOneNameForAReaderOnEverySurface() throws Exception {
        // SF22: "(no gate)" in the chip, "(no component)" on the Server tab, "block" on the console.
        String typo = kpi("typo", "lag", 500, "kafak");
        String doc = block("{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"}}", kpi("k", "lag", 500, "kafka"), typo);

        ServerGateEvaluation evaluated = evaluate(doc, payloadJson("r", ok("lag", 100)), run());
        assertEquals("1 server check(s) never ran ((no component): 1 invalid)", evaluated.fatalConfigReason());
        assertEquals("(no component)", evaluated.headerChip().getLabel());
        assertEquals("(no component)", ServerGateEvaluation.displayId(ServerGateEvaluation.NO_GATE_ID));
        assertEquals("kafka", ServerGateEvaluation.displayId("kafka"), "a declared id is its own name");

        // The interim report counts it there too, and an unrunnable assert in a declared component
        // under that component: there are no summaries yet, and "block" was every one of them.
        String broken = raw("broken", "kafka", "\"reduce\":{\"kind\":\"nope\"},\"fail\":{\"gt\":1}");
        ServerGateEvaluation interim = evaluate(block("{\"kafka\":{\"type\":\"custom\"}}", broken, typo), null, run());
        assertFalse(interim.evaluated());
        assertEquals("2 server check(s) never ran (kafka: 1 invalid; (no component): 1 invalid)",
                interim.fatalConfigReason());
        assertEquals(2, interim.blockConfigErrors(), "the count is unchanged; only the words are");
    }

    @Test
    void theInterimAndRefusedLogLinesNameTheServerAsTheConsoleDoesNeverAGate() throws Exception {
        // SF22 review: jmeter.log said "Server gates: NOT EVALUATED - ..." on every interim and refused
        // run, beside logSummary's "Server component <id>" and the console verdict line's "Server:
        // NOT EVALUATED" - one log speaking both words, "gate" being one nothing a reader sees uses.
        String doc = block("{\"kafka\":{\"type\":\"custom\"}}", kpi("k", "lag", 500, "kafka"));
        java.util.List<String> logged = new java.util.ArrayList<>();
        org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger)
                org.apache.logging.log4j.LogManager.getLogger(ServerGateEvaluation.class.getName());
        org.apache.logging.log4j.core.appender.AbstractAppender capture =
                new org.apache.logging.log4j.core.appender.AbstractAppender("sf22-capture", null, null, true,
                        org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
                    @Override
                    public void append(org.apache.logging.log4j.core.LogEvent event) {
                        logged.add(event.getMessage().getFormattedMessage());
                    }
                };
        capture.start();
        org.apache.logging.log4j.Level was = logger.getLevel();
        logger.addAppender(capture);
        logger.setLevel(org.apache.logging.log4j.Level.INFO);
        try {
            evaluate(doc, null, run());
            evaluateRefused(doc, run());
        } finally {
            logger.removeAppender(capture);
            logger.setLevel(was);
            capture.stop();
        }
        // Review of SF47: the state is named in the header chip's word for it — NOT COLLECTED on
        // the interim report, REFUSED over a refused payload — as the console verdict line does.
        // "NOT EVALUATED" here sat beside a chip and a Summary card reading NOT COLLECTED.
        java.util.List<String> unjudged = new java.util.ArrayList<>();
        for (String line : logged) {
            assertFalse(line.contains("Server gates"), "a log line still says gates: " + line);
            assertFalse(line.startsWith("Server: NOT EVALUATED"), "the state is the chip's word now: " + line);
            if (line.startsWith("Server: ")) {
                unjudged.add(line);
            }
        }
        assertEquals(2, unjudged.size(), "one line per run: " + logged);
        assertTrue(unjudged.get(0).startsWith("Server: NOT COLLECTED - 1 server assert(s) declared, no payload given"),
                unjudged.get(0));
        assertTrue(unjudged.get(1).startsWith("Server: REFUSED - 1 server assert(s) declared, and the payload given was refused"),
                unjudged.get(1));
    }

    /** The findings about one assert, under either of its names. */
    private static java.util.List<AssertionValidator.Finding> findingsAbout(ServerGateEvaluation e, String... names) {
        java.util.List<String> wanted = java.util.Arrays.asList(names);
        java.util.List<AssertionValidator.Finding> about = new java.util.ArrayList<>();
        for (AssertionValidator.Finding f : e.findings()) {
            if (wanted.contains(f.ruleId())) {
                about.add(f);
            }
        }
        return about;
    }

    @Test
    void aCatalogueAssertOnAnUndeclaredComponentIsOneFindingNeverAlsoNoQuery() throws Exception {
        // SF21. The metric brings the query from the component's type, so with no component it
        // could not resolve - and KpiUsability added "no query - the server assert has nothing to
        // collect" beside the sentence that names the typo. The evaluator's refusal, filed under
        // the derived id, then made a third: the mapper had filed the same sentence under the
        // assert's position.
        String doc = block("{\"orders-db\":{\"type\":\"postgres\"}}",
                "{\"component\":\"orders-dbb\",\"metric\":\"connections.used.pct\",\"fail\":{\"gt\":90}}",
                "{\"id\":\"conn\",\"component\":\"orders-dbb\",\"metric\":\"connections.used.pct\",\"fail\":{\"gt\":90}}");
        String component = "component 'orders-dbb' is not declared under serverMetrics.components (declared: orders-db)";
        for (String payload : new String[] {null, payloadJson("r", ok("lag", 1))}) {
            ServerGateEvaluation e = evaluate(doc, payload, run());
            String when = payload == null ? "at step 1" : "at step 3";
            java.util.List<AssertionValidator.Finding> idless =
                    findingsAbout(e, "serverMetrics.asserts[0]", "orders-dbb-connections-used-pct");
            assertEquals(1, idless.size(), when + ", one mistake, one finding: " + e.findings());
            assertEquals("serverMetrics.asserts[0]", idless.get(0).ruleId(), "under the label the page keys F15 on");
            assertEquals(component, idless.get(0).message());
            java.util.List<AssertionValidator.Finding> named = findingsAbout(e, "conn");
            assertEquals(1, named.size(), when + ": " + e.findings());
            assertEquals(component, named.get(0).message());
            for (AssertionValidator.Finding f : e.findings()) {
                assertFalse(f.message().contains("no query"), when + ": " + f);
            }
            if (payload != null) {
                assertEquals(NotJudged.INVALID_RULE, e.results().get(0).notJudged(), "still refused");
                assertEquals(component, e.results().get(0).notJudgedDetail());
            }
        }
    }

    @Test
    void anIdLessAssertsRefusalIsSaidOnceUnderItsPositionNotAgainUnderItsDerivedId() throws Exception {
        // SF21's second half, for any reason the mapper and the evaluator both say: the dedupe is on
        // the assert, under either of its names, not on the id alone. The derived-id twin was the one
        // a Delete on the page could not hide (F15 keys on the written id or the position).
        String doc = block("{\"db\":{\"type\":\"custom\"}}", kpi("lag", "lag", 500, "db"),
                "{\"component\":\"db\",\"query\":\"lag\",\"reduce\":{\"kind\":\"nope\"},\"fail\":{\"gt\":1}}");
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100)), run());
        assertEquals(NotJudged.INVALID_RULE, e.results().get(1).notJudged());
        assertEquals("db-lag", e.results().get(1).kpi().id(), "sanity: the derived id");
        assertEquals("serverMetrics.asserts[1]", e.results().get(1).kpi().declarationLabel());
        assertEquals("lag", e.results().get(0).kpi().declarationLabel(), "a written id is its own label");
        java.util.List<AssertionValidator.Finding> about = findingsAbout(e, "serverMetrics.asserts[1]", "db-lag");
        assertEquals(1, about.size(), e.findings().toString());
        assertEquals("serverMetrics.asserts[1]", about.get(0).ruleId());
    }

    @Test
    void declaredAndJudgedAgainstNothingTheChipSaysWhichStateAsTheSummaryCardDoes() throws Exception {
        // SF47 (Q7). It returned null on both, by decision: "declared, never collected: the header
        // says nothing about the server side". The Summary card beside it said REFUSED or NOT
        // COLLECTED, and on a refused run the header was silent about the CONFIG ERROR the run
        // exits 1 over. The card's two words, in the neutral style - neither is a verdict.
        String doc = block(null, kpi("lag", "lag", 500, null));

        ServerGateEvaluation interim = evaluate(doc, null, run());
        ServerGateEvaluation.Chip waiting = interim.headerChip();
        assertNotNull(waiting, "the interim report: declared, nothing collected yet");
        assertEquals("Server · NOT COLLECTED", waiting.getText());
        assertEquals(ServerGateEvaluation.Chip.NOT_COLLECTED, waiting.getStatus());
        assertEquals("badge-none", waiting.getBadgeClass());
        assertNull(waiting.getGateId(), "no component was judged, so none is named");
        assertTrue(waiting.getTitle().contains("Nothing has been collected"), waiting.getTitle());

        ServerGateEvaluation refused = evaluateRefused(doc, run());
        ServerGateEvaluation.Chip chip = refused.headerChip();
        assertNotNull(chip, "a refused payload is a CONFIG ERROR here, and the header said nothing");
        assertEquals("Server · REFUSED", chip.getText());
        assertEquals("badge-none", chip.getBadgeClass());
        assertTrue(chip.getTitle().contains("was refused"), chip.getTitle());

        // Words, not verdicts: serverStatus stays absent on both, as serverComponents[] does.
        assertNull(interim.serverStatus());
        assertNull(refused.serverStatus());

        // A run that declared no server assert still makes no claim at all - refused payload or not.
        assertNull(ServerGateEvaluation.none().headerChip());
        assertNull(evaluateRefused("{\"serverMetrics\":{\"components\":{\"kafka\":{\"type\":\"custom\"}}}}", run())
                .headerChip(), "components and no asserts: nothing was declared that could be judged");
        assertNull(evaluate("{\"testNotes\":\"n\"}", null, run()).headerChip());
    }

    @Test
    void withNoPayloadTheUnrunnableAssertsAreTheOnesCountedEachWithStepThreesSentence() throws Exception {
        // SF47: JUnit writes one <failure> per unrunnable assert on the interim report, so the set
        // it reads must be the one blockConfigErrors counts, in declaration order, and each must
        // say what step 3 says about the same assert.
        String typoReducer = raw("typo-reducer", "kafka", "\"reduce\":{\"kind\":\"maxx\"},\"fail\":{\"gt\":1}");
        String noLoadRule = raw("needs-load", "kafka", "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1},"
                + "\"requiresLoad\":\"load:nope\"");
        String doc = block("{\"kafka\":{\"type\":\"custom\"}}", noLoadRule, kpi("fine", "lag", 1, "kafka"), typoReducer);

        ServerGateEvaluation interim = evaluate(doc, null, run());
        java.util.List<KpiResult> unrunnable = interim.unrunnableResults();
        assertEquals(2, unrunnable.size(), unrunnable.toString());
        assertEquals("needs-load", unrunnable.get(0).kpi().id(), "declaration order");
        assertEquals("typo-reducer", unrunnable.get(1).kpi().id());
        assertEquals(interim.blockConfigErrors(), unrunnable.size(), "the same set the CONFIG ERROR counts");

        ServerGateEvaluation judged = evaluate(doc, payloadJson("r", ok("lag", 0)), run());
        assertTrue(judged.unrunnableResults().isEmpty(), "once judged, they sit in their component");
        for (KpiResult r : unrunnable) {
            KpiResult atStep3 = null;
            for (KpiResult j : judged.results()) {
                if (j.kpi().id().equals(r.kpi().id())) atStep3 = j;
            }
            assertNotNull(atStep3, r.kpi().id());
            assertEquals(NotJudged.INVALID_RULE, r.notJudged());
            assertEquals(atStep3.notJudged(), r.notJudged(), r.kpi().id());
            assertEquals(atStep3.notJudgedDetail(), r.notJudgedDetail(), r.kpi().id() + ": step 1 and step 3 agree");
        }
    }

    @Test
    void everyDeclaredComponentGetsASummaryEmptyOnesIncluded() throws Exception {
        String doc = block(THREE, kpi("lag", "lag", 500, "kafka"), kpi("conn", "conn", 10, "db"));
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100, 700), ok("conn", 3, 4)), run());

        assertTrue(e.evaluated());
        assertEquals("[kafka, db, idle]", e.gates().keySet().toString(), "declaration order, empty one included");
        assertEquals(AssertionStatus.FAIL, e.gates().get("kafka").status());
        assertEquals(AssertionStatus.PASS, e.gates().get("db").status());
        GateSummary idle = e.gates().get("idle");
        assertNull(idle.status(), "a component whose asserts all vanished reads NOT_EVALUATED, not absent");
        assertEquals(0, idle.declared());
        assertEquals("c0ffee", e.collectionId());
        assertEquals(2, e.results().size());
        assertTrue(e.results().get(0) instanceof KpiResult);
    }

    @Test
    void anAssertNamingNoDeclaredComponentIsTheBlocksAndAlwaysFatal() throws Exception {
        String doc = block(THREE,
                kpi("lag", "lag", 500, "kafka"),
                "{\"id\":\"unnamed\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":500}}",
                kpi("typo", "lag", 500, "kafak"));
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100)), run());

        assertEquals(1, e.gates().get("kafka").declared(), "only the assert that named a declared component");
        assertEquals(2, e.blockConfigErrors(), "the unnamed and the misspelt one belong to no component");
        assertEquals(2, e.fatalConfigErrors());
        assertEquals(NotJudged.INVALID_RULE, e.results().get(1).notJudged());
        assertEquals(NotJudged.INVALID_RULE, e.results().get(2).notJudged());
    }

    @Test
    void collectionFailuresAndInvalidRulesAreFatalOnEveryComponent() throws Exception {
        String doc = block(THREE,
                kpi("k-failed", "down", 1, "kafka"),
                kpi("k-sparse", "sparse", 1, "kafka"),
                raw("d-broken", "db", "\"reduce\":{\"kind\":\"nope\"},\"fail\":{\"gt\":1}"));
        String payload = payloadJson("r",
                series("down", "FAILED", null, "HTTP 503 from https://prom.corp", new double[0]),
                series("sparse", "INSUFFICIENT", 0.4, null, 1, 2),
                ok("lag", 1));
        ServerGateEvaluation e = evaluate(doc, payload, run());

        assertEquals(2, e.gates().get("kafka").configErrors(), "kafka: SOURCE_FAILED + LOW_COVERAGE");
        assertEquals(1, e.gates().get("db").configErrors(), "db: INVALID_RULE");
        assertEquals(3, e.fatalConfigErrors(), "every component builds");
        String reason = e.fatalConfigReason();
        assertTrue(reason.startsWith("3 server check(s) never ran"), reason);
        assertTrue(reason.contains("kafka: 1 source failed, 1 low coverage"), reason);
        assertTrue(reason.contains("db: 1 invalid"), reason);
    }

    @Test
    void failsBuildIsAFailOnAnyComponent() throws Exception {
        String doc = block(THREE, kpi("k", "lag", 500, "kafka"), kpi("d", "lag", 50, "db"));
        ServerGateEvaluation dbFails = evaluate(doc, payloadJson("r", ok("lag", 100)), run());
        assertEquals(AssertionStatus.FAIL, dbFails.gates().get("db").status(), "db FAILED at 100 > 50");
        assertTrue(dbFails.failsBuild(), "every component builds");
        assertEquals("[db]", dbFails.failingGateIds().toString());

        ServerGateEvaluation bothFail = evaluate(doc, payloadJson("r", ok("lag", 700)), run());
        assertEquals("[kafka, db]", bothFail.failingGateIds().toString());
    }

    @Test
    void usabilityFindingsAreReportedOnce() throws Exception {
        String doc = block(null,
                raw("no-bands", "server", "\"reduce\":{\"kind\":\"max\"}"),
                raw("dead-warn", "server", "\"reduce\":{\"kind\":\"max\"},\"warn\":{\"gt\":900},\"fail\":{\"gt\":500}"));
        ServerGateEvaluation e = evaluate(doc, payloadJson("r", ok("lag", 100)), run());

        assertEquals("[server]", e.gates().keySet().toString());
        assertFalse(e.failsBuild());
        assertEquals(1, e.fatalConfigErrors(), "no-bands is INVALID_RULE");
        int noBands = 0, deadWarn = 0;
        for (AssertionValidator.Finding f : e.findings()) {
            if (f.ruleId().equals("no-bands")) noBands++;
            if (f.ruleId().equals("dead-warn")) deadWarn++;
        }
        assertEquals(1, noBands, "one finding for the missing bands: " + e.findings());
        assertEquals(1, deadWarn, "one WARNING for the dead warn band: " + e.findings());
        assertNotNull(e.payload());
    }
}
