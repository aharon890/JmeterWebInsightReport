package com.jmeterwebinsightreport.core.servermetrics.collect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code wir-collect --csv FILE --csv-columns SPEC}: what a long CSV calls the columns this reader
 * looks for, when another tool wrote it. InfluxDB's annotated CSV calls them {@code _time},
 * {@code _value}, {@code _measurement} and {@code _field}; Dynatrace's {@code metricId} and a column
 * per dimension. Each is long, one row per point, and only the names differ.
 *
 * <pre>
 * time=_time,value=_value,metric=_measurement+_field,instance=host
 * </pre>
 *
 * <ul>
 *   <li>Comma-separated {@code key=column} pairs. A key is one of {@link #KEYS}, in any case; a
 *       column is a header's text, matched as the long reader matches one (trimmed, in any case).
 *       Each part is trimmed and an empty pair is skipped, so a trailing comma is no fault.</li>
 *   <li>{@code metric} may join two columns with {@code +}: {@code metric=_measurement+_field}
 *       reads {@code <measurement>.<field>}, the name Telegraf gives a metric it writes to InfluxDB
 *       (measurement, a dot, field). No other key joins anything.</li>
 *   <li>A key not given keeps the reader's own column name ({@code time}, {@code component},
 *       {@code metric}, {@code instance}, {@code value}).</li>
 * </ul>
 *
 * Refused, naming the key: an unknown key, a key given twice, a pair with no {@code =} or no
 * column, a join anywhere but {@code metric}, a join of more than two, and a join with an empty
 * side. {@link #normalised} writes it back in one spelling - the keys in {@link #KEYS}' order, only
 * those given - which the payload records and the collectionId folds in, so one mapping is one text.
 */
public final class CsvColumns {

    /** The long reader's own column names, in the order a normalised spec writes them. */
    public static final List<String> KEYS =
            Collections.unmodifiableList(Arrays.asList("time", "component", "metric", "instance", "value"));

    /** The key that may join two columns. */
    static final String JOINED = "metric";

    private static final String EXAMPLE = "e.g. time=_time,value=_value";

    /** Key to the file's column name, or the two a {@code metric} join reads; in {@link #KEYS}' order. */
    private final Map<String, List<String>> columns;

    private CsvColumns(Map<String, List<String>> columns) {
        this.columns = columns;
    }

    /**
     * The SPEC as {@code --csv-columns} takes it.
     *
     * @throws IllegalArgumentException a sentence beginning {@code --csv-columns} that names what it
     *                                  cannot read, for {@code wir-collect}'s usage error
     */
    public static CsvColumns parse(String spec) {
        Map<String, List<String>> given = new LinkedHashMap<>();
        for (String piece : (spec == null ? "" : spec).split(",", -1)) {
            String pair = piece.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--csv-columns '" + pair + "' is not key=column - " + EXAMPLE);
            }
            String key = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String column = pair.substring(eq + 1).trim();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("--csv-columns key '" + pair.substring(0, eq).trim()
                        + "' is not one of " + String.join(", ", KEYS));
            }
            if (given.containsKey(key)) {
                throw new IllegalArgumentException("--csv-columns gives " + key + " twice");
            }
            if (column.isEmpty()) {
                throw new IllegalArgumentException("--csv-columns " + key + "= names no column");
            }
            List<String> names = new ArrayList<>();
            for (String side : column.split("\\+", -1)) {
                names.add(side.trim());
            }
            String written = key + "=" + String.join("+", names);
            if (names.size() > 1 && !key.equals(JOINED)) {
                throw new IllegalArgumentException("--csv-columns " + written + ": only " + JOINED
                        + " joins two columns with '+'");
            }
            if (names.size() > 2) {
                throw new IllegalArgumentException("--csv-columns " + written + ": " + JOINED + " joins two columns at most");
            }
            if (names.contains("")) {
                throw new IllegalArgumentException("--csv-columns " + written + ": each side of '+' names a column");
            }
            given.put(key, Collections.unmodifiableList(names));
        }
        if (given.isEmpty()) {
            throw new IllegalArgumentException("--csv-columns needs key=column pairs, " + EXAMPLE);
        }
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        for (String key : KEYS) {
            if (given.containsKey(key)) {
                ordered.put(key, given.get(key));
            }
        }
        return new CsvColumns(Collections.unmodifiableMap(ordered));
    }

    /** No SPEC: every key reads the column of its own name. */
    static CsvColumns none() {
        return new CsvColumns(Collections.<String, List<String>>emptyMap());
    }

    /**
     * {@code key=column} pairs in {@link #KEYS}' order, only those given, a join written
     * {@code a+b}: {@code time=_time,metric=_measurement+_field,value=_value}. Empty for {@link #none}.
     */
    public String normalised() {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : columns.entrySet()) {
            pairs.add(e.getKey() + "=" + String.join("+", e.getValue()));
        }
        return String.join(",", pairs);
    }

    /** Whether the SPEC names {@code key}'s column: a column the file must then have. */
    public boolean maps(String key) {
        return columns.containsKey(key);
    }

    /**
     * The file's column names {@code key} reads: the SPEC's - two for a joined {@code metric} - or,
     * when it does not give the key, the key itself.
     */
    public List<String> columnsFor(String key) {
        List<String> mapped = columns.get(key);
        return mapped != null ? mapped : Collections.singletonList(key);
    }
}
