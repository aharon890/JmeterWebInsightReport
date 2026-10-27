package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.servermetrics.KpiResult;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpiEvaluator;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueType;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import com.jmeterwebinsightreport.report.gate.ServerFixtures;
import com.jmeterwebinsightreport.report.gate.ServerGateEvaluation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jmeterwebinsightreport.report.gate.ServerFixtures.START;
import static com.jmeterwebinsightreport.report.gate.ServerFixtures.STEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SF8, the Java half: on a capped series the page holds a copy (twenty instances, points thinned)
 * that the build never judged, so the metric picker, Set thresholds and Generate need the number
 * the build WOULD compare - {@code judgedActuals}, one per catalogue metric whose query finds the
 * series, computed over the full series by the evaluator's own reduction, under that metric's
 * reducer and across. Every test here holds it to the evaluator's actual for an assert that names
 * the metric and takes the catalogue's defaults, on the same payload; a second number computed some
 * other way is the drift this exists to stop.
 *
 * <p>The window is ServerFixtures' {@code [START, START + 60s]} with no ramp-up, and every
 * instance carries a point every 15 s from START to the window's end, inclusive: five points.
 * Payloads are built in the collector's shape: one series per component and query, however many
 * metrics share the query.
 */
class ServerMetricsPayloadWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int POINTS = 5;

    /**
     * Over the cap: the last five are past it, and are drawn only where a verdict's or a catalogue
     * number's deciding instance is among them (F6).
     */
    private static final int N = ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES + 5;

    private static final String COMPONENTS = "{\"orders-db\":{\"type\":\"postgres\"},\"bus\":{\"type\":\"kafka\"}}";

    // ---- every catalogue metric -------------------------------------------------------------

    @Test
    void everyCatalogueMetricCarriesTheNumberAnAssertTakingItsDefaultsIsJudgedOn() throws Exception {
        // One capped series per distinct query of every built-in metric, as the collector writes
        // them, all with the same values: instance 24 holds the highest point (a spike at t=3) and
        // the steepest growth, instance 23 the lowest point (a dip at t=1). Both sort past the cap.
        // Kept in collector order, neither was among the twenty drawn when no assert names the
        // series, and the page's copy answered every reducer differently from the build. Each
        // metric's deciding instance is drawn now (F6), so the line reaches its number - but the
        // number still travels: a capped copy is not the series, and the page never recomputes one.
        List<ServerMetricsPayload.SeriesRecord> series = new ArrayList<>();
        java.util.Set<String> collected = new java.util.HashSet<>();
        List<String> asserts = new ArrayList<>();
        List<String[]> metrics = new ArrayList<>();
        for (String component : new String[] {"orders-db", "bus"}) {
            String type = "orders-db".equals(component) ? "postgres" : "kafka";
            for (CatalogueMetric m : Catalogue.builtIn().type(type).metrics().values()) {
                String query = m.identity(null);
                // The collector dedupes on the component and the identity (Collector.collectCatalogue).
                if (collected.add(component + "\u0000" + query)) {
                    series.add(catalogueSeries(component, type, m.name(), ServerMetricsPayloadWriterTest::mixed));
                }
                asserts.add(withDefaults(component, m.name(), "\"fail\":{\"gt\":1000000000000000}"));
                metrics.add(new String[] {component, m.name(), query});
            }
        }
        assertTrue(metrics.size() > 15, "both built-in types, every metric: " + metrics.size());
        assertTrue(series.size() < metrics.size(), "the catalogue has metrics that share a query");

        Judged asserted = judge(ServerFixtures.block(COMPONENTS, asserts.toArray(new String[0])), payload(series));
        ServerMetricsPayload unnamedPayload = payload(series);
        Judged componentsOnly = judge(ServerFixtures.block(COMPONENTS), unnamedPayload);
        boolean sawAMetricWhoseNumberIsNotTheMaximum = false;
        int drawnChecked = 0;
        for (String[] cm : metrics) {
            KpiResult r = asserted.result(cm[0], cm[1]);
            assertTrue(r.status().gates(), cm[1] + " was judged: " + r);
            // The series the page finds for the metric, by its query, as findByQuery finds it.
            JsonNode found = asserted.seriesFor(cm[0], cm[2]);
            assertTrue(found.path("instancesCapped").asBoolean(), cm[1] + " is capped");
            assertEquals(r.actual(), actualOf(found, cm[1]), 0.0,
                    cm[0] + "/" + cm[1] + ": the number the build compared, when an assert names it");
            // D1: a components-only file collects every catalogue metric, and nothing names them.
            JsonNode unnamed = componentsOnly.seriesFor(cm[0], cm[2]);
            assertEquals(r.actual(), actualOf(unnamed, cm[1]), 0.0,
                    cm[0] + "/" + cm[1] + ": the same number when no assert names it");
            if (r.actual() != found.get("judgedMax").asDouble()) {
                sawAMetricWhoseNumberIsNotTheMaximum = true;
            }
            // F6: the line drawn beside that number reaches it. Under across max or min the number
            // is one instance's, so the drawn copy - the twenty the page received - folds to the very
            // number the build compared. (Under avg every instance contributes and no twenty of
            // twenty-five could; no built-in metric uses it.)
            CatalogueMetric metric = Catalogue.builtIn()
                    .type("orders-db".equals(cm[0]) ? "postgres" : "kafka").metric(cm[1]);
            if (metric.across() != com.jmeterwebinsightreport.core.servermetrics.Across.AVG) {
                Double drawn = ServerKpiEvaluator.judgedActual(componentsOnly.server.config(), unnamedPayload,
                        drawnCopy(unnamed), metric.reducer(), metric.across());
                assertEquals(actualOf(unnamed, cm[1]), drawn, 0.0,
                        cm[0] + "/" + cm[1] + ": the chart beside the number reaches it");
                drawnChecked++;
            }
        }
        assertTrue(sawAMetricWhoseNumberIsNotTheMaximum, "judgedMax is not every metric's number");
        assertEquals(metrics.size(), drawnChecked, "every built-in metric folds across max or min");
    }

    @Test
    void theAssertsInstancesComeFirstThenTheCataloguesThenCollectorOrder() {
        // L9-12/SF2: an instance a verdict came from is never displaced by one a catalogue number
        // came from. Twenty asserted instances fill the cap, and the catalogue's is not drawn.
        java.util.Set<Integer> asserted = new java.util.TreeSet<>();
        for (int i = 5; i < 25; i++) {
            asserted.add(i);
        }
        assertEquals(asserted, ServerMetricsPayloadWriter.keptInstances(25, asserted, java.util.Collections.singleton(0)));
        // One of each: both kept, the rest filled in collector order, drawn in collector order.
        java.util.Set<Integer> expected = new java.util.TreeSet<>();
        for (int i = 0; i < 18; i++) {
            expected.add(i);
        }
        expected.add(23);
        expected.add(24);
        assertEquals(expected, ServerMetricsPayloadWriter.keptInstances(25, java.util.Collections.singleton(24),
                java.util.Collections.singleton(23)));
        assertEquals(new java.util.ArrayList<>(expected), new java.util.ArrayList<>(ServerMetricsPayloadWriter
                .keptInstances(25, java.util.Collections.singleton(24), java.util.Collections.singleton(23))));
        // Neither: the first twenty. Under the cap: every one, whatever is named.
        java.util.Set<Integer> first = new java.util.TreeSet<>();
        for (int i = 0; i < 20; i++) {
            first.add(i);
        }
        assertEquals(first, ServerMetricsPayloadWriter.keptInstances(25, null, null));
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(0, 1, 2)),
                ServerMetricsPayloadWriter.keptInstances(3, null, java.util.Collections.singleton(7)));
    }

    @Test
    void theFreeSlotsGoToTheWorstInstancesNotTheFirstInCollectorOrder() throws Exception {
        // C5, S4's C08: 26 instances under a max/max assert warning over 80. The deciding instance
        // (0, at 92) was kept, and the other nineteen slots went to collector order, so a second
        // instance over the band (20, at 83.3) was not drawn while one at 6 (16) was: a WARN beside
        // a chart with one line over the band, the other breach missing. The free slots now go by
        // each instance's reduced value, worst first under the assert's across.
        int n = 26;
        ServerMetricsPayload p = payload(catalogueSeries("orders-db", "postgres", "connections.used.pct", n,
                (i, t) -> i == 0 ? 92 : i == 20 ? 83.3 : i == 16 ? 6 : 50));
        Judged j = judge(ServerFixtures.block(COMPONENTS,
                withDefaults("orders-db", "connections.used.pct", "\"warn\":{\"gt\":80}")), p);
        assertEquals(AssertionStatus.WARN, j.result("orders-db", "connections.used.pct").status());

        JsonNode s = j.series("orders-db", "connections.used.pct");
        assertTrue(s.get("instancesCapped").asBoolean());
        List<String> drawn = drawnLabels(s);
        assertEquals(ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES, drawn.size());
        assertTrue(drawn.contains("pg-1"), "the deciding instance: " + drawn);
        assertTrue(drawn.contains("pg-21"), "the other instance over the band: " + drawn);
        assertFalse(drawn.contains("pg-17"), "the lowest instance gives its slot up: " + drawn);
        // Still drawn in collector order.
        List<String> sorted = new ArrayList<>(drawn);
        sorted.sort(java.util.Comparator.comparingInt(l -> Integer.parseInt(l.substring(3))));
        assertEquals(sorted, drawn);
    }

    @Test
    void aCappedSeriesCarriesTheGapJavaNamesOverTheFullSeries() throws Exception {
        // The Stage 5 review: a LOW_COVERAGE reason names the longest stretch with no point (5.6),
        // and the page's mirror worked it out from its capped copy - twenty instances - so it could
        // name a gap the full series does not have. Like judgedMax, the gap travels on a capped series.
        // Window 0..60 s at 15 s, five slots: instance 0 alone covers slots 0 and 1, every other one only
        // slot 0, so slots 2..4 are empty for all - a gap from 15 s to the end.
        int n = ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES + 5;
        ServerMetricsPayload.SeriesRecord sparse = catalogueSeries("orders-db", "postgres", "connections.used.pct", n,
                (i, t) -> (t == 0 || (i == 0 && t == 1)) ? 50 : Double.NaN);
        // As the collector writes a series: no point where there was no value.
        for (ServerMetricsPayload.InstanceRecord inst : sparse.getInstances()) {
            inst.getPoints().removeIf(pt -> Double.isNaN(pt.getValue()));
        }
        ServerMetricsPayload p = payload(sparse);
        JsonNode s = judge(ServerFixtures.block(COMPONENTS), p).series("orders-db", "connections.used.pct");
        assertTrue(s.get("instancesCapped").asBoolean());
        JsonNode gap = s.get("judgedGap");
        assertNotNull(gap, "a capped series carries the gap Java names: " + s);
        assertEquals(ServerFixtures.START + 15_000, gap.get(0).asLong());
        assertEquals(ServerFixtures.START + 60_000, gap.get(1).asLong());
    }

    @Test
    void anUnassertedCappedSeriesBesideAnAssertedOneKeepsItsDecidingInstanceToo() throws Exception {
        // F6 on a file that DOES declare asserts: the evaluation carries the payload, so the
        // asserts' tier runs - and covers only the series an assert names. up is named by none,
        // is capped, and its instance that went down sorts past the cap. The picker shows up's
        // number from judgedActuals whether or not an assert names it, so its line keeps the
        // instance that number came from.
        int down = N - 2;
        int busiest = N - 1;
        ServerMetricsPayload p = payload(
                catalogueSeries("orders-db", "postgres", "connections.used.pct", (i, t) -> i == busiest ? 90 : 40 + t),
                catalogueSeries("orders-db", "postgres", "up", (i, t) -> i == down && t == 3 ? 0 : 1));

        Judged j = judge(ServerFixtures.block(COMPONENTS,
                withDefaults("orders-db", "connections.used.pct", "\"fail\":{\"gt\":95}")), p);
        assertTrue(j.server.evaluated(), "an assert is declared, so the evaluation carries the payload");

        JsonNode connections = j.series("orders-db", "connections.used.pct");
        assertTrue(connections.get("instancesCapped").asBoolean());
        assertTrue(drawnLabels(connections).contains("pg-" + (busiest + 1)), "the asserted instance stays drawn");
        assertEquals(90.0, drawnMaximum(connections), 0.0);

        JsonNode up = j.series("orders-db", "up");
        assertTrue(up.get("instancesCapped").asBoolean());
        assertEquals(0.0, actualOf(up, "up"), 0.0);
        assertTrue(drawnLabels(up).contains("pg-" + (down + 1)), "up's low instance is drawn: " + drawnLabels(up));
        assertEquals(0.0, drawnMinimum(up), 0.0);
    }

    @Test
    void twoMetricsSharingOneQueryEachCarryTheirOwnNumber() throws Exception {
        // Kafka's consumer.lag.total (max) and consumer.lag.final (last) are one PromQL query. The
        // collector writes ONE series for it, named after whichever metric it met first - here
        // final, as an assert on it makes it. Lag climbs to 10,000 on one instance past the cap and
        // drains to 100; another, also past the cap, climbs to 500 and stays there. One number under
        // the recorded name was shown for both: total read "peak 500" and was banded fail > 750,
        // which the build FAILs at 10,000.
        int spike = N - 3;
        int climb = N - 1;
        ServerMetricsPayload.SeriesRecord shared = catalogueSeries("bus", "kafka", "consumer.lag.final",
                (i, t) -> i == spike ? new double[] {100, 5000, 10000, 5000, 100}[t]
                        : i == climb ? new double[] {10, 50, 100, 300, 500}[t] : 10 + t);
        ServerMetricsPayload p = payload(shared);
        String query = Catalogue.builtIn().type("kafka").metric("consumer.lag.total").query(Catalogue.PROMETHEUS, null);
        assertEquals(query, shared.getQuery(), "one query for both");

        Judged j = judge(ServerFixtures.block(COMPONENTS,
                withDefaults("bus", "consumer.lag.final", "\"fail\":{\"gt\":1000000}"),
                withDefaults("bus", "consumer.lag.total", "\"fail\":{\"gt\":1000000}")), p);
        JsonNode s = j.seriesFor("bus", query);
        assertEquals("consumer.lag.final", s.get("metric").asText(), "named after one of the two");
        assertTrue(s.get("instancesCapped").asBoolean());
        assertEquals(10_000.0, actualOf(s, "consumer.lag.total"), 0.0);
        assertEquals(j.result("bus", "consumer.lag.total").actual(), actualOf(s, "consumer.lag.total"), 0.0);
        assertEquals(500.0, actualOf(s, "consumer.lag.final"), 0.0);
        assertEquals(j.result("bus", "consumer.lag.final").actual(), actualOf(s, "consumer.lag.final"), 0.0);
        assertFalse(s.get("judgedActuals").has("consumer.lag.max"), "another query, another series");

        // A components-only file: the same two numbers, nothing naming either. judgedActuals still
        // matters - a capped series is not recomputable - but the line beside each number is drawn
        // from the instance it came from: both of them, each metric's own (F6). Kept in collector
        // order, the first twenty, neither was drawn, and the picker's "peak 10,000" sat over a
        // line that never left 14.
        JsonNode unnamed = judge(ServerFixtures.block(COMPONENTS), p).seriesFor("bus", query);
        assertEquals(ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES, unnamed.get("instances").size());
        assertTrue(drawnLabels(unnamed).contains("pg-" + (spike + 1)), "total's instance is drawn: " + drawnLabels(unnamed));
        assertTrue(drawnLabels(unnamed).contains("pg-" + (climb + 1)), "final's instance is drawn: " + drawnLabels(unnamed));
        assertEquals(10_000.0, drawnMaximum(unnamed), 0.0, "the spike the number is about is on the chart");
        assertEquals(10_000.0, actualOf(unnamed, "consumer.lag.total"), 0.0);
        assertEquals(500.0, actualOf(unnamed, "consumer.lag.final"), 0.0);

        // One of the two asserted, the other not: the asserts' tier keeps final's instance and
        // nothing more, and the picker still shows total's "peak 10,000" beside this very series.
        // So the catalogue's tier runs on a series an assert already names - skipping it there
        // brought F6 back on exactly the shared query its pooling was built for.
        Judged finalOnly = judge(ServerFixtures.block(COMPONENTS,
                withDefaults("bus", "consumer.lag.final", "\"fail\":{\"gt\":1000000}")), p);
        JsonNode half = finalOnly.seriesFor("bus", query);
        assertTrue(half.get("instancesCapped").asBoolean());
        assertEquals(500.0, finalOnly.result("bus", "consumer.lag.final").actual(), 0.0);
        assertTrue(drawnLabels(half).contains("pg-" + (climb + 1)), "final's instance is drawn: " + drawnLabels(half));
        assertTrue(drawnLabels(half).contains("pg-" + (spike + 1)), "total's instance is drawn: " + drawnLabels(half));
        assertEquals(10_000.0, drawnMaximum(half), 0.0, "the spike total's number is about is on the chart");
        assertEquals(10_000.0, actualOf(half, "consumer.lag.total"), 0.0);
    }

    /** Instance 24 spikes at t=3 and grows fastest; instance 23 dips at t=1; the rest rise gently. */
    private static double mixed(int instance, int t) {
        if (instance == N - 1 && t == 3) {
            return 10 + instance + t * (instance + 1) + 500;
        }
        if (instance == N - 2 && t == 1) {
            return 0.5;
        }
        return 10 + instance + t * (instance + 1);
    }

    // ---- the three the plan names ---------------------------------------------------------

    @Test
    void aMaxMetricIsDrawnFromTheInstanceItIsJudgedOn() throws Exception {
        // The K27/V2 reproduction: transaction.longest.seconds on 21 instances, the 21st holding a
        // 120 s spike, every other point at most 2.9. The page's copy read "peak 3", and the
        // thresholds step seeded fail > 4.3 - which the build FAILs at 120. judgedActuals fixed the
        // number (SF8); the line beside it was still drawn from the first twenty, so "peak 120 s"
        // sat over a chart that never reached 3 (F6). The spike's instance is drawn now, in place
        // of one that decides nothing.
        int n = ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES + 1;
        ServerMetricsPayload p = payload(catalogueSeries("orders-db", "postgres", "transaction.longest.seconds",
                n, (i, t) -> i == n - 1 && t == 2 ? 120 : 1 + (i % 20) * 0.1 - (t == 4 ? 0.1 : 0)));

        Judged componentsOnly = judge(ServerFixtures.block(COMPONENTS), p);
        JsonNode s = componentsOnly.series("orders-db", "transaction.longest.seconds");
        assertEquals(n, s.get("instanceCount").asInt());
        assertEquals(ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES, s.get("instances").size(),
                "still capped: twenty of the twenty-one");
        assertEquals(120.0, drawnMaximum(s), 0.0, "the spike the number is about is on the chart");
        assertEquals(120.0, actualOf(s, "transaction.longest.seconds"), 0.0);

        Judged seeded = judge(ServerFixtures.block(COMPONENTS, withDefaults("orders-db", "transaction.longest.seconds",
                "\"warn\":{\"gt\":3.6},\"fail\":{\"gt\":4.3}")), p);
        KpiResult r = seeded.result("orders-db", "transaction.longest.seconds");
        assertEquals(AssertionStatus.FAIL, r.status(), "the band the thinned copy suggested fails its own run");
        assertEquals(r.actual(), actualOf(s, "transaction.longest.seconds"), 0.0);
        assertEquals(r.actual(), actualOf(seeded.series("orders-db", "transaction.longest.seconds"),
                "transaction.longest.seconds"), 0.0);
    }

    @Test
    void upIsJudgedOnItsLowestInstanceNotOnTheMaximum() throws Exception {
        // up is min across min (SF2): one database down for one scrape is the number, 0. The
        // level's maximum, 1, is what judgedMax says - and what a prefill from it would hide.
        ServerMetricsPayload p = payload(catalogueSeries("orders-db", "postgres", "up",
                (i, t) -> i == N - 3 && t == 2 ? 0 : 1));

        Judged componentsOnly = judge(ServerFixtures.block(COMPONENTS), p);
        JsonNode s = componentsOnly.series("orders-db", "up");
        assertEquals(0.0, actualOf(s, "up"), 0.0);
        assertEquals(1.0, s.get("judgedMax").asDouble(), 0.0, "the level's maximum, which is not up's number");
        // F6: "low 0" used to sit over a flat line at 1 - the instance that went down sorted past
        // the cap, and a components-only file has no assert to keep it. It is drawn now.
        assertTrue(drawnLabels(s).contains("pg-" + (N - 2)), "the instance that went down is drawn: " + drawnLabels(s));
        assertEquals(0.0, drawnMinimum(s), 0.0, "the scrape it was down for is on the chart");

        Judged safeDefault = judge(ServerFixtures.block(COMPONENTS,
                withDefaults("orders-db", "up", "\"fail\":{\"lt\":1}")), p);
        KpiResult r = safeDefault.result("orders-db", "up");
        assertEquals(AssertionStatus.FAIL, r.status());
        assertEquals(r.actual(), actualOf(s, "up"), 0.0);
    }

    @Test
    void databaseSizeGrowthIsASlopePerMinuteNotTheLevel() throws Exception {
        // Instance i grows (i + 1) MB a minute from 7.5 GB; the fastest, 25 MB/min, sorts past the
        // cap. Seeded from the level the page read 7,572,000,000 B (SF9); the number is a rate.
        ServerMetricsPayload p = payload(catalogueSeries("orders-db", "postgres", "database.size.growth",
                (i, t) -> 7.5e9 + (i + 1) * 1e6 * (t * STEP / 60_000.0)));

        Judged componentsOnly = judge(ServerFixtures.block(COMPONENTS), p);
        JsonNode s = componentsOnly.series("orders-db", "database.size.growth");
        assertEquals(25e6, actualOf(s, "database.size.growth"), 1e-3,
                "per minute, from the fastest instance - drawn since F6, and the number still travels: a"
                        + " capped copy is never recomputed");
        assertTrue(drawnLabels(s).contains("pg-" + N), "the fastest instance is drawn: " + drawnLabels(s));
        assertTrue(s.get("judgedMax").asDouble() > 7.5e9, "judgedMax is the level, not the growth");

        Judged asserted = judge(ServerFixtures.block(COMPONENTS, withDefaults("orders-db", "database.size.growth",
                "\"fail\":{\"gt\":100000000}")), p);
        KpiResult r = asserted.result("orders-db", "database.size.growth");
        assertEquals(AssertionStatus.PASS, r.status());
        assertEquals(r.actual(), actualOf(s, "database.size.growth"), 0.0);
    }

    // ---- a tolerance ------------------------------------------------------------------------

    @Test
    void aToleranceIsReducedAsTheEvaluatorReducesIt() throws Exception {
        // No built-in metric declares an `over` - a catalogue `reduce` is a bare key - so the
        // writer never passes one today. judgedActual takes the reducer as a whole, though, and
        // must reduce a tolerance exactly as the evaluator does, or refuse it where the evaluator
        // refuses: here each assert's own reducer is handed over and the numbers compared.
        ServerMetricsPayload p = payload(catalogueSeries("orders-db", "postgres", "transaction.longest.seconds",
                (i, t) -> new double[] {1, 9, 9, 20, 1}[t] + (i == N - 1 ? 5 : 0)));
        String metric = "\"component\":\"orders-db\",\"metric\":\"transaction.longest.seconds\"";
        Judged j = judge(ServerFixtures.block(COMPONENTS,
                "{\"id\":\"held\"," + metric + ",\"reduce\":{\"kind\":\"max\",\"over\":{\"sustainedFor\":\"30s\"}},"
                        + "\"fail\":{\"gt\":1000}}",
                "{\"id\":\"budget\"," + metric + ",\"reduce\":{\"kind\":\"min\",\"over\":{\"breachBudget\":0.4}},"
                        + "\"fail\":{\"gt\":1000}}",
                "{\"id\":\"plain\"," + metric + ",\"fail\":{\"gt\":1000}}",
                "{\"id\":\"off-step\"," + metric + ",\"reduce\":{\"kind\":\"max\",\"over\":{\"sustainedFor\":\"20s\"}},"
                        + "\"fail\":{\"gt\":1000}}",
                "{\"id\":\"no-level\"," + metric + ",\"reduce\":{\"kind\":\"avg\",\"over\":{\"sustainedFor\":\"30s\"}},"
                        + "\"fail\":{\"gt\":1000}}"), p);
        ServerMetricsPayload.SeriesRecord full = p.getSeries().get(0);

        KpiResult held = j.byId("held");
        assertEquals(14.0, held.actual(), 0.0, "held for 30 s: 9 + 5 on the last instance, not its 25 spike");
        assertEquals(held.actual(), actualFor(j, p, full, held), 0.0);
        KpiResult budget = j.byId("budget");
        assertEquals(6.0, budget.actual(), 0.0, "the 40% quantile of the last instance, across max");
        assertEquals(budget.actual(), actualFor(j, p, full, budget), 0.0);
        assertEquals(25.0, j.byId("plain").actual(), 0.0);
        assertEquals(j.byId("plain").actual(), actualOf(j.series("orders-db", "transaction.longest.seconds"),
                "transaction.longest.seconds"), 0.0, "what ships is the catalogue's default, not an override");

        // Where the evaluator has no number, neither has this.
        assertEquals(NotJudged.INVALID_RULE, j.byId("off-step").notJudged(), "20 s is not a whole number of 15 s steps");
        assertNull(actualOrNull(j, p, full, j.byId("off-step")));
        assertEquals(NotJudged.INVALID_RULE, j.byId("no-level").notJudged(), "avg has no level to hold");
        assertNull(actualOrNull(j, p, full, j.byId("no-level")));
    }

    @Test
    void aSustainedForThatIsNotAWholeNumberOfStepsHasNoNumberEvenWherePointsHoldOne() throws Exception {
        // On the 15 s grid above no 20 s window is ever complete, so the reducer has no number
        // whatever the step check says, and the off-step case there cannot tell the check from the
        // grid. Here every instance has a point every 5 s, which does hold 20 s windows, and only the
        // step the source declared differs. At 5 s, 20 s is four steps: judged, and judgedActual is
        // its actual. At 15 s it is not a whole number of steps: the evaluator refuses it, and
        // judgedActual must have no number - not the one the finer points would give.
        String held = "{\"id\":\"off-step\",\"component\":\"orders-db\",\"metric\":\"transaction.longest.seconds\","
                + "\"reduce\":{\"kind\":\"max\",\"over\":{\"sustainedFor\":\"20s\"}},\"fail\":{\"gt\":1000}}";
        for (long step : new long[] {5_000L, STEP}) {
            ServerMetricsPayload.SeriesRecord s = catalogueSeries("orders-db", "postgres", "transaction.longest.seconds",
                    (i, t) -> 0);
            for (ServerMetricsPayload.InstanceRecord inst : s.getInstances()) {
                List<ServerMetricsPayload.Point> points = new ArrayList<>();
                for (long ts = START; ts <= START + (POINTS - 1) * STEP; ts += 5_000L) {
                    // Rising one a second: held for 20 s, the highest level is the one at 40 s.
                    points.add(new ServerMetricsPayload.Point(ts, (ts - START) / 1000.0));
                }
                inst.setPoints(points);
            }
            ServerMetricsPayload p = payload(step, s);
            Judged j = judge(ServerFixtures.block(COMPONENTS, held), p);
            KpiResult r = j.byId("off-step");
            Double actual = actualOrNull(j, p, s, r);
            if (step == STEP) {
                assertEquals(NotJudged.INVALID_RULE, r.notJudged(), "20 s is not a whole number of 15 s steps");
                assertNull(actual, "no number where the evaluator refuses, though the points would give 40");
            } else {
                assertTrue(r.status().gates(), "four 5 s steps: judged, " + r);
                assertEquals(40.0, r.actual(), 0.0);
                assertEquals(r.actual(), actual, 0.0);
            }
        }
    }

    private static double actualFor(Judged j, ServerMetricsPayload p, ServerMetricsPayload.SeriesRecord s, KpiResult r) {
        Double v = actualOrNull(j, p, s, r);
        assertNotNull(v, r.kpi().id());
        return v;
    }

    private static Double actualOrNull(Judged j, ServerMetricsPayload p, ServerMetricsPayload.SeriesRecord s,
                                       KpiResult r) {
        return ServerKpiEvaluator.judgedActual(j.server.config(), p, s, r.kpi().reducer(), r.kpi().across());
    }

    // ---- which series carry it --------------------------------------------------------------

    @Test
    void aSeriesCappedOnPointsAloneCarriesItToo() throws Exception {
        // Two instances, so the instance cap does not bite; one of them has more points than the
        // page keeps, and the thinning drops the one that holds the spike.
        int many = ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE + 100;
        ServerMetricsPayload.SeriesRecord s = catalogueSeries("orders-db", "postgres", "transaction.longest.seconds",
                2, (i, t) -> 1);
        List<ServerMetricsPayload.Point> points = new ArrayList<>();
        double stride = (many - 1) / (double) (ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE - 1);
        java.util.Set<Long> kept = new java.util.HashSet<>();
        for (int i = 0; i < ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE; i++) {
            kept.add(Math.round(i * stride));
        }
        int dropped = -1;
        for (int k = 1; k < many && dropped < 0; k++) {
            if (!kept.contains((long) k)) {
                dropped = k;
            }
        }
        assertTrue(dropped > 0);
        for (int k = 0; k < many; k++) {
            points.add(new ServerMetricsPayload.Point(START + k * 10L, k == dropped ? 77 : 1));
        }
        s.getInstances().get(0).setPoints(points);

        JsonNode out = judge(ServerFixtures.block(COMPONENTS), payload(s)).series("orders-db", "transaction.longest.seconds");
        assertFalse(out.has("instancesCapped"));
        assertTrue(out.get("instances").get(0).get("pointsCapped").asBoolean());
        // The spike sits where every-k-th thinning dropped it; the bucket thinning keeps it (F6), and
        // the build's number still travels, because a thinned copy is not the series.
        assertEquals(77.0, drawnMaximum(out), 0.0, "the spike is drawn");
        assertEquals(77.0, actualOf(out, "transaction.longest.seconds"), 0.0);
    }

    @Test
    void aThinnedInstanceStillDrawsItsDecidingSpikeAndDip() throws Exception {
        // F6-residual. thin() kept every k-th point, so on an instance above the point cap (16.7 h at
        // 15 s) the one point a max judged could fall between two strides: a FAIL beside a chart whose
        // line never reached the band. Thinned by bucket, each bucket's lowest and highest point kept,
        // the drawn line reaches every extreme - the spike a max judges and the dip a min judges.
        int cap = ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE;
        for (int many : new int[] {cap + 100, 3 * cap + 7}) {
            double stride = (many - 1) / (double) (cap - 1);
            java.util.Set<Long> strideKept = new java.util.HashSet<>();
            for (int i = 0; i < cap; i++) {
                strideKept.add(Math.round(i * stride));
            }
            int spike = -1;
            int dip = -1;
            for (int k = many / 3; k < many - 1 && (spike < 0 || dip < 0); k++) {
                if (!strideKept.contains((long) k)) {
                    if (spike < 0) {
                        spike = k;
                    } else if (k > spike + 1) {
                        dip = k;
                    }
                }
            }
            assertTrue(spike > 0 && dip > 0, "every-k-th thinning drops both points at " + many);
            List<ServerMetricsPayload.Point> points = new ArrayList<>();
            for (int k = 0; k < many; k++) {
                points.add(new ServerMetricsPayload.Point(START + k * 10L, k == spike ? 977 : k == dip ? -5 : 1 + k % 3));
            }
            ServerMetricsPayload.SeriesRecord s = catalogueSeries("orders-db", "postgres",
                    "transaction.longest.seconds", 1, (i, t) -> 1);
            s.getInstances().get(0).setPoints(points);

            JsonNode out = judge(ServerFixtures.block(COMPONENTS), payload(s))
                    .series("orders-db", "transaction.longest.seconds");
            JsonNode inst = out.get("instances").get(0);
            JsonNode drawn = inst.get("points");
            assertTrue(inst.get("pointsCapped").asBoolean());
            assertEquals(many, inst.get("pointCount").asInt(), "the true count travels");
            assertTrue(drawn.size() <= cap, drawn.size() + " points drawn at " + many);
            assertEquals(977.0, drawnMaximum(out), 0.0, "the spike a max judges is drawn at " + many);
            assertEquals(-5.0, drawnMinimum(out), 0.0, "the dip a min judges is drawn at " + many);
            assertEquals(START, drawn.get(0).get(0).asLong(), "the first point is kept");
            assertEquals(START + (many - 1) * 10L, drawn.get(drawn.size() - 1).get(0).asLong(), "and the last");
            for (int i = 1; i < drawn.size(); i++) {
                assertTrue(drawn.get(i).get(0).asLong() > drawn.get(i - 1).get(0).asLong(),
                        "drawn in time order, each point once");
            }
        }
    }

    @Test
    void anUncappedSeriesCarriesNeitherNumberTheBrowserRecomputesIt() throws Exception {
        JsonNode s = judge(ServerFixtures.block(COMPONENTS), payload(catalogueSeries("orders-db", "postgres", "up",
                2, (i, t) -> 1))).series("orders-db", "up");
        assertFalse(s.has("instancesCapped"));
        assertFalse(s.has("judgedActuals"), "an uncapped series' chart data is as it was");
        assertFalse(s.has("judgedMax"));
    }

    @Test
    void aCustomQueryHasNoCatalogueNumberAndItsMaximumIsTheSeedsActual() throws Exception {
        // The custom seed (report.js seedKpi, custom branch) declares reduce max and no across, and
        // reads judgedMax on a capped series. That is the seed's number by construction; it needs
        // nothing new. No catalogue metric's query finds a custom series, so it carries no
        // judgedActuals.
        ServerMetricsPayload.SeriesRecord custom = catalogueSeries(ServerFixtures.DEFAULT_COMPONENT, null, null,
                ServerMetricsPayloadWriterTest::mixed);
        custom.setQuery("queue_depth");
        custom.setResolvedQuery("queue_depth");
        ServerMetricsPayload p = payload(custom);
        Judged j = judge(ServerFixtures.block(null, "{\"id\":\"seed\",\"component\":\"server\",\"query\":\"queue_depth\","
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1000000}}"), p);
        JsonNode s = j.seriesByQuery("queue_depth");
        assertTrue(s.get("instancesCapped").asBoolean());
        assertFalse(s.has("judgedActuals"));
        assertEquals(j.byId("seed").actual(), s.get("judgedMax").asDouble(), 0.0);
    }

    @Test
    void aSeriesWhoseComponentTheFileDoesNotDeclareCarriesNoCatalogueNumber() throws Exception {
        // The type comes from the component as the file declares it; without one there are no
        // defaults to take, and a number under a guessed type would be a second definition.
        ServerMetricsPayload p = payload(catalogueSeries("orders-db", "postgres", "up", (i, t) -> 1));
        JsonNode renamed = judge(ServerFixtures.block("{\"billing-db\":{\"type\":\"postgres\"}}"), p).series("orders-db", "up");
        assertTrue(renamed.get("instancesCapped").asBoolean());
        assertFalse(renamed.has("judgedActuals"));
        assertTrue(renamed.has("judgedMax"));

        Map<String, Object> chart = new LinkedHashMap<>();
        ServerMetricsPayloadWriter.addTo(chart, p, null);
        JsonNode blind = JSON.valueToTree(chart).get("serverMetrics").get("series").get(0);
        assertFalse(blind.has("judgedActuals"), "no evaluation, no declared components");
    }

    // ---- a metric with no prometheus query --------------------------------------------------

    @Test
    void aMetricWithNoPrometheusQueryIsFoundByItsNeutralIdentity() throws Exception {
        // A KPI only an exported file can answer is collected from a CSV under its neutral identity,
        // <type>:<metric>{match} (CatalogueMetric.identity) - what the mapper resolves an assert
        // naming it to. Looked up by its prometheus query, it has none: a capped series carried no
        // number for the picker, and the instance that number came from, past the cap, was not drawn.
        Catalogue acme = Catalogue.fromFiles(java.util.Collections.singletonMap("acme", JSON.readTree(
                "{\"type\":\"acme\",\"version\":1,\"exporter\":\"acme_exporter\",\"metrics\":["
                        + "{\"name\":\"pool.used.pct\",\"unit\":\"%\",\"reduce\":\"max\",\"across\":\"max\","
                        + "\"names\":{\"telegraf\":null,"
                        + "\"datadog\":{\"metric\":\"acme.pool.pct\",\"kind\":\"gauge\",\"scale\":100}}}]}")));
        String identity = acme.type("acme").metric("pool.used.pct").identity("instance=\"db-1\"");
        assertEquals("acme:pool.used.pct{instance=\"db-1\"}", identity);

        int busiest = N - 1;
        ServerMetricsPayload.SeriesRecord s = catalogueSeries("pool", null, null,
                (i, t) -> i == busiest && t == 2 ? 97 : 40 + t);
        s.setMetric("pool.used.pct");
        s.setQuery(identity);
        s.setResolvedQuery(identity);
        ServerMetricsPayload p = payload(s);
        // A components-only file. The evaluation's mapper is the built-in one, to which acme is no
        // type - a finding, and the component is kept: its type and match are all the writer reads.
        ReportAnnotations a = ServerFixtures.annotations(
                ServerFixtures.block("{\"pool\":{\"type\":\"acme\",\"match\":\"instance=\\\"db-1\\\"\"}}"));
        ServerGateEvaluation server = ServerGateEvaluation.of(a, p, GateEvaluation.of(ServerFixtures.run(), a).gate());
        Map<String, Object> chart = new LinkedHashMap<>();
        ServerMetricsPayloadWriter.addTo(chart, p, server, acme);
        JsonNode out = JSON.valueToTree(chart).get("serverMetrics").get("series").get(0);

        assertTrue(out.get("instancesCapped").asBoolean());
        assertEquals(97.0, actualOf(out, "pool.used.pct"), 0.0);
        assertTrue(drawnLabels(out).contains("pg-" + (busiest + 1)),
                "the instance the number came from is drawn: " + drawnLabels(out));
        assertEquals(97.0, drawnMaximum(out), 0.0, "the spike the number is about is on the chart");
    }

    // ---- --from: where a tool's export was read from ----------------------------------------

    @Test
    void theToolAFileWasReadAsAndTheNameEachSeriesWasReadUnderReachThePage() throws Exception {
        // The Collection panel says the file was read as the tool's export, and an open row says
        // which name, kind and scale its number came from: both are the payload's, carried as written.
        ServerMetricsPayload.SeriesRecord read = catalogueSeries("orders-db", "postgres", "connections.used.pct", 1,
                (i, t) -> 40);
        read.setReadAs(new ServerMetricsPayload.ReadAs("datadog", "postgresql.percent_usage_connections", "gauge", 100));
        ServerMetricsPayload.SeriesRecord own = catalogueSeries("orders-db", "postgres", "up", 1, (i, t) -> 1);
        ServerMetricsPayload p = payload(read, own);
        p.getSources().get(0).setType("csv");
        p.getSources().get(0).setFrom("datadog");

        Map<String, Object> chart = new LinkedHashMap<>();
        ServerMetricsPayloadWriter.addTo(chart, p, null);
        JsonNode block = JSON.valueToTree(chart).get("serverMetrics");
        assertEquals("datadog", block.get("sources").get(0).get("from").asText());
        JsonNode readAs = block.get("series").get(0).get("readAs");
        assertEquals("datadog", readAs.get("tool").asText());
        assertEquals("postgresql.percent_usage_connections", readAs.get("metric").asText());
        assertEquals("gauge", readAs.get("kind").asText());
        assertEquals(100.0, readAs.get("scale").asDouble(), 0.0);
        assertFalse(block.get("series").get(1).has("readAs"), "read under the catalogue's own name");

        // Without --from neither key is written: a page built from an older payload is as it was.
        ServerMetricsPayload plain = payload(catalogueSeries("orders-db", "postgres", "up", 1, (i, t) -> 1));
        Map<String, Object> before = new LinkedHashMap<>();
        ServerMetricsPayloadWriter.addTo(before, plain, null);
        JsonNode b = JSON.valueToTree(before).get("serverMetrics");
        assertFalse(b.get("sources").get(0).has("from"));
        assertFalse(b.get("series").get(0).has("readAs"));
    }

    @Test
    void aWideCsvsLayoutAndTimeZoneReachThePageOnlyWhenTheCollectionHadThem() throws Exception {
        // The Collection panel says "wide CSV" and the zone the zone-less times were read in.
        ServerMetricsPayload p = payload(catalogueSeries("orders-db", "postgres", "up", 1, (i, t) -> 1));
        p.getSources().get(0).setType("csv");
        p.getSources().get(0).setLayout("wide");
        p.getSources().get(0).setTimeZone("UTC");
        Map<String, Object> chart = new LinkedHashMap<>();
        ServerMetricsPayloadWriter.addTo(chart, p, null);
        JsonNode source = JSON.valueToTree(chart).get("serverMetrics").get("sources").get(0);
        assertEquals("wide", source.get("layout").asText());
        assertEquals("UTC", source.get("timeZone").asText());

        // A long collection with no zone: neither key, so its page is what it was.
        ServerMetricsPayload plain = payload(catalogueSeries("orders-db", "postgres", "up", 1, (i, t) -> 1));
        Map<String, Object> before = new LinkedHashMap<>();
        ServerMetricsPayloadWriter.addTo(before, plain, null);
        JsonNode b = JSON.valueToTree(before).get("serverMetrics").get("sources").get(0);
        assertFalse(b.has("layout"));
        assertFalse(b.has("timeZone"));
    }

    @Test
    void aLongCsvsColumnsAndComponentReachThePageOnlyWhenTheCollectionHadThem() throws Exception {
        // The Collection panel says how the columns were named and which component every row was read as.
        ServerMetricsPayload p = payload(catalogueSeries("orders-db", "postgres", "up", 1, (i, t) -> 1));
        p.getSources().get(0).setType("csv");
        p.getSources().get(0).setColumns("time=_time,metric=_field,value=_value");
        p.getSources().get(0).setComponent("orders-db");
        Map<String, Object> chart = new LinkedHashMap<>();
        ServerMetricsPayloadWriter.addTo(chart, p, null);
        JsonNode source = JSON.valueToTree(chart).get("serverMetrics").get("sources").get(0);
        assertEquals("time=_time,metric=_field,value=_value", source.get("columns").asText());
        assertEquals("orders-db", source.get("component").asText());

        // A long file in this reader's own names: neither key, so its page is what it was.
        ServerMetricsPayload plain = payload(catalogueSeries("orders-db", "postgres", "up", 1, (i, t) -> 1));
        Map<String, Object> before = new LinkedHashMap<>();
        ServerMetricsPayloadWriter.addTo(before, plain, null);
        JsonNode b = JSON.valueToTree(before).get("serverMetrics").get("sources").get(0);
        assertFalse(b.has("columns"));
        assertFalse(b.has("component"));
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private interface Values {
        double at(int instance, int t);
    }

    private static ServerMetricsPayload.SeriesRecord catalogueSeries(String component, String type, String metric,
                                                                     Values values) {
        return catalogueSeries(component, type, metric, N, values);
    }

    /**
     * A series as the collector writes one for a catalogue metric of {@code component} (no match):
     * the metric's query, {@code component} and {@code metric} set, {@code n} instances
     * {@code pg-1 … pg-n}, a point every 15 s across the window. {@code type} null: a custom query,
     * named by the caller.
     */
    private static ServerMetricsPayload.SeriesRecord catalogueSeries(String component, String type, String metric,
                                                                     int n, Values values) {
        ServerMetricsPayload.SeriesRecord s = new ServerMetricsPayload.SeriesRecord();
        s.setSource(ServerSource.COLLECTION_ID);
        s.setComponent(component);
        if (type != null) {
            CatalogueType t = Catalogue.builtIn().type(type);
            String query = t.metric(metric).identity(null);
            s.setMetric(metric);
            s.setQuery(query);
            s.setResolvedQuery(query);
        }
        s.setStatus("OK");
        s.setCoverage(1.0);
        for (int i = 0; i < n; i++) {
            ServerMetricsPayload.InstanceRecord inst = new ServerMetricsPayload.InstanceRecord();
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("instance", "pg-" + (i + 1));
            inst.setLabels(labels);
            List<ServerMetricsPayload.Point> points = new ArrayList<>();
            for (int t = 0; t < POINTS; t++) {
                points.add(new ServerMetricsPayload.Point(START + t * STEP, values.at(i, t)));
            }
            inst.setPoints(points);
            s.getInstances().add(inst);
        }
        return s;
    }

    private static ServerMetricsPayload payload(ServerMetricsPayload.SeriesRecord... series) {
        return payload(STEP, series);
    }

    /** As above, from a source that declares {@code stepMillis}. */
    private static ServerMetricsPayload payload(long stepMillis, ServerMetricsPayload.SeriesRecord... series) {
        List<ServerMetricsPayload.SeriesRecord> list = new ArrayList<>();
        java.util.Collections.addAll(list, series);
        ServerMetricsPayload p = payload(list);
        p.getSources().get(0).setStepMillis(stepMillis);
        return p;
    }

    /** The build's number for {@code metric} on a series' chart data; fails when there is none. */
    private static double actualOf(JsonNode series, String metric) {
        JsonNode v = series.path("judgedActuals").get(metric);
        assertNotNull(v, "no judgedActuals." + metric + " on " + series.path("query").asText() + ": " + series
                .path("judgedActuals"));
        return v.asDouble();
    }

    private static ServerMetricsPayload payload(List<ServerMetricsPayload.SeriesRecord> series) {
        ServerMetricsPayload p = new ServerMetricsPayload();
        p.setRunId("r");
        p.setCollectionId("c0ffee");
        p.setCollectedAt("2026-10-03T12:00:00Z");
        p.setWindow(new ServerMetricsPayload.Window(START, START + (POINTS - 1) * STEP, null));
        ServerMetricsPayload.SourceRecord src = new ServerMetricsPayload.SourceRecord();
        src.setId(ServerSource.COLLECTION_ID);
        src.setType("prometheus");
        src.setStatus("OK");
        src.setStepMillis(STEP);
        src.setResolvedUrl("https://prom.corp/api/v1");
        p.getSources().add(src);
        p.setSeries(series);
        return p;
    }

    /** An assert on a catalogue metric that writes no reduce and no across: the catalogue's defaults. */
    private static String withDefaults(String component, String metric, String bands) {
        return "{\"component\":\"" + component + "\",\"metric\":\"" + metric + "\"," + bands + "}";
    }

    private static Judged judge(String annotationsJson, ServerMetricsPayload payload) throws Exception {
        ReportData data = ServerFixtures.run();
        ReportAnnotations a = ServerFixtures.annotations(annotationsJson);
        GateEvaluation client = GateEvaluation.of(data, a);
        ServerGateEvaluation server = ServerGateEvaluation.of(a, payload, client.gate());
        JsonNode chart = JSON.readTree(new ReportDataSerializer().serializeChartData(data, a, null, client, payload, server));
        return new Judged(server, chart);
    }

    private static double drawnMaximum(JsonNode series) {
        double max = Double.NEGATIVE_INFINITY;
        for (JsonNode inst : series.get("instances")) {
            for (JsonNode pt : inst.get("points")) {
                max = Math.max(max, pt.get(1).asDouble());
            }
        }
        return max;
    }

    private static double drawnMinimum(JsonNode series) {
        double min = Double.POSITIVE_INFINITY;
        for (JsonNode inst : series.get("instances")) {
            for (JsonNode pt : inst.get("points")) {
                min = Math.min(min, pt.get(1).asDouble());
            }
        }
        return min;
    }

    /** The {@code instance} label of every instance the page received, in the order it is drawn. */
    private static List<String> drawnLabels(JsonNode series) {
        List<String> labels = new ArrayList<>();
        for (JsonNode inst : series.get("instances")) {
            labels.add(inst.path("labels").path("instance").asText());
        }
        return labels;
    }

    /** The page's copy of a series, as a series record the evaluator can reduce: what is drawn. */
    private static ServerMetricsPayload.SeriesRecord drawnCopy(JsonNode series) {
        ServerMetricsPayload.SeriesRecord s = new ServerMetricsPayload.SeriesRecord();
        s.setSource(series.get("source").asText());
        s.setComponent(series.path("component").asText(null));
        s.setQuery(series.get("query").asText());
        s.setResolvedQuery(series.get("resolvedQuery").asText());
        s.setStatus(series.get("status").asText());
        for (JsonNode inst : series.get("instances")) {
            ServerMetricsPayload.InstanceRecord copy = new ServerMetricsPayload.InstanceRecord();
            List<ServerMetricsPayload.Point> points = new ArrayList<>();
            for (JsonNode pt : inst.get("points")) {
                points.add(new ServerMetricsPayload.Point(pt.get(0).asLong(), pt.get(1).asDouble()));
            }
            copy.setPoints(points);
            s.getInstances().add(copy);
        }
        return s;
    }

    /** The evaluation and the chart data the page received from it. */
    private static final class Judged {
        final ServerGateEvaluation server;
        final JsonNode chart;

        Judged(ServerGateEvaluation server, JsonNode chart) {
            this.server = server;
            this.chart = chart;
        }

        KpiResult result(String component, String metric) {
            for (KpiResult r : server.results()) {
                if (component.equals(r.kpi().gate()) && metric.equals(r.kpi().metric()) && !r.kpi().idDeclared()) {
                    return r;
                }
            }
            throw new AssertionError("no result for " + component + "/" + metric + " in " + server.results());
        }

        KpiResult byId(String id) {
            for (KpiResult r : server.results()) {
                if (id.equals(r.kpi().id())) {
                    return r;
                }
            }
            throw new AssertionError("no result " + id + " in " + server.results());
        }

        JsonNode series(String component, String metric) {
            for (JsonNode s : chart.get("serverMetrics").get("series")) {
                if (component.equals(s.path("component").asText()) && metric.equals(s.path("metric").asText())) {
                    return s;
                }
            }
            throw new AssertionError("no series " + component + "/" + metric);
        }

        /**
         * The series the page finds for an assert on {@code component} with {@code query}: the
         * query as written first, then the resolved one, as ServerMetricsPayload.findByQuery and
         * report.js findSeries both look.
         */
        JsonNode seriesFor(String component, String query) {
            for (String field : new String[] {"query", "resolvedQuery"}) {
                for (JsonNode s : chart.get("serverMetrics").get("series")) {
                    if (query.equals(s.path(field).asText()) && component.equals(s.path("component").asText())) {
                        return s;
                    }
                }
            }
            throw new AssertionError("no series " + component + "/" + query);
        }

        JsonNode seriesByQuery(String query) {
            for (JsonNode s : chart.get("serverMetrics").get("series")) {
                if (query.equals(s.path("query").asText())) {
                    return s;
                }
            }
            throw new AssertionError("no series " + query);
        }
    }
}
