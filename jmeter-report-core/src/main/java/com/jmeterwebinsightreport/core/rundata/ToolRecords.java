package com.jmeterwebinsightreport.core.rundata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool's own measures, as run data carries them (data-model.md "A tool's own measures"): k6's checks, thresholds and
 * counters. They are shown in the tool's tab and never decide a verdict (constitution VII).
 *
 * <p>Checks arrive one record per check per pass, so they are kept as counts per check, not as records.
 */
public final class ToolRecords {

    /** One {@code check} record. */
    public static final class Check {
        public long time;
        public String name;
        /** k6's group path, {@code ::A::B}, or empty outside any group. */
        public String group;
        public boolean passed;
        public String user;
        public Integer iteration;
    }

    /** One {@code threshold} record. */
    public static final class Threshold {
        /** The metric with its tag filter: {@code http_req_duration{name:Login}}. */
        public String metric;
        public String expression;
        public boolean passed;
        /**
         * Since format 1.1 (spec 002, FR-050), each optional: the statistic the expression names ({@code p(95)},
         * {@code rate}), the value the tool judged it on (k6's summary {@code values}), and the metric's type
         * ({@code trend}, {@code rate}, {@code counter}, {@code gauge}) and what it holds ({@code time}, {@code data},
         * {@code default}). Absent when the tool did not say: never 0.
         */
        public String statistic;
        public Double measuredValue;
        public String metricType;
        public String metricContains;

        public Threshold() {
        }

        public Threshold(String metric, String expression, boolean passed) {
            this.metric = metric;
            this.expression = expression;
            this.passed = passed;
        }
    }

    /** Passes and fails of one check, by its group path and name, in the order first seen. */
    public static final class CheckCount {
        public final String group;
        public final String name;
        public long passes;
        public long fails;

        CheckCount(String group, String name) {
            this.group = group;
            this.name = name;
        }
    }

    private final Map<String, CheckCount> checks = new LinkedHashMap<>();
    private final List<Threshold> thresholds = new ArrayList<>();
    private final Map<String, Double> counters = new LinkedHashMap<>();

    public void addCheck(Check c) {
        String group = c.group == null ? "" : c.group;
        CheckCount count = checks.computeIfAbsent(group + "\u0000" + c.name, k -> new CheckCount(group, c.name));
        if (c.passed) {
            count.passes++;
        } else {
            count.fails++;
        }
    }

    public void addThreshold(Threshold t) {
        thresholds.add(t);
    }

    public void addCounter(String name, double value) {
        counters.put(name, value);
    }

    public List<CheckCount> checks() {
        return new ArrayList<>(checks.values());
    }

    public List<Threshold> thresholds() {
        return List.copyOf(thresholds);
    }

    public Map<String, Double> counters() {
        return new LinkedHashMap<>(counters);
    }

    public boolean isEmpty() {
        return checks.isEmpty() && thresholds.isEmpty() && counters.isEmpty();
    }
}
