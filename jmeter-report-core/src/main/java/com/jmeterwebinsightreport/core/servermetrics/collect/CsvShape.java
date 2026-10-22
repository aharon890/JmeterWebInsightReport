package com.jmeterwebinsightreport.core.servermetrics.collect;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a CSV's own rows say about how to read it, for the two flags a user would otherwise have to
 * know to pass (decision 12, "measure, don't ask"): its layout, and the interval it was exported
 * at. Each is a default only - an explicit {@code --layout} or {@code --step} always wins - and the
 * collector prints whichever it used, so a measured value is never silent.
 *
 * <p>Conservative on purpose. The wide layout is reported only for a header Grafana's "Series
 * joined by time" download writes: a {@code Time} column first, no {@code component} or
 * {@code metric} column, and every other column named {@code component/metric[/instance]}. The step
 * is the most frequent gap between consecutive rows of one series (rows with a blank value count:
 * they are the export's interval too), from the first {@value #MAX_ROWS} rows, and nothing when
 * fewer than three gaps were seen or no time column was found. A file it cannot read is reported as
 * nothing measured; reading it properly, and refusing it in a sentence, is the collector's job.
 */
public final class CsvShape {

    static final int MAX_ROWS = 200_000;

    private final boolean wide;
    private final long stepMillis;

    private CsvShape(boolean wide, long stepMillis) {
        this.wide = wide;
        this.stepMillis = stepMillis;
    }

    /** True when the header is unmistakably Grafana's wide layout. */
    public boolean wide() { return wide; }

    /** The measured interval, or 0 when none could be measured. */
    public long stepMillis() { return stepMillis; }

    /**
     * @param file       the CSV
     * @param timeColumn the time column a {@code --csv-columns} map names, or null for the usual names
     */
    public static CsvShape sniff(File file, String timeColumn) {
        List<String[]> rows = new ArrayList<>();
        String[] header = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(file.toPath()),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null && rows.size() < MAX_ROWS) {
                if (line.startsWith("﻿")) {
                    line = line.substring(1);
                }
                if (line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = cells(line);
                if (header == null || java.util.Arrays.equals(cells, header)) {
                    header = cells;   // a repeated header (an InfluxDB block) starts a block again
                    continue;
                }
                rows.add(cells);
            }
        } catch (IOException | RuntimeException unreadable) {
            return new CsvShape(false, 0);
        }
        if (header == null) {
            return new CsvShape(false, 0);
        }
        boolean wide = looksWide(header);
        int time = timeIndex(header, timeColumn);
        if (time < 0) {
            return new CsvShape(wide, 0);
        }
        int value = wide ? -1 : valueIndex(header);
        Map<String, Long> last = new HashMap<>();
        Map<Long, Integer> gaps = new TreeMap<>();
        for (String[] row : rows) {
            if (row.length <= time) {
                continue;
            }
            Long t = millis(row[time]);
            if (t == null) {
                continue;
            }
            String key = wide ? "" : seriesKey(row, time, value);
            Long before = last.put(key, t);
            if (before != null && t > before) {
                gaps.merge(t - before, 1, Integer::sum);
            }
        }
        long best = 0;
        int most = 0;
        int total = 0;
        for (Map.Entry<Long, Integer> e : gaps.entrySet()) {   // ascending, so a tie keeps the smaller
            total += e.getValue();
            if (e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return new CsvShape(wide, total >= 3 ? best : 0);
    }

    static boolean looksWide(String[] header) {
        if (header.length < 2 || !"time".equalsIgnoreCase(header[0].trim())) {
            return false;
        }
        for (int i = 1; i < header.length; i++) {
            String h = header[i].trim().toLowerCase(Locale.ROOT);
            if (h.equals("component") || h.equals("metric") || h.equals("value")) {
                return false;
            }
            String[] parts = header[i].trim().split("/", -1);
            if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static int timeIndex(String[] header, String timeColumn) {
        for (int i = 0; i < header.length; i++) {
            String h = header[i].trim();
            if (timeColumn != null ? h.equals(timeColumn)
                    : h.equalsIgnoreCase("time") || h.equals("_time") || h.equalsIgnoreCase("timestamp")) {
                return i;
            }
        }
        return -1;
    }

    private static int valueIndex(String[] header) {
        for (int i = 0; i < header.length; i++) {
            String h = header[i].trim();
            if (h.equalsIgnoreCase("value") || h.equals("_value")) {
                return i;
            }
        }
        return header.length - 1;
    }

    private static String seriesKey(String[] row, int time, int value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i != time && i != value) {
                sb.append(row[i]).append('\u0001');
            }
        }
        return sb.toString();
    }

    /** Epoch milliseconds, or an ISO time with or without a zone (its gaps are the same); else null. */
    static Long millis(String cell) {
        String s = cell.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                long v = Long.parseLong(s);
                return v >= 100_000_000_000L ? v : null;
            } catch (NumberFormatException tooLong) {
                return null;
            }
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException notOffset) {
            // fall through
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException notInstant) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException notLocal) {
            return null;
        }
    }

    /** One line's cells: a quoted cell may hold the delimiter, and "" is a quote inside one. */
    static String[] cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out.toArray(new String[0]);
    }
}
