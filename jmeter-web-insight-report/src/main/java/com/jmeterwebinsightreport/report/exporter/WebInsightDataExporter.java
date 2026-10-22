package com.jmeterwebinsightreport.report.exporter;

import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import com.jmeterwebinsightreport.report.generator.ExitPolicy;
import com.jmeterwebinsightreport.report.generator.NonZeroExit;
import com.jmeterwebinsightreport.report.generator.BooleanSetting;
import com.jmeterwebinsightreport.report.generator.OutputDirectories;
import com.jmeterwebinsightreport.report.generator.PropertyCheck;
import com.jmeterwebinsightreport.report.generator.ReportArtifacts;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator;
import org.apache.jmeter.report.config.ReportGeneratorConfiguration;
import org.apache.jmeter.report.dashboard.AbstractDataExporter;
import org.apache.jmeter.report.dashboard.ExportException;
import org.apache.jmeter.report.processor.SampleContext;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JMeter Data Exporter for the -g -o report generation pipeline.
 * <p>
 * Registered via {@code jmeter.reportgenerator.exporter.webinsight.classname} in
 * {@code $JMETER_HOME/bin/reportgenerator.properties}. {@code scripts/build-and-deploy.sh}
 * adds this line automatically.
 * When the user runs {@code jmeter -g results.jtl -o report/}, JMeter's ReportGenerator
 * calls this exporter. We re-parse the JTL file with our own JtlParser to produce a
 * full-fidelity ReportData, then generate the HTML report.
 * </p>
 * <p>
 * Note: The {@code file} parameter in {@link #export} is the <b>source JTL file</b>,
 * not the output directory. The output directory is read from the exporter property
 * {@code output_dir} or from JMeter property {@code jmeter.reportgenerator.outputdir}.
 * </p>
 */
public class WebInsightDataExporter extends AbstractDataExporter {

    private static final Logger log = LoggerFactory.getLogger(WebInsightDataExporter.class);

    private static final String PROP_OUTPUT_DIR = "output_dir";
    // There was a PROP_OUTPUT_DIR_DEFAULT of "report-output", resolved against
    // $JMETER_HOME/bin — a directory inside the installation, which is read-only on a managed
    // install and a shared collision point for every -g run on the machine when it is not.
    // The default now goes through OutputDirectories.resolveDefault like the listener's.

    /**
     * Set by {@code WebInsightReportListener} after a successful write, read here.
     *
     * <p>Package-visible on the listener side through this constant so the two halves of the
     * handshake cannot drift apart into two different spellings of the same string.
     */
    public static final String RESOLVED_FILENAME_PROPERTY = "webinsight.report.resolved.filename";

    /** Companion to {@link #RESOLVED_FILENAME_PROPERTY}: the title the listener actually used. */
    public static final String RESOLVED_TITLE_PROPERTY = "webinsight.report.resolved.title";

    /**
     * Companion to {@link #RESOLVED_FILENAME_PROPERTY}: the annotations file the listener judged
     * against.
     *
     * <p>Needed because that path can come from the GUI/JMX field, and this exporter runs outside
     * the test plan with no test element to read. Without it a combined {@code -n -t -e -o} run
     * emitted two artifact sets sharing one runId and disagreeing about whether the run was gated
     * at all.
     */
    public static final String RESOLVED_ANNOTATIONS_PROPERTY =
            "webinsight.report.resolved.annotations";

    public WebInsightDataExporter() {
        super();
    }

    @Override
    public void setName(String name) {
        super.setName(name);
    }

    @Override
    public void export(SampleContext context, File jtlFile,
                       ReportGeneratorConfiguration reportGeneratorConfiguration)
            throws ExportException {
        log.info("Web Insight Data Exporter: starting report generation from JTL: {}", jtlFile);

        // A setting that arrived under a name nothing reads (the PowerShell -J split, a typo) is
        // said before anything is generated, and the split is a config error like any other.
        String propertyError = PropertyCheck.emit(
                PropertyCheck.check(JMeterUtils.getJMeterProperties()), log::warn);
        if (propertyError != null && failOnError()) {
            NonZeroExit.arrange(NonZeroExit.FAIL_ON_ERROR, propertyError
                    + " (set -Jwebinsight.report.failonerror=false to exit 0 anyway)");
        }

        try {
            // 1. Resolve output directory from exporter properties or JMeter properties
            File outputDir = resolveOutputDirectory(reportGeneratorConfiguration, jtlFile);

            // 2. What would an existing report of this run be CALLED? The decision about
            //    whether to skip is made on identity, at step 6 — this only finds candidates.
            //
            // Prefer what the listener actually wrote over what -J says. The listener accepts
            // its filename from EITHER source: -J first, then the JMX/GUI property. This
            // exporter runs outside the test plan and cannot see a test element, so a
            // GUI-configured filename was invisible to it — the search derived the DEFAULT name,
            // did not find it, and a second report was generated. Reproduced: one run produced
            // gui-name.html AND web-insight-report.html, disagreeing on both the filename and
            // the test title. A consumer globbing *-report.html picks up whichever sorts first.
            //
            // Same JVM, so a property the listener sets during testEnded is still here.
            String resolvedName = JMeterUtils.getProperty(RESOLVED_FILENAME_PROPERTY);
            String configuredName = resolvedName != null && !resolvedName.isEmpty()
                    ? resolvedName
                    : JMeterUtils.getProperty("webinsight.report.filename");

            // 3. Read configuration from JMeter properties (-J flags)
            ReportConfiguration config = buildConfiguration(outputDir);

            // 4. Parse the JTL file with our parser
            JtlParser parser = new JtlParser();
            if (config.getRampUpDurationMillis() > 0) {
                parser.setRampUpDurationMillis(config.getRampUpDurationMillis());
            }

            // Apdex threshold from -J property
            String apdexStr = JMeterUtils.getProperty("webinsight.apdex.threshold");
            if (apdexStr != null && !apdexStr.isEmpty()) {
                try {
                    parser.setApdexThreshold(Double.parseDouble(apdexStr));
                } catch (NumberFormatException e) {
                    log.warn("Invalid webinsight.apdex.threshold: {}", apdexStr);
                }
            }

            String testName = config.getReportTitle();
            ReportData reportData = parser.parse(jtlFile, testName);

            // 5. Set JMeter version in metadata
            try {
                String version = JMeterUtils.getJMeterVersion();
                if (version != null) {
                    reportData.getMetadata().setJmeterVersion(version);
                }
            } catch (Exception e) {
                // JMeterUtils may not be available
            }

            // 6. Is this run's report already here?
            //
            // This check used to run FIRST, on a filename, and that is why it is here now. A
            // name cannot tell "the listener in this very run already wrote this" from
            // "somebody else's report is in the way", and the two answers are opposites: skip
            // versus overwrite. Identity can tell them apart — the contract is that a -g
            // re-export of a run's own JTL derives the same id as the listener that ran it — but
            // identity requires the parsed data, so the question has to be asked after the parse
            // rather than before it. Parsing a JTL we may not use costs a fraction of the render
            // it might save, and it buys an answer that is correct.
            if (thisRunAlreadyReported(outputDir, configuredName, reportData)) {
                // At WARN, not INFO: this line is the ONLY explanation for a run that produces
                // no Web Insight report from the CLI exporter. It now fires only when the
                // existing report is provably this same run's, so it is no longer the report
                // of a guess.
                // "skipping CLI generation" stays lower-case and intact: the scenario suite
                // greps that phrase as the marker for this decision, and it is the only line
                // that explains a run producing no report from the exporter.
                log.warn("This run's report is already in {} — the listener wrote it during the "
                        + "same execution, and its run id matches this one — so this run is "
                        + "skipping CLI generation. If you expected a second report here, this "
                        + "is why there isn't one.", outputDir.getAbsolutePath());
                return;
            }

            // 7. Auto-detect annotations and baseline in output directory
            autoDetectCompanionFiles(config, outputDir);

            // 8. Generate the report
            WebInsightReportGenerator generator = new WebInsightReportGenerator(config);
            ReportArtifacts artifacts = generator.generateReport(reportData);

            // Same emitters as the listener, from the same strings, so the two ingestion
            // paths cannot drift apart in what they report. One line to the log (the intent
            // line above already itemises every artifact), the block to stdout.
            log.info("{}", artifacts.successMarker());
            System.out.println(artifacts.consoleSummary());
            System.out.println(artifacts.successMarker());
            String next = artifacts.nextStepsLine(jtlFile == null ? null : jtlFile.getPath().replace(File.separatorChar, '/'));
            if (next != null) {
                System.out.println(next);
                log.info("{}", next);
            }
            // Both markers, independently. They used to be an if/else chain, so a run that lost
            // an artifact AND could not build its gate printed only the first — and the one CI
            // is told to grep for a broken gate definition was the one withheld, on exactly the
            // runs going worst. Two different things went wrong; say both.
            if (artifacts.hasReportableConfigProblem()) {
                configError(artifacts);
            }
            if (artifacts.anyFailed()) {
                System.err.println("WEB-INSIGHT-REPORT: INCOMPLETE — "
                        + artifacts.incompleteReason());
                if (failOnError()) {
                    throw new ExportException(artifacts.incompleteReason()
                            + " (set -Jwebinsight.report.failonerror=false to exit 0 anyway)");
                }
            }
            failOnAssertIfAsked(artifacts);
        } catch (ExportException e) {
            throw e;
        } catch (Exception e) {
            // Unlike the listener, this propagates and JMeter exits 1 — so the marker is all
            // that is added here, for a pipeline grepping one pattern across both modes.
            System.err.println(ReportArtifacts.failureLine(e));
            throw new ExportException("Failed to generate Web Insight Report from JTL " + jtlFile
                    + ": " + ReportArtifacts.describe(e), e);
        }
    }

    /**
     * The gate could not be built from what the file declared.
     *
     * <p>Deliberately NOT an ExportException, unlike an artifact failure. Throwing here works —
     * JMeter exits 1 on its own — but it renders a typo'd metric name as {@code An error
     * occurred:} over twelve frames of JMeter internals. See {@link NonZeroExit} for the full
     * reasoning; the short version is that a config error is the user's input, not the plugin
     * crashing, and the findings already said everything a stack trace would bury.
     *
     * <p>The marker prints whether or not the flag is on. {@code failonerror=false} buys back the
     * exit code and nothing else — an opt-out that also silenced the marker would mean "and never
     * tell me", which is not what anyone opts into.
     */
    private static void configError(ReportArtifacts artifacts) {
        // Two sides, two flags. The client's problems are fatal under failonerror (default true);
        // the server's only under webinsight.report.server.failonerror (default false), because the
        // server tab is a bonus on the client report. Each side prints its own marker.
        String client = artifacts.clientConfigMarker();
        if (client != null) {
            System.err.println(client);
            if (failOnError()) {
                NonZeroExit.arrange(NonZeroExit.FAIL_ON_ERROR, artifacts.clientConfigProblemReason()
                        + " (set -Jwebinsight.report.failonerror=false to exit 0 anyway)");
            }
        }
        boolean serverFatal = failOnServerError();
        String server = artifacts.serverConfigMarker(serverFatal);
        if (server != null) {
            System.err.println(server);
            if (serverFatal) {
                NonZeroExit.arrange(NonZeroExit.FAIL_ON_SERVER_ERROR, artifacts.serverConfigProblemReason()
                        + " (" + NonZeroExit.FAIL_ON_SERVER_ERROR + "=true)");
            }
        }
    }

    private static boolean failOnServerError() {
        return ExitPolicy.failOnServerError(
                JMeterUtils.getProperty(NonZeroExit.FAIL_ON_SERVER_ERROR),
                WebInsightDataExporter::warn);
    }

    /**
     * The verdict, only if asked — the same rule the listener applies, from the same policy.
     *
     * <p>This path did not apply it at all, so an identical annotations file producing an
     * identical FAIL exited 1 under {@code -n -t} and 0 under {@code -g -o}. WARN does not fail;
     * NOT_JUDGED does not fail whatever the reason. Only a declared assert measured against a
     * real number can come through here.
     */
    private static void failOnAssertIfAsked(ReportArtifacts artifacts) {
        // The client gate's FAIL, or a FAIL on any server gate that builds (D4): the fold is
        // the artifacts', and the verdict line already names which gate said it.
        if (artifacts == null || !artifacts.anyGateFailsBuild()) {
            return;
        }
        if (!failOnAssert()) {
            return;
        }
        System.err.println("WEB-INSIGHT-REPORT: ASSERT FAIL — " + artifacts.verdictLine());
        NonZeroExit.arrange(NonZeroExit.FAIL_ON_ASSERT, artifacts.verdictLine());
    }

    /**
     * Resolve the output directory.
     *
     * <p>Priority: (1) {@code -o} via the {@code jmeter.reportgenerator.outputdir} property,
     * (2) this exporter's own {@code output_dir} property, (3) a resolution chain that ends
     * somewhere writable.
     *
     * <p><b>Both of the first two are explicit configuration and never fall back.</b> Silently
     * relocating a directory someone asked for is its own bug: the report is not where they put
     * it, the overwrite target moves between runs, and files accumulate across locations.
     * Explicit configuration is allowed to fail, loudly, later.
     *
     * <p><b>The default used to be {@code $JMETER_HOME/bin/report-output}</b> — inside the
     * installation. That is the same read-only-managed-install problem the listener's default
     * had, plus a collision point every {@code -g} run in that installation shares. It now goes
     * through the same chain as the listener's, starting from the directory of the JTL being
     * exported, which is both writable in practice and where an operator would look first.
     */
    private File resolveOutputDirectory(ReportGeneratorConfiguration config, File jtlFile)
            throws ExportException {
        // 1. Check JMeter property set by -o flag (highest priority)
        String jmeterOutputDir = JMeterUtils.getProperty("jmeter.reportgenerator.outputdir");
        if (jmeterOutputDir != null && !jmeterOutputDir.isEmpty()) {
            return new File(jmeterOutputDir);
        }

        // 2. Get this exporter's own output_dir property from reportgenerator.properties
        if (config.getExportConfigurations() != null && config.getExportConfigurations().containsKey(getName())) {
            try {
                File dir = getPropertyFromConfig(
                        config.getExportConfigurations().get(getName()),
                        PROP_OUTPUT_DIR, null, File.class);
                if (dir != null) return dir;
            } catch (Exception e) {
                log.debug("Could not read output_dir from exporter config: {}", e.getMessage());
            }
        }

        // 3. Nobody said. Find somewhere that actually works, and say where.
        List<File> preferred = new ArrayList<>(1);
        if (jtlFile != null && jtlFile.getParentFile() != null) {
            preferred.add(jtlFile.getParentFile());
        }
        File resolved = OutputDirectories.resolveDefault(preferred);
        log.info("No output directory configured for the Web Insight exporter — using {} "
                        + "(set -o, or the exporter's {} property, to choose)",
                resolved.getAbsolutePath(), PROP_OUTPUT_DIR);
        return resolved;
    }

    /**
     * Should an incomplete artifact set fail the run? Default yes — see the listener's copy of
     * this decision, which carries the full reasoning.
     *
     * <p>The exporter already exited non-zero when {@code generateReport} <em>threw</em>, because
     * {@link ExportException} propagates. It did not when the CI/CD JSON or the JUnit XML failed,
     * because those writers swallow by design — the same hole the listener had, in the path
     * least likely to be watched by a human.
     */
    private static boolean failOnError() {
        return ExitPolicy.failOnError(
                JMeterUtils.getProperty(NonZeroExit.FAIL_ON_ERROR),
                WebInsightDataExporter::warn);
    }

    /**
     * The verdict flag, read here for the same reason the listener reads it.
     *
     * <p>It was not read on this path at all, so the same annotations file and the same FAIL
     * verdict exited 1 under {@code -n -t} and 0 under {@code -g -o} — and a pipeline that runs
     * the test headless and generates the report afterwards, which is the arrangement the flag is
     * most useful in, was the one it did nothing for.
     */
    private static boolean failOnAssert() {
        return ExitPolicy.failOnAssert(
                JMeterUtils.getProperty(NonZeroExit.FAIL_ON_ASSERT),
                WebInsightDataExporter::warn);
    }

    /** @see BooleanSetting#warningFor */
    private static void warnIfNotBoolean(String propertyName, String raw) {
        // No test element on this path, so there is nothing for an unrecognised value to fall
        // through TO — unlike the listener, where the GUI checkbox is still standing.
        warn(BooleanSetting.warningFor(propertyName, raw,
                "and there is no GUI or JMX field on the -g -o path to fall back to, so this "
                        + "setting is off for this run"));
    }

    private static void warn(String warning) {
        if (warning != null) {
            log.warn(warning);
        }
    }

    /**
     * Build ReportConfiguration from JMeter -J properties.
     *
     * <p>Package-private rather than private so a test can drive it with real properties. Every
     * documented {@code -J} flag has, at least once, been documented and then not read here —
     * {@code externaldata} and {@code externaldata.threshold} both were — and a test that
     * sets a property and asserts the field is the only thing that catches it.
     */
    ReportConfiguration buildConfiguration(File outputDir) {
        ReportConfiguration config = new ReportConfiguration();
        config.setOutputDirectory(outputDir);

        // The title is invisible to this exporter for exactly the same reason the filename was:
        // WebInsightReport.reportTitle is a test-element property. So when both generators ran,
        // the duplicate report carried the DEFAULT title while the listener's carried the
        // configured one — the two artifacts of one run disagreeing on the name of the test.
        String title = firstNonEmpty(JMeterUtils.getProperty(RESOLVED_TITLE_PROPERTY),
                JMeterUtils.getProperty("webinsight.report.title"));
        if (title != null && !title.isEmpty()) {
            config.setReportTitle(title);
        }

        String filename = JMeterUtils.getProperty("webinsight.report.filename");
        if (filename != null && !filename.isEmpty()) {
            config.setReportFilename(filename);
        }

        String baseline = JMeterUtils.getProperty("webinsight.report.baseline");
        if (baseline != null && !baseline.isEmpty()) {
            config.setBaselineJtlFile(new File(baseline));
        }

        // -J first, then whatever the listener judged against in this same JVM. The second half
        // is the GUI/JMX case, which no property on this path can otherwise reach: precedence is
        // the same as everywhere else, so an explicit -J still wins.
        String annotations = JMeterUtils.getProperty("webinsight.report.annotations");
        if (annotations == null || annotations.isEmpty()) {
            annotations = JMeterUtils.getProperty(RESOLVED_ANNOTATIONS_PROPERTY);
            if (annotations != null && !annotations.isEmpty()) {
                log.info("Judging against the annotations file the listener used: {}", annotations);
            }
        }
        if (annotations != null && !annotations.isEmpty()) {
            config.setAnnotationsFile(new File(annotations));
        }

        // The server-metrics payload wir-collect wrote between the run and this report. No
        // auto-detection: -g -o refuses a non-empty output directory, so there is nowhere a
        // payload could be detected in; the path is explicit or there is none.
        String serverMetrics = JMeterUtils.getProperty("webinsight.report.servermetrics");
        if (serverMetrics != null && !serverMetrics.isEmpty()) {
            config.setServerMetricsFile(new File(serverMetrics));
        }

        String rampUp = JMeterUtils.getProperty("webinsight.report.rampup");
        if (rampUp != null && !rampUp.isEmpty()) {
            try {
                config.setRampUpDurationMillis(Long.parseLong(rampUp));
            } catch (NumberFormatException e) {
                log.warn("Invalid rampup value: {}", rampUp);
            }
        }

        // Same flag the listener honours. Without this the documented -J property silently
        // did nothing in -g -o mode, which is the mode most likely to hit a payload big
        // enough to want it.
        //
        // There is no GUI or JMX surface on this path — the exporter runs outside the test plan
        // — so "unset" means off for both of these. The warning still matters: it is the only
        // way an operator learns that -Jwebinsight.report.junit=yes did nothing.
        String externalProp = JMeterUtils.getProperty("webinsight.report.externaldata");
        warnIfNotBoolean("webinsight.report.externaldata", externalProp);
        config.setExternalDataMode(BooleanSetting.parse(externalProp).orElse(false));

        String junitProp = JMeterUtils.getProperty("webinsight.report.junit");
        warnIfNotBoolean("webinsight.report.junit", junitProp);
        config.setGenerateJunitXml(BooleanSetting.parse(junitProp).orElse(false));

        // Same property, same validation as the listener — `-g -o` is the mode most likely to
        // handle a payload big enough to want it tuned.
        String thresholdWarning = config.applyExternalDataThreshold(
                JMeterUtils.getProperty("webinsight.report.externaldata.threshold"));
        if (thresholdWarning != null) {
            log.warn(thresholdWarning);
        }

        return config;
    }

    /**
     * Auto-detect report-annotations.json and baseline.jtl in the output directory.
     */
    private void autoDetectCompanionFiles(ReportConfiguration config, File outputDir) {
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
     * Is the report already in this directory <em>this run's own</em>?
     *
     * <p>Only a yes here justifies writing nothing, and that is the entire change. The old
     * question was "does a file matching this name pattern exist", which conflated the case this
     * check exists for — a combined {@code -n -t … -e -o} run where the listener already produced
     * the report — with "an unrelated report is in the way", where skipping means the run
     * silently produces nothing. One degenerate glob ({@code ${timestamp}} as the whole
     * configured name) even matched JMeter's own {@code statistics.json}, and a real run ended
     * with no Web Insight report at all and exit 0.
     *
     * <p>The name is still how candidates are <b>found</b> — a {@code ${timestamp}} name is
     * stamped at the listener's second, not ours, so the glob is the only way to locate it. It is
     * no longer how the decision is <b>made</b>. Every candidate's {@code .json} is asked which
     * run produced it, and only an exact match against this run's id counts.
     *
     * <p>Anything that cannot answer — no {@code .json}, a truncated one, one from a version
     * before run identity — counts as not ours, and the report is generated. That direction is
     * chosen deliberately: a visible duplicate or overwrite is a far better failure than a run
     * that produces nothing and says nothing.
     */
    // package-private for tests
    static boolean thisRunAlreadyReported(File outputDir, String configuredName,
                                          ReportData reportData) {
        String runId = com.jmeterwebinsightreport.core.util.RunIdentity.of(reportData);
        for (File json : candidateJsons(outputDir, configuredName)) {
            String existing = WebInsightReportGenerator.readRunId(json);
            if (existing != null && existing.equals(runId)) {
                return true;
            }
            log.info("An existing report in {} belongs to a different run ({}) — generating "
                    + "this run's report over it.", outputDir.getAbsolutePath(),
                    existing != null ? existing : "unidentified");
        }
        return false;
    }

    /**
     * The {@code .json} siblings of any report already sitting in this directory under the name
     * this run would use.
     *
     * <p>Uses the generator's own filename resolution so the two halves agree, and matches on a
     * glob when the name contains {@code ${timestamp}} — the listener stamped it at ITS run time,
     * which may be a different second from this one.
     */
    private static List<File> candidateJsons(File outputDir, String configuredName) {
        if (outputDir == null || !outputDir.isDirectory()) {
            return java.util.Collections.emptyList();
        }
        String htmlGlob = WebInsightReportGenerator.reportFilenameGlob(configuredName);
        String base = htmlGlob.endsWith(".html")
                ? htmlGlob.substring(0, htmlGlob.length() - ".html".length())
                : htmlGlob;

        List<File> found = new ArrayList<>(1);
        if (base.indexOf('*') < 0) {
            File json = new File(outputDir, base + ".json");
            if (json.isFile()) {
                found.add(json);
            }
            return found;
        }

        // A glob with no literal part of its own cannot identify OUR report: '${timestamp}' as
        // the entire configured name degenerates to base "*", so the JSON glob becomes "*.json"
        // — which matches JMeter's own statistics.json, written by JsonExporter before any
        // exporter runs. Identity would reject it anyway, but there is no reason to read every
        // JSON in a dashboard directory to find that out.
        if (base.replace("*", "").isEmpty()) {
            log.debug("Configured report name '{}' has no literal part, so an existing report "
                    + "cannot be located by name — generating rather than skipping.",
                    configuredName);
            return found;
        }

        // Build a regex from the glob, quoting the literal segments so a name containing
        // regex metacharacters can't change the match.
        final Pattern jsonPattern = globToPattern(base + ".json");
        String[] matches = outputDir.list((dir, name) -> jsonPattern.matcher(name).matches());
        if (matches != null) {
            for (String name : matches) {
                found.add(new File(outputDir, name));
            }
        }
        return found;
    }

    /** First of the two that is neither null nor empty, or null. */
    private static String firstNonEmpty(String preferred, String fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        return fallback != null && !fallback.isEmpty() ? fallback : null;
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        int from = 0;
        for (int i = glob.indexOf('*'); i >= 0; i = glob.indexOf('*', from)) {
            if (i > from) {
                regex.append(Pattern.quote(glob.substring(from, i)));
            }
            regex.append(".*");
            from = i + 1;
        }
        if (from < glob.length()) {
            regex.append(Pattern.quote(glob.substring(from)));
        }
        return Pattern.compile(regex.toString());
    }
}
