package com.jmeterwebinsightreport.core.assertion;

/**
 * How much of a rule's budget the run used — the answer to "how close was it?".
 *
 * <p><b>Why this exists.</b> A rule's threshold reached no artifact unless it was breached:
 * {@code CiCdJsonSerializer} emitted {@code threshold} only inside a match that had a
 * {@code breachedBand}, so a PASS carried the measured value and nothing to measure it against.
 * A consumer could tell that p95 was 712 ms and could not tell whether the budget was 800 or
 * 8000 — which is the difference between "ship it" and "this is about to break", and it is the
 * first question anyone asks of a green gate. Two runs' JSON could be diffed for a status change
 * and not for a trend towards one.
 *
 * <p><b>No new declaration syntax.</b> This is derived entirely from the rule and its results, so
 * it applies retroactively to every rule ever written — nothing in any annotations file changes,
 * and nothing has to be authored to get it.
 *
 * <h2>Direction comes from the operator</h2>
 *
 * <p>The tester writes the direction ({@link Predicate}), so the operator already says which way
 * is worse and no per-metric table is needed. {@code gt}/{@code gte} means bigger is worse, so the
 * budget is consumed from below and the worst match is the largest value; {@code lt}/{@code lte}
 * means smaller is worse — throughput, apdex — so it is the other way round in both respects.
 *
 * <p>Every band has exactly one threshold, so the fraction is always well defined. It was not:
 * {@code outside: [lo, hi]} had two, "fraction of the budget used" had no single meaning between
 * them, and a corridor rule therefore carried no attainment at all. That operator was deleted
 * rather than special-cased — being unable to answer this question for a rule shape is a reason
 * to drop the shape, not to leave a hole in the contract.
 */
public final class Attainment {

    private final double actual;
    private final double failThreshold;
    private final double consumed;
    private final boolean breached;

    private Attainment(double actual, double failThreshold, double consumed, boolean breached) {
        this.actual = actual;
        this.failThreshold = failThreshold;
        this.consumed = consumed;
        this.breached = breached;
    }

    /**
     * The value of the rule's worst judged match ({@link RuleResult#worstJudged()}), in the metric's
     * own unit: the worst value measured wherever the rule's bands point one way.
     */
    public double actual() {
        return actual;
    }

    /** The value the fail band draws the line at. */
    public double failThreshold() {
        return failThreshold;
    }

    /**
     * The fraction of the budget used: 0.89 means the run was at 89% of its limit.
     *
     * <p>Can exceed 1, and must be allowed to — "how far past" is exactly what a reader wants
     * from a breach, and clamping would make a rule that failed by 3x indistinguishable from one
     * that failed by a hair.
     */
    public double consumed() {
        return consumed;
    }

    /** Whether the fail band actually fired. Mirrors the rule's status; carried so a consumer
        reading only this block does not have to re-derive it from {@link #consumed}. */
    public boolean breached() {
        return breached;
    }

    /**
     * The attainment of one evaluated rule, or {@code null} when the question does not apply.
     *
     * <p>Null rather than a zeroed instance, for the reason the whole engine is built on: a rule
     * with no fail band, a corridor, a rule that matched nothing and a rule whose
     * metric this run could not produce have all measured no budget at all, and a block reading
     * {@code consumed: 0} would say the opposite of that — "comfortably inside", for a rule that
     * checked nothing.
     */
    public static Attainment of(RuleResult result) {
        if (result == null || result.rule() == null) {
            return null;
        }
        AssertionRule rule = result.rule();
        Predicate fail = rule.fail();
        if (fail == null) {
            return null;
        }
        if (!result.status().gates() || result.matches().isEmpty()) {
            return null;
        }

        boolean biggerIsWorse = fail.op() == Predicate.Op.GT || fail.op() == Predicate.Op.GTE;
        // The worst match is RuleResult.worstJudged(), the one the JUnit message names (L13): the
        // highest severity, then the furthest past the band that status was decided against - the
        // fail band for a FAIL, the warn band for a WARN. This took the worst by value on its own,
        // so a rule failing on two samplers had its JSON attainment describe one and its JUnit
        // message the other. The two agree on every rule whose bands point one way; on a warn band
        // pointing away from the fail band, a WARN outranks a PASS nearer the fail band, and the
        // WARN furthest past the warn band outranks one nearer the fail band (NIT-2), so
        // attainment describes the match the rule's status came from, measured against fail's line.
        RuleResult.Match judged = result.worstJudged();
        double worst = judged == null ? Double.NaN : judged.actual();
        if (Double.isNaN(worst)) {
            return null;   // a match this run could not measure contributes no budget
        }

        double threshold = fail.value();
        if (Double.isNaN(threshold)) {
            return null;
        }
        // The divisor is whichever side represents "all of the budget". Guarded because a
        // threshold of 0 is legitimate ("no errors at all") and a zero throughput is a real
        // measurement; either would otherwise produce Infinity or NaN in a JSON contract.
        double numerator = biggerIsWorse ? worst : threshold;
        double divisor = biggerIsWorse ? threshold : worst;
        if (divisor == 0) {
            return null;
        }
        return new Attainment(worst, threshold, numerator / divisor, fail.breaches(worst));
    }
}
