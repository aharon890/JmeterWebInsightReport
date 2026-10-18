package com.jmeterwebinsightreport.report.annotation;

import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionTarget;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.assertion.Usability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the JSON shape into rules, collecting what is wrong rather than refusing to continue.
 *
 * <p>Jackson can only say "cannot deserialize"; it cannot say which rule, or what to write
 * instead. So the config classes accept anything and this maps it, producing a finding for
 * every problem it can name. A file with one bad rule still yields the other nine and still
 * produces a report — which is the artifact you need in order to fix the bad one.
 */
public final class AssertionRuleMapper {

    /** What the mapping produced: the rules it could build, and everything it could not. */
    public static final class Result {
        private final List<AssertionRule> rules;
        private final List<AssertionValidator.Finding> findings;

        Result(List<AssertionRule> rules, List<AssertionValidator.Finding> findings) {
            this.rules = rules;
            this.findings = findings;
        }

        public List<AssertionRule> rules() { return Collections.unmodifiableList(rules); }
        public List<AssertionValidator.Finding> findings() { return Collections.unmodifiableList(findings); }
    }

    public Result map(List<ReportAnnotations.AssertionConfig> configs) {
        List<AssertionRule> rules = new ArrayList<>();
        List<AssertionValidator.Finding> findings = new ArrayList<>();
        if (configs == null || configs.isEmpty()) {
            return new Result(rules, findings);
        }

        int index = 0;
        for (ReportAnnotations.AssertionConfig config : configs) {
            index++;
            String label = config.getId() != null && !config.getId().trim().isEmpty()
                    ? config.getId().trim()
                    // Positional, because a rule with no id and no usable target has nothing
                    // else to be called, and "assertions[3]" is at least findable in the file.
                    : "assertions[" + (index - 1) + "]";

            AssertionTarget target = readTarget(config, label, findings);

            AssertionRule.Builder builder = AssertionRule.builder(target, config.getMetric())
                    // The file's own label, so an unusable-target rule is named the same way the
                    // finding names it — and so two id-less broken rules cannot collide on one
                    // derived id.
                    .id(target.kind() == AssertionTarget.Kind.INVALID ? label : config.getId())
                    .description(config.getDescription())
                    .enabled(!Boolean.FALSE.equals(config.getEnabled()));

            builder.warn(readBand(config, "warn", label, findings, builder));
            builder.fail(readBand(config, "fail", label, findings, builder));

            // A key that used to mean something is not an unknown key, and the policy below is
            // written for unknown ones.
            retiredKeys(config, label, findings, builder);

            // Unknown keys are ignored in silence, and that is a policy rather than an
            // oversight. This plugin parses one specific shape; a key outside it is not a
            // defect in the tester's file, it is simply not ours. Warning about every one of
            // them treats the file as if it owed us something — and the warnings landed on the
            // findings pane, the CI/CD JSON and the log, so an unrecognised key from a newer
            // plugin, a hand-added note, or a field some other tool writes produced noise on
            // every run forever.
            //
            // Nothing is lost that matters. A key we do not know cannot change a verdict by
            // definition; @JsonAnySetter still round-trips it so a save does not eat it; and a
            // MISTYPED key that was meant to be one of ours still surfaces through its
            // consequence — `"faill": {...}` leaves the rule with no bands, which is
            // INVALID_RULE and loud.
            rules.add(builder.build());
        }
        return new Result(rules, findings);
    }

    /**
     * The target, or an {@link AssertionTarget#unusable} placeholder that keeps the rule alive.
     *
     * <p>Never null. Returning null used to drop the whole rule, which meant a typo'd target
     * removed itself from {@code gate.declared}, from every count, from
     * {@code assertions[]} and from the JUnit suite — three declared rules, one counted, and
     * {@code jq -e '.gate.failed == 0'} green. Every message below is now carried on the target
     * and reported as an INVALID rule, which is what a typo'd metric has always done.
     */
    private AssertionTarget readTarget(ReportAnnotations.AssertionConfig config, String label,
                                       List<AssertionValidator.Finding> findings) {
        ReportAnnotations.TargetConfig t = config.getTarget();
        if (t == null) {
            return unusable(label, findings, "no target — add one of \"sampler\", \"samplers\", "
                    + "\"match\" or {\"scope\": \"overall\"}");
        }

        List<String> forms = new ArrayList<>();
        if (notBlank(t.getSampler())) forms.add("sampler");
        if (t.getSamplers() != null && !t.getSamplers().isEmpty()) forms.add("samplers");
        if (notBlank(t.getMatch())) forms.add("match");
        if (notBlank(t.getScope())) forms.add("scope");

        if (forms.isEmpty()) {
            return unusable(label, findings, "target is empty — it needs one of \"sampler\", "
                    + "\"samplers\", \"match\" or {\"scope\": \"overall\"}");
        }
        if (forms.size() > 1) {
            // Picking one silently would make the rule judge something the author did not ask
            // for, which is worse than not running it.
            return unusable(label, findings, "target has more than one form ("
                    + String.join(", ", forms) + ") — exactly one is allowed");
        }
        switch (forms.get(0)) {
            case "sampler":
                return AssertionTarget.sampler(t.getSampler());
            case "samplers":
                return AssertionTarget.samplers(t.getSamplers());
            case "match":
                return AssertionTarget.match(t.getMatch(), t.getExclude());
            default:
                if (!"overall".equalsIgnoreCase(t.getScope())) {
                    return unusable(label, findings, "scope must be \"overall\", not \""
                            + t.getScope() + "\"");
                }
                return AssertionTarget.overall();
        }
    }

    /** Record the problem once and hand it to the target, which reports it as INVALID. */
    private AssertionTarget unusable(String label, List<AssertionValidator.Finding> findings,
                                     String problem) {
        findings.add(error(label, problem));
        return AssertionTarget.unusable(problem);
    }

    /**
     * Read one band. Exactly one operator, and its value has to be a number.
     *
     * <p>A band that cannot be read is recorded ON THE RULE rather than merely reported, and the
     * difference is the whole point. It used to produce a finding and return null, at which point
     * the rule was indistinguishable from one that never declared the band at all — so a rule
     * carrying {@code fail: {gt: 100, lt: 50}} beside a readable warn band was judged on the warn
     * band alone, its fail threshold silently never applied, and the run exited 0 with an ERROR
     * finding sitting in the output saying so. The finding is now rendered from the recorded fact
     * by the validator, so there is still exactly one of them, and the same fact refuses the rule.
     */
    private Predicate readBand(ReportAnnotations.AssertionConfig config, String which, String label,
                               List<AssertionValidator.Finding> findings,
                               AssertionRule.Builder builder) {
        // A band the loadValidity migration could not build, refused in the sentence that names the
        // field the file wrote (L3) — "loadValidity.target must be a finite number, not NaN" — not
        // in this reader's, which would name a band the file never wrote.
        String refused = config.bandRefusal(which);
        if (refused != null) {
            return unreadable(label, findings, builder, which, refused);
        }
        Map<String, Object> band = "warn".equals(which) ? config.getWarn() : config.getFail();
        if (band == null || band.isEmpty()) {
            return null;   // absent, which is allowed: one band is enough
        }
        if (band.size() > 1) {
            return unreadable(label, findings, builder, which, which + " has " + band.size()
                    + " operators (" + String.join(", ", band.keySet())
                    + ") — exactly one is allowed");
        }

        Map.Entry<String, Object> entry = band.entrySet().iterator().next();
        String key = entry.getKey();
        Object value = entry.getValue();
        Predicate.Op op = Predicate.Op.fromKey(key);

        if (op == null) {
            // `outside` was a real operator until the corridor was retired, so a file in the
            // wild can carry one. "not one of gt, gte, lt, lte" is true and useless there —
            // it tells the author their file is wrong without telling them what to write.
            // A corridor is two rules, and saying so is the whole migration.
            if ("outside".equals(key)) {
                return unreadable(label, findings, builder, which, which + " outside is no longer "
                        + "an operator. A corridor is two rules: one with \"lt\" at the low bound "
                        + "and one with \"gt\" at the high bound. Written that way each says which "
                        + "end was missed, and each reports how far past its own limit the run "
                        + "was.");
            }
            return unreadable(label, findings, builder, which, which + " operator '" + key
                    + "' is not one of gt, gte, lt, lte");
        }
        // ServerMetricsMapper's reader, not a copy of it: a quoted number is a common hand-edit and
        // is accepted, and NaN and the infinities are refused on BOTH arms. This mapper's own copy
        // refused them on the text arm only, and a bare 1e400 overflows to Infinity inside the
        // parser, so it arrived as a Number: `gt Infinity` PASSed every run with no finding and
        // exit 0, `lt Infinity` FAILed every run. The refusal names it as a server band's does —
        // "not the number Infinity".
        Double number = ServerMetricsMapper.asNumber(value);
        if (number == null) {
            return unreadable(label, findings, builder, which, which + " " + key
                    + " must be a number, not " + ServerMetricsMapper.describe(value));
        }
        return Predicate.of(op, number);
    }

    /**
     * Report a band that was written and could not be read, record it on the rule, yield no band.
     *
     * <p>Two jobs, and they used to be one. The finding is how the author is told, here and in the
     * file's own words. The recorded problem is what stops the rule being judged anyway — which it
     * was, for as long as an unreadable band and an absent one were both {@code null}.
     */
    private Predicate unreadable(String label, List<AssertionValidator.Finding> findings,
                                 AssertionRule.Builder builder, String which, String problem) {
        findings.add(error(label, problem));
        builder.declarationProblem(Usability.unreadableBand(which, problem));
        return null;
    }

    /**
     * Keys that used to mean something and no longer do.
     *
     * <p>Separate from the unknown-key policy below, and deliberately so. An unknown key is not
     * ours and cannot change a verdict; a RETIRED key was ours, and ignoring it changes what the
     * rule means. {@code observe: true} is the case that matters: it meant *never gates*, and
     * silently dropping it while keeping the bands leaves a rule that gates — so a file written to
     * be safe becomes the one thing turning the badge red. The old validator refused that
     * combination loudly, and this restores it.
     */
    private void retiredKeys(ReportAnnotations.AssertionConfig config, String label,
                             List<AssertionValidator.Finding> findings,
                             AssertionRule.Builder builder) {
        Map<String, Object> unknown = config.getUnknownKeys();
        if (unknown == null || unknown.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> retired : RETIRED_RULE_KEYS.entrySet()) {
            if (unknown.containsKey(retired.getKey())) {
                findings.add(error(label, retired.getValue()));
                builder.declarationProblem(
                        Usability.retiredKey(retired.getKey(), retired.getValue()));
            }
        }
    }

    /**
     * Retired rule-level keys whose removal would INVERT the rule, and what to write instead.
     *
     * <p>The test for membership is narrow and is the whole design: a key belongs here only when
     * ignoring it changes the rule's answer in the unsafe direction. Being retired is not enough.
     *
     * <ul>
     *   <li>{@code observe} meant <em>report this and never gate</em>. Ignore it and the bands
     *       survive, so a rule written to be silent becomes one that can turn the badge red.</li>
     *   <li>{@code onMissing} meant <em>fail if the target matches nothing</em>. Ignore it and an
     *       unmatched target is NO_MATCH, which exits 0 — a build that used to go red now goes
     *       green, which is the worse direction of the two.</li>
     * </ul>
     *
     * <p>{@code tags} is deliberately NOT here, and the test that pins it says why: ignoring it
     * runs every rule instead of a subset, so the failure mode is more checking, never less.
     * Refusing it would break the unknown-key promise for no safety gain. {@code outside} is
     * absent because it is a band OPERATOR rather than a rule key, and is caught where bands are
     * read. {@code exactly()} was never a JSON key at all.
     *
     * <p>A map in one place rather than an if-chain, so retiring a seventh key costs one line —
     * and so the question "does this one invert the rule?" is asked in the same place every time.
     */
    private static final Map<String, String> RETIRED_RULE_KEYS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("observe", "\"observe\" is no longer a rule modifier. It meant \"report this and "
                + "never gate\", and a rule that keeps its bands without it does gate — so leaving "
                + "it in place would silently invert what the rule was written to do. Delete the "
                + "bands to stop the rule judging, or delete \"observe\" to let it judge.");
        m.put("onMissing", "\"onMissing\" is no longer a rule modifier. A target that matches "
                + "nothing is now NOT_JUDGED / NO_MATCH and never fails the run on its own, "
                + "because a plan running a subset is legitimate — so a rule relying on "
                + "\"onMissing\": \"fail\" would pass where it used to fail. Delete the key; if a "
                + "missing sampler must stop the build, assert on it in the pipeline.");
        RETIRED_RULE_KEYS = Collections.unmodifiableMap(m);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static AssertionValidator.Finding error(String ruleId, String message) {
        return AssertionValidator.finding(AssertionValidator.Severity.ERROR, ruleId, message);
    }
}
