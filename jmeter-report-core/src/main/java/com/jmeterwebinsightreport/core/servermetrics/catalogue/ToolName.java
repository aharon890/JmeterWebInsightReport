package com.jmeterwebinsightreport.core.servermetrics.catalogue;

/**
 * What one tool calls a catalogue metric, and what kind of number it hands over — one entry of a
 * metric's {@code names} block:
 *
 * <pre>
 * "names": {
 *   "datadog":  { "metric": "postgresql.percent_usage_connections", "kind": "gauge", "scale": 100 },
 *   "telegraf": null,
 *   "cloudwatch": { "metric": "AWS/RDS:ReplicaLag", "kind": "gauge",
 *                   "note": "RDS read replica; statistic Maximum, per DBInstanceIdentifier" }
 * }
 * </pre>
 *
 * <p>A user exports server metrics from whatever tool they have into a file, and each tool names a
 * KPI its own way and delivers its own kind of number: Telegraf and OpenTelemetry a raw cumulative
 * counter, Datadog a rate per second or a raw total, CloudWatch a sum per period, a fraction where
 * the catalogue says %. {@link #metric()} is the name to look for in that tool's export, written
 * as that tool writes it ({@link Catalogue}'s conventions), {@link #kind()} how its number becomes
 * the catalogue's, {@link #scale()} the factor after that, and {@link #note()}, when the name alone
 * is not enough, which of the tool's series to export. Which tools may be named is
 * {@link Catalogue#TOOLS}.
 *
 * <p>An explicit {@code null} entry is not a {@code ToolName}: it says the tool has no
 * single-metric equivalent of the KPI, which {@link CatalogueMetric#declaresNone} answers.
 */
public final class ToolName {

    /** How a tool's number becomes the catalogue's, before {@link #scale()}. */
    public enum Kind {
        /** A level, used as it is. */
        GAUGE("gauge"),
        /** A raw cumulative counter: turned into a rate per second. */
        COUNTER("counter"),
        /** Already a rate per second. */
        RATE("rate"),
        /** A sum over the export's period: divided by the period. */
        SUM_PER_PERIOD("sum-per-period");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        /** As written in a catalogue file: {@code sum-per-period}. */
        public String key() { return key; }

        /** Null for anything that is not one of {@link #vocabulary()}, exactly as written. */
        public static Kind fromKey(String key) {
            if (key == null) return null;
            for (Kind k : values()) {
                if (k.key.equals(key)) return k;
            }
            return null;
        }

        /** "gauge, counter, rate, sum-per-period" — for a refusal that says what would have been accepted. */
        public static String vocabulary() {
            StringBuilder sb = new StringBuilder();
            for (Kind k : values()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(k.key);
            }
            return sb.toString();
        }
    }

    /** The most characters, counted by code point, a {@link #note()} may have: a line, not a paragraph. */
    public static final int NOTE_MAX = 160;

    private final String tool;
    private final String metric;
    private final Kind kind;
    private final double scale;
    private final String note;

    ToolName(String tool, String metric, Kind kind, double scale, String note) {
        this.tool = tool;
        this.metric = metric;
        this.kind = kind;
        this.scale = scale;
        this.note = note;
    }

    /** One of {@link Catalogue#TOOLS}. */
    public String tool() { return tool; }

    /** The metric's name in that tool's export, exactly as written: never padded. */
    public String metric() { return metric; }

    public Kind kind() { return kind; }

    /**
     * What the number is multiplied by once {@link #kind()} has made it the catalogue's kind of
     * number: 100 for a fraction the catalogue reads as %, 0.001 for milliseconds it reads as
     * seconds. A finite number above 0; 1 when the file writes none.
     */
    public double scale() { return scale; }

    /**
     * Which of the tool's series to export when {@link #metric()} alone is not enough, or null —
     * a tag filter ({@code tag cpu=cpu-total}), a CloudWatch statistic and dimension, an input or
     * metric the tool has off by default. Free text for the tester, one line of at most
     * {@link #NOTE_MAX} characters, never padded; nothing reads it but a person.
     */
    public String note() { return note; }

    @Override
    public String toString() {
        return tool + ":" + metric + " (" + kind.key() + (scale == 1 ? "" : " x" + scale) + ")";
    }
}
