package com.jmeterwebinsightreport.core.parser;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses JMeter JTL/CSV result files and produces ReportData via LiveStatisticsEngine.
 *
 * <p>Reads RFC 4180 CSV: fields may be quoted, quoted fields may contain commas and
 * newlines, and a doubled quote inside a quoted field is a literal quote. JMeter writes
 * exactly this format for {@code responseMessage} and assertion text, both of which
 * routinely contain newlines.
 *
 * <p>All parsing state is local to {@link #parse}, so an instance is reusable and safe
 * to call from more than one thread.
 */
public class JtlParser {

    private static final Logger log = LoggerFactory.getLogger(JtlParser.class);

    /** UTF-8 byte-order mark. JMeter's GUI writes one; it must not become part of the first
     *  header, or validateHeaders rejects the file with a misleading "Missing required JTL
     *  column: timeStamp". Written as a code point so the source encoding cannot alter it. */
    private static final char BOM = (char) 0xFEFF;

    /** Safety valve: an unbalanced quote in a malformed file must not swallow the whole file. */
    private static final int MAX_LINES_PER_RECORD = 1000;

    /** How many skipped rows to name individually before summarising. */
    private static final int MAX_REPORTED_ERRORS = 3;

    private long rampUpDurationMillis;
    private double apdexThreshold = 500;

    public void setRampUpDurationMillis(long rampUpDurationMillis) {
        this.rampUpDurationMillis = rampUpDurationMillis;
    }

    public void setApdexThreshold(double apdexThreshold) {
        this.apdexThreshold = apdexThreshold;
    }

    /**
     * Parse a JTL file and return ReportData.
     *
     * @param jtlFile the JTL/CSV result file
     * @param testName name for the report
     * @return parsed and aggregated report data
     * @throws IOException if the file cannot be read, is not a CSV JTL, or contains no
     *         sample that could be parsed
     */
    public ReportData parse(File jtlFile, String testName) throws IOException {
        return parse(jtlFile, testName, new LiveStatisticsEngine());
    }

    /** {@link #parse(File, String)} into a given engine — the seam a test hands a failing one. */
    ReportData parse(File jtlFile, String testName, LiveStatisticsEngine engine) throws IOException {
        engine.setRampUpDurationMillis(rampUpDurationMillis);
        engine.setApdexThreshold(apdexThreshold);

        long recordsParsed = 0;
        long recordsSkipped = 0;
        List<String> firstErrors = new ArrayList<>(MAX_REPORTED_ERRORS);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(jtlFile), StandardCharsets.UTF_8))) {

            String headerLine = readRecord(reader);
            if (headerLine == null) {
                throw new IOException("Empty JTL file: " + jtlFile);
            }
            headerLine = stripBom(headerLine);
            rejectXmlJtl(headerLine, jtlFile);

            Map<String, Integer> headers = parseHeaders(headerLine);
            validateHeaders(headers);

            int colTimestamp = headers.get("timeStamp");
            int colElapsed = headers.get("elapsed");
            int colLabel = headers.get("label");
            int colCode = headers.get("responseCode");
            int colMessage = headers.getOrDefault("responseMessage", -1);
            int colThread = headers.getOrDefault("threadName", -1);
            int colSuccess = headers.get("success");
            int colBytes = headers.getOrDefault("bytes", -1);
            int colSentBytes = headers.getOrDefault("sentBytes", -1);
            // Prefer total concurrent threads (allThreads) over per-group (grpThreads).
            int colAllThreads = headers.getOrDefault("allThreads", -1);
            int colGrpThreads = headers.getOrDefault("grpThreads", -1);
            int colThreads = colAllThreads >= 0 ? colAllThreads : colGrpThreads;
            int colConnect = headers.getOrDefault("Connect", -1);
            int colLatency = headers.getOrDefault("Latency", -1);
            int colUrl = headers.getOrDefault("URL", -1);

            int expectedColumns = headers.size();
            List<String> physicalLines;
            long recordNum = 1;
            while ((physicalLines = readRecordLines(reader)) != null) {
                String record = String.join("\n", physicalLines);
                long firstLineNum = recordNum + 1;
                recordNum += physicalLines.size();
                if (record.trim().isEmpty()) continue;

                if (consume(engine, record, expectedColumns, colTimestamp, colElapsed, colLabel,
                        colCode, colSuccess, colBytes, colSentBytes, colConnect, colLatency,
                        colThreads, colMessage, colThread, colUrl)) {
                    recordsParsed++;
                    continue;
                }

                // The record failed to parse. If it was stitched together from several
                // physical lines, the join was almost certainly caused by a stray unbalanced
                // quote rather than a genuine multi-line field — and blindly discarding it
                // would throw away every valid row that got swallowed with it (up to the
                // join cap). Fall back to parsing each physical line on its own.
                int recovered = 0;
                int lost = 0;
                if (physicalLines.size() > 1) {
                    for (String physical : physicalLines) {
                        if (physical.trim().isEmpty()) continue;
                        if (consume(engine, physical, expectedColumns, colTimestamp, colElapsed,
                                colLabel, colCode, colSuccess, colBytes, colSentBytes, colConnect,
                                colLatency, colThreads, colMessage, colThread, colUrl)) {
                            recovered++;
                        } else {
                            lost++;
                        }
                    }
                    recordsParsed += recovered;
                    recordsSkipped += lost;
                    if (firstErrors.size() < MAX_REPORTED_ERRORS) {
                        firstErrors.add("rows " + firstLineNum + "-" + recordNum
                                + ": unbalanced quote joined " + physicalLines.size()
                                + " lines; recovered " + recovered + ", skipped " + lost);
                    }
                    log.warn("Unbalanced quote in {} joined rows {}-{}; recovered {} of them individually",
                            jtlFile.getName(), firstLineNum, recordNum, recovered);
                } else {
                    recordsSkipped++;
                    if (firstErrors.size() < MAX_REPORTED_ERRORS) {
                        firstErrors.add("row " + recordNum + ": unparseable");
                    }
                }
            }
        }

        reportSkippedRecords(jtlFile, recordsParsed, recordsSkipped, firstErrors);

        log.info("Parsed {} samples from JTL file: {}", recordsParsed, jtlFile.getName());
        return engine.buildReportData(testName);
    }

    /**
     * Parse one record and feed it to the engine.
     *
     * <p>Only the parse is caught. It used to be one try around parsing AND feeding, so an engine
     * that threw part-way through a record — after {@code recordSample}, in {@code recordError} —
     * was read as "could not parse": the sample stayed in the statistics, the row was counted as
     * skipped, and a record joined from several physical lines was handed back line by line, so
     * the engine recorded the same sample again (CORE-3). A failure inside the engine is a defect,
     * not a malformed row, and it propagates.
     *
     * @return true if the record yielded a sample, false if it could not be parsed
     * @throws IllegalStateException when the engine fails on a record that parsed
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static boolean consume(LiveStatisticsEngine engine, String record, int expectedColumns,
                                   int colTimestamp, int colElapsed, int colLabel, int colCode,
                                   int colSuccess, int colBytes, int colSentBytes, int colConnect,
                                   int colLatency, int colThreads, int colMessage, int colThread,
                                   int colUrl) {
        ParsedSample sample;
        try {
            sample = parseSample(record, expectedColumns, colTimestamp, colElapsed, colLabel,
                    colCode, colSuccess, colBytes, colSentBytes, colConnect, colLatency, colThreads,
                    colMessage, colThread, colUrl);
        } catch (RuntimeException e) {
            return false;
        }
        if (sample == null) {
            return false;
        }
        try {
            sample.feed(engine);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The statistics engine failed on the sample '"
                    + sample.label + "' at " + sample.timestamp + ", a row that parsed: "
                    + describe(e), e);
        }
        return true;
    }

    /**
     * One record's fields, read and nothing more — or null when the record is not a record.
     * Never touches the engine, so whatever it throws is a parse failure.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static ParsedSample parseSample(String record, int expectedColumns,
                                            int colTimestamp, int colElapsed, int colLabel,
                                            int colCode, int colSuccess, int colBytes,
                                            int colSentBytes, int colConnect, int colLatency,
                                            int colThreads, int colMessage, int colThread,
                                            int colUrl) {
        String[] fields = parseCsvLine(record);
        // A field count nowhere near the header's means this is not a record —
        // most often the tail of a wrongly-joined line. Reject it so the caller
        // can try the physical lines individually.
        if (fields.length < expectedColumns) {
            return null;
        }

        ParsedSample s = new ParsedSample();
        s.timestamp = Long.parseLong(fields[colTimestamp].trim());
        s.elapsed = Long.parseLong(fields[colElapsed].trim());
        s.label = fields[colLabel];
        s.responseCode = fields[colCode];
        s.success = Boolean.parseBoolean(fields[colSuccess].trim());
        s.bytes = colBytes >= 0 && colBytes < fields.length
                ? parseLongSafe(fields[colBytes]) : 0;
        s.sentBytes = colSentBytes >= 0 && colSentBytes < fields.length
                ? parseLongSafe(fields[colSentBytes]) : 0;
        s.connectTime = colConnect >= 0 && colConnect < fields.length
                ? parseLongSafe(fields[colConnect]) : -1;
        s.latency = colLatency >= 0 && colLatency < fields.length
                ? parseLongSafe(fields[colLatency]) : -1;
        s.threads = colThreads >= 0 && colThreads < fields.length
                ? parseIntSafe(fields[colThreads]) : 0;
        if (!s.success) {
            s.message = colMessage >= 0 && colMessage < fields.length ? fields[colMessage] : "";
            s.thread = colThread >= 0 && colThread < fields.length ? fields[colThread] : "";
            s.url = colUrl >= 0 && colUrl < fields.length ? fields[colUrl] : null;
        }
        return s;
    }

    /** A parsed record, held so feeding the engine sits outside the parse's catch. */
    private static final class ParsedSample {
        long timestamp;
        long elapsed;
        String label;
        String responseCode;
        boolean success;
        long bytes;
        long sentBytes;
        long connectTime;
        long latency;
        int threads;
        String message;
        String thread;
        String url;

        void feed(LiveStatisticsEngine engine) {
            engine.recordSample(label, elapsed, success, bytes, sentBytes, timestamp,
                    connectTime, latency);
            if (threads > 0) {
                engine.updateActiveThreads(threads, timestamp);
            }
            if (!success) {
                engine.recordError(label, responseCode, message, timestamp, thread, url, null, null);
            }
        }
    }

    /**
     * Fail loudly when nothing could be parsed. Previously every row was caught, logged at
     * WARN and swallowed, so a JTL written with a non-epoch
     * {@code jmeter.save.saveservice.timestamp_format} produced a completely blank report
     * that looked like a plugin bug rather than a configuration problem.
     */
    private void reportSkippedRecords(File jtlFile, long parsed, long skipped,
                                      List<String> firstErrors) throws IOException {
        if (skipped == 0) {
            return;
        }
        String detail = String.join("; ", firstErrors);
        if (parsed == 0) {
            throw new IOException(String.format(Locale.US,
                    "No usable samples in %s — all %d data rows failed to parse (%s). "
                    + "The usual cause is a non-epoch timeStamp column: this parser needs "
                    + "jmeter.save.saveservice.timestamp_format=ms.",
                    jtlFile.getName(), skipped, detail));
        }
        log.warn("Skipped {} of {} data rows in {} ({})",
                skipped, parsed + skipped, jtlFile.getName(), detail);
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message != null ? " " + message : "");
    }

    private static String stripBom(String header) {
        return !header.isEmpty() && header.charAt(0) == BOM ? header.substring(1) : header;
    }

    /**
     * JMeter can also write results as XML. That yields nonsense headers and then a
     * misleading "Missing required JTL column" error, so name the real problem instead.
     */
    private static void rejectXmlJtl(String headerLine, File jtlFile) throws IOException {
        if (headerLine.trim().startsWith("<")) {
            throw new IOException("XML-format JTL is not supported: " + jtlFile.getName()
                    + ". Re-run with jmeter.save.saveservice.output_format=csv, "
                    + "or convert the file to CSV.");
        }
    }

    /**
     * Read one logical CSV record. A quoted field may contain newlines (JMeter writes
     * multi-line assertion text and response messages), in which case the record spans
     * several physical lines; reading line-by-line split those into phantom samples whose
     * columns were all shifted.
     *
     * @return the record with embedded newlines preserved, or null at end of file
     */
    private static String readRecord(BufferedReader reader) throws IOException {
        List<String> lines = readRecordLines(reader);
        return lines == null ? null : String.join("\n", lines);
    }

    /**
     * Read the physical lines that make up one logical CSV record.
     *
     * <p>The caller gets the individual lines, not just the joined text, because the join is a
     * heuristic: a line with an odd number of quotes is <em>assumed</em> to open a quoted field
     * that a later line closes. A single stray quote in an otherwise ordinary row therefore
     * absorbs the following well-formed rows. Handing back the lines lets the caller parse them
     * separately when the joined record turns out not to be a record at all, instead of
     * discarding hundreds of valid samples as "one malformed row".
     *
     * @return the record's physical lines, or null at end of file
     */
    private static List<String> readRecordLines(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        List<String> lines = new ArrayList<>(1);
        lines.add(line);
        if (isBalanced(line)) {
            return lines;
        }

        String next;
        while (lines.size() < MAX_LINES_PER_RECORD && (next = reader.readLine()) != null) {
            lines.add(next);
            if (!isBalanced(next)) {
                // This line closes the open quote (an odd count flips the state back).
                break;
            }
        }
        return lines;
    }

    /** True when the line has an even number of quote characters, i.e. no field left open. */
    private static boolean isBalanced(String line) {
        int quotes = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '"') quotes++;
        }
        return (quotes & 1) == 0;
    }

    private Map<String, Integer> parseHeaders(String headerLine) {
        String[] headers = parseCsvLine(headerLine);
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    private void validateHeaders(Map<String, Integer> headers) throws IOException {
        String[] required = {"timeStamp", "elapsed", "label", "responseCode", "success"};
        for (String h : required) {
            if (!headers.containsKey(h)) {
                throw new IOException("Missing required JTL column: " + h);
            }
        }
    }

    /**
     * RFC 4180 CSV record splitter. Handles quoted fields containing commas and newlines,
     * and unescapes a doubled quote to a single literal quote — the old toggle-on-every-quote
     * approach dropped {@code ""} entirely, so {@code say ""hi""} became {@code say hi} and a
     * label no longer matched its baseline counterpart.
     */
    static String[] parseCsvLine(String line) {
        // Fast path: no quotes at all, so a plain split is exact and avoids per-char copying.
        if (line.indexOf('"') < 0) {
            return splitUnquoted(line);
        }

        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder(64);
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');   // escaped quote
                    i++;
                } else {
                    inQuotes = false;
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    private static String[] splitUnquoted(String line) {
        int colCount = countChar(line, ',') + 1;
        String[] result = new String[colCount];
        int fieldIdx = 0;
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ',') {
                result[fieldIdx++] = line.substring(start, i);
                start = i + 1;
            }
        }
        result[fieldIdx] = line.substring(start);
        return result;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
