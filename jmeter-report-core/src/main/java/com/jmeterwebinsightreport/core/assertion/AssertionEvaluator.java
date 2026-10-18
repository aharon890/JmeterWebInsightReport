package com.jmeterwebinsightreport.core.assertion;

import com.jmeterwebinsightreport.core.model.OverallMetrics;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * Turns declared rules plus a run into a verdict.
 *
 * <p>The only evaluator. There is one rule model for the engine, the outputs and the report's
 * editor to share — two evaluators would mean building every one of those surfaces twice, and
 * keeping the two in step forever.
 *
 * <h2>Order of decisions</h2>
 *
 * <ol>
 *   <li>{@code enabled: false} → NOT_JUDGED / DISABLED.</li>
 *   <li>Unusable rule (unknown metric, bad pattern, no bands) → NOT_JUDGED / INVALID_RULE.</li>
 *   <li>Target selects nothing, or only rows with no samples → NOT_JUDGED / NO_MATCH.</li>
 *   <li>Per match: no measurable value → NOT_JUDGED / NO_VALUE; else {@code fail} first,
 *       then {@code warn}, else PASS.</li>
 *   <li>Rule status = worst JUDGED match, or NOT_JUDGED / NO_VALUE when none was.</li>
 * </ol>
 *
 * <p><strong>{@link #judge} is the only producer of PASS, WARN or FAIL</strong>, and only by
 * calling {@link Predicate#breaches} on a number the run actually measured. Every other exit
 * from this class is NOT_JUDGED with a reason. That is the whole shape of the model: a status
 * is a judgement, and nothing but a comparison can make one.
 *
 * <p>{@code fail} before {@code warn} matters when both would breach: a value past both bands
 * is a failure, not a warning, and testing warn first would report the milder of the two.
 */
public final class AssertionEvaluator {

    /**
     * A matched sampler with no samples is not judged.
     *
     * <p>Its latency metrics are not small — they are undefined, stored as zero. Judging them
     * means every "must be under X" threshold passes, so a plan entry that never executed
     * reports success. That is the same failure mode as an unmatched target, so it gets the
     * same treatment.
     */
    private static boolean cannotBeJudged(SamplerStatistics stat) {
        return stat.getSampleCount() <= 0;
    }

    /** Evaluate every rule against the run. */
    public GateSummary evaluate(List<AssertionRule> rules, ReportData data) {
        List<RuleResult> results = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return GateSummary.of(results);
        }

        OverallMetrics overall = OverallMetrics.of(data);
        Map<String, SamplerStatistics> byName = indexByName(data);

        for (AssertionRule rule : rules) {
            results.add(evaluateOne(rule, data, overall, byName));
        }
        return GateSummary.of(results);
    }

    private RuleResult evaluateOne(AssertionRule rule, ReportData data, OverallMetrics overall,
                                   Map<String, SamplerStatistics> byName) {
        if (!rule.enabled()) {
            return RuleResult.notJudged(rule, NotJudged.DISABLED);
        }

        String unusable = whyUnusable(rule);
        if (unusable != null) {
            return RuleResult.notJudged(rule, NotJudged.INVALID_RULE, unusable);
        }

        List<String> targets;
        try {
            targets = rule.target().resolve(data);
        } catch (PatternSyntaxException e) {
            return RuleResult.notJudged(rule, NotJudged.INVALID_RULE,
                    "pattern is not valid: " + e.getDescription());
        }

        boolean overallScope = rule.target().kind() == AssertionTarget.Kind.OVERALL;

        // Filter out rows that exist but cannot be judged, before deciding "unmatched": a rule
        // whose only match never ran is in the same position as one that matched nothing.
        List<String> judgeable = new ArrayList<>(targets.size());
        for (String name : targets) {
            if (overallScope) {
                if (overall.getTotalSamples() > 0) {
                    judgeable.add(name);
                }
            } else {
                SamplerStatistics stat = byName.get(name);
                if (stat != null && !cannotBeJudged(stat)) {
                    judgeable.add(name);
                }
            }
        }

        if (judgeable.isEmpty()) {
            // Nothing to judge, so nothing is judged — in neither direction. The rule cannot
            // fail the run and cannot pass it.
            //
            // It used to route through an `onMissing` policy that defaulted to FAIL, so renaming
            // a sampler failed the build. That is the engine deciding something the tester never
            // asked it to decide. The configuration problem is real and is reported as a
            // finding, a banner and the NO_MATCH count — what it is not is a verdict about
            // performance, because no performance was measured.
            return RuleResult.notJudged(rule, NotJudged.NO_MATCH);
        }

        List<RuleResult.Match> matches = new ArrayList<>(judgeable.size());
        for (String name : judgeable) {
            double actual = overallScope
                    ? rule.metric().valueForOverall(overall, data)
                    : rule.metric().valueFor(byName.get(name), data);
            matches.add(judge(rule, name, actual, overallScope));
        }

        // The fold, first of its two uses — the same AssertionStatus.worst that GateSummary
        // applies to rules. Only matches that GATE are folded, so an unmeasurable one neither
        // lifts the rule nor drags it down.
        //
        // This is where a real bug went. The old fold started at PASS and took the highest
        // SEVERITY across all matches, with INVALID sitting above FAIL — so one sampler the run
        // could not measure turned the whole rule INVALID and ERASED a genuine FAIL from another
        // sampler the very same rule had judged. A rule is now the worst of what it measured.
        AssertionStatus status = null;
        for (RuleResult.Match m : matches) {
            if (m.status().gates()) {
                status = AssertionStatus.worst(status, m.status());
            }
        }
        if (status == null) {
            // Every match resolved to a value the run could not produce. The rule ran and found
            // its targets, so the matches are kept: "selected three samplers, measured none of
            // them" and "selected nothing" are different problems and must not render the same.
            return RuleResult.unmeasurable(rule, matches, judgeable.size());
        }
        return RuleResult.of(rule, status, matches, judgeable.size());
    }

    private RuleResult.Match judge(AssertionRule rule, String name, double actual,
                                   boolean overallScope) {
        // The KEY, never the display text. A whole-run match was filed under "Whole run", and a
        // sampler can be called that, so the two verdicts merged (CORE-1); "Whole run" is only
        // how the run reads now (Match.label).
        String key = overallScope ? AssertionTarget.OVERALL_KEY : name;

        if (Double.isNaN(actual)) {
            // The metric is real but this run cannot produce it — e.g. p99.9 with no retained
            // distribution. Not a pass, and not a failure either: nothing was compared.
            return RuleResult.Match.notJudged(key, NotJudged.NO_VALUE);
        }
        if (rule.fail() != null && rule.fail().breaches(actual)) {
            return new RuleResult.Match(key, actual, AssertionStatus.FAIL, rule.fail());
        }
        if (rule.warn() != null && rule.warn().breaches(actual)) {
            return new RuleResult.Match(key, actual, AssertionStatus.WARN, rule.warn());
        }
        return new RuleResult.Match(key, actual, AssertionStatus.PASS, null);
    }

    /**
     * Why the rule cannot run, or {@code null} when it can.
     *
     * <p>An unknown metric is the important one. Left alone it resolves to no value, no band
     * breaches, and the rule reports PASS — a typo'd metric name silently switching the gate
     * off is the precise failure this feature exists to prevent.
     *
     * <p>It is NOT_JUDGED / INVALID_RULE rather than a failure. The rule reports nothing, which
     * is the truth, and the file being wrong is said on six channels that all name the rule: the
     * finding (with "did you mean"), jmeter.log, stdout, the {@code notJudged.invalidRule} count
     * in the CI/CD JSON, the findings pane, and a banner the tab cannot be read past. Whether
     * loud means fatal is the pipeline's call, not this class's.
     *
     * <p>The answer itself lives in {@link Usability}, and so does the validator's. This method
     * used to hold its own copy of it — five branches that the validator also implemented, two of
     * them worded differently for the same fault, and three faults the validator knew about and
     * this one did not. A rule could therefore be reported as broken and judged anyway, which is
     * how a band that could never fire went on contributing PASS to a green gate while the
     * finding beside it said, in those words, that it would.
     */
    private String whyUnusable(AssertionRule rule) {
        Usability refusal = Usability.refusal(rule);
        return refusal == null ? null : refusal.reasonText();
    }


    private static Map<String, SamplerStatistics> indexByName(ReportData data) {
        Map<String, SamplerStatistics> byName = new LinkedHashMap<>();
        if (data != null && data.getSamplerStatistics() != null) {
            for (SamplerStatistics s : data.getSamplerStatistics()) {
                byName.put(s.getSamplerName(), s);
            }
        }
        return byName;
    }

}
