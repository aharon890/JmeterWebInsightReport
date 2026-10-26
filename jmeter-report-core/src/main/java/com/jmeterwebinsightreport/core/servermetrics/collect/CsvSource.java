package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.jmeterwebinsightreport.core.servermetrics.Durations;
import com.jmeterwebinsightreport.core.servermetrics.KpiUsability;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.ToolName;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The CSV backend (plans/server-metrics-backends.md §5, §8b): {@code wir-collect --csv FILE}.
 *
 * <p>By default ({@code --layout long}) the file is <em>long</em> — one row per point — with a
 * header naming the columns, in any order, extra columns ignored:
 *
 * <pre>
 * time,component,metric,instance,value
 * 2026-10-02T14:02:15+03:00,orders-db,connections.used.pct,pg-1,41.5
 * 2026-10-02T14:02:15+03:00,checkout-app,queue-depth,,17
 * </pre>
 *
 * A series is found by its component and its metric key — a catalogue metric's name, or a custom
 * assert's query — never by the request's query text, so a catalogue metric with no prometheus
 * query ({@link SeriesSource.Request#neutral()}) is answered like any other. Without {@code --from},
 * the value is the metric's value in the catalogue's unit and nothing is converted.
 *
 * <p><b>{@code --from TOOL}</b>: a file exported from another tool, whose metric column holds that
 * tool's names and whose values are that tool's kind of number ({@link SeriesSource.Request#fromTool}).
 * For a catalogue metric, the rows are looked for under the tool's name for it first, and converted
 * to the catalogue's unit by its kind and scale ({@link Conversion}, over each instance's whole
 * series before the window is applied); then under the catalogue's own name, read as they are — a
 * series the user computed. With neither, a tool the catalogue says has no single metric for it, or
 * does not name at all, fails the series in a sentence that says so; a tool that names it and is
 * not in the file is a missing series, as without {@code --from}. A custom assert's query is never a
 * tool's name, and is looked up and read exactly as without it. The catalogue itself is the
 * collector's: the request carries what this needs of it.
 *
 * <p><b>{@code --csv-columns SPEC}</b> and <b>{@code --component ID}</b>, for a long file another
 * tool wrote: InfluxDB's {@code _time,_value,_measurement,_field,host}, Dynatrace's
 * {@code metricId,dt.entity.host,time,value}. The SPEC names the file's column for each of the
 * reader's own ({@link CsvColumns}: {@code time=_time,value=_value,metric=_measurement+_field,instance=host},
 * a joined metric read {@code <measurement>.<field>}), a key not given keeping its own name; a
 * mapped column the header lacks fails the file, naming it and quoting the header. {@code --component}
 * puts every row in that component, for a tool that knows nothing of components - and so for a file
 * whose asserts are all on it, every other component finding no rows; beside a file that has a
 * component column of its own it is refused, so the component is said in one place.
 * InfluxDB v2's <em>annotated</em> CSV - {@code #group}/{@code #datatype}/{@code #default} lines, a
 * leading empty column, one block per table schema, each with its own header after a blank line -
 * is read whenever it is met ({@link #parse}). A plain long file is one block and reads as it always
 * did; a {@code #} comment in one is skipped and starts nothing.
 *
 * <p><b>{@code --layout wide}</b>: a time column and one column per series — Grafana's panel
 * Inspect &gt; Data &gt; Download CSV with "Series joined by time" ({@link WideCsv}). Each column is
 * named {@code component/metric[/instance]} by its header (the panel's legend), or by a
 * {@code --columns} map of exact header text; one neither names is ignored and listed
 * ({@link #unmappedColumns}), never guessed from a raw metric's name, and so is one that names a
 * series nothing asks for ({@link #columnsNotAskedFor}). It is read into the same index as a long
 * file, so the lookup, {@code --from}, the window and the errors are the same.
 *
 * <p><b>Times</b>, in both layouts: ISO 8601 with an offset or {@code Z}, or epoch milliseconds.
 * A time with no zone is refused unless {@code --time-zone} names the zone it is in
 * ({@link ServerSource#timeZone()}) - and even then when that zone's daylight-saving change repeats
 * or skips it - and an all-digit time below {@link #EPOCH_MILLIS_FLOOR} is refused as epoch seconds:
 * read as milliseconds it lands in 1970, outside every window, and the series comes back EMPTY with
 * nothing to say why.
 *
 * <p>Errors are named by line and fail only the series the row belongs to, so one bad row makes
 * the collection PARTIAL rather than taking every series with it. A metric cell that is a name
 * padded with an invisible character {@link String#trim} leaves (a no-break space, a zero-width or
 * bidi character) is such a row of the series that name asks for. A missing required column fails
 * every series, and a row naming nothing the file asks for is ignored — an export may carry more.
 *
 * <p>The file is read and indexed once, then every series is answered from the index.
 */
public final class CsvSource implements SeriesSource {

    /** The instance label a CSV row's {@code instance} column becomes. */
    public static final String INSTANCE_LABEL = "instance";

    /**
     * The smallest all-digit time read as epoch milliseconds: 1e11, early 1973. Below it the number
     * is epoch seconds (1759406535 is 2025), which read as milliseconds is January 1970.
     */
    static final long EPOCH_MILLIS_FLOOR = 100_000_000_000L;

    /**
     * The first all-digit time NOT read as epoch milliseconds: 1e14, the year 5138. At or above it
     * the number is epoch microseconds or nanoseconds (1792527704613000 is 2026 in microseconds),
     * which read as milliseconds land tens of thousands of years out - the series EMPTY, nothing said.
     */
    static final long EPOCH_MILLIS_CEILING = 100_000_000_000_000L;

    /** A local date and time, as Grafana's Formatted data writes one: no offset, no zone. */
    private static final Pattern LOCAL_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");

    private final File baseDir;
    private String indexedKey;
    private Index index;
    /** The series keys asked of {@link #index} since it was read, a {@code --from} tool's name included. */
    private final Set<String> asked = new HashSet<>();

    /** Relative paths against the working directory. */
    public CsvSource() {
        this(null);
    }

    public CsvSource(File baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * The headers of the wide file last read that neither the header convention nor the
     * {@code --columns} map named, in the file's order: ignored, and listed by {@code wir-collect}
     * so a legend that was not set is seen. Empty for a long file.
     */
    public List<String> unmappedColumns() {
        return index == null ? Collections.<String>emptyList() : Collections.unmodifiableList(index.unmapped);
    }

    /**
     * The {@code --columns} map's columns the wide file last read does not have: they map nothing,
     * and {@code wir-collect} lists them. Empty without a map.
     */
    public List<String> mapColumnsNotInFile() {
        return index == null ? Collections.<String>emptyList() : Collections.unmodifiableList(index.mapMissing);
    }

    /**
     * The headers of the wide file last read that did name a series - by the header convention or
     * the {@code --columns} map - that {@link #collect} was never asked for, in the order their
     * series first appear: a legend with a typo in the component, or a header that only looks like
     * {@code component/metric} (Grafana's label-set legend with a path in it, an expression with a
     * division). Read and never looked up, each is as ignored as an {@linkplain #unmappedColumns
     * unmapped} column, and {@code wir-collect} lists them after the collection. A column asked for
     * under {@code --from}'s tool name was asked for. Empty for a long file.
     */
    public List<String> columnsNotAskedFor() {
        if (index == null) {
            return Collections.emptyList();
        }
        List<String> headers = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : index.columnsByKey.entrySet()) {
            if (!asked.contains(e.getKey())) {
                headers.addAll(e.getValue());
            }
        }
        return Collections.unmodifiableList(headers);
    }

    @Override
    public Answer collect(ServerSource source, String resolvedQuery, ServerMetricsPayload.Window window) {
        // A CSV row is named by component and metric, never by a query alone.
        return Answer.failed("the CSV backend finds a series by its component and metric, and was asked by query only");
    }

    @Override
    public Answer collect(ServerSource source, Request request, ServerMetricsPayload.Window window) {
        Index idx = indexFor(source);
        if (idx.fileError != null) {
            return Answer.failed(idx.fileError);
        }
        String key = seriesKey(request.component(), request.metricKey());
        asked.add(key);
        ToolName readAs = null;
        ToolName toolName = null;
        if (request.tool() != null) {
            // --from: the tool's name first, converted; then the catalogue's own name, as it is -
            // a series the user computed, already in the catalogue's unit (the escape hatch).
            ToolName name = request.toolName();
            toolName = name;
            String toolKey = name == null ? null : seriesKey(request.component(), name.metric());
            if (toolKey != null) {
                asked.add(toolKey);
            }
            if (toolKey != null && idx.has(toolKey)) {
                key = toolKey;
                readAs = name;
            } else if (!idx.has(key) && name == null) {
                // Neither name has rows, and the catalogue gives no name of the tool's to have
                // looked for: say which of the two it is, never an EMPTY nobody can explain. A tool
                // that names it and is not in the file is a missing series, as it always was.
                return request.toolDeclaresNone() ? Answer.noToolMetric(request) : Answer.notNamedInTool(request);
            }
        }
        // A row whose metric cell is this name padded with an invisible character was meant for this
        // series (F8-residual): it fails it, by line, as any other bad row does - recorded only when no
        // earlier line had already failed the series, so the first bad line is still the one reported.
        String padded = idx.padded.get(key);
        if (padded != null) {
            return Answer.failed(padded);
        }
        String error = idx.errors.get(key);
        if (error != null) {
            return Answer.failed(error);
        }
        Map<String, TreeMap<Long, Double>> byInstance = idx.series.get(key);
        List<ServerMetricsPayload.InstanceRecord> instances = new ArrayList<>();
        if (byInstance == null) {
            // No row names this series. When rows name one that differs only by case or by '.',
            // '_' or '-', that is a typo in the export or the file, not a missing series: say so,
            // by name, rather than an EMPTY that reads like a query that matched nothing. Under
            // --from the export most likely spelt the tool's name, so that is tried first.
            String near = toolName == null ? null : nearMiss(idx, request.component(), toolName.metric());
            if (near == null) {
                near = nearMiss(idx, request.component(), request.metricKey());
            }
            if (near != null) {
                return Answer.failed(near);
            }
            String split = splitAt(idx, request.component(), request.metricKey());
            if (split != null) {
                return Answer.failed(split);
            }
            return Answer.of(instances, readAs);
        }
        // Rows outside the run are dropped, with one step of slack at each edge: a range query on
        // a step grid answers the sample just before the window opens and just after it closes,
        // and coverage counts them. Clipping exactly read the same data as less covered than the
        // Prometheus backend did (98% against 100% on E26's own series).
        long slack = Math.max(0, source.stepMillis());
        for (Map.Entry<String, TreeMap<Long, Double>> e : byInstance.entrySet()) {
            // Converted over the whole instance, before the window: a counter's first rate in the
            // run is the difference from the row before it, which the window drops.
            TreeMap<Long, Double> rows = readAs == null ? e.getValue()
                    : Conversion.toCatalogueUnit(e.getValue(), readAs.kind(), readAs.scale(), source.stepMillis());
            List<ServerMetricsPayload.Point> points = new ArrayList<>();
            for (Map.Entry<Long, Double> p : rows.entrySet()) {
                long ts = p.getKey() + source.clockOffsetMs();
                if (!inRun(ts, window, slack)) {
                    continue;
                }
                if (p.getValue() != null) {
                    points.add(new ServerMetricsPayload.Point(ts, p.getValue()));
                }
            }
            if (points.isEmpty()) {
                continue;
            }
            ServerMetricsPayload.InstanceRecord inst = new ServerMetricsPayload.InstanceRecord();
            inst.setLabels(e.getKey().isEmpty() ? Collections.<String, String>emptyMap()
                    : Collections.singletonMap(INSTANCE_LABEL, e.getKey()));
            inst.setPoints(points);
            instances.add(inst);
        }
        if (instances.isEmpty()) {
            return emptySeries(source, request, window, slack, byInstance, readAs, idx);
        }
        return Answer.of(instances, readAs);
    }

    /**
     * Why a series whose rows exist gave no point: every row outside the run (a time problem), the
     * rows inside it holding numbers a {@code --from} conversion left nothing of, or none of them
     * holding a number. The span and the counts are taken only here, where they are said.
     */
    private Answer emptySeries(ServerSource source, Request request, ServerMetricsPayload.Window window, long slack,
                               Map<String, TreeMap<Long, Double>> byInstance, ToolName readAs, Index idx) {
        long fileFirst = Long.MAX_VALUE;
        long fileLast = Long.MIN_VALUE;
        int rowsRead = 0;
        // The rows inside the run, a blank value's included: none means a time problem, whatever
        // the rows outside it carry.
        int inside = 0;
        int insideWithValue = 0;
        for (TreeMap<Long, Double> rows : byInstance.values()) {
            if (!rows.isEmpty()) {
                fileFirst = Math.min(fileFirst, rows.firstKey() + source.clockOffsetMs());
                fileLast = Math.max(fileLast, rows.lastKey() + source.clockOffsetMs());
                rowsRead += rows.size();
            }
            for (Map.Entry<Long, Double> r : rows.entrySet()) {
                if (inRun(r.getKey() + source.clockOffsetMs(), window, slack)) {
                    inside++;
                    insideWithValue += r.getValue() != null ? 1 : 0;
                }
            }
        }
        if (rowsRead == 0) {
            return Answer.of(new ArrayList<ServerMetricsPayload.InstanceRecord>(), readAs);
        }
        String file = new File(source.path()).getName();
        if (inside == 0) {
            // Rows exist for exactly this series and every one fell outside the run. That is a
            // time problem - a local time written with Z, a wrong --time-zone, an export of
            // another range, a skewed server clock - and never "the query matched no series":
            // say so, with both spans and the shift between them.
            long[] span = outsideSpan(idx, source.clockOffsetMs(), window, slack);
            return Answer.failed(outsideWindow(file, request.component(), request.metricKey(), rowsRead,
                    fileFirst, fileLast, span[0], span[1], window, slack));
        }
        String rowsInside = file + " has " + inside + " row" + (inside == 1 ? "" : "s") + " for "
                + request.component() + "/" + request.metricKey() + (inside < rowsRead ? " inside the run" : "");
        if (insideWithValue > 0 && readAs != null) {
            // Numbers in the run that the --from conversion left nothing of: a counter's rate is
            // the difference from the row before, and the export holds no row before them. Told
            // "every value is empty or NaN", the reader looked at cells that held numbers.
            return Answer.failed(rowsInside + " holding numbers, and none is left once read as " + readAs.tool()
                    + "'s " + readAs.kind().key() + " " + readAs.metric()
                    + (readAs.kind() == ToolName.Kind.COUNTER
                        ? ": a counter's rate needs the row before each one - export from one step before the run"
                        : ""));
        }
        // Rows inside the run, and not one value among them.
        return Answer.failed(rowsInside
                + " and every value is empty or NaN - the export carries the series' times and no numbers");
    }

    /**
     * The earliest and latest row, clock offset applied, of every series in the file with no row inside
     * the run: the rows a shift would have to move. Rows of series the run does contain are left out, so
     * one series shifted beside in-window ones is measured on its own (E28).
     */
    static long[] outsideSpan(Index idx, long offset, ServerMetricsPayload.Window window, long slack) {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (Map<String, TreeMap<Long, Double>> series : idx.series.values()) {
            long sFirst = Long.MAX_VALUE;
            long sLast = Long.MIN_VALUE;
            boolean inside = false;
            for (TreeMap<Long, Double> rows : series.values()) {
                for (Long t : rows.keySet()) {
                    if (inRun(t + offset, window, slack)) {
                        inside = true;
                        break;
                    }
                }
                if (inside) {
                    break;
                }
                if (!rows.isEmpty()) {
                    sFirst = Math.min(sFirst, rows.firstKey() + offset);
                    sLast = Math.max(sLast, rows.lastKey() + offset);
                }
            }
            if (!inside && sFirst <= sLast) {
                first = Math.min(first, sFirst);
                last = Math.max(last, sLast);
            }
        }
        return new long[] {first, last};
    }

    /**
     * The sentence for a series whose every row is outside the run window: how many rows, the span
     * they cover against the run's, and the shift between them - a whole number of hours or quarter
     * hours named as a likely time zone, anything else as clock skew, each with the flag that moves
     * it. A hint only: nothing is shifted without being asked.
     *
     * <p>One shift for the file: measured over {@code spanFirst..spanLast}, every series that lies
     * wholly outside the run ({@link #outsideSpan}), so each of them is told the same offset. When that
     * span is at least as long as the run, its middle is matched with the run's: an export takes a
     * lead-in before the run and as much after it ("a minute either side"), which start to start counted
     * into the shift - a file 400 s late was told 340 s, and a series that started late more - and the
     * hint says "about". A shorter span is matched start to start.
     */
    static String outsideWindow(String file, String component, String metric, int rows, long first, long last,
                                long spanFirst, long spanLast, ServerMetricsPayload.Window window, long slack) {
        boolean centred = spanFirst <= spanLast && spanLast - spanFirst >= window.getEnd() - window.getStart();
        long raw = centred ? (spanFirst + spanLast) / 2 - (window.getStart() + window.getEnd()) / 2
                : (spanFirst <= spanLast ? spanFirst : first) - window.getStart();
        long shift = Math.round(raw / 1000.0) * 1000L;
        boolean before = last < window.getStart();
        // Rows on both sides and none inside: an export of other runs, the run falling between them.
        // No shift lines that up.
        boolean around = first < window.getStart() && last > window.getEnd();
        StringBuilder sb = new StringBuilder(file).append(" has ").append(rows).append(" row")
                .append(rows == 1 ? "" : "s").append(" for ").append(component).append('/').append(metric)
                .append(" and none inside the run: they run ").append(iso(first)).append(" .. ").append(iso(last))
                .append(", ").append(around ? "before and after" : before ? "before" : "after").append(" the run (")
                .append(iso(window.getStart())).append(" .. ").append(iso(window.getEnd())).append(")");
        if (around) {
            return sb.append(" - export the run's window").toString();
        }
        if (spanFirst < window.getStart() && spanLast > window.getEnd()) {
            // This series lies on one side, but the file's rows outside the run lie on both (an export of
            // other periods): the middle of those is no clock offset.
            return sb.append(" - export the run's window: the file's rows lie before and after it, so no shift"
                    + " lines them up").toString();
        }
        long quarter = 15 * 60_000L;
        long rounded = Math.round((double) shift / quarter) * quarter;
        long tolerance = Math.max(2 * 60_000L, 2 * slack);
        // Time zones span -12h..+14h: a shift a whole number of quarter hours inside that is a zone.
        if (rounded != 0 && Math.abs(rounded) <= 14 * 3_600_000L && Math.abs(shift - rounded) <= tolerance) {
            sb.append(". They start ").append(Durations.format(Math.abs(rounded))).append(before ? " early" : " late")
                    .append(", which is usually a time zone: were local times written with Z? Give the file's")
                    .append(" zone with --time-zone, or move the rows with --clock-offset ").append(rounded > 0 ? "-" : "")
                    .append(Durations.format(Math.abs(rounded)));
        } else if (shift != 0 && Math.abs(shift) <= 14 * 3_600_000L) {
            sb.append(". Export the run's window; if the server's clock is off")
                    .append(centred ? " by about " + Durations.format(Math.abs(shift)) : "")
                    .append(", move the rows with --clock-offset ")
                    .append(shift > 0 ? "-" : "").append(Durations.format(Math.abs(shift)));
        } else {
            sb.append(". The file covers another period: export the run's window");
        }
        return sb.toString();
    }

    private static String iso(long millis) {
        return java.time.Instant.ofEpochMilli(millis).toString();
    }

    /**
     * When no row names {@code component/metric} but some name it with another case, or with
     * {@code .}, {@code _} and {@code -} swapped: the sentence naming both, or null.
     */
    static String nearMiss(Index idx, String component, String metric) {
        // An assert that names no component (INVALID_RULE already) names nothing a row could: an
        // empty answer, as before nearMiss existed - never the NullPointerException that cost the
        // whole report.
        if (component == null || metric == null) {
            return null;
        }
        String wanted = loose(component) + "\u0000" + loose(metric);
        // Each '"' in a cell that is not quoted opens or closes quoting instead of being text, so the row
        // names a query without them (N4).
        String unquoted = metric.indexOf('"') >= 0 ? component + "\u0000" + metric.replace("\"", "") : null;
        for (Map.Entry<String, Map<String, TreeMap<Long, Double>>> e : idx.series.entrySet()) {
            int sep = e.getKey().indexOf('\u0000');
            String c = e.getKey().substring(0, sep);
            String m = e.getKey().substring(sep + 1);
            boolean lostQuotes = e.getKey().equals(unquoted);
            if (lostQuotes || (loose(c) + "\u0000" + loose(m)).equals(wanted)) {
                int rows = 0;
                for (TreeMap<Long, Double> r : e.getValue().values()) {
                    rows += r.size();
                }
                return "no row names component " + component + ", metric " + metric + "; " + rows + " row"
                        + (rows == 1 ? "" : "s") + " name " + c + "/" + m + (lostQuotes
                            ? " - the same text without its '\"': a cell holding '\"' must be quoted, \"...\","
                              + " with each '\"' inside doubled"
                            : " - component and metric must be written exactly as the annotations file writes them");
            }
        }
        return null;
    }

    /**
     * When no row names {@code component/metric} because a row with more cells than its header was read
     * under the start of that metric - the text before its ',' - the sentence naming that row, or null.
     * A metric with a ',' in it, a PromQL query's, splits that way when its cell is not quoted, and the
     * query it was asked by found no rows and no error: a NO_MATCH that said nothing (S4 X08).
     */
    static String splitAt(Index idx, String component, String metric) {
        if (component == null || metric == null || metric.indexOf(',') < 0) {
            return null;
        }
        // An unquoted cell loses its '"' as well (each one toggles quoting), so the fragment is compared
        // with the metric written without them.
        String bare = metric.replace("\"", "");
        for (Map.Entry<String, String> e : idx.wideRows.entrySet()) {
            int sep = e.getKey().indexOf('\u0000');
            String fragment = e.getKey().substring(sep + 1);
            if (e.getKey().substring(0, sep).equals(component) && !fragment.isEmpty() && bare.startsWith(fragment)
                    && bare.substring(fragment.length()).trim().startsWith(",")) {
                return e.getValue() + ": metric \"" + fragment + "\" is the start of " + metric + ", split at its ','"
                        + " - a metric with ',' in it must be quoted: \"...\", each '\"' inside doubled";
            }
        }
        return null;
    }

    private static String loose(String s) {
        return s.toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
    }

    /** How many times a measured step's gap must repeat in one instance to be taken for its interval. */
    static final int MIN_STEP_REPEATS = 3;

    /**
     * The interval the CSV was exported at (decision 12), from the index this reader builds for
     * {@code source} - the same rows, layout, column names and time rules {@link #collect} reads, and
     * the index {@link #collect} then reuses, since a source's step is not part of what the file reads
     * as. Each instance's {@linkplain #intervalOf interval} between its rows with a value (a blank row
     * is a gap, as {@link Coverage#spacingMillis} reads it), counting only rows within {@code window}
     * and one {@code source} step of it (null: every row) - a jittered, sparse or scattered export has
     * none; then the widest over every instance of the series
     * {@code requests} ask for, as {@link Coverage#spacingMillis} takes it, so a coarser series is never
     * counted in slots finer than its rows. A series is found as {@link #collect} finds it - by
     * component and metric key, under a {@code --from} tool's name first when the file has rows under
     * it - and one the collection never reads, however coarse, sets nothing. 0 when nothing was
     * measured, a file the reader refuses included: its refusal follows.
     */
    public long measuredStepMillis(ServerSource source, ServerMetricsPayload.Window window,
                                   Collection<SeriesSource.Request> requests) {
        Index idx = indexFor(source);
        if (idx.fileError != null) {
            return 0;
        }
        List<Map<String, TreeMap<Long, Double>>> measured = new ArrayList<>();
        if (requests == null) {
            measured.addAll(idx.series.values());
        } else {
            Set<String> keys = new LinkedHashSet<>();
            for (SeriesSource.Request request : requests) {
                keys.add(keyRead(idx, request));
            }
            for (String key : keys) {
                if (idx.series.containsKey(key) && !idx.errors.containsKey(key) && !idx.padded.containsKey(key)) {
                    measured.add(idx.series.get(key));
                }
            }
        }
        long slack = Math.max(0, source.stepMillis());
        long widest = 0;
        for (Map<String, TreeMap<Long, Double>> byInstance : measured) {
            for (TreeMap<Long, Double> rows : byInstance.values()) {
                List<Long> times = new ArrayList<>();
                for (Map.Entry<Long, Double> p : rows.entrySet()) {
                    long ts = p.getKey() + source.clockOffsetMs();
                    if (p.getValue() != null && inRun(ts, window, slack)) {
                        times.add(ts);
                    }
                }
                widest = Math.max(widest, intervalOf(times));
            }
        }
        return widest;
    }

    /** {@link #measuredStepMillis(ServerSource, ServerMetricsPayload.Window, Collection)} over every series in the file. */
    long measuredStepMillis(ServerSource source, ServerMetricsPayload.Window window) {
        return measuredStepMillis(source, window, null);
    }

    /**
     * The index key {@link #collect} reads {@code request} under: a {@code --from} tool's name for the
     * metric when the file has rows under it, else the component and metric key.
     */
    private static String keyRead(Index idx, SeriesSource.Request request) {
        String key = seriesKey(request.component(), request.metricKey());
        ToolName name = request.tool() == null ? null : request.toolName();
        if (name != null) {
            String toolKey = seriesKey(request.component(), name.metric());
            if (idx.has(toolKey)) {
                return toolKey;
            }
        }
        return key;
    }

    /**
     * Whether a row at {@code ts} (the clock offset applied) is one {@link #collect} keeps: inside
     * {@code window} or within {@code slack} of it - always, with no window.
     */
    private static boolean inRun(long ts, ServerMetricsPayload.Window window, long slack) {
        return window == null || (ts >= window.getStart() - slack && ts <= window.getEnd() + slack);
    }

    /**
     * The interval one instance's rows ({@code ascending}) were exported at, or 0 when they have none:
     * the most common gap, the smaller on a tie, taken only when it repeats at least
     * {@value #MIN_STEP_REPEATS} times and is at least half of the gaps. On repeats alone a jittered
     * hour at 15 s repeated some millisecond gap often enough to be taken, and 15001ms, which no
     * {@code sustainedFor} is a whole number of, became the collection's step (F6); an export with no
     * one interval for most of its rows has none to measure, and is collected at the default.
     */
    static long intervalOf(List<Long> ascending) {
        // Coverage's own count of the gaps (the smaller on a tie), so the measured step and the
        // spacing the warnings quote are one rule; only the floor is this method's.
        long[] counts = Coverage.spacingCounts(ascending);
        long most = counts[1];
        return most >= MIN_STEP_REPEATS && most * 2 >= counts[2] ? counts[0] : 0;
    }

    /**
     * How far apart the rows of the file last read for this series are, a row with no value
     * included: each instance's {@linkplain Coverage#mostCommonSpacing most common spacing}, the
     * widest over the instances, 0 with none. Only the rows {@link #collect} keeps count: those within
     * {@code window} and one {@code source} step of it, the clock offset applied, as
     * {@link #measuredStepMillis} and {@link #blankRows} count them - an export of a finer period
     * before the run must not speak for the run's rows. Beside {@link Coverage#spacingMillis} over the
     * points, it tells an export at a coarser interval from one at the step whose blank rows left the gaps.
     */
    public long rowSpacingMillis(ServerSource source, ServerMetricsPayload.Window window, String component,
                                 String metric) {
        Map<String, TreeMap<Long, Double>> byInstance = index == null ? null
                : index.series.get(seriesKey(component, metric));
        long slack = Math.max(0, source.stepMillis());
        long widest = 0;
        if (byInstance != null) {
            for (TreeMap<Long, Double> rows : byInstance.values()) {
                List<Long> times = new ArrayList<>();
                for (Long t : rows.keySet()) {
                    long ts = t + source.clockOffsetMs();
                    if (inRun(ts, window, slack)) {
                        times.add(ts);
                    }
                }
                widest = Math.max(widest, Coverage.mostCommonSpacing(times));
            }
        }
        return widest;
    }

    /**
     * How many rows of the file last read name this series and carry no value (an empty cell or
     * NaN): gaps the export wrote, not an interval coarser than the step - so the collector must not
     * advise a coarser --step for them, which would read half-missing data as fully covered. Only the
     * rows {@link #collect} keeps count, as in {@link #rowSpacingMillis}.
     */
    public int blankRows(ServerSource source, ServerMetricsPayload.Window window, String component, String metric) {
        Map<String, TreeMap<Long, Double>> byInstance = index == null ? null
                : index.series.get(seriesKey(component, metric));
        long slack = Math.max(0, source.stepMillis());
        int blank = 0;
        if (byInstance != null) {
            for (TreeMap<Long, Double> rows : byInstance.values()) {
                for (Map.Entry<Long, Double> p : rows.entrySet()) {
                    blank += p.getValue() == null && inRun(p.getKey() + source.clockOffsetMs(), window, slack) ? 1 : 0;
                }
            }
        }
        return blank;
    }

    /**
     * One line per series of the file last read that repeats a time with another value - the later
     * row is kept, and this says so (two hosts without an instance column, an export appended twice).
     */
    public List<String> duplicateNotes() {
        return index == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(index.duplicates.values()));
    }

    /**
     * The (component, metric) pairs the long file last read has rows for that {@link #collect} was
     * never asked for, as {@code component/metric}, in the file's order - so a name the annotations
     * spell otherwise is listed rather than silently skipped. A wide file lists its columns through
     * {@link #columnsNotAskedFor} instead.
     */
    public List<String> seriesNotAskedFor() {
        if (index == null || !index.columnsByKey.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String key : index.series.keySet()) {
            if (!asked.contains(key)) {
                out.add(key.replace('\u0000', '/'));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private Index indexFor(ServerSource source) {
        // Everything that changes what the file reads as: the same path read wide, in another zone,
        // by other column names or as one component's, is another index.
        String key = source.path() + "\u0000" + source.csvLayout() + "\u0000" + source.csvColumns()
                + "\u0000" + source.timeZone() + "\u0000" + source.csvColumnSpec() + "\u0000" + source.csvComponent();
        if (index == null || !key.equals(indexedKey)) {
            indexedKey = key;
            index = read(source);
            asked.clear();
        }
        return index;
    }

    private File resolve(String path) {
        File file = new File(path);
        return !file.isAbsolute() && baseDir != null ? new File(baseDir, path) : file;
    }

    private Index read(ServerSource source) {
        File file = resolve(source.path());
        if (!file.isFile()) {
            return Index.failed("CSV file does not exist: " + source.path());
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            return Index.failed("CSV file could not be read: " + source.path() + " - " + e.getMessage());
        }
        if (source.csvLayout() != ServerSource.CsvLayout.WIDE) {
            if (WideCsv.isUtf16(bytes)) {
                return Index.failed(file.getName() + " is UTF-16 (a spreadsheet's 'Unicode Text') - save it as"
                        + " 'CSV UTF-8'");
            }
            CsvColumns columns;
            try {
                columns = source.csvColumnSpec() == null ? CsvColumns.none() : CsvColumns.parse(source.csvColumnSpec());
            } catch (IllegalArgumentException e) {
                return Index.failed(e.getMessage());   // wir-collect refuses it first; a source built by hand may not
            }
            return parse(new String(bytes, StandardCharsets.UTF_8), file.getName(), source.timeZone(), columns,
                    source.csvComponent());
        }
        // Grafana's "Download for Excel": tab-separated UTF-16, which read as UTF-8 is a header of
        // NULs and one column. Said by name, with the button that gives a file this reads.
        if (WideCsv.isUtf16(bytes)) {
            return Index.failed(file.getName() + " is UTF-16 - that is Grafana's 'Download for Excel'; use Download CSV");
        }
        WideCsv.ColumnMap map = null;
        if (source.csvColumns() != null) {
            File mapFile = resolve(source.csvColumns());
            if (!mapFile.isFile()) {
                return Index.failed("the --columns file does not exist: " + mapFile.getAbsolutePath());
            }
            try {
                map = WideCsv.ColumnMap.parse(new String(Files.readAllBytes(mapFile.toPath()), StandardCharsets.UTF_8),
                        mapFile.getName());
            } catch (IOException e) {
                return Index.failed("the --columns file could not be read: " + mapFile.getAbsolutePath()
                        + " - " + e.getMessage());
            }
            if (map.error() != null) {
                return Index.failed(map.error());
            }
        }
        return WideCsv.parse(new String(bytes, StandardCharsets.UTF_8), file.getName(), map, source.timeZone());
    }

    /**
     * A long file, the parse separated from the file; {@code zone} is the {@code --time-zone} a
     * zone-less time is read in, or null; {@code columns} what the file calls each column
     * ({@code --csv-columns}, {@link CsvColumns#none} for the reader's own names); {@code component}
     * the component every row is in ({@code --component}), or null: each row names its own.
     *
     * <p>InfluxDB v2's annotated CSV is read whenever it is met, with no flag: a line whose first cell
     * starts with {@code #} is skipped, and the file is one or more blocks - a table whose columns
     * differ starts a new block after a blank line, with a header of its own - so each block's columns
     * are found by its own header. A new header is the line after InfluxDB's own annotations
     * ({@code #group}, {@code #datatype}, {@code #default}), blank line or not, or a line after a
     * blank line that names the time column; any other line is a row of the block it is in, so a
     * blank line between two runs of rows, or a {@code #} comment, reads as it always did. A header's
     * empty cell (the annotated CSV's leading column) is a column nobody maps.
     */
    static Index parse(String text, String name, ZoneId zone, CsvColumns columns, String component) {
        text = withoutBom(text);
        Index idx = new Index();
        Header header = null;
        // A header is due at the top of the file, and after InfluxDB's annotations.
        boolean headerDue = true;
        boolean afterBlank = false;
        boolean annotated = false;
        for (Row row : rows(text)) {
            if (row.unclosedQuote) {
                // Every line after an opening quote that never closes was read as one cell, named no
                // series, and vanished - leaving only "coverage below the floor" to explain it.
                return Index.failed(name + " line " + row.line + ": a quoted cell opens here and never closes,"
                        + " so every line after it reads as one cell - close the quote or remove it");
            }
            if (row.blank()) {
                afterBlank = header != null;
                continue;
            }
            if (row.annotation()) {
                annotated = true;
                headerDue |= row.startsBlock();
                continue;
            }
            if (headerDue || (afterBlank && header.namesTimeColumn(row))) {
                String problem = Header.problem(row, name, columns, component);
                if (problem != null) {
                    // A header that is one cell holding ';' or tabs is another delimiter, which no
                    // flag here reads: said by name, as the wide reader says it.
                    String only = row.cells.size() == 1 ? row.cells.get(0) : "";
                    if (only.indexOf(';') >= 0 || only.indexOf('	') >= 0) {
                        return Index.failed(name + " line " + row.line + ": the delimiter is "
                                + (only.indexOf(';') >= 0 ? "';'" : "a tab") + " and only ',' is read"
                                + " - export with commas (a spreadsheet's 'CSV UTF-8, comma delimited')");
                    }
                    return Index.failed(problem);
                }
                header = new Header(row, columns, component);
                headerDue = false;
                afterBlank = false;
                continue;
            }
            afterBlank = false;
            String rowComponent = component != null ? component : row.cell(header.component).trim();
            String metric = header.metric(row);
            if (rowComponent.isEmpty() || metric.isEmpty()) {
                continue;   // names no series: nothing the file could have asked for
            }
            String key = seriesKey(rowComponent, metric);
            String where = name + " line " + row.line + ": ";
            // String.trim leaves a no-break space, a zero-width or bidi character: a cell padded with
            // one is a different text from the name it was meant to be, and its rows were read and
            // never looked up - the series EMPTY, NO_MATCH, nothing said (F8-residual). Its rows stay
            // indexed under the text as written; the series the bare name asks for is failed by line.
            String bare = KpiUsability.withoutInvisibleEnds(metric);
            if (!bare.isEmpty() && !bare.equals(metric)) {
                String bareKey = seriesKey(rowComponent, bare);
                if (!idx.padded.containsKey(bareKey) && !idx.errors.containsKey(bareKey)) {
                    idx.padded.put(bareKey, where + "metric \"" + bare + "\" has an invisible character at its "
                            + KpiUsability.invisibleEnds(metric) + " that is not trimmed like a plain space, so no row"
                            + " written that way is read as this series - delete it");
                }
            }
            if (idx.errors.containsKey(key)) {
                continue;   // the first bad line is the one reported
            }
            // A row with more cells than its header has an unquoted ',' inside a cell - most often a
            // decimal comma, "90,9", which read cell by cell is 90 and a stray 9: a wrong number with
            // nothing said. Trailing empty cells (a trailing comma) are not more cells.
            int written = row.cells.size();
            while (written > header.width && row.cells.get(written - 1).trim().isEmpty()) {
                written--;
            }
            if (written > header.width) {
                idx.wideRows.putIfAbsent(key, name + " line " + row.line);
                idx.errors.put(key, where + "has " + written + " cells where the header has " + header.width
                        + " - an unquoted ',' inside a value? Write numbers with a '.' decimal point and no"
                        + " thousands separator, or quote the cell");
                continue;
            }
            if (row.cells.size() <= header.value) {
                idx.errors.put(key, where + "has " + row.cells.size() + " cells and stops before the value"
                        + " column - a truncated or hand-edited row");
                continue;
            }
            String time = row.cell(header.time).trim();
            Long ts = parseTime(time, zone);
            if (ts == null) {
                idx.errors.put(key, where + timeError(time, zone));
                continue;
            }
            String v = row.cell(header.value).trim();
            Double value;
            if (v.isEmpty() || v.equalsIgnoreCase("nan")) {
                value = null;   // a gap, not a zero
            } else {
                try {
                    value = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    idx.errors.put(key, where + "value \"" + v + "\" is not a number - write it with a"
                            + " '.' decimal point and no thousands separator");
                    continue;
                }
                if (value.isInfinite()) {
                    idx.errors.put(key, where + "value \"" + v + "\" is not a finite number");
                    continue;
                }
            }
            String instance = header.instance == null ? "" : row.cell(header.instance).trim();
            Double previous = idx.series.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(instance, k -> new TreeMap<>())
                    .put(ts, value);   // a repeated timestamp keeps the last row (§7.3)
            if (previous != null && value != null && !previous.equals(value) && !idx.duplicates.containsKey(key)) {
                // Two values for one instant: two hosts exported without an instance column, or one
                // export appended to another. The last row wins, but not in silence.
                idx.duplicates.put(key, where + "repeats time \"" + time + "\" with another value (" + Durations.trim(previous)
                        + ", then " + Durations.trim(value) + ") - the later row is kept; two hosts without an"
                        + " instance column, or an export appended twice?");
            }
        }
        if (header == null) {
            // An InfluxDB query that matched nothing writes its annotations and no rows.
            return Index.failed(annotated ? name + " has annotation lines and no header - the export holds no rows"
                    : name + " is empty - it needs a header row: " + Header.wanted(columns, component));
        }
        return idx;
    }

    /** How many of a header's cells a refusal quotes before it says how many more there are. */
    static final int HEADER_CELLS_LISTED = 20;

    /**
     * Where one block's header puts each column this reader looks for. {@code metric} is one index,
     * or two for a {@code --csv-columns metric=a+b} join; {@code instance} is null when the file has
     * none (it is optional unless mapped), and {@code component} when {@code --component} names it.
     */
    static final class Header {
        final int time;
        final Integer component;
        final int[] metric;
        final Integer instance;
        final int value;
        /** How many cells the header has: a data row with more has an unquoted ',' inside a cell. */
        final int width;
        /** The time column's name, as a cell of a later header would write it: trimmed, lower case. */
        private final String timeName;

        /** A header {@link #problem} has passed. */
        Header(Row row, CsvColumns columns, String fixedComponent) {
            Map<String, Integer> col = indexOf(row);
            time = col.get(lower(columns.columnsFor("time").get(0)));
            component = fixedComponent != null ? null : col.get(lower(columns.columnsFor("component").get(0)));
            List<String> metricColumns = columns.columnsFor("metric");
            metric = new int[metricColumns.size()];
            for (int i = 0; i < metric.length; i++) {
                metric[i] = col.get(lower(metricColumns.get(i)));
            }
            instance = col.get(lower(columns.columnsFor("instance").get(0)));
            value = col.get(lower(columns.columnsFor("value").get(0)));
            timeName = lower(columns.columnsFor("time").get(0));
            width = row.cells.size();
        }

        /** A row's metric: its one cell, or a join's two written {@code a.b} - empty when either side is. */
        String metric(Row row) {
            if (metric.length == 1) {
                return row.cell(metric[0]).trim();
            }
            String measurement = row.cell(metric[0]).trim();
            String field = row.cell(metric[1]).trim();
            return measurement.isEmpty() || field.isEmpty() ? "" : measurement + "." + field;
        }

        /** Whether {@code row} is a header too: one of its cells is the time column's name, which no time is. */
        boolean namesTimeColumn(Row row) {
            for (String cell : row.cells) {
                if (lower(cell).equals(timeName)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Why {@code row} cannot be this reader's header, or null. A component column beside
         * {@code --component} is refused - the component said in two places - and so is a missing
         * column: one {@code --csv-columns} maps by its name and key, one of the reader's own by its
         * name, the header the file has quoted after either so the right name can be given. A mapped
         * instance is required; the reader's own is optional.
         */
        static String problem(Row row, String name, CsvColumns columns, String fixedComponent) {
            Map<String, Integer> col = indexOf(row);
            String where = name + " line " + row.line + ": ";
            if (fixedComponent != null) {
                // A column of that name another key maps is that key's, not a component column.
                String own = lower(columns.columnsFor("component").get(0));
                boolean claimed = false;
                for (String key : CsvColumns.KEYS) {
                    if (!key.equals("component") && columns.maps(key)) {
                        for (String c : columns.columnsFor(key)) {
                            claimed |= lower(c).equals(own);
                        }
                    }
                }
                if (col.containsKey(own) && !claimed) {
                    return where + "the header has a component column, and --component " + fixedComponent
                            + " names the component too - say it in one place: leave out --component, or the column";
                }
            }
            List<String> missing = new ArrayList<>();
            List<String> ownMissing = new ArrayList<>();
            boolean mappedMissing = false;
            for (String key : CsvColumns.KEYS) {
                if ((key.equals("component") && fixedComponent != null)
                        || (key.equals("instance") && !columns.maps(key))) {
                    continue;
                }
                for (String c : columns.columnsFor(key)) {
                    if (col.containsKey(lower(c))) {
                        continue;
                    }
                    if (columns.maps(key)) {
                        missing.add("\"" + c + "\" (--csv-columns " + key + ")");
                        mappedMissing = true;
                    } else {
                        missing.add("\"" + c + "\"");
                        ownMissing.add(key);
                    }
                }
            }
            if (missing.isEmpty()) {
                return null;
            }
            String header = " - the header is: " + quotedCells(row);
            String flags = ownMissing.isEmpty() ? "" : "; --csv-columns maps a column named otherwise"
                    + (ownMissing.contains("component") ? ", and --component gives every row a component" : "");
            if (mappedMissing) {
                return where + "the header has no column " + String.join(" or ", missing) + header + flags;
            }
            return where + "the header has no " + String.join(", ", ownMissing) + " column - it needs time, "
                    + (fixedComponent == null ? "component, " : "") + "metric and value (instance is optional)"
                    + header + flags;
        }

        /**
         * The header this reader wants, as a file would write it: each key's column - the SPEC's, or
         * the key's own name - in {@link CsvColumns#KEYS}' order, the component left out when
         * {@code --component} gives it. With no flags, {@code time,component,metric,instance,value}.
         */
        static String wanted(CsvColumns columns, String fixedComponent) {
            List<String> names = new ArrayList<>();
            for (String key : CsvColumns.KEYS) {
                if (!(key.equals("component") && fixedComponent != null)) {
                    names.addAll(columns.columnsFor(key));
                }
            }
            return String.join(",", names);
        }

        /** A header's cells by name, trimmed and in lower case; the first of a repeated name wins. */
        private static Map<String, Integer> indexOf(Row row) {
            Map<String, Integer> col = new LinkedHashMap<>();
            for (int i = 0; i < row.cells.size(); i++) {
                col.putIfAbsent(lower(row.cells.get(i)), i);
            }
            return col;
        }

        private static String quotedCells(Row row) {
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(row.cells.size(), HEADER_CELLS_LISTED);
            for (int i = 0; i < shown; i++) {
                sb.append(i > 0 ? ", " : "").append('"').append(row.cells.get(i).trim()).append('"');
            }
            if (row.cells.size() > shown) {
                sb.append(" and ").append(row.cells.size() - shown).append(" more");
            }
            return sb.toString();
        }

        private static String lower(String text) {
            return text.trim().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * ISO 8601 with an offset or Z, or epoch milliseconds from {@link #EPOCH_MILLIS_FLOOR} up; and,
     * when {@code zone} is given, a local date and time ({@code 2026-10-02 14:02:15}, a {@code T}
     * or a space between, seconds and a fraction optional) read in that zone. {@code null} for
     * anything else, a zone-less time without a zone and epoch seconds included - and a local time
     * the zone's daylight-saving change repeats or skips, which is two moments or none.
     */
    static Long parseTime(String t, ZoneId zone) {
        if (t.isEmpty()) {
            return null;
        }
        if (t.chars().allMatch(Character::isDigit)) {
            try {
                long ms = Long.parseLong(t);
                return ms < EPOCH_MILLIS_FLOOR || ms >= EPOCH_MILLIS_CEILING ? null : ms;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return OffsetDateTime.parse(t).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            // not an instant: perhaps a local time the zone is given for
        }
        if (zone != null && LOCAL_TIME.matcher(t).matches()) {
            // One moment only: atZone would take the earlier offset in a fall-back hour, so its second
            // pass lands on the first, and shift a spring-forward time on - a guess an hour wide.
            LocalDateTime local = localTime(t);
            return local == null || zone.getRules().getValidOffsets(local).size() != 1 ? null
                    : local.atZone(zone).toInstant().toEpochMilli();
        }
        return null;
    }

    /** A local date and time as {@link #LOCAL_TIME} writes one, or null when it names no day or hour. */
    private static LocalDateTime localTime(String t) {
        try {
            return LocalDateTime.parse(t.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Why {@code t} is not a time {@link #parseTime} reads, as the sentence after the line. */
    static String timeError(String t, ZoneId zone) {
        return t.isEmpty() ? "time is empty" : "time \"" + t + "\" " + timeProblem(t, zone);
    }

    private static String timeProblem(String t, ZoneId zone) {
        // Epoch seconds by name: read as milliseconds they are January 1970, outside every window,
        // and the series comes back EMPTY with no line to say why. By value, not by length, so a
        // number parseTime refuses below the floor is called seconds however many zeros lead it.
        if (t.matches("\\d+(\\.\\d+)?")) {
            String whole = t.replaceFirst("\\..*", "").replaceFirst("^0+", "");
            if (whole.length() < 19 && (whole.isEmpty() ? 0 : Long.parseLong(whole)) < EPOCH_MILLIS_FLOOR) {
                return "looks like epoch seconds - this reader takes epoch milliseconds";
            }
            if (whole.length() >= 15) {
                return "looks like epoch microseconds or nanoseconds - this reader takes epoch milliseconds";
            }
        }
        // A local time is refused by name: a silent zone guess shifts the whole window.
        if (LOCAL_TIME.matcher(t).matches()) {
            if (zone == null) {
                return "has no time zone - add one (2026-10-02T14:02:15+03:00, or a Z for UTC),"
                        + " write epoch milliseconds, or give the file's zone with --time-zone";
            }
            LocalDateTime local = localTime(t);
            if (local != null && zone.getRules().getValidOffsets(local).size() != 1) {
                return "is not one moment in " + zone.getId() + ", whose clocks change then - give --time-zone as"
                        + " the offset the file was written in (such as " + zone.getRules().getOffset(local).getId()
                        + "), or write epoch milliseconds";
            }
        }
        return "is not a time - write ISO 8601 with a zone (2026-10-02T14:02:15+03:00) or epoch milliseconds";
    }

    static String seriesKey(String component, String metric) {
        return component + "\u0000" + metric;
    }

    /** The text without a UTF-8 byte order mark, which a spreadsheet writes and nothing reads. */
    static String withoutBom(String text) {
        return text.startsWith(String.valueOf((char) 0xFEFF)) ? text.substring(1) : text;
    }

    // ---- RFC 4180, as much as a metrics export needs ------------------------------------

    static final class Row {
        final int line;
        final List<String> cells;
        /** True on the last row when the file ended inside a quoted cell that opened on {@link #line}. */
        boolean unclosedQuote;

        Row(int line, List<String> cells) {
            this.line = line;
            this.cells = cells;
        }

        String cell(int i) {
            return i < cells.size() ? cells.get(i) : "";
        }

        boolean blank() {
            for (String c : cells) {
                if (!c.trim().isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /** A line that is not data: its first cell starts with {@code #} - an InfluxDB annotation, or a comment. */
        boolean annotation() {
            return !cells.isEmpty() && cells.get(0).trim().startsWith("#");
        }

        /**
         * One of InfluxDB's own annotations, {@code #group}, {@code #datatype} or {@code #default},
         * which stand before a block's header and nowhere else: the next line that is not one is a header.
         */
        boolean startsBlock() {
            String first = cells.isEmpty() ? "" : cells.get(0).trim().toLowerCase(Locale.ROOT);
            return first.equals("#group") || first.equals("#datatype") || first.equals("#default");
        }
    }

    /** Rows with the line each starts on; quoted cells may hold commas, quotes ("") and newlines. */
    static List<Row> rows(String text) {
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int rowLine = 1;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                rows.add(new Row(rowLine, cells));
                cells = new ArrayList<>();
                any = false;
                line++;
                rowLine = line;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (any || cell.length() > 0) {
            cells.add(cell.toString());
            Row last = new Row(rowLine, cells);
            last.unclosedQuote = quoted;
            rows.add(last);
        }
        // A trailing newline leaves no empty row behind, and a file of blank lines has no header.
        while (!rows.isEmpty() && rows.get(rows.size() - 1).blank() && rows.size() > 1) {
            rows.remove(rows.size() - 1);
        }
        if (rows.size() == 1 && rows.get(0).blank()) {
            rows.clear();
        }
        return rows;
    }

    /**
     * What one read of the file found: the series by key, and the first bad line per series. A
     * wide file also leaves the headers of the columns that named each series, the columns nothing
     * named, and the map's columns it did not have.
     */
    static final class Index {
        final Map<String, Map<String, TreeMap<Long, Double>>> series = new LinkedHashMap<>();
        final Map<String, String> errors = new LinkedHashMap<>();
        /**
         * By the key of the name a padded metric cell was meant to be, the first such line
         * (F8-residual) - recorded only while that series had no bad line yet.
         */
        final Map<String, String> padded = new LinkedHashMap<>();
        final Map<String, List<String>> columnsByKey = new LinkedHashMap<>();
        final List<String> unmapped = new ArrayList<>();
        final List<String> mapMissing = new ArrayList<>();
        /** By series key, the first line that repeated a time with another value: said, never fatal. */
        final Map<String, String> duplicates = new LinkedHashMap<>();
        /**
         * By the key a row with more cells than its header was read under, that row ("m.csv line 5"). When
         * the ',' was inside the metric cell - an unquoted PromQL query - that key holds only the query's
         * start, and the lookup of the whole query is pointed here ({@link #splitAt}).
         */
        final Map<String, String> wideRows = new LinkedHashMap<>();
        String fileError;

        static Index failed(String error) {
            Index idx = new Index();
            idx.fileError = error;
            return idx;
        }

        /** Whether the file has rows for this series: points, or a bad line that failed it - a padded name's included. */
        boolean has(String key) {
            return series.containsKey(key) || errors.containsKey(key) || padded.containsKey(key);
        }
    }
}
