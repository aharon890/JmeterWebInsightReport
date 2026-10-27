package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6: the instance a number came from, for a series no assert names.
 *
 * <p>The payload writer draws at most twenty instances of a series, and the metric picker shows a
 * capped catalogue series' number ({@link ServerKpiEvaluator#judgedActual}) whether or not an
 * assert names it. So the instance that number came from has to be among the twenty, or "low 0"
 * sits over a flat line at 1. {@link ServerKpiEvaluator#decidingInstances(ServerMetricsConfig,
 * ServerMetricsPayload, ServerMetricsPayload.SeriesRecord, Reducer, Across)} answers it with
 * judgedActual's own guards and window: a deciding instance exists exactly when judgedActual has a
 * number, and under across max or min that one instance folds to the very number.
 */
class ServerKpiEvaluatorTest {

    private static final long START = 1_700_000_000_000L;
    private static final long STEP = 15_000L;
    private static final int POINTS = 5;
    private static final int INSTANCES = 6;

    private interface Values {
        double at(int instance, int t);
    }

    /** Instance 2 spikes, instance 4 dips, instance 5 grows fastest and ends highest. */
    private static double shaped(int i, int t) {
        if (i == 2 && t == 2) return 1000;
        if (i == 4 && t == 1) return -5;
        if (i == 5) return 10 + 40 * t;
        return 10 * i + t;
    }

    @Test
    void theRankingPutsTheWorstFirstInTheAcrossDirectionTiesInIndexOrder() {
        // C5: the display cap fills its free slots from this. Under max of each instance's last
        // point, 10, 20, 0, 20, 5 and 40 rank 5, 1, 3 (a tie, in index order), 0, 4, 2; under min
        // the other way round, ties still in index order; under avg (mean 15.83) by distance from
        // the mean, furthest first.
        ServerMetricsPayload p = payload(null, (i, t) -> new double[] {10, 20, 0, 20, 5, 40}[i]);
        ServerMetricsPayload.SeriesRecord s = p.getSeries().get(0);
        Reducer last = Reducer.of(Reducer.Kind.LAST);
        assertEquals(java.util.Arrays.asList(5, 1, 3, 0, 4, 2),
                ServerKpiEvaluator.rankedInstances(null, p, s, last, Across.MAX));
        assertEquals(java.util.Arrays.asList(2, 4, 0, 1, 3, 5),
                ServerKpiEvaluator.rankedInstances(null, p, s, last, Across.MIN));
        assertEquals(java.util.Arrays.asList(5, 2, 4, 0, 1, 3),
                ServerKpiEvaluator.rankedInstances(null, p, s, last, Across.AVG));
        // Under max and min the first is the deciding instance.
        assertEquals(ServerKpiEvaluator.decidingInstances(null, p, s, last, Across.MAX).get(0),
                ServerKpiEvaluator.rankedInstances(null, p, s, last, Across.MAX).get(0));
        assertEquals(ServerKpiEvaluator.decidingInstances(null, p, s, last, Across.MIN).get(0),
                ServerKpiEvaluator.rankedInstances(null, p, s, last, Across.MIN).get(0));
    }

    @Test
    void underAcrossMaxOrMinTheDecidingInstanceFoldsToTheNumber() {
        ServerMetricsPayload p = payload(null, ServerKpiEvaluatorTest::shaped);
        ServerMetricsPayload.SeriesRecord s = p.getSeries().get(0);
        List<Reducer> reducers = new ArrayList<>();
        for (Reducer.Kind k : new Reducer.Kind[] {Reducer.Kind.MAX, Reducer.Kind.MIN, Reducer.Kind.LAST,
                Reducer.Kind.AVG, Reducer.Kind.SLOPE}) {
            reducers.add(Reducer.of(k));
        }
        reducers.add(Reducer.percentile(95));
        reducers.add(Reducer.of(Reducer.Kind.MAX).over(Reducer.Over.sustainedFor(30_000)));
        reducers.add(Reducer.of(Reducer.Kind.MIN).over(Reducer.Over.breachBudget(0.4)));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Reducer r : reducers) {
            for (Across a : new Across[] {Across.MAX, Across.MIN, null}) {
                Double number = ServerKpiEvaluator.judgedActual(null, p, s, r, a);
                assertNotNull(number, r.describe() + " / " + a);
                List<Integer> deciding = ServerKpiEvaluator.decidingInstances(null, p, s, r, a);
                assertEquals(1, deciding.size(), r.describe() + " / " + a + ": one instance decides");
                assertEquals(number, ServerKpiEvaluator.judgedActual(null, p, only(s, deciding), r, a),
                        r.describe() + " / " + a + ": the deciding instance alone folds to the number");
                seen.addAll(deciding);
            }
        }
        assertTrue(seen.size() >= 3, "the shapes give different reducers different instances: " + seen);
    }

    @Test
    void underAcrossAvgTheTwoThatPullTheAverageFurthestDecide() {
        ServerMetricsPayload p = payload(null, ServerKpiEvaluatorTest::shaped);
        ServerMetricsPayload.SeriesRecord s = p.getSeries().get(0);
        Reducer max = Reducer.of(Reducer.Kind.MAX);
        // Per instance under max: 4, 14, 1000, 34, 44, 170 - the highest is 2, the lowest 0.
        assertEquals(java.util.Arrays.asList(2, 0), ServerKpiEvaluator.decidingInstances(null, p, s, max, Across.AVG));
    }

    @Test
    void whereJudgedActualHasNoNumberThereIsNoDecidingInstance() {
        ServerMetricsPayload p = payload(null, ServerKpiEvaluatorTest::shaped);
        ServerMetricsPayload.SeriesRecord s = p.getSeries().get(0);
        Reducer max = Reducer.of(Reducer.Kind.MAX);
        // A tolerance on a reducer with no level to hold: INVALID_RULE, no number.
        Reducer noLevel = Reducer.of(Reducer.Kind.AVG).over(Reducer.Over.sustainedFor(30_000));
        ServerMetricsPayload.SeriesRecord empty = series(0, (i, t) -> 0);
        ServerMetricsPayload outside = payload(null, ServerKpiEvaluatorTest::shaped);
        outside.setWindow(new ServerMetricsPayload.Window(START + 100 * STEP, START + 200 * STEP, null));

        // A sustainedFor that is not a whole number of steps is the next test: on this 15 s grid no
        // 20 s window is ever complete, so the reducer has no number with or without the step check,
        // and a row here could not tell the two apart.
        Object[][] cases = {
                {"no payload", null, s, max},
                {"no series", p, null, max},
                {"no reducer", p, s, null},
                {"no level to hold", p, s, noLevel},
                {"no instances", p, empty, max},
                {"no point in the window", outside, outside.getSeries().get(0), max},
        };
        for (Object[] c : cases) {
            ServerMetricsPayload payload = (ServerMetricsPayload) c[1];
            ServerMetricsPayload.SeriesRecord series = (ServerMetricsPayload.SeriesRecord) c[2];
            Reducer reducer = (Reducer) c[3];
            assertNull(ServerKpiEvaluator.judgedActual(null, payload, series, reducer, Across.MAX), (String) c[0]);
            assertEquals(Collections.emptyList(),
                    ServerKpiEvaluator.decidingInstances(null, payload, series, reducer, Across.MAX), (String) c[0]);
        }
    }

    @Test
    void aSustainedForThatIsNotAWholeNumberOfStepsHasNoDecidingInstanceEvenWherePointsHoldOne() {
        // Every instance has a point every 5 s, which does hold 20 s windows; only the step the
        // source declares differs. At 5 s, 20 s is four steps: a number, and the instance it came
        // from. At 15 s it is not a whole number of steps: judgedActual has no number, so there is
        // no deciding instance either - not the one the finer points would give.
        Reducer offStep = Reducer.of(Reducer.Kind.MAX).over(Reducer.Over.sustainedFor(20_000));
        for (long step : new long[] {5_000L, STEP}) {
            ServerMetricsPayload p = payload(null, (i, t) -> 0);
            p.getSources().get(0).setStepMillis(step);
            ServerMetricsPayload.SeriesRecord s = p.getSeries().get(0);
            for (int i = 0; i < s.getInstances().size(); i++) {
                List<ServerMetricsPayload.Point> points = new ArrayList<>();
                for (long ts = START; ts <= START + (POINTS - 1) * STEP; ts += 5_000L) {
                    // Rising one a second, instance 3 ten higher: held for 20 s, its level at 40 s.
                    points.add(new ServerMetricsPayload.Point(ts, (ts - START) / 1000.0 + (i == 3 ? 10 : 0)));
                }
                s.getInstances().get(i).setPoints(points);
            }
            Double number = ServerKpiEvaluator.judgedActual(null, p, s, offStep, Across.MAX);
            List<Integer> deciding = ServerKpiEvaluator.decidingInstances(null, p, s, offStep, Across.MAX);
            if (step == STEP) {
                assertNull(number, "20 s is not a whole number of 15 s steps");
                assertEquals(Collections.emptyList(), deciding, "no number, so no instance it came from");
            } else {
                assertEquals(50.0, number, 0.0, "four 5 s steps: held for 20 s, 40 + 10 on instance 3");
                assertEquals(Collections.singletonList(3), deciding);
            }
        }
    }

    @Test
    void theRampUpIsLeftOutExactlyAsJudgedActualLeavesItOut() {
        // Instance 0 spikes inside the ramp-up only; instance 3 holds the highest point after it.
        Values values = (i, t) -> i == 0 && t == 0 ? 999 : i == 3 && t == 3 ? 50 : 1;
        ServerMetricsPayload p = payload(START + STEP, values);
        ServerMetricsPayload.SeriesRecord s = p.getSeries().get(0);
        Reducer max = Reducer.of(Reducer.Kind.MAX);

        // No config reads as the default, which excludes the ramp-up.
        assertEquals(50.0, ServerKpiEvaluator.judgedActual(null, p, s, max, Across.MAX), 0.0);
        assertEquals(Collections.singletonList(3), ServerKpiEvaluator.decidingInstances(null, p, s, max, Across.MAX));
        ServerMetricsConfig keep = ServerMetricsConfig.builder().excludeRampUp(false).build();
        assertEquals(999.0, ServerKpiEvaluator.judgedActual(keep, p, s, max, Across.MAX), 0.0);
        assertEquals(Collections.singletonList(0), ServerKpiEvaluator.decidingInstances(keep, p, s, max, Across.MAX));
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static ServerMetricsPayload.SeriesRecord series(int instances, Values values) {
        ServerMetricsPayload.SeriesRecord s = new ServerMetricsPayload.SeriesRecord();
        s.setSource(ServerSource.COLLECTION_ID);
        s.setQuery("q");
        s.setResolvedQuery("q");
        s.setStatus("OK");
        s.setCoverage(1.0);
        for (int i = 0; i < instances; i++) {
            ServerMetricsPayload.InstanceRecord inst = new ServerMetricsPayload.InstanceRecord();
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("instance", "i-" + i);
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

    /** One series of {@link #INSTANCES} instances over {@code [START, START + 4 steps]}. */
    private static ServerMetricsPayload payload(Long rampUpEnd, Values values) {
        ServerMetricsPayload p = new ServerMetricsPayload();
        p.setRunId("r");
        p.setCollectionId("c0ffee");
        p.setWindow(new ServerMetricsPayload.Window(START, START + (POINTS - 1) * STEP, rampUpEnd));
        ServerMetricsPayload.SourceRecord src = new ServerMetricsPayload.SourceRecord();
        src.setId(ServerSource.COLLECTION_ID);
        src.setType("prometheus");
        src.setStatus("OK");
        src.setStepMillis(STEP);
        p.getSources().add(src);
        List<ServerMetricsPayload.SeriesRecord> list = new ArrayList<>();
        list.add(series(INSTANCES, values));
        p.setSeries(list);
        return p;
    }

    /** The same series holding only the instances at {@code indices}. */
    private static ServerMetricsPayload.SeriesRecord only(ServerMetricsPayload.SeriesRecord s, List<Integer> indices) {
        ServerMetricsPayload.SeriesRecord copy = new ServerMetricsPayload.SeriesRecord();
        copy.setSource(s.getSource());
        copy.setQuery(s.getQuery());
        copy.setResolvedQuery(s.getResolvedQuery());
        copy.setStatus(s.getStatus());
        for (int i : indices) {
            copy.getInstances().add(s.getInstances().get(i));
        }
        return copy;
    }
}
