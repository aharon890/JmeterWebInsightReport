package com.jmeterwebinsightreport.core.assertion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one rule decided, and against which measurements.
 *
 * <p>Every match is kept, passing ones included. A verdict alone is lossy: "checkout-p95
 * FAILED" does not say which of the four samplers the pattern selected actually breached, nor
 * how close the others were, and that is precisely what a tester needs in order to move the
 * threshold. It is also what the report's expandable match rows render.
 *
 * <p>A {@link JudgedResult}: the status and reason are what {@link GateSummary} counts, and
 * the same fold serves a server KPI's result. The matches below are the client-only half.
 */
public final class RuleResult implements JudgedResult {

    /** One sampler (or the run) measured against the rule. */
    public static final class Match {
        private final String samplerName;
        private final double actual;
        private final AssertionStatus status;
        private final Predicate breached;
        private final NotJudged notJudged;

        public Match(String samplerName, double actual, AssertionStatus status, Predicate breached) {
            this(samplerName, actual, status, breached, null);
        }

        /**
         * A match the run could not measure.
         *
         * <p>Per match rather than per rule, and that is the whole point of the pair: a
         * {@code match: "*"} rule over twelve samplers where ONE cannot produce the metric used
         * to poison the entire rule to INVALID, erasing a genuine FAIL from another sampler the
         * same rule judged. The rule now reports the worst of its MEASURED matches, and the
         * unmeasurable one carries its own reason.
         */
        public static Match notJudged(String samplerName, NotJudged reason) {
            return new Match(samplerName, Double.NaN, AssertionStatus.NOT_JUDGED, null, reason);
        }

        private Match(String samplerName, double actual, AssertionStatus status,
                      Predicate breached, NotJudged notJudged) {
            this.samplerName = samplerName;
            this.actual = actual;
            this.status = status;
            this.breached = breached;
            this.notJudged = notJudged;
        }

        /** Why this match was not judged, or {@code null} when it was. */
        public NotJudged notJudged() { return notJudged; }

        /**
         * What the match is keyed by: the sampler's name, or {@link AssertionTarget#OVERALL_KEY}
         * for the whole run. Never shown — {@link #label()} is.
         *
         * <p>It used to be the display text "Whole run" for the run, so anything keyed on it —
         * {@link SamplerVerdicts} — merged a sampler literally named "Whole run" with the
         * whole-run verdict (CORE-1).
         */
        public String samplerName() { return samplerName; }

        /** How the match reads wherever it is shown: the sampler's name, or "Whole run". */
        public String label() { return AssertionTarget.labelOf(samplerName); }

        /** The measured value, or {@link Double#NaN} when the run could not produce one. */
        public double actual() { return actual; }

        public AssertionStatus status() { return status; }

        /** The band this match breached, or {@code null} when it passed. */
        public Predicate breachedBand() { return breached; }
    }

    private final AssertionRule rule;
    private final AssertionStatus status;
    private final List<Match> matches;
    private final int matchedTotal;
    private final NotJudged notJudged;
    private final String notJudgedDetail;

    private RuleResult(AssertionRule rule, AssertionStatus status, List<Match> matches,
                       int matchedTotal, NotJudged notJudged, String notJudgedDetail) {
        this.rule = rule;
        this.status = status;
        this.matches = matches;
        this.matchedTotal = matchedTotal;
        this.notJudged = notJudged;
        this.notJudgedDetail = notJudgedDetail;
    }

    /** A rule that decided something: PASS, WARN or FAIL, folded from its matches. */
    static RuleResult of(AssertionRule rule, AssertionStatus status, List<Match> matches,
                         int matchedTotal) {
        return new RuleResult(rule, status, matches, matchedTotal, null, null);
    }

    /**
     * A rule that decided nothing, and why.
     *
     * <p>One factory for every reason, where there used to be three (unmatched, skipped,
     * invalid) each choosing a different status. There is one status now, so the only thing
     * left to say is the reason — and making it a required argument means a new way of not
     * deciding cannot be added without naming itself.
     *
     * <p>{@code detail} is the human sentence, or {@code null} where {@link NotJudged#phrase()}
     * already says everything there is to say.
     */
    static RuleResult notJudged(AssertionRule rule, NotJudged reason, String detail) {
        return new RuleResult(rule, AssertionStatus.NOT_JUDGED, Collections.<Match>emptyList(),
                0, reason, detail);
    }

    static RuleResult notJudged(AssertionRule rule, NotJudged reason) {
        return notJudged(rule, reason, null);
    }

    /**
     * A rule that selected samplers, measured none of them, and keeps what it selected.
     *
     * <p>The three rule-level reasons happen before any match exists, so they carry none. This
     * one does not: NO_VALUE at rule level means every match came back unmeasurable, and the
     * matches are the evidence. Dropping them would make the row indistinguishable from a rule
     * whose target matched NOTHING — same status, same empty list, same zero — when the fixes
     * are opposite: one is a renamed sampler, the other is a run that did not record the data.
     * The report shows the sampler names with a dash against each.
     */
    static RuleResult unmeasurable(AssertionRule rule, List<Match> matches, int matchedTotal) {
        return new RuleResult(rule, AssertionStatus.NOT_JUDGED, matches, matchedTotal,
                NotJudged.NO_VALUE, null);
    }

    public AssertionRule rule() { return rule; }

    /** The rule, through the seam a gate counts by. {@link #rule()} is the typed view. */
    @Override public Judged judged() { return rule; }

    @Override public AssertionStatus status() { return status; }

    public List<Match> matches() { return Collections.unmodifiableList(matches); }

    /**
     * How many samplers the target selected, which can exceed {@link #matches()}.
     *
     * <p>The report payload caps the matches it carries, so a {@code match: "*"} rule across a
     * 500-sampler run does not put 500 result objects per rule into the HTML. The total is kept
     * so the UI can say "and 300 more" rather than quietly implying the rule judged four things.
     */
    public int matchedTotal() { return matchedTotal; }

    /**
     * True when the target selected nothing — a different problem from a breach, shown
     * differently.
     *
     * <p>Derived from the reason rather than stored beside it: they were independent fields and
     * nothing stopped them disagreeing.
     */
    public boolean unmatched() { return notJudged == NotJudged.NO_MATCH; }

    /** Why the rule decided nothing, or {@code null} when it decided something. */
    @Override public NotJudged notJudged() { return notJudged; }

    /**
     * The human sentence behind {@link #notJudged()}, or {@code null}.
     *
     * <p>Carries the detail the reason cannot: which metric was mistyped, what the loader could
     * not read, and the "did you mean" suggestion.
     */
    @Override public String notJudgedDetail() { return notJudgedDetail; }

    /**
     * The worst match, which is the one the summary row shows. Null when there were none.
     *
     * <p>Ties keep the earliest match, so the row is stable across runs of the same data
     * rather than depending on iteration order.
     */
    public Match worst() {
        Match worst = null;
        for (Match m : matches) {
            if (worst == null || m.status().severity() > worst.status().severity()) {
                worst = m;
            }
        }
        return worst;
    }

    /**
     * The worst match that was actually judged — the one the rule status came from — and the one
     * definition of "worst" every artifact reads (L13): the JUnit message, the CI/CD JSON's
     * {@code attainment} ({@link Attainment#of}), the LOAD_NOT_ACHIEVED sentence, and the page's
     * {@code worstMatch} in report.js, which the Asserts tab's Actual and Budget columns and "Worst
     * breaches" show (the server mirror's {@code worstJudged} is the same function).
     *
     * <ol>
     *   <li><b>The highest severity.</b> A WARN or FAIL message must name the match its status came
     *       from; naming a PASS beside it would contradict itself.</li>
     *   <li><b>Then the furthest past the band that status was decided against</b> — a FAIL by the
     *       fail band, a WARN by the warn band, and a PASS by the fail band, else the warn band;
     *       upward when there is none. Among matches of one status that is the one to fix. It used to
     *       be the FIRST of them, so a rule failing on two samplers named the faster one in the XML
     *       while {@code attainment.actual} in the JSON, which took the worst by value, named the
     *       slower. And it took the fail band's direction for every status (NIT-2), which on a
     *       corridor — {@code warn lt 10} beside {@code fail gt 100} — named the WARN at 8, the one
     *       nearest to passing, over the WARN at 2.</li>
     *   <li><b>Then the earliest</b>, so an exact tie is stable across runs of the same data.</li>
     * </ol>
     *
     * <p>Differs from {@link #worst()} in skipping matches that could not be measured: NOT_JUDGED
     * sits at severity 0, so {@code worst()} can return an unmeasurable match for a rule where every
     * other match passed, and the summary row would then show a dash where the reader expects the
     * number the verdict was taken from.
     */
    public Match worstJudged() {
        Match worst = null;
        for (Match m : matches) {
            if (!m.status().gates()) continue;
            if (worst == null || m.status().severity() > worst.status().severity()) {
                worst = m;
            } else if (m.status().severity() == worst.status().severity()
                    && isWorse(m.actual(), worst.actual(), biggerIsWorse(bandFor(m.status())))) {
                worst = m;
            }
        }
        return worst;
    }

    /**
     * The band a match of {@code status} was decided against — report.js's {@code bandFor}: the warn
     * band for a WARN, else the fail band, else the warn band; {@code null} for a rule with neither.
     */
    private Predicate bandFor(AssertionStatus status) {
        if (rule == null) return null;
        if (status == AssertionStatus.WARN && rule.warn() != null) return rule.warn();
        return rule.fail() != null ? rule.fail() : rule.warn();
    }

    /** Whether a bigger value is further past {@code band}: upward, or no band at all. */
    private static boolean biggerIsWorse(Predicate band) {
        return band == null || band.op() == Predicate.Op.GT || band.op() == Predicate.Op.GTE;
    }

    /** report.js's {@code isWorseActual}: a measured value beats an unmeasured one, and ties keep the earlier. */
    private static boolean isWorse(double candidate, double current, boolean biggerIsWorse) {
        if (Double.isNaN(candidate)) return false;
        if (Double.isNaN(current)) return true;
        return biggerIsWorse ? candidate > current : candidate < current;
    }

    /** A truncated copy carrying at most {@code limit} matches, worst first. */
    public RuleResult cappedTo(int limit) {
        if (matches.size() <= limit) {
            return this;
        }
        List<Match> sorted = new ArrayList<>(matches);
        sorted.sort((a, b) -> Integer.compare(b.status().severity(), a.status().severity()));
        return new RuleResult(rule, status, new ArrayList<>(sorted.subList(0, limit)),
                matchedTotal, notJudged, notJudgedDetail);
    }
}
