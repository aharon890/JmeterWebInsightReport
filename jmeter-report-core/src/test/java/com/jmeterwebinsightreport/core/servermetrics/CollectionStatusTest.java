package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.NotJudged;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bridge between the collection panel and the KPI board. The mapping is what keeps the two
 * consistent rather than merely adjacent, so it is pinned value by value.
 */
class CollectionStatusTest {

    @Test
    void everyStatusAnswersWithoutThrowing() {
        // The switch has no default arm; this is the runtime half of that guarantee.
        for (CollectionStatus s : CollectionStatus.values()) {
            s.toReason();
            s.judgeable();
            assertEquals(s.name(), s.wireName());
        }
    }

    @Test
    void theMappingIsTheOneTheDesignWroteDown() {
        assertNull(CollectionStatus.OK.toReason());
        assertNull(CollectionStatus.PARTIAL.toReason(), "above the floor is judged, with the figure shown");
        assertEquals(NotJudged.LOW_COVERAGE, CollectionStatus.INSUFFICIENT.toReason());
        assertEquals(NotJudged.NO_MATCH, CollectionStatus.EMPTY.toReason());
        assertEquals(NotJudged.SOURCE_FAILED, CollectionStatus.FAILED.toReason());
        assertEquals(NotJudged.INVALID_RULE, CollectionStatus.NOT_REQUESTED.toReason(),
                "a KPI over a series nothing collected is a file/collector disagreement");
    }

    @Test
    void onlyOkAndPartialAreJudgeable() {
        EnumSet<CollectionStatus> judgeable = EnumSet.noneOf(CollectionStatus.class);
        for (CollectionStatus s : CollectionStatus.values()) {
            if (s.judgeable()) {
                judgeable.add(s);
            }
        }
        assertEquals(EnumSet.of(CollectionStatus.OK, CollectionStatus.PARTIAL), judgeable);
    }

    @Test
    void everyReasonItProducesIsOneAKpiCanCarry() {
        // SOURCE_FAILED and LOW_COVERAGE are server-only; NO_MATCH and INVALID_RULE are shared.
        // Nothing here may produce LOAD_NOT_ACHIEVED — that comes from the load rule, not the
        // series — nor DISABLED or NO_VALUE.
        for (CollectionStatus s : CollectionStatus.values()) {
            NotJudged r = s.toReason();
            if (r == null) continue;
            assertFalse(r == NotJudged.LOAD_NOT_ACHIEVED || r == NotJudged.DISABLED
                    || r == NotJudged.NO_VALUE, s + " maps to " + r + ", which a series cannot explain");
            assertTrue(r == NotJudged.SOURCE_FAILED || r == NotJudged.LOW_COVERAGE
                    || r == NotJudged.NO_MATCH || r == NotJudged.INVALID_RULE);
        }
    }
}
