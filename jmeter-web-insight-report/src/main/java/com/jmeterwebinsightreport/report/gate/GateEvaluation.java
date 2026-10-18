package com.jmeterwebinsightreport.report.gate;

import com.jmeterwebinsightreport.core.assertion.AssertionEvaluator;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.RuleResult;
import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.SamplerVerdicts;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.report.annotation.AssertionRuleMapper;
import com.jmeterwebinsightreport.report.annotation.LoadValidityMigration;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place a run is judged.
 *
 * <p>The HTML badge, the CI/CD JSON and the JUnit XML used to evaluate the gate independently —
 * three constructions of the same evaluator, from three call sites, each free to drift from the
 * others. They did not, but nothing stopped them, and "the report says PASS and the pipeline
 * says FAIL" is the least debuggable bug a reporting tool can have. Now they share this.
 *
 * <p>Assertions are the one mechanism. Nothing is translated into a rule behind the reader's
 * back, and a run that declared nothing says NOT EVALUATED rather than reporting silence as
 * success.
 */
public final class GateEvaluation {

    private static final Logger log = LoggerFactory.getLogger(GateEvaluation.class);

    private final List<AssertionRule> rules;
    private final GateSummary gate;
    private final SamplerVerdicts verdicts;
    private final List<AssertionValidator.Finding> findings;

    private GateEvaluation(List<AssertionRule> rules, GateSummary gate, SamplerVerdicts verdicts,
                           List<AssertionValidator.Finding> findings) {
        this.rules = rules;
        this.gate = gate;
        this.verdicts = verdicts;
        this.findings = findings;
    }

    /** An evaluation with nothing declared — the run was reported, not judged. */
    public static GateEvaluation none() {
        return noneWith(Collections.<AssertionValidator.Finding>emptyList());
    }

    /**
     * No gate, but something to say about why.
     *
     * <p>A file that declares no runnable rule leaves the run NOT EVALUATED — correctly. The
     * findings are what stop that reading as nothing-to-see: they reach the log, the CI/CD JSON
     * and the report notice on a run that otherwise has no gate to hang a message on.
     */
    public static GateEvaluation noneWith(List<AssertionValidator.Finding> findings) {
        GateSummary empty = new AssertionEvaluator()
                .evaluate(Collections.<AssertionRule>emptyList(), new ReportData());
        return new GateEvaluation(Collections.<AssertionRule>emptyList(), empty,
                SamplerVerdicts.from(empty), findings);
    }

    public static GateEvaluation of(ReportData data, ReportAnnotations annotations) {
        return of(data, annotations, null);
    }

    /**
     * @param loadProblem what stopped the annotations file being read at all, or {@code null}
     *
     * <p>Separate from the per-rule findings because it is a different kind of wrong: those say
     * "rule 4 of 9 is broken", this says "there is no rule 4, and no rule 1 either". It arrives
     * as an ERROR finding under the id {@code annotations} so it travels the route that already
     * exists — jmeter.log, the CI/CD JSON {@code findings[]}, and the report's findings pane —
     * rather than needing a fifth channel of its own.
     */
    public static GateEvaluation of(ReportData data, ReportAnnotations annotations,
                                    String loadProblem) {
        List<AssertionRule> rules = new ArrayList<>();
        List<AssertionValidator.Finding> findings = new ArrayList<>();
        if (loadProblem != null) {
            findings.add(AssertionValidator.finding(
                    AssertionValidator.Severity.ERROR, "annotations", loadProblem));
        }

        // Declaration order is the order the outputs use, and a hand-written rule is the thing
        // its author is looking for.
        if (annotations != null) {
            // Before mapping, and here rather than only in AnnotationLoader: a legacy
            // loadValidity block becomes an ordinary entry in assertions[], so everything
            // downstream — the mapper, the evaluator, the payload, the saved document — sees
            // one kind of rule and no rule is special. Idempotent, so running it on an
            // already-migrated document does nothing, and running it on annotations built
            // directly (as the tests do) is not a path that can skip the migration. The notice
            // reads what was migrated, not what this call migrated: on a real run the loader
            // already did it and this call finds nothing (GAP-1).
            annotations.migrateLegacyBlocks();
            String migrated = annotations.migratedRuleId();
            if (migrated != null) {
                findings.add(AssertionValidator.finding(
                        AssertionValidator.Severity.WARNING, migrated,
                        LoadValidityMigration.describe(migrated)));
            }

            AssertionRuleMapper.Result mapped =
                    new AssertionRuleMapper().map(annotations.getAssertions());
            rules.addAll(mapped.rules());
            findings.addAll(mapped.findings());
        }
        if (rules.isEmpty()) {
            // No gate — but anything the loader rejected still has to be heard, or the one run
            // that most needs the warning is the one that prints nothing.
            logFindings(findings);
            // And say so even when there is no finding. This is the exit taken by a typo'd
            // -Jwebinsight.report.annotations path and by a file that failed to parse
            // (AnnotationLoader catches everything and returns EMPTY), and it used to be
            // completely silent — no summary line, and a green "ALL PASSED" badge on the HTML.
            // A gate that was configured and did not run must not look like one that was never
            // configured.
            log.info("Quality gate: NOT EVALUATED — no assertions were declared"
                    + (annotations == null ? " (no annotations file was loaded)" : ""));
            return findings.isEmpty() ? none() : noneWith(findings);
        }

        findings.addAll(new AssertionValidator().validate(rules, data));
        logFindings(findings);

        GateSummary gate = new AssertionEvaluator().evaluate(rules, data);
        SamplerVerdicts verdicts = SamplerVerdicts.from(gate);
        logSummary(gate);
        return new GateEvaluation(rules, gate, verdicts, findings);
    }

    private static void logFindings(List<AssertionValidator.Finding> findings) {
        for (AssertionValidator.Finding f : findings) {
            if (f.severity() == AssertionValidator.Severity.ERROR) {
                // ERROR, not WARN: a rule that cannot run has switched part of the gate off,
                // and a pipeline watching only the console would otherwise see unqualified
                // success. The report is still generated — it is what you need to fix this.
                log.error("Invalid {}", f.describe());
            } else {
                log.warn("Questionable {}", f.describe());
            }
        }
    }

    private static void logSummary(GateSummary gate) {
        if (!gate.hasGate()) {
            // Said nothing at all, which made the single worst failure mode in the feature —
            // the gate silently stopped gating — produce zero output on every channel. A
            // typo'd annotations path, a malformed file and a tag selection that matched
            // nothing all land here, and each used to be indistinguishable from a run that was
            // never meant to have a gate.
            log.info("Quality gate: NOT EVALUATED — no assertions were declared");
            return;
        }
        // The reasons come from the enum, not a hand-written format string: the string named
        // four and compiled happily when a fifth reason existed, so the one log line about the
        // gate silently omitted it. Non-zero reasons only, in display order, with label()'s
        // words — the same line the console prints, so the log and the terminal cannot describe
        // one run in two vocabularies. (The old line printed every reason including zeroes;
        // with seven of them that is wallpaper.)
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
        log.info("Quality gate: {} — {} declared, {} passed, {} warned, {} failed, "
                        + "{} not judged{}",
                gate.status() == null ? "NOT EVALUATED" : gate.status(),
                gate.declared(), gate.passed(), gate.warned(), gate.failed(),
                gate.notJudged(), reasons.length() == 0 ? "" : " (" + reasons + ")");
    }

    /** Every declared rule, in file order, derived ones included. */
    public List<AssertionRule> rules() {
        return Collections.unmodifiableList(rules);
    }

    public GateSummary gate() {
        return gate;
    }

    /** Per-sampler verdicts, for the sampler table and the JUnit failure text. */
    public SamplerVerdicts verdicts() {
        return verdicts;
    }

    public List<AssertionValidator.Finding> findings() {
        return Collections.unmodifiableList(findings);
    }

    /**
     * True when anything was declared at all. Drives {@code assertsEvaluated}.
     *
     * <p>Declared, not matched. This used to be {@code gate.hasGate() && !verdicts.isEmpty()},
     * and {@link SamplerVerdicts#from} only folds results that have matches — so a gate made
     * entirely of unmatched or match-less INVALID rules produced an empty verdict map, and the
     * report went out saying {@code status: "UNKNOWN"}, {@code assertsEvaluated: false} and no header
     * badge, while the {@code gate} block in the same file said FAIL and the JUnit XML failed.
     * Two answers, one run. The one that says "the gate is not set up" was the wrong one: the
     * gate was set up, it ran, and it failed because its rules found nothing to judge — which is
     * the failure this feature exists to make loud.
     */
    public boolean hasGate() {
        return gate.hasGate();
    }

    /**
     * The run verdict, or {@code null} when nothing was judged.
     *
     * <p>One line, because there is one vocabulary. This used to take the WORSE OF TWO VIEWS of
     * the same gate — the per-sampler fold and the gate's own verdict — and that was not
     * defensive coding, it was a symptom. {@link SamplerVerdicts#from} folds a result into a
     * sampler only if the result has matches, so an unmatched rule contributed nothing to the
     * per-sampler view; meanwhile a seven-value rule status had to be translated into the
     * three-value word the badge speaks. The two disagreed in real runs: a gate of one unmatched
     * rule reported {@code status: "UNKNOWN"} while the {@code gate} block said FAIL and the
     * JUnit XML failed the build. Two answers, one run.
     *
     * <p>Both causes are gone. There is no translation, and a rule that judged nothing is
     * NOT_JUDGED everywhere rather than a verdict in one view and an absence in the other. So
     * the gate's status IS the verdict, and nothing needs to defend against it being wrong.
     */
    public AssertionStatus overallStatus() {
        return gate.hasGate() ? gate.status() : null;
    }

    /**
     * The worst verdict per metric, across every rule that judges it.
     *
     * <p>Drives the mini-badges beside the summary cards. Those used to compare the run's
     * average p95 against a per-sampler threshold in a Thymeleaf expression — an aggregate
     * measured against the wrong population, computed in the template, by the one surface that
     * had not been folded into the gate.
     *
     * <p>Keyed by canonical metric name, so {@code p95} covers a named-sampler rule, a
     * pattern rule and a {@code scope: "overall"} rule alike: the badge answers "is anything
     * unhappy about p95 in this run", which is what a glance at a summary card is asking.
     * A metric no rule mentions gets no badge, because nothing judged it — that is the
     * declared-equals-judged principle showing through to the UI.
     *
     * <p>This carried a private {@code toVerdict} + {@code worse} pair that folded seven statuses
     * into three. Both are gone: there are four statuses, three of them gate, and the fold is
     * {@link AssertionStatus#worst} — the same one the evaluator and {@code GateSummary} use.
     * The badge cannot disagree with the row it summarises, because they are computed by the
     * same function rather than by two that happened to agree.
     */
    public Map<String, AssertionStatus> metricVerdicts() {
        Map<String, AssertionStatus> worstByMetric = new LinkedHashMap<>();
        for (RuleResult result : gate.ruleResults()) {
            if (!result.status().gates()) {
                continue;
            }
            AssertionRule rule = result.rule();
            String metric = rule.metric() != null
                    ? rule.metric().canonicalName() : rule.metricName();
            worstByMetric.put(metric,
                    AssertionStatus.worst(worstByMetric.get(metric), result.status()));
        }
        return Collections.unmodifiableMap(worstByMetric);
    }
}
