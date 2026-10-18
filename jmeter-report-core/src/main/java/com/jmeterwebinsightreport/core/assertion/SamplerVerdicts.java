package com.jmeterwebinsightreport.core.assertion;


import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-sampler verdicts, folded out of the per-rule results.
 *
 * <p>Rules are the model; the report's sampler table, the {@code assertStatus} field in the CI/CD
 * JSON and the JUnit failure text are all per-sampler. Something has to do the folding, and
 * doing it here — once — is what lets those three outputs stay byte-identical across the
 * evaluator swap while the engine underneath changes completely.
 *
 * <p>A sampler's verdict is the worst across every rule that judged it. Rules that did not
 * gate — disabled, skipped — contribute nothing, which is what keeps a parked rule
 * from making a sampler look evaluated.
 */
public final class SamplerVerdicts {

    /** What one sampler was judged to be, and by which metrics. */
    public static final class Verdict {
        private final AssertionStatus status;
        private final Map<String, AssertionStatus> metricStatuses;
        private final Map<String, Evidence> evidence;
        private final boolean wholeRun;

        Verdict(AssertionStatus status, Map<String, AssertionStatus> metricStatuses,
                Map<String, Evidence> evidence, boolean wholeRun) {
            this.status = status;
            this.metricStatuses = metricStatuses;
            this.evidence = evidence;
            this.wholeRun = wholeRun;
        }

        public AssertionStatus getOverallStatus() { return status; }

        /**
         * Metric key to status, for the JUnit failure body.
         *
         * <p>Keys are the legacy spellings — {@code p95}, {@code p99}, {@code errorRate},
         * {@code mean} — because that is what the existing failure text is built from and what
         * pipelines have been parsing.
         */
        public Map<String, AssertionStatus> getMetricStatuses() {
            return Collections.unmodifiableMap(metricStatuses);
        }

        /**
         * The match a metric's status was taken from, and the rule that judged it, or {@code null}
         * when these verdicts were assembled without a gate ({@link #builder()}).
         *
         * <p>For the JUnit sampler suite (L11): its message named a failing metric only when it was
         * one of the four legacy ones, so a sampler failed by a p50 or a throughput rule read
         * "ASSERT FAIL:" and nothing else. Every failing metric is named now, and a metric outside
         * those four is named by the measurement that failed it — its number, its unit and the band
         * it breached — which is this.
         */
        public Evidence evidence(String metric) {
            return evidence.get(metric);
        }

        /**
         * Whether this verdict is the whole run's — folded from {@code scope: "overall"} rules —
         * rather than a sampler's. The CI/CD JSON writes it as {@code "scope": "overall"} beside the
         * {@code "Whole run"} name (CORE-1): that name is display text, and a sampler can be called
         * it.
         */
        public boolean isWholeRun() { return wholeRun; }
    }

    /**
     * One rule's match, as the evidence for a metric's status on one sampler: the first rule, in
     * declaration order, to reach that status. Every rule on one sampler and one metric measures the
     * same value, so the choice decides only which band is quoted beside it: the band the deciding
     * rule breached.
     */
    public static final class Evidence {
        private final AssertionRule rule;
        private final RuleResult.Match match;
        private final double[] bands;

        Evidence(AssertionRule rule, RuleResult.Match match, double[] bands) {
            this.rule = rule;
            this.match = match;
            this.bands = bands;
        }

        public AssertionRule rule() { return rule; }

        public RuleResult.Match match() { return match; }

        /**
         * The constants of every band of every rule that judged this metric on this sampler, warn and
         * fail alike ({@link Digits#bands}, in declaration order): the bands the measured value is
         * written beside (REV-6). They all measured the same value, so it must sit on its own side of
         * each — written beside the deciding rule's bands alone, a FAIL at 119.36094 under
         * {@code gt 119.36} read "119.361", on the {@code gt 119.361} another rule PASSed it under.
         */
        public double[] bands() { return bands.clone(); }
    }

    private final Map<String, Verdict> bySampler;
    private final AssertionStatus overall;

    private SamplerVerdicts(Map<String, Verdict> bySampler, AssertionStatus overall) {
        this.bySampler = bySampler;
        this.overall = overall;
    }

    /**
     * Fold a gate's results into per-sampler verdicts.
     *
     * <p>Only gating rules contribute. An unmatched rule contributes nothing to any sampler by
     * definition — it matched none — which is how a threshold naming an absent sampler stays
     * invisible in the per-sampler output, exactly as it was before.
     */
    public static SamplerVerdicts from(GateSummary gate) {
        Map<String, Verdict> bySampler = new LinkedHashMap<>();
        AssertionStatus worst = null;

        // ruleResults(), not results(): a server KPI never enters a per-sampler verdict —
        // pod names would leak into assertViolations, assertStatus and the sampler suite.
        for (RuleResult result : gate.ruleResults()) {
            if (!result.status().gates()) {
                continue;
            }
            String metricKey = metricKeyOf(result.rule());
            boolean overallScope = result.rule().target().kind() == AssertionTarget.Kind.OVERALL;
            for (RuleResult.Match match : result.matches()) {
                if (!match.status().gates()) {
                    continue;
                }
                AssertionStatus status = match.status();
                Verdict existing = bySampler.get(match.samplerName());
                Map<String, AssertionStatus> metrics = existing != null
                        ? new LinkedHashMap<>(existing.metricStatuses)
                        : new LinkedHashMap<String, AssertionStatus>();
                Map<String, Evidence> evidence = existing != null
                        ? new LinkedHashMap<>(existing.evidence)
                        : new LinkedHashMap<String, Evidence>();

                // Two rules on the same sampler and metric (a hand-written one beside a derived
                // one) take the worse of the two rather than whichever happened to come last.
                AssertionStatus previousForMetric = metrics.get(metricKey);
                metrics.put(metricKey, worseOf(previousForMetric, status));
                // The evidence moves only with the status, so it is the first rule to reach it; the
                // bands it is written beside are every judging rule's, whichever decided.
                Evidence previous = evidence.get(metricKey);
                double[] bands = concat(previous == null ? new double[0] : previous.bands,
                        Digits.bands(result.rule().warn(), result.rule().fail()));
                if (previousForMetric == null || status.severity() > previousForMetric.severity()) {
                    evidence.put(metricKey, new Evidence(result.rule(), match, bands));
                } else {
                    evidence.put(metricKey, new Evidence(previous.rule, previous.match, bands));
                }

                AssertionStatus samplerStatus = worseOf(
                        existing != null ? existing.status : null, status);
                boolean wholeRun = overallScope || (existing != null && existing.wholeRun);
                bySampler.put(match.samplerName(), new Verdict(samplerStatus, metrics, evidence, wholeRun));
                worst = worseOf(worst, status);
            }
        }
        return new SamplerVerdicts(bySampler, worst);
    }

    /**
     * The metric key a rule reports under.
     *
     * <p>{@code mean} rather than {@code meanResponseTime}, {@code p95} rather than
     * {@code p95.0} — the spellings the existing JUnit body uses.
     *
     * <p>This used to translate {@code median} to {@code p50} here, while the report's charts
     * translated {@code p50} to {@code median} in the other direction. {@link MetricRef} now
     * canonicalises both spellings to {@code p50} once, so there is nothing left to translate.
     */
    private static String metricKeyOf(AssertionRule rule) {
        return rule.metric() != null ? rule.metric().canonicalName() : rule.metricName();
    }

    private static double[] concat(double[] a, double[] b) {
        double[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }


    /**
     * The worse of two verdicts, by the engine's own severity order.
     *
     * <p>This used to fold into a three-value enum, which meant INVALID was reported to a
     * pipeline as FAIL — a rule that could not run, described as a rule that ran and lost.
     * {@link AssertionStatus#worst} is the one ordering now, so a sampler's verdict says the
     * same thing the gate says about it.
     */
    private static AssertionStatus worseOf(AssertionStatus a, AssertionStatus b) {
        if (a == null) return b;
        if (b == null) return a;
        return AssertionStatus.worst(a, b);
    }

    /**
     * Assemble verdicts directly, without an engine.
     *
     * <p>For callers that already know the answer: the serializer tests, which are about the
     * shape of the JSON and XML rather than about how a verdict was reached, and would
     * otherwise have to construct a whole run to assert on one field.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Verdict> bySampler = new LinkedHashMap<>();
        private AssertionStatus overall;

        private Builder() {
        }

        public Builder put(String samplerName, AssertionStatus status, Map<String, AssertionStatus> metrics) {
            bySampler.put(samplerName, new Verdict(status,
                    metrics == null ? new LinkedHashMap<String, AssertionStatus>() : new LinkedHashMap<>(metrics),
                    new LinkedHashMap<String, Evidence>(), false));
            overall = worseOf(overall, status);
            return this;
        }

        public SamplerVerdicts build() {
            return new SamplerVerdicts(bySampler, overall);
        }
    }

    /** Verdicts keyed by sampler name, in the order the rules produced them. */
    public Map<String, Verdict> bySampler() {
        return Collections.unmodifiableMap(bySampler);
    }

    public Verdict get(String samplerName) {
        return bySampler.get(samplerName);
    }

    public boolean containsKey(String samplerName) {
        return bySampler.containsKey(samplerName);
    }

    public boolean isEmpty() {
        return bySampler.isEmpty();
    }

    /**
     * The worst verdict across every sampler, or {@code null} when nothing was judged.
     *
     * <p>Null and PASS are different facts: a run with no gate has not passed one. The CI/CD
     * JSON's {@code assertsEvaluated} flag exists for exactly this distinction, because conflating
     * them is how a typo in the annotations path silently switches the gate off.
     */
    public AssertionStatus overallStatus() {
        return overall;
    }
}
