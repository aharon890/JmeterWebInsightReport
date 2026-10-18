package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionTarget;
import com.jmeterwebinsightreport.core.assertion.Attainment;
import com.jmeterwebinsightreport.core.assertion.Digits;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.MetricRef;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.RuleResult;
import com.jmeterwebinsightreport.core.assertion.SamplerVerdicts;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.Durations;
import com.jmeterwebinsightreport.core.servermetrics.KpiResult;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;
import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.report.gate.ServerGateEvaluation;

import java.util.*;
import java.util.List;
import java.util.Collections;
import java.util.Locale;

/**
 * Serializes a CI/CD-friendly JSON summary for pipeline integration.
 * Produces machine-readable output with test status, sampler stats, and assert violations.
 */
public class CiCdJsonSerializer {

    private final ObjectMapper objectMapper;

    public CiCdJsonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(ReportData reportData,
                            SamplerVerdicts assertResults,
                            AssertionStatus overallStatus) throws JsonProcessingException {
        return serialize(reportData, assertResults, overallStatus, null);
    }

    /**
     * @param artifacts the paths actually written, or null when the caller has none to declare
     */
    public String serialize(ReportData reportData,
                            SamplerVerdicts assertResults,
                            AssertionStatus overallStatus,
                            Map<String, Object> artifacts) throws JsonProcessingException {
        return serialize(reportData, assertResults, overallStatus, artifacts, null);
    }

    /**
     * @param gate the declared rules and their results, or null when there is no gate. Adds the
     *        {@code gate} block and {@code assertions} array; every pre-existing key keeps its
     *        value and position, because pipelines parse this file today.
     */
    public String serialize(ReportData reportData,
                            SamplerVerdicts assertResults,
                            AssertionStatus overallStatus,
                            Map<String, Object> artifacts,
                            GateSummary gate) throws JsonProcessingException {
        return serialize(reportData, assertResults, overallStatus, artifacts, gate,
                Collections.<AssertionValidator.Finding>emptyList());
    }

    /**
     * @param findings what the loader and validator could not accept, or an empty list
     *
     * <p>Published even when there is no gate, which is the case that most needs them: a
     * typo'd annotations path, a file that failed to parse, or one whose every rule was
     * rejected all produce zero rules and a pile of findings. A pipeline reading only
     * {@code gate} saw an absent block and could not tell "no rules were declared" from
     * "every rule I declared was thrown away".
     */
    public String serialize(ReportData reportData,
                            SamplerVerdicts assertResults,
                            AssertionStatus overallStatus,
                            Map<String, Object> artifacts,
                            GateSummary gate,
                            List<AssertionValidator.Finding> findings)
            throws JsonProcessingException {
        return serialize(reportData, assertResults, overallStatus, artifacts, gate, findings, null);
    }

    /**
     * @param server the server gates, or null. Adds {@code serverStatus} beside {@code status}
     *        (D24: the worst building gate's status, from {@link ServerGateEvaluation#serverStatus()}),
     *        {@code serverComponents[]} (C7) — one entry per declared component, plus a reserved
     *        {@code "(no gate)"} entry (label {@code "(no component)"}) over the asserts that named none (A1-6), each
     *        {@code {id, label, type, collectionId, …}} and then the same block the client
     *        {@code gate} carries — and {@code serverAsserts[]}, one entry per server assert in
     *        declaration order. All three only when the gates were evaluated against a payload:
     *        the interim report has none of them, and a pipeline reading it sees no server block
     *        rather than an empty one it might sum. {@code assertsEvaluated} becomes "any gate
     *        ran", client or server (D8).
     *
     *        <p>The jq a pipeline gates with is NOT written here. It is kept in one file,
     *        {@code test-plans/goldens/jq-gate.txt}, where {@code verify_jq_idioms} runs every
     *        line over the golden documents — a default and a strict idiom, both null-safe on a
     *        client-only, server-only and gateless run, and each over goldens where the server
     *        half alone decides the answer. The idiom that used to be quoted here
     *        errored on every client-only run and read false on every server-only one, and
     *        nothing ever ran it. Copy from the file.
     */
    public String serialize(ReportData reportData,
                            SamplerVerdicts assertResults,
                            AssertionStatus overallStatus,
                            Map<String, Object> artifacts,
                            GateSummary gate,
                            List<AssertionValidator.Finding> findings,
                            ServerGateEvaluation server)
            throws JsonProcessingException {
        Map<String, Object> cicd = new LinkedHashMap<>();
        TestMetadata meta = reportData.getMetadata();

        // Which run produced this file — first, because everything below is only meaningful
        // once you know that (R5.3).
        //
        // The four artifacts of one run are bound to each other by filename, and filenames are
        // not unique per run: a pipeline handed a `.json` from a shared output directory had no
        // way to tell whether it belonged to the HTML sitting beside it. It does now, without
        // trusting the name or the directory. It is also what both ingestion paths compare to
        // answer "is the report already here mine?" — see the exists-policy.
        cicd.put("runId", com.jmeterwebinsightreport.core.util.RunIdentity.of(reportData));
        cicd.put("testName", meta != null ? meta.getTestName() : "Unknown");
        cicd.put("status", overallStatus != null ? overallStatus.name() : "UNKNOWN");
        // D24: the server side's own word, beside the client's and never folded into it — present
        // exactly when serverGates is, so a pipeline that reads one reads the other.
        if (server != null && server.evaluated()) {
            cicd.put("serverStatus", server.serverStatus());
        }
        // "UNKNOWN" conflated two very different situations: no thresholds were configured, and
        // thresholds were evaluated but produced no verdict. A pipeline reading only `status`
        // could not tell "the gate is not set up" from "the gate ran", which is how a typo in
        // -Jwebinsight.report.annotations silently turned the quality gate off.
        // A gate that ran counts as evaluated even when it produced no per-sampler verdict —
        // every rule unmatched, or invalid before it could match. Reading only the sampler map
        // said the gate was never set up about a gate that had just failed the build in the `gate`
        // block three keys below, which is the one thing this pair of fields exists to prevent.
        boolean gateRan = gate != null && gate.hasGate();
        // One vocabulary. The sla* keys were emitted beside these as deprecated aliases for
        // exactly one release; that dialect is no longer a supported way to declare a gate, so
        // there is no longer a reader who could be speaking that dialect on purpose.
        boolean serverRan = server != null && server.evaluated();
        boolean evaluated = gateRan || serverRan || (assertResults != null && !assertResults.isEmpty());
        cicd.put("assertsEvaluated", evaluated);
        cicd.put("duration", meta != null ? meta.getDurationMillis() : 0);
        // Top-level population (transaction children excluded), so the pipeline's numbers match
        // the header tiles of the HTML report generated from the same run.
        long totalSamples = reportData.getTopLevelSampleCount();
        long totalErrors = reportData.getTopLevelErrorCount();
        cicd.put("totalSamples", totalSamples);
        cicd.put("totalErrors", totalErrors);
        cicd.put("errorRate", totalSamples > 0 ? round2(totalErrors * 100.0 / totalSamples) : 0);

        // Per-sampler summary
        List<Map<String, Object>> samplerSummaries = new ArrayList<>();
        if (reportData.getSamplerStatistics() != null) {
            for (SamplerStatistics stat : reportData.getSamplerStatistics()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", stat.getSamplerName());
                s.put("sampleCount", stat.getSampleCount());
                s.put("mean", round2(stat.getMeanResponseTime()));
                s.put("p95", round2(stat.getPercentile95()));
                s.put("p99", round2(stat.getPercentile99()));
                s.put("errorRate", round2(stat.getErrorRate()));
                s.put("throughput", round2(stat.getThroughput()));
                s.put("apdex", stat.getApdexScore());
                if (assertResults != null && assertResults.containsKey(stat.getSamplerName())) {
                    s.put("assertStatus",
                            assertResults.get(stat.getSamplerName()).getOverallStatus().name());
                }
                samplerSummaries.add(s);
            }
        }
        cicd.put("samplers", samplerSummaries);

        // Breached samplers.
        if (assertResults != null) {
            List<Map<String, Object>> violations = new ArrayList<>();
            for (Map.Entry<String, SamplerVerdicts.Verdict> entry : assertResults.bySampler().entrySet()) {
                if (entry.getValue().getOverallStatus() != AssertionStatus.PASS) {
                    Map<String, Object> v = new LinkedHashMap<>();
                    // The display text, as it always was: the whole run is keyed apart from any
                    // sampler (CORE-1) and still reads "Whole run" here.
                    v.put("sampler", AssertionTarget.labelOf(entry.getKey()));
                    // CORE-1: the whole run's entry says so in a field of its own, beside the
                    // "Whole run" it has always carried. That name is display text and a sampler
                    // can be called it; a pipeline reads `scope`. A sampler's entry has no scope,
                    // so its shape is as it was.
                    if (entry.getValue().isWholeRun()) {
                        v.put("scope", "overall");
                    }
                    v.put("status", entry.getValue().getOverallStatus().name());
                    violations.add(v);
                }
            }
            cicd.put("assertViolations", violations);
        }

        // The gate, in the form a pipeline can act on.
        //
        // `status` above is one word, and one word cannot tell "two rules warned" from "two
        // rules could not run at all" — a difference that decides whether a build should stop.
        // So a pipeline reads the counts (test-plans/goldens/jq-gate.txt holds the idioms). The
        // counts sum to `declared`, deliberately, so a job can check the gate's own arithmetic
        // rather than trust that every rule landed somewhere.
        if (gate != null && gate.hasGate()) {
            cicd.put("gate", gateBlock(gate));
            cicd.put("assertions", assertionEntries(gate));
        }

        // The server gates, after the client's and before the findings, only once they were
        // evaluated. An interim report (no payload yet) carries no server block at all.
        if (serverRan) {
            // "serverComponents" (C7): one entry per declared component, and the reserved
            // "(no gate)" entry over the asserts that named none.
            cicd.put("serverComponents", serverGateEntries(server, server.reportedGates()));
            // "serverAsserts", not "serverKpis" (D25): the word a reader of this contract sees
            // is the one the annotations file and the report use. Internal names keep "kpi"; no
            // key in this document may (CiCdJsonSerializerTest sweeps for it).
            cicd.put("serverAsserts", serverKpiEntries(server));
        }

        // Outside the gate block on purpose. A finding is not part of a verdict — it is the
        // reason a verdict is missing or smaller than the file asked for, and the runs that
        // carry the most findings are exactly the ones with no gate block at all.
        if (findings != null && !findings.isEmpty()) {
            List<Map<String, Object>> out = new ArrayList<>(findings.size());
            for (AssertionValidator.Finding f : findings) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("severity", f.severity().name().toLowerCase(Locale.ROOT));
                entry.put("ruleId", f.ruleId());
                entry.put("message", f.message());
                out.add(entry);
            }
            cicd.put("findings", out);
        }

        cicd.put("generatedAt", meta != null ? meta.getReportGeneratedAt() : null);

        // Where everything went, so a pipeline stops guessing filenames.
        //
        // The report name is configurable and its siblings are derived from it, so a job that
        // wanted the HTML or the JUnit XML had to reimplement that derivation — including the
        // ${timestamp} substitution, the extension handling and the fallback-on-rejection rules.
        // Declaring the paths is both shorter and correct by construction.
        if (artifacts != null) {
            cicd.put("artifacts", artifacts);
        }

        return objectMapper.writer(lfPrettyPrinter()).writeValueAsString(cicd);
    }

    /**
     * Jackson's default layout, with LF line endings on every OS (Aaron, 2026-10-03). The default
     * printer ends a line with the platform separator, so the same run was a different file on
     * Windows, and a pipeline hashing or diffing it saw a change where there was none.
     */
    static com.fasterxml.jackson.core.util.DefaultPrettyPrinter lfPrettyPrinter() {
        com.fasterxml.jackson.core.util.DefaultPrettyPrinter pretty =
                new com.fasterxml.jackson.core.util.DefaultPrettyPrinter();
        pretty.indentObjectsWith(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n"));
        return pretty;
    }

    /**
     * The gate block, exactly as the report payload carries it.
     *
     * <p>Shared with {@link GatePayloadBuilder} rather than duplicated. Two identical builders
     * meant the CI/CD JSON and the HTML could grow a key apart without anything noticing —
     * a pipeline and a report describing the same gate with different fields.
     */
    static Map<String, Object> gateBlock(GateSummary gate) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("status", gate.status() == null ? "NOT_EVALUATED" : gate.status().wireName());
        block.put("declared", gate.declared());
        block.put("evaluated", gate.evaluated());
        block.put("passed", gate.passed());
        block.put("warned", gate.warned());
        block.put("failed", gate.failed());
        block.put("notJudged", notJudgedBlock(gate));
        return block;
    }

    /**
     * Every way a rule decided nothing, counted separately — one key per {@link NotJudged}
     * constant, always all of them.
     *
     * <p>Nested rather than more sibling keys, so the arithmetic is visible in the shape:
     * the reasons sum to {@code total}, and {@code passed + warned + failed + total == declared}.
     * The three server-only reasons are present on every gate and zero on a gate of client
     * rules, which is the price of one block shape for both kinds of gate — a key that appears
     * only when non-zero is a key a pipeline's sum check cannot rely on.
     *
     * <p>{@code invalidRule} is the one a strict pipeline wants. It replaces the old top-level
     * {@code invalid}, which was a STATUS that outranked FAIL — so a mistyped metric name failed
     * a build with no measured number having exceeded anything. Now the engine reports and the
     * pipeline decides: the STRICT line of {@code test-plans/goldens/jq-gate.txt} refuses it,
     * on the client gate and every server gate alike, and is evaluated over the goldens so it
     * cannot drift from this shape. (The idiom quoted here before read {@code .gate} alone, and
     * so was false on every run that declared only server asserts.)
     */
    private static Map<String, Object> notJudgedBlock(GateSummary gate) {
        Map<String, Object> nj = new LinkedHashMap<>();
        nj.put("total", gate.notJudged());
        for (NotJudged reason : NotJudged.values()) {
            nj.put(reason.countKey(), gate.notJudged(reason));
        }
        return nj;
    }

    /**
     * One entry per rule, in declaration order — diff-friendly, so a job can compare two runs'
     * gates line by line.
     *
     * <p>Every match is carried, passing ones included: a verdict alone does not say which of
     * the four samplers a pattern selected actually breached, nor how close the others were,
     * and that is what moves a threshold.
     */
    private List<Map<String, Object>> assertionEntries(GateSummary gate) {
        List<RuleResult> ruleResults = gate.ruleResults();
        List<Map<String, Object>> entries = new ArrayList<>(ruleResults.size());
        for (RuleResult result : ruleResults) {
            AssertionRule rule = result.rule();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", rule.id());
            if (!rule.description().isEmpty()) {
                entry.put("description", rule.description());
            }
            entry.put("target", rule.target().describe());
            entry.put("targetKind", rule.target().kind().name().toLowerCase(Locale.ROOT));
            entry.put("metric", rule.metric() != null
                    ? rule.metric().canonicalName() : rule.metricName());
            MetricRef metric = rule.metric();
            entry.put("unit", metric != null ? metric.unit().suffix() : "");
            entry.put("status", result.status().wireName());
            if (result.notJudged() != null) {
                entry.put("notJudged", result.notJudged().wireName());
                entry.put("reason", result.notJudgedDetail() != null
                        ? result.notJudgedDetail() : result.notJudged().phrase());
            }
            entry.put("matched", result.matchedTotal());

            // How close it was. Until this block a PASS carried its measured value and nothing
            // to measure it against — `threshold` below is emitted only inside a match that
            // BREACHED — so a consumer could see p95 = 712 and not tell whether the budget was
            // 800 or 8000. Absent rather than zeroed when the question does not apply; see
            // Attainment.of.
            Attainment attainment = Attainment.of(result);
            if (attainment != null) {
                Map<String, Object> a = new LinkedHashMap<>();
                // actual is the per-match `actual` below it by construction: one function
                // (actualOf), one value (the worst match), one set of bands (the rule's) - two
                // renderings of the same measurement inside one artifact must not disagree, and a
                // WARN cannot write 234.912 in its match and 234.91 here.
                a.put("actual", actualOf(attainment.actual(), rule));
                // The threshold does NOT. It is the author's declared constant, and round2
                // quantises the whole threshold space to 0.01: this repo's own fixture declares
                // `fail: {lt: 0.001}` for run-throughput and a loadValidity target of 0.002, and
                // rounding printed `failThreshold: 0.0` — a limit of zero, which is both wrong and
                // unreachable. Echo what was written.
                a.put("failThreshold", attainment.failThreshold());
                // A ratio needs more grain than a millisecond does. At 2dp every band wider than
                // ~50x its measurement reported `consumed: 0.0`, which reads as "no budget" and is
                // indistinguishable from a rule that measured nothing.
                a.put("consumed", consumedOf(attainment.consumed()));
                a.put("breached", attainment.breached());
                entry.put("attainment", a);
            }

            /* EVERY match, uncapped, and deliberately unlike the report payload.
             *
             * GatePayloadBuilder keeps the worst MAX_MATCHES_PER_RULE of a rule so a blanket
             * rule over 500 samplers does not put 500 objects per rule into the HTML, and marks
             * the entry `resultsCapped`. This file does not cap: a pipeline reading a
             * machine-readable contract should not have it truncated under it, and `matched`
             * would then be the only evidence anything was missing.
             *
             * So the two artifacts legitimately carry different SETS for the same rule. That is
             * fine as long as each says which it is, which is why the flag exists over there. */
            List<Map<String, Object>> results = new ArrayList<>(result.matches().size());
            for (RuleResult.Match match : result.matches()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sampler", match.label());
                m.put("actual", Double.isNaN(match.actual()) ? null : actualOf(match.actual(), rule));
                m.put("status", match.status().wireName());
                if (match.breachedBand() != null) {
                    m.put("predicate", match.breachedBand().op().key());
                    // The declared constant, unrounded, for failThreshold's reason above: round2
                    // made a breached `lt 0.001` publish `threshold: 0.0`, a limit the breach was
                    // never measured against, beside a failThreshold of 0.001 (N3). It is always
                    // finite, as failThreshold is: the client mapper reads a band through
                    // ServerMetricsMapper.asNumber, which refuses NaN and both infinities on both
                    // arms — a bare `1e400` and a loadValidity that computes `lt NaN` included — so
                    // no client band reaches this file non-finite, and CiCdJsonSerializerTest sweeps
                    // the whole document for a quoted "Infinity", "-Infinity" or "NaN".
                    m.put("threshold", match.breachedBand().thresholdFor(match.actual()));
                }
                results.add(m);
            }
            entry.put("results", results);
            entries.add(entry);
        }
        return entries;
    }

    /**
     * One entry per declared server gate: its identity, whether it builds, the collection it
     * was judged over, and the same block as the client gate — {@link #gateBlock}, not a
     * second vocabulary (review §1.2). Shared with {@link GatePayloadBuilder}, which passes the
     * declared gates: the page's gate strip lists what the document declares.
     */
    static List<Map<String, Object>> serverGateEntries(ServerGateEvaluation server) {
        return serverGateEntries(server, server.gates());
    }

    /**
     * @param gates which gates: {@link ServerGateEvaluation#gates()} for the page, and
     *        {@link ServerGateEvaluation#reportedGates()} for this document — the declared gates
     *        plus a reserved {@code "(no gate)"} entry over the asserts that named none (they are
     *        fatal, D1) with the same block from {@code GateSummary.of(orphans)}. Without it those
     *        asserts were in {@code serverAsserts[]} and in no component's count, so a pipeline
     *        summing {@code serverComponents[].notJudged.invalidRule} under {@code failonerror=false} missed
     *        exactly the ones that exit 1 by default (A1-6). JUnit has had the same suite since M4.
     */
    static List<Map<String, Object>> serverGateEntries(ServerGateEvaluation server, Map<String, GateSummary> gates) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, GateSummary> e : gates.entrySet()) {
            ServerGate declared = server.gate(e.getKey());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getKey());
            // The reserved entry keeps its id, "(no gate)", a contract since A1-6; its label is
            // the word a reader sees for it everywhere else, "(no component)" (SF22).
            entry.put("label", declared != null ? declared.label() : ServerGateEvaluation.displayId(e.getKey()));
            // The component's type (C7): what picked its catalogue. Absent on the reserved entry,
            // which is no component. No failsBuild: every component builds (C6).
            if (declared != null && declared.type() != null) {
                entry.put("type", declared.type());
            }
            entry.put("collectionId", server.collectionId());
            entry.putAll(gateBlock(e.getValue()));
            entries.add(entry);
        }
        return entries;
    }

    /**
     * One entry per KPI in declaration order: what was declared, what it decided, and the
     * number it decided on. No matches — a KPI judges one number, folded across its instances;
     * the per-instance series are chart data, in the payload beside the series (D19).
     *
     * <p>{@code unit} is the declared unit — the series' — always: for a {@code divideBy} assert
     * the {@code actual} and the bands are in {@code unit ÷ <divideBy rule>}, and for a
     * {@code slope} in {@code unit/min} (a slope ratio in both), which is how the report and JUnit
     * write them ({@code ServerKpi.verdictUnit()}). The field is left as declared because it is a
     * consumed contract, and no second field is added (SF16): {@code reduce.kind} and
     * {@code divideBy} already tell a machine everything the verdict unit says.
     */
    static List<Map<String, Object>> serverKpiEntries(ServerGateEvaluation server) {
        return serverKpiEntries(server, false);
    }

    /**
     * As above, for the JSON ({@code forPage} false) or for the page's chart data
     * ({@code chartData.serverKpis}, {@code forPage} true). One builder, so the two cannot drift:
     * the page reads the entry's {@code reduce}, {@code divideBy}, bands and {@code breached}
     * exactly as the JSON writes them.
     *
     * <p>They differ in {@code actual} alone (F7). The JSON writes four places, with the digits
     * that keep the actual on its own side of every band the assert declares where four would not,
     * up to Digits' cap ({@link #serverActualOf}): it is a consumed contract, which the goldens hold
     * (R06). The page
     * gets Java's own double, {@code null} when it is not finite, because the page decides its own
     * digits: {@code formatActual} adds a digit only where two decimals would put an actual on or
     * across its band (SF15), and it cannot recover digits a rounded number no longer has. Handed
     * the old 4dp, a FAIL at 2.24146 under {@code lt 2.24148} read "2.2415" - the passing side of
     * its own band - and any actual below 5e-5 read 0. Do not put the rounding back for
     * "consistency": the page's number is not the wire's on purpose. {@code coverage} is the same on
     * both: the collector already wrote it at 4dp.
     */
    static List<Map<String, Object>> serverKpiEntries(ServerGateEvaluation server, boolean forPage) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (KpiResult result : server.results()) {
            ServerKpi kpi = result.kpi();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", kpi.id());
            if (!kpi.description().isEmpty()) {
                entry.put("description", kpi.description());
            }
            // The component it judges, and the catalogue metric when it names one (C7). No
            // "source": a collection has one, given to wir-collect, never the file's (C8).
            entry.put("component", kpi.gate());
            if (kpi.metric() != null) {
                entry.put("metric", kpi.metric());
            }
            entry.put("query", kpi.query());
            entry.put("unit", kpi.unit());
            entry.put("reduce", kpi.reducer() == null ? null : reduceOf(kpi.reducer()));
            entry.put("across", kpi.across().key());
            if (kpi.requiresLoad() != null) {
                entry.put("requiresLoad", kpi.requiresLoad());
            }
            if (kpi.divideBy() != null) {
                entry.put("divideBy", kpi.divideBy());
            }
            entry.put("status", result.status().wireName());
            if (result.notJudged() != null) {
                entry.put("notJudged", result.notJudged().wireName());
                entry.put("reason", result.notJudgedDetail() != null
                        ? result.notJudgedDetail() : result.notJudged().phrase());
            }
            double actual = result.actual();
            // The page: Java's double, and null rather than an Infinity Jackson would write and
            // JSON.parse refuse. The JSON: four places beside the assert's bands (serverActualOf).
            entry.put("actual", forPage ? (Double.isFinite(actual) ? actual : null)
                    : (Double.isNaN(actual) ? null : serverActualOf(actual, kpi)));
            entry.put("coverage", Double.isNaN(result.coverage()) ? null : round4(result.coverage()));
            entry.put("instances", result.instances());
            if (kpi.warn() != null) {
                entry.put("warn", bandOf(kpi.warn()));
            }
            if (kpi.fail() != null) {
                entry.put("fail", bandOf(kpi.fail()));
            }
            if (result.breachedBand() != null) {
                entry.put("breached", bandOf(result.breachedBand()));
            }
            entries.add(entry);
        }
        return entries;
    }

    /**
     * The reducer in the grammar the annotations file writes it in — {@code {"kind": "p95",
     * "over": {"sustainedFor": "30s"}}} or {@code {"over": {"breachBudget": 0.02}}} — never the
     * display sentence {@code Reducer.describe()} makes of it (A1-12). A consumer reading
     * "max sustained for 10s" had to parse English to recover the reducer, and any rewording of
     * that sentence moved a consumed contract. The sentence stays where a person reads it: the
     * JUnit messages and the console. {@code kind} is {@code Reducer.key()} and the duration is
     * {@code Durations.format}, the spellings the editor writes back, so the object round-trips
     * into a file unchanged. {@code over} is absent when the file declared no tolerance.
     */
    static Map<String, Object> reduceOf(Reducer reducer) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", reducer.key());
        Reducer.Over over = reducer.over();
        if (over != null) {
            Map<String, Object> o = new LinkedHashMap<>();
            if (over.isSustainedFor()) {
                o.put("sustainedFor", Durations.format(over.sustainedForMillis()));
            } else {
                o.put("breachBudget", over.breachBudget());
            }
            r.put("over", o);
        }
        return r;
    }

    /**
     * A band, one operator to one threshold: {@code {"gt": 800.0}}. The threshold is echoed and
     * never rounded — and always as a double, {@link Predicate#value()} being one, so a file
     * that wrote {@code 800} reads back {@code 800.0}. A consumer must parse it as a number.
     */
    private static Map<String, Object> bandOf(Predicate band) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put(band.op().key(), band.value());
        return b;
    }

    /**
     * A client rule's measured value, as {@code results[].actual} and {@code attainment.actual} write
     * it: two decimals, unless two decimals would put it on a band it is not on, or across one - then
     * the digits that keep it on its own side ({@link Digits#beside}: JUnit writes the same number,
     * and the page the same rule at its own base). round2 alone wrote a FAIL at 119.36094674556213
     * under {@code gt 119.36} as 119.36, on the band it breached beside {@code "threshold": 119.36},
     * and a PASS under {@code gte 214.75} as 214.75 beside {@code "breached": false}.
     *
     * <ul>
     *   <li>Every band the rule declares counts, warn and fail (Q1), not the breached one: a PASS has
     *       none, and its {@code failThreshold} is printed beside it all the same.</li>
     *   <li>No band of 0 (Q12): this document has no never-zero guard, so a 7.7e-6/s throughput that
     *       no band separates from 0 is still 0.0 - while a FAIL over {@code gt 0} at 0.005 % is
     *       0.005, because the band itself separates it.</li>
     *   <li>Float noise is the stated exception (Q13): when even Digits' cap misleads, the capped
     *       number is written, so a T-Digest 993.2499999999998 that PASSes {@code gte 993.25} is
     *       993.25. That verdict is decided by noise, which is the evaluator's to answer for
     *       (plan L8).</li>
     *   <li>A double goes into the map, never a BigDecimal: Jackson keeps a BigDecimal's trailing
     *       zeros ({@code 993.250}) and writes a double's shortest form ({@code 119.361}).</li>
     *   <li>A value that is not finite keeps round2's old path, which writes a number rather than the
     *       quoted {@code "Infinity"} a consumer would choke on: {@code Math.round} clamps an infinity
     *       to {@code Long.MAX_VALUE}, so it is written 9.223372036854776E16 (negated for -Infinity),
     *       where JUnit prints "Infinity". No client metric reaches one (a zero-length window is
     *       charged 1 ms). NaN never gets here (callers write {@code null}, or have no attainment).</li>
     * </ul>
     *
     * <p>{@code samplers[]} and the top-level {@code errorRate} keep round2: no band stands beside
     * them, and they match the HTML table. So a rule's actual can differ from its sampler's row in the
     * third decimal, which is the point.
     */
    private static double actualOf(double value, AssertionRule rule) {
        return Double.isFinite(value)
                ? Digits.beside(value, 2, Digits.bands(rule.warn(), rule.fail())) : round2(value);
    }

    /**
     * A server assert's measured value, as {@code serverAsserts[].actual} writes it: the server twin
     * of {@link #actualOf}, at the four places this field has always had. round4 alone wrote a FAIL
     * at 3.00004 under {@code gt 3} as 3.0, on the band it breached beside
     * {@code "breached": {"gt": 3.0}}, and a PASS at 2.99996 under {@code gte 3} as 3.0, on the band
     * it never reached - while JUnit's {@code actual:} line for the same assert read 3.00004 (S2,
     * L20). {@link Digits#beside} keeps the four places and adds digits only where four would put the
     * actual on or across a band, up to Digits' cap.
     *
     * <ul>
     *   <li>Every band the assert declares counts, warn and fail (Q1), in the verdict's unit as the
     *       actual is: the same bands JUnit prints it beside.</li>
     *   <li>No band of 0, as {@link #actualOf} has none (Q12): an actual no declared band separates
     *       from 0 is 0.0 here, as it was, where JUnit keeps the never-zero guard and prints it. A
     *       declared band at 0 separates like any band, so a FAIL over {@code gt 0} at 0.00004 is
     *       0.00004, not the 0.0 round4 wrote on it.</li>
     *   <li>Float noise is the stated exception (Q13), as on the client, and it starts lower here:
     *       Digits' cap is twelve significant digits or a third decimal, and from 1e7 up four places
     *       are already that many, so the field is round4's number there. A FAIL at 10000000.00004
     *       over {@code gt 10000000.00001} is 1.0E7, below its band, and JUnit writes the same
     *       (digits-vectors.json pins both edges). Twelve significant digits is a relative 1e-12,
     *       the level a computed double's noise reaches. From 1e8 up to this field's 2^53 edge,
     *       JUnit's cap is a third decimal, short of these four places: a FAIL at 100000000.0004
     *       over {@code gt 1e8} is 100000000.0004 here and "100000000.00" in JUnit's line, on its
     *       band. That is JUnit's cap, not this field's.</li>
     *   <li>Past 2^53 / 10^4 (9.007E11) a value is written as it is, Digits' own edge, where round4
     *       moved some doubles by one ulp below its 2^63 edge (the last double below 2^49 among
     *       them): the number JUnit and the page show. Every number below that edge and clear of
     *       its bands is round4's, bit for bit.</li>
     *   <li>A value that is not finite keeps round4's old path, which writes a number rather than
     *       the quoted {@code "Infinity"}: an {@code avg} that overflows reads
     *       9.223372036854776E14. NaN never gets here (the caller writes {@code null}).</li>
     * </ul>
     */
    private static double serverActualOf(double value, ServerKpi kpi) {
        return Double.isFinite(value)
                ? Digits.beside(value, 4, Digits.bands(kpi.warn(), kpi.fail())) : round4(value);
    }

    /**
     * {@code attainment.consumed}: the fraction of the budget used, written beside the line it is
     * measured against - the whole budget, 1.0 - as an actual is beside its band (L14). Four places,
     * plus whatever digits keep it on its own side of 1.0 ({@link Digits#beside}). round4 alone wrote
     * a FAIL at 373.8625 over {@code gt 373.86} (1.0000067) as 1.0 beside {@code "breached": true},
     * and a PASS at 211.405 under {@code gte 211.41} (0.99998) as 1.0 beside {@code "breached": false}:
     * one number for two verdicts, and the number that means "exactly on the line". They are 1.00001
     * and 0.99998 now. A value exactly on the line (a {@code gte} met to the digit, a breach) is 1.0,
     * because it is; one clear of the line is round4's number, bit for bit.
     *
     * <ul>
     *   <li>No band of 0: a budget barely touched is still 0.0, as it was, at four places.</li>
     *   <li>Float noise is the stated exception, as for every actual: a breach that division puts
     *       within an ulp of 1.0 is written on it.</li>
     *   <li>Past 2^53 / 10^4 the value is written as measured (Digits' edge), where round4 moved some
     *       doubles one ulp; a value that is not finite keeps round4's old path, which writes a number
     *       rather than the quoted {@code "Infinity"}.</li>
     * </ul>
     *
     * <p>The page's Budget label follows the same rule at whole percents, beside 100%, through
     * {@code WIR.digits.beside} ({@code budgetLabel} in report.js): it is not this number, which keeps
     * four places of the fraction (0.9876 here is "99%" there), but it is never written on or across
     * the line either.
     */
    private static double consumedOf(double consumed) {
        return Double.isFinite(consumed) ? Digits.beside(consumed, 4, 1.0) : round4(consumed);
    }

    private static double round2(double value) {
        return round(value, 100.0);
    }

    /** For ratios, where 2dp is coarse enough to turn a real fraction into a flat zero. */
    private static double round4(double value) {
        return round(value, 10_000.0);
    }

    /**
     * 2^63, exactly. {@link Math#round(double)} returns {@code Long.MAX_VALUE} for anything at or
     * above it, and {@code Long.MIN_VALUE} at or below its negation.
     */
    private static final double LONG_EDGE = 0x1p63;

    /**
     * {@code Math.round(value * scale) / scale}, except where {@code Math.round} would clamp (N4).
     *
     * <p>It saturated: every value whose scaled form reached 2^63 came back as the one constant
     * {@code Long.MAX_VALUE / scale}, so every server {@code actual} from 9.223372036854776E14 up
     * was written as 9.223372036854776E14 - a FAIL at 3E15 against {@code gt 2E15} read as below
     * its own limit, and a PASS against an {@code lt} band as breaching it, while JUnit printed
     * the true number. Past the edge a double's spacing is at least 1/8 at 4dp (16 at 2dp), so
     * it has no digit beyond the third decimal and there is nothing to round: it is written as
     * measured.
     *
     * <p>Below the edge this is the old expression, bit for bit (0 differences over 3.9 million
     * values, both signs, the 100,000 doubles just under each edge included), so no number
     * written before the edge moves - not even where the old expression is one ulp off, as it is
     * on the last double below 2^49. NaN and the infinities keep the old path on purpose: NaN
     * writes 0.0 wherever a caller has not already written {@code null}, as it always did, and
     * the old path turns an infinity into a finite number Jackson writes as a number rather than
     * the quoted {@code "Infinity"} a consumer would choke on.
     */
    private static double round(double value, double scale) {
        double scaled = value * scale;
        return Double.isFinite(value) && Math.abs(scaled) >= LONG_EDGE ? value : Math.round(scaled) / scale;
    }
}
