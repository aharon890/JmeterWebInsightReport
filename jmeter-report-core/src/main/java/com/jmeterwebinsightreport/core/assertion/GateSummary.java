package com.jmeterwebinsightreport.core.assertion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The gate: the run verdict and how it was made up.
 *
 * <p>The counts exist so a pipeline can gate on {@code jq -e '.gate.failed == 0'} rather than
 * string-matching a status. A single word cannot distinguish "two rules warned" from "two
 * rules could not run at all", and the difference decides whether a build should stop.
 *
 * <h2>Two arithmetics, both closed</h2>
 *
 * <pre>
 *   passed + warned + failed + notJudged                         == declared
 *   disabled + noMatch + noValue + invalidRule
 *     + sourceFailed + lowCoverage + loadNotAchieved             == notJudged
 * </pre>
 *
 * <p>Both are enforced by counting from the enums rather than by hand, so a status or reason
 * added later cannot be silently dropped from the arithmetic. A pipeline can check them itself
 * instead of trusting that every rule landed in some bucket. The last three reasons are
 * {@linkplain NotJudged#serverOnly() server-only}; on a gate of client rules they are always
 * present and always zero, which is what keeps the block one shape and the sum closed.
 *
 * <p>The second one is the point of the split. INVALID used to be a status that ranked <em>above
 * FAIL</em>, so a mistyped metric name failed a build with no measured number having exceeded
 * anything. It is a reason now and it gates nothing — but it is still counted on its own, so a
 * pipeline that wants a broken rule to stop the build asks for exactly that:
 * {@code jq -e '.gate.failed == 0 and .gate.notJudged.invalidRule == 0'}. Strictness became the
 * pipeline's opt-in rather than a verdict imposed on every consumer.
 */
public final class GateSummary {

    private final AssertionStatus status;
    private final List<JudgedResult> results;
    private final int declared;
    private final int evaluated;
    private final int passed;
    private final int warned;
    private final int failed;
    private final int notJudged;
    private final Map<NotJudged, Integer> reasons;

    private GateSummary(AssertionStatus status, List<JudgedResult> results,
                        Map<AssertionStatus, Integer> counts, Map<NotJudged, Integer> reasons) {
        this.status = status;
        this.results = results;
        this.declared = results.size();
        this.passed = counts.get(AssertionStatus.PASS);
        this.warned = counts.get(AssertionStatus.WARN);
        this.failed = counts.get(AssertionStatus.FAIL);
        this.notJudged = counts.get(AssertionStatus.NOT_JUDGED);
        this.reasons = reasons;
        // Only rules excluded BEFORE evaluation are subtracted: a rule that ran and found
        // nothing to judge was still asked the question.
        this.evaluated = this.declared - reasons.get(NotJudged.DISABLED);
    }

    /**
     * Fold a list of results into a summary. The one place a gate is counted.
     *
     * <p>Counting by enum rather than by hand means a value added later cannot be silently
     * dropped from the arithmetic the class comment promises. And a summary is derivable from
     * results alone, so results computed somewhere other than the evaluator — a server-metric
     * KPI's {@code KpiResult} — makes a gate of exactly the same shape instead of needing a
     * second kind beside it. The fold reads only the {@link JudgedResult} seam; what was
     * judged stays the concrete type's business.
     *
     * <p>One gate holds one concrete kind. The client gate is all {@code RuleResult}s and each
     * server gate is all {@code KpiResult}s; the signature accepts a mixed list because it
     * counts through the seam, but a consumer's typed filter ({@link #ruleResults()}) would
     * then render fewer rows than {@link #declared()} counts. Nothing builds a mixed gate, and
     * nothing should.
     */
    public static GateSummary of(List<? extends JudgedResult> results) {
        Map<AssertionStatus, Integer> counts = new EnumMap<>(AssertionStatus.class);
        for (AssertionStatus s : AssertionStatus.values()) {
            counts.put(s, 0);
        }
        Map<NotJudged, Integer> reasons = new EnumMap<>(NotJudged.class);
        for (NotJudged r : NotJudged.values()) {
            reasons.put(r, 0);
        }

        AssertionStatus verdict = null;
        for (JudgedResult r : results) {
            counts.put(r.status(), counts.get(r.status()) + 1);
            if (r.notJudged() != null) {
                reasons.put(r.notJudged(), reasons.get(r.notJudged()) + 1);
            }
            // The same fold the evaluator applies to matches. One function, used at both levels,
            // so the run verdict and a rule verdict cannot be computed by two different rules.
            if (r.status().gates()) {
                verdict = AssertionStatus.worst(verdict, r.status());
            }
        }
        // `verdict` stays null exactly when nothing gated — a gate of nothing but NOT_JUDGED
        // rules has decided nothing, and reporting PASS for it would be the kind of lie this
        // engine exists to stop.
        //
        // Copied, not stored. The counts above are a snapshot; the list was not, so a caller
        // that kept its own reference and added a result afterwards broke the invariant this
        // factory exists to guarantee — and the javadoc explicitly invites outside callers, so
        // that was reachable rather than theoretical.
        return new GateSummary(verdict, new ArrayList<JudgedResult>(results), counts, reasons);
    }

    /**
     * The run verdict: the worst status across rules that decided something.
     *
     * <p>{@code null} when nothing gated — which is not the same as PASS, and the existing
     * {@code assertsEvaluated} flag says so. A run with no gate has not passed a gate; it was
     * reported, not judged.
     */
    public AssertionStatus status() { return status; }

    /** Every result, in declaration order, through the seam. */
    public List<JudgedResult> results() { return Collections.unmodifiableList(results); }

    /**
     * The results of one concrete kind — {@code results(RuleResult.class)} for the client
     * renderers, {@code results(KpiResult.class)} for the server ones.
     *
     * <p>A typed filter rather than a cast at each call site, so a renderer built for client
     * rules handed a server gate renders nothing rather than throwing — and so the five client
     * consumers did not each need to learn that the list can now hold something else.
     */
    public <T extends JudgedResult> List<T> results(Class<T> type) {
        List<T> out = new ArrayList<>(results.size());
        for (JudgedResult r : results) {
            if (type.isInstance(r)) {
                out.add(type.cast(r));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The client rules' results — what the sampler verdicts, the JSON and the JUnit suite render. */
    public List<RuleResult> ruleResults() { return results(RuleResult.class); }

    /** Rules in the file, before {@code enabled: false} parks any of them. */
    public int declared() { return declared; }

    /**
     * Rules the gate attempted — declared minus the ones parked by {@code enabled: false}.
     *
     * <p>A rule that ran and then found nothing to judge still counts as evaluated: it was
     * asked, and it answered "nothing to judge". Only rules excluded before evaluation are
     * subtracted.
     */
    public int evaluated() { return evaluated; }

    public int passed() { return passed; }
    public int warned() { return warned; }
    public int failed() { return failed; }

    /** Rules that decided nothing, for any reason. Never gates. */
    public int notJudged() { return notJudged; }

    /** How many rules were not judged for this reason. */
    public int notJudged(NotJudged reason) { return reasons.get(reason); }

    /**
     * Rules not judged for a reason that means the file asked for a check that never ran —
     * {@link NotJudged#isConfigError()} decides which, once, for every consumer.
     *
     * <p>This is the number {@code failonerror} fails the process over and the number the JUnit
     * writer adds to {@code failures}. It used to be {@link #invalidRule()} at each of those
     * sites, which was correct only while INVALID_RULE was the sole reason of its kind.
     */
    public int configErrors() {
        int n = 0;
        for (NotJudged r : NotJudged.values()) {
            if (r.isConfigError()) {
                n += reasons.get(r);
            }
        }
        return n;
    }

    /** Rules parked with {@code enabled: false}. */
    public int disabled() { return reasons.get(NotJudged.DISABLED); }

    /**
     * Rules whose target selected nothing to judge.
     *
     * <p>Reported separately because it is a different problem with a different fix: a breach
     * means the system is too slow, an unmatched target usually means the rule has a typo in it
     * and has never run at all.
     */
    public int noMatch() { return reasons.get(NotJudged.NO_MATCH); }

    /** Rules whose metric this run could not produce a value for. */
    public int noValue() { return reasons.get(NotJudged.NO_VALUE); }

    /**
     * Rules the file got wrong — unknown metric, unreadable target, no bands.
     *
     * <p>The count a strict pipeline gates on. It does not move the verdict, deliberately; see
     * the class comment.
     */
    public int invalidRule() { return reasons.get(NotJudged.INVALID_RULE); }

    /** True when any rule was declared at all. Drives {@code assertsEvaluated} in the CI/CD JSON. */
    public boolean hasGate() {
        return declared > 0;
    }
}
