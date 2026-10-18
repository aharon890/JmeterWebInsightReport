package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KpiResultTest {

    private static final ServerKpi KPI = ServerKpi.builder("prom", "q").id("lag").unit("msgs")
            .reducer(Reducer.parse("max")).gate("server")
            .fail(Predicate.of(Predicate.Op.GT, 800)).build();

    @Test
    void aJudgedResultNeedsAGatingStatus() {
        KpiResult r = KpiResult.of(KPI, AssertionStatus.FAIL, 900, KPI.fail(), 0.97, 6);
        assertSame(KPI, r.kpi());
        assertSame(KPI, r.judged(), "the seam sees the same object the typed view does");
        assertEquals(AssertionStatus.FAIL, r.status());
        assertEquals(900.0, r.actual());
        assertSame(KPI.fail(), r.breachedBand());
        assertEquals(0.97, r.coverage());
        assertEquals(6, r.instances());
        assertNull(r.notJudged());
        assertNull(r.notJudgedDetail());

        assertThrows(IllegalArgumentException.class,
                () -> KpiResult.of(KPI, AssertionStatus.NOT_JUDGED, 1, null, 1, 1),
                "NOT_JUDGED is not a judgement; use notJudged() and say why");
        assertThrows(IllegalArgumentException.class, () -> KpiResult.of(KPI, null, 1, null, 1, 1));
    }

    @Test
    void aNotJudgedResultNeedsItsReasonAndCarriesNoNumber() {
        KpiResult r = KpiResult.notJudged(KPI, NotJudged.SOURCE_FAILED, "prometheus returned 401");
        assertEquals(AssertionStatus.NOT_JUDGED, r.status());
        assertEquals(NotJudged.SOURCE_FAILED, r.notJudged());
        assertEquals("prometheus returned 401", r.notJudgedDetail());
        assertTrue(Double.isNaN(r.actual()), "nothing was measured");
        assertTrue(Double.isNaN(r.coverage()), "nothing was collected");
        assertNull(r.breachedBand());

        KpiResult lowCoverage = KpiResult.notJudged(KPI, NotJudged.LOW_COVERAGE, null, 0.62, 3);
        assertEquals(0.62, lowCoverage.coverage(), "LOW_COVERAGE carries its figure");
        assertEquals(3, lowCoverage.instances());
        assertNull(lowCoverage.notJudgedDetail(), "the phrase suffices");

        assertThrows(IllegalArgumentException.class, () -> KpiResult.notJudged(KPI, null));
    }

    @Test
    void aGateOfKpiResultsCountsExactlyAsAGateOfRuleResultsWould() {
        // The whole point of the seam: GateSummary.of reads status and reason only, so a server
        // gate is the same arithmetic as the client gate — and the config-error decision made
        // once in NotJudged applies to both.
        GateSummary gate = GateSummary.of(Arrays.asList(
                KpiResult.of(KPI, AssertionStatus.PASS, 100, null, 1, 1),
                KpiResult.of(KPI, AssertionStatus.FAIL, 900, KPI.fail(), 1, 1),
                KpiResult.notJudged(KPI, NotJudged.SOURCE_FAILED, "401"),
                KpiResult.notJudged(KPI, NotJudged.LOAD_NOT_ACHIEVED),
                KpiResult.notJudged(KPI, NotJudged.DISABLED)));

        assertEquals(AssertionStatus.FAIL, gate.status());
        assertEquals(5, gate.declared());
        assertEquals(4, gate.evaluated(), "only the parked one is excluded");
        assertEquals(1, gate.passed());
        assertEquals(1, gate.failed());
        assertEquals(3, gate.notJudged());
        assertEquals(1, gate.notJudged(NotJudged.SOURCE_FAILED));
        assertEquals(1, gate.configErrors(), "SOURCE_FAILED is a config error; LOAD_NOT_ACHIEVED is not");
        assertEquals(5, gate.results().size());
        assertEquals(5, gate.results(KpiResult.class).size());
        assertTrue(gate.ruleResults().isEmpty(), "a server gate renders nothing through a client renderer");
    }
}
