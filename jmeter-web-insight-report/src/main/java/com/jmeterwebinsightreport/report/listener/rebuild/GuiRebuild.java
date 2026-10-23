package com.jmeterwebinsightreport.report.listener.rebuild;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.exporter.ReportRebuild;
import com.jmeterwebinsightreport.report.generator.ReportArtifacts;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The listener panel's rebuild: {@link ReportRebuild}, the one rebuild {@code jmeter -g} runs too, over
 * what a {@link RebuildRequest} resolved, with everything it prints captured for the result dialog.
 *
 * <p><b>What it must never do</b> is anything that means "this process exits": no {@code NonZeroExit},
 * no {@code ExitPolicy}, no {@code PropertyCheck.emit}. Those are the {@code -g} path's policy, and in
 * a GUI session {@code NonZeroExit.arrange} hangs a {@code halt(1)} on the user's JMeter, to fire
 * whenever they close it, hours later. A refused collection, a config problem, a failed write: each
 * comes back as a sentence in the {@link RebuildResult}, never as an exception and never as an exit code.
 *
 * <p><b>Where it writes (decision 1, R5).</b> Over the report the run wrote, in place: the run's own
 * output directory and the filename it resolved, so a {@code ${timestamp}} name is not stamped again and
 * the page keeps its {@code runId} and drafts. Through the generator's staging and {@code PublishLock},
 * like every other write. What changes is said: the rebuilt report is the {@code -g} report, read from
 * the JTL, so it carries no response bodies.
 *
 * <p>Runs on a {@code SwingWorker}'s thread, never on the event thread.
 */
public final class GuiRebuild {

    private static final Logger log = LoggerFactory.getLogger(GuiRebuild.class);

    /** About what reading a CSV costs in heap, per byte of file: E29's 25 MB CSV takes about 250 MB. */
    static final long HEAP_PER_CSV_BYTE = 10;

    private GuiRebuild() {
    }

    /** Rebuild with JMeter's properties, this process's environment and its free heap. */
    public static RebuildResult run(RebuildRequest request) {
        Runtime rt = Runtime.getRuntime();
        long available = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        return run(request, JMeterUtils::getProperty, System.getenv(), available);
    }

    /**
     * Rebuild with the properties, environment and available heap given.
     *
     * @param props         JMeter's properties; the CSV reader options and the comparison thresholds are
     *                      read from them as {@code jmeter -g} reads them
     * @param availableHeap bytes of heap this JVM can still use, for the pre-check
     */
    static RebuildResult run(RebuildRequest request, Function<String, String> props, Map<String, String> env,
                             long availableHeap) {
        List<String> warnings = new ArrayList<>(request.warnings());
        if (request.isRefused()) {
            return new RebuildResult(null, request.refusal(), null, new ArrayList<>(), warnings);
        }
        heapPreCheck(request.serverData(), availableHeap, warnings);

        // One stream for both of the collector's channels, so its lines keep the order it printed them in.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream console = utf8(captured);
        ReportConfiguration config = configuration(request);
        // The apdex threshold is the run's; every other property is read now, as -g reads it.
        Function<String, String> rebuildProps = key -> "webinsight.apdex.threshold".equals(key)
                ? request.apdexThreshold() : props.apply(key);
        String serverDataRead = describe(request.serverData());
        ReportArtifacts artifacts = null;
        String failure = null;
        try {
            // A rebuild is a run of its own to the generator's "written twice in one run" guard: the
            // report this overwrites was written by the run, which that guard would otherwise remember.
            WebInsightReportGenerator.beginTestRun();
            ReportData parsed = ReportRebuild.parse(request.jtl(), config, rebuildProps);
            runIdCheck(request.lastRun(), parsed, warnings);
            // The collector's lines go to the dialog, not to a console, and the refusals say so.
            ReportRebuild.readCompanionFiles(config, request.outputDirectory(), request.jtl(), parsed,
                    rebuildProps, console, console, env, ReportRebuild.Route.LISTENER_PANEL);
            artifacts = ReportRebuild.generateAndAnnounce(config, parsed,
                    RebuildRequest.slashes(request.jtl()), console, line -> log.info("{}", line));
        } catch (OutOfMemoryError e) {
            failure = "JMeter ran out of memory rebuilding the report - give it more heap (JVM_ARGS=-Xmx2g in the"
                    + " environment JMeter starts from) or export a narrower window of server data";
        } catch (Exception e) {
            log.error("The listener panel's rebuild failed: {}", ReportArtifacts.describe(e), e);
            failure = ReportArtifacts.describe(e);
        } finally {
            // generateAndAnnounce drops the collected payload itself; this covers a throw before it ran.
            File scratch = config.getCollectedServerPayload();
            if (scratch != null && scratch.exists() && !scratch.delete()) {
                log.debug("Could not delete the temporary server payload {}", scratch);
            }
            console.flush();
        }
        return new RebuildResult(artifacts, failure, serverDataRead,
                collectorLines(new String(captured.toByteArray(), StandardCharsets.UTF_8)), warnings);
    }

    /** The configuration the run's listener built, from what the request resolved. */
    static ReportConfiguration configuration(RebuildRequest request) {
        ReportConfiguration config = new ReportConfiguration();
        config.setReportTitle(request.title());
        config.setOutputDirectory(request.outputDirectory());
        // The run's own name, already stamped: the rebuild overwrites the report rather than adding one.
        config.setReportFilename(request.reportFilename());
        config.setAnnotationsFile(request.annotations());
        config.setBaselineJtlFile(request.baseline());
        config.setServerMetricsFile(request.serverData());
        config.setRampUpDurationMillis(request.rampUpMillis());
        config.setGenerateJunitXml(request.junit());
        config.setExternalDataMode(request.externalData());
        String thresholdWarning = config.applyExternalDataThreshold(request.externalDataThreshold());
        if (thresholdWarning != null) {
            log.warn(thresholdWarning);
        }
        return config;
    }

    /**
     * R9: the JTL can hold another run than the one this listener reported - appended to by a second
     * run, or with sub-results that change the top-level count. Said, and the rebuild still writes:
     * the report is true to the file, and the page's drafts of the old run will not carry over.
     */
    private static void runIdCheck(LastRun run, ReportData parsed, List<String> warnings) {
        if (run == null || run.runId() == null) {
            return;
        }
        String now = RunIdentity.of(parsed);
        if (!run.runId().equals(now)) {
            warnings.add("The results file does not hold exactly the run this listener last reported (run id "
                    + run.runId() + " then, " + now + " now) - was it appended to by another run? The report is"
                    + " written from the file as it is, and drafts saved on the run's report will not carry over.");
        }
    }

    /** A CSV costs about ten times its size while it is indexed; say so before the heap runs out. */
    static void heapPreCheck(File serverData, long availableHeap, List<String> warnings) {
        if (serverData == null || !serverData.isFile() || ReportRebuild.looksLikeJson(serverData)) {
            return;
        }
        long needed = serverData.length() * HEAP_PER_CSV_BYTE;
        if (needed > availableHeap) {
            warnings.add("The server data is " + RebuildRequest.size(serverData.length()) + "; reading it takes about"
                    + " ten times that in memory, and this JMeter has about " + RebuildRequest.size(Math.max(0, availableHeap))
                    + " of heap left. If it runs out, give JMeter more heap (JVM_ARGS=-Xmx2g) or export a narrower window.");
        }
    }

    /** L5: the file, its saved time and its size, as the dialog names what it read. */
    private static String describe(File serverData) {
        if (serverData == null) {
            return null;
        }
        String saved = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(serverData.lastModified()));
        return "Read " + RebuildRequest.slashes(serverData) + " (saved " + saved + ", "
                + RebuildRequest.size(serverData.length()) + ")";
    }

    /** The collector's lines, in order: every line it printed starts with its name. */
    static List<String> collectorLines(String console) {
        List<String> lines = new ArrayList<>();
        for (String line : console.split("\\r?\\n")) {
            if (line.startsWith("wir-collect")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static PrintStream utf8(ByteArrayOutputStream bytes) {
        try {
            return new PrintStream(bytes, true, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);   // UTF-8 is always supported
        }
    }
}
