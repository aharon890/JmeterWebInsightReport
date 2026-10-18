package com.jmeterwebinsightreport.core.assertion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * The single answer to <em>can this rule decide anything?</em>
 *
 * <p>That question used to be answered twice. {@link AssertionValidator} answered it and produced
 * a {@link AssertionValidator.Finding}; {@code AssertionEvaluator.whyUnusable} answered it again
 * and decided whether the rule was judged at all. <strong>Findings do not gate.</strong> So the
 * validator could be completely right and completely inert, and the two answers could — and did —
 * drift: four conditions were implemented in both classes, two of them worded differently for the
 * same fact.
 *
 * <p>The proof that this was not theoretical is three lines up in the validator's own history. A
 * {@code loadValidity} tolerance of 20, written by someone who meant 20%, shipped as
 * {@code lt -19} and reported PASS on every run. A check was written for it. It went into the
 * validator, {@code whyUnusable} never asked the validator anything, and the rule went on being
 * judged, went on reporting PASS, and went on exiting 0 — the bug surviving the fix written for
 * it, because the fix was put where the answer did not count.
 *
 * <p>So there is one answer now, and both callers read it:
 *
 * <ul>
 *   <li>{@link #refusal(AssertionRule)} is what the evaluator asks. Non-null means the rule is
 *       {@code NOT_JUDGED / INVALID_RULE} — it decides nothing, in either direction.</li>
 *   <li>{@link #check(AssertionRule)} is what the validator asks. Every problem, in report order,
 *       rendered as findings.</li>
 * </ul>
 *
 * <p><strong>The invariant:</strong> {@code refusal(r) != null} exactly when {@code check(r)}
 * contains an {@link AssertionValidator.Severity#ERROR}, and the {@code INVALID_RULE} detail is
 * that entry's own sentence. Pinned by {@code UsabilityTest}, because without it the two answers
 * can silently separate again — which is the whole of what this class exists to prevent.
 *
 * <p><strong>Not everything wrong with a rule refuses it.</strong> A warn band the fail band
 * already covers is a WARNING: the rule's verdicts are correct, it is just not what anyone meant.
 * Refusing it would turn a swapped pair of numbers into a non-zero exit. Severity is the whole
 * distinction between "this is wrong" and "this cannot run".
 *
 * <p>Every check here is a pure function of ONE rule. Two of the validator's checks are not, and
 * deliberately stay there: duplicate ids need the whole rule set, and an unmatched target needs
 * the run. The second is not a usability question at all — a rule that matched nothing is
 * well-formed, and answers {@code NO_MATCH}.
 */
public final class Usability {

    /**
     * Why a rule cannot decide, or why it is suspect.
     *
     * <p>Deliberately finer-grained than {@link NotJudged}: every ERROR here maps onto the single
     * {@code INVALID_RULE} reason. A {@code NotJudged} constant is a count a pipeline gates on,
     * and "which of nine ways the file is wrong" is not a thing to gate on separately — the fix
     * is the same file either way. The distinction lives in the sentence, not in the count.
     */
    public enum Reason {
        /** The loader could not read the target at all. */
        UNREADABLE_TARGET,
        /** A glob or regex that will not compile. */
        BAD_PATTERN,
        /** A metric name that is not one, or a selector that will not parse. */
        UNKNOWN_METRIC,
        /** A real metric, asked for where it has no value — whole-run vs per-sampler. */
        METRIC_WRONG_SCOPE,
        /** No warn and no fail. Nothing to compare against. */
        NO_BANDS,
        /** A band was declared and could not be read. Known only to the loader. */
        BAND_UNREADABLE,
        /** A band no measurement can ever breach. */
        BAND_UNREACHABLE,
        /** A key that used to mean something and no longer does. Known only to the loader. */
        RETIRED_KEY,
        /** Warn sits behind fail, so it can never fire. The rule still judges correctly. */
        WARN_COVERED_BY_FAIL
    }

    private final Reason reason;
    private final AssertionValidator.Severity severity;
    private final String sentence;
    private final String extra;

    private Usability(Reason reason, AssertionValidator.Severity severity, String sentence,
                      String extra) {
        this.reason = reason;
        this.severity = severity;
        this.sentence = sentence;
        this.extra = extra;
    }

    public Reason reason() { return reason; }

    public AssertionValidator.Severity severity() { return severity; }

    /** True when this problem stops the rule deciding anything. */
    public boolean refuses() { return severity == AssertionValidator.Severity.ERROR; }

    /**
     * True when the loader has already reported this, at the point it happened.
     *
     * <p>Three faults are found while reading the file rather than by looking at a finished rule:
     * a target that would not parse, a band that would not parse, and a retired key. The loader
     * says so as it finds them, in the file's own words and against the file's own label — which
     * for an id-less rule is {@code assertions[2]} rather than a derived id, and is the only
     * thing that would let the author find the line.
     *
     * <p>So the validator must not say it a second time. "Two findings for one mistake" is a
     * regression this project has already had and pinned against: the second is a consequence of
     * the first rather than a separate thing to fix, and it doubles the noise on exactly the runs
     * that are already going badly. The problem still travels here because it still has to
     * REFUSE the rule — reporting and refusing are different jobs, and conflating them is what
     * this class exists to undo.
     */
    public boolean loaderReported() {
        return reason == Reason.UNREADABLE_TARGET
                || reason == Reason.BAND_UNREADABLE
                || reason == Reason.RETIRED_KEY;
    }

    /**
     * The short sentence, as the gate reports it.
     *
     * <p>This is what reaches {@code NOT_JUDGED / INVALID_RULE}, the JUnit XML and the report's
     * broken-rule banner, so it says what is wrong and what to write instead, and stops there.
     */
    public String reasonText() { return sentence; }

    /**
     * The long form, as a finding renders it.
     *
     * <p>Identical to {@link #reasonText()} except where a finding can afford context a banner
     * cannot — today only the metric vocabulary, which is long and belongs in the log rather than
     * over the tab. Two renderings of one answer, never two answers.
     */
    public String explanation() { return extra == null ? sentence : sentence + extra; }

    /** Render as a finding against the given rule id. */
    public AssertionValidator.Finding asFinding(String ruleId) {
        return AssertionValidator.finding(severity, ruleId, explanation());
    }

    // ---- loader-only facts ---------------------------------------------------
    //
    // Neither of these is derivable from the model: by the time a rule object exists, an
    // unreadable band is indistinguishable from an absent one, and a retired key has been
    // swallowed by the unknown-key policy. Both are recorded at mapping time and travel on the
    // rule itself — the same route AssertionTarget.unusable already takes for a target the
    // loader could not read.

    /** A band that was written and could not be read. {@code which} is "warn" or "fail". */
    public static Usability unreadableBand(String which, String problem) {
        return new Usability(Reason.BAND_UNREADABLE, AssertionValidator.Severity.ERROR,
                problem + " — so this rule declares a limit it cannot apply, and would report "
                        + "PASS without ever testing " + which, null);
    }

    /** A key that used to mean something. The sentence must name the key and the replacement. */
    public static Usability retiredKey(String key, String problem) {
        return new Usability(Reason.RETIRED_KEY, AssertionValidator.Severity.ERROR, problem, null);
    }

    // ---- the checks ----------------------------------------------------------

    /**
     * Every problem with this rule, in report order, worst first.
     *
     * <p>The order is load-bearing rather than cosmetic. A rule whose target could not be read
     * has nothing trustworthy below it, so complaining about its metric would bury the real fault
     * under a consequence of it.
     */
    public static List<Usability> check(AssertionRule rule) {
        List<Usability> found = new ArrayList<>(2);
        if (rule == null) {
            return found;
        }

        // Facts the loader recorded. First, because they are about what was WRITTEN, and
        // everything below reasons about what could be parsed out of it.
        found.addAll(rule.declarationProblems());

        if (rule.target().kind() == AssertionTarget.Kind.INVALID) {
            found.add(new Usability(Reason.UNREADABLE_TARGET, AssertionValidator.Severity.ERROR,
                    rule.target().describe(), null));
            // Nothing below this can be trusted to be the real complaint.
            return found;
        }

        Usability pattern = badPattern(rule);
        if (pattern != null) {
            found.add(pattern);
            return found;
        }

        Usability metric = metricProblem(rule);
        if (metric != null) {
            found.add(metric);
            return found;
        }

        if (!rule.hasBands()) {
            // Only when no band survived AND none was rejected — a rule whose single band was
            // unreadable already said so above, and "no warn or fail band" would be a second
            // finding for one mistake, and the less useful of the two.
            if (!hasUnreadableBand(rule)) {
                found.add(new Usability(Reason.NO_BANDS, AssertionValidator.Severity.ERROR,
                        "no warn or fail band — the rule can never decide anything", null));
            }
            return found;
        }

        addIfUnreachable(found, rule, rule.warn(), "warn");
        addIfUnreachable(found, rule, rule.fail(), "fail");

        Usability deadWarn = warnCoveredByFail(rule);
        if (deadWarn != null) {
            found.add(deadWarn);
        }
        return found;
    }

    /**
     * The first thing that stops this rule deciding anything, or {@code null} when it can.
     *
     * <p>This method IS the gate's definition of an unusable rule. Nothing else may manufacture
     * one.
     */
    public static Usability refusal(AssertionRule rule) {
        for (Usability u : check(rule)) {
            if (u.refuses()) {
                return u;
            }
        }
        return null;
    }

    private static boolean hasUnreadableBand(AssertionRule rule) {
        for (Usability u : rule.declarationProblems()) {
            if (u.reason() == Reason.BAND_UNREADABLE) {
                return true;
            }
        }
        return false;
    }

    private static Usability badPattern(AssertionRule rule) {
        if (rule.target().kind() != AssertionTarget.Kind.MATCH) {
            return null;
        }
        try {
            AssertionTarget.compile(rule.target().pattern());
            for (String ex : rule.target().excludes()) {
                AssertionTarget.compile(ex);
            }
        } catch (PatternSyntaxException e) {
            return new Usability(Reason.BAD_PATTERN, AssertionValidator.Severity.ERROR,
                    "pattern is not a valid expression at index " + e.getIndex() + ": "
                            + e.getDescription(), null);
        }
        return null;
    }

    /**
     * An unknown metric is the important one.
     *
     * <p>Left alone it resolves to no value, no band breaches, and the rule reports PASS — a
     * typo'd metric name silently switching the gate off is the precise failure this feature
     * exists to prevent.
     */
    private static Usability metricProblem(AssertionRule rule) {
        boolean overallScope = rule.target().kind() == AssertionTarget.Kind.OVERALL;
        MetricRef metric = rule.metric();

        if (metric == null) {
            // A selector that could not be read gets a sentence about the selector. Falling
            // through to "unknown metric" would bury the one thing that is wrong under a list of
            // every valid name.
            String selectorProblem = MetricRef.problemWith(rule.metricName());
            if (selectorProblem != null) {
                return new Usability(Reason.UNKNOWN_METRIC, AssertionValidator.Severity.ERROR,
                        selectorProblem, null);
            }
            String suggestion = MetricRef.nearestName(rule.metricName(), overallScope);
            return new Usability(Reason.UNKNOWN_METRIC, AssertionValidator.Severity.ERROR,
                    "unknown metric '" + rule.metricName() + "'"
                            + (suggestion != null ? " — did you mean '" + suggestion + "'?" : ""),
                    ". Valid here: " + String.join(", ", MetricRef.vocabulary(overallScope)));
        }
        if (overallScope && !metric.validForOverall()) {
            return new Usability(Reason.METRIC_WRONG_SCOPE, AssertionValidator.Severity.ERROR,
                    "metric '" + metric.canonicalName() + "' is not available for the whole run",
                    null);
        }
        if (!overallScope && !metric.validForSampler()) {
            return new Usability(Reason.METRIC_WRONG_SCOPE, AssertionValidator.Severity.ERROR,
                    "metric '" + metric.canonicalName() + "' is not available per sampler", null);
        }
        return null;
    }

    /**
     * A band no measurement can breach, judged on its own rather than against its sibling.
     *
     * <p>{@link #warnCoveredByFail} only compares warn against fail and returns early unless both
     * exist, so a rule carrying one dead band raised nothing at all. That is how a
     * {@code loadValidity} tolerance of 20 — written by someone who meant 20% — shipped as
     * {@code lt -19 × target} and reported PASS on every run, in the feature whose whole job is
     * refusing to pass a run that never applied its load.
     *
     * <p>The reasoning is narrow on purpose: every metric this report computes is a duration, a
     * count, a rate or a score, and none of them can be negative. So a downward band at or below
     * zero is unreachable no matter what the run did. Nothing is claimed about upward bands —
     * response time has no ceiling, and "1000000 ms is surely impossible" is a guess about the
     * system under test, which is not this class's to make.
     */
    private static void addIfUnreachable(List<Usability> found, AssertionRule rule, Predicate band,
                                         String which) {
        if (band == null) {
            return;
        }
        boolean downward = band.op() == Predicate.Op.LT || band.op() == Predicate.Op.LTE;
        if (!downward) {
            return;
        }
        boolean unreachable = band.op() == Predicate.Op.LT ? band.value() <= 0 : band.value() < 0;
        if (!unreachable) {
            return;
        }
        String unit = rule.metric() != null ? rule.metric().unit().suffix() : "";
        found.add(new Usability(Reason.BAND_UNREACHABLE, AssertionValidator.Severity.ERROR,
                which + " can never fire: " + band.describe(unit) + " is unreachable because this "
                        + "metric is never negative. The rule reports PASS on every run, "
                        + "including one that measured nothing at all. A tolerance meant as a "
                        + "percentage is written as a fraction — 0.2, not 20.", null));
    }

    /**
     * A warn band the fail band already covers.
     *
     * <p>{@code fail} is tested first, so {@code warn > 500} beside {@code fail > 400} can never
     * fire: everything above 500 is above 400 and has already failed. Almost always the two
     * values were typed the wrong way round, and the message says so rather than making the
     * reader work it out.
     *
     * <p>WARNING, not ERROR, and that is the point of having a severity: the rule's verdicts are
     * correct. Refusing it would turn a swapped pair of numbers into a non-zero exit.
     */
    private static Usability warnCoveredByFail(AssertionRule rule) {
        Predicate warn = rule.warn();
        Predicate fail = rule.fail();
        if (warn == null || fail == null) {
            return null;
        }
        boolean upward = warn.op() == Predicate.Op.GT || warn.op() == Predicate.Op.GTE;
        boolean failUpward = fail.op() == Predicate.Op.GT || fail.op() == Predicate.Op.GTE;
        if (upward != failUpward) {
            return null; // opposite directions is a corridor written the long way; leave it
        }
        boolean dead = upward ? warn.value() >= fail.value() : warn.value() <= fail.value();
        if (!dead) {
            return null;
        }
        String unit = rule.metric() != null ? rule.metric().unit().suffix() : "";
        return new Usability(Reason.WARN_COVERED_BY_FAIL, AssertionValidator.Severity.WARNING,
                "warn can never fire: fail " + fail.describe(unit) + " is tested first and already "
                        + "covers warn " + warn.describe(unit) + ". Swapped values?", null);
    }

    /** An empty, immutable list — the common case, allocated once. */
    static final List<Usability> NONE = Collections.emptyList();

    @Override
    public String toString() {
        return severity + " " + reason + ": " + sentence;
    }
}
