package com.jmeterwebinsightreport.core.servermetrics;

import java.util.Locale;

/**
 * How a KPI folds a time series into the one number its bands judge — {@code reduce} in the
 * file.
 *
 * <p>This is the whole of the "nine assert types" the canvas had, collapsed. Each of those was
 * a reducer with a band bolted on: {@code threshold} was max/min/avg/last with a band,
 * {@code percentile} was pN with a band, {@code sustained} and {@code budget} were a reducer
 * with a tolerance, {@code slope} was a reducer, {@code range} was two bands, {@code ratio} was
 * a divisor. Written as reducer × band, a server KPI carries the same {@code warn}/{@code fail}
 * as a client rule, the same operators, the same editor form for the band — and one fewer
 * vocabulary for the reader to learn.
 *
 * <p>{@link Over} is the tolerance: a single 1-second CPU spike must not fail a build. With
 * {@code sustainedFor}, a breach counts only when every sample across a span of that length is
 * in breach; with {@code breachBudget}, only when the fraction of the samples in breach exceeds
 * it. Both count the samples the series carries, not wall-clock time — a missing scrape inside a
 * span is bridged, not treated as a break — which is what {@code ServerKpiEvaluator} documents
 * in full. Never both — that is two tolerances with no defined combination.
 */
public final class Reducer {

    public enum Kind {
        MAX("max"),
        MIN("min"),
        AVG("avg"),
        LAST("last"),
        /** {@code pN} — a percentile of the series' own points. Carries the N. */
        PERCENTILE("p"),
        /** Linear regression over the window, in units per minute. Can legitimately be negative. */
        SLOPE("slope");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /**
         * True when the reduced value can be below zero even for a non-negative series — so
         * a downward band at or below zero is not unreachable on it. The client's
         * unreachable-band check assumes every metric is non-negative; on a server KPI that is
         * only ever a guess, and on a slope it is simply false.
         */
        public boolean canBeNegative() {
            return this == SLOPE;
        }
    }

    /** The tolerance clause: exactly one of the two forms, or absent. */
    public static final class Over {
        private final long sustainedForMillis;
        private final double breachBudget;

        private Over(long sustainedForMillis, double breachBudget) {
            this.sustainedForMillis = sustainedForMillis;
            this.breachBudget = breachBudget;
        }

        /** Breach only when every sample across a span this long is in breach (a gap is bridged). */
        public static Over sustainedFor(long millis) {
            if (millis <= 0) {
                throw new IllegalArgumentException("sustainedFor must be positive: " + millis);
            }
            return new Over(millis, Double.NaN);
        }

        /** Breach only when more than this fraction (0..1) of the window's samples is in breach. */
        public static Over breachBudget(double fraction) {
            if (!(fraction >= 0 && fraction <= 1)) {
                throw new IllegalArgumentException("breachBudget must be a fraction 0..1: " + fraction);
            }
            return new Over(-1, fraction);
        }

        public boolean isSustainedFor() { return sustainedForMillis > 0; }
        public boolean isBreachBudget() { return !Double.isNaN(breachBudget); }

        public long sustainedForMillis() { return sustainedForMillis; }
        public double breachBudget() { return breachBudget; }

        public String describe() {
            return isSustainedFor()
                    ? "sustained for " + Durations.format(sustainedForMillis)
                    : "breach budget " + Durations.percent(breachBudget) + "%";
        }
    }

    private final Kind kind;
    private final double percentile;
    private final Over over;

    private Reducer(Kind kind, double percentile, Over over) {
        this.kind = kind;
        this.percentile = percentile;
        this.over = over;
    }

    public static Reducer of(Kind kind) {
        if (kind == Kind.PERCENTILE) {
            throw new IllegalArgumentException("a percentile reducer needs its N — use percentile(n)");
        }
        return new Reducer(kind, Double.NaN, null);
    }

    public static Reducer percentile(double n) {
        if (!(n > 0 && n < 100)) {
            throw new IllegalArgumentException("percentile must be between 0 and 100 exclusive: " + n);
        }
        return new Reducer(Kind.PERCENTILE, n, null);
    }

    public Reducer over(Over tolerance) {
        return new Reducer(kind, percentile, tolerance);
    }

    /**
     * Parse the {@code kind} key: {@code max}, {@code min}, {@code avg}, {@code last},
     * {@code slope}, or {@code pN} with N in (0, 100). Returns {@code null} for anything else —
     * the mapper turns that into an INVALID_RULE with the vocabulary in the sentence.
     */
    public static Reducer parse(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (Kind kind : Kind.values()) {
            if (kind != Kind.PERCENTILE && kind.key.equals(k)) {
                return of(kind);
            }
        }
        // Digits and at most one point: parseDouble alone would also take "p 95", "p+95",
        // "p1e1" and a hex float, none of which key() writes back — so an unedited file would
        // diff on its first save.
        if (k.length() > 1 && k.charAt(0) == 'p' && k.substring(1).matches("\\d+(?:\\.\\d+)?")) {
            double n = Double.parseDouble(k.substring(1));
            if (n > 0 && n < 100) {
                return percentile(n);
            }
        }
        return null;
    }

    /** The keys {@link #parse} accepts, for the "valid here" sentence. */
    public static String vocabulary() {
        return "max, min, avg, last, slope, pN (e.g. p95)";
    }

    public Kind kind() { return kind; }

    /** The N of a {@code pN} reducer; NaN otherwise. */
    public double percentile() { return percentile; }

    /** The tolerance clause, or {@code null}. */
    public Over over() { return over; }

    /**
     * False exactly when this reducer carries a tolerance it has no level for: one is defined on
     * {@code max} and {@code min}, the two reducers that pick a level, and on nothing else.
     * {@code KpiUsability} refuses an assert for it, and {@code ServerKpiEvaluator.judgedActual}
     * reports no number for it, so the two cannot disagree about which reducers can be judged.
     */
    public boolean toleranceHasLevel() {
        return over == null || kind == Kind.MAX || kind == Kind.MIN;
    }

    /** The {@code kind} key exactly as the file writes it back. */
    public String key() {
        if (kind == Kind.PERCENTILE) {
            return "p" + Durations.trim(percentile);
        }
        return kind.key();
    }

    /** e.g. {@code "max"}, {@code "p95 sustained for 30s"}. */
    public String describe() {
        return over == null ? key() : key() + " " + over.describe();
    }

    @Override
    public String toString() {
        return describe();
    }
}
