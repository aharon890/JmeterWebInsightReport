package com.jmeterwebinsightreport.report.exporter;

import com.jmeterwebinsightreport.WirCollect;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.report.generator.ReportArtifacts;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one rebuild of a report from a finished run's JTL: parse it, read the files beside it (the
 * annotations, the baseline, the server data - a CSV collected here, in-process), generate, and say
 * what was written. {@code jmeter -g} runs it ({@link WebInsightDataExporter}) and so does the
 * listener panel's Rebuild button.
 *
 * <p>The API, in the order a caller uses it:
 * <pre>
 * static ReportData parse(File jtl, ReportConfiguration config, Function&lt;String, String&gt; props) throws IOException
 * static void readCompanionFiles(ReportConfiguration config, File outputDir, File jtl, ReportData parsed,
 *                                Function&lt;String, String&gt; props, PrintStream out, PrintStream err,
 *                                Map&lt;String, String&gt; env, Route route)
 * static ReportArtifacts generateAndAnnounce(ReportConfiguration config, ReportData data, String jtlForNext,
 *                                            PrintStream out, Consumer&lt;String&gt; log) throws IOException
 * static boolean looksLikeJson(File file)
 * static final String[][] CSV_OPTIONS
 * </pre>
 *
 * <p><b>Why it is one class and not two copies.</b> Two copies of "rebuild" drift, and this project
 * has watched it happen: {@code failonassert} was read on the listener path and not at all on the
 * {@code -g} one, so one file and one FAIL exited 1 under {@code -n -t} and 0 under {@code -g -o}
 * ({@code ExitPolicy} exists because of it). So the button does not get a second rebuild that
 * matches the first today; it gets this one.
 *
 * <p><b>What it deliberately does not own</b> is everything that is a policy of the caller rather
 * than a step of the rebuild: where the output directory is and how the configuration is built,
 * {@code PropertyCheck}, the "this run is already reported" skip, the {@code CONFIG ERROR} /
 * {@code INCOMPLETE} / {@code ASSERT FAIL} markers, {@code NonZeroExit} and {@code ExportException}.
 * Those mean something to a process with an exit code and nothing to a button in a GUI session,
 * where arranging an exit code would hang a {@code halt(1)} on the user's JMeter.
 *
 * <p>Properties arrive through a {@code Function<String, String>} and console lines leave through
 * the {@code PrintStream}s the caller hands in, so {@code -g} passes {@code JMeterUtils::getProperty}
 * and {@code System.out}/{@code System.err} - its console is byte for byte what it was before this
 * class existed - and the button passes streams it shows in a dialog.
 */
public final class ReportRebuild {

    private static final Logger log = LoggerFactory.getLogger(ReportRebuild.class);

    /** The reader options for a CSV given as server data, as -J or -q properties, and wir-collect's flag for each. */
    public static final String[][] CSV_OPTIONS = {
            {"step", "--step"}, {"clockoffset", "--clock-offset"}, {"timezone", "--time-zone"}, {"from", "--from"},
            {"layout", "--layout"}, {"columns", "--columns"}, {"csvcolumns", "--csv-columns"},
            {"component", "--component"}, {"env", "--env"}};

    /**
     * Who runs the rebuild, which decides where two of its sentences send the reader. Both are written
     * into the report as the refused payload's reason and shown again wherever that reason is shown, so
     * each must be true for the caller that produced it.
     *
     * <p>Under {@code jmeter -g} the collector's lines are on the console and the annotations file is a
     * property. Under the panel's button the collector's lines were captured and shown in the Rebuild
     * dialog - a JMeter GUI started from a shortcut has no console at all - and the annotations file is
     * a field on the panel. One sentence per caller with one owner, here, rather than the GUI rewriting
     * the {@code -g} sentence after the fact; and the {@code -g} sentences are byte for byte what they
     * were before the button existed.
     */
    public enum Route {
        /** {@code jmeter -g} and Tools &gt; Generate HTML report: a console, and properties. */
        COMMAND_LINE("the wir-collect lines on the console say why",
                "give webinsight.report.annotations too"),
        /** The listener panel's Rebuild button: a dialog, and the panel's fields. */
        LISTENER_PANEL("the wir-collect lines in the Rebuild dialog say why",
                "name one in the listener's Annotations File field (or webinsight.report.annotations)");

        private final String whyCollectionWasRefused;
        private final String howToGiveAnnotations;

        Route(String whyCollectionWasRefused, String howToGiveAnnotations) {
            this.whyCollectionWasRefused = whyCollectionWasRefused;
            this.howToGiveAnnotations = howToGiveAnnotations;
        }
    }

    private ReportRebuild() {
    }

    /**
     * {@code sampleresult.timestamp.start} read as {@code JMeterUtils.getPropDefault} reads a boolean:
     * {@code true}/{@code t} or 1 is true, {@code false}/{@code f} or any other number false, and
     * anything else JMeter's default, false. Absent is true: {@code jmeter.properties} ships the
     * line set to true, and a properties source without it here is a test's or a caller's that
     * never loaded JMeter's file, not a JMeter that stamps at the end.
     */
    static boolean stampedAtStart(String value) {
        return jmeterBoolean(value, false);
    }

    /**
     * {@code jmeter.save.saveservice.assertion_results_failure_message}, read as JMeter's HTML report reads it
     * ({@code ErrorsSummaryConsumer}: {@code getPropDefault(..., true)}): whether a failed assertion's message
     * names a 2xx failure's type of error (E1).
     */
    static boolean failureMessageTypesErrors(String value) {
        return jmeterBoolean(value, true);
    }

    /** As {@code JMeterUtils.getPropDefault} reads a boolean, absent read as true (see {@link #stampedAtStart}). */
    private static boolean jmeterBoolean(String value, boolean unreadable) {
        if (value == null) {
            return true;
        }
        String v = value.trim();
        if ("true".equalsIgnoreCase(v) || "t".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "f".equalsIgnoreCase(v)) {
            return false;
        }
        try {
            return Integer.parseInt(v) == 1;
        } catch (NumberFormatException e) {
            return unreadable;
        }
    }

    /**
     * The JTL parsed as the report reads it: under the configured title, which {@code runId} hashes,
     * with the configured ramp-up excluded, the Apdex threshold from {@code webinsight.apdex.threshold}
     * (an unreadable one warned about and the parser's default kept), and the JMeter version in the
     * metadata when there is one to read.
     */
    public static ReportData parse(File jtl, ReportConfiguration config, Function<String, String> props)
            throws IOException {
        JtlParser parser = new JtlParser();
        if (config.getRampUpDurationMillis() > 0) {
            parser.setRampUpDurationMillis(config.getRampUpDurationMillis());
        }

        String apdexStr = props.apply("webinsight.apdex.threshold");
        if (apdexStr != null && !apdexStr.isEmpty()) {
            try {
                parser.setApdexThreshold(Double.parseDouble(apdexStr));
            } catch (NumberFormatException e) {
                log.warn("Invalid webinsight.apdex.threshold: {}", apdexStr);
            }
        }

        // The file is read the way JMeter wrote it (B3): sampleresult.timestamp.start=false
        // stamps each row at its end.
        parser.setTimestampIsStart(stampedAtStart(props.apply("sampleresult.timestamp.start")));
        parser.setFailureMessageTypesErrors(failureMessageTypesErrors(
                props.apply("jmeter.save.saveservice.assertion_results_failure_message")));

        ReportData reportData = parser.parse(jtl, config.getReportTitle());

        try {
            String version = JMeterUtils.getJMeterVersion();
            if (version != null) {
                reportData.getMetadata().setJmeterVersion(version);
            }
        } catch (Exception e) {
            // JMeterUtils may not be available
        }
        return reportData;
    }

    /**
     * The files this run reads beside its own settings, in the order one needs the other: the
     * annotations and baseline auto-detected in the output directory first, then the server data,
     * because collecting a CSV reads the annotations file to know what to collect. Collected first,
     * a CSV given beside an auto-detected annotations file was refused as having none.
     * {@code parsed} is this run's parse of {@code jtl}, which the collection reuses (null: the
     * collector parses the file itself). {@code route} is who asked, so a refusal names where its
     * explanation actually is.
     */
    public static void readCompanionFiles(ReportConfiguration config, File outputDir, File jtl, ReportData parsed,
                                          Function<String, String> props, PrintStream out, PrintStream err,
                                          Map<String, String> env, Route route) {
        autoDetectCompanionFiles(config, outputDir);
        collectServerCsvIfGiven(config, jtl, parsed, props, out, err, env, route);
    }

    /**
     * Generate the report and say so: the success marker to the log, the console summary and the
     * marker to {@code out}, then the NEXT line to {@code out} and the log when there is one. Then
     * the temporary payload the collection wrote, if it wrote one, is deleted - whether or not the
     * generation got that far.
     *
     * <p>The same emitters as the listener, from the same strings, so the ingestion paths cannot
     * drift apart in what they report. One line to the log (the generator's intent line already
     * itemises every artifact), the block to {@code out}.
     *
     * @param jtlForNext the JTL as the NEXT line names it ({@code /} separators), or null
     * @param log        where the marker and the NEXT line are logged; null logs nothing
     */
    public static ReportArtifacts generateAndAnnounce(ReportConfiguration config, ReportData data,
                                                      String jtlForNext, PrintStream out, Consumer<String> log)
            throws IOException {
        try {
            ReportArtifacts artifacts = new WebInsightReportGenerator(config).generateReport(data);
            if (log != null) {
                log.accept(artifacts.successMarker());
            }
            out.println(artifacts.consoleSummary());
            out.println(artifacts.successMarker());
            String next = artifacts.nextStepsLine(jtlForNext);
            if (next != null) {
                out.println(next);
                if (log != null) {
                    log.accept(next);
                }
            }
            return artifacts;
        } finally {
            deleteCollectedPayload(config);
        }
    }

    /**
     * The payload the collection wrote is this rebuild's scratch, read once by the generator and
     * worth nothing after: the report embeds what it needs. Only that file - never a
     * {@code server-metrics.json} a user gave as server data, which {@link ReportConfiguration}
     * keeps apart by never recording it as collected.
     */
    private static void deleteCollectedPayload(ReportConfiguration config) {
        File collected = config.getCollectedServerPayload();
        if (collected == null) {
            return;
        }
        config.setCollectedServerPayload(null);
        if (collected.exists() && !collected.delete()) {
            log.debug("Could not delete the temporary server payload {} - it goes when the JVM exits", collected);
        }
    }

    /**
     * Auto-detect report-annotations.json and baseline.jtl in the output directory.
     */
    private static void autoDetectCompanionFiles(ReportConfiguration config, File outputDir) {
        if (config.getAnnotationsFile() == null) {
            File autoAnnotations = new File(outputDir, "report-annotations.json");
            if (autoAnnotations.exists()) {
                config.setAnnotationsFile(autoAnnotations);
                log.warn("{}", ReportConfiguration.annotationsAutoDetectWarning(autoAnnotations));
            }
        }

        if (config.getBaselineJtlFile() == null) {
            File autoBaseline = new File(outputDir, "baseline.jtl");
            if (autoBaseline.exists()) {
                config.setBaselineJtlFile(autoBaseline);
                log.info("Auto-detected baseline JTL: {}", autoBaseline.getAbsolutePath());
            }
        }
    }

    /**
     * When the server data names a CSV rather than a wir-collect payload, collect it here,
     * in-process, with the collector's own code ({@link WirCollect#run}): every check, refusal and
     * console line the collector has, no second grammar. The run's window, identity and ramp-up come
     * from this very JTL and title, so there is no --test-name or second --rampup to get wrong. The
     * JTL is not read again: {@code parsed}, this report's own parse of it under its title, is handed
     * to the collector ({@link WirCollect#run(String[], ReportData, PrintStream, PrintStream, Map)}),
     * whose window and runId derive from exactly the fields its own parse would give (null: the
     * collector parses the file). The payload goes to a temporary file that this report then reads
     * and {@link #generateAndAnnounce} deletes; a CSV the collector refuses becomes a refused payload
     * - said, never fatal by default (decision B).
     *
     * <p>A relative path that does not exist where JMeter runs is looked up beside the JTL, so a
     * properties file with relative names works from JMeter's own Generate HTML report dialog too.
     *
     * <p>The two refusals that send the reader somewhere - to the collector's lines, to the annotations
     * input - say where through {@code route}: the console and a property for {@code -g}, the Rebuild
     * dialog and the panel's field for the button.
     */
    static void collectServerCsvIfGiven(ReportConfiguration config, File jtlFile, ReportData parsed,
                                        Function<String, String> props, PrintStream out, PrintStream err,
                                        Map<String, String> env, Route route) {
        File given = config.getServerMetricsFile();
        if (given == null) {
            return;
        }
        if (!given.exists() && !given.isAbsolute() && jtlFile != null && jtlFile.getAbsoluteFile().getParentFile() != null) {
            File besideJtl = new File(jtlFile.getAbsoluteFile().getParentFile(), given.getPath());
            if (besideJtl.exists()) {
                given = besideJtl;
                config.setServerMetricsFile(given);
            }
        }
        if (!given.isFile() || looksLikeJson(given)) {
            return;   // a payload, or a missing file: the loader says which, as it always has
        }
        if (config.getAnnotationsFile() == null) {
            config.setServerMetricsFile(null);
            config.setServerDataProblem("the server data " + given.getName() + " is a CSV, and no annotations file"
                    + " says what to read from it - " + route.howToGiveAnnotations);
            return;
        }
        List<String> args = new ArrayList<>(Arrays.asList(
                "--jtl", jtlFile.getAbsolutePath(), "--annotations", config.getAnnotationsFile().getPath(),
                "--csv", given.getPath(), "--no-wait",
                "--collected-at", java.time.Instant.ofEpochMilli(given.lastModified()).toString()));
        File payload;
        try {
            payload = File.createTempFile("wir-server-metrics-", ".json");
            // A backstop only: generateAndAnnounce deletes it once the report has read it.
            payload.deleteOnExit();
        } catch (IOException e) {
            config.setServerMetricsFile(null);
            config.setServerDataProblem("the server CSV " + given.getName() + " could not be collected: "
                    + ReportArtifacts.describe(e));
            return;
        }
        args.add("--out");
        args.add(payload.getPath());
        if (config.getReportTitle() != null && !config.getReportTitle().isEmpty()) {
            args.add("--test-name");
            args.add(config.getReportTitle());
        }
        if (config.getRampUpDurationMillis() > 0) {
            args.add("--rampup");
            args.add(String.valueOf(config.getRampUpDurationMillis()));
        }
        // The files the collector is handed, which its refusal may name by a local path (D5).
        List<String> paths = new ArrayList<>(Arrays.asList(jtlFile.getAbsolutePath(),
                config.getAnnotationsFile().getPath(), given.getPath(), payload.getPath()));
        for (String[] option : CSV_OPTIONS) {
            String value = props.apply("webinsight.report.servermetrics." + option[0]);
            if (value != null && !value.trim().isEmpty()) {
                args.add(option[1]);
                args.add(value.trim());
                if ("columns".equals(option[0])) {
                    paths.add(value.trim());
                }
            }
        }
        // Its lines still go to err as they come; a copy is kept for the refusal.
        java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        PrintStream collectorErr = new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                err.write(b);
                said.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                err.write(b, off, len);
                said.write(b, off, len);
            }

            @Override
            public void flush() {
                err.flush();
            }
        }, true);
        int code;
        try {
            code = WirCollect.run(args.toArray(new String[0]), parsed, out, collectorErr, env);
        } catch (OutOfMemoryError e) {
            collectorErr.println("wir-collect: ran out of memory reading " + given.getName()
                    + " - give JMeter more heap (JVM_ARGS=-Xmx2g) or export a narrower window");
            code = 1;
        } catch (RuntimeException e) {
            // A fault in the collector costs the Server tab, never the client report (decision B):
            // uncaught, it reached the exporter as an ExportException and no report was written.
            collectorErr.println("wir-collect: could not collect " + given.getName() + " - "
                    + ReportArtifacts.describe(e));
            code = 1;
        }
        collectorErr.flush();
        if (code == 0) {
            config.setServerMetricsFile(payload);
            config.setCollectedServerPayload(payload);
        } else {
            // Nothing was written into it, and nothing will read it: the empty file createTempFile
            // made goes now rather than at the JVM's exit, which in a GUI JMeter is hours away.
            payload.delete();
            config.setServerMetricsFile(null);
            String refusal = refusalOf(new String(said.toByteArray(), java.nio.charset.Charset.defaultCharset()), paths);
            config.setServerDataProblem("the server CSV " + given.getName() + " could not be collected"
                    + (refusal != null ? ": " + refusal : " - " + route.whyCollectionWasRefused));
        }
    }

    /**
     * The collector's refusal in what it wrote on err: its last {@code wir-collect:} line (findings come
     * before it, and a flag refusal's usage lines after it are not the collector's), without that prefix,
     * and with every one of {@code paths} it names reduced to its file name (D5). It becomes the refused
     * payload's reason, which the page, the CI/CD JSON and JUnit print, and the published page names no
     * local path ({@code report_local_path}); the console line keeps the path. Null when no line is the
     * collector's: the caller then points at where its lines went.
     *
     * @param paths the files the collector was handed, as handed: each is matched as written, absolute,
     *              and canonical, in either separator, spelt as the console spells it
     */
    static String refusalOf(String said, List<String> paths) {
        String last = null;
        for (String line : said.split("\r?\n")) {
            if (line.startsWith("wir-collect: ")) {
                last = line.substring("wir-collect: ".length());
            }
        }
        if (last == null) {
            return null;
        }
        Map<String, String> names = new java.util.TreeMap<>((a, b) -> a.length() != b.length()
                ? b.length() - a.length() : a.compareTo(b));   // longest first: a path before its own tail
        for (String path : paths) {
            if (path == null || path.isEmpty()) {
                continue;
            }
            String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
            List<String> forms = new ArrayList<>(Arrays.asList(path, new File(path).getAbsolutePath()));
            try {
                forms.add(new File(path).getCanonicalPath());
            } catch (IOException e) {
                // the other spellings still apply
            }
            for (String form : forms) {
                for (String spelt : Arrays.asList(form, form.replace('\\', '/'), form.replace('/', '\\'))) {
                    String ascii = WirCollect.ascii(spelt);
                    if (!ascii.equals(name)) {
                        names.put(ascii, WirCollect.ascii(name));
                    }
                }
            }
        }
        for (Map.Entry<String, String> e : names.entrySet()) {
            last = last.replace(e.getKey(), e.getValue());
        }
        return last;
    }

    /** True when the file's first non-blank character (after a BOM) opens a JSON object. */
    public static boolean looksLikeJson(File file) {
        try (java.io.Reader in = new java.io.InputStreamReader(new java.io.FileInputStream(file),
                java.nio.charset.StandardCharsets.UTF_8)) {
            int c;
            while ((c = in.read()) != -1) {
                if (c == 0xFEFF || Character.isWhitespace(c)) {
                    continue;
                }
                return c == '{';
            }
        } catch (IOException e) {
            return true;   // unreadable: let the loader say so, in its words
        }
        return true;
    }
}
