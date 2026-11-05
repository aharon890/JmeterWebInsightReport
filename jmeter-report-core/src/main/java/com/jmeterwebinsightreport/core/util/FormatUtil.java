package com.jmeterwebinsightreport.core.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formatting utilities for report rendering — durations, numbers, timestamps.
 */
public final class FormatUtil {

    // Numbers are written as JMeter writes them, whatever the JVM's locale: '.' for decimals and no
    // thousands separator (JMeter's Aggregate Report and HTML report never group, 1200 not 1,200).
    // The report's JavaScript parses these values assuming this format; a locale-dependent format
    // (e.g. "1.234,56") would corrupt client-side table sorting.
    private static final DecimalFormatSymbols US_SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);
    // ThreadLocal because DecimalFormat is explicitly documented as not thread-safe. Only
    // the single report-generation thread uses these today, but this is a public utility in a
    // module whose engine is advertised as thread-safe — a latent trap, and the fix is free.
    private static final ThreadLocal<DecimalFormat> DECIMAL_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#0.##", US_SYMBOLS));
    private static final ThreadLocal<DecimalFormat> PERCENTAGE_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#0.00", US_SYMBOLS));
    // The Aggregate Report's own column patterns (StatGraphVisualizer.getRenderers): Throughput
    // RateRenderer("#.0"), Received/Sent KB/sec NumberRenderer("#0.00"); and the HTML report's Apdex,
    // toFixed(3) (dashboard.js.fmkr)
    private static final ThreadLocal<DecimalFormat> RATE_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#.0", US_SYMBOLS));
    private static final ThreadLocal<DecimalFormat> TWO_DECIMALS_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#0.00", US_SYMBOLS));
    private static final ThreadLocal<DecimalFormat> APDEX_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#0.000", US_SYMBOLS));
    // Includes the zone (e.g. "2026-07-17 13:00:00 IDT") so the generation time is unambiguous —
    // chart axes render in the viewer's local zone, which can differ from the generation machine's.
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private FormatUtil() {
        // utility class
    }

    /**
     * Format a duration in milliseconds to a human-readable string (e.g., "2m 30s", "150ms"), in
     * Latin digits whatever the JVM's locale (an Arabic or Persian JVM wrote "٤٨.٤s").
     */
    public static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %ds", minutes, seconds);
        } else {
            return String.format(Locale.ROOT, "%d.%ds", seconds, duration.toMillisPart() / 100);
        }
    }

    /**
     * Format a decimal number as JMeter writes one: no thousands separator, up to two decimals
     * (e.g., 1234.56, 1200).
     */
    public static String formatNumber(double value) {
        return DECIMAL_FORMAT.get().format(value);
    }

    /**
     * A throughput as the Aggregate Report's Throughput column writes it (JMeter's RateRenderer):
     * one decimal, per second, and per minute or per hour when fewer than one a second or a minute
     * (34.2/s, 20.7/min, 43.0/hour). The unit is written "/s" where JMeter writes "/sec".
     */
    public static String formatRate(double perSecond) {
        if (Double.isNaN(perSecond) || Double.isInfinite(perSecond) || perSecond == Double.MAX_VALUE) {
            return "#N/A";
        }
        double rate = perSecond;
        String unit = "s";
        if (rate < 1.0) {
            rate *= 60.0;
            unit = "min";
        }
        if (rate < 1.0) {
            rate *= 60.0;
            unit = "hour";
        }
        return RATE_FORMAT.get().format(rate) + "/" + unit;
    }

    /**
     * Two decimals, as the Aggregate Report writes its Received and Sent KB/sec columns (0.60, 19.82).
     */
    public static String formatTwoDecimals(double value) {
        return TWO_DECIMALS_FORMAT.get().format(value);
    }

    /**
     * An Apdex score as JMeter's HTML report writes it: three decimals (0.948, 1.000).
     */
    public static String formatApdex(double score) {
        return APDEX_FORMAT.get().format(score);
    }

    /**
     * Format a response time in milliseconds for display (e.g., 1437).
     *
     * <p>JMeter records sample times as whole milliseconds, and its Aggregate/Summary reports
     * print them as plain integers. Our aggregates are doubles — means, and percentiles that
     * T-Digest interpolates between samples — so they carry fractional digits that are below
     * JMeter's 1 ms measurement granularity and read as false precision. Round to whole
     * milliseconds and omit grouping to match JMeter's own output.
     */
    public static String formatMillis(double millis) {
        if (Double.isNaN(millis) || Double.isInfinite(millis)) {
            return "0";
        }
        return Long.toString(Math.round(millis));
    }

    /**
     * Format a percentage value (e.g., 95.50).
     */
    public static String formatPercentage(double value) {
        return PERCENTAGE_FORMAT.get().format(value) + "%";
    }

    /**
     * Format an Instant to a readable timestamp string.
     */
    public static String formatTimestamp(Instant instant) {
        return TIMESTAMP_FORMAT.format(instant);
    }

    /**
     * Format bytes to human-readable size (KB, MB, GB).
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return DECIMAL_FORMAT.get().format(bytes / 1024.0) + " KB";
        } else if (bytes < 1024 * 1024 * 1024) {
            return DECIMAL_FORMAT.get().format(bytes / (1024.0 * 1024.0)) + " MB";
        } else {
            return DECIMAL_FORMAT.get().format(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB";
        }
    }
}
