package com.jmeterwebinsightreport.core.servermetrics.collect;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wide CSV ({@code wir-collect --csv FILE --layout wide}): a time column, then one column per
 * series — what Grafana's panel Inspect &gt; Data &gt; Download CSV writes with "Series joined by
 * time". Read into the {@link CsvSource.Index} a long file builds, so the lookup by component and
 * metric, {@code --from}, the window and the per-series errors are {@link CsvSource}'s, unchanged.
 *
 * <pre>
 * "Time","orders-db/connections.used.pct/pg-1","orders-db/connections.used.pct/pg-2"
 * 1759406535000,41,17
 * 1759406550000,43,
 * </pre>
 *
 * <ul>
 *   <li><b>The time column</b> is the first whose header is {@code time}, in any case, and
 *       otherwise the first column. Its cells are read as a long file's {@code time} is.</li>
 *   <li><b>Every other column is a series</b>, named by a {@code --columns} map of exact header
 *       text ({@link ColumnMap}) or else by its header written {@code component/metric} or
 *       {@code component/metric/instance} — the panel's legend. A column neither names is ignored
 *       and listed; a KPI is never inferred from a raw metric's name, because two KPIs can derive
 *       from one ({@code connections.used.pct} and {@code connections.active} from
 *       {@code pg_stat_activity_count}). A column that names a series nothing asks for (a typo in
 *       the component) is listed too, once the collection has asked ({@link CsvSource#columnsNotAskedFor}).
 *       Two columns naming one series and instance fail that series, naming both.</li>
 *   <li><b>A metric name with a '/' needs the map.</b> The header is split at every '/', so a
 *       custom query with one, and every CloudWatch name in an AWS namespace read with
 *       {@code --from cloudwatch} ({@code AWS/EC2:CPUCreditBalance}), cannot be carried by the
 *       legend: {@code web/AWS/EC2:CPUCreditBalance} is component {@code web}, metric {@code AWS},
 *       instance {@code EC2:CPUCreditBalance} - a series nothing asks for, and listed as one - and a
 *       fourth part leaves the header unread. The map's {@code metric} cell holds the name whole.
 *       The agent's {@code CWAgent:} names and every other tool's have no '/' and need no map.</li>
 *   <li><b>Each cell is a point</b> of its column's series at its row's time. An empty cell or
 *       {@code NaN} is no point: joined by time is an outer join, so an empty cell is a series
 *       missing at another series' timestamp, not one of its own periods. A cell that is not a
 *       plain number fails its series by line, and one that looks formatted (a unit, a thousands
 *       separator) says to export with Formatted data off.</li>
 *   <li><b>Refused by name for the whole file</b>: UTF-16 (Grafana's "Download for Excel") and a
 *       delimiter other than a comma. An older Grafana's {@code sep=,} first line is skipped, and
 *       every line keeps its number.</li>
 * </ul>
 */
final class WideCsv {

    private WideCsv() {
    }

    /** Excel's hint for its delimiter, written first by an older Grafana's Download for Excel. */
    private static final Pattern SEP_LINE = Pattern.compile("(?i)sep=(.?)");

    /** A number followed by a unit or a suffix: "41.5%", "1.20 K", "250 ms". */
    private static final Pattern WITH_UNIT = Pattern.compile("[-+]?\\d[\\d,]*(\\.\\d+)?\\s*[^\\d\\s.,].*");

    /** A number written with a thousands separator: "1,234", "12 345.5". */
    private static final Pattern THOUSANDS = Pattern.compile("[-+]?\\d{1,3}([, ]\\d{3})+(\\.\\d+)?");

    /** Whether {@code bytes} are UTF-16: a byte order mark of either order, or a NUL no UTF-8 text has. */
    static boolean isUtf16(byte[] bytes) {
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
                || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF))) {
            return true;
        }
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param map  the {@code --columns} map, or null: every column named by its header alone
     * @param zone the {@code --time-zone} a zone-less time is read in, or null
     */
    static CsvSource.Index parse(String text, String name, ColumnMap map, ZoneId zone) {
        text = CsvSource.withoutBom(text);
        int eol = firstLineEnd(text);
        Matcher sep = SEP_LINE.matcher(text.substring(0, eol).trim());
        boolean sepLine = sep.matches();
        if (sepLine && !sep.group(1).isEmpty() && !sep.group(1).equals(",")) {
            return CsvSource.Index.failed(name + " line 1: " + delimiterProblem(sep.group(1).charAt(0)));
        }
        List<CsvSource.Row> rows = CsvSource.rows(text);
        if (sepLine && !rows.isEmpty()) {
            rows.remove(0);   // each row keeps the line it was on, so every later line is named as the file numbers it
        }
        if (rows.isEmpty() || rows.get(0).blank()) {
            return CsvSource.Index.failed(name + " is empty - it needs a header row: a time column, then one column"
                    + " per series");
        }
        CsvSource.Row header = rows.get(0);
        // One cell holding a ';' or a tab is a header split by something other than a comma: Grafana
        // takes its delimiter from the browser's locale, and Excel's download separates by tabs.
        if (header.cells.size() == 1) {
            String only = header.cells.get(0);
            for (char d : new char[] {';', '\t'}) {
                if (only.indexOf(d) >= 0) {
                    return CsvSource.Index.failed(name + " line " + header.line + ": " + delimiterProblem(d));
                }
            }
        }

        List<String> headers = new ArrayList<>();
        for (String cell : header.cells) {
            headers.add(cell.trim());
        }
        int timeColumn = 0;
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).toLowerCase(Locale.ROOT).equals("time")) {
                timeColumn = i;
                break;
            }
        }

        CsvSource.Index idx = new CsvSource.Index();
        Map<Integer, Target> targets = new LinkedHashMap<>();
        // series and instance -> the column that holds it, to name both of two that claim it
        Map<String, Integer> columnOf = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            if (i == timeColumn) {
                continue;
            }
            String h = headers.get(i);
            Target t = map == null ? null : map.targets.get(h);
            if (t == null) {
                t = Target.ofHeader(h);
            }
            if (t == null) {
                idx.unmapped.add(h.isEmpty() ? "(column " + (i + 1) + ", no header)" : h);
                continue;
            }
            String key = CsvSource.seriesKey(t.component, t.metric);
            // Every column that names a series, a refused second one too: one nothing asks for is
            // listed after the collection, as an unmapped one is now.
            idx.columnsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(h);
            Integer other = columnOf.putIfAbsent(key + "\u0000" + t.instance, i);
            if (other != null) {
                idx.errors.putIfAbsent(key, name + " line " + header.line + ": columns " + (other + 1) + " \""
                        + headers.get(other) + "\" and " + (i + 1) + " \"" + h + "\" both map to " + t.component + "/"
                        + t.metric + (t.instance.isEmpty() ? "" : " instance " + t.instance)
                        + " - a series is read from one column only");
                continue;
            }
            targets.put(i, t);
            // The column is the series' rows whatever its cells hold, as a long file's rows are.
            idx.series.computeIfAbsent(key, k -> new LinkedHashMap<>()).computeIfAbsent(t.instance, k -> new TreeMap<>());
        }
        if (map != null) {
            Set<String> present = new HashSet<>(headers);
            for (String column : map.targets.keySet()) {
                if (!present.contains(column)) {
                    idx.mapMissing.add(column);
                }
            }
        }

        for (int r = 1; r < rows.size(); r++) {
            CsvSource.Row row = rows.get(r);
            if (row.blank()) {
                continue;
            }
            String where = name + " line " + row.line + ": ";
            String time = row.cell(timeColumn).trim();
            Long ts = CsvSource.parseTime(time, zone);
            if (ts == null) {
                // The row's time is every column's: each series on it fails, at its first bad line.
                String problem = where + CsvSource.timeError(time, zone);
                for (Target t : targets.values()) {
                    idx.errors.putIfAbsent(CsvSource.seriesKey(t.component, t.metric), problem);
                }
                continue;
            }
            for (Map.Entry<Integer, Target> e : targets.entrySet()) {
                Target t = e.getValue();
                String key = CsvSource.seriesKey(t.component, t.metric);
                if (idx.errors.containsKey(key)) {
                    continue;   // the first bad line is the one reported
                }
                String v = row.cell(e.getKey()).trim();
                if (v.isEmpty() || v.equalsIgnoreCase("nan")) {
                    continue;   // a gap: no point, and not one of this series' own periods
                }
                double value;
                try {
                    value = Double.parseDouble(v);
                } catch (NumberFormatException ex) {
                    idx.errors.put(key, where + "column \"" + headers.get(e.getKey()) + "\": value \"" + v + "\" "
                            + (CsvSource.infinityText(v) ? "is not a finite number"
                                    : looksFormatted(v)
                                    ? "is not a plain number - it looks formatted, with a unit or a thousands separator:"
                                            + " export it with Formatted data off"
                                    : "is not a number - write it with a '.' decimal point and no thousands separator"));
                    continue;
                }
                if (Double.isInfinite(value)) {
                    idx.errors.put(key, where + "column \"" + headers.get(e.getKey()) + "\": value \"" + v
                            + "\" is not a finite number");
                    continue;
                }
                idx.series.get(key).get(t.instance).put(ts, value);   // a repeated timestamp keeps the last row
            }
        }
        return idx;
    }

    /** Whether a cell that is not a number is one Grafana's Formatted data would write. */
    static boolean looksFormatted(String v) {
        return WITH_UNIT.matcher(v).matches() || THOUSANDS.matcher(v).matches();
    }

    private static String delimiterProblem(char d) {
        return "the delimiter is " + (d == '\t' ? "a tab" : "'" + d + "'") + " and only ',' is read"
                + " - export the file comma-separated";
    }

    private static int firstLineEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return text.length();
    }

    /** The series a column holds: a component, a metric (a catalogue name or a custom query), an instance. */
    static final class Target {
        final String component;
        final String metric;
        final String instance;

        Target(String component, String metric, String instance) {
            this.component = component;
            this.metric = metric;
            this.instance = instance;
        }

        /**
         * The header convention: {@code component/metric} or {@code component/metric/instance},
         * each part trimmed and none empty; null for any other header. A custom query with a
         * {@code /} in it cannot be written this way, nor can a CloudWatch {@code AWS/...} name, and
         * each is named by the map.
         */
        static Target ofHeader(String header) {
            String[] parts = header.split("/", -1);
            if (parts.length < 2 || parts.length > 3) {
                return null;
            }
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
                if (parts[i].isEmpty()) {
                    return null;
                }
            }
            return new Target(parts[0], parts[1], parts.length == 3 ? parts[2] : "");
        }
    }

    /**
     * The {@code --columns} map: a CSV whose header names {@code column}, {@code component},
     * {@code metric} and, optionally, {@code instance}, found by name in any order as a long file's
     * are. {@code column} is an export header exactly as written (trimmed). It is for a legend that
     * cannot be set to {@code component/metric}, and for a metric name with a {@code /} in it: a
     * custom query, or a CloudWatch name in an AWS namespace under {@code --from cloudwatch}.
     * A file it cannot read fails the whole collection by line, since it is a few lines written by
     * hand and a guess at what a broken one meant would name the wrong series.
     */
    static final class ColumnMap {
        final Map<String, Target> targets = new LinkedHashMap<>();
        private String error;

        String error() {
            return error;
        }

        private static ColumnMap failed(String error) {
            ColumnMap m = new ColumnMap();
            m.error = error;
            return m;
        }

        static ColumnMap parse(String text, String name) {
            List<CsvSource.Row> rows = CsvSource.rows(CsvSource.withoutBom(text));
            if (rows.isEmpty() || rows.get(0).blank()) {
                return failed(name + " is empty - it needs a header row: column,component,metric,instance");
            }
            CsvSource.Row header = rows.get(0);
            Map<String, Integer> col = new LinkedHashMap<>();
            for (int i = 0; i < header.cells.size(); i++) {
                col.putIfAbsent(header.cells.get(i).trim().toLowerCase(Locale.ROOT), i);
            }
            List<String> missing = new ArrayList<>();
            for (String r : new String[] {"column", "component", "metric"}) {
                if (!col.containsKey(r)) {
                    missing.add(r);
                }
            }
            if (!missing.isEmpty()) {
                return failed(name + " line " + header.line + ": the header has no " + String.join(", ", missing)
                        + " column - it needs column, component and metric (instance is optional)");
            }
            Integer cInstance = col.get("instance");
            ColumnMap map = new ColumnMap();
            Map<String, Integer> lineOf = new HashMap<>();
            for (int r = 1; r < rows.size(); r++) {
                CsvSource.Row row = rows.get(r);
                if (row.blank()) {
                    continue;
                }
                String column = row.cell(col.get("column")).trim();
                String component = row.cell(col.get("component")).trim();
                String metric = row.cell(col.get("metric")).trim();
                if (column.isEmpty() || component.isEmpty() || metric.isEmpty()) {
                    return failed(name + " line " + row.line + ": a row needs a column, a component and a metric");
                }
                Integer first = lineOf.putIfAbsent(column, row.line);
                if (first != null) {
                    return failed(name + " line " + row.line + ": column \"" + column + "\" is mapped twice (line "
                            + first + " too)");
                }
                map.targets.put(column, new Target(component, metric, cInstance == null ? "" : row.cell(cInstance).trim()));
            }
            return map;
        }
    }
}
