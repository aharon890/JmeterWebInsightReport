package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.assertion.AssertionEvaluator;
import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionTarget;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The operator-facing text is asserted here, as plain Strings, because it cannot be asserted
 * through a logging framework: there is no slf4j binding on the test classpath, so a logger
 * in a unit test is a no-op. Building the messages in {@link ReportArtifacts} rather than at
 * the call sites is what makes this file possible without adding a log-capture dependency.
 */
class ReportArtifactsTest {

    @TempDir
    Path outputDir;

    private ReportArtifacts inlineRun() {
        return new ReportArtifacts(outputDir.toFile(), "r.html", "r.json", null,
                "web-insight-data.js", false, 10_000_000L);
    }

    private ReportArtifacts externalRun() {
        return new ReportArtifacts(outputDir.toFile(), "r.html", "r.json", "r.xml",
                "r-data.js", true, 10_000_000L);
    }

    // ---- client and server config problems, two flags -------------------------

    @Test
    void aServerProblemIsReportedApartFromTheClientAndSaysTheExitCodeIsUnchanged() {
        ReportArtifacts a = inlineRun().recordServerMetricsProblem("the payload was refused");
        assertTrue(a.hasServerConfigProblem());
        assertFalse(a.hasClientConfigProblem());
        assertNull(a.clientConfigMarker(), "no CONFIG ERROR for a side that cannot exit non-zero");
        String lenient = a.serverConfigMarker(false);
        assertTrue(lenient.startsWith("WEB-INSIGHT-REPORT: SERVER NOT JUDGED — the payload was refused"), lenient);
        assertTrue(lenient.contains("exit code is unchanged"), lenient);
        assertTrue(lenient.contains(NonZeroExit.FAIL_ON_SERVER_ERROR + "=true"), lenient);
        // Under the opt-in it exits non-zero, so it is a CONFIG ERROR like the client's.
        assertEquals("WEB-INSIGHT-REPORT: CONFIG ERROR — the payload was refused", a.serverConfigMarker(true));
    }

    @Test
    void aClientProblemKeepsItsConfigErrorAndCarriesNoServerClause() {
        ReportArtifacts a = inlineRun().recordConfigProblem(2, null);
        assertTrue(a.hasClientConfigProblem());
        assertFalse(a.hasServerConfigProblem());
        assertTrue(a.clientConfigMarker().startsWith("WEB-INSIGHT-REPORT: CONFIG ERROR — 2 declared check(s) never ran"));
        assertNull(a.serverConfigMarker(false));
        assertEquals(a.clientConfigProblemReason(), a.configProblemReason());
    }

    @Test
    void bothSidesWrongNameBothInTheCombinedReason() {
        ReportArtifacts a = inlineRun().recordConfigProblem(0, "the annotations file does not exist")
                .recordServerMetricsProblem("the payload was refused");
        assertEquals("the annotations file does not exist — no gate ran; the payload was refused",
                a.configProblemReason());
        assertTrue(a.hasConfigProblem());
    }

    // ---- intent line ---------------------------------------------------------

    @Test
    void intentLineNamesEveryArtifactAndAnAbsoluteDirectory() {
        String line = inlineRun().intentLine();

        assertTrue(line.startsWith("Web Insight Report: outputDir="), line);
        // Absolute, not the configured string: a relative -Jwebinsight.report.output resolves
        // against the process working directory, and the exporter's own fallback is a
        // directory inside the JMeter installation, so the configured value under-informs.
        assertTrue(line.contains("outputDir=" + outputDir.toFile().getAbsolutePath()), line);
        assertTrue(line.contains(" html=r.html"), line);
        assertTrue(line.contains(" json=r.json"), line);
    }

    @Test
    void intentLineSaysDisabledRatherThanOmittingTheXml() {
        // Omitting the field would be indistinguishable from a bug that dropped it.
        assertTrue(inlineRun().intentLine().contains(" xml=disabled"));
        assertTrue(externalRun().intentLine().contains(" xml=r.xml"));
    }

    @Test
    void intentLineAdmitsThatInlineVsExternalIsNotYetDecided() {
        // The threshold is checked against a payload that has not been serialized when this
        // line is emitted, so the line must not claim inline as a settled fact.
        String inline = inlineRun().intentLine();
        assertTrue(inline.contains("data=inline (external:web-insight-data.js above 10000000 chars)"),
                inline);

        // Forced mode IS settled up front.
        assertTrue(externalRun().intentLine().contains("data=external:r-data.js"));
    }

    // ---- console summary -----------------------------------------------------

    @Test
    void summaryNamesEveryArtifactThatWasWritten() {
        ReportArtifacts a = externalRun()
                .written(ReportArtifacts.Kind.HTML)
                .written(ReportArtifacts.Kind.JSON)
                .written(ReportArtifacts.Kind.XML)
                .written(ReportArtifacts.Kind.DATA);

        String summary = a.consoleSummary();
        // The verdict leads. Nothing that reached a terminal used to carry it at all, so a
        // failing gate printed four file paths and exited 0.
        assertTrue(summary.startsWith("Asserts: "), summary);
        assertTrue(summary.contains("Web Insight Report generated:"), summary);
        for (ReportArtifacts.Kind kind : ReportArtifacts.Kind.values()) {
            assertTrue(summary.contains(a.file(kind).getAbsolutePath()),
                    "summary omits " + kind + ": " + summary);
        }
        assertFalse(a.anyFailed());
    }

    // ---- the verdict line ----------------------------------------------------

    /**
     * No gate recorded at all — generation never reached the judging step, or nothing was
     * declared. Either way the line must state that rather than say nothing, because silence on
     * this channel is what made "the gate stopped gating" invisible.
     */
    @Test
    void verdictLineStatesThatNothingWasJudged() {
        String line = inlineRun().verdictLine();
        assertEquals("Asserts: NOT EVALUATED — nothing was declared, so nothing was judged", line);
        assertTrue(inlineRun().consoleSummary().startsWith(line));
    }

    @Test
    void verdictLineNamesTheStatusAndTheNonZeroCounts() {
        ReportArtifacts a = inlineRun();
        a.recordGate(evaluate(banded("ok", 100_000), banded("bad", 1)));

        String line = a.verdictLine();
        assertTrue(line.startsWith("Asserts: FAIL — 2 declared"), line);
        assertTrue(line.contains("1 passed"), line);
        assertTrue(line.contains("1 failed"), line);
        // Zero counts are omitted: a two-rule gate should not read as a wall of zeroes.
        assertFalse(line.contains("0 "), line);
    }

    /**
     * The full text, not contains(): the reasons are printed in NotJudged.displayOrder(), and
     * the one time that order was derived from the enum's wire order instead, "disabled" moved
     * ahead of "unmatched" on every run that had both and nothing here noticed. The client
     * reasons must read exactly as the console printed them before server metrics existed.
     */
    @Test
    void verdictLinePrintsTheReasonsInDisplayOrder() {
        ReportArtifacts a = inlineRun();
        a.recordGate(evaluate(
                AssertionRule.builder(AssertionTarget.sampler("A"), "p95").id("parked")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).enabled(false).build(),
                AssertionRule.builder(AssertionTarget.sampler("Nope"), "p95").id("ghost")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).build(),
                AssertionRule.builder(AssertionTarget.sampler("A"), "nope").id("broken")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).build()));

        assertEquals("Asserts: NOT EVALUATED — 3 declared, 1 invalid, 1 unmatched, 1 disabled",
                a.verdictLine());
    }

    /** A gate whose only rule judged nothing decided nothing, and must not report a pass. */
    @Test
    void verdictLineDoesNotClaimAPassForAGateThatDecidedNothing() {
        ReportArtifacts a = inlineRun();
        // Targets a sampler the run does not contain: unmatched, so SKIPPED, so no verdict.
        a.recordGate(evaluate(AssertionRule.builder(AssertionTarget.sampler("Nope"), "p95")
                .id("watched").fail(Predicate.of(Predicate.Op.GT, 1)).build()));

        String line = a.verdictLine();
        assertTrue(line.contains("NOT EVALUATED"), line);
        assertFalse(line.contains("PASS"), line);
    }

    /* Through the real evaluator rather than hand-built results: RuleResult.of is
       package-private, and widening it so a console-text test can reach it would trade a
       production invariant for test convenience. This also means the counts under assertion
       are the ones the engine actually produces. */
    private static GateSummary evaluate(AssertionRule... rules) {
        SamplerStatistics stat = new SamplerStatistics();
        stat.setSamplerName("A");
        stat.setSampleCount(10);
        stat.setPercentile95(50);
        ReportData data = new ReportData();
        data.setSamplerStatistics(java.util.Collections.singletonList(stat));
        return new AssertionEvaluator().evaluate(java.util.Arrays.asList(rules), data);
    }

    private static AssertionRule banded(String id, double failAbove) {
        return AssertionRule.builder(AssertionTarget.sampler("A"), "p95")
                .id(id).fail(Predicate.of(Predicate.Op.GT, failAbove)).build();
    }

    @Test
    void summaryShowsAFailedJsonInsteadOfClaimingSuccess() {
        // The defect this exists for: the JSON write is swallowed by design, so the console
        // reported unqualified success while the file a pipeline gates on was absent.
        ReportArtifacts a = inlineRun()
                .written(ReportArtifacts.Kind.HTML)
                .failed(ReportArtifacts.Kind.JSON, new FileSystemException(
                        outputDir.resolve("r.json").toString(), null, "Is a directory"));

        String summary = a.consoleSummary();
        assertTrue(summary.contains("JSON : FAILED — Is a directory: "), summary);
        assertTrue(summary.contains(a.file(ReportArtifacts.Kind.HTML).getAbsolutePath()), summary);
        assertTrue(a.anyFailed());
    }

    @Test
    void summaryDistinguishesDisabledFromInlineFromMissing() {
        ReportArtifacts a = inlineRun().written(ReportArtifacts.Kind.HTML);

        String summary = a.consoleSummary();
        assertTrue(summary.contains("XML  : disabled"), summary);   // never planned
        assertTrue(summary.contains("DATA : inline"), summary);      // planned, deliberately unwritten
        assertTrue(summary.contains("JSON : not written"), summary); // planned and then nothing happened
    }

    @Test
    void aSizeTriggeredPromotionToExternalIsReported() {
        ReportArtifacts a = inlineRun().written(ReportArtifacts.Kind.HTML);
        assertTrue(a.consoleSummary().contains("DATA : inline"));

        a.externalDataWritten();
        assertTrue(a.consoleSummary().contains(
                a.file(ReportArtifacts.Kind.DATA).getAbsolutePath()));
    }

    // ---- markers -------------------------------------------------------------

    @Test
    void successMarkerIsOneGreppableLineWithAnAbsolutePath() {
        String marker = inlineRun().written(ReportArtifacts.Kind.HTML).successMarker();
        assertEquals("WEB-INSIGHT-REPORT: OK " + outputDir.resolve("r.html").toFile().getAbsolutePath(),
                marker);
        assertFalse(marker.contains("\n"), "the marker must stay one line: " + marker);
    }

    @Test
    void failureLineAlwaysNamesTheExceptionClass() {
        String line = ReportArtifacts.failureLine(new IOException("output directory is not a directory: D:\\out\\blocked"));
        assertTrue(line.startsWith("WEB-INSIGHT-REPORT: FAILED output directory is not a directory: D:\\out\\blocked"),
                line);
        assertTrue(line.contains("(java.io.IOException)"), line);
    }

    @Test
    void failureLineUnwrapsTheRootCause() {
        String line = ReportArtifacts.failureLine(
                new RuntimeException("wrapped", new IllegalStateException("the real reason")));
        assertTrue(line.contains("caused by java.lang.IllegalStateException: the real reason"), line);
    }

    @Test
    void failureLineSurvivesANullAndAMessagelessThrowable() {
        assertTrue(ReportArtifacts.failureLine(null).contains("unknown error"));
        String line = ReportArtifacts.failureLine(new NullPointerException());
        assertTrue(line.contains("NullPointerException"), line);
    }

    @Test
    void failureLineDoesNotSpinOnACyclicCauseChain() {
        // An error path is the worst place for an infinite loop, and a self-referential
        // cause is legal Java.
        IOException a = new IOException("a");
        IOException b = new IOException("b", a);
        a.initCause(b);
        assertTrue(ReportArtifacts.failureLine(b).startsWith("WEB-INSIGHT-REPORT: FAILED b"));
    }

    // ---- diagnose, don't echo ------------------------------------------------

    @Test
    void nioFileExceptionsGetASentenceInsteadOfABarePath() {
        // getMessage() on all three of these returns the filename and nothing else, which is
        // how "your output directory is a file" surfaced as an unadorned path naming the
        // report rather than the directory.
        assertEquals("no such file or directory: D:\\out\\blocked\\r.html",
                ReportArtifacts.describe(new NoSuchFileException("D:\\out\\blocked\\r.html")));
        assertEquals("access denied: D:\\out\\r.json",
                ReportArtifacts.describe(new AccessDeniedException("D:\\out\\r.json")));
        assertEquals("Is a directory: D:\\out\\r.xml",
                ReportArtifacts.describe(new FileSystemException("D:\\out\\r.xml", null, "Is a directory")));
        assertEquals("file system error: D:\\out\\r.xml",
                ReportArtifacts.describe(new FileSystemException("D:\\out\\r.xml")));
    }

    @Test
    void anInvalidPathIsNamedAsSuch() {
        // Construct the exception rather than provoking the filesystem into throwing it.
        // This used to be assertThrows(..., () -> new File("D:\\out\\rep:ort.html").toPath()),
        // which asserts a property of the PLATFORM, not of describe(): a colon inside a path
        // segment is illegal on Windows and an ordinary filename character on Linux, so the
        // test passed here and failed on the first CI run it ever saw. What it means to pin —
        // that describe() gives an InvalidPathException a sentence instead of a bare path —
        // is unchanged and is now pinned everywhere.
        InvalidPathException e = new InvalidPathException("D:\\out\\rep:ort.html",
                "Illegal char <:> at index 6");
        assertTrue(ReportArtifacts.describe(e).startsWith("invalid path: "),
                ReportArtifacts.describe(e));
    }

    // ---- resolution ----------------------------------------------------------

    @Test
    void aNullOutputDirectoryResolvesAgainstTheWorkingDirectory() {
        // The listener documents the working directory as its default, and
        // new File((File) null, name) is what implements that.
        ReportArtifacts a = new ReportArtifacts(null, "r.html", "r.json", null,
                "web-insight-data.js", false, 10L);
        assertEquals(new File(System.getProperty("user.dir")).getAbsolutePath(),
                a.outputDirectory().getPath());
        assertEquals("r.html", a.html().getName());
    }

    @Test
    void anArtifactThatWasNeverPlannedHasNoFile() {
        assertNull(inlineRun().file(ReportArtifacts.Kind.XML));
        assertNull(inlineRun().filename(ReportArtifacts.Kind.XML));
        // ...and marking it is a no-op rather than an NPE, so a caller does not have to
        // branch on whether JUnit XML was enabled.
        assertFalse(inlineRun().written(ReportArtifacts.Kind.XML).anyFailed());
    }

    // ---- what a failed run tells the exit code ------------------------------------

    /**
     * The sentence that fails the run, now that an incomplete set exits non-zero by default.
     *
     * <p>The JSON and XML writers swallow their exceptions by design — a report is still worth
     * having when its CI/CD sibling fails — so by the time anyone asks whether the run should go
     * red, the original exception is long gone. Naming the artifact is the whole value:
     * "the JUnit XML could not be written" is actionable, and "something failed, see the log" is
     * the behaviour being removed.
     */
    @Test
    void anIncompleteSetExplainsItselfByName() {
        ReportArtifacts a = externalRun()
                .written(ReportArtifacts.Kind.HTML)
                .failed(ReportArtifacts.Kind.JSON, new AccessDeniedException("/out/r.json"))
                .failed(ReportArtifacts.Kind.XML, new IOException("disk full"));

        String reason = a.incompleteReason();
        assertNotNull(reason);
        assertTrue(reason.contains("r.json"), reason);
        assertTrue(reason.contains("r.xml"), reason);
        assertTrue(reason.contains("access denied"), "the cause, not just the name: " + reason);
        assertTrue(reason.contains("disk full"), reason);
        assertTrue(reason.contains(outputDir.toFile().getAbsolutePath()),
                "and where it was trying to write: " + reason);
    }

    @Test
    void aCompleteSetHasNothingToExplain() {
        assertNull(inlineRun()
                .written(ReportArtifacts.Kind.HTML)
                .written(ReportArtifacts.Kind.JSON)
                .incompleteReason(),
                "null is the signal that the run is fine — an empty string would read as a reason");
    }

    // ---- turning a published file back into an outcome ---------------------------

    @Test
    void aFileIsRecognisedAsTheArtifactItIs() {
        ReportArtifacts a = externalRun();
        assertEquals(ReportArtifacts.Kind.HTML, a.kindOf(a.file(ReportArtifacts.Kind.HTML)));
        assertEquals(ReportArtifacts.Kind.JSON, a.kindOf(a.file(ReportArtifacts.Kind.JSON)));
        assertEquals(ReportArtifacts.Kind.XML, a.kindOf(a.file(ReportArtifacts.Kind.XML)));
        assertEquals(ReportArtifacts.Kind.DATA, a.kindOf(a.file(ReportArtifacts.Kind.DATA)));
        assertNull(a.kindOf(new File(outputDir.toFile(), "someone-elses.html")));
        assertNull(a.kindOf(null));
    }

    // ---- the config-problem sentence -------------------------------------------

    @Test
    void theConfigProblemReasonNamesEveryProblemNotTheFirst() {
        // A run with one broken client rule AND a foreign payload used to print the rule count
        // and never mention the payload — on exactly the runs with two things wrong, and the
        // payload sentence has no finding to be seen anywhere else.
        String payload = "the server-metrics payload /x/sm.json belongs to run aaaa, and this is run bbbb";
        String both = inlineRun().recordConfigProblem(2, null).recordServerMetricsProblem(payload).configProblemReason();
        assertTrue(both.startsWith("2 declared check(s) never ran"), both);
        assertTrue(both.endsWith("; " + payload), both);

        assertEquals(payload, inlineRun().recordServerMetricsProblem(payload).configProblemReason(),
                "alone, the payload sentence is the whole reason");
        String load = inlineRun().recordConfigProblem(0, "annotations.json could not be read")
                .recordServerMetricsProblem(payload).configProblemReason();
        assertTrue(load.startsWith("annotations.json could not be read — no gate ran; "), load);
        assertTrue(load.endsWith(payload), load);
        assertTrue(inlineRun().recordServerMetricsProblem(payload).hasConfigProblem());
        assertFalse(inlineRun().recordServerMetricsProblem(null).hasConfigProblem());
    }

    // ---- the server gates in the fold (M4) ---------------------------------------

    private static com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server(String gates, String payload,
                                                                                        String... kpis) throws Exception {
        return com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(
                com.jmeterwebinsightreport.report.gate.ServerFixtures.block(gates, kpis), payload,
                com.jmeterwebinsightreport.report.gate.ServerFixtures.run());
    }

    private static final String GATES = "{\"kafka\":{\"type\":\"custom\"},\"db\":{\"type\":\"custom\"}}";

    @Test
    void aComponentsFailIsWhatFailonassertActsOn() throws Exception {
        // Every component builds (C6): a FAIL on either is a verdict failonassert acts on.
        String lagHigh = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 700));
        for (String component : new String[] {"kafka", "db"}) {
            ReportArtifacts a = inlineRun().recordServerGates(server(GATES, lagHigh,
                    com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("k", "lag", 500, component)));
            assertTrue(a.serverFailsBuild(), component);
            assertTrue(a.anyGateFailsBuild(), component);
            assertFalse(a.hasConfigProblem(), "a FAIL is a verdict, not a config problem");
            assertTrue(a.verdictLine().contains("; Server " + component + ": FAIL — 1 declared, 1 failed"), a.verdictLine());
            assertFalse(a.verdictLine().contains("observe-only"), a.verdictLine());
        }
    }

    @Test
    void aCollectionFailureIsFatalOnAnyComponent() throws Exception {
        String down = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.series("down", "FAILED", null, "HTTP 503", new double[0]));
        for (String component : new String[] {"kafka", "db"}) {
            ReportArtifacts a = inlineRun().recordServerGates(server(GATES, down,
                    com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("k", "down", 1, component)));
            assertTrue(a.hasConfigProblem(), component);
            assertTrue(a.configProblemReason().contains("1 server check(s) never ran (" + component + ": 1 source failed"),
                    a.configProblemReason());
            assertFalse(a.anyGateFailsBuild(), "NOT_JUDGED is never a FAIL");
        }
    }

    @Test
    void anInvalidKpiIsFatal() throws Exception {
        String lag = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100));
        ReportArtifacts a = inlineRun().recordServerGates(server(GATES, lag,
                "{\"id\":\"broken\",\"component\":\"db\",\"query\":\"lag\",\"reduce\":{\"kind\":\"nope\"},\"fail\":{\"gt\":1}}"));
        assertTrue(a.hasConfigProblem(), "INVALID_RULE is a file defect: always fatal");
        assertTrue(a.configProblemReason().contains("db: 1 invalid"), a.configProblemReason());
    }

    @Test
    void theVerdictLineNamesTheAssertsInNoDeclaredComponentAsTheServerTabDoes() throws Exception {
        // SF22. The line walked the DECLARED components, so the asserts that named none - which
        // exit 1 - had no clause, and a file declaring no component at all said nothing about the
        // server side. They are the reserved entry, named for a reader as the tab names its card.
        String lag = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100));
        String typo = com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("typo", "lag", 500, "kafak");
        ReportArtifacts beside = inlineRun().recordServerGates(server(GATES, lag,
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("k", "lag", 500, "kafka"), typo));
        assertTrue(beside.verdictLine().endsWith("; Server kafka: PASS — 1 declared, 1 passed; Server db: NOT EVALUATED"
                + " — 0 declared; Server (no component): NOT EVALUATED — 1 declared, 1 invalid"), beside.verdictLine());
        assertTrue(beside.configProblemReason().contains("1 server check(s) never ran ((no component): 1 invalid)"),
                beside.configProblemReason());

        ReportArtifacts none = inlineRun().recordServerGates(server("{}", lag, typo));
        assertTrue(none.verdictLine().endsWith("; Server (no component): NOT EVALUATED — 1 declared, 1 invalid"),
                "no component declared still says what the server side did: " + none.verdictLine());
        for (ReportArtifacts a : new ReportArtifacts[] {beside, none}) {
            assertFalse(a.verdictLine().contains("(no gate)"), "the wire id is not a reader's word: " + a.verdictLine());
            assertFalse(a.configProblemReason().contains("block"), a.configProblemReason());
        }
    }

    @Test
    void theVerdictLineSaysWhenTheServerBlockWasNotEvaluated() throws Exception {
        // In the header chip's word for the state (review of SF47): the chip and the Summary card
        // read NOT COLLECTED on the interim report, and the console said NOT EVALUATED.
        ReportArtifacts a = inlineRun().recordServerGates(server(null, null,
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("k", "lag", 500, null)));
        assertTrue(a.verdictLine().endsWith("; Server: NOT COLLECTED — 1 server assert(s) declared, no payload given"), a.verdictLine());
        assertFalse(a.hasConfigProblem());
        assertFalse(a.anyGateFailsBuild());

        // ...and REFUSED over a refused payload, as its chip reads.
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation refused =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluateRefused(
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("k", "lag", 500, null)),
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.run());
        assertEquals("Server · REFUSED", refused.headerChip().getText());
        String line = inlineRun().recordServerGates(refused).verdictLine();
        assertTrue(line.endsWith("; Server: REFUSED — 1 server assert(s) declared, the payload given was refused"), line);
    }
}
