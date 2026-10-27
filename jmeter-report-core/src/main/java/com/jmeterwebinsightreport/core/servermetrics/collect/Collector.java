package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.jmeterwebinsightreport.core.servermetrics.CollectionStatus;
import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.core.servermetrics.ServerMetricsConfig;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueType;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.ToolName;
import com.jmeterwebinsightreport.core.util.RunIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a mapped {@code serverMetrics} block, a run's window and identity, and the sources into
 * a {@link ServerMetricsPayload}. The one place a collection is assembled; {@code wir-collect}
 * is a thin shell around it.
 *
 * <p>What is collected: every distinct {@code (source, resolvedQuery)} named by an enabled KPI
 * whose source is usable and whose query resolves. A KPI refused for a reason unrelated to
 * collection — an unreadable band, say — still gets its series, because the chart is useful
 * even when the verdict is not, and because M7's "Validate query" is to answer from what was
 * collected (D22). A KPI parked with {@code enabled: false} is not collected for itself: the
 * author said not to. A parked custom query is therefore not collected at all, while a parked
 * catalogue metric still is, as every metric of each declared catalogue component is, for the
 * picker (D1).
 *
 * <p>What is never written: a secret's value. The collector is the one place that knows which
 * values were secrets — it substituted the {@code ${secret.*}} placeholders and read the
 * {@code auth.*Env} variables — so it masks them by value ({@link Redaction#values}) in the
 * resolved URL and in every error message before either reaches a record. The name-based rule
 * ({@link Redaction#url}) runs after, as the last line.
 *
 * <p>What each series is called: the collector decides the {@link CollectionStatus}, because
 * it knows the coverage floor and the window and a source does not. {@code FAILED} when the
 * source did not answer; {@code EMPTY} when it answered with no series; otherwise by coverage —
 * at or above the floor it is {@code OK} (complete) or {@code PARTIAL} (gaps), below it
 * {@code INSUFFICIENT}. Those names map onto the KPI's not-judged reason by
 * {@link CollectionStatus#toReason()}, which is what keeps the collection panel and the KPI
 * board consistent rather than merely adjacent.
 *
 * <p>A file exported from another tool ({@link ServerSource#fromTool()}, {@code --from TOOL}): every
 * catalogue metric is asked for with what the catalogue says that tool calls it
 * ({@link SeriesSource.Request#fromTool}), and a series read under the tool's name records it
 * ({@link ServerMetricsPayload.ReadAs}). The source records the tool, and the collectionId folds it
 * in only when there is one, so a collection without it keeps the id it always had. A wide CSV
 * ({@code --layout wide}) and a {@code --time-zone} are recorded and folded in the same way, and so
 * are a long CSV's {@code --csv-columns} and {@code --component}.
 */
public final class Collector {

    /** What the collector cannot proceed from at all — reported, never a payload. */
    public static final class CollectionException extends Exception {
        public CollectionException(String message) {
            super(message);
        }
    }

    /** The run's facts a query may interpolate, and the identity the payload carries. */
    public static final class Run {
        private final String runId;
        private final String testName;
        private final ServerMetricsPayload.Window window;

        public Run(String runId, String testName, ServerMetricsPayload.Window window) {
            this.runId = runId;
            this.testName = testName;
            this.window = window;
        }

        public String runId() { return runId; }
        public String testName() { return testName; }
        public ServerMetricsPayload.Window window() { return window; }
    }

    private final Map<ServerSource.Type, SeriesSource> sources;
    private final Map<String, String> secrets;
    private final Catalogue catalogue;

    /**
     * @param sources one reader per source type
     * @param secrets the environment the {@code *Env} references and {@code ${secret.*}} resolve from
     */
    public Collector(Map<ServerSource.Type, SeriesSource> sources, Map<String, String> secrets) {
        this(sources, secrets, Catalogue.builtIn());
    }

    /**
     * @param catalogue whose metrics are collected for every declared component (D1), and which
     *                  tells an assert on a metric with no prometheus query from a query to send;
     *                  null for none, and every assert is sent as written
     */
    public Collector(Map<ServerSource.Type, SeriesSource> sources, Map<String, String> secrets,
                     Catalogue catalogue) {
        this.sources = sources;
        this.secrets = secrets == null ? new LinkedHashMap<String, String>() : secrets;
        this.catalogue = catalogue;
    }

    /**
     * @param environment the selected environment's name, or null for {@code defaultEnv} / none
     * @param collectedAt ISO-8601 text stamped into the payload and folded into the collectionId
     */
    public ServerMetricsPayload collect(ServerMetricsConfig config, String environment, Run run,
                                        String collectedAt) throws CollectionException {
        String envName = environment != null ? environment : config.defaultEnv();
        Interpolation.Scope scope = scope(config, environment, run);

        ServerMetricsPayload payload = new ServerMetricsPayload();
        payload.setRunId(run.runId());
        payload.setEnvironment(envName);
        payload.setCollectedAt(collectedAt);
        payload.setWindow(run.window());

        // Sources first: resolve the URL once, record the outcome once.
        Map<String, ServerSource> resolved = new LinkedHashMap<>();
        Map<String, ServerMetricsPayload.SourceRecord> records = new LinkedHashMap<>();
        // Per source, the values that must never be written: the secrets its URL interpolated
        // and the ones its auth references name. Masked by value in every text about it.
        Map<String, List<String>> secretsOf = new LinkedHashMap<>();
        for (ServerSource s : config.sources().values()) {
            ServerMetricsPayload.SourceRecord rec = new ServerMetricsPayload.SourceRecord();
            rec.setId(s.id());
            rec.setType(s.type() == null ? null : s.type().key());
            rec.setFrom(s.fromTool());
            // How a CSV was read, only where it differs from what every CSV was read as before:
            // wide, and a zone for its zone-less times. A long file without one records neither.
            rec.setLayout(s.csvLayout() == ServerSource.CsvLayout.WIDE ? s.csvLayout().key() : null);
            rec.setTimeZone(s.timeZone() == null ? null : s.timeZone().getId());
            // A long file's columns named otherwise (--csv-columns) and the one component its rows
            // were read as (--component), likewise only when given.
            rec.setColumns(s.csvColumnSpec());
            rec.setComponent(s.csvComponent());
            rec.setStepMillis(s.stepMillis());
            rec.setClockOffsetMs(s.clockOffsetMs());
            if (!s.usable()) {
                rec.setStatus("FAILED");
                rec.setError(s.problem());
            } else if (s.type() == ServerSource.Type.PROMETHEUS) {
                List<String> used = new ArrayList<>();
                for (String envVar : s.authEnv().values()) {
                    String value = secrets.get(envVar);
                    if (value != null && !value.isEmpty()) {
                        used.add(value);
                    }
                }
                Interpolation.Result url = Interpolation.resolve(s.url(), scope, Interpolation.Site.URL);
                used.addAll(url.secretsUsed());
                secretsOf.put(s.id(), used);
                if (url.failed()) {
                    rec.setStatus("FAILED");
                    rec.setError("url: " + url.problem());
                } else {
                    rec.setStatus("OK");
                    rec.setResolvedUrl(Redaction.url(Redaction.values(url.text(), used)));
                    resolved.put(s.id(), ServerSource.prometheus(s.id(), url.text(), s.stepMillis(),
                            s.clockOffsetMs(), s.authEnv()));
                }
            } else {
                rec.setStatus("OK");
                // A CSV is recorded by its file name: the payload reaches the published page, and a
                // path given absolute (as a -q properties file usually does) would carry a local
                // directory into it. The name is all a reader needs to find the file.
                rec.setResolvedUrl(s.type() == ServerSource.Type.CSV ? new java.io.File(s.path()).getName() : s.path());
                resolved.put(s.id(), s);
            }
            records.put(s.id(), rec);
            payload.getSources().add(rec);
        }

        // Then every distinct query, once.
        Set<String> seen = new LinkedHashSet<>();
        List<String> material = new ArrayList<>();
        Map<String, List<String>> failedBySource = new LinkedHashMap<>();
        Map<String, Integer> askedBySource = new LinkedHashMap<>();
        for (ServerKpi kpi : config.kpis()) {
            if (!kpi.enabled() || kpi.source() == null || kpi.query() == null || kpi.query().trim().isEmpty()) {
                continue;
            }
            ServerSource source = resolved.get(kpi.source());
            ServerMetricsPayload.SourceRecord rec = records.get(kpi.source());
            if (rec == null) {
                continue;   // unknown source: the KPI is INVALID_RULE already; nothing to ask
            }
            Interpolation.Result q = Interpolation.resolve(kpi.query(), scope, Interpolation.Site.QUERY);
            String resolvedText = resolvedText(kpi, q);
            // A series is its component and its query (plans/server-metrics-backends.md §8b): two
            // components of one type with no match resolve to one query, and only the component
            // tells their CSV rows apart.
            String key = kpi.source() + "\u0000" + kpi.gate() + "\u0000" + resolvedText;
            if (!seen.add(key)) {
                continue;
            }
            ServerMetricsPayload.SeriesRecord series = new ServerMetricsPayload.SeriesRecord();
            series.setSource(kpi.source());
            series.setComponent(kpi.gate());
            series.setQuery(kpi.query());
            if (q.failed()) {
                series.setResolvedQuery(kpi.query());
                series.setStatus(CollectionStatus.FAILED.wireName());
                series.setError(q.problem());
            } else if (source == null) {
                series.setResolvedQuery(resolvedText);
                series.setStatus(CollectionStatus.FAILED.wireName());
                series.setError(rec.getError());
            } else {
                series.setResolvedQuery(resolvedText);
                SeriesSource reader = sources.get(source.type());
                if (reader == null) {
                    throw new CollectionException("no reader for source type " + source.type());
                }
                SeriesSource.Request request = assertRequest(config, kpi, resolvedText, source);
                SeriesSource.Answer answer = reader.collect(source, request, run.window());
                if (answer.failed()) {
                    // The reader quotes the URL it used and the body it got; neither may carry a
                    // secret's value into the payload.
                    String error = Redaction.values(answer.error(), secretsOf.get(kpi.source()));
                    series.setStatus(CollectionStatus.FAILED.wireName());
                    series.setError(error);
                    failedBySource.computeIfAbsent(kpi.source(), k -> new ArrayList<>()).add(error);
                } else if (answer.instances().isEmpty()) {
                    series.setReadAs(readAs(answer));
                    series.setStatus(CollectionStatus.EMPTY.wireName());
                    series.setCoverage(0.0);
                } else {
                    series.setReadAs(readAs(answer));
                    series.setInstances(answer.instances());
                    double coverage = Coverage.of(answer.instances(), run.window(), source.stepMillis());
                    series.setCoverage(Double.isNaN(coverage) ? null : round4(coverage));
                    series.setStatus(statusFor(coverage, config.coverageFloor()).wireName());
                }
            }
            if (kpi.metric() != null) {
                series.setMetric(kpi.metric());
            }
            payload.getSeries().add(series);
            askedBySource.merge(kpi.source(), 1, Integer::sum);
            material.add(series.getSource() + "|" + series.getComponent() + "|" + series.getResolvedQuery());
        }

        collectCatalogue(config, scope, run, resolved, seen, payload, material);

        // A source that answered some queries and failed others is PARTIAL, not FAILED: with one
        // source per collection (C8), "FAILED" on the first bad query read as if nothing had come
        // back (decided 2026-10-03). FAILED is kept for a source where nothing did.
        for (Map.Entry<String, List<String>> e : failedBySource.entrySet()) {
            ServerMetricsPayload.SourceRecord rec = records.get(e.getKey());
            if (rec == null || !"OK".equals(rec.getStatus())) {
                continue;
            }
            int failed = e.getValue().size();
            int asked = askedBySource.getOrDefault(e.getKey(), failed);
            rec.setStatus(failed >= asked ? "FAILED" : "PARTIAL");
            rec.setError(failed >= asked && failed == 1 ? e.getValue().get(0)
                    : failed + " of " + asked + " series failed - first: " + e.getValue().get(0));
        }

        for (ServerMetricsPayload.SourceRecord rec : payload.getSources()) {
            // The tool only when there is one, so a collection without --from keeps its id: the
            // same file read as a tool's export is a different collection, with different numbers.
            // The layout and the zone likewise: each decides which rows are which series, and when.
            // So do the columns and the component.
            material.add(rec.getId() + "|" + rec.getType() + "|" + rec.getResolvedUrl()
                    + (rec.getFrom() == null ? "" : "|from=" + rec.getFrom())
                    + (rec.getLayout() == null ? "" : "|layout=" + rec.getLayout())
                    + (rec.getTimeZone() == null ? "" : "|timeZone=" + rec.getTimeZone())
                    + (rec.getColumns() == null ? "" : "|columns=" + rec.getColumns())
                    + (rec.getComponent() == null ? "" : "|component=" + rec.getComponent()));
        }
        material.add(run.window().getStart() + "-" + run.window().getEnd());
        material.add(collectedAt == null ? "" : collectedAt);
        payload.setCollectionId(RunIdentity.digest(String.join("\n", material)));
        return payload;
    }

    /**
     * What {@link #collect} asks {@code source} for on behalf of the enabled asserts, as it asks for
     * it - one request per distinct component and resolved query, by the same name, neutral identity
     * and {@code --from} tool name - and nothing is fetched. A query that does not resolve asks for
     * nothing, and so does an environment the file does not declare, which {@link #collect} refuses.
     * So a reader can measure the series the asserts will read before the collection reads them
     * ({@link CsvSource#measuredStepMillis}).
     */
    public List<SeriesSource.Request> assertRequests(ServerMetricsConfig config, String environment, Run run,
                                                     ServerSource source) {
        List<SeriesSource.Request> requests = new ArrayList<>();
        Interpolation.Scope scope;
        try {
            scope = scope(config, environment, run);
        } catch (CollectionException refused) {
            return requests;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ServerKpi kpi : config.kpis()) {
            if (!kpi.enabled() || kpi.query() == null || kpi.query().trim().isEmpty()
                    || !source.id().equals(kpi.source())) {
                continue;
            }
            Interpolation.Result q = Interpolation.resolve(kpi.query(), scope, Interpolation.Site.QUERY);
            if (q.failed()) {
                continue;
            }
            String resolvedText = resolvedText(kpi, q);
            if (seen.add(kpi.gate() + "\u0000" + resolvedText)) {
                requests.add(assertRequest(config, kpi, resolvedText, source));
            }
        }
        return requests;
    }

    /**
     * What {@link #collect} asks {@code source} for on behalf of the declared components' catalogue
     * metrics (D1), as it asks for it; nothing is fetched. With an undeclared environment, nothing.
     */
    public List<SeriesSource.Request> catalogueRequests(ServerMetricsConfig config, String environment, Run run,
                                                        ServerSource source) {
        List<SeriesSource.Request> requests = new ArrayList<>();
        Interpolation.Scope scope;
        try {
            scope = scope(config, environment, run);
        } catch (CollectionException refused) {
            return requests;
        }
        if (catalogue == null) {
            return requests;
        }
        for (ServerGate component : config.gates().values()) {
            CatalogueType type = component.type() == null ? null : catalogue.type(component.type());
            if (type == null) {
                continue;
            }
            for (CatalogueMetric metric : type.metrics().values()) {
                Interpolation.Result q = Interpolation.resolve(metric.identity(component.match()), scope,
                        Interpolation.Site.QUERY);
                if (!q.failed()) {
                    requests.add(catalogueRequest(component, metric, q.text().trim(), source));
                }
            }
        }
        return requests;
    }

    /**
     * What a query may interpolate: the selected environment's variables (refused when the file
     * does not declare it), the run's, and the secrets.
     */
    private Interpolation.Scope scope(ServerMetricsConfig config, String environment, Run run)
            throws CollectionException {
        String envName = environment != null ? environment : config.defaultEnv();
        Map<String, String> envVars = new LinkedHashMap<>();
        if (envName != null) {
            Map<String, String> declared = config.environments().get(envName);
            if (declared == null) {
                throw new CollectionException("environment '" + envName + "' is not declared under "
                        + "serverMetrics.environments" + (config.environments().isEmpty()
                        ? " (none are)" : " (declared: " + String.join(", ", config.environments().keySet()) + ")"));
            }
            envVars.putAll(declared);
            envVars.put("name", envVars.containsKey("name") ? envVars.get("name") : envName);
        }
        Map<String, String> runVars = new LinkedHashMap<>();
        runVars.put("startTs", String.valueOf(run.window().getStart()));
        runVars.put("endTs", String.valueOf(run.window().getEnd()));
        runVars.put("runId", run.runId());
        runVars.put("testName", run.testName() == null ? "" : run.testName());
        return new Interpolation.Scope(envVars, runVars, secrets);
    }

    /**
     * An assert's query as it is asked: the query itself is trimmed (ServerKpi, U7), but an
     * ${env.*} value is taken as written, so "${env.q}" with q = " lag " would still ask for padded
     * text. Trimmed here too: no backend gives surrounding whitespace a meaning, and a CSV trims its
     * cells. A query that did not resolve is kept as written.
     */
    private static String resolvedText(ServerKpi kpi, Interpolation.Result q) {
        return q.failed() ? kpi.query() : q.text().trim();
    }

    /** How an assert's series is asked of {@code source}. */
    private SeriesSource.Request assertRequest(ServerMetricsConfig config, ServerKpi kpi, String resolvedText,
                                               ServerSource source) {
        // A catalogue metric's rows are named by the metric; a custom assert's by its
        // query, which is the series' identity on every backend (§8b).
        String metricKey = kpi.metric() != null ? kpi.metric() : resolvedText;
        SeriesSource.Request request = new SeriesSource.Request(resolvedText, kpi.gate(), metricKey);
        // A custom query that interpolates is named in the NEXT line and the CSV template as the file
        // writes it: a CSV may carry either text (N5).
        if (kpi.metric() == null && kpi.query() != null && !kpi.query().equals(resolvedText)) {
            request = request.alsoWrittenAs(kpi.query());
        }
        // A metric with no prometheus query has only its neutral identity to ask by: a
        // source that fetches by query says it cannot answer, and the assert is FAILED in
        // those words - SOURCE_FAILED, never a NO_MATCH nobody can explain.
        CatalogueMetric neutral = neutralMetricOf(config, kpi);
        if (neutral != null) {
            request = request.asNeutral(neutral.qualifiedName());
        }
        // A file exported from another tool (--from): the catalogue's name for the metric in
        // that tool's export, which only a catalogue metric has. A custom query is read as
        // it always was.
        CatalogueMetric named = source.fromTool() == null ? null : catalogueMetricOf(config, kpi);
        if (named != null) {
            request = request.fromTool(source.fromTool(), named);
        }
        return request;
    }

    /** How a component's catalogue metric is asked of {@code source} (D1): once, never settled. */
    private static SeriesSource.Request catalogueRequest(ServerGate component, CatalogueMetric metric, String text,
                                                         ServerSource source) {
        SeriesSource.Request request = new SeriesSource.Request(text, component.id(), metric.name(), true);
        if (metric.neutral()) {
            request = request.asNeutral(metric.qualifiedName());
        }
        // Read as the tool's export too, under --from: a metric it does not name and the
        // file does not carry fails, and is skipped like any other.
        if (source.fromTool() != null) {
            request = request.fromTool(source.fromTool(), metric);
        }
        return request;
    }

    /**
     * The catalogue metric an assert names, when the assert's query is that metric's neutral
     * identity ({@link CatalogueMetric#neutral()}); null for every other assert — a custom query,
     * a metric with a prometheus query, one that does not resolve, and one that also wrote a
     * query of its own (refused by the mapper, and still collected by what it wrote).
     */
    private CatalogueMetric neutralMetricOf(ServerMetricsConfig config, ServerKpi kpi) {
        CatalogueMetric metric = catalogueMetricOf(config, kpi);
        return metric != null && metric.neutral()
                && metric.identity(config.gate(kpi.gate()).match()).equals(kpi.query()) ? metric : null;
    }

    /**
     * The catalogue metric an assert names — its {@code metric}, in its component's type — or null:
     * a custom query, a component the file does not declare, or a type or metric the catalogue does
     * not know. The CSV finds the assert's rows by that metric's name ({@code metricKey}), so it is
     * the metric whose tool names are looked up under {@code --from}.
     */
    private CatalogueMetric catalogueMetricOf(ServerMetricsConfig config, ServerKpi kpi) {
        if (catalogue == null || kpi.metric() == null || kpi.gate() == null) {
            return null;
        }
        ServerGate component = config.gate(kpi.gate());
        CatalogueType type = component == null ? null : catalogue.type(component.type());
        return type == null ? null : type.metric(kpi.metric());
    }

    /** The payload's record of what an answer was read as, or null when it was read under our own name. */
    private static ServerMetricsPayload.ReadAs readAs(SeriesSource.Answer answer) {
        ToolName t = answer.readAs();
        return t == null ? null : new ServerMetricsPayload.ReadAs(t.tool(), t.metric(), t.kind().key(), t.scale());
    }

    /**
     * D1 (2026-10-03): every catalogue metric of every declared component, beside the ones
     * the asserts name — so the page's metric picker can show what each did in this run and seed
     * a band from it. One fetch each, never settled ({@link SeriesSource.Request#oneShot}): these
     * numbers inform, they judge nothing.
     *
     * <p>Only an answer with data is recorded. A metric the source does not have (an exporter
     * without that collector, a CSV that exports a subset) would otherwise be an EMPTY or FAILED
     * series nobody asked for — and since nobody asked, it never makes the source PARTIAL. So is
     * a metric the source cannot fetch at all: one with no prometheus query, asked of Prometheus
     * or a recording ({@link SeriesSource.Answer#noQuery}), is skipped here for the same reason,
     * and FAILED only where an assert names it.
     */
    private void collectCatalogue(ServerMetricsConfig config, Interpolation.Scope scope, Run run,
                                  Map<String, ServerSource> resolved, Set<String> seen,
                                  ServerMetricsPayload payload, List<String> material) {
        ServerSource source = resolved.get(ServerSource.COLLECTION_ID);
        if (source == null || catalogue == null) {
            return;
        }
        SeriesSource reader = sources.get(source.type());
        if (reader == null) {
            return;
        }
        for (ServerGate component : config.gates().values()) {
            CatalogueType type = component.type() == null ? null : catalogue.type(component.type());
            if (type == null) {
                continue;
            }
            for (CatalogueMetric metric : type.metrics().values()) {
                // Its query, or its neutral identity when it has none: what the mapper resolves an
                // assert naming it to, so the dedupe key below is the one the asserts loop wrote.
                String template = metric.identity(component.match());
                Interpolation.Result q = Interpolation.resolve(template, scope, Interpolation.Site.QUERY);
                if (q.failed()) {
                    continue;
                }
                // Trimmed as an assert's resolved query is above, so the dedupe key below is the
                // one that loop wrote. The template itself cannot be padded: Catalogue refuses it.
                String text = q.text().trim();
                if (!seen.add(source.id() + "\u0000" + component.id() + "\u0000" + text)) {
                    continue;   // an assert names it: collected above, settled, with its status
                }
                SeriesSource.Request request = catalogueRequest(component, metric, text, source);
                SeriesSource.Answer answer = reader.collect(source, request, run.window());
                if (answer.failed() || answer.instances().isEmpty()) {
                    continue;
                }
                ServerMetricsPayload.SeriesRecord series = new ServerMetricsPayload.SeriesRecord();
                series.setSource(source.id());
                series.setComponent(component.id());
                series.setMetric(metric.name());
                series.setReadAs(readAs(answer));
                series.setQuery(template);
                series.setResolvedQuery(text);
                series.setInstances(answer.instances());
                double coverage = Coverage.of(answer.instances(), run.window(), source.stepMillis());
                series.setCoverage(Double.isNaN(coverage) ? null : round4(coverage));
                series.setStatus(statusFor(coverage, config.coverageFloor()).wireName());
                payload.getSeries().add(series);
                material.add(series.getSource() + "|" + series.getComponent() + "|" + series.getResolvedQuery());
            }
        }
    }

    /** The collector's one judgement about a series that arrived. */
    static CollectionStatus statusFor(double coverage, double floor) {
        if (Double.isNaN(coverage)) {
            return CollectionStatus.EMPTY;
        }
        if (coverage < floor) {
            return CollectionStatus.INSUFFICIENT;
        }
        return coverage >= 0.999 ? CollectionStatus.OK : CollectionStatus.PARTIAL;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
