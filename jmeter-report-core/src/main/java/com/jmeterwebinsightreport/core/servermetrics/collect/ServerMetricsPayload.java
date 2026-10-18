package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.jmeterwebinsightreport.core.servermetrics.CollectionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code server-metrics.json} — what {@code wir-collect} writes and the report generator reads.
 *
 * <p>The canvas named the artifact and never gave it a shape; this is the shape. Plain beans so
 * Jackson reads and writes them without custom code, and so the file is legible to a person
 * looking at why a KPI was not judged: every series carries its own {@link CollectionStatus},
 * its coverage, and the query exactly as it was sent (secrets redacted — see {@code Redaction}).
 *
 * <p>Series are keyed by {@code (source, resolvedQuery)}. That key is what the report's
 * "Validate query" will look up — an exact match against what was collected, never an estimate
 * — and what makes collecting each distinct query once, however many KPIs share it, the
 * natural unit.
 *
 * <p>Identity: {@code runId} must equal the report's own, or the generator refuses the payload
 * (a foreign payload drawn under this run's headings is the bug {@code RunIdentity} exists to
 * stop). {@code collectionId} fingerprints the collection itself — sources, resolved queries,
 * window, time — so a re-collection against the same run is distinguishable from the first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerMetricsPayload {

    /** The schema this class writes. A reader refuses anything else rather than guessing. */
    public static final String SCHEMA = "wir-server-metrics/1";

    private String schema = SCHEMA;
    private String runId;
    private String collectionId;
    private String environment;
    private String collectedAt;
    private Window window;
    private List<SourceRecord> sources = new ArrayList<>();
    private List<SeriesRecord> series = new ArrayList<>();

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getCollectionId() { return collectionId; }
    public void setCollectionId(String collectionId) { this.collectionId = collectionId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String collectedAt) { this.collectedAt = collectedAt; }
    public Window getWindow() { return window; }
    public void setWindow(Window window) { this.window = window; }
    public List<SourceRecord> getSources() { return sources; }
    public void setSources(List<SourceRecord> sources) { this.sources = sources == null ? new ArrayList<SourceRecord>() : sources; }
    public List<SeriesRecord> getSeries() { return series; }
    public void setSeries(List<SeriesRecord> series) { this.series = series == null ? new ArrayList<SeriesRecord>() : series; }

    /** The series collected for {@code (source, resolvedQuery)}, or {@code null}. */
    public SeriesRecord find(String source, String resolvedQuery) {
        for (SeriesRecord s : series) {
            if (Objects.equals(s.getSource(), source) && Objects.equals(s.getResolvedQuery(), resolvedQuery)) {
                return s;
            }
        }
        return null;
    }

    /**
     * The series collected for {@code (source, query as written)}, or {@code null} — what the
     * evaluator asks, because a KPI knows its query as written and not what the collector
     * resolved it to. {@code null} means the payload was collected against a file that did not
     * declare this KPI: {@code NOT_REQUESTED}, a config error.
     *
     * <p>The raw text is tried first and the resolved text second, because the collector dedups
     * by the <em>resolved</em> query and keeps only the first KPI's raw text. Two KPIs whose
     * different raw queries resolve to one string therefore share one record, and the second one
     * would otherwise be permanently {@code NOT_REQUESTED} — a config error naming a KPI that is
     * declared perfectly well. A KPI whose query needs no interpolation is exactly that second
     * case whenever another KPI interpolates to the same text.
     */
    public SeriesRecord findByQuery(String source, String query) {
        return findByQuery(source, query, null);
    }

    /**
     * As above, for an assert on {@code component}: a series is identified by its component and
     * its query (plans/server-metrics-backends.md §8b), because two components of one type with
     * no {@code match} resolve to one query and only the component tells their rows apart. A
     * series that names no component — every payload collected before it was recorded — matches
     * any, as it always did.
     */
    public SeriesRecord findByQuery(String source, String query, String component) {
        for (SeriesRecord s : series) {
            if (Objects.equals(s.getSource(), source) && Objects.equals(s.getQuery(), query)
                    && sameComponent(s, component)) {
                return s;
            }
        }
        for (SeriesRecord s : series) {
            if (Objects.equals(s.getSource(), source) && Objects.equals(s.getResolvedQuery(), query)
                    && sameComponent(s, component)) {
                return s;
            }
        }
        return null;
    }

    private static boolean sameComponent(SeriesRecord s, String component) {
        return s.getComponent() == null || component == null || s.getComponent().equals(component);
    }

    /** The source record with this id, or {@code null}. */
    public SourceRecord source(String id) {
        for (SourceRecord s : sources) {
            if (Objects.equals(s.getId(), id)) {
                return s;
            }
        }
        return null;
    }

    /**
     * One sample, {@code [epochMillis, value]} on the wire — an array rather than an object,
     * because ten thousand {@code {"ts":…,"value":…}} pairs would double the payload for no
     * reader's benefit. A class rather than {@code double[]} because the timestamp is a long and
     * must be written as one: widened into a double it came out as {@code 1.700000001E12}, which
     * is exact but not legible, not greppable, and not what a golden should freeze.
     */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonPropertyOrder({"ts", "value"})
    public static final class Point {
        private long ts;
        private double value;

        public Point() {}

        public Point(long ts, double value) {
            this.ts = ts;
            this.value = value;
        }

        public long getTs() { return ts; }
        public void setTs(long ts) { this.ts = ts; }
        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }

        @Override
        public String toString() {
            return "[" + ts + ", " + value + "]";
        }
    }

    /** The window the series were collected over — epoch millis, as the JTL recorded them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Window {
        private long start;
        private long end;
        private Long rampUpEnd;

        public Window() {}

        public Window(long start, long end, Long rampUpEnd) {
            this.start = start;
            this.end = end;
            this.rampUpEnd = rampUpEnd;
        }

        public long getStart() { return start; }
        public void setStart(long start) { this.start = start; }
        public long getEnd() { return end; }
        public void setEnd(long end) { this.end = end; }
        /** Where the ramp-up ended, or {@code null} when the run declared none. */
        public Long getRampUpEnd() { return rampUpEnd; }
        public void setRampUpEnd(Long rampUpEnd) { this.rampUpEnd = rampUpEnd; }

        public long durationMillis() { return end - start; }
    }

    /** One source as the collector saw it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SourceRecord {
        private String id;
        private String type;
        private String from;
        private String layout;
        private String timeZone;
        private String columns;
        private String component;
        private String status;
        private long stepMillis;
        private long clockOffsetMs;
        private String resolvedUrl;
        private String error;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        /**
         * The tool a CSV was exported from ({@code wir-collect --csv FILE --from TOOL}): its metric
         * column was read by that tool's names, and its values converted to the catalogue's unit.
         * Absent for a file in the catalogue's own names, and for every other source; so a payload
         * collected without {@code --from} is the file it always was.
         */
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        /**
         * {@code wide} for a CSV read as a time column and one column per series
         * ({@code --layout wide}, Grafana's export). Absent for a long CSV, the layout every CSV had
         * before there was a choice, and for every other source; so a long collection's payload is
         * the file it always was.
         */
        public String getLayout() { return layout; }
        public void setLayout(String layout) { this.layout = layout; }
        /**
         * The zone a CSV's zone-less times were read in ({@code --time-zone}), as java.time names it:
         * {@code UTC}, {@code +03:00}, {@code Europe/Berlin}. Absent when none was given, and then
         * such a time was refused rather than placed.
         */
        public String getTimeZone() { return timeZone; }
        public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
        /**
         * How a long CSV's columns were named ({@code --csv-columns}), as the collector normalised it:
         * {@code key=column} pairs in the order time, component, metric, instance, value, only those
         * given — {@code time=_time,metric=_measurement+_field,value=_value}. Absent when the file had
         * this reader's own names, and for every other source; so such a payload is the file it was.
         */
        public String getColumns() { return columns; }
        public void setColumns(String columns) { this.columns = columns; }
        /**
         * The component every row of a long CSV was read as ({@code --component}): a file exported from
         * a tool that knows nothing of components. Absent when each row named its own.
         */
        public String getComponent() { return component; }
        public void setComponent(String component) { this.component = component; }
        /**
         * {@code OK}, {@code PARTIAL} (some series failed, others came back) or {@code FAILED}
         * (nothing came back — an unusable source, an unreadable file, every series failed).
         */
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public long getStepMillis() { return stepMillis; }
        public void setStepMillis(long stepMillis) { this.stepMillis = stepMillis; }
        /** Applied on read and always shown — a 30 s skew misattributes every spike in silence. */
        public long getClockOffsetMs() { return clockOffsetMs; }
        public void setClockOffsetMs(long clockOffsetMs) { this.clockOffsetMs = clockOffsetMs; }
        /** The URL as queried, userinfo stripped and secret-looking parameters masked. */
        public String getResolvedUrl() { return resolvedUrl; }
        public void setResolvedUrl(String resolvedUrl) { this.resolvedUrl = resolvedUrl; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    /** One collected query: its instances, its coverage, its status. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SeriesRecord {
        private String source;
        private String component;
        private String metric;
        private ReadAs readAs;
        private String query;
        private String resolvedQuery;
        private String status;
        private Double coverage;
        private String error;
        private List<InstanceRecord> instances = new ArrayList<>();

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        /** The component whose assert asked for it; absent in a payload collected before §8b. */
        public String getComponent() { return component; }
        public void setComponent(String component) { this.component = component; }
        /**
         * The catalogue metric this series is, when it is one: an asserted catalogue metric, or one
         * no assert names, collected for the page's picker (D1). Absent for a custom query.
         */
        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        /**
         * The tool's name this series was read under, and how its number was converted — present
         * only when it was ({@code --from}, and the file had rows under the tool's name). A series
         * read under the catalogue's own name, or collected without {@code --from}, has none.
         */
        public ReadAs getReadAs() { return readAs; }
        public void setReadAs(ReadAs readAs) { this.readAs = readAs; }
        /** The query as written in the file, placeholders unresolved. */
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        /** The query as sent, placeholders resolved. Queries carry labels, not secrets. */
        public String getResolvedQuery() { return resolvedQuery; }
        public void setResolvedQuery(String resolvedQuery) { this.resolvedQuery = resolvedQuery; }
        /** A {@link CollectionStatus} wire name. */
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        /** Fraction of the window's expected steps that carried a point, or null when none could. */
        public Double getCoverage() { return coverage; }
        public void setCoverage(Double coverage) { this.coverage = coverage; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public List<InstanceRecord> getInstances() { return instances; }
        public void setInstances(List<InstanceRecord> instances) { this.instances = instances == null ? new ArrayList<InstanceRecord>() : instances; }

        /**
         * The status as an enum. Throws {@link IllegalArgumentException} on a wire name this
         * version does not know — {@code ServerMetricsPayloadLoader} checks every series before
         * a payload is accepted, so a hand-edited or newer file is a problem, never a crash.
         */
        public CollectionStatus collectionStatus() {
            return status == null ? CollectionStatus.NOT_REQUESTED : CollectionStatus.valueOf(status);
        }
    }

    /**
     * Where a series' number came from when it was read as a tool's export: the tool, its name for
     * the catalogue metric, the kind of number it hands over ({@code gauge}, {@code counter},
     * {@code rate}, {@code sum-per-period}) and the scale applied after the kind — the catalogue's
     * {@code ToolName}, as it was when the series was collected. So a reader can tell a 41.5 % that
     * was a Datadog fraction of 0.415 from one the file held as written.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"tool", "metric", "kind", "scale"})
    public static class ReadAs {
        private String tool;
        private String metric;
        private String kind;
        private double scale = 1;

        public ReadAs() {}

        public ReadAs(String tool, String metric, String kind, double scale) {
            this.tool = tool;
            this.metric = metric;
            this.kind = kind;
            this.scale = scale;
        }

        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
        /** The metric's name in the tool's export, as the file's metric column holds it. */
        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        /** As the catalogue writes it: {@code sum-per-period}. */
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public double getScale() { return scale; }
        public void setScale(double scale) { this.scale = scale; }
    }

    /** One instance (pod, host, broker) of a series: its labels and its points. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InstanceRecord {
        private Map<String, String> labels;
        /** {@code [[epochMillis, value], …]}, ascending, after the source's clock offset. */
        private List<Point> points = new ArrayList<>();

        public Map<String, String> getLabels() { return labels; }
        public void setLabels(Map<String, String> labels) { this.labels = labels; }
        public List<Point> getPoints() { return points; }
        public void setPoints(List<Point> points) { this.points = points == null ? new ArrayList<Point>() : points; }
    }
}
