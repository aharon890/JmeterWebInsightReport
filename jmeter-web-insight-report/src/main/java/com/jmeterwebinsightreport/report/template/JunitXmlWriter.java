package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.Digits;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.RuleResult;
import com.jmeterwebinsightreport.core.assertion.SamplerVerdicts;
import com.jmeterwebinsightreport.core.servermetrics.KpiResult;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.report.gate.ServerGateEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes JUnit XML output from report data and the assert gate.
 * Each top-level sampler becomes a test case; a breached assert becomes a test failure.
 * With nothing declared, every test case passes.
 */
public class JunitXmlWriter {

    private static final Logger log = LoggerFactory.getLogger(JunitXmlWriter.class);

    /**
     * The suite that collects the KPIs belonging to no declared gate. Reserved as a gate id by
     * {@code ServerMetricsMapper}, alongside {@code assertions} — the client rule suite's name —
     * so no declared gate can produce a second {@code <testsuite>} with either name. The same id
     * the CI/CD JSON's {@code serverGates[]} reports them under.
     */
    static final String NO_GATE_SUITE = ServerGateEvaluation.NO_GATE_ID;

    /**
     * The suite of a run whose server asserts were declared and judged against nothing — the
     * interim report, or a refused payload (SF47). After the block, {@code serverMetrics}: it
     * exists only when no component suite does, so it cannot collide with one.
     */
    static final String UNJUDGED_SUITE = com.jmeterwebinsightreport.report.annotation.ServerMetricsMapper.BLOCK_ID;

    /**
     * Write JUnit XML report to the given output file.
     *
     * @param reportData  the aggregated report data
     * @param assertResults  the gate's verdict per sampler (null or empty when nothing was declared)
     * @param testName    the test name for the top-level testsuites element
     * @param outputFile  the target XML file
     * @throws IOException if writing fails
     */
    public void write(ReportData reportData, SamplerVerdicts assertResults,
                      String testName, File outputFile) throws IOException {
        write(reportData, assertResults, testName, outputFile, null);
    }

    // NOTE: neither `write` overload has a production caller — report generation goes through
    // `render` and `ArtifactPublication.stage`, so the XML is staged with the rest of the set and
    // published with it. These remain only because the golden and gate-output tests write a
    // document to a temp file and read it back, which is a legitimate use and not worth
    // reshaping. Do not reach for them from generation code: a direct write publishes one
    // artifact on its own, which is exactly the mixed-set outcome the publication exists to
    // prevent.

    /**
     * @param gate the declared rules, or null. Adds a <em>second</em> testsuite rather than
     *        reshaping the first.
     *
     *        <p>One testcase per rule is the right shape for a rule engine. The existing
     *        one-testcase-per-sampler suite is what every pipeline consuming this file already
     *        parses — including samplers with no threshold, which appear as passing tests, and
     *        one combined failure body per sampler. Replacing it would change {@code tests=},
     *        every testcase name and every failure message at once, with no opt-out, breaking
     *        anyone whose CI trends on those names. Appending gives rules first-class testcases
     *        and leaves the existing contract untouched — which the golden fixtures then keep
     *        honest.
     */
    public void write(ReportData reportData, SamplerVerdicts assertResults,
                      String testName, File outputFile, GateSummary gate) throws IOException {
        com.jmeterwebinsightreport.core.util.AtomicFileWriter.write(outputFile,
                render(reportData, assertResults, testName, gate));
        log.info("JUnit XML written to: {}", outputFile.getAbsolutePath());
    }

    /**
     * The same document, as a String.
     *
     * <p>Separated from the write so report generation can stage all four artifacts before
     * publishing any of them — see {@code ArtifactPublication}. The whole file was already built
     * in memory before it touched the disk, so this costs nothing.
     */
    public String render(ReportData reportData, SamplerVerdicts assertResults,
                         String testName, GateSummary gate) {
        return render(reportData, assertResults, testName, gate, null);
    }

    /**
     * @param server the server gates, or null. One <em>more</em> testsuite per evaluated gate,
     *        named {@code <testName> - <gateId>} ({@code assertions} stays the client's), with
     *        {@code classname="server-metrics"} on every testcase — or, when server asserts were
     *        declared and nothing was judged, one {@code <testName> - serverMetrics} suite saying
     *        why (SF47). The root's {@code tests} and
     *        {@code failures} keep counting the sampler suite only — that is the contract every
     *        pipeline consuming this file already parses (D8).
     */
    public String render(ReportData reportData, SamplerVerdicts assertResults,
                         String testName, GateSummary gate, ServerGateEvaluation server) {

        // Filter to top-level samplers only (exclude transaction controller children)
        List<SamplerStatistics> topLevelSamplers = reportData.getSamplerStatistics().stream()
                .filter(s -> s.getParentSamplerName() == null || s.getParentSamplerName().isEmpty())
                .collect(Collectors.toList());

        int totalTests = topLevelSamplers.size();
        int totalFailures = 0;

        if (assertResults != null) {
            for (SamplerStatistics stat : topLevelSamplers) {
                SamplerVerdicts.Verdict result = assertResults.get(stat.getSamplerName());
                if (result != null && result.getOverallStatus() == AssertionStatus.FAIL) {
                    totalFailures++;
                }
            }
        }

        double totalTimeSeconds = reportData.getMetadata().getDurationMillis() / 1000.0;
        String escapedTestName = escapeXml(testName);
        String runId = com.jmeterwebinsightreport.core.util.RunIdentity.of(reportData);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append(String.format(Locale.US,
                "<testsuites name=\"%s\" tests=\"%d\" failures=\"%d\" time=\"%.1f\">\n",
                escapedTestName, totalTests, totalFailures, totalTimeSeconds));
        sb.append(String.format(Locale.US,
                "  <testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" time=\"%.1f\">\n",
                escapedTestName, totalTests, totalFailures, totalTimeSeconds));
        appendRunIdProperty(sb, runId);

        for (SamplerStatistics stat : topLevelSamplers) {
            double timeSeconds = stat.getMeanResponseTime() / 1000.0;
            String escapedName = escapeXml(stat.getSamplerName());

            SamplerVerdicts.Verdict result = assertResults != null ? assertResults.get(stat.getSamplerName()) : null;
            boolean hasFailed = result != null && result.getOverallStatus() == AssertionStatus.FAIL;

            sb.append(String.format(Locale.US,
                    "    <testcase name=\"%s\" time=\"%.3f\" classname=\"performance\">\n",
                    escapedName, timeSeconds));

            if (hasFailed) {
                String failureMessage = buildFailureMessage(stat, result);
                String failureDetails = buildFailureDetails(stat, result);
                sb.append(String.format("      <failure message=\"%s\">\n",
                        escapeXml(failureMessage)));
                sb.append(escapeXml(failureDetails));
                sb.append("\n      </failure>\n");
            }

            sb.append("    </testcase>\n");
        }

        sb.append("  </testsuite>\n");
        appendAssertionSuite(sb, gate, escapedTestName, runId);
        appendServerSuites(sb, server, escapedTestName, runId);
        sb.append("</testsuites>\n");

        return sb.toString();
    }

    /**
     * One testsuite per evaluated server component, one testcase per server assert, with the same
     * three renderings as the client suite, decided by the same function: a breached fail band and
     * a check the file asked for that never ran are {@code <failure>} ("never ran" is
     * {@code NotJudged.isConfigError()} — a source that could not be reached vanishing into
     * {@code <skipped>} is a component that stopped gating and told nobody); the other not-judged
     * reasons are {@code <skipped>}; WARN is {@code <system-out>}. Every component builds (C6), so
     * there is no observe-only rendering any more (D29 went with it).
     *
     * <p>Every evaluated assert reaches a suite: the ones that named no declared component go to
     * {@value #NO_GATE_SUITE}, because they are fatal and must not be invisible in the XML. The
     * suites are {@code ServerGateEvaluation.reportedGates()}, the same list the CI/CD JSON's
     * {@code serverComponents[]} is written from. With nothing evaluated there are none, and
     * {@link #appendUnjudgedServerSuite} says why instead.
     */
    private void appendServerSuites(StringBuilder sb, ServerGateEvaluation server, String escapedTestName,
                                    String runId) {
        if (server == null) {
            return;
        }
        if (!server.evaluated()) {
            appendUnjudgedServerSuite(sb, server, escapedTestName, runId);
            return;
        }
        // An assert that named no declared component is fatal and is in serverAsserts[]. Walking
        // only gates() left it in no <testsuite> at all, so a CI dashboard reading the XML saw a
        // green run while the process exited 1 — the one shape where the two outputs disagreed.
        for (Map.Entry<String, GateSummary> e : server.reportedGates().entrySet()) {
            String suiteId = ServerGateEvaluation.NO_GATE_ID.equals(e.getKey()) ? NO_GATE_SUITE : escapeXml(e.getKey());
            appendServerSuite(sb, escapedTestName, runId, suiteId, e.getValue(), e.getValue().results(KpiResult.class));
        }
    }

    /**
     * The suite a run gets when server asserts were declared and nothing was judged (SF47, Q7):
     * the interim report, before any collector has run, or a payload that was given and refused.
     * It had no server suite at all, so on a refused run the CONFIG ERROR the process exits 1 over
     * never reached a Jenkins {@code junit} step, and nor did an assert the file makes unrunnable
     * on the interim one. Named {@code <testName> - serverMetrics}, after the block: there are no
     * component suites to collide with, because those exist only once something was judged.
     *
     * <ul>
     *   <li>{@code serverMetrics.payload}, the payload's state. A refused payload the declared
     *       asserts depended on ({@code fatalPayloadProblem()}) is a {@code <failure>} carrying the
     *       CI/CD JSON's ERROR finding, word for word; one nothing depended on is a
     *       {@code <skipped>} with its WARNING. The interim report is a {@code <skipped>} saying
     *       what to run: no payload is not an error (D11), and a {@code <failure>} there would mark
     *       every step-1 build unstable over the step the choreography expects.</li>
     *   <li>One {@code <failure>} per assert the file makes unrunnable without a payload
     *       ({@code unrunnableResultsWithUnknownTypes()}), with the body step 3 gives an
     *       {@code INVALID_RULE} — the check the file asked for that can never run, whatever is
     *       collected. The asserts on a component of unknown type are among them, as at step 3,
     *       although the CONFIG ERROR count takes that component once.</li>
     * </ul>
     *
     * <p>Nothing when no server assert is declared, as the header chip says nothing then.
     */
    private void appendUnjudgedServerSuite(StringBuilder sb, ServerGateEvaluation server, String escapedTestName,
                                           String runId) {
        if (!server.declared()) {
            return;
        }
        boolean refused = server.payloadRefusal() != null;
        boolean fatal = server.fatalPayloadProblem() != null;
        int declared = server.config().kpis().size();
        String message;
        if (refused) {
            message = payloadFindingMessage(server);
            if (message == null) {
                message = "the server-metrics payload given was refused (" + server.payloadRefusal().name() + ")";
            }
        } else {
            message = "Nothing collected yet: no server-metrics payload was given, so none of the " + declared
                    + " declared server assert(s) was judged. Collect the server data (wir-collect), then regenerate "
                    + "with webinsight.report.servermetrics in a -q properties file (or a quoted "
                    + "\"-Jwebinsight.report.servermetrics=<server-metrics.json>\")";
        }
        List<KpiResult> unrunnable = server.unrunnableResultsWithUnknownTypes();
        int failures = (fatal ? 1 : 0) + unrunnable.size();
        int skipped = fatal ? 0 : 1;
        sb.append(String.format(Locale.US,
                "  <testsuite name=\"%s - %s\" tests=\"%d\" failures=\"%d\" skipped=\"%d\" time=\"0.0\">%n",
                escapedTestName, UNJUDGED_SUITE, 1 + unrunnable.size(), failures, skipped));
        appendRunIdProperty(sb, runId);
        sb.append(String.format(Locale.US,
                "    <testcase name=\"%s\" time=\"0.000\" classname=\"server-metrics\">%n",
                escapeXml(ServerGateEvaluation.PAYLOAD_FINDING_ID)));
        if (fatal) {
            sb.append(String.format("      <failure message=\"%s\">%n", escapeXml(message)));
            sb.append(escapeXml("payload:   refused (" + server.payloadRefusal().name() + ")\n"
                    + "asserts:   " + declared + " declared, none judged"));
            sb.append("\n      </failure>\n");
        } else {
            sb.append("      <skipped message=\"").append(escapeXml(message)).append("\"/>\n");
        }
        sb.append("    </testcase>\n");
        for (KpiResult result : unrunnable) {
            sb.append(String.format(Locale.US,
                    "    <testcase name=\"%s\" time=\"0.000\" classname=\"server-metrics\">%n",
                    escapeXml(result.kpi().id())));
            sb.append(String.format("      <failure message=\"%s\">%n", escapeXml(kpiSkipMessage(result))));
            sb.append(escapeXml(kpiDetails(result)));
            sb.append("\n      </failure>\n");
            sb.append("    </testcase>\n");
        }
        sb.append("  </testsuite>\n");
    }

    /** The CI/CD JSON's finding about the refused payload — the same sentence on both surfaces. */
    private static String payloadFindingMessage(ServerGateEvaluation server) {
        for (com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding f : server.findings()) {
            if (ServerGateEvaluation.PAYLOAD_FINDING_ID.equals(f.ruleId())) {
                return f.message();
            }
        }
        return null;
    }

    /** How one server testcase is written. */
    private enum Rendering { FAILURE, SKIPPED, WARNING, PASSED }

    /** The one decision, for the testcase and for the suite's counts alike, so the two cannot disagree. */
    private static Rendering renderingOf(KpiResult result) {
        NotJudged reason = result.notJudged();
        if (reason != null && reason.isConfigError()) {
            return Rendering.FAILURE;
        }
        switch (result.status()) {
            case NOT_JUDGED: return Rendering.SKIPPED;
            case FAIL:       return Rendering.FAILURE;
            case WARN:       return Rendering.WARNING;
            default:         return Rendering.PASSED;
        }
    }

    /** One {@code <testsuite>} for a set of results, in the order the xs:sequence requires. */
    private void appendServerSuite(StringBuilder sb, String escapedTestName, String runId, String suiteId,
                                   GateSummary gate, List<KpiResult> results) {
        int failures = 0;
        int skipped = 0;
        for (KpiResult result : results) {
            Rendering r = renderingOf(result);
            if (r == Rendering.FAILURE) {
                failures++;
            } else if (r == Rendering.SKIPPED) {
                skipped++;
            }
        }
        sb.append(String.format(Locale.US,
                "  <testsuite name=\"%s - %s\" tests=\"%d\" failures=\"%d\" skipped=\"%d\" time=\"0.0\">%n",
                escapedTestName, suiteId, gate.declared(), failures, skipped));
        appendRunIdProperty(sb, runId);
        for (KpiResult result : results) {
            sb.append(String.format(Locale.US,
                    "    <testcase name=\"%s\" time=\"0.000\" classname=\"server-metrics\">%n",
                    escapeXml(result.kpi().id())));
            boolean verdict = result.notJudged() == null;
            switch (renderingOf(result)) {
                case FAILURE:
                    sb.append(String.format("      <failure message=\"%s\">%n",
                            escapeXml(verdict ? kpiFailureMessage(result) : kpiSkipMessage(result))));
                    sb.append(escapeXml(kpiDetails(result)));
                    sb.append("\n      </failure>\n");
                    break;
                case SKIPPED:
                    sb.append("      <skipped message=\"").append(escapeXml(kpiSkipMessage(result))).append("\"/>\n");
                    break;
                case WARNING:
                    sb.append(String.format("      <system-out>%s</system-out>%n",
                            escapeXml("WARN: " + kpiFailureMessage(result))));
                    break;
                default:
                    break;
            }
            sb.append("    </testcase>\n");
        }
        sb.append("  </testsuite>\n");
    }

    private String kpiSkipMessage(KpiResult result) {
        NotJudged reason = result.notJudged();
        if (reason == null) {
            return "not judged";
        }
        String base = "Server assert " + reason.phrase();
        return result.notJudgedDetail() != null ? base + ": " + result.notJudgedDetail() : base;
    }

    /**
     * {@code Server assert 'id': <reduce> <actual> <unit> breached <band>}, the actual and the
     * band in the {@link ServerKpi#verdictUnit() verdict's unit} (SF16): a slope reads
     * {@code B/min} and a ratio {@code msgs ÷ load:throughput}, the numbers they are. The series'
     * unit alone wrote a 60 MB/min growth as "60000000.00 B".
     */
    private String kpiFailureMessage(KpiResult result) {
        ServerKpi kpi = result.kpi();
        String unit = kpi.verdictUnit();
        StringBuilder msg = new StringBuilder("Server assert '").append(kpi.id()).append("': ")
                .append(kpi.reducer() == null ? "?" : kpi.reducer().describe()).append(' ')
                .append(formatValue(result.actual(), Digits.bands(kpi.warn(), kpi.fail())))
                .append(unit.isEmpty() ? "" : " " + unit);
        if (result.breachedBand() != null) {
            msg.append(" breached ").append(result.breachedBand().describe(unit));
        }
        if (!kpi.description().isEmpty()) {
            msg.append(" - ").append(kpi.description());
        }
        return msg.toString();
    }

    private String kpiDetails(KpiResult result) {
        ServerKpi kpi = result.kpi();
        StringBuilder details = new StringBuilder();
        // The component the assert names, a typo included; one that names none printed "null".
        details.append("component: ").append(kpi.gate() != null ? kpi.gate() : ServerGateEvaluation.NO_GATE_LABEL)
                .append('\n');
        if (kpi.metric() != null) {
            details.append("metric:    ").append(kpi.metric()).append('\n');
        }
        details.append("query:     ").append(kpi.query()).append('\n');
        // No reduce line when the reducer did not parse, as the Server tab's row has none (SF46):
        // "? across max" reads as a judgement taken with an unknown reduction, and the problem:
        // line below already quotes the mapper's sentence about it.
        if (kpi.reducer() != null) {
            details.append("reduce:    ").append(kpi.reducer().describe())
                    .append(" across ").append(kpi.across().key()).append('\n');
        }
        // What the reduction was divided by, beside how it was reduced: the body never said a
        // ratio was one, so "actual: 36.35 msgs" read as a lag (SF16, the F5 leftovers).
        if (kpi.divideBy() != null) {
            details.append("divideBy:  ").append(kpi.divideBy()).append('\n');
        }
        // criteria and actual in the verdict's unit, as the failure message is (SF16) - so the
        // assert's own bands are the ones its actual is printed beside, unconverted.
        details.append("criteria:  ").append(kpi.describeCriteria()).append('\n');
        if (result.notJudged() != null) {
            details.append("problem:   ").append(kpiSkipMessage(result)).append('\n');
        } else {
            String unit = kpi.verdictUnit();
            details.append("actual:    ")
                    .append(formatValue(result.actual(), Digits.bands(kpi.warn(), kpi.fail())))
                    .append(unit.isEmpty() ? "" : " " + unit).append('\n');
        }
        // "of the collected window" (D28): coverage is measured at collect time over the window
        // the collector fetched, while the verdict is taken over the judged one, and with a
        // ramp-up excluded the two differ. Every surface that shows coverage says which (SF25).
        if (!Double.isNaN(result.coverage())) {
            details.append("coverage:  ").append(Math.round(result.coverage() * 100))
                    .append("% of the collected window\n");
        }
        details.append("instances: ").append(result.instances()).append('\n');
        return details.toString().trim();
    }

    /**
     * The run's identity, so a consumer of this file can tell which execution produced it
     * without trusting the filename or the directory it was found in (R5.3).
     *
     * <p><b>Placement is load-bearing, not cosmetic.</b> Both strict schemas for this format —
     * Maven Surefire's {@code surefire-test-report.xsd} and the Windyroad {@code JUnit.xsd} that
     * Azure DevOps names as its JUnit schema — model {@code testsuite} as an {@code xs:sequence}
     * with {@code properties} <em>ahead of</em> {@code testcase}. A properties block after the
     * first testcase violates that sequence. Jenkins' xUnit plugin validates against an XSD from
     * version 2.0.0 on, so an out-of-order block is not "ignored by lenient parsers" — it is a
     * rejected file, which means a silently missing quality gate.
     *
     * <p>Written into <em>both</em> testsuites, for the same reason: Windyroad's schema omits
     * {@code minOccurs} on {@code properties}, so it defaults to 1 and the element is effectively
     * required in every suite. Duplicating sixteen hex characters costs nothing.
     *
     * <p>And a {@code <property>} rather than a custom attribute on {@code <testsuite>}: no
     * JUnit schema declares {@code xs:anyAttribute} there, so an invented attribute is a
     * validation error under those same strict parsers.
     */
    private void appendRunIdProperty(StringBuilder sb, String runId) {
        sb.append("    <properties>\n");
        sb.append("      <property name=\"webinsight.runId\" value=\"")
                .append(escapeXml(runId)).append("\"/>\n");
        sb.append("    </properties>\n");
    }

    /**
     * One testcase per declared rule, run-level rules included.
     *
     * <p>A {@code scope: "overall"} rule has no sampler to hang off, so it could never appear
     * in the per-sampler suite at all — the gate's broadest checks were exactly the ones the
     * JUnit output had no way to express.
     */
    private void appendAssertionSuite(StringBuilder sb, GateSummary gate, String escapedTestName,
                                      String runId) {
        if (gate == null || !gate.hasGate()) {
            return;
        }
        // A breached fail band is a failure. So is a check the file asked for that never ran —
        // not because it produced a verdict (it did not, and gate.status is untouched by this),
        // but because <skipped> is invisible in the dashboards people actually watch. A broken
        // rule, or a source that could not be reached, that vanishes from Jenkins is a gate
        // that stopped gating and told nobody.
        //
        // The other not-judged reasons stay skipped: parked on purpose, a metric this run could
        // not produce, a target that matched nothing, a KPI whose load rule already FAILED.
        // Which reasons are which is NotJudged.isConfigError()'s decision, made once — this used
        // to be `notJudged - invalidRule`, an arithmetic under which every reason added later
        // became a skip by default.
        int failures = gate.failed() + gate.configErrors();
        int skipped = gate.notJudged() - gate.configErrors();

        sb.append(String.format(Locale.US,
                "  <testsuite name=\"%s - assertions\" tests=\"%d\" failures=\"%d\""
                        + " skipped=\"%d\" time=\"0.0\">%n",
                escapedTestName, gate.declared(), failures, skipped));
        appendRunIdProperty(sb, runId);

        for (RuleResult result : gate.ruleResults()) {
            AssertionRule rule = result.rule();
            sb.append(String.format(Locale.US,
                    "    <testcase name=\"%s\" time=\"0.000\" classname=\"assertions\">%n",
                    escapeXml(rule.id())));

            AssertionStatus status = result.status();
            boolean brokenRule = result.notJudged() != null && result.notJudged().isConfigError();
            if (brokenRule) {
                // The skip message, in a <failure>. It already carries the sentence naming the
                // metric and its suggestion, so a CI report showing only this XML still says
                // what to fix — which is the whole reason this is not simply "skipped".
                sb.append(String.format("      <failure message=\"%s\">%n",
                        escapeXml(skipMessage(result))));
                sb.append(escapeXml(assertionFailureDetails(result)));
                sb.append("\n      </failure>\n");
            } else if (status == AssertionStatus.NOT_JUDGED) {
                sb.append("      <skipped message=\"")
                        .append(escapeXml(skipMessage(result)))
                        .append("\"/>\n");
            } else if (status == AssertionStatus.FAIL) {
                sb.append(String.format("      <failure message=\"%s\">%n",
                        escapeXml(assertionFailureMessage(result))));
                sb.append(escapeXml(assertionFailureDetails(result)));
                sb.append("\n      </failure>\n");
            } else if (status == AssertionStatus.WARN) {
                // A warning is not a failure — a build that stopped on one would make warn
                // bands pointless — but it must not vanish either.
                sb.append(String.format("      <system-out>%s</system-out>%n",
                        escapeXml("WARN: " + assertionFailureMessage(result))));
            }
            sb.append("    </testcase>\n");
        }
        sb.append("  </testsuite>\n");
    }

    /**
     * Why a testcase was skipped, in one line a CI report can show without the HTML beside it.
     *
     * <p>The reason's own phrase, plus the detail when there is one — which for INVALID_RULE is
     * the sentence carrying the mistyped name and the suggestion. A bare "skipped" on the rule
     * whose metric is a typo would be the quietest possible way to report the loudest problem.
     */
    private String skipMessage(RuleResult result) {
        NotJudged reason = result.notJudged();
        if (reason == null) {
            return "not judged";
        }
        String base = "rule " + reason.phrase();
        return result.notJudgedDetail() != null
                ? base + ": " + result.notJudgedDetail()
                : base;
    }

    /**
     * {@code Assertion 'id': <metric> <actual> <unit> on <sampler> breached <band>}, about
     * {@link RuleResult#worstJudged()} — the one definition of the worst match (L13), which the CI/CD
     * JSON's {@code attainment} describes too. It named the first match of the highest severity, so a
     * rule failing on two samplers named the faster one here and the slower one there.
     *
     * <p>A space between the number and its unit (L10), as the band beside it and every server line
     * have: "610.50ms ... breached &gt; 20 ms" wrote one unit two ways in one sentence.
     */
    private String assertionFailureMessage(RuleResult result) {
        AssertionRule rule = result.rule();
        String metric = rule.metric() != null ? rule.metric().canonicalName() : rule.metricName();

        RuleResult.Match worst = result.worstJudged();
        if (worst == null) {
            return "Assertion '" + rule.id() + "' produced no result";
        }
        String unit = rule.metric() != null ? rule.metric().unit().suffix() : "";
        StringBuilder msg = new StringBuilder("Assertion '").append(rule.id()).append("': ")
                .append(metric).append(' ')
                .append(formatValue(worst.actual(), Digits.bands(rule.warn(), rule.fail()))).append(spaced(unit))
                .append(" on ").append(worst.label());
        if (worst.breachedBand() != null) {
            msg.append(" breached ").append(worst.breachedBand().describe(unit));
        }
        if (!rule.description().isEmpty()) {
            msg.append(" - ").append(rule.description());
        }
        return msg.toString();
    }

    /** Every match, so the reader can see how close the passing ones were. */
    private String assertionFailureDetails(RuleResult result) {
        AssertionRule rule = result.rule();
        StringBuilder details = new StringBuilder();
        details.append("target:   ").append(rule.target().describe()).append('\n');
        details.append("metric:   ").append(rule.metric() != null
                ? rule.metric().canonicalName() : rule.metricName()).append('\n');
        details.append("criteria: ").append(rule.describeCriteria()).append('\n');
        if (result.notJudged() != null) {
            details.append("problem:  ").append(skipMessage(result)).append('\n');
        }
        String unit = rule.metric() != null ? rule.metric().unit().suffix() : "";
        double[] bands = Digits.bands(rule.warn(), rule.fail());
        for (RuleResult.Match match : result.matches()) {
            details.append("  [").append(match.status().wireName()).append("] ")
                    .append(match.label()).append(" = ")
                    // An unmeasured match is a dash, with no unit to measure it in.
                    .append(Double.isNaN(match.actual()) ? "-" : formatValue(match.actual(), bands) + spaced(unit))
                    .append('\n');
        }
        if (result.matchedTotal() > result.matches().size()) {
            details.append("  ... and ")
                    .append(result.matchedTotal() - result.matches().size())
                    .append(" more\n");
        }
        return details.toString().trim();
    }

    /**
     * A measured number, beside the bands it was judged against (item 3). An actual is never
     * printed on a band it is not on, or across one: two decimals wrote a FAIL at 119.36094674556213
     * as "p50 119.36ms ... breached &gt; 119.36 ms", and a 3.004 s FAIL as "max 3.00 s breached &gt;
     * 3 s", the band each had crossed.
     *
     * <ul>
     *   <li>A whole number is written as it is: printed exactly, it cannot mislead.</li>
     *   <li>Anything else is {@link Digits#beside} at two decimals, every band the rule or assert
     *       declares counting (Q1), plus a band of 0: the never-zero guard (SF15), which two decimals
     *       alone broke by writing 0.004 s as "max 0.00 s breached &gt; 0.003 s". The CI/CD JSON
     *       writes a client actual as the same number without that band (Q12), so 7.7e-6 is 0.0
     *       there and 0.0000077 here. Float noise is the stated exception on both: when even Digits'
     *       cap misleads, the capped number is printed.</li>
     *   <li>At a decimal tie the number is round2's (J2, Q14), not {@code %.2f}'s decimal half up:
     *       1.005, whose double sits just below the tie, is "1.00" as the JSON's 1.0, not "1.01", and
     *       a negative tie rounds toward zero as round2 does.</li>
     *   <li>Spelled as JUnit always spelled it: {@code %.2f} when the number has at most two decimals,
     *       so 610.50 keeps its zero, and otherwise every digit it has (119.361, 0.0000077). Either
     *       text parses back to exactly that number, so the test on the number is the test on the
     *       text.</li>
     *   <li>NaN is "-", and an infinity keeps the text it always had ("Infinity").</li>
     * </ul>
     *
     * <p>The legacy per-sampler suite writes its four legacy metrics in their legacy words
     * ({@code %.0f}, no band) and does not come here for them; any other metric it names (L11) is
     * written from the rule's match, here, beside the bands of every rule that judged that metric on
     * that sampler (REV-6, {@code SamplerVerdicts.Evidence.bands()}): beside the deciding rule's
     * alone, a FAIL at 119.36094 under {@code gt 119.36} read "119.361", on the {@code gt 119.361}
     * another rule PASSed it under.
     *
     * <p>{@code ServerKpiEvaluator.formatValue} in core is the second copy, kept in step by hand: the
     * LOAD_NOT_ACHIEVED sentence quotes a client load rule's actual with this number and this
     * spelling, so one measurement reads alike in a {@code <failure>} here and in the
     * {@code <skipped>} that sentence becomes (Q19). Its page mirror, {@code javaMeasured} in
     * report.js, is held to it by the server parity vectors.
     *
     * @param bands the constants of the bands the value is printed beside, in its unit
     */
    private static String formatValue(double value, double... bands) {
        if (Double.isNaN(value)) {
            return "-";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        if (Double.isInfinite(value)) {
            return String.format(Locale.US, "%.2f", value);
        }
        double n = Digits.beside(value, 2, Digits.withZero(bands));
        BigDecimal digits = BigDecimal.valueOf(n).stripTrailingZeros();
        return digits.scale() <= 2 ? String.format(Locale.US, "%.2f", n) : digits.toPlainString();
    }

    /**
     * The four metrics the per-sampler suite has always written in its own words — {@code P95 611ms
     * exceeds threshold}, {@code P95: 611ms (FAIL)} — which pipelines grep, so they keep them.
     */
    private static final List<String> LEGACY_METRICS = java.util.Arrays.asList("p95", "p99", "mean", "errorRate");

    /**
     * Build the failure message attribute (single line summary).
     *
     * <p>Every failing metric is named (L11): the four legacy ones in their legacy words, in their
     * legacy order, then every other in the order the rules first judged it, by the measurement that
     * failed it — {@code p50 119.361 ms breached > 119.36 ms}. It named the four only, so a sampler
     * failed by a p50 or a throughput rule read "ASSERT FAIL:" and nothing else.
     */
    private String buildFailureMessage(SamplerStatistics stat, SamplerVerdicts.Verdict result) {
        /* The message says what the product says: one word, one mechanism. */
        List<String> failing = new java.util.ArrayList<>();
        Map<String, AssertionStatus> metricStatuses = result.getMetricStatuses();

        if (metricStatuses.get("p95") == AssertionStatus.FAIL) {
            failing.add(String.format(Locale.US, "P95 %.0fms exceeds threshold", stat.getPercentile95()));
        }
        if (metricStatuses.get("p99") == AssertionStatus.FAIL) {
            failing.add(String.format(Locale.US, "P99 %.0fms exceeds threshold", stat.getPercentile99()));
        }
        if (metricStatuses.get("mean") == AssertionStatus.FAIL) {
            failing.add(String.format(Locale.US, "Mean %.0fms exceeds threshold", stat.getMeanResponseTime()));
        }
        if (metricStatuses.get("errorRate") == AssertionStatus.FAIL) {
            failing.add(String.format(Locale.US, "Error Rate %.1f%% exceeds threshold", stat.getErrorRate()));
        }
        for (Map.Entry<String, AssertionStatus> e : metricStatuses.entrySet()) {
            if (e.getValue() != AssertionStatus.FAIL || LEGACY_METRICS.contains(e.getKey())) {
                continue;
            }
            SamplerVerdicts.Evidence evidence = result.evidence(e.getKey());
            RuleResult.Match match = evidence == null ? null : evidence.match();
            if (match == null || match.breachedBand() == null) {
                // Verdicts assembled without a gate carry no match to quote; the metric is still named.
                failing.add(e.getKey() + " breached its fail band");
                continue;
            }
            String unit = unitOf(evidence.rule());
            failing.add(e.getKey() + ' ' + formatValue(match.actual(), evidence.bands()) + spaced(unit)
                    + " breached " + match.breachedBand().describe(unit));
        }
        // Never a bare "ASSERT FAIL:": a FAIL comes from a failing metric, and a verdict assembled by
        // hand with none still says what failed.
        return failing.isEmpty() ? "ASSERT FAIL: an assert on this sampler failed"
                : "ASSERT FAIL: " + String.join("; ", failing);
    }

    /**
     * Build the failure details body (multi-line details with actual vs threshold info): the four
     * legacy metrics in their legacy words, then every other metric judged on this sampler (L11),
     * {@code p50: 119.361 ms (FAIL)}, its number from the match its status was taken from.
     */
    private String buildFailureDetails(SamplerStatistics stat, SamplerVerdicts.Verdict result) {
        StringBuilder details = new StringBuilder();
        Map<String, AssertionStatus> metricStatuses = result.getMetricStatuses();

        if (metricStatuses.containsKey("p95")) {
            details.append(String.format(Locale.US, "P95: %.0fms (%s)\n",
                    stat.getPercentile95(), metricStatuses.get("p95")));
        }
        if (metricStatuses.containsKey("p99")) {
            details.append(String.format(Locale.US, "P99: %.0fms (%s)\n",
                    stat.getPercentile99(), metricStatuses.get("p99")));
        }
        if (metricStatuses.containsKey("mean")) {
            details.append(String.format(Locale.US, "Mean: %.0fms (%s)\n",
                    stat.getMeanResponseTime(), metricStatuses.get("mean")));
        }
        if (metricStatuses.containsKey("errorRate")) {
            details.append(String.format(Locale.US, "Error Rate: %.1f%% (%s)\n",
                    stat.getErrorRate(), metricStatuses.get("errorRate")));
        }
        for (Map.Entry<String, AssertionStatus> e : metricStatuses.entrySet()) {
            if (LEGACY_METRICS.contains(e.getKey())) {
                continue;
            }
            SamplerVerdicts.Evidence evidence = result.evidence(e.getKey());
            details.append(e.getKey()).append(": ");
            if (evidence != null && !Double.isNaN(evidence.match().actual())) {
                details.append(formatValue(evidence.match().actual(), evidence.bands()))
                        .append(spaced(unitOf(evidence.rule()))).append(' ');
            }
            details.append('(').append(e.getValue()).append(")\n");
        }

        return details.toString().trim();
    }

    private static String unitOf(AssertionRule rule) {
        return rule.metric() != null ? rule.metric().unit().suffix() : "";
    }

    /** A unit as it follows a number: after a space (L10), or nothing for a unitless metric. */
    private static String spaced(String unit) {
        return unit == null || unit.isEmpty() ? "" : " " + unit;
    }

    /**
     * Escape XML special characters in text content and attribute values, and drop
     * characters that XML 1.0 does not permit at all.
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;");  continue;
                case '<':  sb.append("&lt;");   continue;
                case '>':  sb.append("&gt;");   continue;
                case '"':  sb.append("&quot;"); continue;
                case '\'': sb.append("&apos;"); continue;
                default: break;
            }
            if (isXmlChar(text, i, c)) {
                sb.append(c);
            }
            // else: dropped — see isXmlChar
        }
        return sb.toString();
    }

    /**
     * True when the character at {@code index} is legal in XML 1.0.
     *
     * <p>Control characters below 0x20 other than TAB/LF/CR, the non-characters
     * 0xFFFE/0xFFFF, and unpaired surrogates are illegal <em>even when written as a
     * numeric character reference</em>. A sampler label carrying one (common when an
     * app echoes raw bytes into a URL or a CSV-driven {@code ${var}} label) would
     * otherwise produce a file that Jenkins/GitLab reject as not well-formed, silently
     * dropping the quality gate this report exists to enforce. Dropping the character
     * is the only way to keep the rest of the results parseable.
     */
    private static boolean isXmlChar(String s, int index, char c) {
        if (c == '\t' || c == '\n' || c == '\r') return true;
        if (c < 0x20) return false;
        if (c == 0xFFFE || c == 0xFFFF) return false;
        if (Character.isHighSurrogate(c)) {
            return index + 1 < s.length() && Character.isLowSurrogate(s.charAt(index + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return index > 0 && Character.isHighSurrogate(s.charAt(index - 1));
        }
        return true;
    }
}
