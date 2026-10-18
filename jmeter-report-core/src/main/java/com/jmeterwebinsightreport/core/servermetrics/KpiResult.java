package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.Judged;
import com.jmeterwebinsightreport.core.assertion.JudgedResult;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.Predicate;

/**
 * What one KPI decided, and against what.
 *
 * <p>The server-side {@code RuleResult}. There are no matches: a KPI judges one number, the
 * reduced series folded {@link Across} its instances. The per-instance values are display data
 * (charted, "and N more") and travel in the payload beside the series, never here — which is
 * what keeps pod names out of every per-sampler output.
 *
 * <p>The factories are <em>public</em>, unlike {@code RuleResult}'s, so that M4's report-module
 * tests can build a {@code SOURCE_FAILED} result without an evaluator and prove the JUnit
 * writer renders it as a {@code <failure>} (the writer does not read {@code KpiResult}s yet —
 * M4). The client kept its factories package-private because the engine was the only producer;
 * here M3's collection status will be a second one.
 */
public final class KpiResult implements JudgedResult {

    private final ServerKpi kpi;
    private final AssertionStatus status;
    private final double actual;
    private final Predicate breached;
    private final NotJudged notJudged;
    private final String notJudgedDetail;
    private final double coverage;
    private final int instances;

    private KpiResult(ServerKpi kpi, AssertionStatus status, double actual, Predicate breached,
                      NotJudged notJudged, String notJudgedDetail, double coverage, int instances) {
        this.kpi = kpi;
        this.status = status;
        this.actual = actual;
        this.breached = breached;
        this.notJudged = notJudged;
        this.notJudgedDetail = notJudgedDetail;
        this.coverage = coverage;
        this.instances = instances;
    }

    /** A KPI that decided something: PASS, WARN or FAIL on {@code actual}. */
    public static KpiResult of(ServerKpi kpi, AssertionStatus status, double actual,
                               Predicate breached, double coverage, int instances) {
        if (status == null || !status.gates()) {
            throw new IllegalArgumentException("a judged result needs a gating status, not " + status);
        }
        return new KpiResult(kpi, status, actual, breached, null, null, coverage, instances);
    }

    /** A KPI that decided nothing, and why. {@code detail} may be null where the phrase suffices. */
    public static KpiResult notJudged(ServerKpi kpi, NotJudged reason, String detail) {
        if (reason == null) {
            throw new IllegalArgumentException("a not-judged result needs its reason");
        }
        return new KpiResult(kpi, AssertionStatus.NOT_JUDGED, Double.NaN, null, reason, detail,
                Double.NaN, 0);
    }

    public static KpiResult notJudged(ServerKpi kpi, NotJudged reason) {
        return notJudged(kpi, reason, null);
    }

    /** A not-judged result that still knows how much data arrived — LOW_COVERAGE carries its figure. */
    public static KpiResult notJudged(ServerKpi kpi, NotJudged reason, String detail, double coverage,
                                      int instances) {
        if (reason == null) {
            throw new IllegalArgumentException("a not-judged result needs its reason");
        }
        return new KpiResult(kpi, AssertionStatus.NOT_JUDGED, Double.NaN, null, reason, detail,
                coverage, instances);
    }

    public ServerKpi kpi() { return kpi; }

    @Override public Judged judged() { return kpi; }
    @Override public AssertionStatus status() { return status; }
    @Override public NotJudged notJudged() { return notJudged; }
    @Override public String notJudgedDetail() { return notJudgedDetail; }

    /** The reduced value the bands saw, or {@link Double#NaN} when nothing was judged. */
    public double actual() { return actual; }

    /** The band that fired, or {@code null} when the KPI passed or was not judged. */
    public Predicate breachedBand() { return breached; }

    /** Fraction of the window that carried data, or NaN when nothing was collected. */
    public double coverage() { return coverage; }

    /** How many instances the series had before {@link Across} folded them. */
    public int instances() { return instances; }

    @Override
    public String toString() {
        return kpi.id() + " " + status + (notJudged != null ? " / " + notJudged : " = " + actual);
    }
}
