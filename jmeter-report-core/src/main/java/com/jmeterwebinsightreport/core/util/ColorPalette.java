package com.jmeterwebinsightreport.core.util;

/**
 * Color palette constants for report charts and status indicators.
 */
public final class ColorPalette {

    private ColorPalette() {
        // utility class
    }

    // Status colors
    public static final String SUCCESS = "#22c55e";
    public static final String WARNING = "#f59e0b";
    public static final String ERROR = "#ef4444";
    public static final String INFO = "#3b82f6";

    // Chart series colors (ECharts-friendly)
    public static final String[] CHART_SERIES = {
            "#5470c6", "#91cc75", "#fac858", "#ee6666", "#73c0de",
            "#3ba272", "#fc8452", "#9a60b4", "#ea7ccc", "#48b8d0"
    };

    /**
     * Get a chart color by index, cycling through the palette.
     */
    public static String getChartColor(int index) {
        return CHART_SERIES[index % CHART_SERIES.length];
    }

    /**
     * Get the status color for an assert status.
     */
    public static String getStatusColor(String status) {
        switch (status.toUpperCase()) {
            case "PASS":
                return SUCCESS;
            case "WARN":
                return WARNING;
            case "FAIL":
                return ERROR;
            default:
                return INFO;
        }
    }
}
