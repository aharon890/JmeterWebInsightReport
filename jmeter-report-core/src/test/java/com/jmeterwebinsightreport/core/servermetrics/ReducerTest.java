package com.jmeterwebinsightreport.core.servermetrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReducerTest {

    @Test
    void theFiveNamedKindsParseAndWriteBackTheSameKey() {
        for (String key : new String[] {"max", "min", "avg", "last", "slope"}) {
            Reducer r = Reducer.parse(key);
            assertEquals(key, r.key(), key);
            assertEquals(key, r.describe());
            assertNull(r.over());
        }
        assertEquals(Reducer.Kind.MAX, Reducer.parse(" MAX ").kind(), "case and whitespace are forgiven");
    }

    @Test
    void percentilesCarryTheirNAndSpellItBackWithoutATrailingZero() {
        assertEquals(95.0, Reducer.parse("p95").percentile());
        assertEquals("p95", Reducer.parse("p95").key(), "a whole N must not come back as p95.0");
        assertEquals("p97.5", Reducer.parse("p97.5").key());
        assertEquals("p99.9", Reducer.parse("p99.9").key());
        assertEquals(Reducer.Kind.PERCENTILE, Reducer.parse("p50").kind());
    }

    @Test
    void whatIsNotAReducerIsNull() {
        // Null, never an exception: the mapper names the KPI and the vocabulary in its finding.
        for (String bad : new String[] {null, "", "mean", "p0", "p100", "p", "px", "threshold", "range", "sum",
                // parseDouble would take these; key() would write them back differently.
                "p 95", "p+95", "p1e1", "p0x1p6", "pNaN"}) {
            assertNull(Reducer.parse(bad), "'" + bad + "' must not parse");
        }
        assertThrows(IllegalArgumentException.class, () -> Reducer.of(Reducer.Kind.PERCENTILE),
                "a percentile without its N is not a reducer");
    }

    @Test
    void onlySlopeCanBeNegativeOnANonNegativeSeries() {
        for (Reducer.Kind k : Reducer.Kind.values()) {
            assertEquals(k == Reducer.Kind.SLOPE, k.canBeNegative(), k.toString());
        }
    }

    @Test
    void aToleranceIsExactlyOneOfTwoForms() {
        Reducer.Over sustained = Reducer.Over.sustainedFor(30_000);
        assertTrue(sustained.isSustainedFor());
        assertFalse(sustained.isBreachBudget());
        assertEquals("sustained for 30s", sustained.describe());

        Reducer.Over budget = Reducer.Over.breachBudget(0.02);
        assertTrue(budget.isBreachBudget());
        assertFalse(budget.isSustainedFor());
        assertEquals("breach budget 2%", budget.describe());

        assertThrows(IllegalArgumentException.class, () -> Reducer.Over.sustainedFor(0));
        assertThrows(IllegalArgumentException.class, () -> Reducer.Over.breachBudget(1.5));
        assertThrows(IllegalArgumentException.class, () -> Reducer.Over.breachBudget(Double.NaN));

        assertEquals("max sustained for 30s", Reducer.parse("max").over(sustained).describe());
    }

    @Test
    void aBreachBudgetReadsAsThePercentageWrittenNotTheBinaryNoiseOfTimesHundred() {
        // C-13. 0.07 * 100 is 7.000000000000001, and Durations.trim printed every digit of it
        // into the CI/CD JSON's reduce sentence and the NO_VALUE detail. 0.02 and 0.05 happen to
        // multiply exactly, which is why no fixture ever showed it.
        assertEquals("breach budget 7%", Reducer.Over.breachBudget(0.07).describe());
        assertEquals("max breach budget 7%", Reducer.parse("max").over(Reducer.Over.breachBudget(0.07)).describe());
        assertEquals("33.3", Durations.percent(0.333));
        assertEquals("0.5", Durations.percent(0.005));
        assertEquals("0", Durations.percent(0));
        assertEquals("100", Durations.percent(1));
        // Plain notation, as the browser writes it: never "1.0E-4".
        assertEquals("0.0001", Durations.percent(0.000001));
        assertEquals("57", Durations.percent(0.57), "0.57 * 100 is 56.99999999999999");
    }

    @Test
    void durationsParseTheFileGrammarAndRefuseABareNumber() {
        assertEquals(30_000L, Durations.parseMillis("30s"));
        assertEquals(120_000L, Durations.parseMillis("2m"));
        assertEquals(500L, Durations.parseMillis("500ms"));
        assertEquals(5_400_000L, Durations.parseMillis("1.5h"));
        assertEquals(1_500L, Durations.parseMillis("1.5s"));
        assertEquals(15_000L, Durations.parseMillis(" 15s "), "surrounding whitespace is forgiven");
        // The accepted grammar is exactly the written one: format() emits lowercase with no
        // inner space, so anything else would diff on the first save of an unedited file.
        assertNull(Durations.parseMillis("15S"));
        assertNull(Durations.parseMillis("30 s"));
        assertNull(Durations.parseMillis("30M"));
        // A bare number is ambiguous between seconds and milliseconds — a factor of a thousand.
        assertNull(Durations.parseMillis("15"));
        assertNull(Durations.parseMillis("15 seconds"));
        assertNull(Durations.parseMillis(""));
        assertNull(Durations.parseMillis(null));
        assertNull(Durations.parseMillis("-5s"));
    }

    @Test
    void durationsFormatTheShortestExactSpelling() {
        assertEquals("30s", Durations.format(30_000));
        assertEquals("2m", Durations.format(120_000));
        assertEquals("90s", Durations.format(90_000), "whole seconds beat a fractional minute");
        assertEquals("1h", Durations.format(3_600_000));
        assertEquals("250ms", Durations.format(250));
        assertEquals("1.5s", Durations.format(1_500));
        // Round trip for every spelling the parser accepts.
        for (String s : new String[] {"30s", "2m", "500ms", "1h", "1.5m", "90s"}) {
            long millis = Durations.parseMillis(s);
            assertEquals(millis, (long) Durations.parseMillis(Durations.format(millis)), s);
        }
    }
}
