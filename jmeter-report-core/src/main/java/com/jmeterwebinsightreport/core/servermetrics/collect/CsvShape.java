package com.jmeterwebinsightreport.core.servermetrics.collect;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * What a CSV's header says about how to read it, for the one flag a user would otherwise have to
 * know to pass (decision 12, "measure, don't ask"): its layout. A default only - an explicit
 * {@code --layout} always wins - and the collector prints it when it used it, so it is never silent.
 * It is read here, off the header, because it must be known before {@link CsvSource} indexes the
 * file: the layout is part of what the file reads as. The step is measured from that index instead
 * ({@link CsvSource#measuredStepMillis}), so it comes from the reader's own rows and rules.
 *
 * <p>Conservative on purpose. The wide layout is reported only for a header Grafana's "Series
 * joined by time" download writes: a {@code Time} column first, no {@code component} or
 * {@code metric} column, and every other column named {@code component/metric[/instance]}. A file
 * it cannot read is not wide; reading it properly, and refusing it in a sentence, is the reader's job.
 */
public final class CsvShape {

    private CsvShape() {
    }

    /**
     * True when the file's header - its first line that is neither blank nor a {@code #} line, split
     * as {@link CsvSource} splits one - is unmistakably Grafana's wide layout.
     */
    public static boolean looksWide(File file) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(file.toPath()),
                StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = in.readLine()) != null) {
                List<CsvSource.Row> rows = CsvSource.rows(first ? CsvSource.withoutBom(line) : line);
                first = false;
                if (rows.isEmpty() || rows.get(0).blank() || rows.get(0).annotation()) {
                    continue;
                }
                return looksWide(rows.get(0).cells.toArray(new String[0]));
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
        return false;
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
}
