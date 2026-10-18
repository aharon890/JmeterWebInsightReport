package com.jmeterwebinsightreport.core.servermetrics.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.Across;
import com.jmeterwebinsightreport.core.servermetrics.KpiUsability;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in component types (plan C1–C9): what a {@code serverMetrics.components} entry's
 * {@code type} may be, and the metrics each offers.
 *
 * <p>Read once from the classpath, under {@code com/jmeterwebinsightreport/} because the shaded
 * JAR's allowlist refuses anything else. {@code index.json} lists the types in the order the
 * Add-component dialog offers them; a shaded JAR has no directory listing to discover them from.
 *
 * <p>{@code custom} is not a catalogue type and is not in here: it is the component type with no
 * catalogue, whose every assert writes its own query (C9). {@link #CUSTOM} names it.
 *
 * <p>A metric's {@code queries} map a backend to its query — text, never empty, never padded.
 * Its optional {@code names} map each of {@link #TOOLS} to what that tool's export calls the
 * metric and what kind of number it is ({@link ToolName}), or to {@code null} where the tool has
 * no single-metric equivalent — a ratio, a difference or a sum of its metrics, or nothing at all —
 * and the tester exports the computed series under the catalogue's own name. A metric may have no
 * prometheus query at all; it is then known by its neutral
 * {@link CatalogueMetric#identity identity}, {@code <type>:<metric>}, and only a CSV collection
 * can answer it.
 *
 * <p>A name is written the way its tool's export writes it, one convention per tool:
 * <ul>
 *   <li>{@code telegraf}: {@code <measurement>.<field>} ({@code postgresql.deadlocks}), what
 *       {@code --csv-columns metric=_measurement+_field} joins from InfluxDB's columns;</li>
 *   <li>{@code datadog}: the metric's name ({@code postgresql.percent_usage_connections});</li>
 *   <li>{@code otel}: the metric's name ({@code postgresql.deadlocks}), not the one an export to
 *       Prometheus rewrites it to;</li>
 *   <li>{@code cloudwatch}: {@code <Namespace>:<MetricName>} ({@code AWS/EC2:CPUCreditBalance},
 *       {@code CWAgent:mem_used_percent}), with the statistic and the dimension in the entry's
 *       {@code note}.</li>
 * </ul>
 * The {@code note} says whatever else picks the series out: a tag filter, a fold over the
 * tool's own tags, an input or metric the tool has off by default.
 *
 * <p>A malformed built-in file is a build defect, not a user fault, so loading throws;
 * {@code CatalogueTest} reads every file and expands every template, so it cannot ship.
 */
public final class Catalogue {

    /** The component type with no catalogue: every assert on it is a custom query. */
    public static final String CUSTOM = "custom";

    /** The backend every built-in query is written for today. */
    public static final String PROMETHEUS = "prometheus";

    /** The tools a metric's {@code names} block may name it for ({@link ToolName}), in this order. */
    public static final List<String> TOOLS =
            Collections.unmodifiableList(Arrays.asList("telegraf", "datadog", "otel", "cloudwatch"));

    private static final String ROOT = "com/jmeterwebinsightreport/core/servermetrics/catalogue/";

    private static volatile Catalogue builtIn;

    private final Map<String, CatalogueType> types;

    /** Each type's file as read, for the page (see {@link #pageJson()}). */
    private final Map<String, JsonNode> files;

    Catalogue(Map<String, CatalogueType> types) {
        this(types, Collections.<String, JsonNode>emptyMap());
    }

    private Catalogue(Map<String, CatalogueType> types, Map<String, JsonNode> files) {
        this.types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
        this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    /** The types shipped in this JAR. */
    public static Catalogue builtIn() {
        Catalogue c = builtIn;
        if (c == null) {
            synchronized (Catalogue.class) {
                c = builtIn;
                if (c == null) {
                    c = load();
                    builtIn = c;
                }
            }
        }
        return c;
    }

    /** By key, in the order index.json lists them. */
    public Map<String, CatalogueType> types() { return types; }

    /** Null for an unknown key, and for {@link #CUSTOM}, which has no catalogue. */
    public CatalogueType type(String key) {
        return key == null ? null : types.get(key.trim());
    }

    /** Whether a component may name this type: a catalogue type, or {@code custom}. */
    public boolean knows(String key) {
        return key != null && (CUSTOM.equals(key.trim()) || types.containsKey(key.trim()));
    }

    /**
     * "postgres, kafka, redis, host-linux, aws-ec2, oracle, influxdb2, influxdb3, jvm, http-service,
     * kubernetes-pod, mysql, custom" — for a refusal that says what would have been accepted.
     */
    public String vocabulary() {
        StringBuilder sb = new StringBuilder();
        for (String k : types.keySet()) {
            sb.append(k).append(", ");
        }
        return sb.append(CUSTOM).toString();
    }

    /**
     * The catalogue as the page reads it — {@code {"order": [...], "types": {key: file}}} — the
     * type files exactly as shipped, so the browser's mirror resolves a metric to the same query
     * from the same text rather than from a second, hand-kept copy.
     */
    public Map<String, Object> pageJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", new java.util.ArrayList<>(files.keySet()));
        Map<String, Object> byKey = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : files.entrySet()) {
            byKey.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class));
        }
        out.put("types", byKey);
        return out;
    }

    // ---- loading -------------------------------------------------------------

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Catalogue load() {
        JsonNode index = read("index.json");
        Map<String, JsonNode> files = new LinkedHashMap<>();
        for (JsonNode t : index.path("types")) {
            String key = t.asText();
            files.put(key, read(key + ".json"));
        }
        return fromFiles(files);
    }

    /**
     * The catalogue these type files make, key → file in the order the dialog offers them, read
     * and refused exactly as the built-in files are — {@link #builtIn()} is this over the files
     * on the classpath. Public so a test can judge a type the shipped files do not have (a metric
     * with no prometheus query, say) through the real loader rather than a hand-built model.
     */
    public static Catalogue fromFiles(Map<String, JsonNode> files) {
        Map<String, CatalogueType> types = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> f : files.entrySet()) {
            types.put(f.getKey(), readType(f.getKey(), f.getValue()));
        }
        return new Catalogue(types, files);
    }

    private static JsonNode read(String file) {
        ClassLoader loader = Catalogue.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(ROOT + file)) {
            if (in == null) {
                throw new IllegalStateException("built-in catalogue file missing: " + ROOT + file);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("built-in catalogue file unreadable: " + ROOT + file, e);
        }
    }

    static CatalogueType readType(String expectedKey, JsonNode node) {
        String key = text(node, "type", expectedKey);
        if (!expectedKey.equals(key)) {
            throw new IllegalStateException(expectedKey + ".json declares type '" + key + "'");
        }
        if (CUSTOM.equals(key)) {
            throw new IllegalStateException("'custom' is the type with no catalogue and cannot have a file");
        }
        int version = node.path("version").asInt(0);
        if (version < 1) {
            throw new IllegalStateException(key + ".json needs a version of 1 or more");
        }
        Map<String, CatalogueMetric> metrics = new LinkedHashMap<>();
        for (JsonNode m : node.path("metrics")) {
            CatalogueMetric metric = readMetric(key, m);
            if (metrics.put(metric.name(), metric) != null) {
                throw new IllegalStateException(key + ": metric '" + metric.name() + "' is listed twice");
            }
        }
        if (metrics.isEmpty()) {
            throw new IllegalStateException(key + ".json lists no metrics");
        }
        // The card names it (SF46): a type whose prerequisite is unnamed reads as one that needs
        // nothing, and a tester then trusts metric names no exporter of theirs publishes.
        String exporter = text(node, "exporter", "");
        if (exporter.isEmpty()) {
            throw new IllegalStateException(key + ".json names no exporter");
        }
        return new CatalogueType(key, version, text(node, "label", key), text(node, "summary", ""),
                exporter, text(node, "source", ""), metrics);
    }

    private static CatalogueMetric readMetric(String type, JsonNode m) {
        String name = text(m, "name", null);
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException(type + ": a metric has no name");
        }
        String where = type + "." + name;
        Reducer reducer = Reducer.parse(text(m, "reduce", "max"));
        if (reducer == null) {
            throw new IllegalStateException(where + ": reduce '" + text(m, "reduce", "") + "' is not a reducer");
        }
        String acrossKey = text(m, "across", null);
        Across across = acrossKey == null ? Across.defaultValue() : Across.fromKey(acrossKey);
        if (across == null) {
            throw new IllegalStateException(where + ": across '" + acrossKey + "' is not one of "
                    + Across.vocabulary());
        }
        Map<String, String> queries = new LinkedHashMap<>();
        JsonNode queriesNode = m.get("queries");
        if (queriesNode != null && !queriesNode.isNull() && !queriesNode.isObject()) {
            throw new IllegalStateException(where + ": queries " + queriesNode + " is not an object of backend queries");
        }
        Iterator<Map.Entry<String, JsonNode>> it = m.path("queries").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> q = it.next();
            // Text, or refused. asText() read an object as "" and a number as "42": a metric that
            // loaded with a query nobody wrote, and asked a backend for it.
            if (!q.getValue().isTextual()) {
                throw new IllegalStateException(where + ": the " + q.getKey() + " query " + q.getValue()
                        + " is not text" + (q.getValue().isNull() ? NO_QUERY_FOR_IT : ""));
            }
            String query = q.getValue().asText();
            if (query.isEmpty()) {
                throw new IllegalStateException(where + ": the " + q.getKey() + " query is empty" + NO_QUERY_FOR_IT);
            }
            // Refused, not trimmed. A query is a series' identity (plan §7a), and ServerKpi trims
            // the one an assert resolves to - but Collector.collectCatalogue reads these templates
            // without going through ServerKpi, and the page reads this very file as shipped.
            // Trimmed here, Java and the page would key one metric two ways; refused, the file
            // cannot ship (CatalogueTest loads it).
            if (!query.equals(query.trim()) || KpiUsability.hasInvisibleEnd(query)) {
                throw new IllegalStateException(where + ": the " + q.getKey() + " query starts or ends with "
                        + "whitespace or an invisible character - a query is a series' identity, so write it "
                        + "without");
            }
            queries.put(q.getKey(), query);
        }
        String unit = text(m, "unit", "");
        Double ceiling = ceiling(where, m.get("ceiling"), reducer);
        JsonNode safe = m.path("safeDefault");
        Predicate safeWarn = band(where, safe.path("warn"));
        Predicate safeFail = band(where, safe.path("fail"));
        refuseBandPastCeiling(where, "warn", safeWarn, ceiling, unit);
        refuseBandPastCeiling(where, "fail", safeFail, ceiling, unit);
        return new CatalogueMetric(type, name, text(m, "label", name), text(m, "category", "Other"),
                text(m, "description", ""), unit, reducer, across,
                CatalogueMetric.Direction.fromKey(text(m, "direction", "none")), ceiling,
                text(m, "dimension", ""), m.path("recommended").asBoolean(false),
                safeWarn, safeFail, queries, names(where, m.get("names")));
    }

    /** Why a null or empty query is refused rather than read as "no query for this backend". */
    private static final String NO_QUERY_FOR_IT = " - leave the backend out when the metric has no query for it";

    /** The keys a {@link ToolName} entry may have. */
    private static final List<String> NAME_KEYS = Arrays.asList("metric", "kind", "scale", "note");

    /**
     * The metric's {@code names} block: tool → {@link ToolName}, a {@code null} value where the
     * file says the tool has none. Read strictly — the catalogue is ours, so a typo is a build
     * defect, and a misread kind or scale would convert every number a user's export carries
     * without a word. {@code "names": null} is no block, as a null is no value in every other
     * key this reads.
     */
    private static Map<String, ToolName> names(String where, JsonNode node) {
        Map<String, ToolName> names = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return names;
        }
        if (!node.isObject()) {
            throw new IllegalStateException(where + ": names " + node + " is not an object of tool names");
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String tool = e.getKey();
            if (!TOOLS.contains(tool)) {
                throw new IllegalStateException(where + ": names tool '" + tool + "' is not one of "
                        + String.join(", ", TOOLS));
            }
            names.put(tool, toolName(where + ": names." + tool, tool, e.getValue()));
        }
        return names;
    }

    /** One entry of a names block; null for the file's explicit "this tool has none". */
    private static ToolName toolName(String where, String tool, JsonNode entry) {
        if (entry.isNull()) {
            return null;
        }
        if (!entry.isObject()) {
            throw new IllegalStateException(where + " " + entry + " is neither an object nor null");
        }
        Iterator<String> keys = entry.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!NAME_KEYS.contains(key)) {
                throw new IllegalStateException(where + " key '" + key + "' is not one of " + String.join(", ", NAME_KEYS));
            }
        }
        JsonNode metricNode = entry.get("metric");
        if (metricNode == null || metricNode.isNull()
                || (metricNode.isTextual() && metricNode.asText().trim().isEmpty())) {
            throw new IllegalStateException(where + " has no metric");
        }
        if (!metricNode.isTextual()) {
            throw new IllegalStateException(where + " metric " + metricNode + " is not text");
        }
        String metric = metricNode.asText();
        // Refused, not trimmed, as a query is: the name is what an export is searched for, and a
        // padded one would match nothing in it.
        if (!metric.equals(metric.trim()) || KpiUsability.hasInvisibleEnd(metric)) {
            throw new IllegalStateException(where + " metric " + metricNode + " starts or ends with whitespace or an "
                    + "invisible character - write it without");
        }
        JsonNode kindNode = entry.get("kind");
        if (kindNode == null || kindNode.isNull()) {
            throw new IllegalStateException(where + " has no kind - one of " + ToolName.Kind.vocabulary());
        }
        ToolName.Kind kind = kindNode.isTextual() ? ToolName.Kind.fromKey(kindNode.asText()) : null;
        if (kind == null) {
            throw new IllegalStateException(where + " kind " + (kindNode.isTextual() ? "'" + kindNode.asText() + "'"
                    : kindNode.toString()) + " is not one of " + ToolName.Kind.vocabulary());
        }
        JsonNode scaleNode = entry.get("scale");
        double scale = 1;
        if (scaleNode != null && !scaleNode.isNull()) {
            if (!scaleNode.isNumber() || !Double.isFinite(scaleNode.asDouble()) || scaleNode.asDouble() <= 0) {
                throw new IllegalStateException(where + " scale " + scaleNode + " is not a finite number above 0");
            }
            scale = scaleNode.asDouble();
        }
        return new ToolName(tool, metric, kind, scale, note(where, entry.get("note")));
    }

    /**
     * An entry's optional {@code note} ({@link ToolName#note()}), or null. Free text a tester reads
     * as written, so it is read as strictly as the name it qualifies: text, saying something, not
     * padded, on one line, and short. {@code "note": null} is no note.
     */
    private static String note(String where, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalStateException(where + " note " + node + " is not text");
        }
        String note = node.asText();
        if (note.trim().isEmpty()) {
            throw new IllegalStateException(where + " note is blank - leave it out when there is nothing to say");
        }
        if (!note.equals(note.trim()) || KpiUsability.hasInvisibleEnd(note)) {
            throw new IllegalStateException(where + " note " + node + " starts or ends with whitespace or an invisible"
                    + " character - write it without");
        }
        for (int i = 0; i < note.length(); i++) {
            char c = note.charAt(i);
            // LF, CR, NEL, LINE SEPARATOR, PARAGRAPH SEPARATOR: each starts a line somewhere.
            if (c == 0x0A || c == 0x0D || c == 0x85 || c == 0x2028 || c == 0x2029) {
                throw new IllegalStateException(where + " note spans more than one line - write it on one");
            }
        }
        int length = note.codePointCount(0, note.length());
        if (length > ToolName.NOTE_MAX) {
            throw new IllegalStateException(where + " note is " + length + " characters - at most " + ToolName.NOTE_MAX);
        }
        return note;
    }

    /**
     * The metric's {@code ceiling} (F2), or null when it declares none. The page proposes no band
     * at or past it, so a malformed one would mislead every proposal in silence; it is refused
     * instead. A slope is judged per minute, not in the metric's unit, so no ceiling bounds it.
     */
    private static Double ceiling(String where, JsonNode node, Reducer reducer) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber() || !Double.isFinite(node.asDouble())) {
            throw new IllegalStateException(where + ": ceiling " + node + " is not a finite number");
        }
        if (reducer.kind() == Reducer.Kind.SLOPE) {
            throw new IllegalStateException(where + ": a slope is judged per minute, not in the metric's unit, "
                    + "so it cannot declare a ceiling");
        }
        return node.asDouble();
    }

    /** A safe band that can never fire under the ceiling the same file declares: gt at or past it, gte past it. */
    private static void refuseBandPastCeiling(String where, String which, Predicate band, Double ceiling, String unit) {
        if (band == null || ceiling == null) {
            return;
        }
        boolean dead = band.op() == Predicate.Op.GT ? band.value() >= ceiling
                : band.op() == Predicate.Op.GTE && band.value() > ceiling;
        if (dead) {
            throw new IllegalStateException(where + ": safe " + which + " band " + band.describe(unit)
                    + " can never fire under the metric's ceiling of " + Predicate.trim(ceiling)
                    + (unit.isEmpty() ? "" : " " + unit));
        }
    }

    private static Predicate band(String where, JsonNode band) {
        if (band.isMissingNode() || band.isNull()) {
            return null;
        }
        if (band.size() != 1) {
            throw new IllegalStateException(where + ": a safe band needs exactly one operator");
        }
        Map.Entry<String, JsonNode> e = band.fields().next();
        Predicate.Op op = Predicate.Op.fromKey(e.getKey());
        if (op == null || !e.getValue().isNumber()) {
            throw new IllegalStateException(where + ": safe band '" + e.getKey() + "' is not gt/gte/lt/lte and a number");
        }
        return Predicate.of(op, e.getValue().asDouble());
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }
}
