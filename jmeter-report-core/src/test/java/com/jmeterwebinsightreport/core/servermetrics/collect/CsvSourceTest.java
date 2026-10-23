package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.CollectionStatus;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.core.servermetrics.ServerMetricsConfig;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSV backend (plans/server-metrics-backends.md §5, §8b): the format, its errors by line, and
 * what the collector makes of one bad series — PARTIAL, not FAILED.
 */
class CsvSourceTest {

    @TempDir
    Path dir;

    /** 1700000000000 .. 1700000060000: the run's window. */
    private static final ServerMetricsPayload.Window WINDOW =
            new ServerMetricsPayload.Window(1_700_000_000_000L, 1_700_000_060_000L, null);

    private ServerSource csv(String text) throws IOException {
        Path file = dir.resolve("m.csv");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0);
    }

    private static SeriesSource.Answer ask(CsvSource reader, ServerSource source, String component, String metric) {
        return reader.collect(source, new SeriesSource.Request("q", component, metric), WINDOW);
    }

    private static List<ServerMetricsPayload.Point> points(SeriesSource.Answer a, int instance) {
        return a.instances().get(instance).getPoints();
    }

    @Test
    void rowsBecomeInstancesOfTheSeriesTheirComponentAndMetricName() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000015000,orders-db,connections.used.pct,pg-1,44\n"
                + "1700000000000,orders-db,connections.used.pct,pg-1,41.5\n"
                + "2023-11-14T22:13:20Z,orders-db,connections.used.pct,pg-2,10\n"
                + "2023-11-15T00:13:35+02:00,orders-db,connections.used.pct,pg-2,12\n"
                + "1700000000000,billing-db,connections.used.pct,pg-9,99\n"
                + "1700000000000,checkout,queue-depth,,17\n");
        CsvSource reader = new CsvSource();

        SeriesSource.Answer orders = ask(reader, s, "orders-db", "connections.used.pct");
        assertNull(orders.error());
        assertEquals(2, orders.instances().size());
        assertEquals(Collections.singletonMap("instance", "pg-1"), orders.instances().get(0).getLabels());
        // Sorted by time whatever the row order; a zone offset is honoured, not guessed.
        assertEquals(1_700_000_000_000L, points(orders, 0).get(0).getTs());
        assertEquals(41.5, points(orders, 0).get(0).getValue());
        assertEquals(1_700_000_015_000L, points(orders, 1).get(1).getTs());

        // The component tells two components of one type apart.
        assertEquals(99.0, points(ask(reader, s, "billing-db", "connections.used.pct"), 0).get(0).getValue());
        // An empty instance is one instance with no label; a custom assert's rows are named by its query.
        SeriesSource.Answer queue = ask(reader, s, "checkout", "queue-depth");
        assertEquals(Collections.emptyMap(), queue.instances().get(0).getLabels());
        // Nothing for this series is an empty answer, not a failure: NO_MATCH, as a live source says.
        SeriesSource.Answer none = ask(reader, s, "orders-db", "deadlocks");
        assertNull(none.error());
        assertTrue(none.instances().isEmpty());
    }

    // ---- the silent cases the research found (P3): each is now said, by line or by span ----------

    @Test
    void aFileWhollyOutsideTheRunSaysSoWithBothSpansAndAZoneHint() throws IOException {
        // Local +03:00 wall-clock written with a Z: every row 3 h after the run. It used to come back
        // EMPTY, reported as "the query matched no series", and pass the build.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "2023-11-15T01:13:20Z,app,cpu,,40\n"
                + "2023-11-15T01:13:35Z,app,cpu,,41\n"
                + "2023-11-15T01:14:20Z,app,cpu,,42\n");
        String e = ask(new CsvSource(), s, "app", "cpu").error();
        assertEquals("m.csv has 3 rows for app/cpu and none inside the run: they run 2023-11-15T01:13:20Z .."
                + " 2023-11-15T01:14:20Z, after the run (2023-11-14T22:13:20Z .. 2023-11-14T22:14:20Z). They start"
                + " 3h late, which is usually a time zone: were local times written with Z? Give the file's zone"
                + " with --time-zone, or move the rows with --clock-offset -3h", e);
    }

    @Test
    void aSkewOfSecondsIsNamedAsClockSkewWithTheOffsetThatMovesIt() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000100000,app,cpu,,40\n1700000115000,app,cpu,,41\n");
        String e = ask(new CsvSource(), s, "app", "cpu").error();
        assertTrue(e.endsWith("if the server's clock is off, move the rows with --clock-offset -100s"), e);
    }

    @Test
    void aNameThatDiffersOnlyByCaseOrPunctuationIsSaidNotEmpty() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,App,cpu_pct,,40\n1700000015000,App,cpu_pct,,41\n");
        CsvSource reader = new CsvSource();
        assertEquals("no row names component app, metric cpu.pct; 2 rows name App/cpu_pct - component and metric"
                + " must be written exactly as the annotations file writes them", ask(reader, s, "app", "cpu.pct").error());
        // And a name nothing resembles is still an empty answer: NO_MATCH.
        assertNull(ask(reader, s, "db", "connections").error());
        assertTrue(ask(reader, s, "db", "connections").instances().isEmpty());
    }

    @Test
    void theLongFilesSeriesNothingAskedForAreListed() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,app,cpu,,40\n1700000000000,cache,hits,,7\n");
        CsvSource reader = new CsvSource();
        ask(reader, s, "app", "cpu");
        assertEquals(Collections.singletonList("cache/hits"), reader.seriesNotAskedFor());
    }

    @Test
    void anUnquotedDecimalCommaIsRefusedByLineNeverReadAsAWrongNumber() throws IOException {
        // "90,9" unquoted is two cells: it used to read as 90, and a FAIL over a 90 band became a WARN.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,app,cpu,,40\n1700000015000,app,cpu,,90,9\n");
        assertEquals("m.csv line 3: has 6 cells where the header has 5 - an unquoted ',' inside a value? Write"
                + " numbers with a '.' decimal point and no thousands separator, or quote the cell",
                ask(new CsvSource(), s, "app", "cpu").error());
        // A trailing comma is not another cell.
        ServerSource trailing = csv("time,component,metric,instance,value\n1700000000000,app,cpu,,40,\n");
        assertNull(ask(new CsvSource(), trailing, "app", "cpu").error());
    }

    @Test
    void aRowThatStopsBeforeTheValueIsRefusedByLine() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n1700000000000,app,cpu\n");
        assertEquals("m.csv line 2: has 3 cells and stops before the value column - a truncated or hand-edited row",
                ask(new CsvSource(), s, "app", "cpu").error());
    }

    @Test
    void anUnclosedQuoteIsRefusedNamingItsLine() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,app,cpu,\"pod-1,40\n1700000015000,app,cpu,,41\n");
        assertEquals("m.csv line 2: a quoted cell opens here and never closes, so every line after it reads as one"
                + " cell - close the quote or remove it", ask(new CsvSource(), s, "app", "cpu").error());
    }

    @Test
    void epochMicrosecondsAreRefusedByName() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n1700000000000000,app,cpu,,40\n");
        assertEquals("m.csv line 2: time \"1700000000000000\" looks like epoch microseconds or nanoseconds - this"
                + " reader takes epoch milliseconds", ask(new CsvSource(), s, "app", "cpu").error());
    }

    @Test
    void aRepeatedTimeWithAnotherValueKeepsTheLastAndSaysSo() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,app,cpu,,40\n1700000000000,app,cpu,,95\n");
        CsvSource reader = new CsvSource();
        assertEquals(95.0, points(ask(reader, s, "app", "cpu"), 0).get(0).getValue());
        assertEquals(Collections.singletonList("m.csv line 3: repeats time \"1700000000000\" with another value (40,"
                + " then 95) - the later row is kept; two hosts without an instance column, or an export appended"
                + " twice?"), reader.duplicateNotes());
    }

    @Test
    void anotherDelimiterOrUtf16IsNamedOnTheLongPathToo() throws IOException {
        ServerSource semi = csv("time;component;metric;instance;value\n1700000000000;app;cpu;;40\n");
        assertEquals("m.csv line 1: the delimiter is ';' and only ',' is read - export with commas (a"
                + " spreadsheet's 'CSV UTF-8, comma delimited')", ask(new CsvSource(), semi, "app", "cpu").error());
        Path file = dir.resolve("u.csv");
        Files.write(file, ("﻿time,component,metric,instance,value\n").getBytes(StandardCharsets.UTF_16LE));
        ServerSource utf16 = ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0);
        assertTrue(ask(new CsvSource(), utf16, "app", "cpu").error().startsWith("u.csv is UTF-16"));
    }

    @Test
    void rowsInsideTheRunWithNoValueAreSaidNotEmpty() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,app,cpu,,\n1700000015000,app,cpu,,NaN\n");
        assertEquals("m.csv has 2 rows for app/cpu and every value is empty or NaN - the export carries the"
                + " series' times and no numbers", ask(new CsvSource(), s, "app", "cpu").error());
    }

    @Test
    void columnsAreFoundByNameAndTheRestIsTolerated() throws IOException {
        ServerSource s = csv("﻿Value,Metric,note,Component,TIME\r\n"
                + "\"1,5\",x,\"quoted, with a comma\",c,1700000000000\r\n"
                + "2.5,x,,c,1700000015000\r\n"
                + "NaN,x,,c,1700000030000\r\n"
                + ",x,,c,1700000045000\r\n"
                + "7,x,,c,1700000045000\r\n"
                + "\r\n");
        SeriesSource.Answer a = ask(new CsvSource(), s, "c", "x");
        // "1,5" is not a number (no thousands or decimal comma), so the series fails, by line.
        assertEquals("m.csv line 2: value \"1,5\" is not a number - write it with a '.' decimal point and no thousands separator",
                a.error());

        ServerSource ok = csv("value,metric,component,time\n"
                + "2.5,x,c,1700000015000\n"
                + "NaN,x,c,1700000030000\n"
                + ",x,c,1700000045000\n"
                + "7,x,c,1700000045000\n"
                + "8,x,c,1699999900000\n"
                + "9,x,c,1699999990000\n"
                + "10,x,c,1700000075000\n"
                + "11,x,c,1700000076000\n");
        SeriesSource.Answer b = ask(new CsvSource(), ok, "c", "x");
        List<ServerMetricsPayload.Point> pts = points(b, 0);
        // NaN and empty are gaps, never zeros; a repeated timestamp keeps the last row; a row
        // more than a step (15s) outside the window is dropped, and one within it is kept, as a
        // range query on a step grid would answer it.
        assertEquals(java.util.Arrays.asList(9.0, 2.5, 7.0, 10.0),
                pts.stream().map(ServerMetricsPayload.Point::getValue).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void aClockOffsetMovesEveryRowBeforeTheWindowIsApplied() throws IOException {
        // Servers whose clocks run 30 s ahead: their rows say 1700000030000 for the run's first
        // second, and --clock-offset -30s lines them up with it.
        Path file = dir.resolve("ahead.csv");
        Files.write(file, ("time,component,metric,instance,value\n"
                + "1700000030000,c,x,,1\n"
                + "1700000045000,c,x,,2\n").getBytes(StandardCharsets.UTF_8));
        ServerSource ahead = ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, -30_000);
        List<ServerMetricsPayload.Point> pts = points(ask(new CsvSource(), ahead, "c", "x"), 0);
        assertEquals(1_700_000_000_000L, pts.get(0).getTs());
        assertEquals(1_700_000_015_000L, pts.get(1).getTs());
    }

    @Test
    void aTimeWithNoZoneIsRefusedByLineNotGuessed() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,c,x,,1\n"
                + "2026-10-02 14:02,c,x,,2\n"
                + "yesterday,c,y,,3\n"
                + "1700000000000,c,z,,4\n");
        CsvSource reader = new CsvSource();
        // ...and the sentence names the flag that reads it, for a file whose zone is known.
        assertEquals("m.csv line 3: time \"2026-10-02 14:02\" has no time zone - add one (2026-10-02T14:02:15+03:00,"
                + " or a Z for UTC), write epoch milliseconds, or give the file's zone with --time-zone",
                ask(reader, s, "c", "x").error());
        assertEquals("m.csv line 4: time \"yesterday\" is not a time - write ISO 8601 with a zone"
                + " (2026-10-02T14:02:15+03:00) or epoch milliseconds", ask(reader, s, "c", "y").error());
        // ...and only that series: the others in the file are read.
        assertNull(ask(reader, s, "c", "z").error());
    }

    private static final String NBSP = String.valueOf((char) 0x00A0);
    private static final String ZWSP = String.valueOf((char) 0x200B);

    @Test
    void aMetricCellPaddedWithAnInvisibleCharacterFailsTheSeriesItWasMeantForByLine() throws IOException {
        // F8-residual. A metric cell is trimmed as String.trim trims, which leaves a no-break space,
        // so "connections.used.pct" + U+00A0 was another series than the one the assert asks for:
        // its rows were read and never looked up, the series came back EMPTY - NO_MATCH, which never
        // fails a build - and nothing named the line. A bad row fails the series it was meant for.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,db,connections.used.pct,,41\n"
                + "1700000015000,db,connections.used.pct" + NBSP + " ,,99\n"
                + "1700000000000,db," + ZWSP + "up,,1\n"
                + "1700000000000,db,deadlocks,,0\n"
                + "1700000000000,db,lag" + NBSP + "x,,5\n");
        CsvSource reader = new CsvSource();
        assertEquals("m.csv line 3: metric \"connections.used.pct\" has an invisible character at its end (U+00A0)"
                        + " that is not trimmed like a plain space, so no row written that way is read as this series"
                        + " - delete it",
                ask(reader, s, "db", "connections.used.pct").error());
        assertEquals("m.csv line 4: metric \"up\" has an invisible character at its start (U+200B)"
                        + " that is not trimmed like a plain space, so no row written that way is read as this series"
                        + " - delete it",
                ask(reader, s, "db", "up").error());
        // ...and only those series: the others in the file are read.
        assertEquals(1, ask(reader, s, "db", "deadlocks").instances().size());
        // One inside the cell is not padding: "lag" has no rows, as before - an empty answer.
        SeriesSource.Answer lag = ask(reader, s, "db", "lag");
        assertNull(lag.error());
        assertTrue(lag.instances().isEmpty());
        // A declared name that carries the character itself is answered as written.
        SeriesSource.Answer padded = ask(reader, s, "db", "connections.used.pct" + NBSP);
        assertNull(padded.error());
        assertEquals(99.0, points(padded, 0).get(0).getValue());

        // The first bad line of the series is the one reported, whichever kind it is.
        ServerSource earlier = csv("time,component,metric,instance,value\n"
                + "yesterday,db,connections.used.pct,,41\n"
                + "1700000015000,db,connections.used.pct" + NBSP + ",,99\n");
        assertTrue(ask(new CsvSource(), earlier, "db", "connections.used.pct").error()
                .startsWith("m.csv line 2: time \"yesterday\""));
    }

    @Test
    void aFileWithoutTheRequiredColumnsFailsEverySeries() throws IOException {
        // The header it has, and the two flags for a file whose columns are called otherwise (an
        // InfluxDB or Dynatrace export handed over as it came).
        assertEquals("m.csv line 1: the header has no component, value column - it needs time, component, metric"
                        + " and value (instance is optional) - the header is: \"time\", \"metric\"; --csv-columns maps"
                        + " a column named otherwise, and --component gives every row a component",
                ask(new CsvSource(), csv("time,metric\n1700000000000,x\n"), "c", "x").error());
        assertTrue(ask(new CsvSource(), csv(""), "c", "x").error().contains("is empty"));
        ServerSource missing = ServerSource.csv(ServerSource.COLLECTION_ID, dir.resolve("nope.csv").toString(), 15_000, 0);
        assertTrue(ask(new CsvSource(), missing, "c", "x").error().startsWith("CSV file does not exist"));
    }

    private static ServerKpi custom(String id, String component, String query) {
        return ServerKpi.builder(ServerSource.COLLECTION_ID, query).id(id).reducer(Reducer.parse("max"))
                .gate(component).fail(Predicate.of(Predicate.Op.GT, 800)).build();
    }

    @Test
    void theCollectorCallsTheSourcePartialWhenOneSeriesFailsAndKeysSeriesByComponent() throws Exception {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,a,shared,,1\n"
                + "1700000015000,a,shared,,1\n"
                + "1700000000000,b,shared,,50\n"
                + "1700000015000,b,shared,,50\n"
                + "1700000000000,a,broken,,oops\n");
        ServerMetricsConfig config = ServerMetricsConfig.builder().source(s)
                .kpi(custom("a-shared", "a", "shared"))
                .kpi(custom("b-shared", "b", "shared"))
                .kpi(custom("a-broken", "a", "broken"))
                .build();
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, new CsvSource());
        ServerMetricsPayload p = new Collector(readers, Collections.<String, String>emptyMap())
                .collect(config, null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");

        // One query, two components: two series, each with its own rows.
        assertEquals(3, p.getSeries().size(), p.getSeries().toString());
        ServerMetricsPayload.SeriesRecord a = p.findByQuery(ServerSource.COLLECTION_ID, "shared", "a");
        ServerMetricsPayload.SeriesRecord b = p.findByQuery(ServerSource.COLLECTION_ID, "shared", "b");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals("a", a.getComponent());
        assertEquals(50.0, b.getInstances().get(0).getPoints().get(0).getValue());
        assertEquals(1.0, a.getInstances().get(0).getPoints().get(0).getValue());

        ServerMetricsPayload.SeriesRecord broken = p.findByQuery(ServerSource.COLLECTION_ID, "broken", "a");
        assertEquals(CollectionStatus.FAILED, broken.collectionStatus());
        // PARTIAL, not FAILED: two of three came back (decided 2026-10-03).
        ServerMetricsPayload.SourceRecord src = p.getSources().get(0);
        assertEquals("PARTIAL", src.getStatus());
        assertEquals("1 of 3 series failed - first: m.csv line 6: value \"oops\" is not a number - write it with a"
                + " '.' decimal point and no thousands separator", src.getError());
        assertEquals("csv", src.getType());
    }

    @Test
    void d1EveryCatalogueMetricOfAComponentIsCollectedButOnlyWhatHasDataIsRecorded() throws Exception {
        // A postgres component with one assert (deadlocks). The CSV also carries connections for
        // it; the rest of postgres@1's metrics are absent. D1: the picker gets every metric that
        // has data in this run, and an absent one is no series and no PARTIAL.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,orders-db,deadlocks,,0\n"
                + "1700000015000,orders-db,deadlocks,,0\n"
                + "1700000000000,orders-db,connections.used.pct,pg-1,41\n"
                + "1700000015000,orders-db,connections.used.pct,pg-1,44\n");
        com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue cat =
                com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue.builtIn();
        String deadlocks = cat.type("postgres").metrics().get("deadlocks").query("prometheus", null);
        ServerMetricsConfig config = ServerMetricsConfig.builder().source(s)
                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("orders-db", null, "postgres", null))
                .kpi(ServerKpi.builder(ServerSource.COLLECTION_ID, deadlocks).id("orders-db-deadlocks")
                        .metric("deadlocks").reducer(Reducer.parse("max")).gate("orders-db")
                        .fail(Predicate.of(Predicate.Op.GT, 0)).build())
                .build();
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, new CsvSource());
        ServerMetricsPayload p = new Collector(readers, Collections.<String, String>emptyMap())
                .collect(config, null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");

        assertEquals(2, p.getSeries().size(), p.getSeries().toString());
        assertEquals("deadlocks", p.getSeries().get(0).getMetric(), "the asserted one, first, tagged");
        ServerMetricsPayload.SeriesRecord extra = p.getSeries().get(1);
        assertEquals("connections.used.pct", extra.getMetric());
        assertEquals("orders-db", extra.getComponent());
        assertEquals(cat.type("postgres").metrics().get("connections.used.pct").query("prometheus", null),
                extra.getQuery(), "its identity is the catalogue's query, as an assert's would be");
        assertEquals(2, extra.getInstances().get(0).getPoints().size());
        assertEquals("OK", p.getSources().get(0).getStatus(), "absent extras never make a source PARTIAL");

        // ...and a collector given no catalogue collects only what is asserted.
        ServerMetricsPayload none = new Collector(readers, Collections.<String, String>emptyMap(), null)
                .collect(config, null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");
        assertEquals(1, none.getSeries().size());
    }

    @Test
    void aKpiWithNoPrometheusQueryIsCollectedFromACsvByComponentAndMetric() throws Exception {
        // The neutral identity is never sent anywhere: a CSV row is found by component and metric,
        // so the KPI Prometheus cannot answer is collected here like any other - the asserted one
        // and the one nobody asserted (D1) alike.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,db,pool.used.pct,pg-1,41\n"
                + "1700000015000,db,pool.used.pct,pg-1,44\n"
                + "1700000030000,db,pool.used.pct,pg-1,43\n"
                + "1700000045000,db,pool.used.pct,pg-1,42\n"
                + "1700000060000,db,pool.used.pct,pg-1,40\n"
                + "1700000000000,db,waits,,3\n"
                + "1700000015000,db,waits,,5\n");
        // SourcesAndCollectorTest's acme type and assert, shared on purpose: Prometheus is asked for
        // this very KPI there, so the two tests are about one catalogue.
        com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue acme = SourcesAndCollectorTest.acme();
        ServerMetricsConfig config = ServerMetricsConfig.builder().source(s)
                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("db", null, "acme",
                        SourcesAndCollectorTest.DB_MATCH))
                .kpi(SourcesAndCollectorTest.catalogueKpi("db-pool", acme.type("acme").metric("pool.used.pct")))
                .build();
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, new CsvSource());
        ServerMetricsPayload p = new Collector(readers, Collections.<String, String>emptyMap(), acme)
                .collect(config, null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");

        ServerMetricsPayload.SeriesRecord pool = p.findByQuery(ServerSource.COLLECTION_ID,
                "acme:pool.used.pct{instance=\"db-1\"}", "db");
        assertNotNull(pool, p.getSeries().toString());
        assertEquals(CollectionStatus.OK, pool.collectionStatus());
        assertEquals("acme:pool.used.pct{instance=\"db-1\"}", pool.getResolvedQuery());
        assertEquals(44.0, pool.getInstances().get(0).getPoints().get(1).getValue());
        assertEquals("OK", p.getSources().get(0).getStatus());

        // waits: no assert names it; its rows are there, so it is recorded under its own identity.
        // conns has no rows, so it is no series at all, as before.
        assertEquals(2, p.getSeries().size(), p.getSeries().toString());
        ServerMetricsPayload.SeriesRecord waits = p.getSeries().get(1);
        assertEquals("waits", waits.getMetric());
        assertEquals("acme:waits{instance=\"db-1\"}", waits.getQuery());
        assertEquals("acme:waits{instance=\"db-1\"}", waits.getResolvedQuery());

        // And the report judges it: 44 is under its fail band of 90.
        List<com.jmeterwebinsightreport.core.servermetrics.KpiResult> judged =
                new com.jmeterwebinsightreport.core.servermetrics.ServerKpiEvaluator().evaluate(config, p, null);
        assertEquals(com.jmeterwebinsightreport.core.assertion.AssertionStatus.PASS, judged.get(0).status());
        assertEquals(44.0, judged.get(0).actual());
    }

    @Test
    void aQueryWithSurroundingSpacesCollectsTheRowsOfTheQueryWithout() throws Exception {
        // U7. CsvSource trims its cells, so a row says "lag" however it was typed - but the series
        // was asked for by the assert's untrimmed query, "lag ", found no row, and was EMPTY: a
        // NO_MATCH that never fails a build, on a run whose lag should have FAILed it. And an
        // ${env.*} value is taken as written, so the resolved text is trimmed too.
        ServerSource s = csv("time,component,metric,instance,value\n"   // every 15 s of the window
                + "1700000000000,db,lag,,900\n"
                + "1700000015000,db,lag,,950\n"
                + "1700000030000,db,lag,,900\n"
                + "1700000045000,db,lag,,900\n"
                + "1700000060000,db,lag,,900\n");
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, new CsvSource());
        Collector collector = new Collector(readers, Collections.<String, String>emptyMap());

        ServerMetricsPayload p = collector.collect(ServerMetricsConfig.builder().source(s)
                        .kpi(custom("padded", "db", "lag ")).build(),
                null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");
        ServerMetricsPayload.SeriesRecord padded = p.findByQuery(ServerSource.COLLECTION_ID, "lag", "db");
        assertNotNull(padded, "keyed by the query without its padding");
        assertEquals("lag", padded.getQuery());
        assertEquals("lag", padded.getResolvedQuery());
        assertEquals(CollectionStatus.OK, padded.collectionStatus());
        assertEquals(950.0, padded.getInstances().get(0).getPoints().get(1).getValue());

        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("q", " lag\t");
        ServerMetricsPayload env = collector.collect(ServerMetricsConfig.builder().source(s)
                        .environment("ci", vars).defaultEnv("ci")
                        .kpi(custom("from-env", "db", "${env.q}")).build(),
                null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");
        ServerMetricsPayload.SeriesRecord fromEnv = env.findByQuery(ServerSource.COLLECTION_ID, "${env.q}", "db");
        assertNotNull(fromEnv);
        assertEquals("lag", fromEnv.getResolvedQuery(), "the interpolated text is trimmed as well");
        assertEquals(CollectionStatus.OK, fromEnv.collectionStatus());
        assertEquals("OK", env.getSources().get(0).getStatus());
    }

    @Test
    void aSourceIsFailedOnlyWhenNothingCameBack() throws Exception {
        ServerSource s = csv("time,metric\n");
        ServerMetricsConfig config = ServerMetricsConfig.builder().source(s)
                .kpi(custom("one", "a", "x")).kpi(custom("two", "a", "y")).build();
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, new CsvSource());
        ServerMetricsPayload p = new Collector(readers, Collections.<String, String>emptyMap())
                .collect(config, null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");
        ServerMetricsPayload.SourceRecord src = p.getSources().get(0);
        assertEquals("FAILED", src.getStatus());
        assertTrue(src.getError().startsWith("2 of 2 series failed - first: m.csv line 1: the header has no"), src.getError());
    }

    // ---- --from TOOL: a file exported from another tool ------------------------------------

    /**
     * One type, {@code shop}, whose metrics each tool names its own way. {@code conns.pct} (%):
     * Datadog hands it over as a 0-1 gauge (x100), and Telegraf has no single metric for it.
     * {@code requests} (per minute): Telegraf a raw counter, Datadog a rate per second, CloudWatch a
     * sum per period, each x60. {@code errors}: no tool is named at all. {@code drops} (per minute)
     * has no prometheus query - the shape {@code --from} exists for, known only by its neutral
     * identity - and Datadog hands it over as a rate per second (x60).
     */
    static com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue shop() throws IOException {
        return com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue.fromFiles(Collections.singletonMap("shop",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"shop\",\"version\":1,\"exporter\":\"shop_exporter\",\"metrics\":["
                        + "{\"name\":\"conns.pct\",\"unit\":\"%\",\"queries\":{\"prometheus\":\"shop_conns_pct${match}\"},"
                        + "\"names\":{\"telegraf\":null,"
                        + "\"datadog\":{\"metric\":\"shop.conns.fraction\",\"kind\":\"gauge\",\"scale\":100}}},"
                        + "{\"name\":\"requests\",\"unit\":\"/min\",\"queries\":{\"prometheus\":\"shop_requests${match}\"},"
                        + "\"names\":{\"telegraf\":{\"metric\":\"shop_requests_total\",\"kind\":\"counter\",\"scale\":60},"
                        + "\"datadog\":{\"metric\":\"shop.requests\",\"kind\":\"rate\",\"scale\":60},"
                        + "\"cloudwatch\":{\"metric\":\"Requests\",\"kind\":\"sum-per-period\",\"scale\":60}}},"
                        + "{\"name\":\"errors\",\"queries\":{\"prometheus\":\"shop_errors${match}\"}},"
                        + "{\"name\":\"drops\",\"unit\":\"/min\","
                        + "\"names\":{\"datadog\":{\"metric\":\"shop.drops\",\"kind\":\"rate\",\"scale\":60}}}]}")));
    }

    /** An assert on component {@code web} (type shop) naming {@code metric}, as the mapper builds one. */
    private static ServerKpi shopKpi(String metric) throws IOException {
        com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric m = shop().type("shop").metric(metric);
        return ServerKpi.builder(ServerSource.COLLECTION_ID, m.identity(null)).id("web-" + metric).metric(m.name())
                .unit(m.unit()).reducer(m.reducer()).gate("web").fail(Predicate.of(Predicate.Op.GT, 1_000_000)).build();
    }

    /** Rows for one series at every 15 s of the window, value(i) at the i-th. */
    private static String every15s(String component, String metric, String instance, java.util.function.IntFunction<String> value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= 4; i++) {
            sb.append(WINDOW.getStart() + i * 15_000L).append(',').append(component).append(',').append(metric)
                    .append(',').append(instance).append(',').append(value.apply(i)).append('\n');
        }
        return sb.toString();
    }

    private ServerMetricsPayload collectFrom(String tool, String rows, ServerKpi... kpis) throws Exception {
        Path file = dir.resolve("export.csv");
        Files.write(file, ("time,component,metric,instance,value\n" + rows).getBytes(StandardCharsets.UTF_8));
        ServerMetricsConfig.Builder b = ServerMetricsConfig.builder()
                .source(ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0, tool))
                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("web", null, "shop", null))
                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("app", null, "custom", null));
        for (ServerKpi k : kpis) {
            b.kpi(k);
        }
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, new CsvSource());
        return new Collector(readers, Collections.<String, String>emptyMap(), shop())
                .collect(b.build(), null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");
    }

    private static ServerMetricsPayload.SeriesRecord seriesOf(ServerMetricsPayload p, String component, String metric) {
        for (ServerMetricsPayload.SeriesRecord s : p.getSeries()) {
            if (component.equals(s.getComponent()) && metric.equals(s.getMetric())) {
                return s;
            }
        }
        return null;
    }

    private static List<Double> values(ServerMetricsPayload.SeriesRecord s) {
        return s.getInstances().get(0).getPoints().stream().map(ServerMetricsPayload.Point::getValue)
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void fromReadsTheToolsNameAndConvertsToTheCataloguesUnit() throws Exception {
        // Datadog's connections are a 0-1 fraction; the catalogue's are %. Read as they were, a
        // 0.5 judged against "fail > 90 %" would pass whatever the database did.
        ServerMetricsPayload p = collectFrom("datadog",
                every15s("web", "shop.conns.fraction", "pg-1", i -> i == 2 ? "0.75" : "0.5")
                        + every15s("web", "shop.requests", "", i -> "2"),
                shopKpi("conns.pct"), shopKpi("requests"));

        ServerMetricsPayload.SeriesRecord conns = seriesOf(p, "web", "conns.pct");
        assertNotNull(conns, p.getSeries().toString());
        assertEquals(CollectionStatus.OK, conns.collectionStatus());
        assertEquals(java.util.Arrays.asList(50.0, 50.0, 75.0, 50.0, 50.0), values(conns));
        // Where the number came from travels with it.
        assertNotNull(conns.getReadAs());
        assertEquals("datadog", conns.getReadAs().getTool());
        assertEquals("shop.conns.fraction", conns.getReadAs().getMetric());
        assertEquals("gauge", conns.getReadAs().getKind());
        assertEquals(100.0, conns.getReadAs().getScale());
        // A rate per second, read per minute.
        ServerMetricsPayload.SeriesRecord requests = seriesOf(p, "web", "requests");
        assertEquals(java.util.Arrays.asList(120.0, 120.0, 120.0, 120.0, 120.0), values(requests));
        assertEquals("rate", requests.getReadAs().getKind());
        assertEquals(60.0, requests.getReadAs().getScale());

        assertEquals("datadog", p.getSources().get(0).getFrom());
        assertEquals("OK", p.getSources().get(0).getStatus());
    }

    @Test
    void aCountersFirstRateInTheWindowIsTakenFromTheRowBeforeIt() throws Exception {
        // Telegraf's raw counter. The row a minute before the run is outside the window (and its
        // one step of slack), but the run's first rate is the difference from it: converted after
        // the window was applied, the first point would have nothing before it, and be lost.
        long t = WINDOW.getStart();
        String rows = (t - 60_000) + ",web,shop_requests_total,,0\n"
                + t + ",web,shop_requests_total,,120\n"
                + (t + 15_000) + ",web,shop_requests_total,,150\n"
                + (t + 30_000) + ",web,shop_requests_total,,15\n";   // a restart
        ServerMetricsPayload p = collectFrom("telegraf", rows, shopKpi("requests"));

        ServerMetricsPayload.SeriesRecord requests = seriesOf(p, "web", "requests");
        List<ServerMetricsPayload.Point> pts = requests.getInstances().get(0).getPoints();
        assertEquals(t, pts.get(0).getTs(), "the run's first moment has its rate");
        // 120 over 60 s, 30 over 15 s, then 15 since the reset over 15 s - each per minute.
        assertEquals(java.util.Arrays.asList(120.0, 120.0, 60.0), values(requests));
        assertEquals("counter", requests.getReadAs().getKind());
        assertEquals("shop_requests_total", requests.getReadAs().getMetric());
    }

    @Test
    void aSumPerPeriodIsDividedByTheExportsPeriod() throws Exception {
        // CloudWatch at a 30 s period: 600 requests in 30 s is 20 per second, 1200 per minute. A
        // period of 60 s would cancel the x60 and read the same as no conversion at all, and
        // dividing by the step (15 s) instead of the rows' own spacing would read twice as much.
        long t = WINDOW.getStart();
        ServerMetricsPayload p = collectFrom("cloudwatch", t + ",web,Requests,,600\n"
                + (t + 30_000) + ",web,Requests,,1200\n" + (t + 60_000) + ",web,Requests,,300\n", shopKpi("requests"));
        ServerMetricsPayload.SeriesRecord requests = seriesOf(p, "web", "requests");
        assertEquals(java.util.Arrays.asList(1200.0, 2400.0, 600.0), values(requests));
        assertEquals("sum-per-period", requests.getReadAs().getKind());
    }

    @Test
    void fromFallsBackToTheCataloguesOwnNameAndReadsItUnconverted() throws Exception {
        // The escape hatch: a series the user computed, already in the catalogue's unit, under our
        // name. Datadog names the metric, but the file has no rows under that name.
        ServerMetricsPayload p = collectFrom("datadog", every15s("web", "conns.pct", "pg-1", i -> "41.5"),
                shopKpi("conns.pct"));
        ServerMetricsPayload.SeriesRecord conns = seriesOf(p, "web", "conns.pct");
        assertEquals(CollectionStatus.OK, conns.collectionStatus());
        assertEquals(java.util.Arrays.asList(41.5, 41.5, 41.5, 41.5, 41.5), values(conns));
        assertNull(conns.getReadAs(), "read under our own name, so nothing was read as the tool's");

        // ...and where the tool has no single metric, the computed series is the way to give it.
        ServerMetricsPayload telegraf = collectFrom("telegraf", every15s("web", "conns.pct", "pg-1", i -> "41.5"),
                shopKpi("conns.pct"));
        assertEquals(CollectionStatus.OK, seriesOf(telegraf, "web", "conns.pct").collectionStatus());
        assertNull(seriesOf(telegraf, "web", "conns.pct").getReadAs());
    }

    /**
     * aws-ec2's four metric-math KPIs, as the built-in catalogue ships them: CloudWatch has no
     * single metric for any (null), so a CloudWatch export answers each only with the series its
     * description says to compute, under the catalogue's own name, read as it is. CloudWatch's own
     * metrics in the file answer nothing, and the collector says which name to write instead.
     */
    @Test
    void awsEc2sMetricMathKpisAreReadFromACloudWatchExportUnderTheirOwnNames() throws Exception {
        com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueType ec2 =
                com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue.builtIn().type("aws-ec2");
        String[] computed = {"ebs.iops", "ebs.throughput.rate", "ebs.limit.exceeded", "network.allowance.exceeded"};
        StringBuilder rows = new StringBuilder();
        for (String name : computed) {
            rows.append(every15s("i-0abc", name, "", i -> name.equals("ebs.limit.exceeded") ? "1" : "250.5"));
        }
        // The other instance's rows are what a raw CloudWatch export holds instead: a per-period
        // sum, under CloudWatch's name, which is no KPI's series.
        rows.append(every15s("i-0def", "AWS/EBS:VolumeReadOps", "vol-1", i -> "15030"));

        Path file = dir.resolve("cloudwatch.csv");
        Files.write(file, ("time,component,metric,instance,value\n" + rows).getBytes(StandardCharsets.UTF_8));
        ServerMetricsConfig.Builder b = ServerMetricsConfig.builder()
                .source(ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0, "cloudwatch"))
                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("i-0abc", null, "aws-ec2", null))
                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("i-0def", null, "aws-ec2", null));
        for (String name : computed) {
            com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric m = ec2.metric(name);
            for (String component : new String[] {"i-0abc", "i-0def"}) {
                b.kpi(ServerKpi.builder(ServerSource.COLLECTION_ID, m.identity(null)).id(component + "-" + name)
                        .metric(m.name()).unit(m.unit()).reducer(m.reducer()).gate(component)
                        .fail(Predicate.of(Predicate.Op.GT, 1_000_000)).build());
            }
        }
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, new CsvSource());
        ServerMetricsPayload p = new Collector(readers, Collections.<String, String>emptyMap(),
                com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue.builtIn())
                .collect(b.build(), null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");

        for (String name : computed) {
            ServerMetricsPayload.SeriesRecord s = seriesOf(p, "i-0abc", name);
            assertNotNull(s, name + ": " + p.getSeries());
            assertEquals(CollectionStatus.OK, s.collectionStatus(), name);
            assertEquals("aws-ec2:" + name, s.getResolvedQuery(), name + " is known by its neutral identity");
            double v = name.equals("ebs.limit.exceeded") ? 1.0 : 250.5;
            assertEquals(java.util.Arrays.asList(v, v, v, v, v), values(s), name + " is read as it is, unconverted");
            assertNull(s.getReadAs(), name + ": read under the catalogue's own name, not as a CloudWatch metric");

            ServerMetricsPayload.SeriesRecord none = seriesOf(p, "i-0def", name);
            assertEquals(CollectionStatus.FAILED, none.collectionStatus(), name);
            assertEquals("cloudwatch has no single metric for aws-ec2." + name
                    + " - put the computed series in the file under " + name, none.getError());
        }
        assertEquals("PARTIAL", p.getSources().get(0).getStatus());
    }

    @Test
    void aToolWithNoSingleMetricForTheKpiIsRefusedByName() throws Exception {
        ServerKpi conns = shopKpi("conns.pct");
        ServerMetricsPayload p = collectFrom("telegraf", every15s("web", "something.else", "", i -> "1"), conns);
        ServerMetricsPayload.SeriesRecord s = seriesOf(p, "web", "conns.pct");
        assertEquals(CollectionStatus.FAILED, s.collectionStatus());
        assertEquals("telegraf has no single metric for shop.conns.pct - put the computed series in the file under"
                + " conns.pct", s.getError());
        assertTrue(s.getError().chars().allMatch(c -> c < 128), "ASCII, as every console line is");
        assertNull(s.getReadAs());
        // The report judges it SOURCE_FAILED, in those words - never a NO_MATCH nobody can explain.
        List<com.jmeterwebinsightreport.core.servermetrics.KpiResult> judged =
                new com.jmeterwebinsightreport.core.servermetrics.ServerKpiEvaluator().evaluate(
                        ServerMetricsConfig.builder().source(ServerSource.csv(ServerSource.COLLECTION_ID, "x", 15_000, 0))
                                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("web", null, "shop", null))
                                .kpi(conns).build(), p, null);
        assertEquals(com.jmeterwebinsightreport.core.assertion.NotJudged.SOURCE_FAILED, judged.get(0).notJudged());
        assertEquals(s.getError(), judged.get(0).notJudgedDetail());
    }

    @Test
    void aToolTheCatalogueDoesNotNameTheKpiForIsRefusedByName() throws Exception {
        // OpenTelemetry is not declared for conns.pct, and no tool is for errors: "the catalogue
        // does not know", which is not "the tool has none".
        ServerMetricsPayload otel = collectFrom("otel", "", shopKpi("conns.pct"));
        assertEquals("the catalogue does not name shop.conns.pct in otel's export - put the series in the file under"
                + " conns.pct", seriesOf(otel, "web", "conns.pct").getError());
        ServerMetricsPayload datadog = collectFrom("datadog", "", shopKpi("errors"));
        assertEquals(CollectionStatus.FAILED, seriesOf(datadog, "web", "errors").collectionStatus());
        assertEquals("the catalogue does not name shop.errors in datadog's export - put the series in the file under"
                + " errors", seriesOf(datadog, "web", "errors").getError());
    }

    @Test
    void aToolThatNamesTheKpiButIsNotInTheFileIsAMissingSeriesAsBefore() throws Exception {
        // Datadog names requests; the file has neither its name nor ours: exactly what a missing
        // series is without --from - an answer with nothing in it, EMPTY.
        ServerMetricsPayload p = collectFrom("datadog", every15s("web", "unrelated", "", i -> "1"), shopKpi("requests"));
        ServerMetricsPayload.SeriesRecord s = seriesOf(p, "web", "requests");
        assertEquals(CollectionStatus.EMPTY, s.collectionStatus());
        assertNull(s.getError());
    }

    @Test
    void theToolsRowsWinOverRowsUnderTheCataloguesOwnName() throws Exception {
        ServerMetricsPayload p = collectFrom("datadog",
                every15s("web", "conns.pct", "pg-1", i -> "99")
                        + every15s("web", "shop.conns.fraction", "pg-1", i -> "0.5"),
                shopKpi("conns.pct"));
        ServerMetricsPayload.SeriesRecord conns = seriesOf(p, "web", "conns.pct");
        assertEquals(java.util.Arrays.asList(50.0, 50.0, 50.0, 50.0, 50.0), values(conns));
        assertEquals("shop.conns.fraction", conns.getReadAs().getMetric());
    }

    @Test
    void aCustomAssertsQueryIsLookedUpAsWrittenAndNeverConverted() throws Exception {
        // A custom query names no catalogue metric, so there is no tool's name for it and nothing to
        // convert by: its rows are read exactly as without --from.
        ServerKpi custom = ServerKpi.builder(ServerSource.COLLECTION_ID, "shop.conns.fraction").id("app-fraction")
                .reducer(Reducer.parse("max")).gate("app").fail(Predicate.of(Predicate.Op.GT, 1)).build();
        ServerMetricsPayload p = collectFrom("datadog", every15s("app", "shop.conns.fraction", "", i -> "0.5"), custom);
        ServerMetricsPayload.SeriesRecord s = p.findByQuery(ServerSource.COLLECTION_ID, "shop.conns.fraction", "app");
        assertEquals(java.util.Arrays.asList(0.5, 0.5, 0.5, 0.5, 0.5), values(s));
        assertNull(s.getReadAs());
    }

    @Test
    void aCatalogueMetricNoAssertNamesIsReadAsTheToolsTooAndAFailureIsSkippedAsBefore() throws Exception {
        // D1 under --from: conns.pct is read under Datadog's name and converted; errors, which the
        // catalogue does not name in Datadog's export and the file does not carry, is no series -
        // nobody asked for it, so it never makes the source PARTIAL.
        ServerMetricsPayload p = collectFrom("datadog",
                every15s("web", "shop.requests", "", i -> "2") + every15s("web", "shop.conns.fraction", "pg-1", i -> "0.25"),
                shopKpi("requests"));
        ServerMetricsPayload.SeriesRecord conns = seriesOf(p, "web", "conns.pct");
        assertNotNull(conns, p.getSeries().toString());
        assertEquals(java.util.Arrays.asList(25.0, 25.0, 25.0, 25.0, 25.0), values(conns));
        assertEquals("datadog", conns.getReadAs().getTool());
        assertNull(seriesOf(p, "web", "errors"));
        assertEquals(2, p.getSeries().size(), p.getSeries().toString());
        assertEquals("OK", p.getSources().get(0).getStatus());
    }

    @Test
    void aMetricWithNoPrometheusQueryIsReadAsTheToolsLikeAnyOther() throws Exception {
        // The shape --from exists for: a metric no query language is asked for, known only by its
        // neutral identity, whose one source is a file. Its request is both neutral and the tool's,
        // and the CSV must read it by the tool's name, asserted and D1 alike.
        ServerMetricsPayload asserted = collectFrom("datadog", every15s("web", "shop.drops", "", i -> "0.5"),
                shopKpi("drops"));
        ServerMetricsPayload.SeriesRecord drops = seriesOf(asserted, "web", "drops");
        assertNotNull(drops, asserted.getSeries().toString());
        assertEquals(CollectionStatus.OK, drops.collectionStatus(), String.valueOf(drops.getError()));
        assertEquals("shop:drops", drops.getResolvedQuery());
        assertEquals(java.util.Arrays.asList(30.0, 30.0, 30.0, 30.0, 30.0), values(drops));
        assertEquals("datadog", drops.getReadAs().getTool());
        assertEquals("shop.drops", drops.getReadAs().getMetric());
        assertEquals("rate", drops.getReadAs().getKind());
        assertEquals(60.0, drops.getReadAs().getScale());

        ServerMetricsPayload d1 = collectFrom("datadog", every15s("web", "shop.drops", "", i -> "0.5")
                + every15s("web", "shop.requests", "", i -> "2"), shopKpi("requests"));
        ServerMetricsPayload.SeriesRecord seen = seriesOf(d1, "web", "drops");
        assertNotNull(seen, d1.getSeries().toString());
        assertEquals(java.util.Arrays.asList(30.0, 30.0, 30.0, 30.0, 30.0), values(seen));
        assertEquals("shop.drops", seen.getReadAs().getMetric());

        // A tool the catalogue does not name it in: refused in its words, naming the metric.
        ServerMetricsPayload otel = collectFrom("otel", "", shopKpi("drops"));
        assertEquals("the catalogue does not name shop.drops in otel's export - put the series in the file under"
                + " drops", seriesOf(otel, "web", "drops").getError());
    }

    @Test
    void aRequestKeepsTheNeutralIdentityAndTheToolsNameWhicheverIsAddedFirst() throws IOException {
        // Both ride one positional constructor, and a CSV ignores the neutral flag: a swap of the
        // two there would still read the right rows, and no collection could notice it.
        com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric drops = shop().type("shop").metric("drops");
        SeriesSource.Request base = new SeriesSource.Request("shop:drops", "web", "drops");
        for (SeriesSource.Request r : java.util.Arrays.asList(
                base.asNeutral(drops.qualifiedName()).fromTool("datadog", drops),
                base.fromTool("datadog", drops).asNeutral(drops.qualifiedName()))) {
            assertTrue(r.neutral());
            assertEquals("shop.drops", r.neutralFor());
            assertEquals("datadog", r.tool());
            assertEquals("shop.drops", r.catalogueMetric());
            assertEquals("shop.drops", r.toolName().metric());
            assertTrue(!r.toolDeclaresNone());
            assertEquals("drops", r.metricKey());
        }
    }

    @Test
    void withoutFromNothingChangesNotTheLookupNotThePayloadNotTheCollectionId() throws Exception {
        String rows = every15s("web", "shop.conns.fraction", "pg-1", i -> "0.5") + every15s("web", "requests", "", i -> "7");
        ServerMetricsPayload p = collectFrom(null, rows, shopKpi("conns.pct"), shopKpi("requests"));

        // Our names only, as written: the tool's rows are nobody's, and nothing is converted.
        assertEquals(CollectionStatus.EMPTY, seriesOf(p, "web", "conns.pct").collectionStatus());
        assertEquals(java.util.Arrays.asList(7.0, 7.0, 7.0, 7.0, 7.0), values(seriesOf(p, "web", "requests")));

        // No key the --from work added, so the file is what it was.
        String written = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p);
        assertTrue(!written.contains("\"from\"") && !written.contains("\"readAs\""), written);

        // The collectionId's material is exactly what it was before --from existed: every series,
        // then every source (id|type|resolvedUrl), the window, the time.
        List<String> material = new java.util.ArrayList<>();
        for (ServerMetricsPayload.SeriesRecord s : p.getSeries()) {
            material.add(s.getSource() + "|" + s.getComponent() + "|" + s.getResolvedQuery());
        }
        for (ServerMetricsPayload.SourceRecord s : p.getSources()) {
            material.add(s.getId() + "|" + s.getType() + "|" + s.getResolvedUrl());
        }
        material.add(WINDOW.getStart() + "-" + WINDOW.getEnd());
        material.add("now");
        assertEquals(com.jmeterwebinsightreport.core.util.RunIdentity.digest(String.join("\n", material)),
                p.getCollectionId());

        // ...and a collection read as a tool's export is a different collection.
        assertTrue(!p.getCollectionId().equals(collectFrom("datadog", rows, shopKpi("conns.pct"), shopKpi("requests"))
                .getCollectionId()));
    }

    // ---- --time-zone, and epoch seconds: both layouts -----------------------------------------

    private ServerSource csv(String text, String zone) throws IOException {
        Path file = dir.resolve("m.csv");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0, null,
                ServerSource.CsvLayout.LONG, null, zone == null ? null : java.time.ZoneId.of(zone));
    }

    @Test
    void aTimeZoneReadsAZoneLessTimeInThatZoneAndLeavesEveryOtherTimeAsItWas() throws IOException {
        // Grafana's Formatted data writes "2023-11-14 22:13:20" in the dashboard's zone and says
        // nothing of it; --time-zone is the user saying which. A time that carries its own offset,
        // and epoch milliseconds, are what they say whatever the flag.
        ServerSource utc = csv("time,component,metric,instance,value\n"
                + "2023-11-14 22:13:20,c,x,,1\n"
                + "2023-11-14T22:13:35,c,x,,2\n"
                + "2023-11-14 22:13:50.000,c,x,,3\n"
                + "2023-11-14T22:14:05Z,c,x,,4\n"
                + "1700000060000,c,x,,5\n", "UTC");
        List<ServerMetricsPayload.Point> pts = points(ask(new CsvSource(), utc, "c", "x"), 0);
        assertEquals(java.util.Arrays.asList(1_700_000_000_000L, 1_700_000_015_000L, 1_700_000_030_000L,
                1_700_000_045_000L, 1_700_000_060_000L),
                pts.stream().map(ServerMetricsPayload.Point::getTs).collect(java.util.stream.Collectors.toList()));

        // An offset, and an IANA zone (Helsinki is UTC+2 in November); an ISO time with its own Z
        // is not moved by either.
        ServerSource plus2 = csv("time,component,metric,instance,value\n"
                + "2023-11-15 00:13:20,c,x,,1\n"
                + "2023-11-14T22:13:35Z,c,x,,2\n", "+02:00");
        assertEquals(1_700_000_000_000L, points(ask(new CsvSource(), plus2, "c", "x"), 0).get(0).getTs());
        assertEquals(1_700_000_015_000L, points(ask(new CsvSource(), plus2, "c", "x"), 0).get(1).getTs());
        ServerSource helsinki = csv("time,component,metric,instance,value\n"
                + "2023-11-15 00:13:20,c,x,,1\n", "Europe/Helsinki");
        assertEquals(1_700_000_000_000L, points(ask(new CsvSource(), helsinki, "c", "x"), 0).get(0).getTs());
    }

    @Test
    void anAllDigitTimeBelowTheEpochMillisecondFloorIsRefusedAsEpochSeconds() throws IOException {
        // 1700000000 read as milliseconds is 1970-01-20: every row silently outside the window, and
        // a series that came back EMPTY with nothing to say why.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000,c,x,,1\n"
                + "1700000015,c,x,,2\n"
                + "1700000000000,c,y,,3\n"
                + "0001700000000,c,z,,4\n");
        CsvSource reader = new CsvSource();
        assertEquals("m.csv line 2: time \"1700000000\" looks like epoch seconds - this reader takes epoch milliseconds",
                ask(reader, s, "c", "x").error());
        assertNull(ask(reader, s, "c", "y").error(), "milliseconds are read as ever");
        assertEquals(1_700_000_000_000L, points(ask(reader, s, "c", "y"), 0).get(0).getTs());
        // Thirteen characters, and still worth 1.7e9: refused as seconds, and said as seconds.
        assertEquals("m.csv line 5: time \"0001700000000\" looks like epoch seconds - this reader takes epoch milliseconds",
                ask(reader, s, "c", "z").error());
    }

    @Test
    void aZoneLessTimeADaylightSavingChangeRepeatsOrSkipsIsRefusedNeverResolvedInSilence() throws IOException {
        // Berlin's clocks went back at 03:00 on 2023-10-29, so 02:30 happened twice; they went forward
        // at 02:00 on 2023-03-26, so 02:30 never happened. Resolved to one offset, an hour of rows
        // lands on the hour before it.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "2023-10-29 01:30:00,c,x,,1\n"
                + "2023-10-29 02:30:00,c,x,,2\n"
                + "2023-03-26 02:30:00,c,y,,3\n"
                + "2023-10-29 03:30:00,c,z,,4\n", "Europe/Berlin");
        CsvSource reader = new CsvSource();
        assertEquals("m.csv line 3: time \"2023-10-29 02:30:00\" is not one moment in Europe/Berlin, whose clocks"
                + " change then - give --time-zone as the offset the file was written in (such as +02:00), or write"
                + " epoch milliseconds", ask(reader, s, "c", "x").error());
        assertEquals("m.csv line 4: time \"2023-03-26 02:30:00\" is not one moment in Europe/Berlin, whose clocks"
                + " change then - give --time-zone as the offset the file was written in (such as +01:00), or write"
                + " epoch milliseconds", ask(reader, s, "c", "y").error());
        // Either side of the change the zone reads as ever. This row is outside the test's window,
        // and a series whose every row is outside the run is said, with both spans - never EMPTY.
        assertTrue(ask(reader, s, "c", "z").error().startsWith("m.csv has 1 row for c/z and none inside the run:"
                + " they run 2023-10-29T02:30:00Z .. 2023-10-29T02:30:00Z, before the run"), ask(reader, s, "c", "z").error());
        // An offset has no such hour: the row is read (and, outside the window, said as such).
        ServerSource offset = csv("time,component,metric,instance,value\n2023-10-29 02:30:00,c,x,,1\n", "+01:00");
        assertTrue(ask(new CsvSource(), offset, "c", "x").error().contains("none inside the run"));
    }

    // ---- --layout wide: Grafana's export ------------------------------------------------------

    private ServerSource wide(String text) throws IOException {
        return wide(text, null, null);
    }

    private ServerSource wide(String text, String columns, String zone) throws IOException {
        Path file = dir.resolve("grafana.csv");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return wideAt(file, columns, zone);
    }

    private ServerSource wideAt(Path file, String columns, String zone) throws IOException {
        String map = null;
        if (columns != null) {
            Path m = dir.resolve("columns.csv");
            Files.write(m, columns.getBytes(StandardCharsets.UTF_8));
            map = m.toString();
        }
        return ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0, null,
                ServerSource.CsvLayout.WIDE, map, zone == null ? null : java.time.ZoneId.of(zone));
    }

    private static List<Double> valuesOf(SeriesSource.Answer a, int instance) {
        return points(a, instance).stream().map(ServerMetricsPayload.Point::getValue)
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void aWideColumnNamedComponentSlashMetricIsThatSeriesWithOrWithoutAnInstance() throws IOException {
        // Grafana's "Series joined by time", Formatted data off: every header cell quoted, \r\n, a
        // time column and one column per series, named by the panel's legend.
        ServerSource s = wide("\"Time\",\"orders-db/connections.used.pct/pg-1\",\" orders-db / connections.used.pct / pg-2 \","
                + "\"checkout/queue-depth\"\r\n"
                + "1700000000000,41.5,10,17\r\n"
                + "1700000015000,44,12,18\r\n");
        CsvSource reader = new CsvSource();

        SeriesSource.Answer conns = ask(reader, s, "orders-db", "connections.used.pct");
        assertNull(conns.error());
        assertEquals(2, conns.instances().size());
        assertEquals(Collections.singletonMap("instance", "pg-1"), conns.instances().get(0).getLabels());
        // Each part is trimmed, as a long file's cells are.
        assertEquals(Collections.singletonMap("instance", "pg-2"), conns.instances().get(1).getLabels());
        assertEquals(java.util.Arrays.asList(41.5, 44.0), valuesOf(conns, 0));
        assertEquals(1_700_000_015_000L, points(conns, 1).get(1).getTs());
        // No instance part: one instance with no label, as an empty instance cell is.
        SeriesSource.Answer queue = ask(reader, s, "checkout", "queue-depth");
        assertEquals(Collections.emptyMap(), queue.instances().get(0).getLabels());
        assertEquals(java.util.Arrays.asList(17.0, 18.0), valuesOf(queue, 0));
        assertTrue(reader.unmappedColumns().isEmpty(), reader.unmappedColumns().toString());
        // Nothing for this series is an empty answer, as in a long file.
        assertTrue(ask(reader, s, "orders-db", "deadlocks").instances().isEmpty());
    }

    @Test
    void theTimeColumnIsTheOneNamedTimeOrElseTheFirst() throws IOException {
        SeriesSource.Answer named = ask(new CsvSource(), wide("\"a/x\",\" TIME \",\"b/y\"\n5,1700000000000,6\n"), "a", "x");
        assertEquals(1_700_000_000_000L, points(named, 0).get(0).getTs());
        assertEquals(5.0, points(named, 0).get(0).getValue());
        SeriesSource.Answer first = ask(new CsvSource(), wide("\"ts\",\"a/x\"\n1700000000000,7\n"), "a", "x");
        assertEquals(7.0, points(first, 0).get(0).getValue());
    }

    @Test
    void aColumnsMapNamesColumnsByTheirExactHeaderAndAColumnTheExportDoesNotHaveIsReported() throws IOException {
        // A legend that cannot be changed: the map says what each header is. Its columns are found
        // by name, in any order. A map entry wins over the header convention.
        String map = "Component,Column,Metric,Instance\n"
                + "orders-db,orders-db:5432 deadlocks,deadlocks,pg-1\n"
                + "checkout,queue,queue-depth,\n"
                + "orders-db,Replication lag,replication.lag.seconds,pg-1\n"
                + "web,a/b,conns.pct,pg-9\n";
        ServerSource s = wide("\"Time\",\"orders-db:5432 deadlocks\",\"queue\",\"orders-db/up/pg-1\","
                + "\"orders-db:5432 pg_stat_activity_count\",\"free text\",\"a/b\"\r\n"
                + "1700000000000,0,17,1,41,9,3\r\n"
                + "1700000015000,1,18,1,43,9,4\r\n", map, null);
        CsvSource reader = new CsvSource();

        assertEquals(java.util.Arrays.asList(0.0, 1.0), valuesOf(ask(reader, s, "orders-db", "deadlocks"), 0));
        assertEquals(Collections.singletonMap("instance", "pg-1"),
                ask(reader, s, "orders-db", "deadlocks").instances().get(0).getLabels());
        assertEquals(java.util.Arrays.asList(17.0, 18.0), valuesOf(ask(reader, s, "checkout", "queue-depth"), 0));
        // The rest still read by the convention.
        assertEquals(java.util.Arrays.asList(1.0, 1.0), valuesOf(ask(reader, s, "orders-db", "up"), 0));
        // The map's entry for "a/b", not the convention's a/b.
        assertEquals(java.util.Arrays.asList(3.0, 4.0), valuesOf(ask(reader, s, "web", "conns.pct"), 0));
        assertTrue(ask(reader, s, "a", "b").instances().isEmpty());

        // Never inferred from a raw metric's name: a column neither names is ignored, and listed.
        assertEquals(java.util.Arrays.asList("orders-db:5432 pg_stat_activity_count", "free text"),
                reader.unmappedColumns());
        // A map entry for a column the export does not have maps nothing, and is listed.
        assertEquals(Collections.singletonList("Replication lag"), reader.mapColumnsNotInFile());
        assertTrue(ask(reader, s, "orders-db", "replication.lag.seconds").instances().isEmpty());
    }

    @Test
    void aColumnsMapItCannotReadFailsTheFileByLine() throws IOException {
        String export = "\"Time\",\"x\"\n1700000000000,1\n";
        assertEquals("columns.csv line 1: the header has no metric column - it needs column, component and metric"
                + " (instance is optional)", ask(new CsvSource(), wide(export, "column,component\nx,c\n", null), "c", "x").error());
        assertEquals("columns.csv line 3: column \"x\" is mapped twice (line 2 too)",
                ask(new CsvSource(), wide(export, "column,component,metric\nx,c,m\nx,c,n\n", null), "c", "m").error());
        assertEquals("columns.csv line 2: a row needs a column, a component and a metric",
                ask(new CsvSource(), wide(export, "column,component,metric\nx,,m\n", null), "c", "m").error());
        ServerSource missing = ServerSource.csv(ServerSource.COLLECTION_ID, dir.resolve("grafana.csv").toString(), 15_000, 0,
                null, ServerSource.CsvLayout.WIDE, dir.resolve("nope.csv").toString(), null);
        assertTrue(ask(new CsvSource(), missing, "c", "x").error().startsWith("the --columns file does not exist: "),
                ask(new CsvSource(), missing, "c", "x").error());
    }

    @Test
    void twoColumnsForOneSeriesAreRefusedForThatSeriesNamingBoth() throws IOException {
        ServerSource s = wide("\"Time\",\"orders-db/up/pg-1\",\"pg one up\",\"orders-db/up/pg-2\",\"checkout/queue-depth\"\n"
                + "1700000000000,1,1,1,17\n", "column,component,metric,instance\npg one up,orders-db,up,pg-1\n", null);
        CsvSource reader = new CsvSource();
        assertEquals("grafana.csv line 1: columns 2 \"orders-db/up/pg-1\" and 3 \"pg one up\" both map to orders-db/up"
                + " instance pg-1 - a series is read from one column only", ask(reader, s, "orders-db", "up").error());
        // ...and only that series.
        assertNull(ask(reader, s, "checkout", "queue-depth").error());
        assertEquals(17.0, points(ask(reader, s, "checkout", "queue-depth"), 0).get(0).getValue());
    }

    @Test
    void aColumnNamingASeriesNothingAsksForIsListedOnceTheCollectionHasAsked() throws IOException {
        // The convention maps any header of two or three non-empty '/' parts, so a legend with a typo
        // in the component, Grafana's label-set legend with a path in it and an expression with a
        // division all name a series - one nothing asks for. Read and never looked up, each is as
        // ignored as an unmapped column, and as easy to miss: the series meant is EMPTY further down.
        ServerSource s = wide("\"Time\",\"order-db/connections.used.pct/pg-1\","
                + "\"{handler=\"\"/api/orders\"\", instance=\"\"x\"\"}\",\"sum(rate(a[1m])) / sum(rate(b[1m]))\","
                + "\"orders-db/connections.used.pct/pg-2\",\"plain raw\"\n"
                + "1700000000000,41,1,2,17,9\n");
        CsvSource reader = new CsvSource();
        assertTrue(reader.columnsNotAskedFor().isEmpty(), "nothing read, nothing to say");
        assertEquals(1, ask(reader, s, "orders-db", "connections.used.pct").instances().size());
        assertEquals(java.util.Arrays.asList("order-db/connections.used.pct/pg-1",
                "{handler=\"/api/orders\", instance=\"x\"}", "sum(rate(a[1m])) / sum(rate(b[1m]))"),
                reader.columnsNotAskedFor());
        // A column neither names is the other list, never this one.
        assertEquals(Collections.singletonList("plain raw"), reader.unmappedColumns());
        // Asked for, a column is not listed.
        ask(reader, s, "order-db", "connections.used.pct");
        assertEquals(java.util.Arrays.asList("{handler=\"/api/orders\", instance=\"x\"}",
                "sum(rate(a[1m])) / sum(rate(b[1m]))"), reader.columnsNotAskedFor());
        // A long file has no columns to list.
        CsvSource longReader = new CsvSource();
        ask(longReader, csv("time,component,metric,instance,value\n1700000000000,c,x,,1\n1700000000000,c,y,,2\n"), "c", "x");
        assertTrue(longReader.columnsNotAskedFor().isEmpty(), longReader.columnsNotAskedFor().toString());
    }

    @Test
    void anEmptyCellOrNaNIsAGapAndNeverAZero() throws IOException {
        // Joined by time is an outer join: a series missing at a timestamp is an empty cell.
        ServerSource s = wide("\"Time\",\"c/x\",\"c/y\"\n"
                + "1700000000000,1,\n"
                + "1700000015000,,5\n"
                + "1700000030000,NaN,6\n"
                + "1700000045000,4, \n");
        CsvSource reader = new CsvSource();
        assertEquals(java.util.Arrays.asList(1.0, 4.0), valuesOf(ask(reader, s, "c", "x"), 0));
        assertEquals(java.util.Arrays.asList(1_700_000_000_000L, 1_700_000_045_000L),
                points(ask(reader, s, "c", "x"), 0).stream().map(ServerMetricsPayload.Point::getTs)
                        .collect(java.util.stream.Collectors.toList()));
        assertEquals(java.util.Arrays.asList(5.0, 6.0), valuesOf(ask(reader, s, "c", "y"), 0));
    }

    @Test
    void aFormattedCellIsRefusedForItsSeriesWithTheFormattedDataSentence() throws IOException {
        ServerSource s = wide("\"Time\",\"c/pct\",\"c/k\",\"c/thousands\",\"c/word\",\"c/fine\"\n"
                + "1700000000000,41.5,1,1,1,1\n"
                + "1700000015000,41.5%,1.20 K,\"1,234\",abc,2\n");
        CsvSource reader = new CsvSource();
        String formatted = " is not a plain number - it looks formatted, with a unit or a thousands separator:"
                + " export it with Formatted data off";
        assertEquals("grafana.csv line 3: column \"c/pct\": value \"41.5%\"" + formatted, ask(reader, s, "c", "pct").error());
        assertEquals("grafana.csv line 3: column \"c/k\": value \"1.20 K\"" + formatted, ask(reader, s, "c", "k").error());
        assertEquals("grafana.csv line 3: column \"c/thousands\": value \"1,234\"" + formatted,
                ask(reader, s, "c", "thousands").error());
        // Not a number at all: the plain sentence a long file's value gets.
        assertEquals("grafana.csv line 3: column \"c/word\": value \"abc\" is not a number - write it with a '.'"
                + " decimal point and no thousands separator", ask(reader, s, "c", "word").error());
        assertNull(ask(reader, s, "c", "fine").error());
        assertEquals(java.util.Arrays.asList(1.0, 2.0), valuesOf(ask(reader, s, "c", "fine"), 0));
    }

    @Test
    void aUtf16FileIsRefusedAsExcelsDownloadByName() throws IOException {
        String text = "\"Time\"\t\"c/x\"\r\n1700000000000\t1\r\n";
        Path withBom = dir.resolve("excel.csv");
        byte[] le = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[le.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(le, 0, bytes, 2, le.length);
        Files.write(withBom, bytes);
        assertEquals("excel.csv is UTF-16 - that is Grafana's 'Download for Excel'; use Download CSV",
                ask(new CsvSource(), wideAt(withBom, null, null), "c", "x").error());
        // Big-endian with its BOM, and no BOM at all: the NUL bytes give it away.
        Path be = dir.resolve("be.csv");
        Files.write(be, (String.valueOf((char) 0xFEFF) + text).getBytes(StandardCharsets.UTF_16BE));
        assertEquals("be.csv is UTF-16 - that is Grafana's 'Download for Excel'; use Download CSV",
                ask(new CsvSource(), wideAt(be, null, null), "c", "x").error());
        Path bare = dir.resolve("bare.csv");
        Files.write(bare, text.getBytes(StandardCharsets.UTF_16LE));
        assertEquals("bare.csv is UTF-16 - that is Grafana's 'Download for Excel'; use Download CSV",
                ask(new CsvSource(), wideAt(bare, null, null), "c", "x").error());
    }

    @Test
    void aDelimiterOtherThanACommaIsRefusedByNameAndASepLineIsSkipped() throws IOException {
        assertEquals("grafana.csv line 1: the delimiter is ';' and only ',' is read - export the file comma-separated",
                ask(new CsvSource(), wide("\"Time\";\"c/x\"\n1700000000000;1\n"), "c", "x").error());
        assertEquals("grafana.csv line 1: the delimiter is a tab and only ',' is read - export the file comma-separated",
                ask(new CsvSource(), wide("\"Time\"\t\"c/x\"\n1700000000000\t1\n"), "c", "x").error());
        assertEquals("grafana.csv line 1: the delimiter is ';' and only ',' is read - export the file comma-separated",
                ask(new CsvSource(), wide("sep=;\n\"Time\";\"c/x\"\n1700000000000;1\n"), "c", "x").error());

        // An older Grafana's Excel mode wrote "sep=," first: skipped, and the lines keep their numbers.
        ServerSource sep = wide("sep=,\r\n\"Time\",\"c/x\",\"c/y\"\r\n1700000000000,5,1\r\n1700000015000,6,oops\r\n");
        CsvSource reader = new CsvSource();
        assertEquals(java.util.Arrays.asList(5.0, 6.0), valuesOf(ask(reader, sep, "c", "x"), 0));
        assertEquals("grafana.csv line 4: column \"c/y\": value \"oops\" is not a number - write it with a '.' decimal"
                + " point and no thousands separator", ask(reader, sep, "c", "y").error());
    }

    @Test
    void aWideFilesTimesAreReadAsALongFilesAre() throws IOException {
        // Formatted data on: zone-less times, which --time-zone reads and nothing else may guess.
        String formatted = "\"Time\",\"c/x\"\n2023-11-15 00:13:20,7\n2023-11-15 00:13:35.000,8\n";
        SeriesSource.Answer helsinki = ask(new CsvSource(), wide(formatted, null, "Europe/Helsinki"), "c", "x");
        assertEquals(1_700_000_000_000L, points(helsinki, 0).get(0).getTs());
        assertEquals(1_700_000_015_000L, points(helsinki, 0).get(1).getTs());
        assertEquals("grafana.csv line 2: time \"2023-11-15 00:13:20\" has no time zone - add one"
                + " (2026-10-02T14:02:15+03:00, or a Z for UTC), write epoch milliseconds, or give the file's zone"
                + " with --time-zone", ask(new CsvSource(), wide(formatted), "c", "x").error());
        assertEquals("grafana.csv line 2: time \"1700000000\" looks like epoch seconds - this reader takes epoch milliseconds",
                ask(new CsvSource(), wide("\"Time\",\"c/x\"\n1700000000,7\n"), "c", "x").error());
    }

    private ServerMetricsPayload collectWith(ServerSource source, ServerKpi... kpis) throws Exception {
        return collectWith(new CsvSource(), source, kpis);
    }

    private ServerMetricsPayload collectWith(CsvSource reader, ServerSource source, ServerKpi... kpis) throws Exception {
        ServerMetricsConfig.Builder b = ServerMetricsConfig.builder().source(source)
                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("web", null, "shop", null));
        for (ServerKpi k : kpis) {
            b.kpi(k);
        }
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, reader);
        return new Collector(readers, Collections.<String, String>emptyMap(), shop())
                .collect(b.build(), null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");
    }

    @Test
    void fromReadsAWideFileByTheToolsNamesAsItReadsALongOne() throws Exception {
        // A Grafana panel over Datadog: the legend names the column by Datadog's metric, and the
        // value is Datadog's 0-1 fraction - converted, as in a long file.
        Path file = dir.resolve("grafana.csv");
        StringBuilder sb = new StringBuilder("\"Time\",\"web/shop.conns.fraction/pg-1\"\r\n");
        for (int i = 0; i <= 4; i++) {
            sb.append(WINDOW.getStart() + i * 15_000L).append(',').append(i == 2 ? "0.75" : "0.5").append("\r\n");
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        CsvSource reader = new CsvSource();
        ServerMetricsPayload p = collectWith(reader, ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0,
                "datadog", ServerSource.CsvLayout.WIDE, null, null), shopKpi("conns.pct"));
        ServerMetricsPayload.SeriesRecord conns = seriesOf(p, "web", "conns.pct");
        assertNotNull(conns, p.getSeries().toString());
        assertEquals(CollectionStatus.OK, conns.collectionStatus());
        assertEquals(java.util.Arrays.asList(50.0, 50.0, 75.0, 50.0, 50.0), values(conns));
        assertEquals("shop.conns.fraction", conns.getReadAs().getMetric());
        assertEquals(Collections.singletonMap("instance", "pg-1"), conns.getInstances().get(0).getLabels());
        // Asked for by the tool's name: a column the collection read, not one nothing asked for.
        assertTrue(reader.columnsNotAskedFor().isEmpty(), reader.columnsNotAskedFor().toString());
    }

    /**
     * A CloudWatch name lives in a namespace, AWS/EC2:CPUCreditBalance, and its '/' splits a header
     * written by the legend convention: i-0abc/AWS/EC2:CPUCreditBalance reads as component i-0abc,
     * metric AWS, instance EC2:CPUCreditBalance, and a fourth part leaves the header unread. So a wide
     * CloudWatch export names its columns through --columns, whose metric cell holds the name whole.
     */
    @Test
    void aWideCloudWatchExportNamesItsColumnsThroughAColumnsMapBecauseTheNamespaceSplitsAHeader() throws Exception {
        com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric credits =
                com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue.builtIn().type("aws-ec2")
                        .metric("cpu.credit.balance");
        assertEquals("AWS/EC2:CPUCreditBalance", credits.names().get("cloudwatch").metric(),
                "precondition: the catalogue's CloudWatch name carries its namespace");
        ServerKpi kpi = ServerKpi.builder(ServerSource.COLLECTION_ID, credits.identity(null)).id("i-0abc-credits")
                .metric(credits.name()).unit(credits.unit()).reducer(credits.reducer()).gate("i-0abc")
                .fail(Predicate.of(Predicate.Op.LT, 1)).build();
        Path file = dir.resolve("grafana.csv");
        StringBuilder sb = new StringBuilder("\"Time\",\"i-0abc/AWS/EC2:CPUCreditBalance\",\"i-0abc CPUCreditBalance\","
                + "\"i-0abc/AWS/EBS:BurstBalance/vol-1\"\r\n");
        for (int i = 0; i <= 4; i++) {
            String v = i == 2 ? "12.5" : "40";
            sb.append(WINDOW.getStart() + i * 15_000L).append(',').append(v).append(',').append(v).append(",99\r\n");
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        Path map = dir.resolve("columns.csv");
        Files.write(map, "column,component,metric\ni-0abc CPUCreditBalance,i-0abc,AWS/EC2:CPUCreditBalance\n"
                .getBytes(StandardCharsets.UTF_8));

        CsvSource reader = new CsvSource();
        ServerMetricsPayload p = collectAwsEc2(reader, ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(),
                15_000, 0, "cloudwatch", ServerSource.CsvLayout.WIDE, map.toString(), null), kpi);
        ServerMetricsPayload.SeriesRecord s = seriesOf(p, "i-0abc", "cpu.credit.balance");
        assertNotNull(s, p.getSeries().toString());
        assertEquals(CollectionStatus.OK, s.collectionStatus(), String.valueOf(s.getError()));
        // Read by CloudWatch's name, through the map's column, as a gauge: unconverted.
        assertEquals(java.util.Arrays.asList(40.0, 40.0, 12.5, 40.0, 40.0), values(s));
        assertEquals("cloudwatch", s.getReadAs().getTool());
        assertEquals("AWS/EC2:CPUCreditBalance", s.getReadAs().getMetric());
        // The convention's split names a series nothing asks for (component i-0abc, metric AWS), and
        // it is listed as one; four parts are no series at all, and listed as unread.
        assertEquals(Collections.singletonList("i-0abc/AWS/EC2:CPUCreditBalance"), reader.columnsNotAskedFor());
        assertEquals(Collections.singletonList("i-0abc/AWS/EBS:BurstBalance/vol-1"), reader.unmappedColumns());

        // Without the map, the legend's header alone answers nothing for the assert.
        CsvSource bare = new CsvSource();
        ServerMetricsPayload unmapped = collectAwsEc2(bare, ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(),
                15_000, 0, "cloudwatch", ServerSource.CsvLayout.WIDE, null, null), kpi);
        assertTrue(seriesOf(unmapped, "i-0abc", "cpu.credit.balance").getInstances().isEmpty(),
                unmapped.getSeries().toString());
        assertEquals(Collections.singletonList("i-0abc/AWS/EC2:CPUCreditBalance"), bare.columnsNotAskedFor());
    }

    /** A collection over one aws-ec2 component, i-0abc, with the built-in catalogue. */
    private ServerMetricsPayload collectAwsEc2(CsvSource reader, ServerSource source, ServerKpi kpi) throws Exception {
        ServerMetricsConfig config = ServerMetricsConfig.builder().source(source)
                .gate(com.jmeterwebinsightreport.core.servermetrics.ServerGate.component("i-0abc", null, "aws-ec2", null))
                .kpi(kpi).build();
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        readers.put(ServerSource.Type.CSV, reader);
        return new Collector(readers, Collections.<String, String>emptyMap(),
                com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue.builtIn())
                .collect(config, null, new Collector.Run("abcdef0123456789", "Checkout", WINDOW), "now");
    }

    @Test
    void theLayoutAndTheTimeZoneAreRecordedOnlyWhenGivenAndOnlyThenMoveTheCollectionId() throws Exception {
        Path longFile = dir.resolve("long.csv");
        Files.write(longFile, ("time,component,metric,instance,value\n" + every15s("web", "conns.pct", "pg-1", i -> "41"))
                .getBytes(StandardCharsets.UTF_8));
        ServerMetricsPayload plain = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, longFile.toString(),
                15_000, 0), shopKpi("conns.pct"));
        ServerMetricsPayload explicitLong = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, longFile.toString(),
                15_000, 0, null, ServerSource.CsvLayout.LONG, null, null), shopKpi("conns.pct"));
        // A long collection is the file it always was: no key, the same id.
        String written = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(explicitLong);
        assertTrue(!written.contains("\"layout\"") && !written.contains("\"timeZone\""), written);
        assertNull(explicitLong.getSources().get(0).getLayout());
        assertNull(explicitLong.getSources().get(0).getTimeZone());
        assertEquals(plain.getCollectionId(), explicitLong.getCollectionId());

        // The zone, when given, even on a long file: it decided where every zone-less row landed.
        ServerMetricsPayload zoned = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, longFile.toString(),
                15_000, 0, null, ServerSource.CsvLayout.LONG, null, java.time.ZoneId.of("+03:00")), shopKpi("conns.pct"));
        assertNull(zoned.getSources().get(0).getLayout());
        assertEquals("+03:00", zoned.getSources().get(0).getTimeZone());
        assertTrue(!zoned.getCollectionId().equals(plain.getCollectionId()));

        // Wide: the layout, and the zone as java.time names it.
        Path wideFile = dir.resolve("wide.csv");
        StringBuilder sb = new StringBuilder("\"Time\",\"web/conns.pct/pg-1\"\n");
        for (int i = 0; i <= 4; i++) {
            sb.append(WINDOW.getStart() + i * 15_000L).append(",41\n");
        }
        Files.write(wideFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        ServerMetricsPayload wide = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, wideFile.toString(),
                15_000, 0, null, ServerSource.CsvLayout.WIDE, null, java.time.ZoneId.of("UTC")), shopKpi("conns.pct"));
        assertEquals("wide", wide.getSources().get(0).getLayout());
        assertEquals("UTC", wide.getSources().get(0).getTimeZone());
        assertEquals(java.util.Arrays.asList(41.0, 41.0, 41.0, 41.0, 41.0), values(seriesOf(wide, "web", "conns.pct")));
        // The material, spelt and ordered: every series, then the source with the layout after any
        // tool and the zone after the layout, the window, the time. Each one alone moves the id.
        List<String> material = new java.util.ArrayList<>();
        for (ServerMetricsPayload.SeriesRecord series : wide.getSeries()) {
            material.add(series.getSource() + "|" + series.getComponent() + "|" + series.getResolvedQuery());
        }
        material.add(ServerSource.COLLECTION_ID + "|csv|" + wideFile.getFileName() + "|layout=wide|timeZone=UTC");
        material.add(WINDOW.getStart() + "-" + WINDOW.getEnd());
        material.add("now");
        assertEquals(com.jmeterwebinsightreport.core.util.RunIdentity.digest(String.join("\n", material)),
                wide.getCollectionId());
        ServerMetricsPayload wideNoZone = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, wideFile.toString(),
                15_000, 0, null, ServerSource.CsvLayout.WIDE, null, null), shopKpi("conns.pct"));
        assertEquals("wide", wideNoZone.getSources().get(0).getLayout());
        assertNull(wideNoZone.getSources().get(0).getTimeZone());
        assertTrue(!wide.getCollectionId().equals(wideNoZone.getCollectionId()));
    }

    // ---- --csv-columns, --component, InfluxDB v2's annotated CSV ----------------------------------

    /** A long file read with {@code --csv-columns spec} (normalised, as wir-collect passes it) and {@code --component}. */
    private ServerSource mapped(String text, String spec, String component, String zone) throws IOException {
        Path file = dir.resolve("m.csv");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0, null, ServerSource.CsvLayout.LONG,
                null, zone == null ? null : java.time.ZoneId.of(zone), spec == null ? null : CsvColumns.parse(spec).normalised(),
                component);
    }

    /** Asked with no window, so a file's own dates are all kept: the docs' example is from 2023-01-01. */
    private static SeriesSource.Answer askAll(CsvSource reader, ServerSource source, String component, String metric) {
        return reader.collect(source, new SeriesSource.Request("q", component, metric), null);
    }

    private static String refusal(String spec) {
        return org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> CsvColumns.parse(spec)).getMessage();
    }

    @Test
    void aColumnSpecIsKeyEqualsColumnPairsNormalisedIntoTheReadersOwnOrder() {
        // Keys in any order and any case, each part trimmed; written back time, component, metric,
        // instance, value - only those given - so one mapping is one text in the payload.
        CsvColumns c = CsvColumns.parse(" value=_value, Time = _time ,metric=_measurement + _field,instance=host,");
        assertEquals("time=_time,metric=_measurement+_field,instance=host,value=_value", c.normalised());
        assertTrue(c.maps("time") && c.maps("metric") && c.maps("instance") && c.maps("value"));
        assertTrue(!c.maps("component"), "not given: the column keeps its own name");
        assertEquals("component=svc", CsvColumns.parse("component=svc").normalised());
        // A column name keeps its case: the header is matched in any case, the provenance says what was typed.
        assertEquals("metric=metricId,instance=dt.entity.host", CsvColumns.parse("instance=dt.entity.host,metric=metricId")
                .normalised());
    }

    @Test
    void aColumnSpecItCannotReadIsRefusedNamingTheKey() {
        assertEquals("--csv-columns needs key=column pairs, e.g. time=_time,value=_value", refusal(""));
        assertEquals("--csv-columns needs key=column pairs, e.g. time=_time,value=_value", refusal(" , "));
        assertEquals("--csv-columns key 'metrc' is not one of time, component, metric, instance, value", refusal("metrc=_field"));
        assertEquals("--csv-columns gives time twice", refusal("time=_time,value=_value,Time=t"));
        assertEquals("--csv-columns value= names no column", refusal("time=_time,value= "));
        assertEquals("--csv-columns '_time' is not key=column - e.g. time=_time,value=_value", refusal("_time"));
        assertEquals("--csv-columns '=_time' is not key=column - e.g. time=_time,value=_value", refusal("=_time"));
        assertEquals("--csv-columns instance=host+region: only metric joins two columns with '+'",
                refusal("instance=host+region"));
        assertEquals("--csv-columns metric=a+b+c: metric joins two columns at most", refusal("metric=a+b+c"));
        assertEquals("--csv-columns metric=_measurement+: each side of '+' names a column", refusal("metric=_measurement+"));
    }

    @Test
    void eachKeyMapsItsColumnByTheHeadersNameTrimmedAndInAnyCase() throws IOException {
        ServerSource s = mapped(" TS ,Svc,name,node,val,extra\n"
                + "1700000000000,orders-db,connections.used.pct,pg-1,41.5,x\n"
                + "1700000015000,orders-db,connections.used.pct,pg-1,44,x\n"
                + "1700000000000,checkout,queue-depth,,17,x\n",
                "time=ts,component=svc,metric=name,instance=node,value=val", null, null);
        CsvSource reader = new CsvSource();
        SeriesSource.Answer conns = ask(reader, s, "orders-db", "connections.used.pct");
        assertNull(conns.error());
        assertEquals(Collections.singletonMap("instance", "pg-1"), conns.instances().get(0).getLabels());
        assertEquals(java.util.Arrays.asList(41.5, 44.0), valuesOf(conns, 0));
        assertEquals(Collections.emptyMap(), ask(reader, s, "checkout", "queue-depth").instances().get(0).getLabels());
    }

    @Test
    void aKeyTheSpecDoesNotGiveKeepsTheReadersOwnColumnName() throws IOException {
        ServerSource s = mapped("_time,component,metric,instance,value\n"
                + "2023-11-14T22:13:20Z,orders-db,deadlocks,pg-1,1\n", "time=_time", null, null);
        SeriesSource.Answer a = ask(new CsvSource(), s, "orders-db", "deadlocks");
        assertNull(a.error());
        assertEquals(1.0, points(a, 0).get(0).getValue());
        assertEquals(1_700_000_000_000L, points(a, 0).get(0).getTs());
    }

    @Test
    void metricJoinsTwoColumnsWithADotTheTelegrafNameConvention() throws IOException {
        // InfluxDB keeps a Telegraf metric as a measurement and a field: postgresql.numbackends.
        ServerSource s = mapped("_time,_measurement,_field,_value\n"
                + "1700000000000,postgresql,numbackends,12\n"
                + "1700000015000,postgresql,numbackends,13\n"
                + "1700000015000,postgresql,,99\n",
                "time=_time,metric=_measurement+_field,value=_value", "orders-db", null);
        CsvSource reader = new CsvSource();
        assertEquals(java.util.Arrays.asList(12.0, 13.0), valuesOf(ask(reader, s, "orders-db", "postgresql.numbackends"), 0));
        // A row with half a name names no series, as a row with no metric never did.
        assertTrue(ask(reader, s, "orders-db", "postgresql.").instances().isEmpty());
        assertNull(ask(reader, s, "orders-db", "postgresql.").error());
    }

    @Test
    void aMappedColumnTheHeaderLacksFailsTheFileNamingItAndTheHeader() throws IOException {
        ServerSource s = mapped("_time,component,metric,_value\n1700000000000,c,x,1\n", "time=_tme,value=_value", null, null);
        assertEquals("m.csv line 1: the header has no column \"_tme\" (--csv-columns time) - the header is: \"_time\","
                + " \"component\", \"metric\", \"_value\"", ask(new CsvSource(), s, "c", "x").error());
        // A mapped instance is required, as the optional default one is not; and a default missing too is named.
        ServerSource two = mapped("_time,metric,val\n1700000000000,x,1\n", "time=_time,instance=host", "c", null);
        assertEquals("m.csv line 1: the header has no column \"host\" (--csv-columns instance) or \"value\" - the header"
                + " is: \"_time\", \"metric\", \"val\"; --csv-columns maps a column named otherwise",
                ask(new CsvSource(), two, "c", "x").error());
        // Either side of a join.
        ServerSource join = mapped("_time,_measurement,_value\n1700000000000,m,1\n",
                "time=_time,metric=_measurement+_field,value=_value", "c", null);
        assertEquals("m.csv line 1: the header has no column \"_field\" (--csv-columns metric) - the header is:"
                + " \"_time\", \"_measurement\", \"_value\"", ask(new CsvSource(), join, "c", "m.x").error());
        // A header of many tags is quoted up to HEADER_CELLS_LISTED cells, then counted.
        StringBuilder wide = new StringBuilder("_time");
        StringBuilder quoted = new StringBuilder("\"_time\"");
        for (int i = 1; i <= CsvSource.HEADER_CELLS_LISTED + 1; i++) {
            wide.append(",tag").append(i);
            if (i < CsvSource.HEADER_CELLS_LISTED) {
                quoted.append(", \"tag").append(i).append('"');
            }
        }
        ServerSource many = mapped(wide + "\n1700000000000\n", "time=_time,value=_value,metric=_field", "c", null);
        assertEquals("m.csv line 1: the header has no column \"_field\" (--csv-columns metric) or \"_value\" (--csv-columns"
                + " value) - the header is: " + quoted + " and 2 more", ask(new CsvSource(), many, "c", "x").error());
    }

    @Test
    void aFileMissingTheReadersOwnColumnsSaysWhichFlagsNameThem() throws IOException {
        // An InfluxDB export handed over with no flags: every column is named otherwise.
        String influx = "#datatype,string,long,dateTime:RFC3339,double,string,string\n"
                + ",result,table,_time,_value,_field,host\n"
                + ",,0,2023-11-14T22:13:20Z,15.43,mem,A\n";
        assertEquals("m.csv line 2: the header has no time, component, metric, value column - it needs time, component,"
                + " metric and value (instance is optional) - the header is: \"\", \"result\", \"table\", \"_time\","
                + " \"_value\", \"_field\", \"host\"; --csv-columns maps a column named otherwise, and --component gives"
                + " every row a component", ask(new CsvSource(), csv(influx), "c", "x").error());
        // With --component, the component is not asked of the header.
        assertEquals("m.csv line 1: the header has no value column - it needs time, metric and value (instance is"
                + " optional) - the header is: \"time\", \"metric\", \"val\"; --csv-columns maps a column named otherwise",
                ask(new CsvSource(), mapped("time,metric,val\n1700000000000,x,1\n", null, "c", null), "c", "x").error());
    }

    @Test
    void aComponentFlagPutsEveryRowInThatComponent() throws IOException {
        ServerSource s = mapped("time,metric,instance,value\n"
                + "1700000000000,connections.used.pct,pg-1,41\n"
                + "1700000015000,connections.used.pct,pg-1,43\n"
                + "1700000000000,deadlocks,pg-1,0\n", null, "orders-db", null);
        CsvSource reader = new CsvSource();
        assertEquals(java.util.Arrays.asList(41.0, 43.0), valuesOf(ask(reader, s, "orders-db", "connections.used.pct"), 0));
        assertEquals(java.util.Arrays.asList(0.0), valuesOf(ask(reader, s, "orders-db", "deadlocks"), 0));
        // Another component has no rows in this file: an empty answer, as a long file's missing series is.
        assertTrue(ask(reader, s, "events", "connections.used.pct").instances().isEmpty());
    }

    @Test
    void aComponentFlagBesideAFileWithItsOwnComponentColumnIsRefusedForOneSourceOfTruth() throws IOException {
        ServerSource s = mapped("time,Component,metric,value\n1700000000000,orders-db,x,1\n", null, "orders-db", null);
        assertEquals("m.csv line 1: the header has a component column, and --component orders-db names the component"
                + " too - say it in one place: leave out --component, or the column", ask(new CsvSource(), s, "orders-db", "x").error());
        // A column of that name the spec maps to another key is that key's, not a component column.
        ServerSource renamed = mapped("time,component,value\n1700000000000,deadlocks,1\n", "metric=component", "orders-db",
                null);
        assertEquals(1.0, points(ask(new CsvSource(), renamed, "orders-db", "deadlocks"), 0).get(0).getValue());
    }

    /** The InfluxDB v2 docs' annotated CSV, verbatim (tmp/kpi-research/export-formats.md section 5). */
    private static final String INFLUX_DOCS_EXAMPLE = "#group,false,false,true,true,false,false,true,true,true,true\n"
            + "#datatype,string,long,dateTime:RFC3339,dateTime:RFC3339,dateTime:RFC3339,double,string,string,string,string\n"
            + "#default,mean,,,,,,,,,\n"
            + ",result,table,_start,_stop,_time,_value,_field,_measurement,host,region\n"
            + ",,0,2022-12-31T05:41:24Z,2023-01-31T05:41:24.001Z,2023-01-01T00:52:00Z,15.43,mem,m,A,east\n"
            + ",,1,2022-12-31T05:41:24Z,2023-01-31T05:41:24.001Z,2023-01-01T00:52:00Z,59.25,mem,m,B,east\n";

    @Test
    void theInfluxDocsAnnotatedCsvReadsWithTheDocumentedFlags() throws IOException {
        ServerSource s = mapped(INFLUX_DOCS_EXAMPLE, "time=_time,value=_value,metric=_measurement+_field,instance=host", "X",
                null);
        SeriesSource.Answer a = askAll(new CsvSource(), s, "X", "m.mem");
        assertNull(a.error());
        assertEquals(2, a.instances().size());
        assertEquals(Collections.singletonMap("instance", "A"), a.instances().get(0).getLabels());
        assertEquals(Collections.singletonMap("instance", "B"), a.instances().get(1).getLabels());
        long t = java.time.Instant.parse("2023-01-01T00:52:00Z").toEpochMilli();
        assertEquals(t, points(a, 0).get(0).getTs());
        assertEquals(15.43, points(a, 0).get(0).getValue());
        assertEquals(59.25, points(a, 1).get(0).getValue());
    }

    @Test
    void eachBlockOfAnAnnotatedCsvIsReadByItsOwnHeader() throws IOException {
        // A table whose schema differs starts a new block after a blank line: fresh annotations, a
        // fresh header, here with an extra tag column and its columns in another order.
        String text = "#group,false,false,false,false,true,true\r\n"
                + "#datatype,string,long,dateTime:RFC3339,double,string,string\r\n"
                + "#default,_result,,,,,\r\n"
                + ",result,table,_time,_value,_field,host\r\n"
                + ",,0,2023-11-14T22:13:20Z,41,connections.used.pct,pg-1\r\n"
                + ",,0,2023-11-14T22:13:35Z,43,connections.used.pct,pg-1\r\n"
                + "\r\n"
                + "#group,false,false,true,false,false,true,false\r\n"
                + "#datatype,string,long,string,double,dateTime:RFC3339,string,string\r\n"
                + "#default,_result,,,,,,\r\n"
                + ",result,table,host,_value,_time,_field,region\r\n"
                + ",,1,pg-2,7,2023-11-14T22:13:20Z,connections.used.pct,east\r\n"
                + ",,1,pg-2,oops,2023-11-14T22:13:35Z,deadlocks,east\r\n";
        ServerSource s = mapped(text, "time=_time,value=_value,metric=_field,instance=host", "orders-db", null);
        CsvSource reader = new CsvSource();
        SeriesSource.Answer conns = ask(reader, s, "orders-db", "connections.used.pct");
        assertNull(conns.error());
        assertEquals(2, conns.instances().size());
        assertEquals(java.util.Arrays.asList(41.0, 43.0), valuesOf(conns, 0));
        assertEquals(Collections.singletonMap("instance", "pg-2"), conns.instances().get(1).getLabels());
        assertEquals(java.util.Arrays.asList(7.0), valuesOf(conns, 1));
        // Lines keep the file's numbers across blocks and annotations.
        assertEquals("m.csv line 13: value \"oops\" is not a number - write it with a '.' decimal point and no thousands"
                + " separator", ask(reader, s, "orders-db", "deadlocks").error());
    }

    @Test
    void annotationsAreSkippedWhereverABlockStartsAndAnEmptyLeadingColumnIsNobodys() throws IOException {
        // A block may open with any of the three annotations, or none; the leading column has no
        // name, and a spec cannot name it (an empty column is refused), so it maps nothing.
        String text = "#datatype,string,long,dateTime:RFC3339,double,string\n"
                + ",result,table,_time,_value,_field\n"
                + ",_result,0,1700000000000,1,up\n"
                + "\n"
                + "\n"
                + ",result,table,_time,_value,_field\n"
                + ",_result,1,1700000015000,0,up\n"
                + "\n"
                + "#default,_result,,,,\n"
                + "#group,false,false,false,false,true\n"
                + ",result,table,_field,_time,_value\n"
                + ",_result,2,up,1700000030000,1\n";
        ServerSource s = mapped(text, "time=_time,value=_value,metric=_field", "orders-db", null);
        assertEquals(java.util.Arrays.asList(1.0, 0.0, 1.0), valuesOf(ask(new CsvSource(), s, "orders-db", "up"), 0));
        // A block whose header lacks a mapped column fails the file, at that header's line.
        String broken = ",result,table,_time,_value,_field\n,_result,0,1700000000000,1,up\n\n"
                + "#datatype,string,long,dateTime:RFC3339,double\n,result,table,_time,_value\n,_result,1,1700000015000,0\n";
        assertEquals("m.csv line 5: the header has no column \"_field\" (--csv-columns metric) - the header is: \"\","
                + " \"result\", \"table\", \"_time\", \"_value\"",
                ask(new CsvSource(), mapped(broken, "time=_time,value=_value,metric=_field", "orders-db", null),
                        "orders-db", "up").error());
    }

    @Test
    void aPlainLongFileReadsAsItAlwaysDidABlankLineInItsMiddleIncluded() throws IOException {
        // Today's long files have no '#' lines and one block. A blank line between two runs of rows is
        // not a new header: the row after it reads as a row of the block it is in.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1700000000000,c,x,,1\n"
                + "\n"
                + "1700000015000,c,x,,2\n"
                + "\n"
                + "2023-11-14T22:13:50Z,c,x,,3\n");
        SeriesSource.Answer a = ask(new CsvSource(), s, "c", "x");
        assertNull(a.error());
        assertEquals(java.util.Arrays.asList(1.0, 2.0, 3.0), valuesOf(a, 0));
        // ...and a second header after a blank line, as two exports concatenated, is a header.
        ServerSource twice = csv("time,component,metric,instance,value\n1700000000000,c,x,,1\n\n"
                + "component,metric,time,value\nc,x,1700000015000,2\n");
        assertEquals(java.util.Arrays.asList(1.0, 2.0), valuesOf(ask(new CsvSource(), twice, "c", "x"), 0));
    }

    @Test
    void aLoneHashLineStartsNothingAndInfluxsOwnAnnotationsStartABlockWithOrWithoutABlankLine() throws IOException {
        // A comment after a blank line is skipped and starts no block: the row after it is a row, as
        // it was before annotated CSV was read, never a header the whole file then fails on.
        ServerSource commented = csv("time,component,metric,instance,value\n"
                + "1700000000000,c,x,,1\n"
                + "\n"
                + "# pasted from the second export\n"
                + "1700000015000,c,x,,2\n"
                + "#\n"
                + "1700000030000,c,x,,3\n");
        SeriesSource.Answer a = ask(new CsvSource(), commented, "c", "x");
        assertNull(a.error());
        assertEquals(java.util.Arrays.asList(1.0, 2.0, 3.0), valuesOf(a, 0));
        // #group, #datatype and #default start a block even with no blank line before them: the line
        // after them is its header, never a row read by the last block's columns.
        String text = ",result,table,_time,_value,_field\n"
                + ",,0,1700000000000,1,up\n"
                + "#datatype,string,long,double,dateTime:RFC3339,string\n"
                + ",result,table,_value,_time,_field\n"
                + ",,1,0,1700000015000,up\n";
        SeriesSource.Answer up = ask(new CsvSource(), mapped(text, "time=_time,value=_value,metric=_field", "orders-db", null),
                "orders-db", "up");
        assertNull(up.error());
        assertEquals(java.util.Arrays.asList(1.0, 0.0), valuesOf(up, 0));
    }

    @Test
    void aFileWithNoHeaderSaysWhatHeaderItWantedOrThatItHeldOnlyAnnotations() throws IOException {
        // An InfluxDB query that returned nothing writes its annotations and no rows.
        assertEquals("m.csv has annotation lines and no header - the export holds no rows",
                ask(new CsvSource(), mapped("#group,false,false\n#datatype,string,long\n#default,_result,\n",
                        "time=_time,value=_value,metric=_field", "orders-db", null), "orders-db", "up").error());
        // An empty file asks for the header the flags name: the mapped columns, the reader's own for the rest.
        assertEquals("m.csv is empty - it needs a header row: _time,_measurement,_field,host,_value",
                ask(new CsvSource(), mapped("", "time=_time,value=_value,metric=_measurement+_field,instance=host",
                        "orders-db", null), "orders-db", "up").error());
        assertEquals("m.csv is empty - it needs a header row: time,component,metric,instance,value",
                ask(new CsvSource(), csv("\n\n"), "c", "x").error());
    }

    @Test
    void aDynatraceExportReadsWithItsColumnsMappedAndItsZoneGiven() throws IOException {
        // Metrics API v2 with Accept: text/csv; header=present: the metric id, a dimension column, a
        // time with no zone (UTC), the value.
        String text = "metricId,dt.entity.host,time,value\n"
                + "builtin:host.cpu.usage,HOST-0956C3557E9109C1,2023-11-14 22:13:20,19.0\n"
                + "builtin:host.cpu.usage,HOST-0956C3557E9109C1,2023-11-14 22:14:20,21.5\n";
        ServerSource utc = mapped(text, "metric=metricId,instance=dt.entity.host", "web", "UTC");
        SeriesSource.Answer a = ask(new CsvSource(), utc, "web", "builtin:host.cpu.usage");
        assertNull(a.error());
        assertEquals(Collections.singletonMap("instance", "HOST-0956C3557E9109C1"), a.instances().get(0).getLabels());
        assertEquals(java.util.Arrays.asList(1_700_000_000_000L, 1_700_000_060_000L), points(a, 0).stream()
                .map(ServerMetricsPayload.Point::getTs).collect(java.util.stream.Collectors.toList()));
        // Without the zone, refused by line as any zone-less time is.
        assertTrue(ask(new CsvSource(), mapped(text, "metric=metricId,instance=dt.entity.host", "web", null),
                "web", "builtin:host.cpu.usage").error().startsWith("m.csv line 2: time \"2023-11-14 22:13:20\" has no time zone"));
    }

    @Test
    void anInfluxDb3SqlResultShapedLongReadsWithAComponentAndUtc() throws IOException {
        // SELECT time, 'postgresql.numbackends' AS metric, host AS instance, numbackends AS value ...:
        // this reader's own names, no component, and UTC times written with no zone, fraction and all.
        ServerSource s = mapped("time,metric,instance,value\n"
                + "2023-11-14T22:13:20.690067,postgresql.numbackends,pg-1,12\n"
                + "2023-11-14T22:13:35,postgresql.numbackends,pg-1,14\n", null, "orders-db", "UTC");
        SeriesSource.Answer a = ask(new CsvSource(), s, "orders-db", "postgresql.numbackends");
        assertNull(a.error());
        assertEquals(1_700_000_000_690L, points(a, 0).get(0).getTs());
        assertEquals(java.util.Arrays.asList(12.0, 14.0), valuesOf(a, 0));
    }

    @Test
    void theColumnsAndTheComponentAreRecordedOnlyWhenGivenAndOnlyThenMoveTheCollectionId() throws Exception {
        Path longFile = dir.resolve("long.csv");
        Files.write(longFile, ("time,component,metric,instance,value\n" + every15s("web", "conns.pct", "pg-1", i -> "41"))
                .getBytes(StandardCharsets.UTF_8));
        ServerMetricsPayload plain = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, longFile.toString(),
                15_000, 0), shopKpi("conns.pct"));
        ServerMetricsPayload explicitNulls = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, longFile.toString(),
                15_000, 0, null, ServerSource.CsvLayout.LONG, null, null, null, null), shopKpi("conns.pct"));
        // A plain long collection is the file it always was: no key on the source, the same id.
        com.fasterxml.jackson.databind.JsonNode src = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(explicitNulls).get("sources").get(0);
        assertTrue(!src.has("columns") && !src.has("component"), src.toString());
        assertEquals(plain.getCollectionId(), explicitNulls.getCollectionId());

        Path influx = dir.resolve("influx.csv");
        StringBuilder sb = new StringBuilder("#datatype,string,long,dateTime:RFC3339,double,string,string\n"
                + ",result,table,_time,_value,_field,host\n");
        for (int i = 0; i <= 4; i++) {
            sb.append(",,0,").append(java.time.Instant.ofEpochMilli(WINDOW.getStart() + i * 15_000L)).append(",41,conns.pct,pg-1\n");
        }
        Files.write(influx, sb.toString().getBytes(StandardCharsets.UTF_8));
        String spec = CsvColumns.parse("value=_value,time=_time,metric=_field,instance=host").normalised();
        ServerMetricsPayload mappedP = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, influx.toString(), 15_000,
                0, null, ServerSource.CsvLayout.LONG, null, null, spec, "web"), shopKpi("conns.pct"));
        assertEquals("time=_time,metric=_field,instance=host,value=_value", mappedP.getSources().get(0).getColumns());
        assertEquals("web", mappedP.getSources().get(0).getComponent());
        assertEquals(java.util.Arrays.asList(41.0, 41.0, 41.0, 41.0, 41.0), values(seriesOf(mappedP, "web", "conns.pct")));
        // The material, spelt and ordered: the columns after any zone, the component after the columns.
        List<String> material = new java.util.ArrayList<>();
        for (ServerMetricsPayload.SeriesRecord series : mappedP.getSeries()) {
            material.add(series.getSource() + "|" + series.getComponent() + "|" + series.getResolvedQuery());
        }
        material.add(ServerSource.COLLECTION_ID + "|csv|" + influx.getFileName() + "|columns=time=_time,metric=_field,instance=host,"
                + "value=_value|component=web");
        material.add(WINDOW.getStart() + "-" + WINDOW.getEnd());
        material.add("now");
        assertEquals(com.jmeterwebinsightreport.core.util.RunIdentity.digest(String.join("\n", material)),
                mappedP.getCollectionId());

        // Each alone moves the id: the component without a spec, on a file of this reader's own names.
        Path noComponent = dir.resolve("no-component.csv");
        Files.write(noComponent, ("time,metric,instance,value\n" + every15s("web", "conns.pct", "pg-1", i -> "41")
                .replace("web,", "")).getBytes(StandardCharsets.UTF_8));
        ServerMetricsPayload componentOnly = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, noComponent.toString(),
                15_000, 0, null, ServerSource.CsvLayout.LONG, null, null, null, "web"), shopKpi("conns.pct"));
        assertNull(componentOnly.getSources().get(0).getColumns());
        assertEquals("web", componentOnly.getSources().get(0).getComponent());
        assertEquals(java.util.Arrays.asList(41.0, 41.0, 41.0, 41.0, 41.0), values(seriesOf(componentOnly, "web", "conns.pct")));
        assertEquals(com.jmeterwebinsightreport.core.util.RunIdentity.digest(String.join("\n",
                materialOf(componentOnly, noComponent + "|component=web"))), componentOnly.getCollectionId());

        // ...and the spec without the component, on a file whose rows name theirs in a column it maps.
        Path tagged = dir.resolve("tagged.csv");
        StringBuilder tb = new StringBuilder(",result,table,_time,_value,_field,host,svc\n");
        for (int i = 0; i <= 4; i++) {
            tb.append(",,0,").append(java.time.Instant.ofEpochMilli(WINDOW.getStart() + i * 15_000L)).append(",41,conns.pct,pg-1,web\n");
        }
        Files.write(tagged, tb.toString().getBytes(StandardCharsets.UTF_8));
        String tagSpec = CsvColumns.parse("time=_time,value=_value,metric=_field,instance=host,component=svc").normalised();
        ServerMetricsPayload columnsOnly = collectWith(ServerSource.csv(ServerSource.COLLECTION_ID, tagged.toString(), 15_000,
                0, null, ServerSource.CsvLayout.LONG, null, null, tagSpec, null), shopKpi("conns.pct"));
        assertEquals("time=_time,component=svc,metric=_field,instance=host,value=_value",
                columnsOnly.getSources().get(0).getColumns());
        assertNull(columnsOnly.getSources().get(0).getComponent());
        assertEquals(java.util.Arrays.asList(41.0, 41.0, 41.0, 41.0, 41.0), values(seriesOf(columnsOnly, "web", "conns.pct")));
        assertEquals(com.jmeterwebinsightreport.core.util.RunIdentity.digest(String.join("\n",
                materialOf(columnsOnly, tagged + "|columns=" + tagSpec))), columnsOnly.getCollectionId());
    }

    /** A CSV collection's collectionId material, as {@code Collector} spells it, its source line ending {@code pathAndKeys}. */
    private static List<String> materialOf(ServerMetricsPayload payload, String pathAndKeys) {
        List<String> material = new java.util.ArrayList<>();
        for (ServerMetricsPayload.SeriesRecord series : payload.getSeries()) {
            material.add(series.getSource() + "|" + series.getComponent() + "|" + series.getResolvedQuery());
        }
        // The source is recorded by its file name (no local directory on the page).
        String[] parts = pathAndKeys.split("[|]", 2);
        String name = java.nio.file.Paths.get(parts[0]).getFileName().toString();
        material.add(ServerSource.COLLECTION_ID + "|csv|" + name + (parts.length > 1 ? "|" + parts[1] : ""));
        material.add(WINDOW.getStart() + "-" + WINDOW.getEnd());
        material.add("now");
        return material;
    }

    @Test
    void aPayloadCollectedBeforeComponentsWereRecordedStillMatchesAnyComponent() {
        ServerMetricsPayload p = new ServerMetricsPayload();
        ServerMetricsPayload.SeriesRecord old = new ServerMetricsPayload.SeriesRecord();
        old.setSource("metrics");
        old.setQuery("up");
        p.getSeries().add(old);
        assertEquals(old, p.findByQuery("metrics", "up", "kafka"));
        ServerMetricsPayload.SeriesRecord tagged = new ServerMetricsPayload.SeriesRecord();
        tagged.setSource("metrics");
        tagged.setComponent("db");
        tagged.setQuery("lag");
        p.getSeries().add(tagged);
        assertNull(p.findByQuery("metrics", "lag", "kafka"), "another component's series is not this one's");
        assertEquals(tagged, p.findByQuery("metrics", "lag", "db"));
    }

    // ---- rows on both sides of the run (F15) ------------------------------------------------------

    @Test
    void rowsBeforeAndAfterTheRunWithNoneInsideAreATimeProblemNotValueless() throws IOException {
        // An export of two other runs, this one falling between them: every row has a number.
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1699999900000,app,cpu,,40\n1699999915000,app,cpu,,41\n"
                + "1700000160000,app,cpu,,42\n1700000175000,app,cpu,,43\n");
        assertEquals("m.csv has 4 rows for app/cpu and none inside the run: they run 2023-11-14T22:11:40Z .."
                + " 2023-11-14T22:16:15Z, before and after the run (2023-11-14T22:13:20Z .. 2023-11-14T22:14:20Z)"
                + " - export the run's window", ask(new CsvSource(), s, "app", "cpu").error());
    }

    @Test
    void blankRowsInsideTheRunBesideNumberedOnesOutsideItAreCountedInside() throws IOException {
        ServerSource s = csv("time,component,metric,instance,value\n"
                + "1699999900000,app,cpu,,40\n1700000000000,app,cpu,,\n1700000015000,app,cpu,,NaN\n");
        assertEquals("m.csv has 2 rows for app/cpu inside the run and every value is empty or NaN - the export"
                + " carries the series' times and no numbers", ask(new CsvSource(), s, "app", "cpu").error());
    }

    // ---- the measured step (decision 12): the reader's own index, the reader's own rules ----------

    /** An hour from 1700000000000: long enough that no measured step here is cut by the window. */
    private static final ServerMetricsPayload.Window HOUR =
            new ServerMetricsPayload.Window(1_700_000_000_000L, 1_700_003_600_000L, null);

    private static String rows(String series, long from, long every, int n, boolean descending) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            long t = from + every * (descending ? n - 1 - i : i);
            sb.append(t).append(',').append(series).append(",1\n");
        }
        return sb.toString();
    }

    @Test
    void theStepIsAnInstancesMostCommonSpacingBetweenRowsWithAValue() throws IOException {
        // Instance a every 5 s; instance b's rows are blank but for every third, which would read
        // 15 s - a blank row is a gap, not the export's interval, so b measures 15 s and is the widest.
        StringBuilder sb = new StringBuilder("time,component,metric,instance,value\n");
        for (int i = 0; i < 30; i++) {
            long t = 1_700_000_000_000L + i * 5000L;
            sb.append(t).append(",app,cpu,a,50\n");
            sb.append(t).append(",app,cpu,b,").append(i % 3 == 0 ? "7" : "").append('\n');
        }
        assertEquals(15_000, new CsvSource().measuredStepMillis(csv(sb.toString()), HOUR));
        // Without b, a's 5 s.
        assertEquals(5_000, new CsvSource().measuredStepMillis(csv("time,component,metric,instance,value\n"
                + rows("app,cpu,a", 1_700_000_000_000L, 5000, 10, false)), HOUR));
    }

    @Test
    void jitteredTimestampsMeasureNoStep() throws IOException {
        // Gaps of 15001, 14998, 15003, 14998 ms: none is seen three times (F6).
        ServerSource s = csv("time,component,metric,value\n1700000000000,app,cpu,1\n1700000015001,app,cpu,1\n"
                + "1700000029999,app,cpu,1\n1700000045002,app,cpu,1\n1700000060000,app,cpu,1\n");
        assertEquals(0, new CsvSource().measuredStepMillis(s, HOUR));
        // An hour of a raw 15 s export jittered by up to 3 ms, over several instances. Each instance's
        // most common gap repeats dozens of times (14998ms 103 times in 239), so on repeats alone the
        // widest of them, 15002ms, was taken - a step no sustainedFor 30s is a whole number of. None is
        // half the gaps, so nothing is measured and the collection keeps the default.
        StringBuilder sb = new StringBuilder("time,component,metric,instance,value\n");
        long[] jitter = {0, 3, -2, 1, -3, 2, -1};
        for (int inst = 0; inst < 5; inst++) {
            for (int i = 0; i < 240; i++) {
                long t = 1_700_000_000_000L + i * 15_000L + jitter[(i * (inst + 2)) % jitter.length];
                sb.append(t).append(",app,cpu,i").append(inst).append(",1\n");
            }
        }
        assertEquals(0, new CsvSource().measuredStepMillis(csv(sb.toString()), HOUR));
    }

    @Test
    void anExportWithNoOneIntervalForMostOfItsRowsMeasuresNoStep() throws IOException {
        // Gaps of 10, 12 and 11 s: whole seconds, each seen once.
        assertEquals(0, new CsvSource().measuredStepMillis(csv("time,component,metric,value\n"
                + "1700000000000,app,cpu,1\n1700000010000,app,cpu,1\n1700000022000,app,cpu,1\n"
                + "1700000033000,app,cpu,1\n"), HOUR));
        // Two repeats are not enough.
        assertEquals(0, new CsvSource().measuredStepMillis(csv("time,component,metric,value\n"
                + rows("app,cpu", 1_700_000_000_000L, 10_000, 3, false)), HOUR));
        // Three 5 s gaps among nine scattered ones repeat, and are still not the export's interval.
        long[] gaps = {5000, 7000, 5000, 9000, 11000, 5000, 13000, 17000, 19000, 23000, 29000, 31000};
        StringBuilder sb = new StringBuilder("time,component,metric,value\n");
        long t = 1_700_000_000_000L;
        sb.append(t).append(",app,cpu,1\n");
        for (long g : gaps) {
            t += g;
            sb.append(t).append(",app,cpu,1\n");
        }
        assertEquals(0, new CsvSource().measuredStepMillis(csv(sb.toString()), HOUR));
    }

    @Test
    void anIntervalIsTakenWhenItIsHalfTheGapsOrMore() {
        assertEquals(500, CsvSource.intervalOf(java.util.Arrays.asList(0L, 500L, 1000L, 1500L)));
        // Half the gaps is enough - an export that missed a scrape now and then keeps its interval.
        assertEquals(15_000, CsvSource.intervalOf(java.util.Arrays.asList(0L, 15_000L, 45_000L, 60_000L,
                90_000L, 105_000L, 135_000L)));
        // Three of seven is not: 15 s three times, 30 s three times, 35 s once.
        assertEquals(0, CsvSource.intervalOf(java.util.Arrays.asList(0L, 15_000L, 45_000L, 60_000L,
                90_000L, 105_000L, 135_000L, 170_000L)));
    }

    @Test
    void aDescendingExportMeasuresItsInterval() throws IOException {
        assertEquals(10_000, new CsvSource().measuredStepMillis(csv("time,component,metric,value\n"
                + rows("app,cpu", 1_700_000_000_000L, 10_000, 8, true)), HOUR));
    }

    @Test
    void aFileMixingIntervalsMeasuresItsWidestSoNoSeriesIsCountedInFinerSlots() throws IOException {
        // One host series at 1 s and a DB series at 15 s: 15 s, which costs the 1 s series nothing (F7).
        ServerSource s = csv("time,component,metric,value\n"
                + rows("host,cpu", 1_700_000_000_000L, 1000, 600, false)
                + rows("db,conns", 1_700_000_000_000L, 15_000, 40, false));
        assertEquals(15_000, new CsvSource().measuredStepMillis(s, HOUR));
    }

    @Test
    void onlyRowsInTheRunAndOneStepOfItAreMeasured() throws IOException {
        // An hour of 1 s rows the day before, then 10 s rows over the run.
        ServerSource s = csv("time,component,metric,value\n"
                + rows("app,cpu", 1_700_000_000_000L - 86_400_000L, 1000, 3600, false)
                + rows("app,cpu", 1_700_000_000_000L, 10_000, 30, false));
        assertEquals(10_000, new CsvSource().measuredStepMillis(s, HOUR));
    }

    @Test
    void aCsvColumnsMapIsHonouredAsTheReaderHonoursIt() throws IOException {
        // A capitalised key and a column named in another case: the reader's rules, so measured (F9);
        // the mapped value column is never part of a series' key, so a changing value is no new series (F8).
        StringBuilder sb = new StringBuilder("ts,name,reading\n");
        for (int i = 0; i < 10; i++) {
            sb.append(1_700_000_000_000L + i * 30_000L).append(",cpu,").append(i).append('\n');
        }
        Path file = dir.resolve("mapped.csv");
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        ServerSource s = ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0, null,
                ServerSource.CsvLayout.LONG, null, null,
                CsvColumns.parse("Time=TS,metric=name,value=reading").normalised(), "app");
        assertEquals(30_000, new CsvSource().measuredStepMillis(s, HOUR));
    }

    @Test
    void aWideFileReadThroughAColumnsMapMeasuresEachColumnAsASeries() throws IOException {
        // Legend headers a --columns map names; a gauge alternating 12,15 must not pair equal values (F8).
        StringBuilder sb = new StringBuilder("Time,orders-db CPU,orders-db Mem\n");
        for (int i = 0; i < 10; i++) {
            sb.append(1_700_000_000_000L + i * 15_000L).append(',').append(i % 2 == 0 ? 12 : 15).append(",3\n");
        }
        Path file = dir.resolve("wide.csv");
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        Path map = dir.resolve("map.csv");
        Files.write(map, ("column,component,metric,instance\norders-db CPU,orders-db,cpu,\norders-db Mem,orders-db,mem,\n")
                .getBytes(StandardCharsets.UTF_8));
        ServerSource s = ServerSource.csv(ServerSource.COLLECTION_ID, file.toString(), 15_000, 0, null,
                ServerSource.CsvLayout.WIDE, map.toString(), null);
        assertEquals(15_000, new CsvSource().measuredStepMillis(s, HOUR));
    }

    @Test
    void epochMicrosecondsMeasureNothingAndTheReaderRefusesThem() throws IOException {
        // Read as milliseconds they were 1000 x the interval (F11): the reader's ceiling applies.
        StringBuilder sb = new StringBuilder("time,component,metric,value\n");
        for (int i = 0; i < 6; i++) {
            sb.append(1_700_000_000_000_000L + i * 15_000_000L).append(",app,cpu,1\n");
        }
        ServerSource s = csv(sb.toString());
        CsvSource reader = new CsvSource();
        assertEquals(0, reader.measuredStepMillis(s, HOUR));
        assertTrue(ask(reader, s, "app", "cpu").error().contains("looks like epoch microseconds"));
    }

    @Test
    void aFileTheReaderRefusesMeasuresNothing() throws IOException {
        assertEquals(0, new CsvSource().measuredStepMillis(csv("when,component,metric,value\n1,a,b,1\n"), HOUR));
        assertEquals(0, new CsvSource().measuredStepMillis(ServerSource.csv(ServerSource.COLLECTION_ID,
                dir.resolve("missing.csv").toString(), 15_000, 0), HOUR));
    }

    @Test
    void theRowsSpacingCountsABlankRow() throws IOException {
        // 15 s rows, two in three blank: the points are 45 s apart, the rows 15 s.
        StringBuilder sb = new StringBuilder("time,component,metric,value\n");
        for (int i = 0; i < 12; i++) {
            sb.append(1_700_000_000_000L + i * 15_000L).append(",app,cpu,").append(i % 3 == 0 ? "1" : "").append('\n');
        }
        CsvSource reader = new CsvSource();
        ServerSource s = csv(sb.toString());
        ask(reader, s, "app", "cpu");
        assertEquals(15_000, reader.rowSpacingMillis(s, HOUR, "app", "cpu"));
        assertEquals(8, reader.blankRows(s, HOUR, "app", "cpu"));
    }

    @Test
    void theRowsSpacingAndBlankRowsCountOnlyTheRowsTheCollectionKeeps() throws IOException {
        // A day-old hour of 1 s rows, every other one blank, then 15 s rows over the run, one in three
        // blank: the run's rows are 15 s apart with 8 blanks, whatever the file held before it.
        StringBuilder sb = new StringBuilder("time,component,metric,value\n");
        for (int i = 0; i < 3600; i++) {
            sb.append(1_700_000_000_000L - 86_400_000L + i * 1000L).append(",app,cpu,").append(i % 2 == 0 ? "1" : "")
                    .append('\n');
        }
        for (int i = 0; i < 12; i++) {
            sb.append(1_700_000_000_000L + i * 15_000L).append(",app,cpu,").append(i % 3 == 0 ? "1" : "").append('\n');
        }
        CsvSource reader = new CsvSource();
        ServerSource s = csv(sb.toString());
        ask(reader, s, "app", "cpu");
        assertEquals(15_000, reader.rowSpacingMillis(s, HOUR, "app", "cpu"));
        assertEquals(8, reader.blankRows(s, HOUR, "app", "cpu"));
        // The clock offset moves the rows as the collection moves them: a day later, the old hour is the run's.
        ServerSource moved = ServerSource.csv(ServerSource.COLLECTION_ID, s.path(), 15_000, 86_400_000L);
        assertEquals(1_000, reader.rowSpacingMillis(moved, HOUR, "app", "cpu"));
        assertEquals(1800, reader.blankRows(moved, HOUR, "app", "cpu"));
    }

    @Test
    void theMeasuredStepIsTheAskedSeriesOnesAndACoarseSeriesNobodyReadsSetsNothing() throws IOException {
        // 1 s host rows an assert reads, beside a 60 s series no assert names: 1 s.
        ServerSource s = csv("time,component,metric,value\n"
                + rows("host,cpu", 1_700_000_000_000L, 1000, 600, false)
                + rows("db,conns", 1_700_000_000_000L, 60_000, 40, false));
        List<SeriesSource.Request> asked = Collections.singletonList(new SeriesSource.Request("q", "host", "cpu"));
        assertEquals(1_000, new CsvSource().measuredStepMillis(s, HOUR, asked));
        // Asked for both, the widest: the coarse one is now read, and must not be counted in 1 s slots.
        List<SeriesSource.Request> both = java.util.Arrays.asList(new SeriesSource.Request("q", "host", "cpu"),
                new SeriesSource.Request("q", "db", "conns"));
        assertEquals(60_000, new CsvSource().measuredStepMillis(s, HOUR, both));
        // Asked for nothing the file has: nothing measured.
        assertEquals(0, new CsvSource().measuredStepMillis(s, HOUR,
                Collections.singletonList(new SeriesSource.Request("q", "app", "mem"))));
    }
}
