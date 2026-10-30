package com.jmeterwebinsightreport.report.listener;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import com.jmeterwebinsightreport.report.exporter.WebInsightDataExporter;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.report.generator.ExitPolicy;
import com.jmeterwebinsightreport.report.generator.NonZeroExit;
import com.jmeterwebinsightreport.report.generator.BooleanSetting;
import com.jmeterwebinsightreport.report.generator.OutputDirectories;
import com.jmeterwebinsightreport.report.generator.PropertyCheck;
import com.jmeterwebinsightreport.report.generator.ReportArtifacts;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.listener.rebuild.LastRun;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JMeter Listener that collects sample results during test execution and generates
 * the Web Insight Report at test end.
 */
public class WebInsightReportListener extends ResultCollector {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(WebInsightReportListener.class);

    public static final String REPORT_TITLE = "WebInsightReport.reportTitle";
    public static final String REPORT_OUTPUT_DIR = "WebInsightReport.outputDirectory";
    public static final String REPORT_FILENAME = "WebInsightReport.reportFilename";
    public static final String GENERATE_JUNIT_XML = "WebInsightReport.generateJunitXml";

    /** The annotations file: the asserts, markers and notes this run is judged against. */
    public static final String ANNOTATIONS_FILE = "WebInsightReport.annotationsFile";

    /**
     * The server data the panel's <b>Rebuild report with server data</b> button reads: a CSV, or a
     * {@code server-metrics.json} payload. Read only by that button (and named by the NEXT line as the
     * file to write). The run itself never collects it: when the run ends, the data does not exist yet
     * (L2), and {@code jmeter -g} cannot see a {@code .jmx} field, so a path saved here can never reach
     * or override a pipeline's {@code -g}.
     */
    public static final String SERVER_DATA_FILE = "WebInsightReport.serverDataFile";

    /**
     * Runs with a report still to come, across every Web Insight listener in this JVM: incremented
     * when a run's first host starts, decremented once its last host ended and the report is written
     * (or was not). Half of the panel's "is a test running" (R8); {@code JMeterContextService}'s start
     * time is the other half, and reaches 0 only once every results file is closed.
     */
    private static final AtomicInteger RUNS_IN_FLIGHT = new AtomicInteger();

    /**
     * The element this one was cloned from - its <b>direct</b> source, never followed further.
     *
     * <p>A GUI Start runs on clones: {@code Start.cloneTree} uses {@code new TreeCloner(false)}, which
     * clones {@code NoThreadClone} elements too, so {@code testStarted}/{@code testEnded} never run on
     * the element the panel edits (R1). The run-clone publishes its {@link LastRun} through this link
     * into the element the panel shows. Never chained: a pasted copy is a clone too, and its run must
     * land on the copy, not on the element it was copied from.
     *
     * <p>Weak, so a finished run's clone cannot keep a deleted element alive, and an identity link,
     * not a map key: {@code AbstractTestElement}'s equals and hashCode are property-based, so two
     * listeners configured alike are equal.
     */
    private transient WeakReference<WebInsightReportListener> source;

    /** What this element's last run resolved, published once by its run-clone (or itself under -n). */
    private transient volatile LastRun lastRun;

    /**
     * {@link #getFilename()} as this run resolved it. A running clone's property is a function JMeter
     * re-executes on every read, so {@code results-${__time(HHmmss)}.jtl} named one file when
     * ResultCollector opened it and another at the NEXT line (R2). Cleared when a run starts, before
     * {@code super.testStarted} opens the file through it; set only while running.
     */
    private transient volatile String filenameThisRun;

    /** Whether this element counted a run in {@link #RUNS_IN_FLIGHT} that it has not yet uncounted. */
    private transient boolean countedInFlight;

    /** The two fields the Rebuild button reads, as this run was started with them and as it resolved them (R4). */
    private transient String serverDataRaw;
    private transient String serverDataResolved;
    private transient String annotationsRaw;
    private transient String annotationsResolved;

    /** Written on the test-start thread under {@code synchronized (this)}, read by every
     *  sampler thread (and by RMI worker threads in distributed mode) in
     *  {@link #sampleOccurred}. Volatile so those reads see the initialised engine
     *  instead of null — a sample seen through a stale reference is dropped silently. */
    private transient volatile LiveStatisticsEngine statsEngine;
    private transient volatile ReportConfiguration configuration;
    private transient long testStartTime;
    /** Number of started-but-not-ended hosts. In distributed mode the controller calls
     *  testStarted/testEnded once per remote host on this single listener instance; we create the
     *  engine only on the first start and generate the report only after the last host ends. */
    private transient int activeHostCount;
    /** So the "a stuck host means no report" caution is logged once, not per deferral. */
    private transient boolean deferralWarningLogged;

    public WebInsightReportListener() {
        super();
    }

    /** A clone that knows its direct source, so its run can tell the panel what it resolved. See {@link #source}. */
    @Override
    public Object clone() {
        WebInsightReportListener copy = (WebInsightReportListener) super.clone();
        copy.source = new WeakReference<>(this);
        return copy;
    }

    /** The last run of this element in this JMeter session, or null - what the Rebuild button rebuilds. */
    public LastRun getLastRun() {
        return lastRun;
    }

    /**
     * Runs whose report is still to come, in this JVM. The panel refuses to rebuild while it is above
     * zero: the run's results file is still being written.
     */
    public static int runsInFlight() {
        return RUNS_IN_FLIGHT.get();
    }

    /** Memoised while running: see {@link #filenameThisRun}. */
    @Override
    public String getFilename() {
        String memo = filenameThisRun;
        if (memo != null) {
            return memo;
        }
        String name = super.getFilename();
        if (isRunningVersion()) {
            filenameThisRun = name;
        }
        return name;
    }

    @Override
    public void testStarted() {
        testStarted("");
    }

    @Override
    public void testStarted(String host) {
        synchronized (this) {
            if (activeHostCount == 0) {
                // Before super.testStarted, which opens the results file through getFilename(): from
                // here the name is read once and kept, so the JTL, the NEXT line and LastRun agree.
                filenameThisRun = null;
            }
        }
        super.testStarted(host);
        synchronized (this) {
            if (activeHostCount == 0) {
                log.info("Web Insight Report Listener: test started");
                if (!countedInFlight) {
                    countedInFlight = true;
                    RUNS_IN_FLIGHT.incrementAndGet();
                }
                testStartTime = System.currentTimeMillis();
                // The panel's element forgets the previous run now, not when this one's report is
                // written: a run whose report then fails never publishes a LastRun, and the one
                // before it would stay - so Rebuild would quietly rebuild that older run (its JTL,
                // its folder, its title) over its own report, judged fresh against its own end time,
                // and a runId check that passes because the older JTL really is the older run. With
                // nothing remembered the button reads the fields as they are now, and says so.
                panelElement().lastRun = null;
                // A new run, so forget which paths the previous one wrote. Here and not on
                // every testStarted: in distributed mode this method fires once per remote
                // host on this single instance, and clearing per host would disarm the guard
                // in the only mode where two writers are genuinely likely.
                WebInsightReportGenerator.beginTestRun();
                // Publish last, and publish HERE. Sampler threads read statsEngine without
                // holding this lock, so it must only become visible once fully configured —
                // and keeping the assignment out of the builder means the builder's body
                // never names the field at all. See createConfiguredEngine(). The configuration
                // the same way, and first: it is read lock-free too (RPT-13).
                ReportConfiguration config = new ReportConfiguration();
                LiveStatisticsEngine engine = createConfiguredEngine(config);
                // The two fields the panel's Rebuild button reads at the click, kept as this run
                // read them: an unchanged field then reads as the run read it (R4) - a test-plan
                // variable included, which resolves here on the engine thread and nowhere after.
                serverDataRaw = rawText(SERVER_DATA_FILE);
                serverDataResolved = getPropertyAsString(SERVER_DATA_FILE);
                annotationsRaw = rawText(ANNOTATIONS_FILE);
                annotationsResolved = getPropertyAsString(ANNOTATIONS_FILE);
                configuration = config;
                statsEngine = engine;
            } else {
                log.info("Web Insight Report Listener: additional host started (host={}), {} now active",
                        host, activeHostCount + 1);
            }
            activeHostCount++;
        }
    }

    /**
     * Build the stats engine and read configuration from properties. Called once per test run
     * (on the first host's testStarted).
     *
     * <p>This RETURNS the engine instead of assigning {@link #statsEngine}, and that is the
     * whole point: the field must not become visible to sampler threads until configuration
     * is complete, so nothing in here may touch it. When the assignment lived at the end of
     * this method, a line added in the middle that reached through the field instead of the
     * local compiled fine, merged without conflict, and then threw NullPointerException at
     * run time — out of a catch clause that only handled NumberFormatException, on every run
     * that set the ramp-up property. Returning the engine removes the field from this scope
     * entirely, so there is nothing to reach for by mistake.</p>
     *
     * <p>The {@link #configuration} field gets the same treatment, for the same reason: it is
     * volatile and read lock-free as well. It used to be assigned a new, empty configuration here
     * and then populated, so a reader between the two saw a default title and no output
     * directory (RPT-13). The caller hands in a local, fills nothing else, and publishes it once
     * this returns.</p>
     *
     * @param config the configuration to populate — the caller's local, never the field
     */
    private LiveStatisticsEngine createConfiguredEngine(ReportConfiguration config) {
        LiveStatisticsEngine engine = new LiveStatisticsEngine();

        // Configurable response body truncation limit. A negative value used to parse fine
        // and then blow up inside recordError for every failed sample, silently losing all
        // error details — reject it and keep the default instead.
        String maxBodyStr = getJMeterProperty("webinsight.error.body.maxsize");
        if (maxBodyStr != null && !maxBodyStr.isEmpty()) {
            try {
                engine.setMaxResponseBodyLength(Integer.parseInt(maxBodyStr));
            } catch (IllegalArgumentException e) {   // includes NumberFormatException
                log.warn("Ignoring invalid webinsight.error.body.maxsize '{}' ({}) — keeping default",
                        maxBodyStr, e.getMessage());
            }
        }

        // Apdex threshold (default 500ms)
        String apdexStr = getJMeterProperty("webinsight.apdex.threshold");
        if (apdexStr != null && !apdexStr.isEmpty()) {
            try {
                engine.setApdexThreshold(Double.parseDouble(apdexStr));
            } catch (NumberFormatException e) {
                log.warn("Invalid webinsight.apdex.threshold: {}", apdexStr);
            }
        }

        // CLI properties (-J) take precedence over GUI fields
        String title = getJMeterProperty("webinsight.report.title");
        if (title == null || title.isEmpty()) {
            title = getPropertyAsString(REPORT_TITLE);
        }
        if (title == null || title.isEmpty()) {
            title = "JMeter Web Insight Report";
        }
        config.setReportTitle(title);

        String outputDir = getJMeterProperty("webinsight.report.output");
        if (outputDir == null || outputDir.isEmpty()) {
            outputDir = getPropertyAsString(REPORT_OUTPUT_DIR);
        }
        if (outputDir != null && !outputDir.isEmpty()) {
            config.setOutputDirectory(new File(outputDir));
        }
        // else: null means use working directory (resolved at generation time)

        String filename = getJMeterProperty("webinsight.report.filename");
        if (filename == null || filename.isEmpty()) {
            filename = getPropertyAsString(REPORT_FILENAME);
        }
        if (filename != null && !filename.isEmpty()) {
            config.setReportFilename(filename);
        }

        // Baseline JTL for the comparison tab. Without this, comparison only happened when a
        // file literally named baseline.jtl sat in the output directory, even though
        // FEATURES.md documents the property for listener mode too (E32).
        String baseline = getJMeterProperty("webinsight.report.baseline");
        if (baseline != null && !baseline.isEmpty()) {
            config.setBaselineJtlFile(new File(baseline));
        }

        // Annotations file (assertions, timeline markers, notes).
        //
        // -J first, then the GUI field, matching every other setting here. The GUI field is new:
        // until it existed there was no way at all to point a GUI run at an asserts file, so
        // "author in JSON or in the GUI" was true only for someone driving JMeter from a command
        // line. A tester with the listener open in front of them could declare nothing.
        String annotations = getJMeterProperty("webinsight.report.annotations");
        if (annotations == null || annotations.isEmpty()) {
            annotations = getPropertyAsString(ANNOTATIONS_FILE);
        }
        if (annotations != null && !annotations.isEmpty()) {
            config.setAnnotationsFile(new File(annotations));
        }

        // The server data is for the -g step, and only for it: the listener writes its report when
        // the run ends, before any server data for this run can exist. It used to read the
        // property and refuse what it named as another run's - so one run.properties shared by
        // both commands (the documented way: no -J to survive a shell) made every interim report
        // read REFUSED instead of "Add the server data", with no NEXT line. Ignored here, and said.
        // The panel's Rebuild button reads it (it beats the Server data field), after the run.
        String serverMetrics = getJMeterProperty("webinsight.report.servermetrics");
        if (serverMetrics != null && !serverMetrics.isEmpty()) {
            log.info("webinsight.report.servermetrics ({}) is for the jmeter -g step and the listener's Rebuild"
                    + " report with server data button: this report is written when the run ends, before the"
                    + " server data exists", serverMetrics);
        }

        // Ramp-up overlay. The engine owns this in listener mode — the exporter's
        // config.rampUpDurationMillis path is only read by the -g -o parser, so setting it
        // here alone would leave the overlay impossible to produce from the listener.
        String rampUp = getJMeterProperty("webinsight.report.rampup");
        if (rampUp != null && !rampUp.isEmpty()) {
            try {
                long rampUpMillis = Long.parseLong(rampUp.trim());
                if (rampUpMillis < 0) {
                    throw new NumberFormatException("must be >= 0");
                }
                config.setRampUpDurationMillis(rampUpMillis);
                // The local `engine`, not the field: the field is published last (below) so it
                // is still null here. Reaching through it would throw NPE — and out of the
                // NumberFormatException catch — on every run that sets the ramp-up property.
                engine.setRampUpDurationMillis(rampUpMillis);
            } catch (NumberFormatException e) {
                log.warn("Ignoring invalid webinsight.report.rampup '{}' ({})", rampUp, e.getMessage());
            }
        }

        // External data mode. Without a property this was only settable programmatically, so
        // the streaming write path could not be reached — or tested — from a real run.
        // No GUI field for this one, so "unset" means off.
        String externalProp = getJMeterProperty("webinsight.report.externaldata");
        warnIfNotBoolean("webinsight.report.externaldata", externalProp);
        config.setExternalDataMode(BooleanSetting.parse(externalProp).orElse(false));

        // The threshold was documented as configurable and was not: setExternalDataThreshold had
        // zero call sites anywhere in either module, so the 10 MB switch point could not be
        // tuned. Rejecting <= 0 the same way the error-body limit rejects negatives, because a
        // zero or negative threshold would force external mode for every run including empty
        // ones.
        String thresholdWarning = config.applyExternalDataThreshold(
                getJMeterProperty("webinsight.report.externaldata.threshold"));
        if (thresholdWarning != null) {
            log.warn(thresholdWarning);
        }

        // JUnit XML generation — CLI property takes precedence over GUI checkbox.
        //
        // The resolution used to have no else branch: only "true" enabled it, and only
        // null/empty consulted the checkbox. So -Jwebinsight.report.junit=yes matched neither,
        // and a typo silently suppressed a ticked box with no message anywhere. An unrecognised
        // value now warns and falls through to the box, which is what "this told us nothing"
        // ought to mean.
        String junitProp = getJMeterProperty("webinsight.report.junit");
        warnIfNotBoolean("webinsight.report.junit", junitProp);
        config.setGenerateJunitXml(BooleanSetting.parse(junitProp)
                .orElse(getPropertyAsBoolean(GENERATE_JUNIT_XML, false)));

        return engine;
    }

    /**
     * Where the report goes when nobody configured a directory.
     *
     * <p>It was the process working directory, full stop. In GUI mode that is normally
     * {@code $JMETER_HOME/bin}, which is read-only on most managed or packaged installs, and in
     * a container it may not be writable by the run user — so the documented default failed in
     * two of the plugin's most common environments. A default that commonly fails is not a
     * default.
     *
     * <p>The order: beside the results file this collector writes, then the working directory,
     * then beside the test plan, then a folder under their home, then system temp. Each candidate
     * is probed with a real write, and the chosen one is announced — a location that can move
     * between runs is only acceptable if you can find out where it went.
     *
     * <p><b>The results file outranks the working directory and the test plan does not</b>, which
     * looks arbitrary and is the whole point: a results filename is the operator naming a place
     * for this run's output, while the {@code .jmx}'s directory is only where a file happened to
     * be opened from. Ranking the plan above the working directory put reports next to the plans
     * — {@code test-plans/} in this repository, which is what A10 caught. See
     * {@link OutputDirectories#candidates(List, List)}.
     *
     * <p>This applies to the <b>default only</b>. An explicitly configured directory is never
     * relocated; it is allowed to fail.
     */
    private File defaultOutputDirectory() {
        List<File> preferred = new ArrayList<>(1);
        List<File> secondary = new ArrayList<>(1);

        // The results file, if this collector has one. Same object JMeter writes the JTL to, so
        // it is by definition a directory this run can already write into.
        //
        // This is the listener's OWN filename field, not the global `-l`: JMeter services `-l`
        // with a ResultCollector of its own rather than by configuring the ones in the plan, so
        // this is empty unless somebody filled the field in. That is why the working directory
        // still wins in the ordinary case, and why A10 — which configures nothing — is unmoved
        // by the results file being promoted above it.
        try {
            String jtl = getFilename();
            if (jtl != null && !jtl.isEmpty()) {
                File parent = new File(jtl).getAbsoluteFile().getParentFile();
                if (parent != null) {
                    preferred.add(parent);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read the results filename: {}", e.getMessage());
        }

        // The directory the .jmx was loaded from — after the working directory, deliberately.
        try {
            String base = org.apache.jmeter.services.FileServer.getFileServer().getBaseDir();
            if (base != null && !base.isEmpty()) {
                secondary.add(new File(base));
            }
        } catch (Exception e) {
            // FileServer is not initialised in every embedding context.
            log.debug("Could not read the test plan directory: {}", e.getMessage());
        }

        File resolved = OutputDirectories.resolveDefault(preferred, secondary);
        log.info("No output directory configured — writing the report to {} "
                        + "(set -Jwebinsight.report.output or the listener's Output Directory "
                        + "field to choose)",
                resolved.getAbsolutePath());
        return resolved;
    }

    /** @see BooleanSetting#warningFor */
    private void warnIfNotBoolean(String propertyName, String raw) {
        String warning = BooleanSetting.warningFor(propertyName, raw);
        if (warning != null) {
            log.warn(warning);
        }
    }

    private String getJMeterProperty(String name) {
        try {
            return JMeterUtils.getProperty(name);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void sampleOccurred(SampleEvent event) {
        super.sampleOccurred(event);
        SampleResult result = event.getResult();

        // Read the volatile once: a check-then-act on the field could see it non-null here
        // and null again inside processSampleResult if the run is torn down concurrently.
        LiveStatisticsEngine engine = statsEngine;
        if (engine != null) {
            processSampleResult(engine, result);
        }
    }

    /**
     * Process a single SampleResult and recursively process any sub-results.
     * Transaction Controllers with parent=true wrap child samples as sub-results.
     */
    private void processSampleResult(LiveStatisticsEngine statsEngine, SampleResult result) {
        // The sample's start, whatever sampleresult.timestamp.start says: getTimeStamp() is its
        // END when that property is false, and every sample was then placed and windowed by its
        // end (S46: duration 3 m 10 s against JMeter's 3 m 4 s, B3). JMeter's Aggregate Report
        // reads getStartTime() too. A result that never set one keeps its time stamp.
        long start = result.getStartTime() > 0 ? result.getStartTime() : result.getTimeStamp();
        // And its real end: a Transaction Controller's parent sample with "include timers" off
        // counts only its children's time as elapsed and its pauses as idle time, and still ends
        // after them. Its row's throughput window stopped at start + elapsed (P11: 0.86/s on the
        // transaction row, 0.76/s on the Summary tile for the same 50 transactions, B7).
        long end = Math.max(result.getEndTime(), start + Math.max(0, result.getTime()));
        statsEngine.recordSample(
                result.getSampleLabel(),
                result.getTime(),
                result.isSuccessful(),
                result.getBytesAsLong(),
                result.getSentBytes(),
                start,
                end,
                result.getConnectTime(),
                result.getLatency(),
                // 1 and 0/1 for every sample but one JMeter aggregated (Statistical mode), which
                // stands for that many, that many failed (B6)
                result.getSampleCount(),
                result.getErrorCount()
        );

        // Track active threads — total across all thread groups, not just this sampler's group.
        int activeThreads = result.getAllThreads();
        if (activeThreads <= 0) {
            activeThreads = result.getGroupThreads();
        }
        statsEngine.updateActiveThreads(activeThreads, start);
        if (LiveStatisticsEngine.isTransactionRow(result.getResponseMessage())) {
            // Left out of the Errors tab, as JMeter's HTML report leaves it out (E3)
            statsEngine.recordTransactionRow(result.getSampleCount());
        }

        // Record error details with URL and response body
        if (!result.isSuccessful()) {
            String url = null;
            String responseBody = null;
            try {
                url = result.getUrlAsString();
            } catch (Exception e) { /* ignore */ }
            try {
                responseBody = result.getResponseDataAsString();
            } catch (Exception e) { /* ignore */ }
            statsEngine.recordError(
                    result.getSampleLabel(),
                    result.getResponseCode(),
                    result.getResponseMessage(),
                    // what a results file's failureMessage column holds (CSVSaveService) (E1)
                    result.getFirstAssertionFailureMessage(),
                    start,
                    result.getThreadName(),
                    url,
                    null,
                    responseBody
            );
        }

        // Process sub-results (Transaction Controller children / embedded resources / redirect
        // hops). Record the REAL parent->child relationship so transaction grouping is based on
        // the actual result tree, not guessed from sampler name prefixes.
        //
        // Nesting applies to every sub-result: the parent's elapsed time already includes them,
        // so counting them again at top level would inflate the headline totals. But only a
        // sample that genuinely came from a Transaction Controller is reported as one —
        // otherwise a plain HTTP sampler with "Retrieve all embedded resources" showed up in
        // the report as a Transaction Controller.
        SampleResult[] subResults = result.getSubResults();
        if (subResults != null && subResults.length > 0) {
            String parentLabel = result.getSampleLabel();
            boolean fromTransactionController = TransactionController.isFromTransactionController(result);
            for (SampleResult sub : subResults) {
                statsEngine.recordTransaction(parentLabel, sub.getSampleLabel(), fromTransactionController);
                processSampleResult(statsEngine, sub);
            }
        }
    }

    @Override
    public void testEnded() {
        testEnded("");
    }

    @Override
    public void testEnded(String host) {
        super.testEnded(host);
        synchronized (this) {
            if (activeHostCount > 0) {
                activeHostCount--;
            }
            if (activeHostCount > 0) {
                log.info("Web Insight Report Listener: host {} ended, {} still running — deferring report",
                        host, activeHostCount);
                // A worker that dies without delivering testEnded (JVM crash, dropped RMI
                // connection) leaves the count above zero forever, so hours of collected
                // samples produce NO report at all. Say so once, at WARN, so an operator can
                // tell a normal wait from a permanently stuck one — but not on every
                // deferral, which is ordinary in a healthy multi-host run.
                if (!deferralWarningLogged) {
                    deferralWarningLogged = true;
                    log.warn("Web Insight Report Listener: waiting on {} more host(s). If a "
                            + "worker crashes or its RMI connection drops, its testEnded never "
                            + "arrives and no report is generated for this run.", activeHostCount);
                }
                return; // generate only after the last host ends
            }
        }
        // The run is out of flight once its report is written or has failed to be, whichever way
        // reportAtEnd leaves: the panel's Rebuild must not wait on a run that returned early.
        try {
            reportAtEnd();
        } finally {
            synchronized (this) {
                if (countedInFlight) {
                    countedInFlight = false;
                    RUNS_IN_FLIGHT.decrementAndGet();
                }
            }
        }
    }

    /** The report, once the last host has ended. */
    private void reportAtEnd() {
        log.info("Web Insight Report Listener: test ended, generating report");

        // Same check as the exporter's: a setting under a name nothing reads is said, and the
        // PowerShell -J split is a config error like any other.
        String propertyError = PropertyCheck.emit(
                PropertyCheck.check(JMeterUtils.getJMeterProperties()), log::warn);
        if (propertyError != null && failOnError()) {
            failTheRun(NonZeroExit.FAIL_ON_ERROR, new IllegalStateException(propertyError));
        }

        if (statsEngine == null) {
            log.warn("Statistics engine is null, skipping report generation");
            return;
        }

        try {
            String title = configuration.getReportTitle();
            ReportData reportData = statsEngine.buildReportData(title);

            // Set JMeter version
            try {
                String version = JMeterUtils.getJMeterVersion();
                if (version != null) {
                    reportData.getMetadata().setJmeterVersion(version);
                }
            } catch (Exception e) {
                // JMeterUtils may not be available in all contexts
            }

            // Resolve the output directory. Nothing configured means "somewhere sensible", not
            // "the process working directory" — see defaultOutputDirectory().
            File outputDir = configuration.getOutputDirectory();
            if (outputDir == null) {
                outputDir = defaultOutputDirectory();
                configuration.setOutputDirectory(outputDir);
            }

            // Auto-detect annotations file next to output directory.
            //
            // At WARN, not INFO, and for a stronger reason than the baseline below: this is an
            // INPUT being read out of an OUTPUT directory. In a shared directory someone else's
            // thresholds silently decide this run's gate verdict, and a wrong verdict looks
            // exactly like a right one.
            if (configuration.getAnnotationsFile() == null && outputDir != null) {
                File autoAnnotations = new File(outputDir, "report-annotations.json");
                if (autoAnnotations.exists()) {
                    configuration.setAnnotationsFile(autoAnnotations);
                    log.warn("{}", ReportConfiguration.annotationsAutoDetectWarning(autoAnnotations));
                }
            }

            // Auto-detect baseline JTL for comparison.
            //
            // At WARN, not INFO: an unrequested comparison changes what the report MEANS —
            // a Compare tab appears and every metric grows a delta against a run the
            // operator never asked to compare against. A stale baseline.jtl left in a
            // working directory is easy to forget and, in JMeter's bin/, easy to never know
            // about.
            if (configuration.getBaselineJtlFile() == null && outputDir != null) {
                File autoBaseline = new File(outputDir, "baseline.jtl");
                if (autoBaseline.exists()) {
                    configuration.setBaselineJtlFile(autoBaseline);
                    log.warn("Auto-detected baseline JTL and enabling comparison: {} — "
                            + "delete it or set -Jwebinsight.report.baseline to control this",
                            autoBaseline.getAbsolutePath());
                }
            }

            WebInsightReportGenerator generator = new WebInsightReportGenerator(configuration);
            ReportArtifacts artifacts = generator.generateReport(reportData);

            // Tell the -g -o exporter what we actually wrote, so a combined
            // `-n -t … -e -o` run does not produce a second, differently-named report of the
            // same test. It runs later in this same JVM, so a property survives.
            publishResolved(artifacts);
            announce(artifacts);
            publishLastRun(reportData, artifacts);

            // A1: an incomplete set is a failed run too.
            //
            // failTheRun used to fire only from the catch below, and the CI/CD JSON and JUnit
            // XML writers swallow their own exceptions by design — so a run that produced no
            // machine-readable summary at all printed INCOMPLETE to stderr and still exited 0,
            // even with failonerror explicitly turned on. A pipeline gating on the .json
            // learned nothing from any channel it was watching.
            if (artifacts.anyFailed() && failOnError()) {
                failTheRun(NonZeroExit.FAIL_ON_ERROR,
                        new java.io.IOException(artifacts.incompleteReason()));
            }
            // Independently of the branch above, not chained to it. As an else-if, a run that
            // both lost an artifact AND could not build its gate printed only the first — and
            // the marker CI is told to grep for a broken gate definition was the one withheld,
            // on exactly the runs going worst. The exporter path has said both since M3; this
            // one had not, so one flag meant two things depending on how the report was made.
            if (artifacts.hasReportableConfigProblem()) {
                // A gate that could not be BUILT from the file, which is not a verdict about
                // the system under test — it is the plugin saying it could not do what it was
                // told, exactly like an unwritable output directory above.
                //
                // A metric name that does not exist means the gate is not running while its
                // author believes it is, and that is how a quality gate dies. It stays out of
                // failonassert deliberately: that flag is for a verdict you might want a build
                // to stop on, and is off by default because the verdict is yours to act on.
                // This is not a verdict.
                // The marker is printed whether or not the flag is on, matching the exporter
                // path and the precedent C19 sets for artifact failures: failonerror=false buys
                // back the EXIT CODE and nothing else. Silencing the marker too would make the
                // opt-out mean "and never tell me", which is not what anyone opts into.
                // Two sides, two flags: the client's problems are fatal under failonerror, the
                // server's only under webinsight.report.server.failonerror (default off) — server
                // data is a bonus on the client report. failTheRun is guarded so every branch can
                // run and still arrange at most one non-zero exit.
                String client = artifacts.clientConfigMarker();
                if (client != null) {
                    System.err.println(client);
                    if (failOnError()) {
                        failTheRun(NonZeroExit.FAIL_ON_ERROR,
                                new IllegalStateException(artifacts.clientConfigProblemReason()));
                    }
                }
                boolean serverFatal = ExitPolicy.failOnServerError(
                        getJMeterProperty(NonZeroExit.FAIL_ON_SERVER_ERROR), log::warn);
                String server = artifacts.serverConfigMarker(serverFatal);
                if (server != null) {
                    System.err.println(server);
                    if (serverFatal) {
                        failTheRun(NonZeroExit.FAIL_ON_SERVER_ERROR,
                                new IllegalStateException(artifacts.serverConfigProblemReason()));
                    }
                }
            }

            // The verdict, only if asked. Default OFF, and it stays off.
            //
            // This is the one place the tool is allowed to turn a report into an exit code, and
            // it is opt-in because deciding what a FAIL means is the tester's job, not this
            // plugin's. Everything else that reads red — a rule the file got wrong, a target
            // that matched nothing, an annotations file that would not load — is reported and
            // counted and does NOT come through here. Only a declared assert measured against a
            // real number can fail a build, which is the whole shape of the model.
            failOnAssertIfAsked(artifacts);

        } catch (Exception e) {
            // The stack has always gone to jmeter.log; what was missing was any usable
            // statement on the channel a non-GUI run actually shows. e.getMessage() alone was
            // worse than nothing here — for the NIO file exceptions it is just a path, and in
            // the output-directory-is-a-file case the path it names is the report, not the
            // directory that is actually wrong.
            log.error("Failed to generate Web Insight Report: {}", ReportArtifacts.describe(e), e);
            System.err.println(ReportArtifacts.failureLine(e));

            if (failOnError()) {
                failTheRun(NonZeroExit.FAIL_ON_ERROR, e);
            }
        }
    }

    /**
     * Should a run that could not publish a complete artifact set exit non-zero?
     *
     * <p><b>The default is now yes, and that is a breaking change taken deliberately.</b> It was
     * opt-in, default off, on the reasoning that no existing run should change behaviour. The
     * measured consequence of that reasoning: every failure mode observed across a 110-row
     * scenario matrix — unwritable output directory, unwritable {@code .json} path, a report
     * lost to a race between two runs sharing a directory — exited 0. A pipeline shaped
     * {@code jmeter -n -t … && publish-report} stayed green with nothing to publish, and CI
     * could not detect a missing report without parsing a log file it was never given. A flag
     * that has to be discovered before it can protect you protects nobody.
     *
     * <p>It also made the two ingestion paths disagree: the {@code -g -o} exporter's exception
     * propagates and JMeter exits 1, so the identical failure failed the build in one mode and
     * passed it in the other.
     *
     * <p>{@code -Jwebinsight.report.failonerror=false} restores the old behaviour, and an
     * unrecognised value warns and falls back to the default rather than silently meaning false
     * — see {@link BooleanSetting}.
     */
    private boolean failOnError() {
        return ExitPolicy.failOnError(
                getJMeterProperty(NonZeroExit.FAIL_ON_ERROR), log::warn);
    }

    /** @see ExitPolicy#failOnAssert — the exporter reads it through the same call. */
    private boolean failOnAssert() {
        return ExitPolicy.failOnAssert(
                getJMeterProperty(NonZeroExit.FAIL_ON_ASSERT), log::warn);
    }

    /**
     * Exit non-zero when the gate said FAIL and {@code -Jwebinsight.report.failonassert=true}.
     *
     * <p>Separate from {@code failonerror}, which is about the plugin failing to produce its
     * artifacts. That is the tool not doing its job; this is the tool doing its job and the
     * answer being one the tester wants a build to stop on. Conflating them would mean a broken
     * output directory and a breached threshold arrived on the same channel.
     *
     * <p>WARN does not fail. A warn band exists precisely to say "look at this" without
     * stopping anything, and a flag that failed on it would make the band pointless.
     *
     * <p>NOT_JUDGED does not fail either, whatever the reason. A pipeline that wants a broken
     * rule to be fatal has {@code gate.notJudged.invalidRule} in the CI/CD JSON and can say so
     * itself — which is a decision it makes, rather than one made for it here.
     */
    private void failOnAssertIfAsked(ReportArtifacts artifacts) {
        // The client gate's FAIL, or a FAIL on any server gate that builds (D4): the fold is
        // the artifacts', and the verdict line already names which gate said it.
        if (artifacts == null || !artifacts.anyGateFailsBuild()) {
            return;
        }
        if (!failOnAssert()) {
            return;
        }
        log.error("webinsight.report.failonassert=true and a gate said FAIL \u2014 this run will exit 1");
        failTheRun(NonZeroExit.FAIL_ON_ASSERT,
                new IllegalStateException(artifacts.verdictLine()));
    }

    /**
     * Make {@code webinsight.report.failonerror=true} actually fail the run.
     *
     * <p><b>Rethrowing does not work, and this was measured, not assumed.</b> A listener's
     * {@code testEnded} is called during engine shutdown and
     * {@code StandardJMeterEngine} catches whatever it throws:
     *
     * <pre>WARN o.a.j.e.StandardJMeterEngine: Error encountered during shutdown of
     * com.jmeterwebinsightreport.report.listener.WebInsightReportListener@4392fbe0</pre>
     *
     * <p>...and the process still exits 0. So the obvious implementation — throw and let it
     * propagate — produces a warning in a log file nobody reads and changes nothing, which is
     * exactly the failure mode this flag exists to remove.
     *
     * <p>A shutdown hook that calls {@link Runtime#halt} is the mechanism that does work. Not
     * {@code System.exit} from here: {@code testEnded} runs while other listeners still have
     * theirs pending, so exiting inline can cut off a {@code SimpleDataWriter} mid-flush and
     * truncate the JTL — trading a report for a corrupted results file. A hook runs after
     * JMeter's own shutdown has completed, and {@code halt} from inside one sets the process
     * status even though JMeter itself exits 0.
     */
    private void failTheRun(String flag, Exception cause) {
        NonZeroExit.arrange(flag, ReportArtifacts.describe(cause));
    }

    /** @see WebInsightDataExporter#RESOLVED_FILENAME_PROPERTY */
    private void publishResolved(ReportArtifacts artifacts) {
        try {
            JMeterUtils.setProperty(WebInsightDataExporter.RESOLVED_FILENAME_PROPERTY,
                    artifacts.filename(ReportArtifacts.Kind.HTML));
            String title = configuration.getReportTitle();
            if (title != null && !title.isEmpty()) {
                JMeterUtils.setProperty(WebInsightDataExporter.RESOLVED_TITLE_PROPERTY, title);
            }
            // The gate this run was judged against, for the same reason as the filename above.
            //
            // The annotations path can arrive from the GUI/JMX field, which the exporter cannot
            // see: it runs outside the test plan and has no test element to read. So a combined
            // `-n -t -e -o` run produced TWO artifact sets — the listener's carrying a real gate,
            // the exporter's saying assertsEvaluated:false and exiting 0 — with the SAME runId,
            // which by RunIdentity's own contract means the same run. A pipeline globbing the -o
            // directory, which is what CI passes, read the gateless one. Measured, not inferred.
            File annotations = configuration.getAnnotationsFile();
            if (annotations != null) {
                JMeterUtils.setProperty(WebInsightDataExporter.RESOLVED_ANNOTATIONS_PROPERTY,
                        annotations.getAbsolutePath());
            }
        } catch (Exception e) {
            // JMeterUtils may be unavailable in a standalone/embedded context. The handshake is
            // an optimisation over the existing name derivation, never a requirement.
            log.debug("Could not publish the resolved report filename: {}", e.getMessage());
        }
    }

    /**
     * Print what was written, on the channel a non-GUI run shows.
     *
     * <p>Called once per test run, not once per host: in distributed mode {@code testEnded}
     * fires once for every remote host on this single listener instance, and the
     * {@code activeHostCount} guard above means we only reach here after the last one.
     */
    private void announce(ReportArtifacts artifacts) {
        // One line to the log, the block to stdout. The log already carries the intent line,
        // which itemises every planned artifact, plus a "written to" line each — so putting
        // the block there too would duplicate all of it and leave jmeter.log with a
        // five-line record that no grep matches on more than its first line.
        log.info("{}", artifacts.successMarker());
        System.out.println(artifacts.consoleSummary());
        // One line, fixed prefix, absolute path: this is what a pipeline greps rather than
        // parsing a block or reading jmeter.log.
        System.out.println(artifacts.successMarker());
        // The listener cannot see -l's path; it names its own Results file when one is set, and the
        // JTL generically otherwise - the name this run resolved, not a function re-run now (R2).
        // Logged too, so the GUI's log viewer shows it. When the Server data field (or the property
        // that beats it) names a file, that is the file to write; with a GUI, the panel's Rebuild
        // button is the way to add it.
        String ownJtl = getFilename();
        String next = artifacts.nextStepsLine(ownJtl == null || ownJtl.trim().isEmpty() ? null
                : ownJtl.trim().replace(java.io.File.separatorChar, '/'),
                serverDataToWrite(), guiMode());
        if (next != null) {
            System.out.println(next);
            log.info("{}", next);
        }
        if (artifacts.anyFailed()) {
            // The HTML may well exist — but a CI job that gates on the .json or the .xml has to
            // be able to see that its file is missing without reading a log file it was never
            // given. The reason is named here rather than left as "see the summary above",
            // because this line is now also the explanation for a non-zero exit.
            System.err.println("WEB-INSIGHT-REPORT: INCOMPLETE — " + artifacts.incompleteReason());
        }
    }

    /**
     * The server data file the Rebuild button would read, for the NEXT line: the property, which beats
     * the field (L1), else the field as this run resolved it; null when neither names one. Never read
     * by this run: only named.
     */
    private String serverDataToWrite() {
        String property = getJMeterProperty("webinsight.report.servermetrics");
        String path = property != null && !property.trim().isEmpty() ? property : serverDataResolved;
        return path == null || path.trim().isEmpty() ? null
                : path.trim().replace(File.separatorChar, '/');
    }

    /** Whether this JMeter has a GUI, and so a Rebuild button to name. */
    private static boolean guiMode() {
        try {
            return org.apache.jmeter.gui.GuiPackage.getInstance() != null;
        } catch (Throwable e) {   // no GUI classes in an embedding: no button
            return false;
        }
    }

    /**
     * Tell the element the panel shows what this run resolved (R1, L3): its direct source when this is
     * a GUI run's clone, else this element itself (a non-GUI run is not cloned). Written once, after a
     * report was generated - a run that wrote none has nothing to rebuild, and its start already cleared
     * the run before it (see {@code testStarted}).
     */
    private void publishLastRun(ReportData reportData, ReportArtifacts artifacts) {
        try {
            File jtl = null;
            String name = getFilename();
            if (name != null && !name.trim().isEmpty()) {
                // As ResultCollector resolved it when it opened the file: relative to the test plan.
                jtl = new File(org.apache.jmeter.services.FileServer.resolveBaseRelativeName(name.trim()))
                        .getAbsoluteFile();
            }
            String baseline = getJMeterProperty("webinsight.report.baseline");
            LastRun run = LastRun.builder()
                    .jtl(jtl)
                    .outputDirectory(configuration.getOutputDirectory().getAbsoluteFile())
                    .htmlFilename(artifacts.filename(ReportArtifacts.Kind.HTML))
                    .title(configuration.getReportTitle())
                    .rampUpMillis(configuration.getRampUpDurationMillis())
                    .junit(configuration.isGenerateJunitXml())
                    .externalData(configuration.isExternalDataMode(), configuration.getExternalDataThreshold())
                    .apdexThreshold(getJMeterProperty("webinsight.apdex.threshold"))
                    .baseline(baseline == null || baseline.isEmpty() ? null : new File(baseline))
                    .runId(RunIdentity.of(reportData))
                    .endedAt(System.currentTimeMillis())
                    .serverData(serverDataRaw, serverDataResolved)
                    .annotations(annotationsRaw, annotationsResolved)
                    .build();
            panelElement().lastRun = run;
        } catch (Exception e) {
            // The report is written and announced; only the panel's rebuild loses its memory of it.
            log.debug("Could not record this run for the listener panel's Rebuild button: {}", e.getMessage());
        }
    }

    /**
     * The element whose {@link LastRun} this run writes: its direct source when this is a GUI run's
     * clone, else this element itself. One answer for the run's start, which clears it, and its end,
     * which sets it.
     */
    private WebInsightReportListener panelElement() {
        WebInsightReportListener target = source == null ? null : source.get();
        return target != null ? target : this;
    }

    /**
     * A property's text as typed, not as evaluated: a running clone's property is a function, and its
     * string value is what the function returns now. The function keeps the text it was compiled from.
     * Read on this element, not its source: it is the text this run was started with, whatever the
     * panel's element has been edited to since.
     */
    private String rawText(String name) {
        JMeterProperty property = getProperty(name);
        Object value = property.getObjectValue();
        if (value instanceof CompoundVariable) {
            return ((CompoundVariable) value).getRawParameters();
        }
        return property.getStringValue();
    }

}
