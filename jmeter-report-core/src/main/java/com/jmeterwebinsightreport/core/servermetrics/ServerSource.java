package com.jmeterwebinsightreport.core.servermetrics;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where a collection's series come from: the one source {@code wir-collect}'s command line names.
 *
 * <p>Never the annotations file (plan C8). The file says what is expected of which component, the
 * collector's flags say where the actual is fetched, and a report reads only the payload the
 * collector wrote. {@code WirCollect.collectionSource} builds this from the flags, under
 * {@link #COLLECTION_ID}, and {@link ServerMetricsConfig#withSource} puts it beside the mapped
 * block, so every assert's series is looked up under that one id. A {@code sources} key in the file
 * is retired, and refused with a sentence naming the flags.
 *
 * <p>Three types, exactly one per collection, each a flag:
 * <ul>
 *   <li>{@code prometheus} ({@code --prometheus-url URL [--prometheus-token-env NAME]}): a
 *       {@code query_range} endpoint, queried live.</li>
 *   <li>{@code file} ({@code --recording DIR}): recorded {@code query_range} responses replayed
 *       from disk, one per query - the offline path, and the deterministic one the suites use.</li>
 *   <li>{@code csv} ({@code --csv FILE}): a file exported from any tool, {@link CsvLayout#LONG}
 *       unless {@code --layout wide}. Beside it, each only when given: {@link #fromTool()}
 *       ({@code --from TOOL}, whose names and kind of number the file holds), {@link #csvColumns()}
 *       ({@code --columns}, a wide file's columns named by their exact header),
 *       {@link #timeZone()} ({@code --time-zone}), {@link #csvColumnSpec()} ({@code --csv-columns},
 *       a long file's columns as another tool named them) and {@link #csvComponent()}
 *       ({@code --component}, the one component every row belongs to).</li>
 * </ul>
 * {@code --step} and {@code --clock-offset} apply to all three. There is no live Datadog, Elastic
 * or Grafana backend: their data is read as a CSV export.
 *
 * <p><b>Secrets by reference only.</b> The token is named by its environment variable
 * ({@code --prometheus-token-env NAME}, carried as {@code bearerTokenEnv}), never given as a value,
 * and the collector refuses a URL carrying credentials: the payload records the URL it fetched and
 * travels into every report. Which references exist is decided here, once - {@link #AUTH_KEYS}
 * and {@link #authProblem} - and the Prometheus reader asks before every request, so a source built
 * some other way cannot ignore a reference in silence and fail with a 401 that sends the reader to
 * their credentials.
 *
 * <p>{@link #unusable} keeps a source that cannot be used under its id, carrying the problem: the
 * collector records it FAILED with that sentence rather than dropping it, which would have turned
 * every series on it into a different fault with a different fix.
 */
public final class ServerSource {

    public enum Type {
        PROMETHEUS("prometheus"),
        FILE("file"),
        /**
         * A CSV export (plans/server-metrics-backends.md §5): {@code wir-collect --csv}, long or,
         * with {@code --layout wide}, a time column and one column per series ({@link CsvLayout}).
         */
        CSV("csv");

        private final String key;

        Type(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Type fromKey(String key) {
            if (key == null) return null;
            String k = key.trim().toLowerCase(Locale.ROOT);
            for (Type t : values()) {
                if (t.key.equals(k)) return t;
            }
            return null;
        }

        public static String vocabulary() {
            return "prometheus, file, csv";
        }
    }

    /**
     * How a CSV lays its points out ({@code wir-collect --csv FILE --layout long|wide}): {@code long}
     * is one row per point, {@code time,component,metric,instance,value}; {@code wide} is a time
     * column and one column per series, the shape of Grafana's Inspect &gt; Data &gt; Download CSV
     * with "Series joined by time".
     */
    public enum CsvLayout {
        LONG("long"),
        WIDE("wide");

        private final String key;

        CsvLayout(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** The layout {@code text} names, trimmed and in any case, or null. */
        public static CsvLayout fromKey(String text) {
            if (text == null) return null;
            String k = text.trim().toLowerCase(Locale.ROOT);
            for (CsvLayout l : values()) {
                if (l.key.equals(k)) return l;
            }
            return null;
        }

        public static String vocabulary() {
            return "long, wide";
        }
    }

    /** The floor for a source that declares no step. */
    public static final long DEFAULT_STEP_MILLIS = 15_000;

    /**
     * The one source a collection has (plan C8): connection details are {@code wir-collect}
     * flags, never the annotations file, so every assert names this id and the collector injects
     * the source it was given under it ({@link ServerMetricsConfig#withSource}). The payload's
     * series and the evaluator's lookup both key on it, so it is the same for every backend.
     */
    public static final String COLLECTION_ID = "metrics";

    /** The auth references a {@code prometheus} source may carry, and nothing else. */
    public static final Set<String> AUTH_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("bearerTokenEnv", "usernameEnv", "passwordEnv")));

    /**
     * Why a source's auth references cannot be used, or {@code null} when they can. The one
     * definition, asked by the Prometheus reader before it fetches each series: the annotations
     * file declares no source to check at load time (C8), and {@code wir-collect} only ever sets
     * {@code bearerTokenEnv}, so this is what stops a source built some other way from ignoring a
     * reference in silence.
     */
    public static String authProblem(Map<String, String> authEnv) {
        if (authEnv == null || authEnv.isEmpty()) {
            return null;
        }
        for (String key : authEnv.keySet()) {
            if (!AUTH_KEYS.contains(key)) {
                return "auth." + key + " is not understood - one of bearerTokenEnv, or usernameEnv + passwordEnv";
            }
        }
        if (authEnv.containsKey("usernameEnv") != authEnv.containsKey("passwordEnv")) {
            return "auth needs both usernameEnv and passwordEnv";
        }
        return null;
    }

    private final String id;
    private final Type type;
    private final String url;
    private final String path;
    private final long stepMillis;
    private final long clockOffsetMs;
    private final Map<String, String> authEnv;
    private final String fromTool;
    private final CsvLayout csvLayout;
    private final String csvColumns;
    private final ZoneId timeZone;
    private final String csvColumnSpec;
    private final String csvComponent;
    private final String problem;

    private ServerSource(String id, Type type, String url, String path, long stepMillis,
                         long clockOffsetMs, Map<String, String> authEnv, String fromTool,
                         CsvLayout csvLayout, String csvColumns, ZoneId timeZone, String csvColumnSpec,
                         String csvComponent, String problem) {
        this.id = id;
        this.type = type;
        this.url = url;
        this.path = path;
        this.stepMillis = stepMillis;
        this.clockOffsetMs = clockOffsetMs;
        this.authEnv = Collections.unmodifiableMap(new LinkedHashMap<>(authEnv));
        this.fromTool = fromTool;
        this.csvLayout = csvLayout;
        this.csvColumns = csvColumns;
        this.timeZone = timeZone;
        this.csvColumnSpec = csvColumnSpec;
        this.csvComponent = csvComponent;
        this.problem = problem;
    }

    /** A Prometheus source. {@code authEnv} maps an auth field to the env var that holds it. */
    public static ServerSource prometheus(String id, String url, long stepMillis, long clockOffsetMs,
                                          Map<String, String> authEnv) {
        return new ServerSource(id, Type.PROMETHEUS, url, null, stepMillis, clockOffsetMs,
                authEnv == null ? Collections.<String, String>emptyMap() : authEnv, null, null, null, null, null, null,
                null);
    }

    /** A recorded-response source rooted at {@code path}. */
    public static ServerSource file(String id, String path, long stepMillis, long clockOffsetMs) {
        return new ServerSource(id, Type.FILE, null, path, stepMillis, clockOffsetMs,
                Collections.<String, String>emptyMap(), null, null, null, null, null, null, null);
    }

    /** A CSV export at {@code path}: rows of time, component, metric, instance, value. */
    public static ServerSource csv(String id, String path, long stepMillis, long clockOffsetMs) {
        return csv(id, path, stepMillis, clockOffsetMs, null);
    }

    /**
     * A CSV exported from {@code fromTool} ({@code wir-collect --csv FILE --from TOOL}): its metric
     * column holds that tool's names for the catalogue's metrics, and its values that tool's kind of
     * number. Null is a file in the catalogue's own names and unit, as {@link #csv(String, String,
     * long, long)} reads one.
     */
    public static ServerSource csv(String id, String path, long stepMillis, long clockOffsetMs, String fromTool) {
        return csv(id, path, stepMillis, clockOffsetMs, fromTool, CsvLayout.LONG, null, null);
    }

    /**
     * A CSV read as {@code layout} ({@code --layout}; null is {@link CsvLayout#LONG}). For a wide
     * file, {@code columns} is the {@code --columns} map — a CSV of
     * {@code column,component,metric[,instance]} naming each export column by its exact header —
     * or null, and each column is named by its header alone. {@code timeZone} ({@code --time-zone})
     * is the zone a time written with no zone is in, or null, and such a time is refused.
     */
    public static ServerSource csv(String id, String path, long stepMillis, long clockOffsetMs, String fromTool,
                                   CsvLayout layout, String columns, ZoneId timeZone) {
        return csv(id, path, stepMillis, clockOffsetMs, fromTool, layout, columns, timeZone, null, null);
    }

    /**
     * As above, for a long file whose columns another tool named ({@code --csv-columns SPEC}) and
     * whose rows carry no component ({@code --component ID}). {@code columnSpec} is the SPEC,
     * normalised ({@code CsvColumns#normalised}), or null: the columns are called
     * {@code time,component,metric,instance,value}. {@code component} is the component every row
     * belongs to, or null: each row names its own.
     */
    public static ServerSource csv(String id, String path, long stepMillis, long clockOffsetMs, String fromTool,
                                   CsvLayout layout, String columns, ZoneId timeZone, String columnSpec,
                                   String component) {
        return new ServerSource(id, Type.CSV, null, path, stepMillis, clockOffsetMs,
                Collections.<String, String>emptyMap(), fromTool, layout == null ? CsvLayout.LONG : layout,
                columns, timeZone, columnSpec, component, null);
    }

    /**
     * A source the loader could not use, kept under its id so KPIs naming it get the right
     * sentence. {@code problem} says what is wrong and what to write instead.
     */
    public static ServerSource unusable(String id, String problem) {
        return new ServerSource(id, null, null, null, DEFAULT_STEP_MILLIS, 0,
                Collections.<String, String>emptyMap(), null, null, null, null, null, null, problem);
    }

    public String id() { return id; }

    /** {@code null} for an unusable source. */
    public Type type() { return type; }

    public String url() { return url; }
    public String path() { return path; }
    public long stepMillis() { return stepMillis; }

    /** Applied on read and always shown — a 30 s skew misattributes every spike in silence. */
    public long clockOffsetMs() { return clockOffsetMs; }

    /** Auth field → environment variable name. Never a value. */
    public Map<String, String> authEnv() { return authEnv; }

    /**
     * The tool a CSV was exported from — one of {@code Catalogue.TOOLS} — or null: a file in the
     * catalogue's own names, and every source that is not a CSV.
     */
    public String fromTool() { return fromTool; }

    /** How a CSV lays its points out — {@link CsvLayout#LONG} unless told — or null for every other source. */
    public CsvLayout csvLayout() { return csvLayout; }

    /** A wide CSV's {@code --columns} map, as typed, or null: each column is named by its header. */
    public String csvColumns() { return csvColumns; }

    /** The zone a CSV's zone-less time is read in ({@code --time-zone}), or null: such a time is refused. */
    public ZoneId timeZone() { return timeZone; }

    /**
     * A long CSV's {@code --csv-columns} SPEC, normalised — {@code time=_time,metric=_field,value=_value}
     * — or null: its columns have this reader's own names.
     */
    public String csvColumnSpec() { return csvColumnSpec; }

    /** The component every row of a long CSV belongs to ({@code --component}), or null: each row names its own. */
    public String csvComponent() { return csvComponent; }

    /** Why this source cannot be used, or {@code null}. */
    public String problem() { return problem; }

    public boolean usable() { return problem == null; }

    @Override
    public String toString() {
        return id + (problem != null ? " (unusable: " + problem + ")" : " [" + type.key() + "]");
    }
}
