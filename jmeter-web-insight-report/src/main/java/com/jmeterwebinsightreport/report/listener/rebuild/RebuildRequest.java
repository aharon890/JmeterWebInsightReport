package com.jmeterwebinsightreport.report.listener.rebuild;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * What the listener panel's <b>Rebuild report with server data</b> button would rebuild, resolved from
 * the panel, the element's last run and JMeter's properties - or the sentence saying why it cannot.
 * No Swing: the panel hands its fields' text in, and shows {@link #statusLine()}, {@link #refusal()} and
 * {@link #warnings()} as they come back.
 *
 * <p>The rules, from the plan:
 * <ul>
 *   <li><b>L1 - a property beats the field.</b> {@code webinsight.report.servermetrics} (and
 *   {@code webinsight.report.annotations}), set when JMeter was launched, wins over the panel's field,
 *   exactly as {@code -Jwebinsight.report.title} beats Report Title. The panel never hides it: the
 *   status line says "Overridden by ...".</li>
 *   <li><b>L3 - the run's own inputs are the run's.</b> The JTL, the output directory, the report's
 *   filename and title, ramp-up, JUnit, external data, Apdex and an explicit baseline come from the
 *   {@link LastRun}, never re-evaluated: {@code results-${__time(HHmmss)}.jtl} evaluated at the click
 *   names a file that does not exist, and a title edited since the run would change the {@code runId}.
 *   With no run in this JMeter session the fields are read now, and a warning says so.</li>
 *   <li><b>L4/R4 - what the rebuild consumes is read at the click</b>: the server data and the
 *   annotations, the two reasons to click. A field unchanged since the run reads as the run read it (a
 *   test-plan variable included, which only the run could resolve); an edited one is evaluated now.</li>
 *   <li><b>L6 - a stale file is warned about, not guessed at</b>: server data saved before the run
 *   ended is probably the previous run's export. The button stays enabled; the collector's own
 *   outside-the-window refusal is the real check.</li>
 *   <li><b>R8 - never while a test runs</b>: the JTL is still being written.</li>
 * </ul>
 *
 * <p>A relative server data path is looked up where JMeter runs and then beside the JTL, as
 * {@code jmeter -g} looks it up, so the file the status line names is the file the rebuild reads.
 */
public final class RebuildRequest {

    /** The property that beats the Server data field (L1), and the one {@code jmeter -g} reads. */
    public static final String SERVER_DATA_PROPERTY = "webinsight.report.servermetrics";
    /** The property that beats the Annotations File field (L1). */
    public static final String ANNOTATIONS_PROPERTY = "webinsight.report.annotations";

    /**
     * With no {@link LastRun}: JMeter restarted, nothing has run yet, or the last run wrote no report -
     * its start forgets the run before it, so a failed run is never quietly rebuilt as the one before.
     */
    static final String NO_RUN = "No report from a run in this JMeter session (none has run since JMeter started,"
            + " or the last run wrote no report) - using the fields as they read now.";
    static final String NO_BODIES = "The rebuilt report is read from the results file, as jmeter -g reads it, so it"
            + " carries no response bodies: the Errors tab of the report the run wrote had them, this one will not.";
    /** The generator's filename token, and what stands in for it while the rest of the field is evaluated. */
    private static final String TIMESTAMP = "${timestamp}";
    private static final String TIMESTAMP_HELD = "wir-timestamp-held-for-the-generator";

    static final String RUNNING = "A test is running - its results file is still being written. Rebuild once"
            + " the test has ended.";

    /** The panel's fields as they read now, unsaved edits included: the button reads the panel, as Run does. */
    public static final class Panel {
        final String title;
        final String filename;
        final String outputDirectory;
        final String annotations;
        final String results;
        final String serverData;
        final boolean junit;

        public Panel(String title, String filename, String outputDirectory, String annotations, String results,
                     String serverData, boolean junit) {
            this.title = trim(title);
            this.filename = trim(filename);
            this.outputDirectory = trim(outputDirectory);
            this.annotations = trim(annotations);
            this.results = trim(results);
            this.serverData = trim(serverData);
            this.junit = junit;
        }
    }

    private final String refusal;
    private final boolean refusedOnServerData;
    private final String statusLine;
    private final boolean serverDataOverridden;
    private final List<String> warnings;
    private final LastRun lastRun;
    private final File jtl;
    private final File outputDirectory;
    private final String reportFilename;
    private final String title;
    private final long rampUpMillis;
    private final boolean junit;
    private final boolean externalData;
    private final String externalDataThreshold;
    private final String apdexThreshold;
    private final File baseline;
    private final File annotations;
    private final File serverData;

    private RebuildRequest(Builder b) {
        this.refusal = b.refusal;
        this.refusedOnServerData = b.refusedOnServerData;
        this.statusLine = b.statusLine;
        this.serverDataOverridden = b.serverDataOverridden;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(b.warnings));
        this.lastRun = b.lastRun;
        this.jtl = b.jtl;
        this.outputDirectory = b.outputDirectory;
        this.reportFilename = b.reportFilename;
        this.title = b.title;
        this.rampUpMillis = b.rampUpMillis;
        this.junit = b.junit;
        this.externalData = b.externalData;
        this.externalDataThreshold = b.externalDataThreshold;
        this.apdexThreshold = b.apdexThreshold;
        this.baseline = b.baseline;
        this.annotations = b.annotations;
        this.serverData = b.serverData;
    }

    /**
     * Resolve a rebuild with JMeter's own evaluator and the system time zone.
     *
     * @param panel        the panel's fields as they read now
     * @param lastRun      the element's last run in this JMeter session, or null
     * @param props        JMeter's properties ({@code JMeterUtils::getProperty})
     * @param running      whether a test is running now (R8)
     * @param baseRelative how a relative results file resolves, as ResultCollector resolves it
     *                     ({@code FileServer::resolveBaseRelativeName})
     */
    public static RebuildRequest resolve(Panel panel, LastRun lastRun, Function<String, String> props,
                                         boolean running, UnaryOperator<String> baseRelative) {
        return resolve(panel, lastRun, props, running, baseRelative, FieldValue::evaluate, ZoneId.systemDefault());
    }

    /** As {@link #resolve(Panel, LastRun, Function, boolean, UnaryOperator)}, with the evaluator and zone given. */
    public static RebuildRequest resolve(Panel panel, LastRun lastRun, Function<String, String> props,
                                         boolean running, UnaryOperator<String> baseRelative,
                                         Function<String, FieldValue> evaluate, ZoneId zone) {
        Builder b = new Builder();
        b.lastRun = lastRun;

        // The run's own inputs (L3), or the fields now with a warning that says so.
        String jtlRefusal = lastRun != null ? fromLastRun(b, lastRun, panel, evaluate, baseRelative)
                : fromFieldsNow(b, panel, props, evaluate, baseRelative);

        // What the rebuild consumes, read at the click (L1, L4, R4).
        String annotationsRefusal = annotations(b, panel, lastRun, props, evaluate);
        String serverDataRefusal = serverData(b, panel, lastRun, props, evaluate, zone);

        if (lastRun != null) {
            b.warnings.add(NO_BODIES);
        }

        // One refusal, the most fundamental first: the button's tooltip and the click's message.
        if (running) {
            b.refusal = RUNNING;
        } else if (jtlRefusal != null) {
            b.refusal = jtlRefusal;
        } else if (serverDataRefusal != null) {
            b.refusal = serverDataRefusal;
            b.refusedOnServerData = true;
        } else if (annotationsRefusal != null) {
            b.refusal = annotationsRefusal;
        }
        return new RebuildRequest(b);
    }

    /** L3: every input the run resolved. The panel's results field is read only when the run wrote no JTL. */
    private static String fromLastRun(Builder b, LastRun run, Panel panel, Function<String, FieldValue> evaluate,
                                      UnaryOperator<String> baseRelative) {
        b.outputDirectory = run.outputDirectory();
        b.reportFilename = run.htmlFilename();
        b.title = run.title();
        b.rampUpMillis = run.rampUpMillis();
        b.junit = run.junit();
        b.externalData = run.externalData();
        b.externalDataThreshold = String.valueOf(run.externalDataThreshold());
        b.apdexThreshold = run.apdexThreshold();
        b.baseline = run.baseline();
        if (run.jtl() != null) {
            b.jtl = run.jtl();
            return b.jtl.isFile() ? null
                    : "The results file the last run wrote, " + slashes(b.jtl) + ", is not there any more.";
        }
        if (panel.results.isEmpty()) {
            return "The last run wrote no results file: the Results file (JTL) field was empty. Set it and run the"
                    + " test again, or, for a JTL written by -l or another listener, use Tools > Generate HTML report.";
        }
        b.warnings.add("The last run wrote no results file; reading Results file (JTL) as it reads now.");
        return jtlFromField(b, panel, evaluate, baseRelative);
    }

    /** No run in this session: each input from its property, else its field evaluated now, as the listener reads them. */
    private static String fromFieldsNow(Builder b, Panel panel, Function<String, String> props,
                                        Function<String, FieldValue> evaluate, UnaryOperator<String> baseRelative) {
        b.warnings.add(NO_RUN);
        b.junit = parseBoolean(props.apply("webinsight.report.junit"), panel.junit);
        b.externalData = parseBoolean(props.apply("webinsight.report.externaldata"), false);
        b.externalDataThreshold = props.apply("webinsight.report.externaldata.threshold");
        b.apdexThreshold = props.apply("webinsight.apdex.threshold");
        String baseline = props.apply("webinsight.report.baseline");
        b.baseline = isBlank(baseline) ? null : new File(baseline.trim());
        String rampUp = props.apply("webinsight.report.rampup");
        if (!isBlank(rampUp)) {
            try {
                b.rampUpMillis = Math.max(0, Long.parseLong(rampUp.trim()));
            } catch (NumberFormatException e) {
                b.warnings.add("webinsight.report.rampup = " + rampUp + " is not a whole number of milliseconds,"
                        + " so no ramp-up is excluded.");
            }
        }

        String refusal = jtlFromField(b, panel, evaluate, baseRelative);

        FieldValue title = propertyOrField(props, "webinsight.report.title", panel.title, evaluate);
        if (title.isRefused() && refusal == null) {
            refusal = "Report Title: " + title.refusal();
        }
        b.title = title.value().isEmpty() ? "JMeter Web Insight Report" : title.value();

        FieldValue filename = reportFilename(props, panel.filename, evaluate);
        if (filename.isRefused() && refusal == null) {
            refusal = "Report Filename: " + filename.refusal();
        }
        b.reportFilename = filename.value().isEmpty() ? null : filename.value();

        FieldValue output = propertyOrField(props, "webinsight.report.output", panel.outputDirectory, evaluate);
        if (output.isRefused() && refusal == null) {
            refusal = "Output Directory: " + output.refusal();
        }
        if (!output.value().isEmpty()) {
            b.outputDirectory = new File(output.value()).getAbsoluteFile();
        } else if (b.jtl != null) {
            // Where the listener's default puts the report first: beside the results file it writes.
            b.outputDirectory = b.jtl.getAbsoluteFile().getParentFile();
        }
        return refusal;
    }

    private static String jtlFromField(Builder b, Panel panel, Function<String, FieldValue> evaluate,
                                       UnaryOperator<String> baseRelative) {
        if (panel.results.isEmpty()) {
            return "No results file: set Results file (JTL) and run the test - Rebuild reads the JTL that run"
                    + " writes. For a JTL written by -l or another listener, use Tools > Generate HTML report.";
        }
        FieldValue results = evaluate.apply(panel.results);
        if (results.isRefused()) {
            return "Results file (JTL): " + results.refusal();
        }
        b.jtl = new File(baseRelative.apply(results.value())).getAbsoluteFile();
        return b.jtl.isFile() ? null
                : "The results file " + slashes(b.jtl) + " does not exist - it is written while the test runs, so"
                + " run the test first.";
    }

    /** L1, then R4: the property, else the field as the run read it when unchanged, else evaluated now. */
    private static String annotations(Builder b, Panel panel, LastRun run, Function<String, String> props,
                                      Function<String, FieldValue> evaluate) {
        String property = props.apply(ANNOTATIONS_PROPERTY);
        if (!isBlank(property)) {
            b.annotations = new File(property.trim());
            if (!panel.annotations.isEmpty()) {
                b.warnings.add(ANNOTATIONS_PROPERTY + " = " + property.trim()
                        + " overrides the Annotations File field.");
            }
            return null;
        }
        FieldValue field = run != null && panel.annotations.equals(run.annotationsRaw())
                ? FieldValue.resolved(run.annotationsResolved())
                : evaluate.apply(panel.annotations);
        if (field.isRefused()) {
            return "Annotations File: " + field.refusal();
        }
        // Empty: the rebuild auto-detects report-annotations.json in the output directory, as the run did.
        b.annotations = field.value().isEmpty() ? null : new File(field.value());
        if (field.wasEvaluated()) {
            b.warnings.add(evaluatedNow("Annotations File"));
        }
        return null;
    }

    /** L1, R4 and L6 for the server data, and the status line under its field. */
    private static String serverData(Builder b, Panel panel, LastRun run, Function<String, String> props,
                                     Function<String, FieldValue> evaluate, ZoneId zone) {
        String property = props.apply(SERVER_DATA_PROPERTY);
        boolean overridden = !isBlank(property);
        FieldValue field;
        if (overridden) {
            // A property is plain text: JMeter evaluates no function in a -J value, and neither does this.
            field = FieldValue.resolved(property);
            b.serverDataOverridden = true;
            if (!panel.serverData.isEmpty()) {
                b.warnings.add(SERVER_DATA_PROPERTY + " = " + property.trim() + " overrides the Server data (CSV) field.");
            }
        } else if (run != null && panel.serverData.equals(run.serverDataRaw())) {
            field = FieldValue.resolved(run.serverDataResolved());
        } else {
            field = evaluate.apply(panel.serverData);
        }

        String prefix = overridden ? "Overridden by " + SERVER_DATA_PROPERTY + " = " + property.trim()
                + " - this is the file Rebuild reads" : null;
        if (field.isRefused()) {
            String refusal = "Server data (CSV): " + field.refusal();
            b.statusLine = refusal;
            return refusal;
        }
        if (field.value().isEmpty()) {
            b.statusLine = "Name the CSV your export tool wrote for this run, or a server-metrics.json.";
            return "No server data: name the CSV (or a server-metrics.json) in Server data (CSV).";
        }

        File file = locate(field.value(), b.jtl);
        b.serverData = file;
        StringBuilder line = new StringBuilder(prefix != null ? prefix : "Resolves to " + slashes(file));
        String refusal = null;
        if (file.isDirectory()) {
            line.append(" · a folder, not a file");
            refusal = "The server data " + slashes(file) + " is a folder - name the CSV file in it.";
        } else if (!file.isFile()) {
            line.append(" · not found");
            refusal = "The server data " + slashes(file) + " does not exist - export it first: the run's NEXT line"
                    + " and the report's Server tab say what to export.";
        } else {
            line.append(" · found, ").append(size(file.length()));
            if (run != null && file.lastModified() < run.endedAt()) {
                String stale = "saved " + time(file.lastModified(), run.endedAt(), zone) + ", before this run ended ("
                        + time(run.endedAt(), file.lastModified(), zone) + ") - the previous run's export?";
                line.append(" · ").append(stale);
                b.warnings.add("Server data " + stale);
            }
        }
        if (field.wasEvaluated()) {
            line.append(" · evaluated now, not when the run started");
            b.warnings.add(evaluatedNow("Server data (CSV)"));
        }
        b.statusLine = line.toString();
        return refusal;
    }

    /**
     * The file a server data path names: as given when it exists or is absolute, else beside the JTL when
     * it is there - the order {@code jmeter -g} looks in.
     */
    static File locate(String path, File jtl) {
        File given = new File(path);
        if (!given.exists() && !given.isAbsolute() && jtl != null && jtl.getAbsoluteFile().getParentFile() != null) {
            File besideJtl = new File(jtl.getAbsoluteFile().getParentFile(), path);
            if (besideJtl.exists()) {
                return besideJtl.getAbsoluteFile();
            }
        }
        return given.getAbsoluteFile();
    }

    private static FieldValue propertyOrField(Function<String, String> props, String property, String field,
                                              Function<String, FieldValue> evaluate) {
        String value = props.apply(property);
        return isBlank(value) ? evaluate.apply(field) : FieldValue.resolved(value);
    }

    /**
     * The Report Filename as the listener reads it: the property, else the field evaluated - with the
     * generator's own {@code ${timestamp}} left for the generator to stamp. JMeter leaves that token
     * alone at test start (no variable of that name exists), so every run's name carries it to
     * {@code WebInsightReportGenerator}; evaluated here unguarded it was refused as a test-plan
     * variable, with advice ({@code ${__P(timestamp)}}) that would have broken the per-run name the
     * README documents. It is held out of the evaluation under a stand-in no function reads, and put
     * back into the value and into any refusal of the rest of the field.
     */
    private static FieldValue reportFilename(Function<String, String> props, String field,
                                             Function<String, FieldValue> evaluate) {
        String property = props.apply("webinsight.report.filename");
        if (!isBlank(property)) {
            return FieldValue.resolved(property);
        }
        if (!field.contains(TIMESTAMP)) {
            return evaluate.apply(field);
        }
        return evaluate.apply(field.replace(TIMESTAMP, TIMESTAMP_HELD))
                .map(text -> text.replace(TIMESTAMP_HELD, TIMESTAMP));
    }

    private static String evaluatedNow(String field) {
        return field + " was evaluated now: a function such as __time reads the time of this click, not of the run.";
    }

    /** HH:mm, with the date in front when the two moments fall on different days. */
    private static String time(long millis, long other, ZoneId zone) {
        ZonedDateTime at = Instant.ofEpochMilli(millis).atZone(zone);
        LocalDate otherDay = Instant.ofEpochMilli(other).atZone(zone).toLocalDate();
        return at.format(DateTimeFormatter.ofPattern(at.toLocalDate().equals(otherDay) ? "HH:mm" : "yyyy-MM-dd HH:mm"));
    }

    /** 2.1 MB, 340 KB, 12 bytes. */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + (bytes == 1 ? " byte" : " bytes");
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        // Locale.ROOT: a German JMeter would otherwise write "2,1 MB" beside paths written with "/".
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0)) + " MB";
    }

    static String slashes(File file) {
        return file.getPath().replace(File.separatorChar, '/');
    }

    private static boolean parseBoolean(String value, boolean otherwise) {
        if (value != null && ("true".equalsIgnoreCase(value.trim()) || "false".equalsIgnoreCase(value.trim()))) {
            return Boolean.parseBoolean(value.trim());
        }
        return otherwise;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ---- what came out ----

    /** Why the button cannot rebuild now, or null when it can. */
    public String refusal() {
        return refusal;
    }

    public boolean isRefused() {
        return refusal != null;
    }

    /**
     * True when the refusal is about the server data, which {@link #statusLine()} already says; false
     * when it is about something else (a running test, the JTL, the annotations), which the panel then
     * shows under the status line.
     */
    public boolean refusedOnServerData() {
        return refusedOnServerData;
    }

    /**
     * The line under the Server data field: "Resolves to ... · found, 2.1 MB", "... · not found",
     * "Overridden by webinsight.report.servermetrics = ...", the field's refusal, the stale warning.
     */
    public String statusLine() {
        return statusLine;
    }

    /** True when {@code webinsight.report.servermetrics} decides the file and the field is not read (L1). */
    public boolean serverDataOverridden() {
        return serverDataOverridden;
    }

    /** Everything the result dialog says before what the rebuild said: the no-run note, overrides, stale file, R5. */
    public List<String> warnings() {
        return warnings;
    }

    /** The run this rebuilds, or null when there was none in this session. */
    public LastRun lastRun() {
        return lastRun;
    }

    public File jtl() {
        return jtl;
    }

    public File outputDirectory() {
        return outputDirectory;
    }

    /** The report's filename: the run's, already stamped, so the rebuild overwrites it; or the configured one. */
    public String reportFilename() {
        return reportFilename;
    }

    public String title() {
        return title;
    }

    public long rampUpMillis() {
        return rampUpMillis;
    }

    public boolean junit() {
        return junit;
    }

    public boolean externalData() {
        return externalData;
    }

    /** {@code webinsight.report.externaldata.threshold}'s text, or null for the default. */
    public String externalDataThreshold() {
        return externalDataThreshold;
    }

    public String apdexThreshold() {
        return apdexThreshold;
    }

    public File baseline() {
        return baseline;
    }

    /** The annotations file, or null: then one is auto-detected in the output directory. */
    public File annotations() {
        return annotations;
    }

    public File serverData() {
        return serverData;
    }

    private static final class Builder {
        String refusal;
        boolean refusedOnServerData;
        String statusLine = "";
        boolean serverDataOverridden;
        final List<String> warnings = new ArrayList<>();
        LastRun lastRun;
        File jtl;
        File outputDirectory;
        String reportFilename;
        String title;
        long rampUpMillis;
        boolean junit;
        boolean externalData;
        String externalDataThreshold;
        String apdexThreshold;
        File baseline;
        File annotations;
        File serverData;
    }
}
