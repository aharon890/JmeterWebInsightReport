package com.jmeterwebinsightreport.report.gate;

import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.servermetrics.KpiResult;
import com.jmeterwebinsightreport.core.servermetrics.KpiUsability;
import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpiEvaluator;
import com.jmeterwebinsightreport.core.servermetrics.ServerMetricsConfig;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.report.annotation.ServerMetricsMapper;
import com.jmeterwebinsightreport.report.servermetrics.ServerMetricsPayloadLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place the server gates are judged — {@link GateEvaluation}'s sibling for the
 * {@code serverMetrics} block.
 *
 * <p>Built once at the generator's step 4b beside the client evaluation and read by every
 * consumer: the payload the Server tab draws from, the CI/CD JSON's {@code serverGates[]} and
 * {@code serverKpis[]}, the JUnit writer's one suite per gate, and the exit fold in
 * {@code ReportArtifacts}. There is no combined verdict anywhere (delivery plan D17): every
 * declared gate gets its own {@link GateSummary} — the same arithmetic as the client gate, so a
 * pipeline reads both with one idiom — and the fold below answers the two questions the exit
 * code needs without ever producing a word a human reads.
 *
 * <h2>No payload is not an error</h2>
 *
 * <p>KPIs declared and no {@code -Jwebinsight.report.servermetrics} given is step 1 of the
 * choreography (D11): the interim {@code -n -t} report, before any collector has run. The
 * server gates are simply <em>not evaluated</em>: no results, no gate summaries, nothing in the
 * CI/CD JSON, one log line saying what to do next. Making it SOURCE_FAILED would exit 1 on every
 * interim report by default. What it does say (SF47): the header chip reads NOT COLLECTED, and
 * JUnit carries one {@code serverMetrics} suite whose payload testcase is {@code <skipped>} — so
 * a pipeline's {@code junit} step shows that the server half has not run yet, without marking the
 * build unstable over a step the choreography expects.
 *
 * <p>A payload that <em>is</em> configured and cannot be used — missing, unreadable, another
 * schema's, another run's — is a config problem only when an enabled assert sits in a declared
 * component ({@link #fatalPayloadProblem()}, A1-7), and then it is also an ERROR finding, so the
 * CI/CD JSON carries it, and a {@code <failure>} in that JUnit suite, so a {@code junit} step does
 * (SF47). Otherwise — nothing enabled, or no server assert declared at all — nothing the payload
 * could have decided reaches the exit code, so it is a WARNING finding, a {@code <skipped>}, and
 * the exit is unaffected. The page's refused-payload notice, and the chip's REFUSED, are the same
 * either way. What does count without a payload is the
 * block's own faults — a literal secret, a {@code packs} key, an unusable source, an unrunnable
 * KPI, and a {@code requiresLoad} or {@code divideBy} naming no client rule (or a non-throughput
 * one) — because the file is wrong whether or not anything was collected, and step 1 and step 3
 * of the choreography must agree about the file. The unrunnable asserts are {@code <failure>}s
 * in that suite too ({@link #unrunnableResults()}), as step 3 writes them.
 *
 * <h2>{@code serverStatus} and the header chip (D24)</h2>
 *
 * <p>One fold, {@link #worstBuildingGate()}, read by both: the worst status over the components,
 * ranked FAIL &gt; NOT_EVALUATED &gt; WARN &gt; PASS, where NOT_EVALUATED is a component that
 * judged nothing — every assert in it parked, unmatched or refused. The rank is the point: a null
 * status used to lose to any verdict at all, so a component whose source died (exit 1 under
 * {@code failonerror}) could hand the header word to a sibling's PASS (L3-6). The asserts that
 * name no declared component are reported as one more, {@value #NO_GATE_ID}
 * ({@link #reportedGates()}), which is what JUnit has always done with them.
 *
 * <h2>The fold (D3, C6)</h2>
 *
 * <ul>
 *   <li>{@link #failsBuild()}: any component whose status is FAIL. What {@code failonassert}
 *       acts on, beside the client gate's FAIL.</li>
 *   <li>{@link #fatalConfigErrors()}: what {@code failonerror} acts on — {@code INVALID_RULE},
 *       {@code SOURCE_FAILED} and {@code LOW_COVERAGE} on any component, the block-level problems,
 *       and the asserts that belong to no declared component. Every component builds (C6), so
 *       there is no observe-only exemption.</li>
 * </ul>
 */
public final class ServerGateEvaluation {

    private static final Logger log = LoggerFactory.getLogger(ServerGateEvaluation.class);

    /**
     * The gate the asserts that name no declared gate are reported under — the JUnit suite's name
     * since M4, and an entry of the CI/CD JSON's {@code serverGates[]} since M7d (A1-6). Reserved
     * by {@code ServerMetricsMapper}, so no declared gate can take it.
     */
    public static final String NO_GATE_ID = "(no gate)";

    /**
     * What a READER sees for {@value #NO_GATE_ID}, on every surface that shows it — the header
     * chip, the console, the CI/CD JSON's {@code label}, the Server tab's card (SF22). The id stays
     * on the wire, where it is a contract (the JSON's {@code id}, the JUnit suite's name); the word
     * a reader is shown is the one the product uses, "component" (C7, D25). It had three names:
     * "(no gate)" in the chip, "(no component)" on the tab, "block" on the console.
     */
    public static final String NO_GATE_LABEL = "(no component)";

    /** How a reported entry is named for a reader: the reserved entry as {@value #NO_GATE_LABEL}, any other by its id. */
    public static String displayId(String gateId) {
        return NO_GATE_ID.equals(gateId) ? NO_GATE_LABEL : gateId;
    }

    /** The wire word for a gate that decided nothing — {@code CiCdJsonSerializer.gateBlock}'s too. */
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    private final ServerMetricsConfig config;
    private final ServerMetricsPayload payload;
    private final List<KpiResult> results;
    private final Map<String, GateSummary> gates;
    private final List<KpiResult> orphans;
    private final List<AssertionValidator.Finding> findings;
    /** With no payload: the KPIs whose requiresLoad/divideBy cannot resolve. Empty once evaluated. */
    private final List<KpiResult> referenceRefusals;
    private final ServerMetricsPayloadLoader.Refusal payloadRefusal;
    private final String payloadDetail;
    private final String payloadProblem;

    private ServerGateEvaluation(ServerMetricsConfig config, ServerMetricsPayload payload, List<KpiResult> results,
                                 Map<String, GateSummary> gates, List<KpiResult> orphans,
                                 List<AssertionValidator.Finding> findings, List<KpiResult> referenceRefusals,
                                 ServerMetricsPayloadLoader.Result collected) {
        this.config = config;
        this.payload = payload;
        this.results = results;
        this.gates = gates;
        this.orphans = orphans;
        this.findings = findings;
        this.referenceRefusals = referenceRefusals;
        this.payloadRefusal = collected == null ? null : collected.refusal();
        this.payloadDetail = collected == null ? null : collected.detail();
        this.payloadProblem = collected == null ? null : collected.problem();
    }

    /** No {@code serverMetrics} block at all — nothing declared, nothing to say. */
    public static ServerGateEvaluation none() {
        return notDeclared(null);
    }

    private static ServerGateEvaluation notDeclared(ServerMetricsPayloadLoader.Result collected) {
        ServerMetricsConfig none = ServerMetricsConfig.none();
        List<AssertionValidator.Finding> findings = new ArrayList<>();
        // A payload configured for a run that declares no server asserts: nothing needed it, so a
        // payload that could not be used is worth a sentence and not an exit code (A1-7).
        addPayloadFinding(findings, none, collected);
        logFindings(findings);
        return new ServerGateEvaluation(none, null, Collections.<KpiResult>emptyList(),
                Collections.<String, GateSummary>emptyMap(), Collections.<KpiResult>emptyList(),
                findings, Collections.<KpiResult>emptyList(), collected);
    }

    /**
     * True when a server assert the payload could have judged is declared — the one condition
     * under which a payload that could not be used is a configuration error (A1-7). Asked of the
     * asserts, not of the components: a component that holds no enabled assert has nothing the
     * payload could have decided, and neither does a file with components and no asserts, or no
     * block at all. Every component builds (C6). An assert naming a component the file never
     * declared does not count: it is refused for that whatever was collected, so the payload
     * could not have decided it either.
     */
    static boolean payloadRequired(ServerMetricsConfig config) {
        for (ServerKpi kpi : config.kpis()) {
            ServerGate gate = kpi.gate() == null ? null : config.gate(kpi.gate());
            if (kpi.enabled() && gate != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The finding a refused payload gets, under {@link #PAYLOAD_FINDING_ID}: an ERROR when a
     * building gate needed it ({@link #payloadRequired}) — the same fact the CONFIG ERROR line
     * and {@code failonerror} act on, so that a pipeline reading only the CI/CD JSON (the STRICT
     * line of {@code test-plans/goldens/jq-gate.txt}, on {@code failonerror=false}) can see it,
     * since a refused payload leaves no {@code serverGates} to count — and a WARNING when nothing
     * that builds needed it. Published, so it is built from the loader's PUBLISHED sentence (the
     * file's name, never its absolute path, and no parser message); the console's fuller
     * sentence is the CONFIG ERROR line's, through {@code ReportArtifacts}.
     */
    private static void addPayloadFinding(List<AssertionValidator.Finding> findings,
                                          ServerMetricsConfig config,
                                          ServerMetricsPayloadLoader.Result collected) {
        if (collected == null || collected.refusal() == null) {
            return;
        }
        if (payloadRequired(config)) {
            findings.add(AssertionValidator.finding(AssertionValidator.Severity.ERROR, PAYLOAD_FINDING_ID,
                    sentence(collected.detail()) + " The server asserts this run declares depend on it. "
                            + "The client report and verdict are unaffected; this fails the build only "
                            + "under webinsight.report.server.failonerror=true"));
            return;
        }
        String why = config.declaresAnything()
                ? "no enabled server assert sits in a declared component, so nothing it could have "
                        + "decided reaches the exit code"
                : "this run declares no server asserts, so nothing needed it";
        findings.add(AssertionValidator.finding(AssertionValidator.Severity.WARNING, PAYLOAD_FINDING_ID,
                sentence(collected.detail()) + " Not a configuration error here: " + why
                        + " - it does not fail the build"));
    }

    /** The loader's sentence, ended — some already end in a question ("...a different plugin version?"). */
    private static String sentence(String text) {
        return text.endsWith(".") || text.endsWith("?") || text.endsWith("!") ? text : text + ".";
    }

    /** The finding id a refused payload is reported under — an ERROR or a WARNING, see {@link #addPayloadFinding}. */
    public static final String PAYLOAD_FINDING_ID = ServerMetricsMapper.BLOCK_ID + ".payload";

    /**
     * @param annotations the loaded document, or null
     * @param payload     the accepted collector payload, or null when none was given or it was refused
     * @param clientGate  the client gate, which {@code requiresLoad} and {@code divideBy} resolve against
     */
    public static ServerGateEvaluation of(ReportAnnotations annotations, ServerMetricsPayload payload,
                                          GateSummary clientGate) {
        return of(annotations, payload, null, clientGate, null);
    }

    /**
     * The generator's entry point: the loader's whole answer rather than its payload alone, so a
     * payload that was configured and refused is told apart from one that was never given — the
     * page has to say which — and the run, so the payload's ramp-up can be checked against the
     * report's own.
     *
     * @param collected  what {@code ServerMetricsPayloadLoader} made of the configured payload
     * @param run        this run, or null
     */
    public static ServerGateEvaluation of(ReportAnnotations annotations, ServerMetricsPayloadLoader.Result collected,
                                          GateSummary clientGate, ReportData run) {
        return of(annotations, collected == null ? null : collected.payload(), collected, clientGate, run);
    }

    private static ServerGateEvaluation of(ReportAnnotations annotations, ServerMetricsPayload payload,
                                           ServerMetricsPayloadLoader.Result collected, GateSummary clientGate,
                                           ReportData run) {
        if (annotations == null || annotations.getServerMetrics() == null) {
            return notDeclared(collected);
        }
        ServerMetricsMapper.Result mapped = new ServerMetricsMapper().map(annotations.getServerMetrics());
        ServerMetricsConfig config = mapped.config();
        List<AssertionValidator.Finding> findings = new ArrayList<>(mapped.findings());
        // What the mapper does not say at load time — a missing query, no bands, a dead warn
        // band, a suspect negative band — the usability check says here, once.
        for (ServerKpi kpi : config.kpis()) {
            for (KpiUsability u : KpiUsability.check(kpi)) {
                if (!u.loaderReported()) {
                    findings.add(u.asFinding(kpi.id()));
                }
            }
        }
        if (payload != null && config.declaresAnything()) {
            AssertionValidator.Finding rampUp = rampUpDisagreement(config, payload, run);
            if (rampUp != null) {
                findings.add(rampUp);
            }
        }
        addPayloadFinding(findings, config, collected);
        logFindings(findings);

        if (!config.declaresAnything() || payload == null) {
            List<KpiResult> unrunnable = Collections.emptyList();
            if (config.declaresAnything()) {
                // Step 1 of the choreography, or a refused payload: nothing is judged, but a
                // requiresLoad or divideBy that names no client rule (or a non-throughput one) is
                // wrong in the FILE, and the client gate is right here. Asked through the same
                // arms the evaluator runs at step 3, so the two steps agree about the file.
                unrunnable = new ServerKpiEvaluator().referenceRefusals(config, clientGate);
                appendRuntimeRefusals(findings, unrunnable);
                // "Server: REFUSED" or "Server: NOT COLLECTED" — the header chip's word for the
                // state (SF47), and the console verdictLine's: one run, one word on every surface.
                // "Server", never "gate": "gate" is not a word the file, the page or the console
                // uses any more (SF22), and logSummary names a component, never a gate.
                if (collected != null && collected.refusal() != null) {
                    log.info("Server: {} - {} server assert(s) declared, and the payload given "
                            + "was refused ({}): {}", Chip.unjudged(Chip.REFUSED).getStatusLabel(),
                            config.kpis().size(), collected.refusal(), collected.problem());
                } else {
                    log.info("Server: {} - {} server assert(s) declared, no payload given. Run "
                            + "wir-collect and regenerate with webinsight.report.servermetrics in a -q "
                            + "properties file (or a quoted \"-Jwebinsight.report.servermetrics=<server-metrics.json>\")",
                            Chip.unjudged(Chip.NOT_COLLECTED).getStatusLabel(), config.kpis().size());
                }
            }
            return new ServerGateEvaluation(config, null, Collections.<KpiResult>emptyList(),
                    Collections.<String, GateSummary>emptyMap(), Collections.<KpiResult>emptyList(), findings,
                    unrunnable, collected);
        }

        List<KpiResult> results = new ServerKpiEvaluator().evaluate(config, payload, clientGate);
        // Not every INVALID_RULE is knowable from the KPI alone, so not every one comes from
        // KpiUsability: a divideBy naming no client rule, a requiresLoad naming a non-throughput
        // rule, a query the payload never collected are all decided in the evaluator, against
        // this run. They are still defects in the FILE, and findings[] is the list of those - so
        // the evaluator's refusals join it here, deduped against what the loader already said.
        appendRuntimeRefusals(findings, results);
        // Every declared gate gets an entry, empty ones included: a gate whose KPIs all vanished
        // reads NOT_EVALUATED rather than disappearing from the outputs.
        Map<String, List<KpiResult>> byGate = new LinkedHashMap<>();
        for (String id : config.gates().keySet()) {
            byGate.put(id, new ArrayList<KpiResult>());
        }
        List<KpiResult> orphans = new ArrayList<>();
        for (KpiResult r : results) {
            String gate = r.kpi().gate();
            if (gate != null && byGate.containsKey(gate)) {
                byGate.get(gate).add(r);
            } else {
                orphans.add(r);   // no gate, or a gate the file never declared: INVALID_RULE, block-level
            }
        }
        Map<String, GateSummary> gates = new LinkedHashMap<>();
        for (Map.Entry<String, List<KpiResult>> e : byGate.entrySet()) {
            gates.put(e.getKey(), GateSummary.of(e.getValue()));
        }
        ServerGateEvaluation evaluation = new ServerGateEvaluation(config, payload, results, gates, orphans, findings,
                Collections.<KpiResult>emptyList(), collected);
        evaluation.logSummary();
        return evaluation;
    }

    /**
     * A WARNING when the payload's judged window and the report disagree about the ramp-up.
     *
     * <p>The judged window is the payload's: {@code rampUpEnd} is written by
     * {@code wir-collect --rampup} and by nothing else, and the evaluator excludes
     * {@code [start, rampUpEnd]} from it. The report's own {@code -Jwebinsight.report.rampup}
     * marks the client charts and is never consulted for the server side. So a forgotten
     * {@code --rampup}, or one given in seconds while the {@code -J} value is in milliseconds,
     * judges the server KPIs over a different window from the one the report draws — silently.
     * The run identity does not cover it (it hashes no ramp-up), so this is the only place the two
     * meet. A finding, not a refusal: the verdicts are still the payload's, correctly computed over
     * the window it declares; what is wrong is that the reader cannot tell. Only asked when the
     * ramp-up is excluded at all — with {@code excludeRampUp: false} the payload's value judges
     * nothing.
     */
    static AssertionValidator.Finding rampUpDisagreement(ServerMetricsConfig config, ServerMetricsPayload payload,
                                                         ReportData run) {
        if (run == null || run.getMetadata() == null || payload.getWindow() == null || !config.excludeRampUp()) {
            return null;
        }
        long reportRampUp = Math.max(0, run.getMetadata().getRampUpDurationMillis());
        ServerMetricsPayload.Window w = payload.getWindow();
        long payloadRampUp = w.getRampUpEnd() == null ? 0 : Math.max(0, w.getRampUpEnd() - w.getStart());
        if (reportRampUp == payloadRampUp) {
            return null;
        }
        AssertionValidator.Finding f = AssertionValidator.finding(AssertionValidator.Severity.WARNING,
                ServerMetricsMapper.BLOCK_ID + ".collection",
                "the payload was collected with a ramp-up of " + payloadRampUp + " ms (wir-collect --rampup"
                        + (payloadRampUp == 0 ? " not given" : " " + payloadRampUp) + "), but this report's "
                        + "webinsight.report.rampup is " + reportRampUp + " ms. The server asserts are judged over "
                        + "the payload's window, from " + payloadRampUp + " ms into the run, while the charts mark "
                        + "the ramp-up at " + reportRampUp + " ms - pass the same number of milliseconds to both "
                        + "and re-collect");
        return f;
    }

    /**
     * One ERROR finding per {@code INVALID_RULE} the evaluator decided, unless the loader already
     * said the same sentence about the same KPI — {@code KpiUsability}'s refusals reach both, and
     * "two findings for one mistake" is the regression the client side has already pinned against.
     *
     * <p>"The same KPI" is the ASSERT, under either of its names (SF21): the mapper files a
     * problem under the label the file gives the assert — its id, or {@code serverMetrics.asserts[i]}
     * when it has none — and the evaluator's refusal carries the id it is judged under, which for an
     * id-less assert is the derived one. Keyed on the id alone, one undeclared component on an
     * id-less assert was two findings, and the derived-id twin outlived a Delete on the page (F15),
     * which hides a finding by the label.
     */
    private static void appendRuntimeRefusals(List<AssertionValidator.Finding> findings,
                                              List<KpiResult> results) {
        Set<String> said = new LinkedHashSet<>();
        for (AssertionValidator.Finding f : findings) {
            said.add(f.ruleId() + " " + f.message());
        }
        for (KpiResult r : results) {
            if (r.notJudged() != NotJudged.INVALID_RULE || r.notJudgedDetail() == null) {
                continue;
            }
            if (said.contains(r.kpi().declarationLabel() + " " + r.notJudgedDetail())
                    || !said.add(r.kpi().id() + " " + r.notJudgedDetail())) {
                continue;
            }
            AssertionValidator.Finding f = AssertionValidator.finding(
                    AssertionValidator.Severity.ERROR, r.kpi().id(), r.notJudgedDetail());
            findings.add(f);
            log.error("Invalid server {}", f.describe());
        }
    }

    private static void logFindings(List<AssertionValidator.Finding> findings) {
        for (AssertionValidator.Finding f : findings) {
            if (f.severity() == AssertionValidator.Severity.ERROR) {
                log.error("Invalid server {}", f.describe());
            } else {
                log.warn("Questionable server {}", f.describe());
            }
        }
    }

    private void logSummary() {
        // Every reported entry, the reserved one included and named as a reader sees it (SF22):
        // its asserts exit 1, so a log that skipped it read as a run with nothing wrong.
        for (Map.Entry<String, GateSummary> e : reportedGates().entrySet()) {
            GateSummary gate = e.getValue();
            StringBuilder reasons = new StringBuilder();
            for (NotJudged r : NotJudged.displayOrder()) {
                int n = gate.notJudged(r);
                if (n == 0) {
                    continue;
                }
                if (reasons.length() > 0) {
                    reasons.append(", ");
                }
                reasons.append(n).append(' ').append(r.label());
            }
            log.info("Server component {}: {} - {} declared, {} passed, {} warned, {} failed, {} not judged{}",
                    displayId(e.getKey()),
                    gate.status() == null ? "NOT EVALUATED" : gate.status(),
                    gate.declared(), gate.passed(), gate.warned(), gate.failed(), gate.notJudged(),
                    reasons.length() == 0 ? "" : " (" + reasons + ")");
        }
    }

    // ---- what was declared and what was done ---------------------------------------

    /** True when the file declared at least one server KPI. */
    public boolean declared() {
        return config.declaresAnything();
    }

    /** True when the KPIs were judged against a payload — the gates below exist only then. */
    public boolean evaluated() {
        return payload != null;
    }

    public ServerMetricsConfig config() {
        return config;
    }

    /**
     * The server data the file asks for, as a CSV must name it: one entry per enabled server assert
     * on a component, deduplicated, in declaration order - {component, metric, unit-or-null}, where
     * metric is the catalogue metric's name or the custom assert's query text. What the CI/CD JSON's
     * serverData block and the console's NEXT block both list, so the two cannot disagree.
     */
    public List<String[]> requiredSeries() {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (ServerKpi kpi : config.kpis()) {
            if (!waitsForServerData(kpi)) {
                continue;
            }
            String metric = kpi.metric() != null ? kpi.metric() : kpi.query();
            if (!seen.add(kpi.gate() + "|" + metric)) {
                continue;
            }
            String unit = kpi.unit() == null || kpi.unit().isEmpty() ? null : kpi.unit();
            out.add(new String[] {kpi.gate(), metric, unit});
        }
        return out;
    }

    /**
     * Whether a server assert asks for a series {@link #requiredSeries()} lists: enabled, on a
     * component, and naming a catalogue metric or a query. The console's NEXT line counts by it, so
     * its count and its list of names are of the same asserts.
     */
    public static boolean waitsForServerData(ServerKpi kpi) {
        if (!kpi.enabled() || kpi.gate() == null) {
            return false;
        }
        String metric = kpi.metric() != null ? kpi.metric() : kpi.query();
        return metric != null && !metric.isEmpty();
    }

    /** The accepted payload's {@code collectionId}, or null when not evaluated. */
    public String collectionId() {
        return payload == null ? null : payload.getCollectionId();
    }

    /** The accepted payload, or null. */
    public ServerMetricsPayload payload() {
        return payload;
    }

    /**
     * Why the configured payload was refused, or null when none was configured or it was accepted.
     * Not evaluated plus a refusal is a different state from not evaluated with nothing given, and
     * the tab must say which: "no payload was given — run wir-collect" sent a reader whose title
     * did not match the payload's to re-run the command that had just been refused.
     */
    public ServerMetricsPayloadLoader.Refusal payloadRefusal() {
        return payloadRefusal;
    }

    /**
     * The loader's sentence for {@link #payloadRefusal()} — the file, what is wrong, what to do —
     * in the form that may be published into the report, or null: the file's name, not its
     * absolute path, and no parser message (see {@code ServerMetricsPayloadLoader.Result#detail()}).
     * The console's fuller sentence goes through {@code ReportArtifacts}, not through here.
     */
    public String payloadDetail() {
        return payloadDetail;
    }

    /**
     * The loader's console sentence for a refused payload when it is a configuration error, or
     * null — what the CONFIG ERROR line and {@code failonerror} act on (A1-7).
     *
     * <p>Fatal only when an enabled assert sits in a declared component ({@link #payloadRequired}):
     * then the payload was the only way it could be judged, and a run whose server asserts
     * silently decided nothing must not exit 0. Otherwise the same refusal is a WARNING finding
     * (in {@link #findings()}) and costs no exit code, and prints no CONFIG ERROR marker — a
     * marker over a run that has, by decision (A1-7), no configuration error would contradict it.
     * It is not silent: the verdict line says "the payload given was refused" whenever server
     * asserts are declared, the loader's WARN line names the file, and the WARNING finding
     * reaches the CI/CD JSON and the Server tab.
     */
    public String fatalPayloadProblem() {
        return payloadRefusal != null && payloadRequired(config) ? payloadProblem : null;
    }

    /** Every declared gate, in declaration order, with its summary — empty when not evaluated. */
    public Map<String, GateSummary> gates() {
        return Collections.unmodifiableMap(gates);
    }

    /**
     * Every gate the CI/CD JSON and the JUnit XML report: the declared gates in declaration
     * order, then {@value #NO_GATE_ID} over the asserts that named none when there are any —
     * so summing {@code serverGates[].notJudged.invalidRule} counts every refused assert, as the
     * JUnit suites always have (A1-6). Empty when not evaluated.
     *
     * <p>Not {@link #gates()}: the page's gate strip lists what the document DECLARES, and a
     * reserved id there would read as a gate the document no longer declares.
     */
    public Map<String, GateSummary> reportedGates() {
        if (orphans.isEmpty()) {
            return gates();
        }
        Map<String, GateSummary> all = new LinkedHashMap<>(gates);
        all.put(NO_GATE_ID, GateSummary.of(orphans));
        return Collections.unmodifiableMap(all);
    }

    /**
     * True when a reported component's verdicts reach the exit code — every one since C6, the
     * {@value #NO_GATE_ID} entry included. Kept as a question so the serializers ask it rather
     * than assume it.
     */
    public boolean builds(String gateId) {
        return true;
    }

    /** The declared gate behind an id, or null. */
    public ServerGate gate(String id) {
        return config.gate(id);
    }

    /**
     * The results of the KPIs that belong to no declared gate — a gate id the file never
     * declared, or none at all once {@code gates} is declared (D1). Block-level config errors,
     * and the JUnit writer's "(no gate)" suite, so a fatal KPI is never absent from the XML.
     */
    public List<KpiResult> orphans() {
        return Collections.unmodifiableList(orphans);
    }

    /** Every KPI's result in declaration order — empty when not evaluated. */
    public List<KpiResult> results() {
        return Collections.unmodifiableList(results);
    }

    /** The mapper's findings plus the usability check's, in that order. */
    public List<AssertionValidator.Finding> findings() {
        return Collections.unmodifiableList(findings);
    }

    // ---- the fold --------------------------------------------------------------------------

    /**
     * Config errors that belong to no gate: the block's own problems (a literal secret, a
     * {@code packs} key, an unusable source, an unknown {@code defaultEnv}) and the KPIs that
     * named no declared gate. Always fatal under {@code failonerror}.
     *
     * <p>The orphans are counted <em>by reason</em>, through the same {@link GateSummary}
     * arithmetic every gate uses, and not by how many there are: D3 says a config error is
     * decided once, by {@link NotJudged#isConfigError()}, and an orphan that is merely
     * {@code DISABLED} is not one.
     */
    public int blockConfigErrors() {
        return config.errorCount() + GateSummary.of(orphans).configErrors() + unrunnableWithoutAPayload().size();
    }

    /**
     * With no payload, the asserts the file makes unrunnable — empty once evaluated, when each is
     * counted in its component's summary instead.
     *
     * <p>Nothing was evaluated, so an unrunnable KPI reaches no gate summary to be counted in. It
     * is still a defect in the file, and one that must not be fatal at step 3 of the choreography
     * and invisible at step 1.
     *
     * <p>UNKNOWN_TYPE is skipped because it is not the KPI's fault and the mapper has already
     * counted it, at the component: readComponents() records one block problem per component of
     * unknown type. Counting it again per assert would make one typo'd type N config errors and
     * give the console "N problem(s)" for one mistake. Then the KPIs whose requiresLoad or divideBy
     * names no client rule, or one that is not about throughput: knowable from the file and the
     * client gate alone, so a typo there must be as fatal from -n -t as it is from -g -o (A1-3).
     * Disjoint from the first set: referenceRefusals() skips every KPI KpiUsability already refuses.
     */
    private List<ServerKpi> unrunnableWithoutAPayload() {
        List<ServerKpi> unrunnable = new ArrayList<>();
        for (KpiResult r : unrunnableResults()) {
            unrunnable.add(r.kpi());
        }
        return unrunnable;
    }

    /**
     * With no payload, the {@code INVALID_RULE} result of every assert the file makes unrunnable,
     * in declaration order — the same set {@link #blockConfigErrors()} counts, each with the
     * sentence step 3 would give it (the evaluator asks {@code KpiUsability.refusal} and the same
     * reference arms). Empty once evaluated, when each sits in its component's summary instead.
     *
     * <p>Public for the JUnit writer (SF47): on an interim or refused run these are the CONFIG
     * ERROR, and a {@code <failure>} per assert is how a {@code junit} step learns of it — the
     * suites that carry them at step 3 do not exist yet.
     */
    public List<KpiResult> unrunnableResults() {
        return unrunnable(false);
    }

    /**
     * What the JUnit writer lists on an interim or refused run (SF47): {@link #unrunnableResults()}
     * plus the asserts on a component of unknown type, each as step 3 writes it — an
     * {@code INVALID_RULE} with {@code KpiUsability}'s sentence. Those are counted once, at the
     * component, by {@link #blockConfigErrors()}; in JUnit they had no testcase at all, so a
     * {@code junit} step read 0 failures from a run that exited 1 over them, and step 3 then wrote one
     * {@code <failure>} per assert for the same file.
     */
    public List<KpiResult> unrunnableResultsWithUnknownTypes() {
        return unrunnable(true);
    }

    private List<KpiResult> unrunnable(boolean withUnknownTypes) {
        List<KpiResult> unrunnable = new ArrayList<>();
        if (payload != null) {
            return unrunnable;
        }
        Map<ServerKpi, KpiResult> byReference = new java.util.IdentityHashMap<>();
        for (KpiResult r : referenceRefusals) {
            byReference.put(r.kpi(), r);
        }
        for (ServerKpi kpi : config.kpis()) {
            KpiUsability refusal = kpi.enabled() ? KpiUsability.refusal(kpi) : null;
            if (refusal != null && (withUnknownTypes || refusal.reason() != KpiUsability.Reason.UNKNOWN_TYPE)) {
                unrunnable.add(KpiResult.notJudged(kpi, NotJudged.INVALID_RULE, refusal.reasonText()));
            } else if (byReference.containsKey(kpi)) {
                unrunnable.add(byReference.get(kpi));
            }
        }
        return unrunnable;
    }

    /**
     * What {@code failonerror} fails the process over: the block's errors, every
     * {@code INVALID_RULE}, and every component's server-only config errors (C6: all build).
     */
    public int fatalConfigErrors() {
        return countConfigErrors();
    }

    /**
     * What the {@code CONFIG ERROR} marker reports. The same count as {@link #fatalConfigErrors()}
     * since C6 — it differed only by an observe-only gate's collection failures, which printed and
     * cost no exit code. Kept apart so the marker and the exit each ask their own question.
     */
    public int reportableConfigErrors() {
        return countConfigErrors();
    }

    private int countConfigErrors() {
        int n = blockConfigErrors();
        for (Map.Entry<String, GateSummary> e : gates.entrySet()) {
            for (NotJudged r : NotJudged.values()) {
                if (r.isConfigError()) {
                    n += e.getValue().notJudged(r);
                }
            }
        }
        return n;
    }

    /** True when any component FAILED — what {@code failonassert} acts on. */
    public boolean failsBuild() {
        return !failingGateIds().isEmpty();
    }

    /**
     * D24's rank: FAIL &gt; NOT_EVALUATED &gt; WARN &gt; PASS. A null status is a gate that judged
     * nothing — NOT_EVALUATED, ranked above WARN because a building gate that decided nothing
     * is a check the build depended on that never ran, and below FAIL because a measured breach
     * is still the worse news.
     */
    private static int rank(AssertionStatus status) {
        if (status == null) {
            return 3;
        }
        switch (status) {
            case FAIL: return 4;
            case WARN: return 2;
            case PASS: return 1;
            default:   return 3;   // a gate's status is PASS/WARN/FAIL or null; anything else decided nothing
        }
    }

    /**
     * THE fold (D24), and the only one: the worst of the reported gates that build, by
     * {@link #rank}, the first in report order on a tie. Null when nothing was evaluated or no
     * reported gate builds. {@link #serverStatus()} and {@link #headerChip()} both read it, so the
     * JSON's word and the header's cannot be chosen by two different rules.
     */
    private Map.Entry<String, GateSummary> worstBuildingGate() {
        Map.Entry<String, GateSummary> worst = null;
        for (Map.Entry<String, GateSummary> e : reportedGates().entrySet()) {
            if (!builds(e.getKey())) {
                continue;
            }
            if (worst == null || rank(e.getValue().status()) > rank(worst.getValue().status())) {
                worst = e;
            }
        }
        return worst;
    }

    /** A gate's status as the wire writes it — {@code CiCdJsonSerializer.gateBlock}'s spelling. */
    private static String wireStatus(AssertionStatus status) {
        return status == null ? NOT_EVALUATED : status.wireName();
    }

    /**
     * The CI/CD JSON's top-level {@code serverStatus} (D24), beside the client {@code status} and
     * never folded into it: the worst status over the gates that build — FAIL, NOT_EVALUATED,
     * WARN or PASS — and NOT_EVALUATED when no gate builds, because then nothing on the server
     * side was judged for the build. Null when the gates were not evaluated, which is exactly
     * when {@code serverGates} is absent too.
     */
    public String serverStatus() {
        if (!evaluated()) {
            return null;
        }
        Map.Entry<String, GateSummary> worst = worstBuildingGate();
        return worst == null ? NOT_EVALUATED : wireStatus(worst.getValue().status());
    }

    /**
     * The header chip (D17): the gate {@link #serverStatus()} was taken from, and how many other
     * gates there are. {@code null} only when no server assert was declared, so the header makes
     * no claim about a server side the run never had.
     *
     * <p>Declared and not evaluated, it names that state in the neutral style: {@code Server ·
     * REFUSED} over a payload that was given and refused, {@code Server · NOT COLLECTED} on the
     * interim report, before any collector has run — the Summary card's two words for the same
     * two states (SF47, Q7). It used to return null here, by decision: "nothing was evaluated, so
     * the header says nothing". The card beside it said REFUSED, and on a refused run the header
     * was then silent about the one CONFIG ERROR the run exits 1 over. Neither word is a verdict:
     * {@link #serverStatus()} stays absent on both, as the CI/CD JSON's {@code serverComponents}
     * does, and the badge is {@code badge-none}.
     *
     * <p>The word is the worst component's; every other declared component is counted by its state,
     * in the fold's order ("1 warn · 3 pass", B2), as Grafana's rule stats and the Prometheus alerts
     * page count every state beside the worst: "2 more" hid a WARN behind a component that judged
     * nothing. With no component reported there is no word at all, in the neutral style.
     *
     * <p>The count is of DECLARED gates — the badges the Server tab's gate strip shows — so
     * "1 more" is one more badge to find there. {@value #NO_GATE_ID} takes part in the fold and
     * can be the gate the chip names (asserts that named no declared gate decided nothing, and
     * they exit 1), but the strip lists what the document declares and has no badge for it, so
     * counting it as "one more" sent the reader looking for a badge that is not there.
     *
     * <p>Decided here rather than in the template. A {@code th:} expression picking a winner
     * among gates would be a second place a verdict is chosen, which is exactly what
     * "one place per side, and only one" forbids — and it could not be unit-tested.
     */
    public Chip headerChip() {
        if (!evaluated()) {
            if (!declared()) {
                return null;
            }
            return Chip.unjudged(payloadRefusal != null ? Chip.REFUSED : Chip.NOT_COLLECTED);
        }
        int declaredGates = gates.size();
        Map.Entry<String, GateSummary> worst = worstBuildingGate();
        if (worst == null) {
            // Nothing reported: no word to give, and nothing a reader could take for PASS or FAIL.
            return new Chip(null, null, null, declaredGates);
        }
        ServerGate declared = config.gate(worst.getKey());
        // The reserved entry is named as the Server tab names its card (SF22); its id stays the wire's.
        String label = declared != null ? declared.label() : displayId(worst.getKey());
        int others = gates.containsKey(worst.getKey()) ? declaredGates - 1 : declaredGates;
        // The rest by state, in the fold's order (B2): a WARN behind a component that judged nothing
        // is said, as Grafana's rule stats and the Prometheus alerts page count every state.
        Map<String, Integer> byState = new LinkedHashMap<>();
        for (String state : new String[] {"FAIL", NOT_EVALUATED, "WARN", "PASS"}) {
            byState.put(state, 0);
        }
        for (Map.Entry<String, GateSummary> gate : gates.entrySet()) {
            if (!gate.getKey().equals(worst.getKey())) {
                byState.merge(wireStatus(gate.getValue().status()), 1, Integer::sum);
            }
        }
        byState.values().removeIf(n -> n == 0);
        return new Chip(worst.getKey(), label, wireStatus(worst.getValue().status()), others, byState);
    }

    /** What the header renders: one gate's word, and how many gates are not named. */
    public static final class Chip {

        /** Declared, and the payload given for it was refused: nothing was judged (SF47). */
        public static final String REFUSED = "REFUSED";

        /** Declared, and no payload given yet — the interim report (SF47). */
        public static final String NOT_COLLECTED = "NOT_COLLECTED";

        private final String gateId;
        private final String label;
        private final String status;
        private final int others;
        private final Map<String, Integer> othersByState;

        Chip(String gateId, String label, String status, int others) {
            this(gateId, label, status, others, Collections.<String, Integer>emptyMap());
        }

        Chip(String gateId, String label, String status, int others, Map<String, Integer> othersByState) {
            this.gateId = gateId;
            this.label = label;
            this.status = status;
            this.others = others;
            this.othersByState = Collections.unmodifiableMap(new LinkedHashMap<>(othersByState));
        }

        /** The chip of a run whose server asserts were declared and not evaluated: {@link #REFUSED} or {@link #NOT_COLLECTED}. */
        static Chip unjudged(String state) {
            return new Chip(null, null, state, 0);
        }

        /**
         * The components the chip does not name, counted by state in the fold's order — FAIL,
         * NOT_EVALUATED, WARN, PASS — non-zero states only (B2). Empty when {@link #getOthers()} is 0.
         */
        public Map<String, Integer> getOthersByState() { return othersByState; }

        /** {@code "1 warn · 3 pass"}: the counts as the chip and the Summary card write them. */
        public String getOthersText() {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, Integer> state : othersByState.entrySet()) {
                if (text.length() > 0) {
                    text.append(" · ");
                }
                text.append(state.getValue()).append(' ')
                        .append(state.getKey().replace('_', ' ').toLowerCase(java.util.Locale.ROOT));
            }
            return text.toString();
        }

        /** True for {@link #REFUSED} and {@link #NOT_COLLECTED}: no component was evaluated at all. */
        private boolean unjudged() {
            return gateId == null && (REFUSED.equals(status) || NOT_COLLECTED.equals(status));
        }

        /** The worst component's id, or null when none was reported, or nothing was evaluated. */
        public String getGateId() { return gateId; }

        /**
         * Its label, falling back to the id — and {@value ServerGateEvaluation#NO_GATE_LABEL} for
         * the reserved entry, as the Server tab names it. Null when none was reported.
         */
        public String getLabel() { return label; }

        /**
         * Its status as a word, or null when none was reported — {@link #REFUSED} or
         * {@link #NOT_COLLECTED} when nothing was evaluated. Never a wire word in those two cases:
         * the CI/CD JSON has no {@code serverStatus} on such a run.
         */
        public String getStatus() { return status; }

        /**
         * How many DECLARED components the chip does not name — the strip's other badges.
         * {@value ServerGateEvaluation#NO_GATE_ID} is never counted: the strip has no badge for it.
         */
        public int getOthers() { return others; }

        /**
         * The chip's text: {@code "Server · Kafka FAIL · 1 warn · 1 pass"}, {@code "Server · nothing judged"},
         * or the state of a run judged against nothing, {@code "Server · REFUSED"} or
         * {@code "Server · NOT COLLECTED"} — the Summary card's word for it.
         */
        public String getText() {
            if (unjudged()) {
                return "Server · " + getStatusLabel();
            }
            if (gateId == null) {
                return "Server · nothing judged";
            }
            String rest = getOthersText();
            return "Server · " + label + " " + getStatusLabel() + (rest.isEmpty() ? "" : " · " + rest);
        }

        /** The tooltip: what the chip is a pointer to, and why it says what it says. */
        public String getTitle() {
            if (REFUSED.equals(status) && gateId == null) {
                return "The server-metrics payload given to this report was refused, so no server assert "
                        + "was judged - open the Server tab for why";
            }
            if (NOT_COLLECTED.equals(status) && gateId == null) {
                return "Nothing has been collected for this report yet, so no server assert was judged - "
                        + "open the Server tab for what was declared";
            }
            if (gateId == null) {
                return "No server component was judged. Open the Server tab for why";
            }
            return "The worst server component - open the Server tab for every component";
        }

        /**
         * The badge class the header paints it with. Decided here because only three of the
         * four statuses have a colour: a gate whose KPIs were all NOT_JUDGED has no verdict to
         * show, and building the class name in the template as {@code 'badge-' + status} would
         * have produced {@code badge-not_judged}, which no stylesheet defines — an unstyled
         * badge reads as neither a pass nor a failure, which is the worst of the three.
         */
        public String getBadgeClass() {
            if ("PASS".equals(status)) return "badge-pass";
            if ("WARN".equals(status)) return "badge-warn";
            if ("FAIL".equals(status)) return "badge-fail";
            return "badge-none";
        }

        /** The word the chip shows, with the two underscored wire names spelled for a reader. */
        public String getStatusLabel() {
            if ("NOT_JUDGED".equals(status)) return "NOT JUDGED";
            if ("NOT_EVALUATED".equals(status)) return "NOT EVALUATED";
            if (NOT_COLLECTED.equals(status)) return "NOT COLLECTED";
            return status;
        }
    }

    /**
     * The components that FAILED, in declaration order. The {@code ASSERT FAIL} marker asks
     * {@link #failsBuild()} and the header asks {@link #headerChip()}.
     */
    public List<String> failingGateIds() {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, GateSummary> e : gates.entrySet()) {
            if (e.getValue().status() == AssertionStatus.FAIL) {
                ids.add(e.getKey());
            }
        }
        return ids;
    }

    /**
     * One clause naming what never ran and where, for the CONFIG ERROR line — or null when
     * nothing is fatal. "2 server check(s) never ran (kafka: 1 invalid, 1 source failed)"; how
     * each entry is named is configReason's.
     */
    public String fatalConfigReason() {
        return configReason();
    }

    /** The same clause for the marker — identical since C6, as the two counts are. */
    public String reportableConfigReason() {
        return configReason();
    }

    /**
     * "3 server check(s) never ran (serverMetrics: 1 problem(s); kafka: 1 source failed; (no
     * component): 1 invalid)": the block's own problems under the key the file has them in, then
     * every reported entry by reason, in report order, the reserved one by the name a reader sees
     * (SF22). It used to say "block" for both the block's problems and the asserts that named no
     * declared component — a third name for {@value #NO_GATE_ID} beside the chip's and the tab's.
     * With no payload there are no summaries, so an unrunnable assert is counted under the
     * component it names, or the reserved entry when that is not one.
     */
    private String configReason() {
        int total = countConfigErrors();
        if (total == 0) {
            return null;
        }
        List<String> where = new ArrayList<>();
        if (config.errorCount() > 0) {
            where.add(ServerMetricsMapper.BLOCK_ID + ": " + config.errorCount() + " problem(s)");
        }
        // Without a payload, in the order the entries would be reported: declared, then reserved.
        Map<String, Integer> unrunnable = new LinkedHashMap<>();
        for (String id : config.gates().keySet()) {
            unrunnable.put(id, 0);
        }
        unrunnable.put(NO_GATE_ID, 0);
        for (ServerKpi kpi : unrunnableWithoutAPayload()) {
            String entry = kpi.gate() != null && config.gate(kpi.gate()) != null ? kpi.gate() : NO_GATE_ID;
            unrunnable.put(entry, unrunnable.get(entry) + 1);
        }
        for (Map.Entry<String, Integer> e : unrunnable.entrySet()) {
            if (e.getValue() > 0) {
                where.add(displayId(e.getKey()) + ": " + e.getValue() + " " + NotJudged.INVALID_RULE.label());
            }
        }
        for (Map.Entry<String, GateSummary> e : reportedGates().entrySet()) {
            StringBuilder reasons = new StringBuilder();
            for (NotJudged r : NotJudged.displayOrder()) {
                if (!r.isConfigError()) {
                    continue;
                }
                int n = e.getValue().notJudged(r);
                if (n == 0) {
                    continue;
                }
                if (reasons.length() > 0) {
                    reasons.append(", ");
                }
                reasons.append(n).append(' ').append(r.label());
            }
            if (reasons.length() > 0) {
                where.add(displayId(e.getKey()) + ": " + reasons);
            }
        }
        return total + " server check(s) never ran (" + String.join("; ", where) + ")";
    }
}
