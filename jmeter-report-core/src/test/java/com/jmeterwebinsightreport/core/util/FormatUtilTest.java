package com.jmeterwebinsightreport.core.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class FormatUtilTest {

    @Test
    void numberFormatIsLocaleIndependent() {
        // B13: rendered numbers must use '.' decimal / ',' grouping regardless of JVM default
        // locale, because the report's JavaScript parses them assuming that format.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY); // default would otherwise produce "1.234,56"
            assertEquals("1,234.56", FormatUtil.formatNumber(1234.56));
            assertEquals("95.50%", FormatUtil.formatPercentage(95.5));
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
