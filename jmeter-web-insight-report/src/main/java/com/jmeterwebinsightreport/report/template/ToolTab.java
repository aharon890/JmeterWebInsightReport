package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.model.ToolMeasures;
import com.jmeterwebinsightreport.core.rundata.ThresholdExpression;
import com.jmeterwebinsightreport.core.rundata.ToolRecords;
import com.jmeterwebinsightreport.core.util.FormatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tool's own tab as the template draws it (spec 001, decision 10, FR-008): named after the tool, after Server, on a
 * run another tool recorded. Every figure is a {@link ToolMeasures} figure, written here once: times in ms to two
 * decimals ({@code #0.00}, as the Samplers table writes KB/s), counts as whole numbers, k6's own metric names. Never a
 * verdict: thresholds and checks are shown as the tool judged them, and the report's verdict is the test's asserts.
 */
public final class ToolTab {

    public static final class Counter {
        public final String key;
        public final String label;
        public final String value;

        Counter(String key, String label, String value) {
            this.key = key;
            this.label = label;
            this.value = value;
        }
    }

    public final String tool;
    /**
     * k6's tab shows every section k6's summary has, saying "none" where the run has nothing. Another tool's shows
     * only what its run carries, and its counters under their own names (US4).
     */
    public final boolean k6;
    /** The tiles at the top of the tab; the thresholds, timings, checks and group durations are {@link #block}'s. */
    public final List<Counter> counters = new ArrayList<>();
    public final String title;
    public final String note;

    private ToolTab(String tool, ToolMeasures m, String notMeasured) {
        this.tool = tool;
        this.k6 = "k6".equals(tool);
        title = tool + "'s own measures";
        // decision 4: shown as the tool judged them, and never the report's verdict
        note = tool + "'s thresholds and checks as " + tool + " judged them. They do not decide this report's verdict:"
                + " the test's asserts do (the Asserts tab), and " + tool + "'s exit code is its own. Times are in ms,"
                + " to the hundredth. A percentile here is the Aggregate Report's, a time some request really took,"
                + " where " + tool + "'s own summary interpolates between two; a threshold's measured value is "
                + tool + "'s own.";
        if (k6) {
            counters.add(counter(m, "iterations", "Iterations", notMeasured));
            counters.add(counter(m, "dropped_iterations", "Dropped iterations", notMeasured));
            counters.add(counter(m, "vus_max", "VUs allocated (vus_max)", notMeasured));
        } else {
            for (Map.Entry<String, Double> c : m.records().counters().entrySet()) {
                counters.add(new Counter(c.getKey(), c.getKey(), FormatUtil.formatNumber(c.getValue())));
            }
        }
    }

    /** The tab of a run another tool recorded, or null when it has nothing of its own to show. */
    public static ToolTab of(String tool, ToolMeasures measures, Map<String, String> words) {
        if (tool == null || measures == null || measures.isEmpty()) {
            return null;
        }
        return new ToolTab(tool, measures, words.get("notMeasured"));
    }

    /** The statistics the tab's switch can offer, in k6's order; p(99) only when the run's summary lists it. */
    static final List<String> STATISTICS = List.of("avg", "min", "med", "max", "p(90)", "p(95)", "p(99)");

    /**
     * The tab's data for report.js, {@code chartData.tool} (spec 002, data-model.md): the thresholds with what k6
     * measured, the timing parts per request with k6's request duration, the checks, the group durations and the
     * counters. Each figure is written once here; the page groups, filters, sorts and formats them. A threshold's
     * expression is read once, here, by k6's own grammar ({@link ThresholdExpression}).
     */
    public static Map<String, Object> block(String tool, String version, ToolMeasures m) {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("name", tool);
        if (version != null) {
            b.put("version", version);
        }
        b.put("k6", "k6".equals(tool));
        List<String> listed = m.records().trendStatistics();
        List<String> offered = new ArrayList<>();
        for (String s : STATISTICS) {
            // with no list (no summary, or run data 1.0), k6's default columns: avg to p(95)
            if (listed == null ? !"p(99)".equals(s) : listed.contains(s)) {
                offered.add(s);
            }
        }
        b.put("trendStatistics", offered);

        List<Map<String, Object>> thresholds = new ArrayList<>();
        for (ToolRecords.Threshold t : m.records().thresholds()) {
            thresholds.add(threshold(t));
        }
        b.put("thresholds", thresholds);

        List<Map<String, Object>> timings = new ArrayList<>();
        Map<String, ToolMeasures.Trend> durations = m.durationsByRequest();
        for (Map.Entry<String, Map<String, ToolMeasures.Trend>> r : m.timingsByRequest().entrySet()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("request", r.getKey());
            row.put("parts", parts(r.getValue(), durations.get(r.getKey())));
            timings.add(row);
        }
        b.put("timings", timings);
        if (!timings.isEmpty()) {
            Map<String, Object> every = new java.util.LinkedHashMap<>();
            every.put("parts", parts(m.timingsOverAll(), m.durationOverAll()));
            b.put("timingsEveryRequest", every);
        }

        List<Map<String, Object>> checks = new ArrayList<>();
        for (ToolRecords.CheckCount c : m.records().checks()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("group", c.group);
            row.put("name", c.name);
            row.put("passes", c.passes);
            row.put("fails", c.fails);
            checks.add(row);
        }
        b.put("checks", checks);

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, ToolMeasures.Trend> g : m.groupDurations().entrySet()) {
            Map<String, Object> row = statistics(g.getValue());
            row.put("name", g.getKey());
            groups.add(row);
        }
        b.put("groupDurations", groups);
        b.put("counters", m.records().counters());
        return b;
    }

    private static Map<String, Object> threshold(ToolRecords.Threshold t) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        String metric = t.metric == null ? "" : t.metric;
        int brace = metric.indexOf('{');
        String tags = brace >= 0 && metric.endsWith("}") ? metric.substring(brace + 1, metric.length() - 1) : "";
        ThresholdExpression e = ThresholdExpression.parse(t.expression);
        row.put("metric", metric);
        row.put("base", brace >= 0 ? metric.substring(0, brace) : metric);
        row.put("tags", tags);
        row.put("request", tagValue(tags, "name"));
        row.put("expression", t.expression);
        row.put("statistic", t.statistic != null ? t.statistic : e.statistic());
        row.put("operator", e.operator());
        row.put("limit", e.limit());
        row.put("measured", t.measuredValue);
        row.put("metricType", t.metricType);
        row.put("metricContains", t.metricContains);
        row.put("passed", t.passed);
        row.put("alwaysTrue", e.alwaysTrue());
        return row;
    }

    /**
     * A tag's value in k6's tag filter, {@code name:Login,group:::Checkout}: each tag is a key, a colon and the rest up
     * to the next comma, so a group path's own colons stay in its value. Null when the filter does not name it.
     */
    static String tagValue(String tags, String key) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        for (String tag : tags.split(",")) {
            int colon = tag.indexOf(':');
            if (colon > 0 && tag.substring(0, colon).trim().equals(key)) {
                return tag.substring(colon + 1);
            }
        }
        return null;
    }

    private static Map<String, Object> parts(Map<String, ToolMeasures.Trend> six, ToolMeasures.Trend duration) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String part : ToolMeasures.TIMING_PARTS) {
            ToolMeasures.Trend t = six.get(part);
            if (t != null) {
                out.put(part, statistics(t));
            }
        }
        if (duration != null && duration.count() > 0) {
            out.put("duration", statistics(duration));
        }
        return out;
    }

    /** One trend in k6's statistic names, in ms; every percentile the Aggregate Report's rank. */
    private static Map<String, Object> statistics(ToolMeasures.Trend t) {
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("count", t.count());
        s.put("avg", t.avg());
        s.put("min", t.min());
        s.put("med", t.med());
        s.put("max", t.max());
        s.put("p(90)", t.p90());
        s.put("p(95)", t.p95());
        s.put("p(99)", t.p99());
        return s;
    }

    private static Counter counter(ToolMeasures m, String key, String label, String notMeasured) {
        Double v = m.counter(key);
        return new Counter(key, label, v == null ? notMeasured : FormatUtil.formatNumber(v));
    }
}
