package com.jmeterwebinsightreport.core.rundata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A k6 threshold expression read as k6 reads it (spec 002, FR-002, task T002): k6's own grammar,
 * {@code metrics/thresholds_parser.go}, read 2026-11-25. The operators are tried in k6's order ({@code <=}, {@code <},
 * {@code >=}, {@code >}, {@code ===}, {@code ==}, {@code !=}), each side trimmed of spaces at its ends only, and
 * {@code p(N)} holds a number from 0 to 100. An expression k6 would refuse gives nothing: never a guessed statistic.
 */
class ThresholdExpressionTest {

    private static void reads(String expression, String statistic, String operator, double limit) {
        ThresholdExpression e = ThresholdExpression.parse(expression);
        assertTrue(e.readable(), expression);
        assertEquals(statistic, e.statistic(), expression + ": statistic");
        assertEquals(operator, e.operator(), expression + ": operator");
        assertEquals(limit, e.limit(), 0.0, expression + ": limit");
    }

    private static void refused(String expression) {
        ThresholdExpression e = ThresholdExpression.parse(expression);
        assertFalse(e.readable(), expression);
        assertNull(e.statistic(), expression);
        assertNull(e.operator(), expression);
        assertNull(e.limit(), expression);
    }

    @Test
    void everyOperatorK6Accepts() {
        reads("p(95)<=600", "p(95)", "<=", 600);
        reads("p(95)<600", "p(95)", "<", 600);
        reads("count>=0", "count", ">=", 0);
        reads("rate>0.5", "rate", ">", 0.5);
        reads("count===3", "count", "===", 3);
        reads("count==0", "count", "==", 0);
        reads("value!=7", "value", "!=", 7);
    }

    @Test
    void spacesAroundEachSideAreK6s() {
        reads("p(95) < 600", "p(95)", "<", 600);
        reads("  rate <= 0.05 ", "rate", "<=", 0.05);
        refused("p( 95 )<600"); // k6 trims only the ends of the method, so " 95 " is not a number to it
    }

    @Test
    void everyAggregationMethod() {
        for (String m : new String[] {"value", "count", "rate", "avg", "min", "med", "max"}) {
            reads(m + "<1", m, "<", 1);
        }
        reads("p(99.9)<1500", "p(99.9)", "<", 1500);
        reads("p(0)>=0", "p(0)", ">=", 0);
        reads("p(100)<9", "p(100)", "<", 9);
    }

    @Test
    void whatK6WouldRefuseGivesNothing() {
        refused("");
        refused(null);
        refused("p(95)");
        refused("<600");
        refused("p(95)<");
        refused("p(101)<600");
        refused("p(-1)<600");
        refused("p(x)<600");
        refused("mean<600");
        refused("p(95)<six hundred");
    }

    @Test
    void anAlwaysTrueExpressionIsAStatisticAtOrAboveZero() {
        // decision 2: petstore adds max>=0, count>=0, rate>=0 and value>=0 only to keep a request's numbers
        assertTrue(ThresholdExpression.parse("max>=0").alwaysTrue());
        assertTrue(ThresholdExpression.parse("count >= 0").alwaysTrue());
        assertFalse(ThresholdExpression.parse("count>=1").alwaysTrue());
        assertFalse(ThresholdExpression.parse("count>0").alwaysTrue(), "a run with no request fails it");
        assertFalse(ThresholdExpression.parse("p(95)<=600").alwaysTrue());
        assertFalse(ThresholdExpression.parse("p( 95 )>=0").alwaysTrue(), "not readable");
    }
}
