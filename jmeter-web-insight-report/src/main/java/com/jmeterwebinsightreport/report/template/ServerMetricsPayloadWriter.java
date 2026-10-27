package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpiEvaluator;
import com.jmeterwebinsightreport.core.servermetrics.ServerMetricsConfig;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueType;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import com.jmeterwebinsightreport.report.gate.ServerGateEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Puts the collector's payload into the chart-data map the page loads, under
 * {@code serverMetrics} — the series M5's Server tab charts and M4's evaluator judges.
 *
 * <p>Through the chart-data map rather than a block of its own, because that map is the one
 * thing every output shares: it is counted by the external-data threshold, and inline,
 * external and streamed outputs all serialise it. A payload that entered somewhere else would
 * be the one that was missing from one of them.
 *
 * <p>Two caps, both logged when they bite and both stated in the data: at most
 * {@link #MAX_INSTANCES_PER_SERIES} instances per series (a 200-pod deployment is charted as
 * twenty of them, "and 180 more") and {@link #MAX_POINTS_PER_INSTANCE} points per instance
 * (a 2-hour run at 15 s is 480 points, so the cap is for a source that answered at a step far
 * finer than it declared; thinned by bucket, each bucket's lowest and highest point kept, so the
 * drawn line still reaches the spike a verdict was taken on). Capped, never silently truncated: the true counts travel beside
 * the lists, and a KPI is judged in M4 over the payload as written by the collector, not over
 * what the page draws.
 *
 * <p>Which twenty: the instances a verdict depends on first — for each KPI over the series, the
 * one its number came from ({@link ServerKpiEvaluator#decidingInstances(ServerKpi, ServerMetricsConfig,
 * ServerMetricsPayload)}) — then, for each catalogue metric of the series' component whose query
 * finds it, the one its {@code judgedActuals} number came from (the static
 * {@code ServerKpiEvaluator.decidingInstances}, under that metric's reducer and across), then the
 * rest worst first by the first assert over the series (else its first catalogue metric), ranked by
 * the same reduction ({@link ServerKpiEvaluator#rankedInstances(ServerKpi, ServerMetricsConfig,
 * ServerMetricsPayload)}), then collector order, and the kept set drawn in collector order. Filling
 * the free slots in collector order kept an instance far inside the band and dropped a second one
 * past it (C5: S4's C08, 26 instances, 83.3 over a warn at 80 not drawn, 6 drawn). Keeping the
 * first twenty dropped the deciding instance whenever it sorted late: a FAIL beside a chart with nothing over the
 * band, the chart contradicting the verdict it sits under. The catalogue's tier exists for the same
 * contradiction where no assert names the number: the metric picker, Set thresholds and Generate
 * show a series' number from {@code judgedActuals} whether or not an assert names it, and draw the
 * series beside it - "low 0" over a flat line at 1, "peak 120 s" over one that never reached 3 (F6).
 * The asserts' instances come first, so a verdict's instance is never displaced by a catalogue
 * number's (L9-12, SF2). The client side keeps the worst as well ({@code RuleResult.cappedTo}).
 *
 * <p>A capped series also carries what the page cannot recompute from its copy, both taken over
 * the full series by the evaluator's own reduction ({@link ServerKpiEvaluator#judgedActual}):
 * {@code judgedMax}, the level's maximum, which is the number a custom-query seed is judged on; and
 * {@code judgedActuals}, for each catalogue metric of the series' component whose query finds it,
 * the number an assert naming that metric with the catalogue's reducer and across would be judged
 * on. Chart data only: the CI/CD JSON and JUnit read the evaluation, never this map, and a capped
 * series still shows the build's baked verdict.
 */
final class ServerMetricsPayloadWriter {

    static final int MAX_INSTANCES_PER_SERIES = 20;
    static final int MAX_POINTS_PER_INSTANCE = 4_000;

    private static final Logger log = LoggerFactory.getLogger(ServerMetricsPayloadWriter.class);

    private ServerMetricsPayloadWriter() {
    }

    static void addTo(Map<String, Object> chartData, ServerMetricsPayload payload) {
        addTo(chartData, payload, null);
    }

    /**
     * @param server the evaluation: its KPIs' deciding instances (when it judged this very payload),
     *               then its block's catalogue metrics' (whenever it carries a block, a
     *               components-only file included), survive the cap first; null keeps collector
     *               order
     */
    static void addTo(Map<String, Object> chartData, ServerMetricsPayload payload, ServerGateEvaluation server) {
        addTo(chartData, payload, server, Catalogue.builtIn());
    }

    /**
     * As above, with the catalogue the components' metrics come from: the built-in one, but for a
     * test that judges a type the shipped files do not have (a metric with no prometheus query).
     */
    static void addTo(Map<String, Object> chartData, ServerMetricsPayload payload, ServerGateEvaluation server,
                      Catalogue types) {
        if (payload == null) {
            return;
        }
        Map<ServerMetricsPayload.SeriesRecord, Set<Integer>> asserted = assertedInstances(payload, server);
        Map<ServerMetricsPayload.SeriesRecord, Set<Integer>> catalogue =
                catalogueInstances(server == null ? null : server.config(), payload, types);
        Map<ServerMetricsPayload.SeriesRecord, List<Integer>> ranked = rankedInstances(payload, server, types);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("schema", payload.getSchema());
        block.put("runId", payload.getRunId());
        block.put("collectionId", payload.getCollectionId());
        block.put("environment", payload.getEnvironment());
        block.put("collectedAt", payload.getCollectedAt());
        if (payload.getWindow() != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("start", payload.getWindow().getStart());
            w.put("end", payload.getWindow().getEnd());
            w.put("rampUpEnd", payload.getWindow().getRampUpEnd());
            block.put("window", w);
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        for (ServerMetricsPayload.SourceRecord s : payload.getSources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("type", s.getType());
            // The tool a CSV was exported from (--from), which the Collection panel names. Only
            // when there is one, as component and metric below: an older page is byte-identical.
            if (s.getFrom() != null) {
                m.put("from", s.getFrom());
            }
            // A wide CSV (--layout wide) and the zone its zone-less times were read in
            // (--time-zone), which the Collection panel says beside it. Only when the collection
            // had them: a long file with no zone is the page it always was.
            if (s.getLayout() != null) {
                m.put("layout", s.getLayout());
            }
            if (s.getTimeZone() != null) {
                m.put("timeZone", s.getTimeZone());
            }
            // A long CSV's column names (--csv-columns) and the component its rows were read as
            // (--component), likewise only when the collection had them.
            if (s.getColumns() != null) {
                m.put("columns", s.getColumns());
            }
            if (s.getComponent() != null) {
                m.put("component", s.getComponent());
            }
            m.put("status", s.getStatus());
            m.put("stepMillis", s.getStepMillis());
            m.put("clockOffsetMs", s.getClockOffsetMs());
            m.put("resolvedUrl", s.getResolvedUrl());
            if (s.getError() != null) {
                m.put("error", s.getError());
            }
            sources.add(m);
        }
        block.put("sources", sources);

        int instancesDropped = 0;
        int pointsDropped = 0;
        List<Map<String, Object>> series = new ArrayList<>();
        for (ServerMetricsPayload.SeriesRecord s : payload.getSeries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", s.getSource());
            // Written only when the collector recorded it, so a page built from an older payload
            // is byte-identical; report.js matches on it exactly as findByQuery does.
            if (s.getComponent() != null) {
                m.put("component", s.getComponent());
            }
            if (s.getMetric() != null) {
                m.put("metric", s.getMetric());
            }
            // Which of the tool's names the series was read under, and how it was converted: an
            // open row says so, beside the number it explains.
            if (s.getReadAs() != null) {
                Map<String, Object> readAs = new LinkedHashMap<>();
                readAs.put("tool", s.getReadAs().getTool());
                readAs.put("metric", s.getReadAs().getMetric());
                readAs.put("kind", s.getReadAs().getKind());
                readAs.put("scale", s.getReadAs().getScale());
                m.put("readAs", readAs);
            }
            m.put("query", s.getQuery());
            m.put("resolvedQuery", s.getResolvedQuery());
            m.put("status", s.getStatus());
            m.put("coverage", s.getCoverage());
            if (s.getError() != null) {
                m.put("error", s.getError());
            }
            m.put("instanceCount", s.getInstances().size());
            List<Map<String, Object>> instances = new ArrayList<>();
            Set<Integer> kept = keptInstances(s.getInstances().size(), asserted.get(s), catalogue.get(s), ranked.get(s));
            if (kept.size() < s.getInstances().size()) {
                instancesDropped += s.getInstances().size() - kept.size();
                m.put("instancesCapped", true);
            }
            for (int index = 0; index < s.getInstances().size(); index++) {
                if (!kept.contains(index)) {
                    continue;
                }
                ServerMetricsPayload.InstanceRecord inst = s.getInstances().get(index);
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("labels", inst.getLabels());
                im.put("pointCount", inst.getPoints().size());
                List<ServerMetricsPayload.Point> points = inst.getPoints();
                if (points.size() > MAX_POINTS_PER_INSTANCE) {
                    List<ServerMetricsPayload.Point> thinned = thin(points, MAX_POINTS_PER_INSTANCE);
                    // What was actually left out: a bucket whose lowest and highest are one point keeps one.
                    pointsDropped += points.size() - thinned.size();
                    im.put("pointsCapped", true);
                    points = thinned;
                }
                im.put("points", points);
                instances.add(im);
            }
            m.put("instances", instances);
            // What the page cannot recompute from a capped copy, computed over the full series by the
            // evaluator's own reduction. Only on a capped series: an uncapped one the page reads whole
            // and recomputes exactly (reduceAcross, parity-pinned), and its chart data stays as it was.
            if (m.containsKey("instancesCapped") || anyPointsCapped(s)) {
                ServerMetricsConfig config = server == null ? null : server.config();
                // The true maximum inside the judged window, over the instances and points that were
                // not shipped: the number a custom-query seed (reduce max) is judged on. Generate
                // seeds a custom query from it (C-8) - from the thinned copy, a dropped spike made a
                // seed that FAILs on the run it came from.
                Double max = ServerKpiEvaluator.judgedMax(config, payload, s);
                if (max != null) {
                    m.put("judgedMax", max);
                }
                // A catalogue metric's number is not the maximum: up is judged on its lowest
                // instance's minimum, database.size.growth on a slope per minute (SF8). So the
                // number an assert taking the catalogue's defaults would compare travels too, for
                // the picker, Set thresholds and Generate - whether or not an assert names the metric,
                // and per metric, because one series can answer for two of them.
                Map<String, Double> actuals = judgedActuals(config, payload, s, types);
                if (!actuals.isEmpty()) {
                    m.put("judgedActuals", actuals);
                }
            }
            series.add(m);
        }
        block.put("series", series);
        chartData.put("serverMetrics", block);

        if (instancesDropped > 0 || pointsDropped > 0) {
            // Never silent: a capped payload that said nothing would look like a smaller run.
            log.info("Server-metrics payload capped for the page: {} instance(s) beyond {} per series and "
                            + "{} point(s) beyond {} per instance are not drawn; the counts travel with the data",
                    instancesDropped, MAX_INSTANCES_PER_SERIES, pointsDropped, MAX_POINTS_PER_INSTANCE);
        }
    }

    /**
     * For each series that will be capped, the instances the declared KPIs' numbers come from.
     * Keyed by identity: two KPIs over one query share one series record, and their deciding
     * instances pool. Only when the evaluation judged this very payload: the KPIs' instances are
     * the evaluation's answer, and on a components-only file - no assert, so nothing judged and no
     * payload on the evaluation - there is none.
     */
    private static Map<ServerMetricsPayload.SeriesRecord, Set<Integer>> assertedInstances(
            ServerMetricsPayload payload, ServerGateEvaluation server) {
        Map<ServerMetricsPayload.SeriesRecord, Set<Integer>> out = new IdentityHashMap<>();
        if (server == null || server.payload() != payload) {
            return out;
        }
        ServerKpiEvaluator evaluator = new ServerKpiEvaluator();
        for (ServerKpi kpi : server.config().kpis()) {
            ServerMetricsPayload.SeriesRecord s = payload.findByQuery(kpi.source(), kpi.query(), kpi.gate());
            if (s == null || s.getInstances().size() <= MAX_INSTANCES_PER_SERIES) {
                continue;
            }
            out.computeIfAbsent(s, k -> new LinkedHashSet<Integer>())
                    .addAll(evaluator.decidingInstances(kpi, server.config(), payload));
        }
        return out;
    }

    /**
     * For each series that will be capped, the instances the {@link #judgedActuals} numbers come
     * from: for every catalogue metric whose query finds the series, the instance its number came
     * from, pooled - two for Kafka's shared lag query, whose {@code consumer.lag.total} (max) and
     * {@code consumer.lag.final} (last) can each be decided by a different instance.
     *
     * <p>Gated as {@link #judgedActuals} is, on the block alone and never on the evaluation's
     * payload, because it answers for the same numbers: the picker shows a capped series' number
     * whether or not an assert names it, on a components-only file above all (F6).
     */
    private static Map<ServerMetricsPayload.SeriesRecord, Set<Integer>> catalogueInstances(
            ServerMetricsConfig config, ServerMetricsPayload payload, Catalogue types) {
        Map<ServerMetricsPayload.SeriesRecord, Set<Integer>> out = new IdentityHashMap<>();
        if (config == null) {
            return out;
        }
        for (ServerMetricsPayload.SeriesRecord s : payload.getSeries()) {
            if (s.getInstances().size() <= MAX_INSTANCES_PER_SERIES) {
                continue;
            }
            for (CatalogueMetric metric : catalogueMetricsOf(config, payload, s, types)) {
                List<Integer> deciding = ServerKpiEvaluator.decidingInstances(config, payload, s, metric.reducer(),
                        metric.across());
                if (!deciding.isEmpty()) {
                    out.computeIfAbsent(s, k -> new LinkedHashSet<Integer>()).addAll(deciding);
                }
            }
        }
        return out;
    }

    /**
     * For each series that will be capped, its instances worst first, for the slots the deciding
     * instances leave (C5): ranked by the first assert over the series when the evaluation judged
     * this very payload, as {@link #assertedInstances} is gated, else by the first catalogue metric
     * whose query finds it, as {@link #catalogueInstances} is. A series neither ranks keeps collector
     * order.
     */
    private static Map<ServerMetricsPayload.SeriesRecord, List<Integer>> rankedInstances(
            ServerMetricsPayload payload, ServerGateEvaluation server, Catalogue types) {
        Map<ServerMetricsPayload.SeriesRecord, List<Integer>> out = new IdentityHashMap<>();
        if (server == null || server.config() == null) {
            return out;
        }
        if (server.payload() == payload) {
            ServerKpiEvaluator evaluator = new ServerKpiEvaluator();
            for (ServerKpi kpi : server.config().kpis()) {
                ServerMetricsPayload.SeriesRecord s = payload.findByQuery(kpi.source(), kpi.query(), kpi.gate());
                if (s == null || s.getInstances().size() <= MAX_INSTANCES_PER_SERIES || out.containsKey(s)) {
                    continue;
                }
                List<Integer> ranked = evaluator.rankedInstances(kpi, server.config(), payload);
                if (!ranked.isEmpty()) {
                    out.put(s, ranked);
                }
            }
        }
        for (ServerMetricsPayload.SeriesRecord s : payload.getSeries()) {
            if (s.getInstances().size() <= MAX_INSTANCES_PER_SERIES || out.containsKey(s)) {
                continue;
            }
            for (CatalogueMetric metric : catalogueMetricsOf(server.config(), payload, s, types)) {
                List<Integer> ranked = ServerKpiEvaluator.rankedInstances(server.config(), payload, s,
                        metric.reducer(), metric.across());
                if (!ranked.isEmpty()) {
                    out.put(s, ranked);
                    break;
                }
            }
        }
        return out;
    }

    /** As below, with no ranking: the free slots filled in collector order. */
    static Set<Integer> keptInstances(int count, Set<Integer> asserted, Set<Integer> catalogue) {
        return keptInstances(count, asserted, catalogue, null);
    }

    /**
     * The indices to ship: every one when the series is under the cap; otherwise the asserts'
     * deciding instances first, then the catalogue's (each tier in index order, the whole at most
     * the cap), then {@code ranked}'s in its order (worst first), filled up to the cap in collector
     * order. Returned sorted, so what is drawn keeps the collector's order. Asserts first: an
     * instance a verdict came from is never displaced by one a catalogue number came from.
     */
    static Set<Integer> keptInstances(int count, Set<Integer> asserted, Set<Integer> catalogue, List<Integer> ranked) {
        Set<Integer> kept = new TreeSet<>();
        if (count <= MAX_INSTANCES_PER_SERIES) {
            for (int i = 0; i < count; i++) {
                kept.add(i);
            }
            return kept;
        }
        keepFirst(kept, asserted, count);
        keepFirst(kept, catalogue, count);
        if (ranked != null) {
            for (int i : ranked) {
                if (kept.size() == MAX_INSTANCES_PER_SERIES) {
                    break;
                }
                if (i >= 0 && i < count) {
                    kept.add(i);
                }
            }
        }
        for (int i = 0; i < count && kept.size() < MAX_INSTANCES_PER_SERIES; i++) {
            kept.add(i);
        }
        return kept;
    }

    /** Adds {@code tier}'s valid indices to {@code kept}, in index order, while it is under the cap. */
    private static void keepFirst(Set<Integer> kept, Set<Integer> tier, int count) {
        if (tier == null) {
            return;
        }
        for (int i : new TreeSet<>(tier)) {
            if (kept.size() == MAX_INSTANCES_PER_SERIES) {
                return;
            }
            if (i >= 0 && i < count) {
                kept.add(i);
            }
        }
    }

    /**
     * For every catalogue metric of {@code s}'s component whose assert would be judged on
     * {@code s}, the number it would compare ({@link ServerKpiEvaluator#judgedActual} over that
     * metric's own reducer and across), in catalogue order; empty when there is none.
     *
     * <p>Per metric, not per series, because one series can answer for two: Kafka's
     * {@code consumer.lag.total} (max) and {@code consumer.lag.final} (last) share one query, the
     * collector dedupes on the query and writes one series named after one of them, and a single
     * number under that name was shown and banded for the other - a peak of 100 where the build
     * compared 10,000, and a fail band that FAILed the run it was suggested from.
     *
     * <p>Still needed now that each number's instance is drawn (F6): a capped copy is not the
     * series - the page cannot tell which instances are missing, and thinned points stay thinned -
     * so the page never recomputes one, and reads this.
     */
    static Map<String, Double> judgedActuals(ServerMetricsConfig config, ServerMetricsPayload payload,
                                             ServerMetricsPayload.SeriesRecord s, Catalogue types) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (CatalogueMetric metric : catalogueMetricsOf(config, payload, s, types)) {
            Double actual = ServerKpiEvaluator.judgedActual(config, payload, s, metric.reducer(), metric.across());
            if (actual != null) {
                out.put(metric.name(), actual);
            }
        }
        return out;
    }

    /**
     * Every catalogue metric of {@code s}'s component whose assert would be judged on {@code s}, in
     * catalogue order; empty when there is none.
     *
     * <p>"Would be judged on" is the evaluator's own lookup: the metric's identity — its query, or
     * the neutral {@code <type>:<metric>} when it has none ({@link CatalogueMetric#identity}) — as
     * the mapper resolves an assert's {@code metric} (its component's type and {@code match}), found through
     * {@link ServerMetricsPayload#findByQuery} — which is what the page's {@code findSeries} mirrors,
     * so a metric here is one the page finds this series for when it asks about it.
     *
     * <p>Empty for a component the file does not declare (a payload series of a component renamed
     * since): the type comes from the component as declared, and a number under a guessed type
     * would be a second definition.
     */
    private static List<CatalogueMetric> catalogueMetricsOf(ServerMetricsConfig config, ServerMetricsPayload payload,
                                                            ServerMetricsPayload.SeriesRecord s, Catalogue types) {
        List<CatalogueMetric> out = new ArrayList<>();
        if (config == null || s.getComponent() == null) {
            return out;
        }
        ServerGate component = config.gate(s.getComponent());
        CatalogueType type = component == null ? null : types.type(component.type());
        if (type == null) {
            return out;
        }
        for (CatalogueMetric metric : type.metrics().values()) {
            // The key the mapper resolves an assert naming it to: its query, or its neutral identity.
            String query = metric.identity(component.match());
            if (payload.findByQuery(ServerSource.COLLECTION_ID, query, component.id()) == s) {
                out.add(metric);
            }
        }
        return out;
    }

    private static boolean anyPointsCapped(ServerMetricsPayload.SeriesRecord s) {
        for (ServerMetricsPayload.InstanceRecord inst : s.getInstances()) {
            if (inst.getPoints().size() > MAX_POINTS_PER_INSTANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * At most {@code max} points, chosen by bucket (F6): the first and last kept, the points between
     * them split into {@code (max - 2) / 2} consecutive buckets, and each bucket's lowest and
     * highest point kept, in time order. So the drawn line reaches every extreme of the series -
     * the spike a {@code max} judged, the dip a {@code min} judged. Every k-th point, the rule this
     * replaced, dropped whichever fell between two strides: on an instance above the cap (16.7 h at
     * 15 s), a FAIL beside a chart whose line never reached the band.
     *
     * <p>A NaN is never a bucket's extreme; a bucket of nothing but NaN keeps its first point, so a
     * gap stays a gap. A shape, not a prefix, and still not the series: the verdict is the build's,
     * and a capped series carries the numbers the page cannot recompute ({@code judgedActuals}).
     *
     * <p>The page's gap markers depend on this shape: report.js {@code gapUnit} derives, from the
     * true point count and the count shipped, the most steps two kept points of a series that
     * missed nothing can be apart (the first of one bucket and the last of the next), and
     * {@code server-chart.test.js} mirrors this method to hold it to that. Change one, change both.
     *
     * <p>A stated limit (REV-10): the kept points are all the page has to draw with, and reaching
     * that spike and that dip is all the drawn line promises. At the collection step, the page's
     * default, it joins them in time order, so it swings between each of these buckets' lowest and
     * highest point rather than following the series, whatever the reducer. At a coarser rung
     * (report.js {@code bucketServerPoints}), a {@code max} or {@code min} line takes the extreme
     * kept point in each page bucket, and any other reducer's averages the kept points: an average
     * of extremes, the mid-range only where a page bucket holds one of these buckets' pair, never
     * the mean of the points the collector saw. The verdict is not drawn from it: it is the build's.
     */
    private static List<ServerMetricsPayload.Point> thin(List<ServerMetricsPayload.Point> points, int max) {
        int n = points.size();
        if (n <= max) {
            return points;
        }
        List<ServerMetricsPayload.Point> out = new ArrayList<>(max);
        out.add(points.get(0));
        int interior = n - 2;
        int buckets = (max - 2) / 2;
        for (int b = 0; b < buckets; b++) {
            int from = 1 + (int) ((long) b * interior / buckets);
            int to = 1 + (int) ((long) (b + 1) * interior / buckets);
            int lo = -1;
            int hi = -1;
            for (int i = from; i < to; i++) {
                double v = points.get(i).getValue();
                if (Double.isNaN(v)) {
                    continue;
                }
                if (lo < 0 || v < points.get(lo).getValue()) {
                    lo = i;
                }
                if (hi < 0 || v > points.get(hi).getValue()) {
                    hi = i;
                }
            }
            if (lo < 0) {
                if (from < to) {
                    out.add(points.get(from));
                }
                continue;
            }
            out.add(points.get(Math.min(lo, hi)));
            if (lo != hi) {
                out.add(points.get(Math.max(lo, hi)));
            }
        }
        out.add(points.get(n - 1));
        return out;
    }
}
