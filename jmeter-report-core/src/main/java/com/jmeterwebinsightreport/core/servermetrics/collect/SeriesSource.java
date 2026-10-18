package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.ToolName;

import java.util.List;

/**
 * Where a series comes from: one implementation per {@link ServerSource.Type}.
 *
 * <p>A source answers with instances or with an error — never with a status. The status
 * (OK, PARTIAL, INSUFFICIENT, EMPTY, FAILED) is the collector's call, because it depends on
 * the coverage floor and the window, which a source does not know and should not.
 */
public interface SeriesSource {

    /** What one query produced. */
    final class Answer {
        private final List<ServerMetricsPayload.InstanceRecord> instances;
        private final String error;
        private final ToolName readAs;

        private Answer(List<ServerMetricsPayload.InstanceRecord> instances, String error, ToolName readAs) {
            this.instances = instances;
            this.error = error;
            this.readAs = readAs;
        }

        public static Answer of(List<ServerMetricsPayload.InstanceRecord> instances) {
            return new Answer(instances, null, null);
        }

        /**
         * Instances read under a tool's name and converted to the catalogue's unit by it
         * ({@link Request#fromTool}); null {@code readAs} is {@link #of(List)}.
         */
        public static Answer of(List<ServerMetricsPayload.InstanceRecord> instances, ToolName readAs) {
            return new Answer(instances, null, readAs);
        }

        /** The source never answered, or answered with something that is not a result. */
        public static Answer failed(String error) {
            return new Answer(java.util.Collections.<ServerMetricsPayload.InstanceRecord>emptyList(), error, null);
        }

        /**
         * {@code --from}: the catalogue says the tool has no single metric for the KPI (an explicit
         * {@code null} in its {@code names}), and the file has no rows under the catalogue's own name
         * either. It says what would answer it. ASCII, as every console line is.
         */
        public static Answer noToolMetric(Request request) {
            return failed(request.tool() + " has no single metric for " + request.catalogueMetric()
                    + " - put the computed series in the file under " + request.metricKey());
        }

        /**
         * {@code --from}: the catalogue does not say what the tool calls the KPI, and the file has no
         * rows under the catalogue's own name. Not "the tool has none": nobody has said.
         */
        public static Answer notNamedInTool(Request request) {
            return failed("the catalogue does not name " + request.catalogueMetric() + " in " + request.tool()
                    + "'s export - put the series in the file under " + request.metricKey());
        }

        /**
         * What a source that fetches by query answers a {@link Request#neutral() neutral} request
         * with, having sent nothing: the metric has no query to send. For an assert the series is
         * FAILED with this sentence and judged SOURCE_FAILED; it says what would answer it. ASCII,
         * as every console line is.
         */
        public static Answer noQuery(Request request) {
            return failed(request.neutralFor() + " has no " + Catalogue.PROMETHEUS + " query in the catalogue"
                    + " - export it to a file and collect it with --csv");
        }

        public List<ServerMetricsPayload.InstanceRecord> instances() { return instances; }
        public String error() { return error; }
        public boolean failed() { return error != null; }

        /**
         * The tool's name the rows were read under and converted by, or null: a series read under
         * the catalogue's own name, every series of a file collected without {@code --from}, and a
         * failure.
         */
        public ToolName readAs() { return readAs; }
    }

    /**
     * What the collector asks for: the resolved query, which is the series' identity on every
     * backend and all a query language needs, and the component and metric key a source whose
     * rows are already the data (CSV) finds them by — a catalogue metric's name, or a custom
     * assert's query (plans/server-metrics-backends.md §8b).
     *
     * <p>For a catalogue metric with no prometheus query the "query" is its neutral identity,
     * {@code <type>:<metric>} ({@link CatalogueMetric#identity}), which no query language
     * understands; the request then says so ({@link #neutral()}), so a source that fetches by
     * query answers {@link Answer#noQuery} instead of sending it. A CSV ignores the flag.
     *
     * <p>For a catalogue metric read from a file another tool exported ({@code --from TOOL}), the
     * request also carries what the catalogue says that tool calls it ({@link #fromTool}): the
     * collector knows the catalogue, the CSV knows the rows, and this is all the CSV needs of the
     * one to read the other.
     */
    final class Request {
        private final String resolvedQuery;
        private final String component;
        private final String metricKey;
        private final boolean oneShot;
        private final String neutralFor;
        private final String tool;
        private final String catalogueMetric;
        private final ToolName toolName;
        private final boolean toolDeclaresNone;

        public Request(String resolvedQuery, String component, String metricKey) {
            this(resolvedQuery, component, metricKey, false);
        }

        public Request(String resolvedQuery, String component, String metricKey, boolean oneShot) {
            this(resolvedQuery, component, metricKey, oneShot, null, null, null, null, false);
        }

        private Request(String resolvedQuery, String component, String metricKey, boolean oneShot,
                        String neutralFor, String tool, String catalogueMetric, ToolName toolName,
                        boolean toolDeclaresNone) {
            this.resolvedQuery = resolvedQuery;
            this.component = component;
            this.metricKey = metricKey;
            this.oneShot = oneShot;
            this.neutralFor = neutralFor;
            this.tool = tool;
            this.catalogueMetric = catalogueMetric;
            this.toolName = toolName;
            this.toolDeclaresNone = toolDeclaresNone;
        }

        /**
         * This request, for a catalogue metric with no prometheus query: {@link #resolvedQuery()}
         * is its neutral identity, not a query.
         *
         * @param catalogueMetric {@code <type>.<metric>} ({@link CatalogueMetric#qualifiedName}), for the sentence
         */
        public Request asNeutral(String catalogueMetric) {
            return new Request(resolvedQuery, component, metricKey, oneShot, catalogueMetric, tool,
                    this.catalogueMetric, toolName, toolDeclaresNone);
        }

        /**
         * This request, for catalogue metric {@code metric} in a file exported from {@code tool}
         * ({@code wir-collect --csv FILE --from TOOL}): its rows are looked for under the tool's name
         * first and converted by it, then under {@link #metricKey()}, the catalogue's own name, as
         * they are. A source that is not a CSV ignores it.
         *
         * @param tool one of {@link Catalogue#TOOLS}
         */
        public Request fromTool(String tool, CatalogueMetric metric) {
            return new Request(resolvedQuery, component, metricKey, oneShot, neutralFor, tool,
                    metric.qualifiedName(), metric.name(tool), metric.declaresNone(tool));
        }

        /** The tool the file was exported from, or null: no {@code --from}, or not a catalogue metric. */
        public String tool() { return tool; }

        /** {@code <type>.<metric>} of a {@link #fromTool} request, for the sentences; null otherwise. */
        public String catalogueMetric() { return catalogueMetric; }

        /**
         * What {@link #tool()} calls the metric and how its number becomes the catalogue's, or null —
         * when the catalogue says the tool has none ({@link #toolDeclaresNone()}), and when it does
         * not say.
         */
        public ToolName toolName() { return toolName; }

        /** Whether the catalogue says {@link #tool()} has no single metric for this one (an explicit null). */
        public boolean toolDeclaresNone() { return toolDeclaresNone; }

        /** Whether {@link #resolvedQuery()} is a neutral identity rather than a backend query. */
        public boolean neutral() { return neutralFor != null; }

        /** {@code <type>.<metric>} of a {@link #neutral()} request; null for a real query. */
        public String neutralFor() { return neutralFor; }

        public String resolvedQuery() { return resolvedQuery; }
        public String component() { return component; }
        public String metricKey() { return metricKey; }

        /**
         * Fetch once, never wait for the source to settle: a catalogue metric no assert names,
         * collected so the page can show what it did (D1). A source that does not wait ignores it.
         */
        public boolean oneShot() { return oneShot; }
    }

    /**
     * @param resolvedQuery the query with every placeholder resolved
     * @param window        the window to fetch, epoch millis
     */
    Answer collect(ServerSource source, String resolvedQuery, ServerMetricsPayload.Window window);

    /**
     * What the collector calls. A source that needs only the query need not override it — and
     * has nothing to send for a {@link Request#neutral() neutral} request, so it is answered
     * {@link Answer#noQuery} here and never reaches {@link #collect(ServerSource, String,
     * ServerMetricsPayload.Window)}. A source that overrides this answers it the same way, or,
     * as a CSV does, finds the rows without the query.
     */
    default Answer collect(ServerSource source, Request request, ServerMetricsPayload.Window window) {
        if (request.neutral()) {
            return Answer.noQuery(request);
        }
        return collect(source, request.resolvedQuery(), window);
    }
}
