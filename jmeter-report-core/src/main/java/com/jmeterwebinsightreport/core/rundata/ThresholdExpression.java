package com.jmeterwebinsightreport.core.rundata;

import java.util.List;
import java.util.Set;

/**
 * A k6 threshold expression, {@code p(95)<=600}, read by k6's own grammar ({@code metrics/thresholds_parser.go}, read
 * 2026-11-25; spec 002, FR-002): the statistic it names, the operator and the limit.
 *
 * <ul>
 *   <li>The operators are tried in k6's order, {@code <=}, {@code <}, {@code >=}, {@code >}, {@code ===}, {@code ==},
 *       {@code !=}, each by the first place it occurs, and the first that leaves a right-hand side wins.</li>
 *   <li>Each side is trimmed of spaces at its ends only, so {@code p( 95 )} is not a statistic, as it is not to k6.</li>
 *   <li>The statistic is one of k6's aggregation methods: {@code value}, {@code count}, {@code rate}, {@code avg},
 *       {@code min}, {@code med}, {@code max}, or {@code p(N)} with N a number from 0 to 100.</li>
 * </ul>
 *
 * An expression k6 would refuse reads as nothing: no statistic, operator or limit is guessed. The statistic is written
 * as the expression wrote it, which is also the key of k6's summary values ({@code p(95)}).
 */
public final class ThresholdExpression {

    private static final List<String> OPERATORS = List.of("<=", "<", ">=", ">", "===", "==", "!=");
    private static final Set<String> METHODS = Set.of("value", "count", "rate", "avg", "min", "med", "max");
    private static final ThresholdExpression UNREADABLE = new ThresholdExpression(null, null, null);

    private final String statistic;
    private final String operator;
    private final Double limit;

    private ThresholdExpression(String statistic, String operator, Double limit) {
        this.statistic = statistic;
        this.operator = operator;
        this.limit = limit;
    }

    public static ThresholdExpression parse(String expression) {
        if (expression == null) {
            return UNREADABLE;
        }
        for (String op : OPERATORS) {
            int at = expression.indexOf(op);
            if (at < 0) {
                continue;
            }
            String right = expression.substring(at + op.length());
            if (right.isEmpty()) {
                continue;
            }
            String method = expression.substring(0, at).strip();
            Double limit = number(right.strip());
            if (!isMethod(method) || limit == null) {
                return UNREADABLE;
            }
            return new ThresholdExpression(method, op, limit);
        }
        return UNREADABLE;
    }

    private static boolean isMethod(String m) {
        if (METHODS.contains(m)) {
            return true;
        }
        if (m.startsWith("p(") && m.endsWith(")") && m.length() > 3) {
            Double n = number(m.substring(2, m.length() - 1));
            return n != null && n >= 0 && n <= 100;
        }
        return false;
    }

    /** A finite number in the form both Go's ParseFloat and Java's read alike, else null. */
    private static Double number(String s) {
        if (s.isEmpty() || !s.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")) {
            return null;
        }
        double v = Double.parseDouble(s);
        return Double.isFinite(v) ? v : null;
    }

    /** Whether k6 would read it. */
    public boolean readable() {
        return statistic != null;
    }

    /** The statistic as written ({@code p(95)}, {@code rate}), or null. */
    public String statistic() {
        return statistic;
    }

    public String operator() {
        return operator;
    }

    public Double limit() {
        return limit;
    }

    /**
     * Whether it holds for any run: a statistic at or above 0 ({@code max>=0}, {@code count>=0}, {@code rate>=0},
     * {@code value>=0}). Petstore's tests add one per request only so that k6 keeps its numbers (spec 002, decision 2).
     * The page folds such a threshold only when it passed: a custom gauge can go below 0.
     */
    public boolean alwaysTrue() {
        return readable() && ">=".equals(operator) && limit == 0.0;
    }
}
