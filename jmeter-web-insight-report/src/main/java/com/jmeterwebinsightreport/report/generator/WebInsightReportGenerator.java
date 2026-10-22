package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.comparison.ComparisonResult;
import com.jmeterwebinsightreport.core.comparison.ComparisonThresholds;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.util.ArtifactPublication;
import com.jmeterwebinsightreport.core.util.PublishLock;
import com.jmeterwebinsightreport.core.assertion.SamplerVerdicts;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.report.annotation.AnnotationLoader;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.report.template.JunitXmlWriter;
import com.jmeterwebinsightreport.report.template.ObjectLiteralWriter;
import com.jmeterwebinsightreport.report.template.ReportDataSerializer;
import com.jmeterwebinsightreport.report.template.ThymeleafReportRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Orchestrates report generation — takes ReportData, serializes chart data,
 * renders via Thymeleaf with inlined CSS/JS, and writes a single self-contained HTML file.
 */
public class WebInsightReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(WebInsightReportGenerator.class);
    private static final String REPORT_FILENAME = "web-insight-report.html";
    // External chart data is emitted as a JS file (window.__WIR_DATA__ = {...};) loaded via a
    // <script> tag rather than fetch(), so large reports still work when opened from file://.
    private static final String EXTERNAL_DATA_FILENAME = "web-insight-data.js";
    private static final String EXTERNAL_DATA_VAR = "window.__WIR_DATA__=";

    /**
     * How long to wait for the directory lock before publishing without it.
     *
     * <p>The locked section is four renames — single-digit milliseconds — so any genuine wait
     * here is for another process's publish, not for work. Ten seconds is far longer than that
     * ever takes and short enough that a stuck holder cannot hang a test run.
     */
    private static final long PUBLISH_LOCK_TIMEOUT_MILLIS = 10_000L;

    /**
     * Report paths written during the current test run, to catch a second write to the same path.
     *
     * <p><b>Per test run, not per JVM.</b> It was per JVM and never cleared, on the reasoning
     * that a JMeter process runs one test plan. The GUI does not: it keeps one JVM alive across
     * every run of a development session, so the guard fired on the second run and every run
     * after it, warning about a misconfiguration that was not there. A warning that appears when
     * nothing is wrong teaches people to ignore warnings, which costs more than the guard is
     * worth.
     *
     * <p>{@link #beginTestRun()} clears it, and the listener calls that when the first host
     * starts — not on every {@code testStarted}, because in distributed mode that fires once per
     * remote host on one listener instance and clearing per host would defeat the guard in the
     * only mode where two writers are genuinely likely.
     *
     * <p>Keys are canonicalised and case-folded. {@code getAbsolutePath()} is neither, so two
     * listeners configured {@code Report.html} and {@code report.html} collided silently on
     * Windows and macOS, and a symlinked output directory did the same everywhere.
     */
    private static final Set<String> WRITTEN_THIS_RUN =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * Start a new test run: forget which report paths this JVM has already written.
     *
     * <p>Called by the listener on the first host's {@code testStarted}. Idempotent, and safe to
     * call from a context that has no listener at all — the {@code -g} exporter never calls it,
     * which is correct, because it runs once per process.
     */
    public static void beginTestRun() {
        WRITTEN_THIS_RUN.clear();
    }

    /** What is already sitting at the report's path, judged by identity rather than by name. */
    public enum ExistingReport {
        /** Nothing there. */
        NONE,
        /** This very run already produced it — the {@code -n -t … -e -o} handshake case. */
        THIS_RUN,
        /** A report from a different run. Overwriting is correct; doing it silently is not. */
        ANOTHER_RUN,
        /**
         * Something is there but will not say which run it belongs to — no {@code .json}, a
         * truncated one, or one written by a version that predates run identity.
         */
        UNIDENTIFIED
    }

    // Cached classpath resources (immutable after first load, safe for concurrent access)
    private static volatile String cachedCss;
    private static volatile String cachedJs;
    private static volatile String cachedEchartsJs;

    private final ReportConfiguration configuration;
    private final ThymeleafReportRenderer renderer;
    private final ReportDataSerializer serializer;
    private final AnnotationLoader annotationLoader;

    public WebInsightReportGenerator(ReportConfiguration configuration) {
        this.configuration = configuration;
        this.renderer = new ThymeleafReportRenderer();
        this.serializer = new ReportDataSerializer();
        this.annotationLoader = new AnnotationLoader();
    }

    /**
     * Generate the HTML report from the given data.
     *
     * @param reportData the aggregated report data
     * @return every artifact this run planned, with what became of each
     * @throws IOException if writing the report itself fails
     */
    public ReportArtifacts generateReport(ReportData reportData) throws IOException {
        // 0. Resolve every path BEFORE doing any work, and say so.
        //
        // This used to happen at step 7, after ~1.3 MB of HTML had been rendered and after
        // the external chart payload had already been written to disk under a fixed name. So
        // a filename the resolver rejects cost a full render, the payload landed before
        // anything had validated where it was going, and the payload's name could not be
        // derived from the report's name even in principle. Resolving up front also means
        // ${timestamp} is evaluated exactly once, rather than once per artifact with a
        // minute boundary sitting between them.
        File outputDir = configuration.getOutputDirectory();
        String htmlFilename = resolveFilename(configuration.getReportFilename(), REPORT_FILENAME,
                timestamp(), true);
        ReportArtifacts artifacts = new ReportArtifacts(
                outputDir,
                htmlFilename,
                siblingName(htmlFilename, ".json"),
                configuration.isGenerateJunitXml() ? siblingName(htmlFilename, ".xml") : null,
                externalDataFilename(htmlFilename),
                configuration.isExternalDataMode(),
                configuration.getExternalDataThreshold());

        // The line that was missing entirely. Every silent-no-output case — a rejected
        // filename, an unwritable directory, the -g -o exporter deciding to skip — left no
        // record of the resolved directory or filename, and often no evidence the plugin had
        // run at all.
        log.info("{}", artifacts.intentLine());

        // 1. Load annotations if configured.
        //
        // read(), not load(): a configured file that is missing or unparseable used to end in a
        // log.warn and an empty document, so a typo'd -Jwebinsight.report.annotations path
        // produced a report indistinguishable from one that never wanted a gate. The problem
        // travels with the document now and becomes a finding on every surface.
        AnnotationLoader.Result loaded = annotationLoader.read(configuration.getAnnotationsFile());
        ReportAnnotations annotations = loaded.annotations();

        // 1b. The server-metrics payload, if a collector wrote one. Same contract as the
        // annotations: a configured file that is missing, unparseable, another schema's or —
        // the one that matters — another run's is a problem that travels, never a silent
        // absence. Checked against this run's identity here, before anything is rendered,
        // because a foreign payload drawn under this run's headings is the bug RunIdentity
        // exists to stop. A refused payload is not embedded at all. One identity for the whole
        // generation: the exists-policy below keys on the same string.
        String runId = com.jmeterwebinsightreport.core.util.RunIdentity.of(reportData);
        com.jmeterwebinsightreport.report.servermetrics.ServerMetricsPayloadLoader.Result collected =
                new com.jmeterwebinsightreport.report.servermetrics.ServerMetricsPayloadLoader()
                        .read(configuration.getServerMetricsFile(), runId);
        com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload serverMetrics =
                collected.payload();

        // 2. Load baseline for comparison if configured
        ComparisonResult comparison = null;
        // An explicitly configured baseline that isn't there is a mistake worth reporting:
        // silently skipping it produces a report with no Compare tab and no explanation.
        if (configuration.getBaselineJtlFile() != null && !configuration.getBaselineJtlFile().exists()) {
            log.warn("Baseline JTL not found, comparison skipped: {}",
                    configuration.getBaselineJtlFile().getAbsolutePath());
        }
        if (configuration.getBaselineJtlFile() != null && configuration.getBaselineJtlFile().exists()) {
            try {
                JtlParser parser = new JtlParser();
                ReportData baselineData = parser.parse(configuration.getBaselineJtlFile(), "Baseline");
                ComparisonThresholds thresholds = buildComparisonThresholds(annotations);
                comparison = new ComparisonResult(baselineData, reportData, thresholds);
                log.info("Baseline comparison loaded from: {}", configuration.getBaselineJtlFile());
            } catch (Exception e) {
                log.warn("Failed to load baseline JTL: {}", e.getMessage());
            }
        }

        // 3. Read CSS and JS from classpath for inlining (cached across invocations)
        String cssContent = getCachedCss();
        String jsContent = getCachedJs();
        String echartsJs = getCachedEchartsJs();

        // 4. Ensure the output directory exists AND is somewhere we can actually write.
        //
        // A probe write, not File.canWrite(): on Windows that method reflects only the DOS
        // read-only attribute and returns true for an ACL-denied directory, which is the normal
        // way a directory is unwritable there. So the fail-fast check never fired — the full
        // ~1.5 MB report rendered first and then failed, naming the staging file rather than the
        // directory that was actually wrong.
        OutputDirectories.requireWritable(outputDir);

        // A literal ${...} in the title or the filename is always a mistake, and was always
        // silent. Checked here, once the name is resolved, so the message names what will
        // actually be written.
        warnOnUnevaluatedPlaceholder("title", configuration.getReportTitle());
        warnOnUnevaluatedPlaceholder("filename", configuration.getReportFilename());

        // Overwriting is the intended default (N06 asserts it), but doing it silently is how a
        // ${timestamp} collision used to destroy the previous run's report without a word — and
        // "an existing report" is not one situation. Whose it is decides whether this is routine.
        ExistingReport existing = inspectExisting(outputDir, htmlFilename, runId);
        if (existing == ExistingReport.ANOTHER_RUN) {
            // "Overwriting an existing report" is kept verbatim: four scenario runners grep
            // that phrase, and it is the line an operator finds when a ${timestamp} collision
            // has quietly destroyed the previous run's report. Whose report it was is new
            // information appended to it, not a replacement for it.
            log.warn("Overwriting an existing report, from a DIFFERENT run: {} — give each run "
                            + "its own output directory, or use ${timestamp} in the filename, "
                            + "if you meant to keep both.",
                    artifacts.html().getAbsolutePath());
        } else if (existing == ExistingReport.UNIDENTIFIED) {
            log.warn("Overwriting an existing report: {} — it does not declare which run produced "
                            + "it (no readable .json sibling), so it may or may not be this one.",
                    artifacts.html().getAbsolutePath());
        }

        // Two Web Insight listeners in one test plan each carry their own stats engine and their
        // own testEnded, and neither knows the other exists — so the same path is rendered
        // (~1.3 MB) and written twice, and which one survives is decided by listener iteration
        // order. Identical content when both engines saw every sample, but the two elements can
        // carry different titles, filenames or JUnit settings, and then the difference is
        // silent. Reproduced with the repo's own multi-listener-test.jmx.
        if (!WRITTEN_THIS_RUN.add(guardKey(artifacts.html()))) {
            // Likewise "has already been written in this JVM" — grepped by A07, A08 and C26.
            // The guard is keyed per test run now, which is what stops it firing on every GUI
            // re-run; the phrase describes where the bookkeeping lives and stays as it was.
            log.warn("This report path has already been written in this JVM, during this test "
                    + "run: {} — two Web Insight listeners in one test plan? The last write "
                    + "wins, and which listener that is depends on iteration order.",
                    artifacts.html().getAbsolutePath());
        }

        // 4b. Judge the run — once, before anything is rendered or serialised.
        //
        // Three consumers need the same verdict: the payload the report's Asserts tab
        // reads, the header badge, and the CI/CD JSON and JUnit XML written at the end. Each
        // used to evaluate it independently, which re-ran the whole gate and logged every
        // validation finding twice, 300ms apart, reading like two separate problems in a CI log.
        // GateEvaluation.of directly. This went through a private evaluateSla() that added
        // nothing but its own name -- the last SLA identifier in src/main, three years after
        // the vocabulary was retired -- and a javadoc claiming it "returns null when nothing
        // was declared", which it never did: GateEvaluation.none() is the empty case and is
        // still an evaluation.
        GateEvaluation evaluation = GateEvaluation.of(reportData, annotations, loaded.problem());
        // So the verdict reaches stdout. Until this line the only channels carrying it were a
        // log4j2 config with no Console appender and the HTML itself.
        artifacts.recordGate(evaluation.gate());
        // What the FILE got wrong, so the ingestion paths can fail the run on it. Both numbers
        // already exist; nothing new is computed. A gate that could not be built is a
        // configuration error, which is what failonerror has always covered — it is not a
        // verdict, and no status, count or fold changes because of this line.
        artifacts.recordConfigProblem(evaluation.gate().configErrors(), loaded.problem());
        // 4c. The server gates, beside the client gate — judged against the accepted payload,
        // or declared and not evaluated when there is none (the interim report). Read by the
        // payload, the CI/CD JSON, the JUnit XML and the exit fold, from this one evaluation.
        // The loader's whole answer goes in, not just the payload: a refused payload has to reach
        // the Server tab as "refused, and why", never as "none was given".
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation serverGates =
                com.jmeterwebinsightreport.report.gate.ServerGateEvaluation.of(annotations, collected,
                        evaluation.gate(), reportData);
        artifacts.recordServerGates(serverGates);
        if (reportData.getMetadata() != null && reportData.getMetadata().getStartTime() != null
                && reportData.getMetadata().getEndTime() != null) {
            artifacts.recordRunWindow(reportData.getMetadata().getStartTime().toEpochMilli(),
                    reportData.getMetadata().getEndTime().toEpochMilli());
        }
        // And what the PAYLOAD got wrong, the same way — when it matters to the build. A payload
        // that could not be used is a config error only when an enabled assert sits in a gate that
        // builds (A1-7), and the evaluation then carries it as an ERROR finding too; otherwise it
        // carries a WARNING finding and this records nothing. Decided there, not here, so the one
        // place that knows which gates build is the one that answers.
        artifacts.recordServerMetricsProblem(serverGates.fatalPayloadProblem());

        // 5. Decide inline vs external, but do not write the payload yet — see step 7.
        boolean useExternalData = configuration.isExternalDataMode();
        String chartDataJson;
        String pendingPayload = null;
        File dataFile = artifacts.file(ReportArtifacts.Kind.DATA);

        if (useExternalData) {
            chartDataJson = "{}"; // Placeholder for the inline template variable
        } else {
            chartDataJson = serializer.serializeChartData(reportData, annotations, comparison,
                    evaluation, serverMetrics, serverGates);

            // Check if size exceeds threshold — switch to external data mode. (length() is
            // UTF-16 chars rather than bytes; near-identical for this ASCII-heavy JSON.)
            if (chartDataJson.length() > configuration.getExternalDataThreshold()) {
                useExternalData = true;
                pendingPayload = chartDataJson;
                chartDataJson = "{}";
            }
        }

        // 6. Render Thymeleaf template with inlined resources, annotations, and comparison
        // The payload name IS the external-data flag now: null means inline. One parameter
        // instead of a boolean plus a name that had to agree with it.
        String html = renderer.render(reportData, chartDataJson, cssContent, jsContent,
                echartsJs, annotations, comparison,
                useExternalData ? artifacts.filename(ReportArtifacts.Kind.DATA) : null,
                evaluation, serverGates);

        // 7. Stage the whole set, then publish it.
        //
        // It used to be four separate writes with all the expensive work happening between them:
        // HTML, then serialise-and-write the payload, then serialise-and-write the JSON, then
        // build-and-write the XML. Each write was individually atomic and the SET was not, so a
        // kill or a full disk anywhere in that span left this run's HTML beside the previous
        // run's JSON — mixed content, with no concurrency involved. Worse, the HTML landed
        // BEFORE the payload it references, so a failed payload write left a brand-new report
        // pointing at the previous run's chart data, from a process that exited 0.
        //
        // Staging first shrinks the window from "the whole render" to "four renames". It does not
        // close it — no filesystem replaces N files atomically — which is why every artifact also
        // carries the run identity, so a set that does get mixed announces itself.
        try (ArtifactPublication publication = new ArtifactPublication()) {
            File outputFile = artifacts.html();

            // The payload is staged first and therefore published first: the page that loads it
            // must never appear in the directory ahead of it.
            if (useExternalData) {
                if (pendingPayload != null) {
                    // Threshold path: the payload is already a String, so stream it out rather
                    // than concatenating the wrapper onto a copy of it. Through the one sidecar
                    // writer, which writes a __proto__ key so the browser keeps it (N2).
                    final String payload = pendingPayload;
                    publication.stage(dataFile, ObjectLiteralWriter.assignment(EXTERNAL_DATA_VAR,
                            writer -> writer.write(payload)));
                } else {
                    // Forced path: never materialise the payload as a String at all.
                    publication.stage(dataFile, serializer.externalDataJsContent(
                            reportData, annotations, comparison, EXTERNAL_DATA_VAR, evaluation,
                            serverMetrics, serverGates));
                }
                artifacts.written(ReportArtifacts.Kind.DATA);
            }

            publication.stage(outputFile, html);
            artifacts.written(ReportArtifacts.Kind.HTML);

            // 8. The gate, evaluated at step 4b and shared with the HTML just staged.
            SamplerVerdicts assertResults = evaluation.hasGate() ? evaluation.verdicts() : null;
            AssertionStatus overallStatus = evaluation.overallStatus();

            // 9. The CI/CD JSON carries the manifest, so the outcomes above must already be
            // recorded when it is built.
            stageCiCdJson(publication, reportData, assertResults, overallStatus, artifacts,
                    evaluation, serverGates);

            // 10. JUnit XML if enabled
            if (configuration.isGenerateJunitXml()) {
                stageJunitXml(publication, reportData, assertResults, artifacts, evaluation, serverGates);
            }

            // 11. Four renames, under a lock so another process sharing this directory cannot
            // interleave its own four with them.
            publishOrRecord(publication, artifacts, outputDir);
        }

        return artifacts;
    }

    /**
     * Move the staged set into place and reconcile what actually landed.
     *
     * <p>A publish failure is rare — it is a rename within one directory, onto a path whose
     * writability was probed before any of this started — but it is not impossible, and the
     * outcomes recorded before the renames are predictions until this runs. So they are
     * corrected here rather than left as optimistic claims: the CI/CD JSON's manifest can say
     * WRITTEN about a file that then failed to publish, and that is a known and documented
     * inaccuracy in one file, but the console summary and the exit code must not repeat it.
     *
     * <p>The report itself is the exception that propagates. Everything else is recorded and
     * survivable — a run with no JUnit XML is a diminished run, a run with no HTML is a failed
     * one.
     */
    private static void publishOrRecord(ArtifactPublication publication, ReportArtifacts artifacts,
                                        File outputDir) throws IOException {
        List<ArtifactPublication.Failure> failures;
        try (PublishLock lock = PublishLock.acquire(outputDir, PUBLISH_LOCK_TIMEOUT_MILLIS)) {
            if (!lock.isHeld()) {
                // Not fatal, and deliberately not silent. Publishing unlocked is exactly the
                // behaviour that existed before the lock did, and the identity stamped into
                // every artifact still catches a set that gets mixed — but an operator
                // debugging a strange directory should be able to see that the guard was not
                // in force for this run.
                log.warn("Publishing without the directory lock ({}). Concurrent runs sharing {} "
                                + "can still overwrite each other; each artifact's run id remains "
                                + "the guard against a mixed set.",
                        lock.failureReason(),
                        artifacts.outputDirectory().getPath());
            }
            // Nothing may outlive the report: a CI/CD JSON beside an HTML that never
            // landed is a summary of a file that does not exist.
            failures = publication.publish(artifacts.html());
        }

        IOException reportFailure = null;
        for (ArtifactPublication.Failure failure : failures) {
            ReportArtifacts.Kind kind = artifacts.kindOf(failure.target());
            if (kind != null) {
                artifacts.failed(kind, failure.cause());
            }
            // Same phrasing as the staging failures below, deliberately. A blocked target
            // passes staging — the staging file has a different name — and only fails at the
            // rename, so this is the branch that fires for the commonest "the path is
            // occupied" case. An operator grepping for "Failed to write the JUnit XML" has to
            // find it here too.
            log.error("Failed to write the {} to {}: {}",
                    kind != null ? kind.description() : "artifact",
                    failure.target().getAbsolutePath(),
                    ReportArtifacts.describe(failure.cause()), failure.cause());
            if (kind == ReportArtifacts.Kind.HTML) {
                reportFailure = failure.cause();
            }
        }
        if (reportFailure != null) {
            throw reportFailure;
        }
        log.info("Report written to: {}", artifacts.html().getAbsolutePath());
        // Every "written to" line lives here, keyed on the outcome AFTER the renames.
        //
        // The HTML and payload lines were moved here when staging was introduced; the CI/CD JSON
        // and JUnit XML lines were left behind at their stage sites and went on announcing a file
        // that had only been staged. A blocked target passes staging — the staging file has a
        // different name — and fails at the rename, so the two outcomes were BOTH logged for the
        // same artifact: "CI/CD JSON written to: X" immediately above "Failed to write the CI/CD
        // summary to X". An operator reading a log top to bottom sees the success first and it is
        // the more specific-looking of the two.
        announceIfWritten(artifacts, ReportArtifacts.Kind.DATA, "External data");
        announceIfWritten(artifacts, ReportArtifacts.Kind.JSON, "CI/CD JSON");
        announceIfWritten(artifacts, ReportArtifacts.Kind.XML, "JUnit XML");
    }

    /** One "written to" line, but only for an artifact that survived the rename. */
    private static void announceIfWritten(ReportArtifacts artifacts, ReportArtifacts.Kind kind,
                                          String label) {
        if (artifacts.outcome(kind) == ReportArtifacts.Outcome.WRITTEN) {
            log.info("{} written to: {}", label, artifacts.file(kind).getAbsolutePath());
        }
    }

    /**
     * The {@code .json}/{@code .xml} sibling of the resolved report name.
     *
     * <p>Safe because {@link #resolveFilename} guarantees the {@code .html} suffix — which is
     * also why this must never be called on a raw configured value.
     */
    private static String siblingName(String htmlFilename, String extension) {
        return htmlFilename.replaceFirst("\\.html$", extension);
    }

    /**
     * The external chart payload's name, derived from the report's.
     *
     * <p>It was a constant, and the template hardcoded it, while the report name was
     * configurable. So two large reports written into one directory shared a single payload and
     * the second run won — leaving the first report's server-rendered header cards and sampler
     * table describing run 1 while every chart, and every sampler filter checkbox, came from
     * run 2. Verified in a browser: the page lists filter controls for samplers that do not
     * appear in its own table. The template's only guard tests {@code window.__WIR_DATA__} for
     * presence, never identity, so nothing could detect it.
     *
     * <p><b>The default name is preserved deliberately.</b> {@code web-insight-report.html} keeps
     * {@code web-insight-data.js} rather than becoming {@code web-insight-report-data.js},
     * because E20 in {@code run-all-tests.sh} asserts that literal name — both that the file
     * exists and that the HTML references it — and README and FEATURES.md document it. Only a
     * configured report name gets a derived payload name, which is exactly the case where the
     * collision is possible.
     */
    static String externalDataFilename(String htmlFilename) {
        if (REPORT_FILENAME.equals(htmlFilename)) {
            return EXTERNAL_DATA_FILENAME;
        }
        return siblingName(htmlFilename, "-data.js");
    }

    /**
     * The duplicate-guard key: canonical where possible, case-folded always.
     *
     * <p>Case-folding unconditionally is deliberate even though Linux filesystems are
     * case-sensitive. The guard's job is to catch a misconfiguration, and two listeners
     * configured {@code Report.html} and {@code report.html} in one plan is a misconfiguration on
     * every platform — it is only <em>guaranteed</em> to destroy data on two of them. Warning on
     * Linux as well costs a false positive nobody will ever produce deliberately.
     */
    private static String guardKey(File html) {
        try {
            return html.getCanonicalPath().toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            // The file does not exist yet in the common case, and canonicalisation of a
            // non-existent path still resolves symlinked parents — but a genuinely broken path
            // must not cost a warning we could still emit.
            return html.getAbsolutePath().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Whose report is already sitting at this path?
     *
     * <p><b>Why identity and not the filename.</b> The two ingestion paths disagreed about a
     * pre-existing report: given the same directory, the listener overwrote and the {@code -g}
     * exporter skipped and wrote nothing. Neither is wrong on its own; the disagreement is. And
     * the exporter's version of the question was answered by a <em>name</em> — a glob derived
     * from the configured filename — which cannot distinguish "the listener in this very run
     * already wrote this" from "somebody else's report is in the way". One degenerate glob
     * ({@code ${timestamp}} as the whole name) matched JMeter's own {@code statistics.json} and
     * the run produced no Web Insight report at all, exit 0.
     *
     * <p>Identity answers the question that was actually being asked. The {@code .json} sibling
     * carries the run id of whatever produced it, and the identity contract guarantees that a
     * {@code -g} re-export of a run's own JTL derives the same id as the listener that ran it —
     * so "is this mine?" is decidable rather than guessed.
     *
     * <p><b>The fallback matters as much as the mechanism.</b> A missing, truncated or
     * version-older {@code .json} yields {@link ExistingReport#UNIDENTIFIED}, and both paths
     * treat that as "not provably mine" and overwrite with an announcement. That is the safe
     * direction: the alternative reading — "unsure, so skip" — is exactly the silent-no-output
     * failure this work exists to remove, and a visible duplicate or overwrite is a far better
     * outcome than a run that produces nothing and says nothing.
     */
    public static ExistingReport inspectExisting(File outputDir, String htmlFilename,
                                                 String runId) {
        File html = new File(outputDir, htmlFilename);
        File json = new File(outputDir, siblingName(htmlFilename, ".json"));
        if (!html.isFile() && !json.isFile()) {
            return ExistingReport.NONE;
        }
        String existingRunId = readRunId(json);
        if (existingRunId == null || existingRunId.isEmpty()) {
            return ExistingReport.UNIDENTIFIED;
        }
        return existingRunId.equals(runId) ? ExistingReport.THIS_RUN : ExistingReport.ANOTHER_RUN;
    }

    /**
     * The {@code runId} out of a CI/CD JSON, or null if it cannot be had.
     *
     * <p>Deliberately incurious about everything else in the file. A report written by an older
     * version has no such key; a file truncated by a crash may not parse at all; a file being
     * written right now by another process may be either. Every one of those is "cannot say",
     * which the caller already has a branch for.
     */
    public static String readRunId(File json) {
        if (!json.isFile()) {
            return null;
        }
        // Streamed, not readTree. This file carries a per-sampler summary and the full assertion
        // results, which on a wide soak run is megabytes — and every run would parse all of it to
        // read sixteen characters. `runId` is the first key the serializer writes, so a streaming
        // read stops within the first few tokens of the file.
        try (com.fasterxml.jackson.core.JsonParser parser =
                     new com.fasterxml.jackson.core.JsonFactory().createParser(json)) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                return null;
            }
            while (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                com.fasterxml.jackson.core.JsonToken value = parser.nextToken();
                if ("runId".equals(field)) {
                    return value == com.fasterxml.jackson.core.JsonToken.VALUE_STRING
                            ? parser.getText() : null;
                }
                // Some other top-level key: step over it whole, so a nested "runId" inside the
                // sampler array can never be mistaken for the document's own.
                parser.skipChildren();
            }
            return null;
        } catch (IOException | RuntimeException e) {
            // A file from a version before run identity, one truncated by a crash, or one being
            // written right now by another process. All of them mean "cannot say", which the
            // caller already has a branch for.
            log.debug("Could not read a run id from {}: {}", json.getAbsolutePath(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the configured report filename exactly the way report generation does.
     *
     * <p>Public and static so the {@code -g -o} exporter can ask the same question when it
     * checks whether the listener already wrote a report: deriving the expected name from the
     * raw property instead misses every configuration that this method rewrites — a
     * {@code ${timestamp}} placeholder, a name without the {@code .html} suffix, or a name
     * containing a path separator that falls back to the default.
     */
    public static String resolveReportFilename(String configured) {
        return resolveFilename(configured, REPORT_FILENAME, timestamp(), true);
    }

    /**
     * The configured pattern with {@code ${timestamp}} replaced by {@code *}, for callers that
     * must match a name generated at a different second than the one they are running in.
     */
    public static String reportFilenameGlob(String configured) {
        // Quiet: this is a QUESTION ("what would the listener have called it?"), not an act of
        // resolution. The exporter asks it on every run, so warning here duplicated every
        // rejection message — once for the query and once for the real write.
        return resolveFilename(configured, REPORT_FILENAME, "*", false);
    }

    /**
     * Seconds, not minutes.
     *
     * <p>It was {@code yyyyMMdd_HHmm}, and JMeter takes several seconds just to start, so two
     * back-to-back short runs land in the same minute and the second silently overwrites the
     * first — with {@code ${timestamp}} being the documented answer to "how do I keep my
     * reports". The suite knew: {@code run-config-tests.sh} slept 61 seconds purely so C14
     * could observe two distinct files.
     */
    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    /**
     * A stand-in for {@code ${timestamp}} while a configured name is being validated.
     *
     * <p>Exactly the shape {@link #timestamp} produces — fifteen characters, all of them in
     * {@link #SAFE_FILENAME} — so the character, position and length checks all see what the
     * resolved name will really look like, without their answer depending on the clock.
     */
    private static final String CANONICAL_STAMP = "00000000_000000";

    /**
     * Characters a report filename may contain.
     *
     * <p>An allowlist, for the same reason the shade guard is one: the previous check named the
     * offenders it knew about — {@code /}, {@code \} and a leading {@code ..} — and a colon
     * walked straight through, then blew up as {@code InvalidPathException} at write time. That
     * exception is an {@code IllegalArgumentException}, so it escaped the declared
     * {@code throws IOException} unchecked, and it fired only *after* the output directory had
     * been created and the chart payload written. Worse, the same name is perfectly legal on
     * Linux, so one configuration behaved differently per platform — precisely what the
     * separator check existed to prevent.
     *
     * <p>{@code \p{L}} and {@code \p{N}} rather than {@code A-Za-z0-9} so non-ASCII report names
     * keep working. Anything outside the set falls back to the default name with a warning,
     * which the intent line then shows — so a rejection is visible, not silent.
     */
    private static final Pattern SAFE_FILENAME = Pattern.compile("[\\p{L}\\p{N}._\\- ]+");

    /**
     * The longest configured name we will accept, before {@code .html} and the siblings.
     *
     * <p>Nothing bounded the resolved name, so a long one failed at write time with an NIO path
     * error — after the render. Most filesystems cap a single component at 255 <em>bytes</em>,
     * and a non-ASCII name is up to four bytes per character, so 180 characters is the
     * conservative reading that also leaves room for the {@code -data.js} suffix and the staging
     * prefix. Windows MAX_PATH is a limit on the whole path rather than the component, and we
     * cannot know the directory's length here, so this does not attempt to police it.
     */
    private static final int MAX_FILENAME_LENGTH = 180;

    /**
     * Names Windows resolves to a device rather than a file, in any directory, with or without
     * an extension.
     *
     * <p>{@code CON.html}, {@code NUL.html} and {@code COM1.html} all pass a character
     * allowlist and then fail — or, worse, silently succeed while writing to a device — only on
     * Windows. That is the same "one configuration, two platforms" class the colon fix was for,
     * so it is rejected everywhere rather than only where it breaks: a rule that depends on the
     * host is a rule nobody can predict.
     */
    private static final Set<String> RESERVED_DEVICE_NAMES = new HashSet<>(Arrays.asList(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"));

    /**
     * An unexpanded JMeter function or property reference that reached a rendered artifact.
     *
     * <p>{@code -J} values are never function-evaluated, while a JMX field is — so
     * {@code -Jwebinsight.report.title='${__P(env)}'} puts that literal string in the report AND
     * discards a working JMX title, because {@code -J} also wins precedence. It is always a
     * mistake, and it was always silent.
     */
    private static final Pattern UNEVALUATED_PLACEHOLDER = Pattern.compile("\\$\\{[^}]*\\}");

    /**
     * Names JMeter's own HTML dashboard writes into the very directory our exporter writes to.
     *
     * <p>Matched against the base name, so it covers the {@code .json} sibling too — which is
     * the half that matters more. Verified against a real {@code jmeter -g -o} run:
     * {@code -Jwebinsight.report.filename=index.html} replaced the dashboard's 9,672-byte entry
     * point with our 1.3 MB report, exit 0, no warning. And {@code =statistics} would put our
     * summary on top of {@code statistics.json}, the file most CI pipelines actually read.
     *
     * <p>Only these two. JMeter also writes {@code content/} and {@code sbadmin2-1.0.7/}, but
     * those are directories and every artifact we write carries an extension, so there is no
     * name to collide with — rejecting them would be a false positive on a report legitimately
     * called "content".
     *
     * <p>Rejected in every mode, not only under {@code -g -o}: the listener's output directory
     * can be the dashboard directory too, and a rule that depends on which ingestion path is
     * running is a rule nobody can predict.
     */
    private static final Set<String> RESERVED_DASHBOARD_NAMES =
            new HashSet<>(Arrays.asList("index", "statistics"));

    /**
     * @param timestampReplacement what {@code ${timestamp}} expands to — the formatted stamp for
     *                             a real resolution, or {@code *} to build a glob. Substituted
     *                             AFTER validation, so the replacement is never itself
     *                             validated: {@code *} is deliberately not in
     *                             {@link #SAFE_FILENAME}, and the previous implementation had to
     *                             smuggle a control-character sentinel through the validator to
     *                             build a glob — which an allowlist would reject outright.
     */
    private static String resolveFilename(String configured, String defaultName,
                                          String timestampReplacement, boolean warn) {
        if (configured == null || configured.isEmpty()) {
            return defaultName;
        }

        // Validate against a CANONICAL stamp rather than the real one, and never against the
        // placeholder simply deleted.
        //
        // Substituting the real stamp would validate a value that is ours rather than the
        // operator's, and would make the answer depend on the second the run happened — which is
        // also why the glob variant, which substitutes '*', must not be validated: '*' is
        // deliberately absent from SAFE_FILENAME. Deleting the placeholder avoided both, and
        // broke every check that depends on WHERE a character sits. 'backup.${timestamp}'
        // collapsed to "backup.", which ends with a dot, so a name that resolves to the perfectly
        // legal 'backup.20260101_120000.html' was rejected and silently replaced by the shared
        // default — losing the one thing ${timestamp} exists to provide, a name per run. The same
        // held for any name ending in a space before the placeholder, and the length check
        // under-counted by the fifteen characters the stamp actually occupies.
        //
        // A canonical stamp is the same length and the same character class as a real one, so
        // every check below asks its question about a faithful stand-in — and asks it identically
        // whether this call is resolving a name or building a glob, which it must, because the
        // exporter finds the listener's report by globbing what this method would have produced.
        String toValidate = configured.replace("${timestamp}", CANONICAL_STAMP);

        // ...except the length check, which keeps measuring the name WITHOUT the stamp, and that
        // is a separate question rather than an oversight. The checks above ask "what will this
        // name look like on disk", where the stamp is fifteen real characters. The limit asks
        // "did the operator ask for too much", and they did not type the stamp — so a name that
        // only crosses 180 once OUR placeholder expands is not theirs to have rejected. 180 is
        // already a conservative reading of a 255-byte component cap.
        String typedLength = configured.replace("${timestamp}", "");

        // The configured name is joined onto the output directory and reused to derive the
        // .json/.xml/-data.js siblings, so a value like "../../evil" would write outside it.
        // This is operator-supplied config, not attacker input, but a report filename has no
        // business containing a path.
        //
        // A plain ".." SUBSTRING is harmless and appears in ordinary names like
        // "load..final.html", so only real path syntax is rejected.
        boolean hasSeparator = toValidate.indexOf('/') >= 0 || toValidate.indexOf('\\') >= 0;
        boolean isParentRef = toValidate.equals("..") || toValidate.startsWith("../")
                || toValidate.startsWith("..\\");
        boolean unsafeChars = !toValidate.isEmpty() && !SAFE_FILENAME.matcher(toValidate).matches();
        if (hasSeparator || isParentRef || unsafeChars) {
            if (warn) {
                log.warn("Ignoring report filename '{}': it must be a plain file name — letters, "
                        + "digits, dot, underscore, hyphen and space only, with no path "
                        + "separators. Using '{}' instead.", configured, defaultName);
            }
            return withHtmlSuffix(defaultName);
        }

        // Windows strips a trailing dot or space from a filename rather than rejecting it, so
        // "report " and "report." either fail or silently become something else — and behave
        // perfectly on Linux. One configuration, two platforms, which is the class the
        // separator and colon checks already exist to close.
        if (!toValidate.isEmpty()
                && (toValidate.endsWith(" ") || toValidate.endsWith("."))) {
            if (warn) {
                log.warn("Ignoring report filename '{}': a name may not end with a dot or a "
                        + "space — Windows silently strips both, so the file would not be where "
                        + "you asked for it. Using '{}' instead.", configured, defaultName);
            }
            return withHtmlSuffix(defaultName);
        }

        if (typedLength.length() > MAX_FILENAME_LENGTH) {
            if (warn) {
                log.warn("Ignoring report filename '{}': it is {} characters and the limit is {}. "
                        + "Most filesystems cap one path component at 255 bytes, and the .json, "
                        + ".xml and -data.js siblings are derived from this name. Using '{}' "
                        + "instead.", configured, typedLength.length(), MAX_FILENAME_LENGTH,
                        defaultName);
            }
            return withHtmlSuffix(defaultName);
        }

        String resolved = withHtmlSuffix(configured.replace("${timestamp}", timestampReplacement));
        String base = baseName(resolved).toLowerCase(Locale.ROOT);

        if (RESERVED_DASHBOARD_NAMES.contains(base)) {
            if (warn) {
                log.warn("Ignoring report filename '{}': '{}' is a name JMeter's own HTML "
                        + "dashboard writes into the output directory, so the report (or its "
                        + ".json sibling) would overwrite it. Using '{}' instead.",
                        configured, resolved, defaultName);
            }
            return withHtmlSuffix(defaultName);
        }

        if (isReservedDeviceName(base)) {
            if (warn) {
                log.warn("Ignoring report filename '{}': '{}' resolves to a Windows device rather "
                        + "than a file, in any directory. Using '{}' instead.",
                        configured, resolved, defaultName);
            }
            return withHtmlSuffix(defaultName);
        }
        return resolved;
    }

    /**
     * Is this base name one Windows resolves to a device?
     *
     * <p>The rule is not "equals CON": everything up to the first dot counts, so
     * {@code CON.anything} is the device too — and trailing spaces are ignored by the OS, so
     * {@code "CON "} is as well. Both are checked here rather than only the exact match, because
     * the near-misses are the ones that would have got through a naive comparison and failed only
     * on one platform.
     */
    private static boolean isReservedDeviceName(String lowerCaseBaseName) {
        int dot = lowerCaseBaseName.indexOf('.');
        String head = (dot >= 0 ? lowerCaseBaseName.substring(0, dot) : lowerCaseBaseName).trim();
        return RESERVED_DEVICE_NAMES.contains(head);
    }

    /**
     * Warn when a {@code ${...}} reference reaches a rendered artifact unexpanded.
     *
     * <p>It is always a mistake, and it was always silent. {@code -J} values are handed to us
     * verbatim — JMeter evaluates functions in JMX fields, not in properties — so
     * {@code -Jwebinsight.report.title='${__P(env)}'} both prints that literal string in the
     * report and discards whatever the JMX field said, because {@code -J} also wins precedence.
     *
     * <p>{@code ${timestamp}} is exempt because it is <em>ours</em> and it is the documented way
     * to make report names unique. It is worth knowing that it is a second clock: a JMeter
     * function in the JMX field is evaluated at {@code testStarted}, while {@code ${timestamp}}
     * is expanded when the report is generated — so the same field can carry two stamps taken
     * minutes apart.
     */
    private static void warnOnUnevaluatedPlaceholder(String what, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String withoutOurs = value.replace("${timestamp}", "");
        java.util.regex.Matcher m = UNEVALUATED_PLACEHOLDER.matcher(withoutOurs);
        if (m.find()) {
            log.warn("The report {} contains '{}', which reached the report unexpanded. JMeter "
                    + "evaluates functions in JMX fields, never in -J property values, and -J "
                    + "takes precedence — so this literal has also displaced any value set in "
                    + "the test plan. Set the value in the JMX field, or pass an already-expanded "
                    + "string. (${timestamp} is the plugin's own placeholder and is expanded at "
                    + "generation time — it is exempt from this warning.)", what, m.group());
        }
    }

    /**
     * Append {@code .html} unless it is already there — case-insensitively, and replacing another
     * extension rather than stacking on top of it.
     *
     * <p>The old check was {@code endsWith(".html")}: exact case and purely additive, so
     * {@code MyReport.HTML} became {@code MyReport.HTML.html} with siblings
     * {@code MyReport.HTML.json} and {@code MyReport.HTML.xml}, and {@code report.htm} and
     * {@code report.json} did the same. The worst case was a configured {@code .html}, which
     * passed the check untouched and produced three dotfiles — {@code .html}, {@code .json},
     * {@code .xml} — invisible to {@code ls} on Unix.
     */
    private static String withHtmlSuffix(String name) {
        if (name.isEmpty() || name.equalsIgnoreCase(".html") || name.equalsIgnoreCase(".htm")) {
            // Nothing but an extension: there is no base name to derive siblings from.
            return REPORT_FILENAME;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            // Normalise the case, because the .json/.xml siblings are derived with a
            // replaceFirst("\\.html$") that assumes lower case.
            return name.substring(0, name.length() - ".html".length()) + ".html";
        }
        if (lower.endsWith(".htm")) {
            return name.substring(0, name.length() - ".htm".length()) + ".html";
        }
        if (lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".js")) {
            // Otherwise "report.json" yields report.json.html alongside report.json.json.
            return name.substring(0, name.lastIndexOf('.')) + ".html";
        }
        return name + ".html";
    }

    /** The resolved name without its {@code .html} suffix. */
    private static String baseName(String htmlFilename) {
        return htmlFilename.toLowerCase(Locale.ROOT).endsWith(".html")
                ? htmlFilename.substring(0, htmlFilename.length() - ".html".length())
                : htmlFilename;
    }

    /**
     * Build ComparisonThresholds from annotations JSON and/or JMeter -J properties.
     * JMeter properties override annotation values.
     */
    private ComparisonThresholds buildComparisonThresholds(ReportAnnotations annotations) {
        return ComparisonThresholdResolver.resolve(annotations,
                org.apache.jmeter.util.JMeterUtils::getProperty);
    }

    /**
     * Write the CI/CD summary. Failure does not abort the run — a report is still worth
     * having without it — but it is recorded, and it is an ERROR.
     *
     * <p>It was a WARN, which put it in {@code jmeter.log} only, while the console printed
     * unqualified success. A pipeline gating on this file therefore learned nothing from any
     * channel it was watching: reproduced by pre-creating the {@code .json} as a directory,
     * which yields exit 0, a cheerful "report generated" line, and no JSON.
     */
    private void stageCiCdJson(ArtifactPublication publication, ReportData reportData,
                               SamplerVerdicts assertResults, AssertionStatus overallStatus,
                               ReportArtifacts artifacts, GateEvaluation evaluation,
                               com.jmeterwebinsightreport.report.gate.ServerGateEvaluation serverGates) {
        File cicdFile = artifacts.file(ReportArtifacts.Kind.JSON);
        try {
            // One findings list: the client's, then the server block's. A finding is a finding
            // whichever side of the file it is about, and a pipeline reads one array.
            java.util.List<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding> findings =
                    new java.util.ArrayList<>(evaluation.findings());
            if (serverGates != null) {
                findings.addAll(serverGates.findings());
            }
            String cicdJson = serializer.serializeCiCdJson(reportData, assertResults, overallStatus,
                    artifacts.manifest(), evaluation.gate(), findings, serverGates);
            publication.stage(cicdFile, cicdJson);
            // Staged, not written — the "written to" line is emitted by publishOrRecord once the
            // rename has actually happened. See the note there.
            artifacts.written(ReportArtifacts.Kind.JSON);
        } catch (Exception e) {
            artifacts.failed(ReportArtifacts.Kind.JSON, e);
            log.error("Failed to write the {} to {}: {}",
                    ReportArtifacts.Kind.JSON.description(), cicdFile.getAbsolutePath(),
                    ReportArtifacts.describe(e), e);
        }
    }

    /** Same contract as {@link #stageCiCdJson}: swallowed, recorded, and logged at ERROR. */
    private void stageJunitXml(ArtifactPublication publication, ReportData reportData,
                               SamplerVerdicts assertResults, ReportArtifacts artifacts,
                               GateEvaluation evaluation,
                               com.jmeterwebinsightreport.report.gate.ServerGateEvaluation serverGates) {
        File xmlFile = artifacts.file(ReportArtifacts.Kind.XML);
        try {
            String testName = configuration.getReportTitle();

            JunitXmlWriter writer = new JunitXmlWriter();
            publication.stage(xmlFile,
                    writer.render(reportData, assertResults, testName, evaluation.gate(), serverGates));
            // Staged, not written. @see #stageCiCdJson
            artifacts.written(ReportArtifacts.Kind.XML);
        } catch (Exception e) {
            artifacts.failed(ReportArtifacts.Kind.XML, e);
            log.error("Failed to write the {} to {}: {}",
                    ReportArtifacts.Kind.XML.description(), xmlFile.getAbsolutePath(),
                    ReportArtifacts.describe(e), e);
        }
    }

    private String getCachedCss() throws IOException {
        String result = cachedCss;
        if (result == null) {
            result = readClasspathResource("static/css/report.css");
            cachedCss = result;
        }
        return result;
    }

    private String getCachedJs() throws IOException {
        String result = cachedJs;
        if (result == null) {
            result = readClasspathResource("static/js/report.js");
            cachedJs = result;
        }
        return result;
    }

    private String getCachedEchartsJs() throws IOException {
        String result = cachedEchartsJs;
        if (result == null) {
            result = readClasspathResource("static/js/echarts.min.js");
            cachedEchartsJs = result;
        }
        return result;
    }

    /**
     * Read one of the plugin's own bundled resources (CSS, report JS, ECharts).
     *
     * <p>Fails loudly. Returning "" on a missing resource produced reports that were broken
     * in ways nothing announced: an empty report.js yields a chartless page with no error at
     * all, and an empty ECharts bundle flips the template to a CDN &lt;script src&gt;, quietly
     * contradicting the "fully self-contained report" promise and breaking on air-gapped
     * machines. Both are symptoms of a bad shade/repackage, which is exactly when you want to
     * hear about it.
     */
    private String readClasspathResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Bundled resource missing from the plugin JAR: " + path
                        + ". The JAR is incomplete — rebuild it (mvn clean package).");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
