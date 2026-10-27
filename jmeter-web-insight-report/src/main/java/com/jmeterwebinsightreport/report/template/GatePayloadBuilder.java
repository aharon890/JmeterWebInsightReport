package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionTarget;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.MetricRef;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.assertion.RuleResult;
import com.jmeterwebinsightreport.core.assertion.Usability;
import com.jmeterwebinsightreport.core.model.PercentileSource;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import com.jmeterwebinsightreport.report.gate.ServerGateEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything the report's Asserts tab needs, in the payload.
 *
 * <p>The report is one self-contained file on {@code file://} — no server to ask. So the page
 * cannot re-derive any of this: the rules as declared (a verdict alone is lossy — you cannot
 * edit a threshold you were not told), the results per rule, the metric vocabulary the editor
 * offers, and the whole annotations document so a save can reproduce it without dropping keys
 * it did not understand.
 *
 * <p>Shipping it all now means phase 2 is genuinely UI-only.
 */
final class GatePayloadBuilder {

    private static final Logger log = LoggerFactory.getLogger(GatePayloadBuilder.class);

    /**
     * Most matches carried per rule.
     *
     * <p>A {@code match: "*"} rule over a 500-sampler run would otherwise put 500 result
     * objects into the HTML for every rule that shape. Twenty such rules is 10 000 objects, and
     * the report has a hard self-containment promise — the payload is the file. The cap is
     * generous enough that no realistic gate hits it, and when one does the total is still
     * carried so the UI can say "and 300 more" rather than quietly implying the rule judged
     * four things.
     */
    static final int MAX_MATCHES_PER_RULE = 200;

    private GatePayloadBuilder() {
    }

    static void addTo(Map<String, Object> chartData, GateEvaluation evaluation,
                      ReportAnnotations annotations, ReportData data) {
        if (evaluation == null) {
            return;
        }
        // The document goes in even with no rules: the report can still save notes and markers,
        // and dropping unknown keys on that path would be the same data loss by another route.
        if (annotations != null) {
            chartData.put("annotationsDocument", documentOf(annotations));
        }
        // The vocabulary goes in unconditionally, and it is the one thing here that must. It
        // describes what the engine CAN judge, not what this run declared, so withholding it from
        // a gateless report said "there are no metrics" when the truth was "there are no rules" —
        // and the editor, which offers exactly this list, opened with an empty dropdown on the one
        // report where a tester has everything still to declare. The ⊕ worked only because it sets
        // the metric directly and never consults the list.
        chartData.put("assertMetrics", vocabulary());

        // Findings go in BEFORE the no-gate exit, and that ordering is the whole point.
        //
        // They used to be emitted after it, so a run with no rules carried none — which is
        // exactly the run that has them: a typo'd -Jwebinsight.report.annotations path, a file
        // that failed to parse, or one whose every rule the loader rejected. The report went out
        // silent on the one page a tester opens to find out why nothing was judged, while the
        // same findings sat in jmeter.log where nobody was looking.
        //
        // A findings list is not part of the gate. It is what the gate could not become.
        chartData.put("assertFindings", findingsOf(evaluation));

        if (!evaluation.gate().hasGate()) {
            return;
        }

        chartData.put("gate", gateOf(evaluation.gate()));
        chartData.put("assertions", rulesOf(evaluation.gate()));
        chartData.put("assertPercentiles", ruleNamedPercentiles(evaluation, data));
    }

    /**
     * The server gates, for M5's tab: the metric catalogue, always; then whether anything was
     * declared and evaluated, the block's findings, and — once judged against a payload — the
     * same {@code serverGates[]} and server-assert entries the CI/CD JSON carries, from the same
     * builders, and the verdict the fold chose ({@link #verdictOf}). A run that declared no server
     * block carries the catalogue and nothing else.
     *
     * <p>One difference, on purpose: a server-assert entry's {@code actual} is Java's double here,
     * where the JSON writes it at four places, beside the assert's bands
     * ({@link CiCdJsonSerializer#serverKpiEntries(ServerGateEvaluation, boolean)}, F7, S2). The page
     * chooses its own digits beside a band (SF15) and cannot get back the ones a rounded number lost;
     * "the same entries" is the same builder, not the same rounding.
     *
     * <p>The chart-data key stays {@code serverKpis}: it is the page's internal payload, read
     * only by {@code report.js}, while the CI/CD JSON's consumed contract says
     * {@code serverAsserts} (D25 renamed what a user reads, not internal names).
     */
    static void addServerTo(Map<String, Object> chartData, ServerGateEvaluation server) {
        // The component types and their metrics, so the page resolves a catalogue metric to the
        // query Java resolved it to, from the same files (plans/server-metrics-components.md §7a).
        // Unconditionally, for the reason assertMetrics is (addTo): it describes what the engine
        // CAN collect and judge, not what this run declared. Put after the no-block exit, a report
        // with no server block - or a block with components and no asserts yet - offered only
        // "Custom" in its empty Server tab and Add component dialog, called a valid postgres
        // component "not one of custom" twice, and opened "+ metric" on a dead end (SF5). It gives
        // up the old "a report with no server block is byte-identical" property: ~9 KB against a
        // 10 MB externalDataThreshold.
        chartData.put("serverCatalogue", Catalogue.builtIn().pageJson());
        if (server == null || (!server.declared() && server.findings().isEmpty()
                && server.payloadRefusal() == null)) {
            return;
        }
        chartData.put("serverDeclared", server.declared());
        chartData.put("serverEvaluated", server.evaluated());
        chartData.put("serverFindings", findingEntries(server.findings()));
        // A payload that was configured and refused (D12: not embedded) still has to reach the
        // tab as a refusal with its reason. Without it the tab could only say "no collected
        // payload was given", which is false, and whose remedy - run wir-collect - re-runs the
        // command whose output was just refused. The loader's PUBLISHED sentence: the file's name
        // and what to do, never the absolute path (a home directory, in an archived report) nor,
        // for an unreadable file, the parser's message - which quotes a token from a file that is
        // by definition not the collector's redacted output. Those stay on the console.
        if (server.payloadRefusal() != null) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("reason", server.payloadRefusal().name());
            refused.put("detail", server.payloadDetail());
            chartData.put("serverPayloadRefused", refused);
        }
        if (server.evaluated()) {
            chartData.put("serverGates", CiCdJsonSerializer.serverGateEntries(server));
            // forPage: Java's actual, not the JSON's four places of it (F7).
            chartData.put("serverKpis", CiCdJsonSerializer.serverKpiEntries(server, true));
            chartData.put("serverVerdict", verdictOf(server));
        }
    }

    /**
     * The server verdict the run was gated on, as THE fold decided it (D24, SF22):
     * {@code status} is {@link ServerGateEvaluation#serverStatus()}, the CI/CD JSON's
     * {@code serverStatus}, and {@code component}, {@code label} and {@code others} are the header
     * chip's — the entry the word was taken from, how a reader names it, and how many declared
     * components it does not name. {@code reserved} is the {@value ServerGateEvaluation#NO_GATE_ID}
     * entry, exactly as {@code serverComponents[]} writes it, when the run has one.
     *
     * <p>So the Summary card prints the build's word rather than folding {@code serverGates} a
     * second time. That list is the DECLARED components only (the strip's badges), so a fold over
     * it could never see the reserved entry, and read WARN beside a {@code NOT EVALUATED} chip on a
     * run whose asserts named an undeclared component — two rules choosing one word, which D24
     * forbids. Only on an evaluated run, as the chip and {@code serverStatus} are.
     */
    static Map<String, Object> verdictOf(ServerGateEvaluation server) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        ServerGateEvaluation.Chip chip = server.headerChip();
        verdict.put("status", server.serverStatus());
        verdict.put("component", chip == null ? null : chip.getGateId());
        verdict.put("label", chip == null ? null : chip.getLabel());
        verdict.put("others", chip == null ? 0 : chip.getOthers());
        // The rest by state, as the chip writes them (B2): the Summary card prints this text.
        verdict.put("othersText", chip == null ? "" : chip.getOthersText());
        GateSummary reserved = server.reportedGates().get(ServerGateEvaluation.NO_GATE_ID);
        if (reserved != null) {
            verdict.put("reserved", CiCdJsonSerializer.serverGateEntries(server,
                    Collections.singletonMap(ServerGateEvaluation.NO_GATE_ID, reserved)).get(0));
        }
        return verdict;
    }

    /**
     * Quantiles that rules name but the standard grid does not carry.
     *
     * <p>The payload already ships p50/p75/p90/p95/p99/p99.9 for every sampler. A rule is free
     * to name p97.5, and the editor has to re-evaluate it live as the tester drags the
     * threshold — which it can only do if the number is on the page. Resolving it here, from
     * the retained digest, is the difference between "tune any rule and watch the verdict" and
     * "tune the six we anticipated".
     *
     * <p>Empty in the common case: most rules name a grid percentile and this costs nothing.
     */
    private static Map<String, Object> ruleNamedPercentiles(GateEvaluation evaluation,
                                                            ReportData data) {
        Set<Double> wanted = new LinkedHashSet<>();
        for (AssertionRule rule : evaluation.rules()) {
            MetricRef metric = rule.metric();
            if (metric != null && metric.percentile() != null
                    && !MetricRef.STANDARD_GRID.contains(metric.percentile())) {
                wanted.add(metric.percentile());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (wanted.isEmpty() || data == null) {
            return out;
        }

        for (SamplerStatistics stat : safeStats(data)) {
            PercentileSource dist = data.percentileSource(stat.getSamplerName());
            if (dist == null || dist.sampleCount() <= 0) {
                continue;
            }
            Map<String, Object> perSampler = new LinkedHashMap<>();
            for (Double p : wanted) {
                perSampler.put(percentileKey(p), judged(dist.percentile(p)));
            }
            out.put(stat.getSamplerName(), perSampler);
        }

        PercentileSource overall = data.overallPercentileSource();
        if (overall != null && overall.sampleCount() > 0) {
            Map<String, Object> forRun = new LinkedHashMap<>();
            for (Double p : wanted) {
                forRun.put(percentileKey(p), judged(overall.percentile(p)));
            }
            out.put(OVERALL_KEY, forRun);
        }
        return out;
    }

    /** The key the client evaluator uses for a whole-run value; never a real sampler name. The
        engine's own key, so the payload and the evaluator cannot spell it two ways. */
    static final String OVERALL_KEY = AssertionTarget.OVERALL_KEY;

    /**
     * The key a quantile is filed under, in the spelling the client evaluator looks it up by.
     *
     * <p>{@code String.valueOf(85.0)} is {@code "85.0"}. The browser computes its key with
     * {@code String(metric.percentile)}, and {@code String(85)} is {@code "85"} — so a
     * {@code p85} rule that Java evaluated as PASS looked up {@code "85"}, found nothing, got
     * NaN and rendered INVALID. {@code p97.5} was unaffected, which is why the drift survived:
     * the vectors only ever exercised a fractional percentile. Whole numbers lose the
     * {@code .0} here so both sides spell the same key.
     */
    private static String percentileKey(double percentile) {
        return percentile == Math.rint(percentile)
                ? String.valueOf((long) percentile)
                : String.valueOf(percentile);
    }

    private static List<SamplerStatistics> safeStats(ReportData data) {
        return data.getSamplerStatistics() == null
                ? java.util.Collections.<SamplerStatistics>emptyList()
                : data.getSamplerStatistics();
    }

    /**
     * The gate block the report's client reads.
     *
     * <p>The same builder the CI/CD JSON uses, not a copy of it. The two were byte-identical
     * and independent, so a field added to one would silently not exist in the other — and the
     * report's tab and the pipeline's JSON are supposed to be the same verdict.
     */
    private static Map<String, Object> gateOf(GateSummary gate) {
        return CiCdJsonSerializer.gateBlock(gate);
    }

    /**
     * The rules as declared, plus what happened.
     *
     * <p>Predicates are carried as plain operator/value pairs rather than a rendered string,
     * because the editor has to load a band back into two form fields — and because
     * re-evaluating client-side is only trivial while the comparison is still a comparison.
     */
    private static List<Map<String, Object>> rulesOf(GateSummary gate) {
        List<RuleResult> results = gate.ruleResults();
        List<Map<String, Object>> out = new ArrayList<>(results.size());
        int truncated = 0;

        for (RuleResult result : results) {
            AssertionRule rule = result.rule();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", rule.id());
            entry.put("description", rule.description());
            entry.put("target", targetOf(rule.target()));
            entry.put("metric", rule.metric() != null
                    ? rule.metric().canonicalName() : rule.metricName());
            entry.put("unit", rule.metric() != null ? rule.metric().unit().suffix() : "");
            entry.put("enabled", rule.enabled());
            if (rule.warn() != null) {
                entry.put("warn", predicateOf(rule.warn()));
            }
            if (rule.fail() != null) {
                entry.put("fail", predicateOf(rule.fail()));
            }
            /* What the file declared and the loader could not use. The browser cannot derive
               these: an unreadable band arrives indistinguishable from an absent one, and a
               retired key never reaches the payload at all — so without shipping them the tab
               would show PASS for a rule the engine refused, and the report and the pipeline
               would disagree about the same file. Only the sentences travel; the browser needs
               to say what is wrong, not to re-derive it. */
            if (!rule.declarationProblems().isEmpty()) {
                List<String> problems = new ArrayList<>(rule.declarationProblems().size());
                for (Usability u : rule.declarationProblems()) {
                    problems.add(u.reasonText());
                }
                entry.put("declarationProblems", problems);
            }

            entry.put("status", result.status().wireName());
            /* Whether the AUTHOR named this rule. The client writes the document back, and a
               derived or positional name written into assertions[] is a key the tester never
               typed — added by a save they made for an unrelated reason. The browser cannot work
               this out for itself: by the time a rule reaches it the derived name IS its id, and
               looking the id up in the document finds nothing precisely because it was never
               there. So the fact travels with the rule. */
            entry.put("idDeclared", rule.idDeclared());
            entry.put("unmatched", result.unmatched());
            entry.put("matched", result.matchedTotal());
            // The reason travels beside the status, never instead of it. The client mirror
            // re-evaluates as the user edits, so it has to be able to reach the same NOT_JUDGED
            // for the same cause — a status with no reason would leave it guessing.
            if (result.notJudged() != null) {
                entry.put("notJudged", result.notJudged().wireName());
            }
            if (result.notJudgedDetail() != null) {
                entry.put("notJudgedDetail", result.notJudgedDetail());
            }

            RuleResult capped = result.cappedTo(MAX_MATCHES_PER_RULE);
            boolean wasCapped = capped.matches().size() < result.matches().size();
            if (wasCapped) {
                truncated++;
            }
            /* Said in the DATA, not only in a log line and the table's "and N more".
             *
             * This list and the CI/CD JSON's carry different sets for the same rule: the report
             * keeps the WORST 200 by severity because those are the breaches someone opened the
             * page to see, and the JSON keeps all of them because a pipeline should not have a
             * contract silently truncated under it. Both are right, and until now neither said
             * so — a reader comparing them found two different answers and nothing explaining
             * why. `matched` already gave the true total; this says the list beside it is a
             * selection rather than the remainder. */
            if (wasCapped) {
                entry.put("resultsCapped", true);
            }
            List<Map<String, Object>> matches = new ArrayList<>(capped.matches().size());
            for (RuleResult.Match match : capped.matches()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sampler", match.label());
                /* The number Java judged, not two decimals of it (N3): the Budget bar judges this
                   value, and round2 put a FAIL at 373.8625 ON its `gt 373.86` — drawn green on
                   load, red after any edit, green again after Discard. How many digits to write
                   is the page's question (WIR.digits). */
                m.put("actual", judged(match.actual()));
                m.put("status", match.status().wireName());
                if (match.notJudged() != null) {
                    m.put("notJudged", match.notJudged().wireName());
                }
                matches.add(m);
            }
            entry.put("results", matches);
            out.add(entry);
        }

        if (truncated > 0) {
            // Never silent: a capped payload that said nothing would look like a smaller gate.
            log.info("{} assertion rule(s) matched more than {} samplers; the report shows the "
                            + "worst {} of each and reports the true total",
                    truncated, MAX_MATCHES_PER_RULE, MAX_MATCHES_PER_RULE);
        }
        return out;
    }

    private static Map<String, Object> targetOf(AssertionTarget target) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("kind", target.kind().name().toLowerCase(Locale.ROOT));
        t.put("text", target.describe());
        switch (target.kind()) {
            case SAMPLER:
                t.put("sampler", target.names().isEmpty() ? null : target.names().get(0));
                break;
            case SAMPLERS:
                t.put("samplers", target.names());
                break;
            case MATCH:
                t.put("match", target.pattern());
                if (!target.excludes().isEmpty()) {
                    t.put("exclude", target.excludes());
                }
                break;
            case INVALID:
                // No form to carry — `text` already holds the problem. The report writes this
                // rule back from the document it loaded rather than from here, because a
                // placeholder is not what the file said and a save must not pretend it was.
                break;
            default:
                t.put("scope", "overall");
                break;
        }
        return t;
    }

    private static Map<String, Object> predicateOf(Predicate predicate) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("op", predicate.op().key());
        p.put("value", predicate.value());
        return p;
    }

    /**
     * What the editor may offer, with units and scope.
     *
     * <p>Hardcoding this list in the JavaScript would put the vocabulary in two places and let
     * the editor offer a metric the engine rejects — which the tester would only discover on
     * the next run, after saving.
     */
    /**
     * The metrics the editor offers, per scope.
     *
     * <p>{@code percentileGrid} used to be here too — the same six quantiles the two lists
     * already carry by name, shipped a second time in a second shape, and read by nothing. The
     * editor needs the names, which it has.
     */
    private static Map<String, Object> vocabulary() {
        Map<String, Object> vocab = new LinkedHashMap<>();
        vocab.put("sampler", describeAll(MetricRef.vocabulary(false)));
        vocab.put("overall", describeAll(MetricRef.vocabulary(true)));
        return vocab;
    }

    private static List<Map<String, Object>> describeAll(List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>(names.size());
        for (String name : names) {
            MetricRef ref = MetricRef.parse(name);
            if (ref == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ref.canonicalName());
            m.put("unit", ref.unit().suffix());
            m.put("family", ref.family().name().toLowerCase(Locale.ROOT));
            out.add(m);
        }
        return out;
    }

    /** Validation findings, so the report can show the notice the log and CI JSON also carry. */
    private static List<Map<String, Object>> findingsOf(GateEvaluation evaluation) {
        return findingEntries(evaluation.findings());
    }

    private static List<Map<String, Object>> findingEntries(List<AssertionValidator.Finding> findings) {
        List<Map<String, Object>> out = new ArrayList<>(findings.size());
        for (AssertionValidator.Finding f : findings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("severity", f.severity().name().toLowerCase(Locale.ROOT));
            entry.put("ruleId", f.ruleId());
            entry.put("message", f.message());
            out.add(entry);
        }
        return out;
    }

    /**
     * The annotations file as loaded, so a save can reproduce it.
     *
     * <p>Unknown keys included. The report writes this file back, and dropping what it did not
     * understand would punish anyone who hand-added a key or opened a file from a newer plugin
     * — which is precisely the failure mode that makes people stop trusting an editor.
     */
    private static Map<String, Object> documentOf(ReportAnnotations a) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", a.getVersion());
        doc.put("testNotes", a.getTestNotes());
        if (a.getAssertions() != null && !a.getAssertions().isEmpty()) {
            doc.put("assertions", a.getAssertions());
        }
        if (a.getSamplerNotes() != null && !a.getSamplerNotes().isEmpty()) {
            doc.put("samplerNotes", a.getSamplerNotes());
        }
        if (a.getTimelineMarkers() != null && !a.getTimelineMarkers().isEmpty()) {
            doc.put("timelineMarkers", a.getTimelineMarkers());
        }
        if (a.getComparisonThresholds() != null) {
            doc.put("comparisonThresholds", a.getComparisonThresholds());
        }
        // Typed since M2, so it is no longer among the unknown keys — but it travels whole, its
        // own unknown keys included, for the same reason they do: the editor writes it back.
        // publishable(), not the block itself: a literal secret under sources.*.auth (which the
        // mapper refuses) would otherwise be written into every HTML and sidecar this run
        // produces. The mask replaces the value; the env-var NAMES the file is supposed to
        // carry are untouched. M3's canary test extends this to the .js and the JSON.
        if (a.getServerMetrics() != null) {
            doc.put("serverMetrics", a.getServerMetrics().publishable());
        }
        // No loadValidity branch, deliberately. The block is consumed by the migration before
        // anything reaches here, so it is always null — and writing it back would put the check
        // in the saved file twice, once as the block and once as the rule it became.
        doc.putAll(a.getUnknownKeys());
        return doc;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * A number the page judges or writes beside a band, at the precision Java judged it: a quantile
     * only the client evaluator reads (R2-1, M7d X), so a rule re-judged on the Asserts tab after an
     * edit must not flip at an edge the run does not; and each match's {@code actual} (N3), which
     * the Budget bar judges and every actual on the tab is written from. The server's entries take
     * the same course (F7, {@code forPage}). The CI/CD JSON writes its own digits, from its own
     * builder; this is the page's payload, not that contract.
     *
     * <p>NaN is null: a number the run could not measure, which the page reads as NO_VALUE. It used
     * to go through round2, and {@code Math.round(NaN)} is 0, so a rule-named quantile the digest
     * could not answer reached the page as a measured 0.0 (plan L16). The infinities still go
     * through round2, as JSON cannot carry them.
     */
    private static Double judged(double value) {
        if (Double.isNaN(value)) {
            return null;
        }
        return Double.isInfinite(value) ? round2(value) : value;
    }
}
