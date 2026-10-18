package com.jmeterwebinsightreport.core.assertion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One declared check: what to measure, on what, and what counts as a breach.
 *
 * <p>Only declared rules ever decide a verdict. Everything else the report shows is data.
 *
 * <p>A {@link Judged}, like a server-metric KPI — the one seam the two share, so a gate's
 * arithmetic can count either without knowing which it has.
 */
public final class AssertionRule implements Judged {

    private final String id;
    private final boolean idDeclared;
    private final String description;
    private final AssertionTarget target;
    private final String metricName;
    private final MetricRef metric;
    private final Predicate warn;
    private final Predicate fail;
    private final boolean enabled;
    private final List<Usability> declarationProblems;

    private AssertionRule(Builder b) {
        this.target = b.target;
        this.metricName = b.metricName;
        this.metric = MetricRef.parse(b.metricName);
        this.warn = b.warn;
        this.fail = b.fail;
        this.enabled = b.enabled;
        this.declarationProblems = b.declarationProblems == null
                ? Usability.NONE
                : Collections.unmodifiableList(new ArrayList<>(b.declarationProblems));
        this.description = b.description == null ? "" : b.description;
        this.idDeclared = b.id != null && !b.id.trim().isEmpty();
        this.id = this.idDeclared ? b.id.trim() : deriveId();
    }

    /**
     * Whether the AUTHOR named this rule, as opposed to this class naming it for them.
     *
     * <p>Needed because the derived name is indistinguishable from a written one once it is in
     * the field: both are just an id. The report writes the document back, and writing a derived
     * name into {@code assertions[]} would silently add a key the tester never typed — on a save
     * they made for an unrelated reason. Round-trip fidelity needs to know the difference.
     */
    public boolean idDeclared() { return idDeclared; }

    /**
     * {@code slug(target)-metric}, matching the design's rule for an omitted id.
     *
     * <p>Ids reach the CI/CD JSON, JUnit testcase names and every failure message, so an
     * auto-derived one has to be both stable across runs and readable in a CI log.
     */
    private String deriveId() {
        String metricPart = metricName == null ? "metric" : metricName.replace(".", "");
        return target.slug() + "-" + metricPart;
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    public AssertionTarget target() { return target; }

    /** The metric exactly as written, kept for error messages that must quote the typo. */
    public String metricName() { return metricName; }

    /** The parsed metric, or {@code null} when the name is not one — a NOT_JUDGED /
        INVALID_RULE rule, reported and counted, never a verdict. */
    public MetricRef metric() { return metric; }

    public Predicate warn() { return warn; }
    public Predicate fail() { return fail; }
    @Override public boolean enabled() { return enabled; }

    /** True when the rule declares at least one USABLE band. Without one it can never decide. */
    public boolean hasBands() {
        return warn != null || fail != null;
    }

    /**
     * What the loader could not make sense of, in the words it used at the time.
     *
     * <p>Some faults are invisible by the time a rule object exists. An unreadable band and an
     * absent one are both {@code null} here — {@code hasBands()} cannot tell them apart, which is
     * how a rule declaring {@code fail: {gt: 100, lt: 50}} beside a good warn band went on being
     * judged on the warn band alone, its fail threshold silently never applied, exit 0. A retired
     * key is worse: it never reaches this class at all, because the unknown-key policy swallows
     * it before the rule is built.
     *
     * <p>So the loader writes those facts here, and {@link Usability} reads them first. This is
     * the same route {@link AssertionTarget#unusable} already takes for a target the loader could
     * not read — a fact known only at load time, carried on the model rather than in a parallel
     * list that nothing downstream can join. It has to be the model: findings are keyed by the
     * file's label and rules by their id, and for an id-less rule those are different strings.
     *
     * <p>Never null; empty for the overwhelming majority of rules.
     */
    public List<Usability> declarationProblems() {
        return declarationProblems;
    }

    /**
     * Human-readable criteria, e.g. {@code "warn > 650 ms  ·  fail > 800 ms"}.
     */
    public String describeCriteria() {
        String unit = metric != null ? metric.unit().suffix() : "";
        StringBuilder sb = new StringBuilder();
        if (warn != null) {
            sb.append("warn ").append(warn.describe(unit));
        }
        if (fail != null) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("fail ").append(fail.describe(unit));
        }
        return sb.toString();
    }

    public static Builder builder(AssertionTarget target, String metricName) {
        return new Builder(target, metricName);
    }

    public static final class Builder {
        private final AssertionTarget target;
        private final String metricName;
        private String id;
        private String description;
        private Predicate warn;
        private Predicate fail;
        private boolean enabled = true;
        private List<Usability> declarationProblems;

        private Builder(AssertionTarget target, String metricName) {
            this.target = target;
            this.metricName = metricName;
        }

        public Builder id(String v) { this.id = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder warn(Predicate v) { this.warn = v; return this; }
        public Builder fail(Predicate v) { this.fail = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }

        /**
         * Record something the loader could not make sense of — see
         * {@link AssertionRule#declarationProblems()}.
         */
        public Builder declarationProblem(Usability problem) {
            if (problem != null) {
                if (declarationProblems == null) {
                    declarationProblems = new ArrayList<>(2);
                }
                declarationProblems.add(problem);
            }
            return this;
        }

        public AssertionRule build() {
            return new AssertionRule(this);
        }
    }

    @Override
    public String toString() {
        return id + " [" + target.describe() + " " + metricName + " " + describeCriteria() + "]";
    }
}
