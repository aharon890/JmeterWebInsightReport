package com.jmeterwebinsightreport.core.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.rundata.RunDataFormat;
import com.jmeterwebinsightreport.core.rundata.RunDataReader;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Reads a Compare baseline (spec 004, User Story 3, FR-001): a JMeter results file, read by {@link JtlParser} as it
 * always was, or run data of any tool, read by {@link RunDataReader} as {@code build-report --run-data} reads it.
 * Which one a file is is read from its first bytes, never from its name: gzip's magic bytes and a first line that is
 * the run data's "run" record, an XML results file's {@code <testResults}, or a CSV results file's header (or, with no
 * header, a numeric first cell, which the parser then decides on as it always did). Anything else is no baseline, and
 * says so in one sentence naming the file and what a baseline is.
 */
public final class BaselineReader {

    private static final Logger log = LoggerFactory.getLogger(BaselineReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How much of the file's start is looked at: a run record or a results file's header is far shorter. */
    private static final int LOOK = 1 << 16;

    /** What a file is, read from its content. */
    public enum Kind {
        /** A JMeter results file (CSV or XML): {@link JtlParser}, which refuses what it never read, as before. */
        JMETER_RESULTS,
        /** Run data of any tool, plain or gzipped. */
        RUN_DATA,
        /** Neither: no Compare. */
        NEITHER
    }

    /** A file that is neither a JMeter results file nor run data. Its message is the whole sentence. */
    public static final class NotABaseline extends IOException {
        NotABaseline(String message) {
            super(message);
        }
    }

    private BaselineReader() {
    }

    /** The baseline's report data, read by the reader its content asks for. */
    public static ReportData read(File file) throws IOException {
        switch (kindOf(file)) {
            case RUN_DATA: {
                LiveStatisticsEngine engine = new LiveStatisticsEngine();
                RunDataReader.Result read = new RunDataReader().read(file, engine);
                for (String problem : read.problems) {
                    log.warn("Baseline run data {}: {}", file.getName(), problem);
                }
                return RunDataReader.reportOf(read, engine);
            }
            case JMETER_RESULTS:
                return new JtlParser().parse(file, "Baseline");
            default:
                throw new NotABaseline(refusal(file));
        }
    }

    /** The sentence for a file that is neither: it names the file and what a baseline is. */
    static String refusal(File file) {
        return file.getPath() + " is neither a JMeter results file (a CSV whose first line names its columns, timeStamp"
                + " among them) nor run data (JSON lines, plain or gzipped, whose first record is the \""
                + RunDataFormat.TYPE_RUN + "\" record)";
    }

    /** What the file is, from its first bytes. */
    public static Kind kindOf(File file) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(file), LOOK)) {
            boolean gzip = RunDataReader.isGzip(raw);
            InputStream in = gzip ? new GZIPInputStream(raw, LOOK) : raw;
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            String start = start(reader);
            String first = firstLine(start);
            if (first == null) {
                return Kind.NEITHER;
            }
            if (first.startsWith("{")) {
                return isRunRecord(first) ? Kind.RUN_DATA : Kind.NEITHER;
            }
            if (gzip) {
                // A gzipped file that is not run data: the results file parser never read a gzipped file
                return Kind.NEITHER;
            }
            if (first.startsWith("<")) {
                return start.contains("<testResults") ? Kind.JMETER_RESULTS : Kind.NEITHER;
            }
            return isResultsCsv(first) ? Kind.JMETER_RESULTS : Kind.NEITHER;
        } catch (ZipException | EOFException e) {
            // gzip's magic bytes over something that is not gzip, or a gzip cut short
            return Kind.NEITHER;
        }
    }

    /** The first {@link #LOOK} characters, or the whole file when shorter. */
    private static String start(Reader reader) throws IOException {
        char[] buf = new char[LOOK];
        int n = 0;
        int r;
        while (n < LOOK && (r = reader.read(buf, n, LOOK - n)) > 0) {
            n += r;
        }
        return new String(buf, 0, n);
    }

    /** The first line that is not blank, without a byte-order mark and trimmed; null for none. */
    private static String firstLine(String start) {
        String s = !start.isEmpty() && start.charAt(0) == '﻿' ? start.substring(1) : start;
        for (String line : s.split("\r?\n", -1)) {
            if (!line.trim().isEmpty()) {
                return line.trim();
            }
        }
        return null;
    }

    private static boolean isRunRecord(String line) {
        try {
            JsonNode r = JSON.readTree(line);
            return r != null && RunDataFormat.TYPE_RUN.equals(r.path("type").asText(null));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A JMeter CSV results file: a header naming timeStamp (the parser's own first required column), or no header and a
     * numeric first cell, which the parser then reads, or refuses, exactly as it did before there was a choice.
     */
    private static boolean isResultsCsv(String first) {
        if (first.contains("timeStamp")) {
            return true;
        }
        int end = 0;
        while (end < first.length() && Character.isDigit(first.charAt(end))) {
            end++;
        }
        return end > 0 && (end == first.length() || ",;\t|".indexOf(first.charAt(end)) >= 0);
    }
}
