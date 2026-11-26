package com.jmeterwebinsightreport.core.model;

import com.jmeterwebinsightreport.core.rundata.RunSample;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import com.jmeterwebinsightreport.core.rundata.ToolRecords;
import com.jmeterwebinsightreport.core.stats.ResponseTimeDistribution;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool's own measures, the tool's tab (spec 001, FR-008, decision 10): its checks, thresholds and counters as the
 * run data carries them, and the distributions k6's end-of-test summary shows, built from the samples:
 * <ul>
 *   <li>each group's wall time (k6's {@code group_duration}, think time included), over its passes;</li>
 *   <li>the six HTTP timing parts per request name, and over every request.</li>
 * </ul>
 * Every distribution goes through the engine's exact aggregator ({@link ResponseTimeDistribution}), so there is no
 * second percentile routine: a percentile is the Aggregate Report's, the value at rank {@code round(n * p)}. The
 * columns are k6's summary columns (avg, min, med, max, p(90), p(95)); k6 interpolates its percentiles, so its own
 * figures can differ by less than one step between two values.
 *
 * <p>Values are kept as whole hundredths of a millisecond, the resolution run data carries timing parts at. Never a
 * verdict (constitution VII).
 */
public final class ToolMeasures {

    /** k6's six HTTP timing parts, in the order its summary lists them. */
    public static final List<String> TIMING_PARTS = Collections.unmodifiableList(Arrays.asList(
            "blocked", "connecting", "tlsHandshaking", "sending", "waiting", "receiving"));

    /** One distribution in k6's summary columns, in ms. */
    public static final class Trend {
        private final ResponseTimeDistribution hundredths = new ResponseTimeDistribution();
        private long sum;

        void add(long valueInHundredths) {
            hundredths.add(valueInHundredths);
            sum += valueInHundredths;
        }

        void merge(Trend other) {
            hundredths.merge(other.hundredths);
            sum += other.sum;
        }

        public long count() {
            return hundredths.getCount();
        }

        public double avg() {
            return count() == 0 ? 0 : sum / 100.0 / count();
        }

        public double min() {
            return at(0);
        }

        public double med() {
            return at(50);
        }

        public double max() {
            return at(100);
        }

        public double p90() {
            return at(90);
        }

        public double p95() {
            return at(95);
        }

        /** Spec 002, FR-011: shown when the run's summary lists p(99). The same rank rule as every percentile here. */
        public double p99() {
            return at(99);
        }

        private double at(double percentile) {
            return hundredths.getPercentile(percentile) / 100.0;
        }
    }

    private final ToolRecords records;
    private final Map<String, Trend> groupDurations = new LinkedHashMap<>();
    private final Map<String, Map<String, Trend>> timingsByRequest = new LinkedHashMap<>();
    private final Map<String, Trend> durationsByRequest = new LinkedHashMap<>();

    /**
     * Each request name's timing parts per second (spec 002, FR-040): for the second a request ended, as the report's
     * time buckets key a sample (LiveStatisticsEngine.endOf), each part's sum in hundredths of a ms and its count, two
     * longs a part in TIMING_PARTS' order.
     */
    private final Map<String, Map<Long, long[]>> partsBySecond = new LinkedHashMap<>();

    public ToolMeasures(ToolRecords records) {
        this.records = records == null ? new ToolRecords() : records;
    }

    /**
     * Takes what one sample says about the tool's own measures: a group pass's wall time, or a request's timing parts.
     * A JMeter Transaction Controller gives no wall time, and a pass the test's end cut off is a request with no timing
     * part (k6 wrote no {@code group_duration} for it), so neither adds anything.
     */
    public void add(RunSample s) {
        if (s.isTransaction()) {
            if (s.wallTime != null) {
                groupDurations.computeIfAbsent(s.name, k -> new Trend()).add(s.wallTime * 100);
            }
            return;
        }
        if (s.timings == null || s.timings.isEmpty()) {
            return;
        }
        Map<String, Trend> parts = timingsByRequest.computeIfAbsent(s.name, k -> new LinkedHashMap<>());
        long second = Math.floorDiv(LiveStatisticsEngine.endOf(s.start, s.elapsed == null ? 0 : s.elapsed), 1000L) * 1000L;
        long[] inSecond = partsBySecond.computeIfAbsent(s.name, k -> new HashMap<>())
                .computeIfAbsent(second, k -> new long[TIMING_PARTS.size() * 2]);
        for (int i = 0; i < TIMING_PARTS.size(); i++) {
            Double v = s.timings.get(TIMING_PARTS.get(i));
            if (v != null) {
                long h = RunSample.hundredths(v);
                parts.computeIfAbsent(TIMING_PARTS.get(i), k -> new Trend()).add(h);
                inSecond[2 * i] += h;
                inSecond[2 * i + 1]++;
            }
        }
        // k6's request duration, as its tracer defines it: sending + waiting + receiving (spec 002, FR-010)
        Double sending = s.timings.get("sending");
        Double waiting = s.timings.get("waiting");
        Double receiving = s.timings.get("receiving");
        if (sending != null && waiting != null && receiving != null) {
            durationsByRequest.computeIfAbsent(s.name, k -> new Trend()).add(RunSample.hundredths(sending)
                    + RunSample.hundredths(waiting) + RunSample.hundredths(receiving));
        }
    }

    public ToolRecords records() {
        return records;
    }

    /** Whether a request name carries timing parts over time. */
    public boolean hasPartsFor(String name) {
        return partsBySecond.containsKey(name);
    }

    /**
     * A request's mean timing part, in ms, over the requests that ended in the second starting at {@code second} (ms
     * since the epoch, a whole second), or null when none did. {@code part} is an index into {@link #TIMING_PARTS}.
     */
    public Double meanPart(String name, int part, long second) {
        Map<Long, long[]> seconds = partsBySecond.get(name);
        long[] in = seconds == null ? null : seconds.get(second);
        if (in == null || in[2 * part + 1] == 0) {
            return null;
        }
        return in[2 * part] / (double) in[2 * part + 1] / 100.0;
    }

    /** Each group's wall time over its passes, by group name, in the order first seen. */
    public Map<String, Trend> groupDurations() {
        return Collections.unmodifiableMap(groupDurations);
    }

    /** Each request name's timing parts, in the order first seen and {@link #TIMING_PARTS}' order. */
    public Map<String, Map<String, Trend>> timingsByRequest() {
        Map<String, Map<String, Trend>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Trend>> e : timingsByRequest.entrySet()) {
            Map<String, Trend> ordered = new LinkedHashMap<>();
            for (String part : TIMING_PARTS) {
                Trend t = e.getValue().get(part);
                if (t != null) {
                    ordered.put(part, t);
                }
            }
            out.put(e.getKey(), ordered);
        }
        return out;
    }

    /** Each timing part over every request, as k6's summary shows {@code http_req_blocked} and the rest. */
    public Map<String, Trend> timingsOverAll() {
        Map<String, Trend> all = new LinkedHashMap<>();
        for (String part : TIMING_PARTS) {
            for (Map<String, Trend> parts : timingsByRequest.values()) {
                Trend t = parts.get(part);
                if (t != null) {
                    all.computeIfAbsent(part, k -> new Trend()).merge(t);
                }
            }
        }
        return all;
    }

    /**
     * Each request name's duration in k6's sense, sending + waiting + receiving (the time a request took without
     * waiting for its connection), in the order first seen. Kept apart from the six parts: it is their sum, not a part.
     */
    public Map<String, Trend> durationsByRequest() {
        return Collections.unmodifiableMap(durationsByRequest);
    }

    /** The duration over every request, as k6's summary shows {@code http_req_duration}. */
    public Trend durationOverAll() {
        Trend all = new Trend();
        for (Trend t : durationsByRequest.values()) {
            all.merge(t);
        }
        return all;
    }

    /** A counter from the tool's summary ({@code iterations}, {@code dropped_iterations}, {@code vus_max}), or null. */
    public Double counter(String name) {
        return records.counters().get(name);
    }

    public boolean isEmpty() {
        return records.isEmpty() && groupDurations.isEmpty() && timingsByRequest.isEmpty();
    }
}
