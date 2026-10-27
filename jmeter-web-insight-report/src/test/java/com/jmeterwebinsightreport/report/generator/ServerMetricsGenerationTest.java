package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payload through the whole generator: it is counted by the external-data threshold, it
 * lands in whichever output the threshold chose, and a foreign one is refused as a config
 * problem rather than drawn.
 */
class ServerMetricsGenerationTest {

    @TempDir
    Path dir;

    private static ReportData run() {
        ReportData d = new ReportData();
        List<SamplerStatistics> stats = new ArrayList<>();
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName("GET /a");
        s.setSampleCount(100);
        s.setMeanResponseTime(100);
        s.setMedianResponseTime(90);
        s.setPercentile90(120);
        s.setPercentile95(150);
        s.setPercentile99(200);
        s.setThroughput(5);
        s.setApdexScore(0.9);
        stats.add(s);
        d.setSamplerStatistics(stats);
        TestMetadata m = new TestMetadata();
        m.setTestName("Server metrics generation");
        m.setStartTime(Instant.ofEpochMilli(1_700_000_000_000L));
        m.setEndTime(Instant.ofEpochMilli(1_700_000_060_000L));
        m.setDurationMillis(60_000);
        m.setTotalThreads(10);
        m.setTotalSamples(100);
        d.setMetadata(m);
        return d;
    }

    private File payload(String runId, int points) throws Exception {
        return payload(runId, points, null);
    }

    /** As {@link #payload(String, int)}, collected with {@code wir-collect --rampup} when {@code rampUp} is set. */
    private File payload(String runId, int points, Long rampUp) throws Exception {
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < points; i++) {
            if (i > 0) pts.append(',');
            pts.append('[').append(1_700_000_000_000L + i * 1000L).append(',').append(i).append(']');
        }
        String json = "{\"schema\":\"wir-server-metrics/1\",\"runId\":\"" + runId + "\",\"collectionId\":\"c0ffee\","
                + "\"window\":{\"start\":1700000000000,\"end\":1700000060000"
                + (rampUp == null ? "" : ",\"rampUpEnd\":" + (1_700_000_000_000L + rampUp)) + "},"
                + "\"sources\":[{\"id\":\"metrics\",\"type\":\"prometheus\",\"status\":\"OK\",\"stepMillis\":1000}],"
                + "\"series\":[{\"source\":\"metrics\",\"query\":\"up\",\"resolvedQuery\":\"up\",\"status\":\"OK\",\"coverage\":1.0,"
                + "\"instances\":[{\"labels\":{\"pod\":\"a\"},\"points\":[" + pts + "]}]}]}";
        Path p = dir.resolve("server-metrics-" + runId + (rampUp == null ? "" : "-r" + rampUp) + ".json");
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    private ReportConfiguration config(File payload, long threshold) {
        ReportConfiguration c = new ReportConfiguration();
        c.setOutputDirectory(dir.resolve("out-" + threshold).toFile());
        c.setServerMetricsFile(payload);
        c.setExternalDataThreshold(threshold);
        return c;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * The chart data is inlined into the HTML as an escaped JS string, so a JSON key appears
     * there as {@code \"key\"} — the same form C34 greps for. The sidecar carries raw JSON.
     */
    private static String inHtml(String jsonFragment) {
        return jsonFragment.replace("\"", "\\\"");
    }

    @Test
    void thisRunsPayloadLandsInlineAndIsNotAConfigProblem() throws Exception {
        ReportData data = run();
        ReportArtifacts a = new WebInsightReportGenerator(config(payload(RunIdentity.of(data), 10), 10_000_000L))
                .generateReport(data);

        assertFalse(a.hasConfigProblem(), a.configProblemReason());
        String html = read(a.html());
        assertTrue(html.contains(inHtml("\"serverMetrics\"")), "the block is in the inline payload");
        assertTrue(html.contains(inHtml("\"collectionId\":\"c0ffee\"")));
        assertFalse(a.file(ReportArtifacts.Kind.DATA).exists(), "inline: no sidecar");
    }

    @Test
    void aPayloadPushesAReportOverTheThresholdIntoExternalMode() throws Exception {
        // The threshold measures the chart-data JSON, and the payload is in that JSON — so a
        // mid-size soak with server series flips to the sidecar the way the canvas estimated.
        ReportData data = run();
        long withoutPayload = new com.jmeterwebinsightreport.report.template.ReportDataSerializer()
                .serializeChartData(data, null, null, com.jmeterwebinsightreport.report.gate.GateEvaluation.none(),
                        null, null).length();
        // The same run under the same threshold with no payload stays inline, or "tipped" below
        // could not fail: the baseline must be the page the generator writes, gate included.
        ReportConfiguration control = config(null, withoutPayload + 100);
        control.setOutputDirectory(dir.resolve("out-control").toFile());
        assertFalse(new WebInsightReportGenerator(control).generateReport(data).file(ReportArtifacts.Kind.DATA).exists(),
                "without the payload the report stays inline");
        ReportArtifacts a = new WebInsightReportGenerator(config(payload(RunIdentity.of(data), 2_000), withoutPayload + 100))
                .generateReport(data);

        assertFalse(a.hasConfigProblem(), a.configProblemReason());
        File sidecar = a.file(ReportArtifacts.Kind.DATA);
        assertTrue(sidecar.exists(), "the payload tipped the report into external mode");
        String js = read(sidecar);
        assertTrue(js.contains("\"serverMetrics\""), "and the block is in the sidecar");
        assertTrue(js.contains("\"collectionId\":\"c0ffee\""));
        assertFalse(read(a.html()).contains(inHtml("\"collectionId\":\"c0ffee\"")), "not inline as well");
    }

    @Test
    void anotherRunsPayloadIsRefusedAndRecordedAsAConfigProblem() throws Exception {
        // ONE_KPI has no gates block, so its assert is on the implicit server gate, which builds:
        // the payload was the only way the build's server gate could be judged (A1-7).
        ReportData data = run();
        ReportConfiguration c = config(payload("0123456789abcdef", 10), annotations(ONE_KPI));
        ReportArtifacts a = new WebInsightReportGenerator(c).generateReport(data);

        assertTrue(a.hasConfigProblem(), "a foreign payload is a check the file asked for that never ran");
        assertTrue(a.configProblemReason().contains("belongs to run 0123456789abcdef"), a.configProblemReason());
        assertTrue(a.configProblemReason().contains(RunIdentity.of(data)));
        String html = read(a.html());
        assertFalse(html.contains(inHtml("\"collectionId\":\"c0ffee\"")), "refused means not embedded");
        assertTrue(a.html().exists(), "the report is still written — it is what you need to fix this");
        String cicd = read(a.file(ReportArtifacts.Kind.JSON));
        assertFalse(cicd.contains("Not a configuration error here"), "fatal is not also a warning");
        // The CI/CD JSON says so too. A refused payload leaves no serverStatus, serverGates or
        // serverAsserts, so before this finding the run that exits 1 left no trace in the JSON at
        // all - while the lenient case beside it got a WARNING. The STRICT jq line reads it.
        assertFalse(cicd.contains("\"serverComponents\""), "sanity: nothing was evaluated");
        assertTrue(cicd.contains("\"ruleId\" : \"serverMetrics.payload\""), cicd);
        String finding = cicd.substring(cicd.lastIndexOf("{", cicd.indexOf("\"ruleId\" : \"serverMetrics.payload\"")));
        finding = finding.substring(0, finding.indexOf('}'));
        assertTrue(finding.contains("\"severity\" : \"error\""), finding);
        assertTrue(finding.contains("'server-metrics-0123456789abcdef.json'"), "names the file: " + finding);
        assertFalse(finding.contains(dir.getFileName().toString()), "no absolute path is published: " + finding);
    }

    @Test
    void aMissingConfiguredPayloadIsAConfigProblemAndNothingElseChanges() throws Exception {
        ReportData data = run();
        ReportArtifacts a = new WebInsightReportGenerator(config(dir.resolve("nope.json").toFile(), annotations(ONE_KPI)))
                .generateReport(data);
        assertTrue(a.hasConfigProblem());
        assertTrue(a.configProblemReason().contains("does not exist"), a.configProblemReason());

        ReportArtifacts none = new WebInsightReportGenerator(config(null, 10_000_000L)).generateReport(data);
        assertFalse(none.hasConfigProblem());
        assertFalse(read(none.html()).contains(inHtml("\"serverMetrics\"")), "no payload, no block — unchanged");
        assertEquals(RunIdentity.of(data), RunIdentity.of(data));
    }

    @Test
    void aPayloadThatCannotBeUsedIsAWarningWhenNothingCouldHaveBeenJudged() throws Exception {
        // A1-7, decided lenient (D27's spirit): with no server assert declared - components but no
        // asserts, or no block at all - nothing the payload could have decided reaches the exit code,
        // so a missing or refused payload is a WARNING finding, not a CONFIG ERROR. The page still
        // says it was refused, and why. (Every component builds, C6: an enabled assert makes it fatal.)
        ReportData data = run();
        String componentsOnly = "{\"serverMetrics\":{\"components\":{\"db\":{\"type\":\"custom\"}}}}";
        File[] refused = {dir.resolve("absent.json").toFile(), payload("0123456789abcdef", 10)};
        File[] documents = {annotations(componentsOnly), null};
        for (File payload : refused) {
            for (File doc : documents) {
                String which = (doc == null ? "no server block" : "no asserts") + ", " + payload.getName();
                ReportConfiguration c = doc == null ? config(payload, 10_000_000L) : config(payload, doc);
                c.setOutputDirectory(dir.resolve("lenient-" + payload.getName() + "-" + (doc == null)).toFile());
                ReportArtifacts a = new WebInsightReportGenerator(c).generateReport(data);

                assertFalse(a.hasConfigProblem(), which + ": " + a.configProblemReason());
                assertFalse(a.hasReportableConfigProblem(), which + ": no CONFIG ERROR marker for a warning");
                String cicd = read(a.file(ReportArtifacts.Kind.JSON));
                assertTrue(cicd.contains("\"ruleId\" : \"serverMetrics.payload\""), which + ": " + cicd);
                assertTrue(cicd.contains("\"severity\" : \"warning\""), which + ": " + cicd);
                assertTrue(cicd.contains("Not a configuration error here"), which + ": " + cicd);
                String warning = cicd.substring(cicd.indexOf("\"ruleId\" : \"serverMetrics.payload\""));
                warning = warning.substring(0, warning.indexOf('}'));
                assertTrue(warning.contains("'" + payload.getName() + "'"), which + ": names the file: " + warning);
                assertFalse(warning.contains(dir.getFileName().toString()), which + ": no absolute path is published: " + warning);
                String html = read(a.html());
                assertTrue(html.contains(inHtml("\"serverPayloadRefused\":{\"reason\":\"")),
                        which + ": the page still says the payload was refused");
            }
        }
    }

    @Test
    void theHeaderChipIsAButtonToTheServerTabNamingTheWorstComponent() throws Exception {
        // L3-6 + D24. The chip is rendered by the template from ServerGateEvaluation.headerChip();
        // this pins what reaches the HTML: a button bound (by data-wir-tab, not an inline handler)
        // to the Server tab, coloured and named by the worst component.
        ReportData data = run();
        String building = "{\"serverMetrics\":{\"components\":" + GATES + ",\"asserts\":[{\"id\":\"up-max\","
                + "\"component\":\"kafka\",\"query\":\"up\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":5}}]}}";
        String html = read(new WebInsightReportGenerator(config(payload(RunIdentity.of(data), 10), annotations(building)))
                .generateReport(data).html());
        // Not the first "wir-server-chip" in the file: report.css is inlined above the header.
        int at = html.indexOf("class=\"status-badge wir-server-chip");
        assertTrue(at > 0, "no chip in the header");
        String chip = html.substring(html.lastIndexOf("<button", at));
        chip = chip.substring(0, chip.indexOf("</button>") + 9);
        assertTrue(chip.contains("data-wir-tab=\"server\""), chip);
        assertFalse(chip.contains("onclick"), chip);
        assertTrue(html.contains("badge-fail\" data-wir-tab=\"server\""), "the component's verdict colours it");
        assertTrue(html.contains(">Server · Kafka FAIL · 1 not evaluated</button>"),
                "and names it, and counts the rest by state: db declares no assert, so it judged nothing");
    }

    // ---- the gates, end to end (M4) -------------------------------------------------

    private File annotations(String json) throws Exception {
        Path p = dir.resolve("annotations-" + json.hashCode() + ".json");
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    private ReportConfiguration config(File payload, File annotations) {
        ReportConfiguration c = config(payload, 10_000_000L);
        c.setOutputDirectory(dir.resolve("out-" + (payload == null ? "none" : payload.getName())
                + "-" + annotations.getName()).toFile());
        c.setAnnotationsFile(annotations);
        c.setGenerateJunitXml(true);
        return c;
    }

    /** Two custom components (C1); the name is the one the gates had, as the class keeps its own. */
    private static final String GATES = "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},\"db\":{\"type\":\"custom\",\"description\":\"DB\"}}";

    @Test
    void aDeclaredKpiIsJudgedAgainstThePayloadAndReachesEveryOutput() throws Exception {
        // One assert per component over the `up` series the payload helper writes (values 0..9 -> max 9).
        ReportData data = run();
        String doc = "{\"serverMetrics\":{\"components\":" + GATES + ",\"asserts\":[{\"id\":\"up-max\",\"component\":\"kafka\","
                + "\"query\":\"up\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":5}},{\"id\":\"up-ok\",\"component\":\"db\","
                + "\"query\":\"up\",\"reduce\":{\"kind\":\"min\"},\"fail\":{\"lt\":-1}}]}}";
        ReportArtifacts a = new WebInsightReportGenerator(config(payload(RunIdentity.of(data), 10), annotations(doc)))
                .generateReport(data);

        assertFalse(a.hasConfigProblem(), a.configProblemReason());
        assertTrue(a.serverFailsBuild(), "max 9 > 5 on the building gate");
        assertTrue(a.anyGateFailsBuild());
        assertTrue(a.verdictLine().contains("Server kafka: FAIL — 1 declared, 1 failed"), a.verdictLine());
        assertTrue(a.verdictLine().contains("Server db: PASS"), a.verdictLine());

        String cicd = read(a.file(ReportArtifacts.Kind.JSON));
        assertTrue(cicd.contains("\"serverComponents\""), cicd);
        assertTrue(cicd.contains("\"id\" : \"kafka\""), cicd);
        assertTrue(cicd.contains("\"assertsEvaluated\" : true"), cicd);
        String xml = read(a.file(ReportArtifacts.Kind.XML));
        assertTrue(xml.contains("- kafka\""), xml);
        assertTrue(xml.contains("classname=\"server-metrics\""), xml);
        String html = read(a.html());
        assertTrue(html.contains(inHtml("\"serverGates\"")), "the page gets the gates");
        assertTrue(html.contains(inHtml("\"serverEvaluated\":true")));
        assertFalse(html.contains("NO ASSERTS DECLARED"), "a file declaring only server KPIs has declared asserts");
    }

    @Test
    void withoutAPayloadTheGatesAreNotEvaluatedAndNothingIsFatal() throws Exception {
        ReportData data = run();
        ReportArtifacts a = new WebInsightReportGenerator(config(null, annotations(ONE_KPI))).generateReport(data);

        assertFalse(a.hasConfigProblem(), a.configProblemReason());
        assertFalse(a.anyGateFailsBuild());
        assertTrue(a.verdictLine().contains("Server: NOT COLLECTED — 1 server assert(s) declared, no payload given"), a.verdictLine());
        String cicd = read(a.file(ReportArtifacts.Kind.JSON));
        assertFalse(cicd.contains("serverComponents"), "the interim report carries no server block");
        assertTrue(cicd.contains("\"assertsEvaluated\" : false"), cicd);
        // SF47: JUnit says the server half has not run yet - one serverMetrics suite, its payload
        // testcase skipped, never a failure, since no payload is not an error (D11). It had no
        // server suite at all. And the header says so, in the Summary card's word.
        String xml = read(a.file(ReportArtifacts.Kind.XML));
        assertTrue(xml.contains(" - serverMetrics\" tests=\"1\" failures=\"0\" skipped=\"1\""), xml);
        assertTrue(xml.contains("<skipped message=\"Nothing collected yet: no server-metrics payload was given"), xml);
        assertFalse(xml.contains("<failure"), xml);
        String html = read(a.html());
        assertTrue(html.contains(">Server · NOT COLLECTED</button>"), "the header chip");
        // The chip's state, for a page whose sidecar did not arrive: NOT_COLLECTED is no verdict,
        // and the Summary card must not call it one (review of SF47).
        assertTrue(html.contains("data-wir-chip-state=\"NOT_COLLECTED\""), "the chip carries its state");
    }

    @Test
    void aFileThatStillSaysKpisIsAConfigErrorOnEveryArtifactNotAnEmptyBlock() throws Exception {
        // D25, end to end. Nothing under the old key is read, so the run declares no server
        // asserts — and a report that then said nothing would let a file written for the old name
        // pass every build with its asserts unjudged. It is a CONFIG ERROR (exit 1 under the
        // default failonerror), its sentence names the new key in the CI/CD JSON's findings, and the
        // page carries the same finding for the Server tab.
        ReportData data = run();
        String doc = "{\"serverMetrics\":{\"components\":{\"server\":{\"type\":\"custom\"}},"
                + "\"kpis\":[{\"id\":\"up-max\",\"component\":\"server\",\"query\":\"up\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":5}}]}}";
        for (File payload : new File[] {null, payload(RunIdentity.of(data), 10)}) {
            ReportArtifacts a = new WebInsightReportGenerator(config(payload, annotations(doc))).generateReport(data);
            assertTrue(a.hasConfigProblem(), "payload " + (payload != null) + ": the old key is refused, not ignored");
            String cicd = read(a.file(ReportArtifacts.Kind.JSON));
            assertTrue(cicd.contains("Rename \\\"kpis\\\" to \\\"asserts\\\""), cicd);
            assertTrue(read(a.html()).contains(inHtml("\"serverFindings\"")), "the tab is told too");
        }
    }

    private static final String ONE_KPI = "{\"serverMetrics\":{\"components\":{\"server\":{\"type\":\"custom\"}},"
            + "\"asserts\":[{\"id\":\"up-max\",\"component\":\"server\","
            + "\"query\":\"up\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":5}}]}}";

    @Test
    void aRefusedPayloadReachesThePageAsARefusalWithItsReasonNotAsNoPayload() throws Exception {
        // L9-4 / L3-12. The refusal used to reach the console and the exit code only; the page
        // carried serverEvaluated:false and the tab said "no collected payload was given - run
        // wir-collect", which is false, and whose remedy re-runs the very command whose output
        // was just refused. The likeliest cause is the title trap: -Jwebinsight.report.title
        // without the same --test-name.
        ReportData data = run();
        File annotations = annotations(ONE_KPI);

        ReportConfiguration foreign = config(payload("0123456789abcdef", 10), annotations);
        ReportArtifacts a = new WebInsightReportGenerator(foreign).generateReport(data);
        String html = read(a.html());
        assertTrue(html.contains(inHtml("\"serverPayloadRefused\":{\"reason\":\"FOREIGN_RUN\"")), "the refusal reaches the page");
        assertTrue(html.contains("--test-name must be the same text as -Jwebinsight.report.title"),
                "and names the title / --test-name agreement, the commonest cause");
        assertTrue(html.contains(inHtml("\"serverEvaluated\":false")));
        assertTrue(a.verdictLine().contains("the payload given was refused"), a.verdictLine());
        assertFalse(a.verdictLine().contains("no payload given"), a.verdictLine());

        ReportConfiguration missing = config(dir.resolve("nope.json").toFile(), annotations);
        String missingHtml = read(new WebInsightReportGenerator(missing).generateReport(data).html());
        assertTrue(missingHtml.contains(inHtml("\"serverPayloadRefused\":{\"reason\":\"MISSING\"")), "a missing file is a refusal too");

        // The page names the FILE, not where it lives on this machine: the report is archived and
        // passed around, and an absolute path carries the user's home directory. The console keeps
        // the path, where the person who ran the command needs it.
        String where = dir.getFileName().toString();
        assertFalse(html.contains(where), "no absolute path in the published refusal");
        assertFalse(missingHtml.contains(where), "no absolute path in the published refusal");
        assertTrue(html.contains("'server-metrics-0123456789abcdef.json'"), "the file is named");
        assertTrue(missingHtml.contains("'nope.json'"), "the file is named");
        assertTrue(a.configProblemReason().contains(where), "the console keeps the path: " + a.configProblemReason());

        // An unreadable file is by definition not the collector's redacted output, and the parser
        // quotes the token it choked on - so the page gets a fixed sentence and the console the rest.
        Path garbage = dir.resolve("garbage.json");
        Files.write(garbage, "CANARY-TOKEN is not json".getBytes(StandardCharsets.UTF_8));
        ReportConfiguration unreadable = config(garbage.toFile(), annotations);
        ReportArtifacts u = new WebInsightReportGenerator(unreadable).generateReport(data);
        String unreadableHtml = read(u.html());
        assertTrue(unreadableHtml.contains(inHtml("\"serverPayloadRefused\":{\"reason\":\"UNREADABLE\"")), unreadableHtml.length() + "");
        assertTrue(unreadableHtml.contains("'garbage.json'"), "the file is named");
        assertTrue(unreadableHtml.contains("it is not a JSON payload wir-collect wrote"));
        assertFalse(unreadableHtml.contains("CANARY"), "the parser's quote of the file stays off the page");
        assertFalse(unreadableHtml.contains(where), "no absolute path in the published refusal");
        assertTrue(u.configProblemReason().contains("CANARY"), "the console keeps the parser's reason: "
                + u.configProblemReason());

        // Nothing configured is the interim report, and says nothing about a refusal.
        ReportArtifacts none = new WebInsightReportGenerator(config(null, annotations)).generateReport(data);
        assertFalse(read(none.html()).contains(inHtml("\"serverPayloadRefused\"")),
                "the key, not report.js's own mention of it");
        assertTrue(none.verdictLine().contains("no payload given"), none.verdictLine());
    }

    @Test
    void aPayloadCollectedUnderAnotherRampUpThanTheReportsIsAWarningFinding() throws Exception {
        // L9-5. The judged window is the payload's - rampUpEnd comes from wir-collect --rampup and
        // nothing else - while -Jwebinsight.report.rampup only marks the client charts. Nothing
        // compared the two, so a forgotten --rampup judged the server KPIs over the ramp-up the
        // report's own charts said was excluded.
        File annotations = annotations(ONE_KPI);

        ReportData data = run();
        data.getMetadata().setRampUpDurationMillis(10_000);
        ReportArtifacts forgot = new WebInsightReportGenerator(config(payload(RunIdentity.of(data), 10), annotations))
                .generateReport(data);
        assertFalse(forgot.hasConfigProblem(), "a finding, not a refusal: " + forgot.configProblemReason());
        String cicd = read(forgot.file(ReportArtifacts.Kind.JSON));
        assertTrue(cicd.contains("\"severity\" : \"warning\""), cicd);
        assertTrue(cicd.contains("collected with a ramp-up of 0 ms (wir-collect --rampup not given), but this "
                + "report's webinsight.report.rampup is 10000 ms"), cicd);
        assertTrue(read(forgot.html()).contains("webinsight.report.rampup is 10000 ms"), "and the page's findings carry it");

        // Agreeing on the number says nothing.
        ReportData agreed = run();
        agreed.getMetadata().setRampUpDurationMillis(10_000);
        ReportConfiguration same = config(payload(RunIdentity.of(agreed), 10, 10_000L), annotations);
        same.setOutputDirectory(dir.resolve("agreed").toFile());
        ReportArtifacts ok = new WebInsightReportGenerator(same).generateReport(agreed);
        assertFalse(read(ok.file(ReportArtifacts.Kind.JSON)).contains("ramp-up"), "the same ramp-up on both sides");

        // ...and neither does a block that judges the whole window anyway.
        String keepRampUp = ONE_KPI.replace("\"asserts\"", "\"collection\":{\"excludeRampUp\":false},\"asserts\"");
        ReportConfiguration kept = config(payload(RunIdentity.of(data), 10), annotations(keepRampUp));
        ReportArtifacts k = new WebInsightReportGenerator(kept).generateReport(data);
        assertFalse(read(k.file(ReportArtifacts.Kind.JSON)).contains("ramp-up"),
                "with excludeRampUp false the payload's ramp-up judges nothing");
    }

    @Test
    void noCredentialShapeReachesAnyArtifactInlineOrExternal() throws Exception {
        // D10's canary end to end, widened by L9-2 to every shape the mapper refuses as a
        // credential. The literal auth value was already masked; userinfo in a source URL and a
        // source-level secret-looking key were not, and the refusal that names them is loud
        // (CONFIG ERROR, exit 1) while the HTML carrying them is still written and archived.
        ReportData data = run();
        String doc = "{\"serverMetrics\":{\"sources\":{"
                + "\"p\":{\"type\":\"prometheus\",\"url\":\"https://p/api/v1\",\"step\":\"1s\"},"
                + "\"lit\":{\"type\":\"prometheus\",\"url\":\"https://p/api/v1\",\"auth\":{\"bearerToken\":\"CANARY-AUTH\"}},"
                + "\"ui\":{\"type\":\"prometheus\",\"url\":\"https://alice:CANARY-USERINFO@p/api/v1\"},"
                + "\"tok\":{\"type\":\"prometheus\",\"url\":\"https://p/api/v1\",\"token\":\"CANARY-TOKEN\"},"
                + "\"qp\":{\"type\":\"prometheus\",\"url\":\"https://p/api/v1?api_key=CANARY-QUERY\"},"
                + "\"env\":{\"type\":\"prometheus\",\"url\":\"${env.PROM}\"}},"
                + "\"environments\":{\"prod\":{\"vars\":{\"PROM\":\"https://bob:CANARY-ENV@p/api/v1\"}}},"
                + "\"components\":{\"server\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"up-max\",\"component\":\"server\",\"query\":\"up\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":5}}]}}";
        File annotations = annotations(doc);
        File payload = payload(RunIdentity.of(data), 10);

        for (long threshold : new long[] {10_000_000L, 1L}) {
            ReportConfiguration c = config(payload, threshold);
            c.setOutputDirectory(dir.resolve("canary-" + threshold).toFile());
            c.setAnnotationsFile(annotations);
            c.setGenerateJunitXml(true);
            ReportArtifacts a = new WebInsightReportGenerator(c).generateReport(data);

            assertTrue(a.hasConfigProblem(), "the retired sources block and the var are a config problem");
            assertEquals(threshold == 1L, a.file(ReportArtifacts.Kind.DATA).exists(),
                    "both payload forms are exercised: inline, then the sidecar");
            for (ReportArtifacts.Kind kind : ReportArtifacts.Kind.values()) {
                File f = a.file(kind);
                if (f != null && f.exists()) {
                    String text = read(f);
                    assertFalse(text.contains("CANARY"), kind + " carries a credential: " + f);
                }
            }
            String carrier = threshold == 1L ? read(a.file(ReportArtifacts.Kind.DATA)) : read(a.html());
            // "***@p" alone: the inline form escapes the slashes around it.
            assertTrue(carrier.contains("***@p"), "the URL travels with its userinfo masked");
        }
    }

    @Test
    void aSourceThatNeverAnsweredIsFatalOnEveryComponent() throws Exception {
        ReportData data = run();
        String runId = RunIdentity.of(data);
        String failed = "{\"schema\":\"wir-server-metrics/1\",\"runId\":\"" + runId + "\",\"collectionId\":\"c0ffee\","
                + "\"window\":{\"start\":1700000000000,\"end\":1700000060000},"
                + "\"sources\":[{\"id\":\"metrics\",\"type\":\"prometheus\",\"status\":\"FAILED\",\"stepMillis\":1000,\"error\":\"HTTP 503\"}],"
                + "\"series\":[{\"source\":\"metrics\",\"query\":\"up\",\"resolvedQuery\":\"up\",\"status\":\"FAILED\",\"error\":\"HTTP 503 from https://p\",\"instances\":[]}]}";
        Path p = dir.resolve("failed-" + runId + ".json");
        Files.write(p, failed.getBytes(StandardCharsets.UTF_8));
        for (String component : new String[] {"kafka", "db"}) {
            String doc = "{\"serverMetrics\":{\"components\":" + GATES + ",\"asserts\":[{\"id\":\"up-max\",\"component\":\""
                    + component + "\",\"query\":\"up\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":5}}]}}";
            ReportArtifacts fatal = new WebInsightReportGenerator(config(p.toFile(), annotations(doc))).generateReport(data);
            assertTrue(fatal.hasConfigProblem(), component);
            assertTrue(fatal.configProblemReason().contains(component + ": 1 "), fatal.configProblemReason());
            assertFalse(fatal.anyGateFailsBuild());
            // SF23: the series could not be collected, and the detail says why - never "because the
            // source never answered", which was false beside a PARTIAL source.
            assertTrue(read(fatal.file(ReportArtifacts.Kind.XML)).contains("<failure message=\"Server assert could not be "
                    + "collected: HTTP 503 from https://p\""), component + ": a <failure> on every component");
        }
    }
}
