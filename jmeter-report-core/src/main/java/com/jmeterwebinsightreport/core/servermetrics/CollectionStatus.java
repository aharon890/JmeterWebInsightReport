package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.NotJudged;

/**
 * What happened to one collected series — a property of the SERIES, per source, per window.
 *
 * <p>This is the first of the two questions the old INCONCLUSIVE conflated ("did we get the
 * data?"); the KPI's status answers the second ("what did the KPI decide?"). They belong on
 * different rows: a series is to carry one of these in M5's collection panel, a KPI carries a
 * status and, when it decided nothing, a {@link NotJudged} reason. {@link #toReason()} is the
 * bridge between them, and it is an exhaustive switch for the same reason
 * {@link NotJudged#countKey()} is — a status added later must say which reason it becomes,
 * or the two panels drift into merely adjacent rather than consistent.
 */
public enum CollectionStatus {

    /** Every point in the window arrived. */
    OK,

    /** Some gaps, but coverage is at or above the floor. Judged, with the coverage shown. */
    PARTIAL,

    /** Data arrived, but coverage is below the floor. Not judged. */
    INSUFFICIENT,

    /** The query ran and matched no series. */
    EMPTY,

    /**
     * This series could not be collected — the source refused it or never answered (auth,
     * timeout, DNS, a 5xx), or a CSV row of it could not be read. One series' fate: the source may
     * have answered every other one (it then reads PARTIAL).
     */
    FAILED,

    /** Nothing asked for this series: no KPI in the collected file named it. */
    NOT_REQUESTED;

    /**
     * The reason a KPI over this series is not judged, or {@code null} when it is judged.
     *
     * <p>{@code NOT_REQUESTED} is {@code INVALID_RULE} because a KPI can only be looking at a
     * series nothing collected if the KPI and the collection disagree about what was declared
     * — a KPI added to the file after the collection ran. That is a config error and therefore
     * always fatal under {@code failonerror}, {@code failsBuild} notwithstanding (delivery plan
     * §M2): the fix is to re-collect, and an observe-only gate silently judging a stale
     * collection is the silent-pass shape the whole vocabulary exists to refuse.
     */
    public NotJudged toReason() {
        switch (this) {
            case OK:
            case PARTIAL:
                return null;
            case INSUFFICIENT:
                return NotJudged.LOW_COVERAGE;
            case EMPTY:
                return NotJudged.NO_MATCH;
            case FAILED:
                return NotJudged.SOURCE_FAILED;
            case NOT_REQUESTED:
                return NotJudged.INVALID_RULE;
        }
        throw new IllegalStateException("no reason mapping for " + this
                + " — add one; a collection status a server assert cannot explain is the INCONCLUSIVE this enum replaced");
    }

    /** True when a KPI over this series can be judged at all. */
    public boolean judgeable() {
        return toReason() == null;
    }

    /** How the status is named in the payload and the collection panel. */
    public String wireName() {
        return name();
    }
}
