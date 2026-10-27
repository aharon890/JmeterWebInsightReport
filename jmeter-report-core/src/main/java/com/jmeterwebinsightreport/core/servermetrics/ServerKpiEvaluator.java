package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.Digits;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.assertion.RuleResult;
import com.jmeterwebinsightreport.core.servermetrics.collect.Coverage;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the declared KPIs plus a collected payload into one {@link KpiResult} per KPI — the
 * server-side {@code AssertionEvaluator}, with the collection step the client does not have.
 *
 * <h2>Order of decisions</h2>
 *
 * <ol>
 *   <li>{@code enabled: false} → NOT_JUDGED / DISABLED.</li>
 *   <li>{@link KpiUsability#refusal} → NOT_JUDGED / INVALID_RULE (unknown source, unknown
 *       reducer, no bands, a tolerance on a reducer that has no level to hold, …).</li>
 *   <li>{@code requiresLoad} (the KPI's, else the run-level default): names no client rule, or
 *       a rule that is not about throughput → INVALID_RULE; that rule FAILED → NOT_JUDGED /
 *       LOAD_NOT_ACHIEVED; that rule decided nothing → the same reason, naming the rule and
 *       its own reason, and saying it was not judged rather than that a target was missed;
 *       PASS or WARN → carry on. The first two arms need no payload, and
 *       {@link #referenceRefusals} asks them on the interim report too.</li>
 *   <li>The series by {@code (source, query as written)}: none collected → INVALID_RULE ("the
 *       payload was collected against an older file — re-collect"); a
 *       {@link CollectionStatus} with a reason → that reason (SOURCE_FAILED, NO_MATCH,
 *       LOW_COVERAGE), with the error or the coverage figure as detail.</li>
 *   <li>The number: per instance, the points inside the judged window reduced as the KPI says;
 *       then {@link Across} the instances. No point in the window, or a reducer with nothing to
 *       say → NOT_JUDGED / NO_VALUE.</li>
 *   <li>{@code divideBy}: the number over the named load rule's measured value. Missing or not
 *       a throughput rule → INVALID_RULE; that rule decided nothing → LOAD_NOT_ACHIEVED; a
 *       measured zero → NO_VALUE (nothing is a ratio over zero).</li>
 *   <li>{@code fail} before {@code warn}, on the number, through {@link Predicate#breaches} —
 *       exactly as the client. {@link #judge} is the only producer of PASS, WARN or FAIL.</li>
 * </ol>
 *
 * <h2>The window</h2>
 *
 * <p>The payload's, minus {@code [start, rampUpEnd]} when the run declared a ramp-up and the
 * block's {@code collection.excludeRampUp} is true (the default). A point at {@code rampUpEnd}
 * itself is steady state. Pre- and cool-down windows are v2+ (delivery plan D9).
 *
 * <h2>The reducers</h2>
 *
 * <p>{@code max}, {@code min}, {@code avg}, {@code last} as named; {@code pN} by nearest rank;
 * {@code slope} by least squares over the window in units <em>per minute</em>. A tolerance:
 * {@code sustainedFor d} on {@code max} is the highest level held for at least {@code d} —
 * {@code max} over every window start of {@code min} of the values in {@code [t, t + d]},
 * counting only windows whose <em>samples</em> span {@code d} (a sample at {@code t} and one at
 * {@code t + d} or later) — and the mirror on {@code min}; {@code breachBudget b} on {@code max}
 * is the {@code (1 − b)} quantile of the points (the level exceeded by at most {@code b} of the
 * samples), on {@code min} the {@code b} quantile. A {@code d} no longer than one step is the
 * plain reducer.
 *
 * <p>Both tolerances count the samples the series carries, not wall-clock time. A window with a
 * missing scrape inside it still spans {@code d}, so the gap is bridged: the level is taken over
 * the samples that are there, and a breach on both sides of the hole reads as held across it.
 * {@code breachBudget}'s denominator is likewise the samples present, not the window's length
 * divided by the step. That is what PromQL's {@code *_over_time} functions do, and a
 * {@code query_range} answer rarely has interior gaps; coverage is where a sparse series shows
 * (D28). Java and the browser mirror agree on this, and the vectors pin it.
 *
 * <p>What {@code server-parity-vectors.json} pins, vector first: the browser mirror (M6) has to
 * reproduce every number here, so none of them is defined by this class alone.
 */
public final class ServerKpiEvaluator {

    /** The metric a {@code requiresLoad} / {@code divideBy} rule must be about. */
    static final String LOAD_METRIC = "throughput";

    /** Evaluate every KPI against the payload. An absent payload judges nothing and returns an empty list. */
    public List<KpiResult> evaluate(ServerMetricsConfig config, ServerMetricsPayload payload,
                                    GateSummary clientGate) {
        List<KpiResult> results = new ArrayList<>();
        if (config == null || payload == null) {
            return results;
        }
        Map<String, RuleResult> clientRules = indexClientRules(clientGate);
        long[] window = judgedWindow(config, payload.getWindow());
        for (ServerKpi kpi : config.kpis()) {
            results.add(evaluateOne(kpi, config, payload, clientRules, window));
        }
        return results;
    }

    /**
     * The {@code INVALID_RULE} every enabled, otherwise usable KPI earns from its
     * {@code requiresLoad} or {@code divideBy} against the client gate — decided with no payload
     * at all, for the interim report.
     *
     * <p>A {@code requiresLoad: "load:nope"} names no client rule whether or not anything was
     * collected, and the client gate exists at step 1 of the choreography exactly as it does at
     * step 3. Asked only inside {@link #evaluate}, which needs a payload, the same broken file
     * exited 0 from {@code -n -t} and 1 from {@code -g -o}: a defect whose reporting depended on
     * the weather. This runs the very same arms {@link #evaluate} runs, in the same order — a KPI
     * whose load rule FAILED stops there in both, so a typo'd {@code divideBy} behind it is
     * reported by neither step rather than by one of them.
     */
    public List<KpiResult> referenceRefusals(ServerMetricsConfig config, GateSummary clientGate) {
        List<KpiResult> refusals = new ArrayList<>();
        if (config == null) {
            return refusals;
        }
        Map<String, RuleResult> clientRules = indexClientRules(clientGate);
        for (ServerKpi kpi : config.kpis()) {
            if (!kpi.enabled() || KpiUsability.refusal(kpi) != null) {
                continue;   // parked, or already refused by the loader and counted from there
            }
            KpiResult blocked = loadReferences(kpi, config, clientRules);
            if (blocked != null && blocked.notJudged() == NotJudged.INVALID_RULE) {
                refusals.add(blocked);
            }
        }
        return refusals;
    }

    private KpiResult evaluateOne(ServerKpi kpi, ServerMetricsConfig config, ServerMetricsPayload payload,
                                  Map<String, RuleResult> clientRules, long[] window) {
        if (!kpi.enabled()) {
            return KpiResult.notJudged(kpi, NotJudged.DISABLED);
        }
        KpiUsability refusal = KpiUsability.refusal(kpi);
        if (refusal != null) {
            return KpiResult.notJudged(kpi, NotJudged.INVALID_RULE, refusal.reasonText());
        }

        KpiResult blocked = loadReferences(kpi, config, clientRules);
        if (blocked != null) {
            return blocked;
        }

        ServerMetricsPayload.SeriesRecord series = payload.findByQuery(kpi.source(), kpi.query(), kpi.gate());
        if (series == null) {
            // No source in the sentence: a collection has one (C8), so naming it says nothing.
            return KpiResult.notJudged(kpi, NotJudged.INVALID_RULE, "no series was collected for query "
                    + kpi.query() + " - the payload was collected against a file that did not declare "
                    + "this server assert; re-run wir-collect");
        }
        double coverage = series.getCoverage() == null ? Double.NaN : series.getCoverage();
        int instances = series.getInstances().size();
        CollectionStatus status;
        try {
            status = series.collectionStatus();
        } catch (IllegalArgumentException unknownWireName) {
            return KpiResult.notJudged(kpi, NotJudged.SOURCE_FAILED,
                    "the payload carries a series status '" + series.getStatus() + "' this version does not know",
                    coverage, instances);
        }
        NotJudged reason = status.toReason();
        if (reason != null) {
            return KpiResult.notJudged(kpi, reason, collectionDetail(status, series, config, payload),
                    coverage, instances);
        }

        long step = stepOf(payload, kpi.source());
        KpiResult unanswerable = sustainedNeedsWholeSteps(kpi, step);
        if (unanswerable != null) {
            return unanswerable;
        }
        Double reduced = reduce(kpi, series, window, step);
        if (reduced == null) {
            KpiResult spacing = sustainedNeedsWholeSpacing(kpi, series, window, step, coverage, instances);
            if (spacing != null) {
                return spacing;
            }
            return KpiResult.notJudged(kpi, NotJudged.NO_VALUE, "no value: " + kpi.reducer().describe()
                    + " over " + describeWindow(window) + " has nothing to reduce - the series carries no "
                    + "complete window there", coverage, instances);
        }
        double actual = reduced;

        if (kpi.divideBy() != null) {
            RuleResult.Match divisor = clientRules.get(kpi.divideBy()).worstJudged();
            double by = divisor == null ? Double.NaN : divisor.actual();
            if (Double.isNaN(by) || by == 0) {
                return KpiResult.notJudged(kpi, NotJudged.NO_VALUE, "divideBy names '" + kpi.divideBy()
                        + "', which measured " + (Double.isNaN(by) ? "nothing" : "0")
                        + " - a ratio over that cannot be judged", coverage, instances);
            }
            actual = actual / by;
        }

        return judge(kpi, actual, coverage, instances);
    }

    /**
     * The only producer of PASS, WARN or FAIL: {@code fail} first, then {@code warn}, on a
     * number the payload actually carried.
     */
    private static KpiResult judge(ServerKpi kpi, double actual, double coverage, int instances) {
        if (kpi.fail() != null && kpi.fail().breaches(actual)) {
            return KpiResult.of(kpi, AssertionStatus.FAIL, actual, kpi.fail(), coverage, instances);
        }
        if (kpi.warn() != null && kpi.warn().breaches(actual)) {
            return KpiResult.of(kpi, AssertionStatus.WARN, actual, kpi.warn(), coverage, instances);
        }
        return KpiResult.of(kpi, AssertionStatus.PASS, actual, null, coverage, instances);
    }

    // ---- the load rule --------------------------------------------------------------

    /**
     * {@code requiresLoad} (the KPI's, else the run-level default), then {@code divideBy}: the
     * first reason either stops this KPI, or {@code null}. One method for {@link #evaluate} and
     * {@link #referenceRefusals}, so step 1 and step 3 cannot ask the question differently.
     */
    private static KpiResult loadReferences(ServerKpi kpi, ServerMetricsConfig config,
                                            Map<String, RuleResult> clientRules) {
        String loadRef = kpi.requiresLoad() != null ? kpi.requiresLoad() : config.requiresLoad();
        if (loadRef != null) {
            KpiResult blocked = loadRuleBlocks(kpi, "requiresLoad", loadRef, clientRules, true);
            if (blocked != null) {
                return blocked;
            }
        }
        // divideBy is resolved HERE, beside requiresLoad, and not beside the division it feeds:
        // a typo'd rule id is a defect in the file, and a defect in the file must be reported
        // whether or not this run happened to carry a number to divide.
        if (kpi.divideBy() != null) {
            return loadRuleBlocks(kpi, "divideBy", kpi.divideBy(), clientRules, false);
        }
        return null;
    }

    /**
     * Why the named client rule stops this KPI, or {@code null} when it does not.
     *
     * @param failBlocks true for {@code requiresLoad} (a FAIL on the load rule withholds the
     *                   verdict), false for {@code divideBy} (a FAIL still measured something to
     *                   divide by; only a rule that decided nothing has no number)
     */
    private static KpiResult loadRuleBlocks(ServerKpi kpi, String field, String ref,
                                            Map<String, RuleResult> clientRules, boolean failBlocks) {
        RuleResult load = clientRules.get(ref);
        if (load == null) {
            return KpiResult.notJudged(kpi, NotJudged.INVALID_RULE, field + " names '" + ref
                    + "', which is not a declared client rule"
                    + (clientRules.isEmpty() ? " (the file declares none)"
                    : " (declared: " + String.join(", ", clientRules.keySet()) + ")"));
        }
        AssertionRule rule = load.rule();
        String metric = rule.metric() != null ? rule.metric().canonicalName() : rule.metricName();
        if (rule.metric() != null && !LOAD_METRIC.equals(metric)) {
            return KpiResult.notJudged(kpi, NotJudged.INVALID_RULE, field + " names '" + ref + "', a "
                    + metric + " rule - it must name a throughput rule, the load the run was meant to "
                    + "achieve (load:throughput)");
        }
        if (!failBlocks && load.matches().size() > 1) {
            // D9 defines the divisor as the rule's measured value over its own target. A rule that
            // matched many samplers has many of those, and worstJudged() would silently pick one -
            // the load furthest past its band, which is not "the run's load" either. Refuse rather
            // than choose.
            return KpiResult.notJudged(kpi, NotJudged.INVALID_RULE, field + " names '" + ref
                    + "', which matched " + load.matches().size() + " samplers - a ratio needs one "
                    + "measured load, so name a rule with a single target (\"scope\": \"overall\", "
                    + "or one sampler)");
        }
        if (load.status() == AssertionStatus.NOT_JUDGED) {
            // The load rule decided nothing, so nothing was missed: say it was not judged and why,
            // in the load rule's own reason, and never that a target was missed. This used to read
            // "load rule 'x' matched nothing", which behind LOAD_NOT_ACHIEVED's old phrase became
            // "the run missed its load target: load rule 'x' matched nothing" in the JUnit message
            // - a sentence that contradicts itself. Not fatal here: the load rule's own reason is
            // the config error where there is one (INVALID_RULE, on the client) and nothing is
            // fatal where there is none (NO_MATCH, DISABLED).
            String why = load.notJudged() == null ? "decided nothing" : load.notJudged().phrase();
            return KpiResult.notJudged(kpi, NotJudged.LOAD_NOT_ACHIEVED, "load rule '" + ref
                    + "' was not judged - it " + why
                    + (load.notJudgedDetail() != null ? ": " + load.notJudgedDetail() : "")
                    + " - so the load this server assert depends on was never measured");
        }
        if (failBlocks && load.status() == AssertionStatus.FAIL) {
            RuleResult.Match worst = load.worstJudged();
            String unit = rule.metric() != null ? rule.metric().unit().suffix() : "";
            // The load rule's own bands, warn and fail: the client actual is quoted beside them, so
            // it is written as the CI/CD JSON and JUnit write that same actual (Q19).
            String measured = worst == null ? "" : ": " + metric + " "
                    + formatValue(worst.actual(), Digits.bands(rule.warn(), rule.fail())) + unit
                    + (worst.breachedBand() != null ? " breached " + worst.breachedBand().describe(unit) : "");
            return KpiResult.notJudged(kpi, NotJudged.LOAD_NOT_ACHIEVED, "load rule '" + ref + "' FAILED"
                    + measured);
        }
        return null;
    }

    /**
     * Why a {@code sustainedFor} cannot be answered at this source's resolution, or {@code null}.
     *
     * <p>A window is complete when its data spans {@code d}, and points arrive every {@code step},
     * so a {@code d} that is not a whole number of steps has <em>no</em> complete window anywhere
     * in the series: {@code sustainedFor: "20s"} against a 15s step can never be true, whatever
     * the run did. Left to the reducer that is NO_VALUE — not a config error, so it exits 0 and
     * the KPI silently decides nothing, which is the exact shape this project keeps closing. It
     * is a defect in the file, and the file is where it can be fixed, so it is INVALID_RULE and
     * the sentence names the step.
     *
     * <p>The step is the collection's — {@code wir-collect --step} — and the sentence says so. It
     * used to say "the source's 1s steps", from before C8 took sources out of the file (U8d), which
     * sent a reader looking for a source to edit.
     */
    private static KpiResult sustainedNeedsWholeSteps(ServerKpi kpi, long step) {
        Reducer reducer = kpi.reducer();
        if (spansWholeSteps(reducer, step)) {
            return null;
        }
        long d = reducer.over().sustainedForMillis();
        return KpiResult.notJudged(kpi, NotJudged.INVALID_RULE, "reduce.over sustainedFor "
                + Durations.format(d) + " is not a whole number of the collection's " + Durations.format(step)
                + " steps, so no window can ever span it - use a multiple of "
                + Durations.format(step) + ", or collect with a finer wir-collect --step");
    }

    /**
     * {@link #sustainedNeedsWholeSteps} for the spacing the data actually has, asked only when the
     * reduction found no complete window (research H11). A CSV keeps its own spacing whatever
     * {@code --step} says: 4 s data collected at the default 15 s step passed the step test for a
     * {@code sustainedFor 30s}, and no window could span 30 s of 4 s points - NO_VALUE, a silent
     * nothing beside a chart showing the breach. Asked after the reduction, so no verdict the
     * points could reach is moved; mirrored by report.js's function of the same name.
     */
    private static KpiResult sustainedNeedsWholeSpacing(ServerKpi kpi, ServerMetricsPayload.SeriesRecord series,
                                                        long[] window, long step, double coverage, int instances) {
        Reducer reducer = kpi.reducer();
        if (reducer == null || reducer.over() == null || !reducer.over().isSustainedFor()) {
            return null;
        }
        long spacing = measuredSpacing(series, window);
        long d = reducer.over().sustainedForMillis();
        if (spacing <= 0 || spacing == step || d <= spacing || d % spacing == 0) {
            return null;
        }
        return KpiResult.notJudged(kpi, NotJudged.INVALID_RULE, "reduce.over sustainedFor " + Durations.format(d)
                + " is not a whole number of the data's measured " + Durations.format(spacing) + " spacing (collected at --step "
                + Durations.format(step) + "), so no window can ever span it - use a multiple of "
                + Durations.format(spacing) + ", or export the data at the --step it is collected with",
                coverage, instances);
    }

    /**
     * The most frequent gap between consecutive points inside the window, over every instance;
     * the smaller on a tie; 0 when no instance has two points there.
     */
    static long measuredSpacing(ServerMetricsPayload.SeriesRecord series, long[] window) {
        Map<Long, Integer> counts = new java.util.TreeMap<>();
        for (ServerMetricsPayload.InstanceRecord inst : series.getInstances()) {
            List<ServerMetricsPayload.Point> points = inWindow(inst.getPoints(), window);
            for (int i = 1; i < points.size(); i++) {
                long gap = points.get(i).getTs() - points.get(i - 1).getTs();
                if (gap > 0) {
                    counts.merge(gap, 1, Integer::sum);
                }
            }
        }
        long best = 0;
        int most = 0;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {   // ascending, so a tie keeps the smaller
            if (e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return best;
    }

    /**
     * False exactly when {@link #sustainedNeedsWholeSteps} refuses: a {@code sustainedFor} longer
     * than one step that is not a whole number of them. One test for the evaluator and
     * {@link #judgedActual}.
     */
    private static boolean spansWholeSteps(Reducer reducer, long step) {
        if (reducer == null || reducer.over() == null || !reducer.over().isSustainedFor() || step <= 0) {
            return true;
        }
        long d = reducer.over().sustainedForMillis();
        return d <= step || d % step == 0;
    }

    private static Map<String, RuleResult> indexClientRules(GateSummary clientGate) {
        Map<String, RuleResult> byId = new LinkedHashMap<>();
        if (clientGate != null) {
            for (RuleResult r : clientGate.ruleResults()) {
                byId.put(r.rule().id(), r);
            }
        }
        return byId;
    }

    // ---- the window -------------------------------------------------------------------

    /** {@code [start, end]} in epoch millis, after the ramp-up exclusion. */
    static long[] judgedWindow(ServerMetricsConfig config, ServerMetricsPayload.Window window) {
        return judgedWindow(config.excludeRampUp(), window);
    }

    private static long[] judgedWindow(boolean excludeRampUp, ServerMetricsPayload.Window window) {
        if (window == null) {
            return new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
        }
        long start = window.getStart();
        if (excludeRampUp && window.getRampUpEnd() != null && window.getRampUpEnd() > start) {
            start = window.getRampUpEnd();
        }
        return new long[] {start, window.getEnd()};
    }

    /**
     * The largest value any instance of {@code series} reached inside the judged window, over every
     * instance and every point the collector fetched — or {@code null} when no point falls there.
     *
     * <p>For what the page cannot recompute: it carries a capped copy of a big series (twenty
     * instances, points thinned), and Generate from sources seeds a KPI at headroom over the
     * observed maximum. Seeded from the thinned copy, a spike on a dropped instance or a dropped
     * point was not seen, and the seed FAILed on the very run it claims to pass (C-8).
     *
     * <p><b>The contract is the level's maximum, and nothing else.</b> It is
     * {@link #judgedActual} for {@code reduce: max}, {@code across: max} and no tolerance — by
     * construction, not by a second loop — which is exactly what a custom-query seed declares
     * ({@code report.js} {@code seedKpi}, custom branch), so for that seed it is the number this
     * evaluator will compare. It is <em>not</em> the number for any other reducer: a min metric
     * ({@code up}: min, across min) is judged on its lowest instance's minimum, a slope on a rate
     * per minute, {@code last} on each instance's final point. A catalogue metric's number is
     * {@link #judgedActual} over the catalogue's reducer and across, which the payload writer
     * ships beside this as {@code judgedActuals}, one per metric. The page's {@code seedCandidates}
     * once took this as the number for every capped series, catalogue ones included, and its
     * catalogue seed then prefilled from the thinned copy (SF8); it reads {@code judgedMax} for a
     * custom series only now, and {@code judgedActuals} (through {@code observedOfSeries}) for a
     * catalogue metric.
     *
     * @param config the block the KPIs are judged under, for its ramp-up exclusion; null reads as
     *               the default, which excludes it
     */
    public static Double judgedMax(ServerMetricsConfig config, ServerMetricsPayload payload,
                                   ServerMetricsPayload.SeriesRecord series) {
        return judgedActual(config, payload, series, Reducer.of(Reducer.Kind.MAX), Across.MAX);
    }

    /**
     * The number this evaluator would compare for an assert over {@code series} that declares
     * {@code reducer} and {@code across} and nothing that changes the number (no
     * {@code divideBy}): the points inside the judged window, every instance and every point the
     * collector fetched, reduced per instance and then folded across them — the very reduction
     * {@link #evaluate} runs, not a second one. {@code null} when it has no number: no point in the
     * window, a reducer with nothing to say on any instance, a tolerance on a reducer that has no
     * level to hold ({@link Reducer#toleranceHasLevel}, refused as INVALID_RULE), or a
     * {@code sustainedFor} that is not a whole number of the collection's steps (INVALID_RULE too).
     *
     * <p>For what the page cannot recompute (SF8). On a capped copy — twenty instances, points
     * thinned — the metric picker, Set thresholds and Generate read a different series from the
     * one the build judges: they showed a peak of 3 where this method says 120, and seeded
     * {@code fail > 4.3}, which FAILs the run it was suggested from. The payload writer ships this
     * on a capped series as {@code judgedActuals}, one number per catalogue metric whose query finds
     * that series, each over that metric's own reducer and across, whether or not an assert names it.
     *
     * <p>The series' collection status is not consulted. It travels beside the number, and the
     * page's recompute of an uncapped series ({@code reduceAcross}) does not consult it either, so
     * the capped and uncapped paths are one function of the data; a LOW_COVERAGE series still has
     * a number here, which the evaluator would not judge.
     *
     * @param config the block the KPIs are judged under, for its ramp-up exclusion; null reads as
     *               the default, which excludes it
     * @param across null reads as the default, {@link Across#defaultValue()}
     */
    public static Double judgedActual(ServerMetricsConfig config, ServerMetricsPayload payload,
                                      ServerMetricsPayload.SeriesRecord series, Reducer reducer, Across across) {
        if (payload == null || series == null || reducer == null || !reducer.toleranceHasLevel()) {
            return null;
        }
        long step = stepOf(payload, series.getSource());
        if (!spansWholeSteps(reducer, step)) {
            return null;
        }
        long[] window = judgedWindow(config == null || config.excludeRampUp(), payload.getWindow());
        Double reduced = reduce(reducer, across == null ? Across.defaultValue() : across, series, window, step);
        return reduced == null || Double.isNaN(reduced) || Double.isInfinite(reduced) ? null : reduced;
    }

    private static String describeWindow(long[] window) {
        return "the judged window [" + window[0] + ", " + window[1] + "]";
    }

    private static long stepOf(ServerMetricsPayload payload, String sourceId) {
        ServerMetricsPayload.SourceRecord source = payload.source(sourceId);
        return source == null ? 0 : Math.max(0, source.getStepMillis());
    }

    // ---- the number -------------------------------------------------------------------

    /**
     * Which of the KPI's instances its number comes from, as indices into the series the
     * payload carries: under {@code across: max} the instance with the highest reduced value,
     * under {@code across: min} the one with the lowest; under {@code across: avg}, where every
     * instance contributes, the highest and the lowest — the two that pull the average
     * furthest. Empty when the KPI reads no series or nothing
     * reduced. Computed by the very reduction {@link #evaluate} uses, over the same window.
     *
     * <p>For whatever has to drop instances for display: the page's payload is capped at a fixed
     * number per series, and a cap that kept the first N in collector order dropped the very
     * instance a FAIL was about whenever it sorted late — the chart beside the verdict then showed
     * nothing over the band. The payload writer keeps these first, then the worst of the rest by
     * {@link #rankedInstances}, as the client side keeps the worst ({@code RuleResult.cappedTo}).
     */
    public List<Integer> decidingInstances(ServerKpi kpi, ServerMetricsConfig config, ServerMetricsPayload payload) {
        if (kpi == null || config == null || payload == null || kpi.reducer() == null) {
            return new ArrayList<>();
        }
        ServerMetricsPayload.SeriesRecord series = payload.findByQuery(kpi.source(), kpi.query(), kpi.gate());
        if (series == null) {
            return new ArrayList<>();
        }
        return decidingIn(series, kpi.reducer(), kpi.across(), judgedWindow(config, payload.getWindow()),
                stepOf(payload, kpi.source()));
    }

    /**
     * Which of {@code series}' instances the number {@link #judgedActual} gives for {@code reducer}
     * and {@code across} comes from — the same selection as the KPI form above, for a catalogue
     * number whether or not an assert names it: a series no assert names, or the other metric of a
     * query two share (F6).
     *
     * <p>The metric picker shows a capped catalogue series' number ({@code judgedActuals}) whether
     * or not an assert names the metric, and draws the series beside it. When the payload writer
     * kept only the instances an assert's verdict came from, a series no assert named was drawn
     * from its first twenty in collector order: "low 0" over a flat line at 1, "peak 120 s" over one
     * that never reached 3. So this answers the question with exactly {@link #judgedActual}'s
     * guards, window and step — a tolerance with no level to hold, a {@code sustainedFor} that is
     * not a whole number of steps, no point in the window: no number, and no deciding instance
     * either. A deciding instance exists exactly when that method has a number, and under
     * {@code across: max} or {@code min} that one instance reduces to it.
     *
     * @param config the block the KPIs are judged under, for its ramp-up exclusion; null reads as
     *               the default, which excludes it
     * @param across null reads as the default, {@link Across#defaultValue()}
     */
    public static List<Integer> decidingInstances(ServerMetricsConfig config, ServerMetricsPayload payload,
                                                  ServerMetricsPayload.SeriesRecord series, Reducer reducer,
                                                  Across across) {
        if (payload == null || series == null || reducer == null || !reducer.toleranceHasLevel()) {
            return new ArrayList<>();
        }
        long step = stepOf(payload, series.getSource());
        if (!spansWholeSteps(reducer, step)) {
            return new ArrayList<>();
        }
        long[] window = judgedWindow(config == null || config.excludeRampUp(), payload.getWindow());
        return decidingIn(series, reducer, across == null ? Across.defaultValue() : across, window, step);
    }

    /**
     * The selection both forms share: per instance the reduction {@link #evaluate} runs, inside
     * {@code window}; then the instance with the highest value, the lowest under
     * {@code across: min}, and both under {@code across: avg}.
     */
    private static List<Integer> decidingIn(ServerMetricsPayload.SeriesRecord series, Reducer reducer, Across across,
                                            long[] window, long step) {
        List<Integer> deciding = new ArrayList<>();
        int high = -1;
        int low = -1;
        double highValue = Double.NaN;
        double lowValue = Double.NaN;
        List<ServerMetricsPayload.InstanceRecord> instances = series.getInstances();
        for (int i = 0; i < instances.size(); i++) {
            List<ServerMetricsPayload.Point> points = inWindow(instances.get(i).getPoints(), window);
            if (points.isEmpty()) {
                continue;
            }
            double value = reduceOne(reducer, points, step);
            if (Double.isNaN(value)) {
                continue;
            }
            if (high < 0 || value > highValue) {
                high = i;
                highValue = value;
            }
            if (low < 0 || value < lowValue) {
                low = i;
                lowValue = value;
            }
        }
        if (high < 0) {
            return deciding;
        }
        if (across == Across.MIN) {
            // The low instance is the one the number came from. Keeping the high one here kept the
            // healthy database on the chart and dropped the one that went down (SF2).
            deciding.add(low);
            return deciding;
        }
        deciding.add(high);
        if (across == Across.AVG && low != high) {
            deciding.add(low);
        }
        return deciding;
    }

    /**
     * Every instance of the KPI's series that reduces to a number, worst first: the highest reduced
     * value first under {@code across: max} and {@code avg}, the lowest under {@code min}; ties in
     * index order. Datadog's top() and bottom() rank by the reduced value; {@code avg} takes their
     * default direction (5.4: it was the distance from the instances' mean, which no tool offers,
     * and under {@code avg} the payload writer keeps both deciding instances first anyway). Computed by the reduction, window and
     * step {@link #decidingInstances} uses, so under {@code max} and {@code min} its first is the
     * deciding instance.
     *
     * <p>For a display cap's free slots, after the deciding instances: filled in collector order,
     * they kept an instance far inside the band and dropped a second one past it (C5, S4's C08:
     * 26 instances, 83.3 over a warn at 80 not drawn, 6 drawn).
     */
    public List<Integer> rankedInstances(ServerKpi kpi, ServerMetricsConfig config, ServerMetricsPayload payload) {
        if (kpi == null || config == null || payload == null || kpi.reducer() == null) {
            return new ArrayList<>();
        }
        ServerMetricsPayload.SeriesRecord series = payload.findByQuery(kpi.source(), kpi.query(), kpi.gate());
        if (series == null) {
            return new ArrayList<>();
        }
        return rankedIn(series, kpi.reducer(), kpi.across(), judgedWindow(config, payload.getWindow()),
                stepOf(payload, kpi.source()));
    }

    /**
     * As above for a reducer and an across that need not belong to a declared KPI, under
     * {@link #decidingInstances(ServerMetricsConfig, ServerMetricsPayload, ServerMetricsPayload.SeriesRecord,
     * Reducer, Across)}'s guards: where that has no deciding instance, this ranks nothing.
     */
    public static List<Integer> rankedInstances(ServerMetricsConfig config, ServerMetricsPayload payload,
                                                ServerMetricsPayload.SeriesRecord series, Reducer reducer,
                                                Across across) {
        if (payload == null || series == null || reducer == null || !reducer.toleranceHasLevel()) {
            return new ArrayList<>();
        }
        long step = stepOf(payload, series.getSource());
        if (!spansWholeSteps(reducer, step)) {
            return new ArrayList<>();
        }
        long[] window = judgedWindow(config == null || config.excludeRampUp(), payload.getWindow());
        return rankedIn(series, reducer, across, window, step);
    }

    private static List<Integer> rankedIn(ServerMetricsPayload.SeriesRecord series, Reducer reducer, Across across,
                                          long[] window, long step) {
        List<Integer> ranked = new ArrayList<>();
        Map<Integer, Double> values = new LinkedHashMap<>();
        List<ServerMetricsPayload.InstanceRecord> instances = series.getInstances();
        for (int i = 0; i < instances.size(); i++) {
            List<ServerMetricsPayload.Point> points = inWindow(instances.get(i).getPoints(), window);
            if (points.isEmpty()) {
                continue;
            }
            double value = reduceOne(reducer, points, step);
            if (!Double.isNaN(value)) {
                ranked.add(i);
                values.put(i, value);
            }
        }
        Across direction = across == null ? Across.defaultValue() : across;
        // A stable sort over ascending indices: ties stay in index order.
        if (direction == Across.MIN) {
            ranked.sort((a, b) -> Double.compare(values.get(a), values.get(b)));
        } else {
            ranked.sort((a, b) -> Double.compare(values.get(b), values.get(a)));
        }
        return ranked;
    }

    /** The reduced value {@link Across} the instances, or {@code null} when nothing could be reduced. */
    static Double reduce(ServerKpi kpi, ServerMetricsPayload.SeriesRecord series, long[] window, long step) {
        return reduce(kpi.reducer(), kpi.across(), series, window, step);
    }

    /**
     * As above, for a reducer and an across that need not belong to a declared KPI — the one
     * reduction {@link #evaluate} and {@link #judgedActual} share.
     */
    private static Double reduce(Reducer reducer, Across across, ServerMetricsPayload.SeriesRecord series,
                                 long[] window, long step) {
        List<Double> perInstance = new ArrayList<>();
        for (ServerMetricsPayload.InstanceRecord inst : series.getInstances()) {
            List<ServerMetricsPayload.Point> points = inWindow(inst.getPoints(), window);
            if (points.isEmpty()) {
                continue;
            }
            double value = reduceOne(reducer, points, step);
            if (!Double.isNaN(value)) {
                perInstance.add(value);
            }
        }
        if (perInstance.isEmpty()) {
            return null;
        }
        if (across == Across.AVG) {
            double sum = 0;
            for (double v : perInstance) {
                sum += v;
            }
            return sum / perInstance.size();
        }
        boolean low = across == Across.MIN;
        double worst = low ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (double v : perInstance) {
            worst = low ? Math.min(worst, v) : Math.max(worst, v);
        }
        return worst;
    }

    private static List<ServerMetricsPayload.Point> inWindow(List<ServerMetricsPayload.Point> points, long[] window) {
        List<ServerMetricsPayload.Point> in = new ArrayList<>(points.size());
        for (ServerMetricsPayload.Point p : points) {
            if (p.getTs() >= window[0] && p.getTs() <= window[1]) {
                in.add(p);
            }
        }
        in.sort((a, b) -> Long.compare(a.getTs(), b.getTs()));
        return in;
    }

    /** One instance's points, already inside the window and ascending, to one number — or NaN. */
    static double reduceOne(Reducer reducer, List<ServerMetricsPayload.Point> points, long step) {
        Reducer.Over over = reducer.over();
        if (over != null) {
            boolean high = reducer.kind() == Reducer.Kind.MAX;
            if (over.isSustainedFor()) {
                return sustained(points, over.sustainedForMillis(), step, high);
            }
            double quantile = high ? (1 - over.breachBudget()) * 100 : over.breachBudget() * 100;
            return nearestRank(values(points), quantile);
        }
        switch (reducer.kind()) {
            case MAX:
                return extreme(points, true);
            case MIN:
                return extreme(points, false);
            case AVG: {
                double sum = 0;
                for (ServerMetricsPayload.Point p : points) {
                    sum += p.getValue();
                }
                return sum / points.size();
            }
            case LAST:
                return points.get(points.size() - 1).getValue();
            case PERCENTILE:
                return nearestRank(values(points), reducer.percentile());
            case SLOPE:
                return slopePerMinute(points);
        }
        throw new IllegalStateException("no reduction for " + reducer.kind() + " - add one");
    }

    private static double extreme(List<ServerMetricsPayload.Point> points, boolean high) {
        double best = high ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (ServerMetricsPayload.Point p : points) {
            best = high ? Math.max(best, p.getValue()) : Math.min(best, p.getValue());
        }
        return best;
    }

    private static List<Double> values(List<ServerMetricsPayload.Point> points) {
        List<Double> values = new ArrayList<>(points.size());
        for (ServerMetricsPayload.Point p : points) {
            values.add(p.getValue());
        }
        return values;
    }

    /** Nearest-rank percentile: the value at rank {@code ceil(p/100 × n)}, clamped to {@code [1, n]}. */
    static double nearestRank(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        rank = Math.max(1, Math.min(sorted.size(), rank));
        return sorted.get(rank - 1);
    }

    /**
     * The highest level held for at least {@code d} (or the lowest, for {@code min}): for every
     * point as a window start, the min (max) of the values within {@code [t, t + d]}, counting
     * only windows whose data actually spans {@code d} — the last point in the window is at
     * {@code t + d} or later. NaN when no window is complete.
     *
     * <p>The span test and the value loop must agree on the interval or the last window of a
     * series is judged on fewer points than the rest: allowing the span a free {@code step}
     * accepted a two-sample spike at the tail as "held for 30s", while the identical spike
     * mid-series reduced to the surrounding level.
     */
    static double sustained(List<ServerMetricsPayload.Point> points, long d, long step, boolean high) {
        if (d <= step) {
            return extreme(points, high);
        }
        double best = Double.NaN;
        for (int i = 0; i < points.size(); i++) {
            long start = points.get(i).getTs();
            long end = start + d;
            double level = points.get(i).getValue();
            long last = start;
            for (int j = i; j < points.size() && points.get(j).getTs() <= end; j++) {
                level = high ? Math.min(level, points.get(j).getValue()) : Math.max(level, points.get(j).getValue());
                last = points.get(j).getTs();
            }
            if (last - start < d) {
                continue;   // the window runs past the data: not held for d at this resolution
            }
            if (Double.isNaN(best)) {
                best = level;
            } else {
                best = high ? Math.max(best, level) : Math.min(best, level);
            }
        }
        return best;
    }

    /** Least-squares slope of value against time, in units per minute. NaN below two distinct timestamps. */
    static double slopePerMinute(List<ServerMetricsPayload.Point> points) {
        if (points.size() < 2) {
            return Double.NaN;
        }
        long t0 = points.get(0).getTs();
        double n = points.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (ServerMetricsPayload.Point p : points) {
            double x = (p.getTs() - t0) / 60_000.0;
            double y = p.getValue();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denominator = n * sumXX - sumX * sumX;
        if (denominator == 0) {
            return Double.NaN;
        }
        return (n * sumXY - sumX * sumY) / denominator;
    }

    // ---- the collection step's detail ---------------------------------------------------

    /**
     * {@code "; no point between <from> and <to> (<n> s)"}: the longest stretch of the collected
     * window with no point from any instance ({@link Coverage#longestGap}), in ISO-8601 UTC to the
     * second, or "" when there is none. The page's {@code gapText} writes the same words.
     */
    static String gapText(ServerMetricsPayload.SeriesRecord series, ServerMetricsPayload payload) {
        long[] gap = Coverage.longestGap(series.getInstances(), payload.getWindow(),
                stepOf(payload, series.getSource()));
        if (gap == null) {
            return "";
        }
        return "; no point between " + secondsUtc(gap[0]) + " and " + secondsUtc(gap[1])
                + " (" + Math.round((gap[1] - gap[0]) / 1000.0) + " s)";
    }

    private static String secondsUtc(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    private static String collectionDetail(CollectionStatus status, ServerMetricsPayload.SeriesRecord series,
                                           ServerMetricsConfig config, ServerMetricsPayload payload) {
        switch (status) {
            case FAILED: {
                if (series.getError() != null) {
                    return series.getError();
                }
                ServerMetricsPayload.SourceRecord source = payload.source(series.getSource());
                // Not "the source never answered" (SF23): a series fails on its own - one bad CSV
                // row, one query a source refused - while the source answers every other series
                // and reads PARTIAL. Without an error on either, all that is known is the status.
                return source != null && source.getError() != null ? source.getError()
                        : "the payload marks it FAILED and records no reason";
            }
            case EMPTY: {
                // A CSV is searched by component and metric, never by a query: "the query matched no
                // series" sent its reader looking for PromQL the file never held. The page's
                // collectionDetail says the same words.
                ServerMetricsPayload.SourceRecord source = payload.source(series.getSource());
                if (source != null && "csv".equals(source.getType()) && series.getComponent() != null) {
                    return "no row of the CSV names component " + series.getComponent() + ", metric "
                            + (series.getMetric() != null ? series.getMetric() : series.getResolvedQuery());
                }
                return "the query matched no series";
            }
            case INSUFFICIENT: {
                // The floor was applied at COLLECT time (Collector.statusFor), against whatever
                // the annotations file declared then. This file may declare a different one now,
                // and "coverage 40% is below the floor 30%" is arithmetic that cannot be true - so
                // name the current floor only while it still explains the status the payload carries.
                // "of the collected window" (D28): coverage is measured over the collected window
                // while the verdicts are taken over the judged one, and the two can disagree.
                Double got = series.getCoverage();
                String gap = gapText(series, payload);
                if (got != null && !Double.isNaN(got) && got >= config.coverageFloor()) {
                    return "coverage " + percent(got) + " of the collected window was below the floor "
                            + "in force when this series was collected; the file now asks for "
                            + percent(config.coverageFloor())
                            + " - re-run wir-collect to judge it against the current floor" + gap;
                }
                return "coverage " + percent(got) + " of the collected window is below the floor "
                        + percent(config.coverageFloor()) + gap;
            }
            case NOT_REQUESTED:
                return "no series was collected for this server assert - re-run wir-collect";
            case OK:
            case PARTIAL:
                return null;
        }
        return null;
    }

    private static String percent(Double fraction) {
        return fraction == null || Double.isNaN(fraction) ? "?" : Math.round(fraction * 100) + "%";
    }

    /**
     * A client load rule's measured value, as the LOAD_NOT_ACHIEVED sentence quotes it beside the
     * band it breached: the number and the spelling of {@code JunitXmlWriter.formatValue}, which
     * prints the same actual in the same artifact set. {@code String.format("%.2f")} alone quoted a
     * FAIL at 12.496 req/s under {@code lt 12.5} as "throughput 12.50/s breached &lt; 12.5 /s", on
     * the band it had FAILed, while the CI/CD JSON's {@code assertions[]} wrote the rule's actual
     * with the digits that keep it off it (Q19).
     *
     * <ul>
     *   <li>A whole number is written as it is.</li>
     *   <li>Anything else is {@link Digits#beside} at two decimals over every band the load rule
     *       declares and a band of 0 (never zero), so the number is round2's at a decimal tie
     *       (1.005 is "1.00", where {@code %.2f} wrote "1.01") and 0.004 is not "0.00".</li>
     *   <li>Spelled {@code %.2f} when that number has at most two decimals (12.50 keeps its zero),
     *       and otherwise with every digit it has (12.496), never in E notation.</li>
     * </ul>
     *
     * <p>{@code WIR.serverKpis}' {@code javaMeasured} in report.js is the mirror, word for word: the
     * sentence is the CI/CD JSON's {@code reason} and the board's. The server parity vectors pin
     * the two together.
     */
    private static String formatValue(double value, double... bands) {
        if (Double.isNaN(value)) {
            return "-";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        if (Double.isInfinite(value)) {
            return String.format(Locale.US, "%.2f", value);
        }
        double n = Digits.beside(value, 2, Digits.withZero(bands));
        BigDecimal digits = BigDecimal.valueOf(n).stripTrailingZeros();
        return digits.scale() <= 2 ? String.format(Locale.US, "%.2f", n) : digits.toPlainString();
    }
}
