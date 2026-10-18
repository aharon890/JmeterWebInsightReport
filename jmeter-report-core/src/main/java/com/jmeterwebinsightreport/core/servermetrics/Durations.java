package com.jmeterwebinsightreport.core.servermetrics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The duration spelling the file uses — {@code "30s"}, {@code "2m"}, {@code "500ms"},
 * {@code "1h"} — and the formatting that writes it back the same way.
 *
 * <p>Prometheus and Datadog both spell a step this way, and the canvas used it for every
 * window and wait, so there is one grammar for the whole block rather than seconds in one key
 * and millis in the next. A bare number is refused: {@code "step": 15} is ambiguous between
 * seconds and milliseconds, and guessing wrong by a factor of a thousand is exactly the silent
 * error this project refuses elsewhere.
 *
 * <p>The accepted grammar is exactly the written one — lowercase unit, no space between number
 * and unit, surrounding whitespace forgiven. Accepting {@code "30 S"} would be friendlier on
 * load and a lie on save: {@link #format} writes {@code "30s"}, so an unedited file would diff
 * the first time the editor wrote it back.
 */
public final class Durations {

    private static final Pattern FORM = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)(ms|s|m|h)\\s*$");

    private Durations() {
    }

    /**
     * Milliseconds, or {@code null} when the text is not a duration.
     *
     * <p>Null rather than an exception: the mapper reports the problem against the KPI or the
     * source it was written on, in the file's own words, which an exception message cannot.
     */
    public static Long parseMillis(String text) {
        if (text == null) return null;
        Matcher m = FORM.matcher(text);
        if (!m.matches()) return null;
        double n = Double.parseDouble(m.group(1));
        switch (m.group(2)) {
            case "ms": return Math.round(n);
            case "s":  return Math.round(n * 1000);
            case "m":  return Math.round(n * 60_000);
            case "h":  return Math.round(n * 3_600_000);
            default:   return null;
        }
    }

    /** The grammar, for the sentence that names what was expected. */
    public static String grammar() {
        return "a duration such as \"30s\", \"2m\", \"500ms\" or \"1h\"";
    }

    /** The shortest exact spelling: 30000 → "30s", 90000 → "1.5m", 250 → "250ms". */
    public static String format(long millis) {
        if (millis % 3_600_000 == 0) return trim(millis / 3_600_000.0) + "h";
        if (millis % 60_000 == 0) return trim(millis / 60_000.0) + "m";
        if (millis % 1000 == 0) return trim(millis / 1000.0) + "s";
        if (millis >= 1000 && millis % 100 == 0) return trim(millis / 1000.0) + "s";
        return millis + "ms";
    }

    /** A number without a trailing {@code .0}: 95.0 → "95", 97.5 → "97.5". */
    public static String trim(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    /**
     * A fraction written as a percentage, to twelve significant figures and in plain notation:
     * 0.07 → "7", 0.333 → "33.3", 0.000001 → "0.0001".
     *
     * <p>Not {@code trim(fraction * 100)}: the multiplication carries binary noise, and
     * {@code 0.07 * 100} is 7.000000000000001 — which is what a breach budget of 0.07 printed in
     * the CI/CD JSON's {@code reduce} sentence and in every NO_VALUE detail (C-13). Twelve figures
     * is far more than any budget is written with and far fewer than the noise sits at. The
     * browser writes the same text (report.js {@code percentText}); the parity vectors pin both.
     */
    public static String percent(double fraction) {
        double d = fraction * 100;
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return String.valueOf(d);
        }
        return new BigDecimal(d).round(new MathContext(12, RoundingMode.HALF_UP))
                .stripTrailingZeros().toPlainString();
    }
}
