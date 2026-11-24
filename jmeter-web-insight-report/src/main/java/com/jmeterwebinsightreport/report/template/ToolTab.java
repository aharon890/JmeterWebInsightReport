package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.model.ToolMeasures;
import com.jmeterwebinsightreport.core.rundata.ToolRecords;
import com.jmeterwebinsightreport.core.util.FormatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The tool's own tab as the template draws it (spec 001, decision 10, FR-008): named after the tool, after Server, on a
 * run another tool recorded. Every figure is a {@link ToolMeasures} figure, written here once: times in ms to two
 * decimals ({@code #0.00}, as the Samplers table writes KB/s), counts as whole numbers, k6's own metric names. Never a
 * verdict: thresholds and checks are shown as the tool judged them, and the report's verdict is the test's asserts.
 */
public final class ToolTab {

    /** k6's summary columns, in its order. */
    public static final List<String> TREND_COLUMNS = Collections.unmodifiableList(
            Arrays.asList("avg", "min", "med", "max", "p(90)", "p(95)"));

    public static final class ThresholdRow {
        public final String metric;
        public final String expression;
        public final boolean passed;

        ThresholdRow(ToolRecords.Threshold t) {
            this.metric = t.metric;
            this.expression = t.expression;
            this.passed = t.passed;
        }

        public String getResult() {
            return passed ? "passed" : "failed";
        }
    }

    public static final class CheckRow {
        /** k6's group path, {@code ::A::B}; empty outside any group. */
        public final String group;
        public final String groupText;
        public final String name;
        public final long passes;
        public final long fails;
        public final String passRate;

        CheckRow(ToolRecords.CheckCount c) {
            this.group = c.group;
            this.groupText = groupText(c.group);
            this.name = c.name;
            this.passes = c.passes;
            this.fails = c.fails;
            long all = c.passes + c.fails;
            this.passRate = all == 0 ? "" : FormatUtil.formatTwoDecimals(100.0 * c.passes / all) + "%";
        }
    }

    /** One distribution: its name (a group, or a request and a timing part) and k6's summary columns. */
    public static final class TrendRow {
        public final String name;
        /** For a timing part: the part's key ({@code blocked}) and k6's metric name ({@code http_req_blocked}). */
        public final String part;
        public final String partText;
        public final long count;
        public final List<String> cells;
        /** A timing part over every request, not one request's. */
        public final boolean everyRequest;

        TrendRow(String name, String part, ToolMeasures.Trend t) {
            this.name = name;
            this.everyRequest = EVERY_REQUEST.equals(name) && part != null;
            this.part = part;
            this.partText = part == null ? null : k6Metric(part);
            this.count = t.count();
            this.cells = Collections.unmodifiableList(Arrays.asList(two(t.avg()), two(t.min()), two(t.med()),
                    two(t.max()), two(t.p90()), two(t.p95())));
        }
    }

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

    /** The name the timing rows use for "every request". */
    public static final String EVERY_REQUEST = "*";

    public final String tool;
    /**
     * k6's tab shows every section k6's summary has, saying "none" or "not measured" where the run has nothing. Another
     * tool's shows only what its run carries, and its counters under their own names (US4).
     */
    public final boolean k6;
    public final List<ThresholdRow> thresholds = new ArrayList<>();
    public final List<CheckRow> checks = new ArrayList<>();
    public final List<TrendRow> groupDurations = new ArrayList<>();
    public final List<TrendRow> timings = new ArrayList<>();
    public final List<Counter> counters = new ArrayList<>();
    public final long thresholdsFailed;
    /** k6's summary columns, for the template's column heads. */
    public final List<String> columns = TREND_COLUMNS;
    public final String title;
    public final String note;
    public final String thresholdsTitle;
    public final String noThresholds;

    private ToolTab(String tool, ToolMeasures m, String notMeasured) {
        this.tool = tool;
        this.k6 = "k6".equals(tool);
        for (ToolRecords.Threshold t : m.records().thresholds()) {
            thresholds.add(new ThresholdRow(t));
        }
        // The failed ones first, then by metric, as k6's own summary groups them: a run can declare dozens (perf
        // hub's petstore-checkout declares 50), and the one that failed must not be hunted for
        thresholds.sort(java.util.Comparator.comparing((ThresholdRow t) -> t.passed)
                .thenComparing(t -> String.valueOf(t.metric)).thenComparing(t -> String.valueOf(t.expression)));
        thresholdsFailed = thresholds.stream().filter(t -> !t.passed).count();
        title = tool + "'s own measures";
        // decision 4: shown as the tool judged them, and never the report's verdict
        note = tool + "'s thresholds and checks as " + tool + " judged them. They do not decide this report's verdict:"
                + " the test's asserts do (the Asserts tab), and " + tool + "'s exit code is its own. Times are in ms,"
                + " to the hundredth. A percentile here is the Aggregate Report's, a time some request really took,"
                + " where " + tool + "'s own summary interpolates between two.";
        thresholdsTitle = "Thresholds (" + (thresholds.size() - thresholdsFailed) + " of " + thresholds.size()
                + " passed)";
        noThresholds = "No thresholds: " + tool + "'s summary declared none, or it was not given.";
        for (ToolRecords.CheckCount c : m.records().checks()) {
            checks.add(new CheckRow(c));
        }
        for (Map.Entry<String, ToolMeasures.Trend> g : m.groupDurations().entrySet()) {
            groupDurations.add(new TrendRow(g.getKey(), null, g.getValue()));
        }
        for (Map.Entry<String, ToolMeasures.Trend> p : m.timingsOverAll().entrySet()) {
            timings.add(new TrendRow(EVERY_REQUEST, p.getKey(), p.getValue()));
        }
        for (Map.Entry<String, Map<String, ToolMeasures.Trend>> r : m.timingsByRequest().entrySet()) {
            for (Map.Entry<String, ToolMeasures.Trend> p : r.getValue().entrySet()) {
                timings.add(new TrendRow(r.getKey(), p.getKey(), p.getValue()));
            }
        }
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

    /** k6's group path as a reader reads it: {@code ::A::B} is "A › B", and no group is empty. */
    static String groupText(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String p = path.startsWith("::") ? path.substring(2) : path;
        return p.replace("::", " › ");
    }

    /** k6's own metric name for a timing part: {@code tlsHandshaking} is {@code http_req_tls_handshaking}. */
    static String k6Metric(String part) {
        StringBuilder b = new StringBuilder("http_req_");
        for (char c : part.toCharArray()) {
            if (Character.isUpperCase(c)) {
                b.append('_').append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static Counter counter(ToolMeasures m, String key, String label, String notMeasured) {
        Double v = m.counter(key);
        return new Counter(key, label, v == null ? notMeasured : FormatUtil.formatNumber(v));
    }

    private static String two(double v) {
        return FormatUtil.formatTwoDecimals(v);
    }
}
