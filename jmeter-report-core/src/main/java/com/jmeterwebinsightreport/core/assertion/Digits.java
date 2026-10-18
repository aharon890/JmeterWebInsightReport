package com.jmeterwebinsightreport.core.assertion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * How many digits a measured number needs when it is written beside the bands it was judged
 * against.
 *
 * <p>The rule, stated once: <b>an actual is never written on a band it is not on, or across one;
 * a band is never rounded.</b> Two decimals are right almost everywhere and wrong exactly where
 * the verdict is closest, which is where a reader looks: a FAIL at 119.36094674556213 under
 * {@code gt 119.36} was written {@code 119.36}, on the band it breached, and a PASS at
 * 214.7455621301775 under {@code gte 214.75} as {@code 214.75} beside {@code "breached": false}.
 *
 * <p>{@link #beside} keeps the precision a surface already writes and adds significant digits only
 * where that number would sit on a band the value is not on, or on the other side of one. Every band
 * the rule declares counts, warn and fail alike ({@link #bands}): a PASS has no breached band, and
 * its fail band is printed beside it all the same. A band of 0 is the never-zero guard — a non-zero
 * value written as 0 is on the wrong side of 0 — so a surface that must never write a measured value
 * as 0 passes one ({@link #withZero}).
 *
 * <p>The page writes the same numbers through {@code WIR.digits.beside} in report.js, its twin, on
 * both tabs. Neither copy owns the definition: {@code assertion/digits-vectors.json} in this
 * module's test resources does, beside the assert parity vectors, and {@code DigitsTest} and
 * {@code test-plans/js-unit/digits.test.js} run that one file with {@code ==}, never a tolerance. A
 * case only one side passes is the drift the file exists to catch.
 *
 * <p>Java's surfaces that write an actual beside a band are the CI/CD JSON's client actual and its
 * server actual ({@code serverAsserts[].actual}, at four places), JUnit, and the server evaluator's
 * LOAD_NOT_ACHIEVED sentence, which quotes a client load rule — the last is in this module, which is
 * why this class is here rather than beside the serializers.
 */
public final class Digits {

    /** 2^53 - 1, JavaScript's Number.MAX_SAFE_INTEGER: past it a double is no longer every integer. */
    private static final double MAX_SAFE = 9007199254740991.0;

    private Digits() {
    }

    /**
     * The number to write for {@code value} beside {@code bands}.
     *
     * <p>{@code value} rounded to {@code decimals} decimals — half up, as {@code Math.round} and the
     * page's {@code Math.round} both round — unless that number is on a band the value is not on, or
     * across one. Then the fewest significant digits that keep it on its own side of every band:
     * from the rounded number's own count, up to 12 or a third decimal, whichever is more, and never
     * past 17, the most any double needs. Each candidate is the double's exact value rounded half up
     * ({@code BigDecimal}), which is what the page's {@code toPrecision} does.
     *
     * <p>Two limits, both the Server tab's (C1), which the page shares:
     * <ul>
     *   <li>Past 2^53 / 10^decimals the value is written as it is. There {@code value * 10^decimals}
     *       is no longer exact, so the rounding moved the last digit — 799450271802898 read
     *       "…897.9" — and every double that large prints in {@code decimals} or fewer anyway.</li>
     *   <li>The cap is what keeps a computed number's float noise out: when even the cap misleads,
     *       the capped number is written. A T-Digest 993.2499999999998 that PASSes {@code gte 993.25}
     *       by ~2e-13 is written {@code 993.25}. That verdict is decided by noise, which is the
     *       evaluator's defect (plan L8), not something more digits should advertise.</li>
     * </ul>
     *
     * <p>A band that is not finite separates nothing and is ignored. A value that is not finite is
     * returned as it is: there is no digit to choose, and the page's copy returns it too.
     *
     * @param decimals the surface's own precision: 2 for the JSON's client actual, JUnit and the
     *                 Server tab, 4 for the JSON's server actual, 0 for a client millisecond or
     *                 second on the Asserts tab
     */
    public static double beside(double value, int decimals, double... bands) {
        if (!Double.isFinite(value)) {
            return value;
        }
        double scale = Math.pow(10, decimals);
        double shown = Math.abs(value) * scale > MAX_SAFE ? value : Math.round(value * scale) / scale;
        if (!misleads(shown, value, bands)) {
            return shown;
        }
        int magnitude = (int) Math.floor(Math.log10(Math.abs(value)));
        int most = Math.min(17, Math.max(12, magnitude + 4));
        for (int p = Math.max(2, magnitude + 1 + decimals); p <= most; p++) {
            shown = Double.parseDouble(
                    new BigDecimal(value).round(new MathContext(p, RoundingMode.HALF_UP)).toString());
            if (!misleads(shown, value, bands)) {
                break;
            }
        }
        return shown;
    }

    /**
     * The constants of every band a rule declares, warn and fail, in that order; an absent band is
     * no constant. Every one counts for every number the rule writes (Q1): the breached band alone
     * cannot fix a PASS, which has none.
     */
    public static double[] bands(Predicate... declared) {
        if (declared == null) {
            return new double[0];
        }
        return Arrays.stream(declared).filter(b -> b != null).mapToDouble(Predicate::value).toArray();
    }

    /** {@code bands} and a band of 0: the never-zero guard, for a surface that keeps one. */
    public static double[] withZero(double[] bands) {
        double[] in = bands == null ? new double[0] : bands;
        double[] out = Arrays.copyOf(in, in.length + 1);
        out[in.length] = 0;
        return out;
    }

    /**
     * Does {@code shown} sit on a band {@code value} is not on, or on the other side of one? Landing
     * on a band counts; being on a band the value is on does not (3 beside {@code > 3}, on a PASS at
     * exactly 3).
     */
    private static boolean misleads(double shown, double value, double[] bands) {
        if (bands == null) {
            return false;
        }
        for (double c : bands) {
            if (!Double.isFinite(c)) {
                continue;
            }
            if (Math.signum(shown - c) != Math.signum(value - c)) {
                return true;
            }
        }
        return false;
    }
}
