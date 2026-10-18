package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.jmeterwebinsightreport.core.servermetrics.catalogue.ToolName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code wir-collect --csv FILE --from TOOL}: how one tool's number becomes the catalogue's, per
 * {@link ToolName.Kind} and scale. Every expected number is exact - the rows are chosen so that no
 * division rounds - and compared with {@code ==}, never a tolerance.
 */
class ConversionTest {

    private static final long STEP = 15_000;

    /** timestamp, value, timestamp, value...; a null value is a gap, as CsvSource indexes one. */
    private static TreeMap<Long, Double> rows(Object... pairs) {
        TreeMap<Long, Double> out = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(((Number) pairs[i]).longValue(), pairs[i + 1] == null ? null : ((Number) pairs[i + 1]).doubleValue());
        }
        return out;
    }

    private static Map<Long, Double> convert(TreeMap<Long, Double> rows, ToolName.Kind kind, double scale) {
        return Conversion.toCatalogueUnit(rows, kind, scale, STEP);
    }

    @Test
    void aGaugeIsItsValueTimesTheScale() {
        // A 0-1 fraction the catalogue reads as %: scale 100.
        assertEquals(rows(0, 50, 15_000, 25), convert(rows(0, 0.5, 15_000, 0.25), ToolName.Kind.GAUGE, 100));
        assertEquals(rows(0, 0.5, 15_000, 0.25), convert(rows(0, 0.5, 15_000, 0.25), ToolName.Kind.GAUGE, 1));
        // A gap is no point, never a zero.
        assertEquals(rows(0, 50, 30_000, 75), convert(rows(0, 0.5, 15_000, null, 30_000, 0.75), ToolName.Kind.GAUGE, 100));
    }

    @Test
    void aRateIsAlreadyPerSecondAndIsOnlyScaled() {
        // A per-minute catalogue metric declares scale 60 on a rate.
        assertEquals(rows(0, 120, 15_000, 180), convert(rows(0, 2, 15_000, 3), ToolName.Kind.RATE, 60));
        assertEquals(rows(0, 2, 15_000, 3), convert(rows(0, 2, 15_000, 3), ToolName.Kind.RATE, 1));
    }

    @Test
    void aCounterIsARatePerSecondBetweenConsecutiveValuesStampedAtTheLaterOne() {
        // 100 -> 130 -> 160 over 15 s each: 2 per second, twice. The first value has nothing
        // before it and gives no point.
        assertEquals(rows(15_000, 2, 30_000, 2), convert(rows(0, 100, 15_000, 130, 30_000, 160), ToolName.Kind.COUNTER, 1));
        // A single value is no rate at all.
        assertTrue(convert(rows(0, 100), ToolName.Kind.COUNTER, 1).isEmpty());
    }

    @Test
    void aCounterThatGoesDownWasResetAndCountsFromZero() {
        // 130 -> 15: a restart. Not -115 / 15 s: the 15 counted since the reset, over 15 s.
        assertEquals(rows(15_000, 2, 30_000, 1), convert(rows(0, 100, 15_000, 130, 30_000, 15), ToolName.Kind.COUNTER, 1));
        // Equal is not a reset: nothing happened, a rate of 0.
        assertEquals(rows(15_000, 0), convert(rows(0, 100, 15_000, 100), ToolName.Kind.COUNTER, 1));
    }

    @Test
    void aCounterSkipsAGapAndDiffersFromTheLastValueBeforeIt() {
        // 100, a gap, 160: 60 over the 30 s since the last value, stamped at 30 s. The gap's own
        // timestamp gives no point.
        assertEquals(rows(30_000, 2), convert(rows(0, 100, 15_000, null, 30_000, 160), ToolName.Kind.COUNTER, 1));
        // A leading gap is skipped too: the first VALUE is the one with nothing before it.
        assertEquals(rows(30_000, 2), convert(rows(0, null, 15_000, 100, 30_000, 130), ToolName.Kind.COUNTER, 1));
    }

    @Test
    void aCounterReadPerMinuteDeclaresScale60() {
        // 5 over 10 s is 0.5 per second, 30 per minute.
        assertEquals(rows(10_000, 30), convert(rows(0, 0, 10_000, 5), ToolName.Kind.COUNTER, 60));
    }

    @Test
    void aSumPerPeriodIsDividedByTheInstancesMostCommonSpacing() {
        // CloudWatch at 5 minutes, one period missing from the export: spacings 300, 300, 600 s,
        // so the period is 300 s. 600 per period is 2 per second.
        assertEquals(rows(0, 2, 300_000, 1, 600_000, 4, 1_200_000, 0.5),
                convert(rows(0, 600, 300_000, 300, 600_000, 1200, 1_200_000, 150), ToolName.Kind.SUM_PER_PERIOD, 1));
        // ...and per minute with scale 60.
        assertEquals(rows(0, 120, 300_000, 60),
                convert(rows(0, 600, 300_000, 300), ToolName.Kind.SUM_PER_PERIOD, 60));
    }

    @Test
    void aSumPerPeriodTieIsTheSmallerSpacing() {
        // Spacings 60 s and 120 s, once each: 60 s. 120 per 60 s is 2 per second.
        assertEquals(rows(0, 2, 60_000, 1, 180_000, 4),
                convert(rows(0, 120, 60_000, 60, 180_000, 240), ToolName.Kind.SUM_PER_PERIOD, 1));
        assertEquals(60_000L, Conversion.period(rows(0, 1, 60_000, 1, 180_000, 1), STEP));
    }

    @Test
    void aSumPerPeriodCountsAGapsRowAsOneOfTheExportsPeriods() {
        // An empty period is still a period of the export: 0, 60, 120 s with the middle one a gap
        // is a 60 s export, not a 120 s one.
        assertEquals(rows(0, 1, 120_000, 2), convert(rows(0, 60, 60_000, null, 120_000, 120), ToolName.Kind.SUM_PER_PERIOD, 1));
    }

    @Test
    void aSumPerPeriodOfOneRowFallsBackToTheStep() {
        // One row has no spacing; the source's --step (15 s here) stands in. 30 per 15 s is 2 per second.
        assertEquals(rows(1_000, 2), convert(rows(1_000, 30), ToolName.Kind.SUM_PER_PERIOD, 1));
        assertEquals(STEP, Conversion.period(rows(1_000, 30), STEP));
    }
}
