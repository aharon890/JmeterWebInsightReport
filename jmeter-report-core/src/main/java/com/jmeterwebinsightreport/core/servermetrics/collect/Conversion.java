package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.jmeterwebinsightreport.core.servermetrics.catalogue.ToolName;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * How one tool's number becomes the catalogue's ({@code wir-collect --csv FILE --from TOOL}): one
 * instance's rows, as the file holds them, turned into points in the catalogue's unit by the
 * {@link ToolName.Kind kind} of number the tool hands over, then multiplied by the
 * {@link ToolName#scale() scale}. Pure. Which name the rows were read under is {@link CsvSource}'s
 * call; this only converts them.
 *
 * <ul>
 *   <li>{@code gauge} and {@code rate} (a rate already per second): the value times the scale.</li>
 *   <li>{@code counter}, a raw cumulative count: a rate per second between consecutive values,
 *       {@code (v1 - v0) / seconds}, stamped at the later one. A value below the one before it is
 *       a reset, counted from zero: {@code v1 / seconds}. The first value has nothing before it and
 *       gives no point. A gap is skipped, and the next value is taken from the last one before
 *       it.</li>
 *   <li>{@code sum-per-period}, a sum over the export's period: divided by the period in seconds,
 *       which is the instance's most common spacing between rows (the smaller on a tie), a gap's row
 *       included, since an empty period is still one of the export's. An instance of one row has
 *       no spacing, and the source's step stands in.</li>
 * </ul>
 *
 * So a catalogue metric read per minute declares {@code scale 60} on a counter or a rate, and a
 * 0-1 fraction the catalogue reads as % declares {@code scale 100}.
 *
 * <p>Applied to the instance's whole series, before the run's window is applied: a counter's first
 * rate in the window is the difference from the row before it, which the window would drop. A gap
 * is no point in what comes back, as a gap is no point anywhere in a payload.
 */
final class Conversion {

    private Conversion() {
    }

    /**
     * @param rows                 timestamp to value, a null value a gap — an instance as
     *                             {@link CsvSource} indexes it, ascending
     * @param fallbackPeriodMillis a sum-per-period instance's period when it has one row: the
     *                             source's step. A sum with no period above 0 to divide by gives
     *                             no point.
     */
    static TreeMap<Long, Double> toCatalogueUnit(NavigableMap<Long, Double> rows, ToolName.Kind kind, double scale,
                                                 long fallbackPeriodMillis) {
        TreeMap<Long, Double> out = new TreeMap<>();
        switch (kind) {
            case COUNTER: {
                Long t0 = null;
                double v0 = 0;
                for (Map.Entry<Long, Double> r : rows.entrySet()) {
                    Double v1 = r.getValue();
                    if (v1 == null) {
                        continue;   // a gap: the next value is taken from the last one before it
                    }
                    long t1 = r.getKey();
                    if (t0 != null) {
                        double seconds = (t1 - t0) / 1000.0;
                        // Below the last value: the counter restarted, and counted v1 since.
                        double counted = v1 >= v0 ? v1 - v0 : v1;
                        out.put(t1, counted / seconds * scale);
                    }
                    t0 = t1;
                    v0 = v1;
                }
                return out;
            }
            case SUM_PER_PERIOD: {
                long period = period(rows, fallbackPeriodMillis);
                if (period <= 0) {
                    return out;
                }
                double seconds = period / 1000.0;
                for (Map.Entry<Long, Double> r : rows.entrySet()) {
                    if (r.getValue() != null) {
                        out.put(r.getKey(), r.getValue() / seconds * scale);
                    }
                }
                return out;
            }
            default:   // GAUGE, RATE
                for (Map.Entry<Long, Double> r : rows.entrySet()) {
                    if (r.getValue() != null) {
                        out.put(r.getKey(), r.getValue() * scale);
                    }
                }
                return out;
        }
    }

    /**
     * The export's period for one instance, in milliseconds: its most common spacing between
     * consecutive rows, the smaller on a tie, a gap's row counted as a row
     * ({@link Coverage#mostCommonSpacing}, the rule {@code wir-collect}'s spacing line reads by);
     * {@code fallbackPeriodMillis} when it has fewer than two.
     */
    static long period(NavigableMap<Long, Double> rows, long fallbackPeriodMillis) {
        return rows.size() < 2 ? fallbackPeriodMillis : Coverage.mostCommonSpacing(rows.keySet());
    }
}
