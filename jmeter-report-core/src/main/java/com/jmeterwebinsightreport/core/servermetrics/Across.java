package com.jmeterwebinsightreport.core.servermetrics;

import java.util.Locale;

/**
 * How one KPI folds N instances (pods, hosts, brokers) into the one number its bands judge.
 *
 * <p>Every instance is meant to stay charted individually (M5); this only decides the verdict.
 * The fold exists to find the WORST instance, and which one that is depends on the metric:
 * {@code max} for a metric that is worse when higher — a saturation threshold is about the pod
 * that is on fire, and an average of six pods hides it — and {@code min} for one that is worse
 * when lower, an availability flag or a hit ratio, where {@code max} reported the healthy
 * instance and a database that went down PASSed beside its live twin (SF2). {@code avg} is the
 * deliberate exception, for a metric whose spread across instances is not the point.
 *
 * <p>{@code max} stays the default, for a hand-written query as before. A catalogue metric that
 * is worse when lower declares {@code "across": "min"} in its type file, per metric, and is not
 * derived from its {@code direction}: the file is the one copy both Java and the page read, and a
 * derivation would be a second rule written twice.
 *
 * <p>Per-instance judging (N verdicts for one KPI) is deliberately not here: it would inherit
 * the client's match cap and worst-of-N fold, and pod names would leak into per-sampler outputs
 * that have no business carrying them.
 */
public enum Across {
    MAX("max"),
    MIN("min"),
    AVG("avg");

    private final String key;

    Across(String key) {
        this.key = key;
    }

    /** The JSON value this is written as. */
    public String key() {
        return key;
    }

    public static Across fromKey(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (Across a : values()) {
            if (a.key.equals(k)) return a;
        }
        return null;
    }

    /** "max, min, avg" — for a refusal that says what would have been accepted. */
    public static String vocabulary() {
        StringBuilder sb = new StringBuilder();
        for (Across a : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a.key);
        }
        return sb.toString();
    }

    /** The value when the KPI says nothing. */
    public static Across defaultValue() {
        return MAX;
    }
}
