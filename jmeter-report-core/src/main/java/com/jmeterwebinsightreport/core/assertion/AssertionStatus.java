package com.jmeterwebinsightreport.core.assertion;

/**
 * What a rule decided.
 *
 * <p>Four values, and only three of them are decisions. A status is a judgement, and only a
 * declared assert compared against a measured number earns one — so everything else is one
 * value, {@link #NOT_JUDGED}, carrying a {@link NotJudged} reason that says which kind of
 * nothing happened.
 *
 * <h2>Why this used to be seven</h2>
 *
 * <p>DISABLED, SKIPPED, OBSERVED and INVALID were four separate statuses for four ways of not
 * deciding, and INVALID additionally <em>gated</em> — it ranked above FAIL, so a typo'd metric
 * name failed a build with no number anywhere that had exceeded anything. Every surface then
 * needed a translation from that seven-value vocabulary into the three-value one the badge
 * speaks, and {@code GateEvaluation.overallStatus()} took the worse of two different views of
 * the same gate because the two dialects disagreed.
 *
 * <p>Collapsing them removes the translation rather than hiding it. The information is not
 * lost: it moves to {@link NotJudged}, where it is a reason rather than a verdict, and it is
 * counted separately in the gate block so a pipeline can still be strict by choice —
 * {@code jq -e '.gate.failed == 0 and .gate.notJudged.invalidRule == 0'}.
 *
 * <p>Ordered by how much attention it demands, which is also how the run verdict is taken: the
 * worst status across every rule that decided anything. NOT_JUDGED never lifts a run above PASS
 * and never drags it down.
 */
public enum AssertionStatus {

    /**
     * Nothing was compared to anything. See {@link NotJudged} for which kind.
     *
     * <p>Never gates, in either direction — the rule cannot fail the run and cannot pass it.
     * The thing that went wrong is reported as a finding, a banner and a count, all of which
     * name the rule; what it is not is a performance verdict, because no performance was
     * measured.
     */
    NOT_JUDGED(0, false),

    /** Measured. No band breached. */
    PASS(1, true),

    /** Measured. The warn band breached; the fail band did not. */
    WARN(2, true),

    /** Measured. The fail band breached. The only way a rule reaches FAIL. */
    FAIL(3, true);

    private final int severity;
    private final boolean gating;

    AssertionStatus(int severity, boolean gating) {
        this.severity = severity;
        this.gating = gating;
    }

    /** Higher is worse. Only meaningful between two gating statuses. */
    public int severity() {
        return severity;
    }

    /** False only for {@link #NOT_JUDGED}, which never moves the run verdict in either direction. */
    public boolean gates() {
        return gating;
    }

    /**
     * The worse of two statuses; NOT_JUDGED loses to anything that gates.
     *
     * <p>The single fold, used twice: match → rule, and rule → run. One function means the two
     * cannot disagree, which they did when the run verdict was computed by a different rule
     * than the rule verdict.
     */
    public static AssertionStatus worst(AssertionStatus a, AssertionStatus b) {
        if (a == null) return b;
        if (b == null) return a;
        if (!a.gates()) return b.gates() ? b : a;
        if (!b.gates()) return a;
        return a.severity >= b.severity ? a : b;
    }

    /** How the status is named in the CI/CD JSON, JUnit XML and report. */
    public String wireName() {
        return name();
    }
}
