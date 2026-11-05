package com.jmeterwebinsightreport.core.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class FormatUtilTest {

    @Test
    void numberFormatIsLocaleIndependent() {
        // B13: rendered numbers must use '.' decimals regardless of JVM default locale, because the
        // report's JavaScript parses them assuming that format. And no thousands separator, as JMeter
        // writes none (its Aggregate Report and HTML report: 1200, not 1,200).
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY); // default would otherwise produce "1.234,56"
            assertEquals("1234.56", FormatUtil.formatNumber(1234.56));
            assertEquals("1200", FormatUtil.formatNumber(1200));
            assertEquals("95.50%", FormatUtil.formatPercentage(95.5));
            assertEquals("24.8/s", FormatUtil.formatRate(24.76065));
            assertEquals("0.60", FormatUtil.formatTwoDecimals(0.6));
            assertEquals("0.948", FormatUtil.formatApdex(0.948));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rateIsWrittenAsTheAggregateReportsThroughputColumn() {
        // JMeter's RateRenderer("#.0"): one decimal, per second, per minute below one a second,
        // per hour below one a minute; "/s" where JMeter writes "/sec"
        assertEquals("24.8/s", FormatUtil.formatRate(24.76065));
        assertEquals("8.3/s", FormatUtil.formatRate(8.28));
        assertEquals("1.0/s", FormatUtil.formatRate(1.0));
        assertEquals("20.7/min", FormatUtil.formatRate(0.345));
        assertEquals("43.0/hour", FormatUtil.formatRate(43.0 / 3600));
        assertEquals("1234.5/s", FormatUtil.formatRate(1234.5));
        // DecimalFormat's half-even on an exact tie, as JMeter's table rounds it
        assertEquals("8.2/s", FormatUtil.formatRate(8.25));
        // "#.0" writes no leading zero, as JMeter does below one an hour
        assertEquals(".5/hour", FormatUtil.formatRate(0.5 / 3600));
        assertEquals("#N/A", FormatUtil.formatRate(Double.NaN));
    }

    @Test
    void kilobytesAndApdexKeepTheirDecimals() {
        // The Aggregate Report's KB/sec ("#0.00") and the HTML report's Apdex (toFixed(3))
        assertEquals("0.60", FormatUtil.formatTwoDecimals(0.6));
        assertEquals("19.82", FormatUtil.formatTwoDecimals(19.8243));
        assertEquals("1234.00", FormatUtil.formatTwoDecimals(1234));
        assertEquals("1.000", FormatUtil.formatApdex(1.0));
        assertEquals("0.948", FormatUtil.formatApdex(0.948));
        assertEquals("0.500", FormatUtil.formatApdex(0.5));
    }

    @Test
    void durationIsWrittenInLatinDigitsWhateverTheJvmLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG")); // String.format wrote "٤٨.٤s" here
            assertEquals("48.4s", FormatUtil.formatDuration(48_400));
            assertEquals("2m 30s", FormatUtil.formatDuration(150_000));
            assertEquals("1h 2m 3s", FormatUtil.formatDuration(3_723_000));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void shouldFormatMilliseconds() {
        assertEquals("500ms", FormatUtil.formatDuration(500));
    }

    @Test
    void millisRoundsToWholeMillisecondsWithoutGrouping() {
        // JMeter measures in whole ms and prints plain integers; T-Digest percentiles and means
        // are fractional, so they are rounded to nearest rather than rendered as "1,437.59".
        assertEquals("1438", FormatUtil.formatMillis(1437.59));
        assertEquals("931", FormatUtil.formatMillis(931.4));
        assertEquals("932", FormatUtil.formatMillis(931.5));
        assertEquals("0", FormatUtil.formatMillis(0.4));
        assertEquals("158", FormatUtil.formatMillis(158));
        // No thousands separator, unlike formatNumber
        assertEquals("12345", FormatUtil.formatMillis(12345.0));
    }

    @Test
    void millisIsLocaleIndependentAndHandlesNonFinite() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1438", FormatUtil.formatMillis(1437.59));
        } finally {
            Locale.setDefault(original);
        }
        assertEquals("0", FormatUtil.formatMillis(Double.NaN));
        assertEquals("0", FormatUtil.formatMillis(Double.POSITIVE_INFINITY));
    }

    @Test
    void shouldFormatSeconds() {
        String result = FormatUtil.formatDuration(2500);
        assertTrue(result.contains("2"));
    }

    @Test
    void shouldFormatPercentage() {
        assertEquals("95.50%", FormatUtil.formatPercentage(95.5));
    }

    @Test
    void shouldFormatBytes() {
        assertEquals("0 B", FormatUtil.formatBytes(0));
        assertTrue(FormatUtil.formatBytes(1024).contains("KB"));
        assertTrue(FormatUtil.formatBytes(1024 * 1024).contains("MB"));
    }

    @Test
    void shouldFormatDuration_minutes() {
        // 150000ms = 2m 30s
        assertEquals("2m 30s", FormatUtil.formatDuration(150000));
    }

    @Test
    void shouldFormatDuration_hours() {
        // 3661000ms = 1h 1m 1s
        assertEquals("1h 1m 1s", FormatUtil.formatDuration(3661000));
    }

    @Test
    void shouldFormatDuration_zero() {
        assertEquals("0ms", FormatUtil.formatDuration(0));
    }

    @Test
    void shouldFormatBytes_gigabytes() {
        // 1 GB = 1073741824 bytes
        assertTrue(FormatUtil.formatBytes(1073741824L).contains("GB"));
    }
}
