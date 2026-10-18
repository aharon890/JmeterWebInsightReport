package com.jmeterwebinsightreport.core.assertion;

/**
 * What one {@link Judged} check decided — the part a gate's arithmetic reads.
 *
 * <p>{@link GateSummary#of} folds a list of these: it needs the status (to count and to take
 * the worst gating one) and the reason (to count under {@code gate.notJudged}). It does not
 * need the matches, the actual value or the band, which differ between a client rule and a
 * server KPI and belong to the concrete result types.
 */
public interface JudgedResult {

    /** The check this result is for. */
    Judged judged();

    /** PASS, WARN, FAIL, or NOT_JUDGED. */
    AssertionStatus status();

    /** Why nothing was decided, or {@code null} when something was. */
    NotJudged notJudged();

    /** The human sentence behind {@link #notJudged()}, or {@code null}. */
    String notJudgedDetail();
}
