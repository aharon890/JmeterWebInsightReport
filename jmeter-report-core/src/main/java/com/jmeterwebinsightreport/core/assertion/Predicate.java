package com.jmeterwebinsightreport.core.assertion;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * One band: the comparison that decides whether a measured value has breached.
 *
 * <p>The tester writes the direction. {@code fail: {gt: 800}} for latency,
 * {@code fail: {lt: 30}} for throughput — so the engine needs no per-metric table of which
 * way is bad, and latency, throughput and apdex share one rule shape. A direction table
 * would have to be extended for every new metric and would be wrong for at least one of
 * them (is a high {@code sampleCount} good?).
 *
 * <p>There is no corridor operator. {@code outside: [lo, hi]} was one, and it is the only
 * band shape with TWO thresholds — so it has no single number to show "actual vs expected"
 * against, which is the column this whole feature is being rebuilt around. A corridor is two
 * asserts written as one; write them as two, and each says which end was missed.
 */
public final class Predicate {

    public enum Op {
        GT("gt", ">"),
        GTE("gte", "≥"),
        LT("lt", "<"),
        LTE("lte", "≤");

        private final String key;
        private final String symbol;

        Op(String key, String symbol) {
            this.key = key;
            this.symbol = symbol;
        }

        /** The JSON key this operator is written as. */
        public String key() {
            return key;
        }

        /** How it is rendered to a human. */
        public String symbol() {
            return symbol;
        }

        public static Op fromKey(String key) {
            if (key == null) return null;
            String k = key.trim().toLowerCase(Locale.ROOT);
            for (Op op : values()) {
                if (op.key.equals(k)) return op;
            }
            return null;
        }
    }

    /** 2^63: where {@link #trim} stops writing an integral band's exact value (L12). */
    private static final double TWO_TO_THE_63 = 0x1p63;

    private final Op op;
    private final double value;

    private Predicate(Op op, double value) {
        this.op = op;
        this.value = value;
    }

    public static Predicate of(Op op, double value) {
        return new Predicate(op, value);
    }

    public Op op() {
        return op;
    }

    /** The one threshold this band is measured against. */
    public double value() {
        return value;
    }

    /**
     * True when {@code actual} has breached this band.
     *
     * <p>Strict, deliberately: a value sitting exactly on its threshold passes. The boundary is
     * the only place this is observable, so the parity vectors put values there on purpose.
     */
    public boolean breaches(double actual) {
        if (Double.isNaN(actual)) {
            return false;
        }
        switch (op) {
            case GT:      return actual > value;
            case GTE:     return actual >= value;
            case LT:      return actual < value;
            case LTE:     return actual <= value;
            default:      return false;
        }
    }

    /** e.g. {@code "> 800 ms"}. */
    public String describe(String unit) {
        String suffix = unit == null || unit.isEmpty() ? "" : " " + unit;
        return op.symbol() + " " + trim(value) + suffix;
    }

    /**
     * The threshold a breach was measured against, for the failure text.
     *
     * <p>Kept as a method rather than folded into {@link #value()} at every call site: it names
     * the question ("what was this compared to?") and the two were only ever different for the
     * corridor operator, which is gone.
     */
    public double thresholdFor(double actual) {
        return value;
    }

    /**
     * A band's threshold as every artifact writes it: in plain digits (L12). {@code WIR.javaText.trim}
     * in report.js is the mirror, held to this by the parity vectors' {@code detail}/{@code warnings}.
     *
     * <ul>
     *   <li>An integral value below 2^63 in magnitude is every digit of its exact value — 800, not
     *       800.0, and 2^62 as 4611686018427387904, not its shortest digits padded with zeros.</li>
     *   <li>Anything else is {@code Double.toString}'s shortest digits without its E notation: 0.0005,
     *       not 5.0E-4, and 12345678.5, not 1.23456785E7. From 1e-3 up to 1e7 that is
     *       {@code Double.toString} itself, as it always was. An integral value at or past 2^63 is
     *       one of these: 1e19 is 10000000000000000000, where the long cast this used to make
     *       saturated it to 9223372036854775807 and two different bands read as one, and 1e23 is
     *       100000000000000000000000 — the digits the file wrote and the tables' {@code formatBand}
     *       shows — not the nearest double's exact 99999999999999991611392.</li>
     *   <li>NaN and the infinities are their own words, as {@code Double.toString} writes them.</li>
     * </ul>
     *
     * <p>{@code KpiUsability} writes a catalogue ceiling beside a band through this too. A duration and
     * a percentile key are {@code Durations.trim}'s, which keeps its notation.
     */
    public static String trim(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return String.valueOf(d);
        }
        if (d == Math.rint(d) && Math.abs(d) < TWO_TO_THE_63) {
            // The exact value of an integral double has no fraction, so this is its digits; -0.0 is 0.
            return new BigDecimal(d).toPlainString();
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    @Override
    public String toString() {
        return describe("");
    }
}
