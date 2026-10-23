package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.report.gate.ServerGateEvaluation;

import java.io.File;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every file one report generation is going to write, what happened to each, and the
 * operator-facing text describing it.
 *
 * <p><b>Why this is a value type and not just log statements.</b> The plugin writes four
 * artifacts and, until this class existed, announced three of them only through
 * {@code log.info} — which in JMeter's default configuration reaches {@code jmeter.log} and
 * the GUI panel and nothing else, because {@code log4j2.xml} ships no Console appender. The
 * one line that did reach a terminal named only the HTML. So a pipeline gating on the CI/CD
 * JSON had no way to learn, from any channel it was watching, whether that file existed.
 *
 * <p>The messages are built here, as pure functions over resolved state, for a second
 * reason: there is no slf4j binding on the test classpath (the only test-scoped dependency
 * is junit-jupiter, and {@code slf4j-api} is {@code provided}), so a logger in a unit test
 * is a no-op and log text asserted through a logging framework is not assertable at all.
 * Keeping {@link #intentLine()}, {@link #consoleSummary()} and {@link #failureLine} as
 * Strings means the wording is covered by ordinary unit tests, and the logger and the
 * {@code println} become dumb emitters. Adding a log-capture test dependency instead would
 * have bought the same coverage at the cost of a dependency, and this way the shell
 * scenarios only have to prove the string <i>reached</i> the log, not what it says.
 *
 * <p>Not thread-safe, and does not need to be: one instance belongs to one
 * {@code generateReport} call.
 */
public final class ReportArtifacts {

    /** The four files a run can produce. Order is the order they are reported in. */
    public enum Kind {
        HTML("HTML", "report"),
        JSON("JSON", "CI/CD JSON"),
        XML("XML ", "JUnit XML"),
        DATA("DATA", "external chart payload");

        private final String label;
        private final String description;

        Kind(String label, String description) {
            this.label = label;
            this.description = description;
        }

        /** Padded to four characters so the summary block's colons line up. */
        String label() {
            return label;
        }

        /**
         * What to call this artifact in a sentence.
         *
         * <p>One vocabulary for both halves of a write. Staging and publishing can each fail,
         * and they fail in different places for different reasons — a blocked target passes
         * staging (the staging file has a different name) and only fails at the rename. If the
         * two logged different phrasings, an operator grepping for "Failed to write the JUnit
         * XML" would find nothing in exactly the case they were looking for, which is what
         * happened the first time this ran.
         */
        public String description() {
            return description;
        }
    }

    /**
     * What became of one artifact.
     *
     * <p>{@link #PLANNED} is the initial state and should never survive to the summary: it
     * means the generator resolved a name for the file and then neither wrote it, failed to
     * write it, nor deliberately skipped it. Reporting it as such is better than silently
     * printing nothing, which is the behaviour this whole class replaces.
     */
    public enum Outcome { PLANNED, WRITTEN, SKIPPED, FAILED }

    private static final class Artifact {
        private final String filename;
        private Outcome outcome = Outcome.PLANNED;
        /** Why it was skipped ("disabled", "inline") — or the diagnosis when it failed. */
        private String note;

        Artifact(String filename) {
            this.filename = filename;
        }
    }

    private final File outputDir;
    private final Map<Kind, Artifact> artifacts = new EnumMap<>(Kind.class);
    /** Only meaningful for DATA: was external mode forced up front, or is it size-triggered? */
    private final boolean externalDataForced;
    private final long externalDataThreshold;

    /**
     * @param outputDir             the resolved output directory; may be null, meaning the
     *                              process working directory, which is what
     *                              {@code new File((File) null, name)} resolves against
     * @param htmlFilename          the resolved HTML name — never null
     * @param jsonFilename          the resolved CI/CD JSON name — never null
     * @param xmlFilename           the resolved JUnit XML name, or null when disabled
     * @param dataFilename          the external chart payload name — never null, because the
     *                              size threshold can promote an inline run to external at
     *                              any point and the name has to be decided before then
     * @param externalDataForced    true when {@code webinsight.report.externaldata=true}
     * @param externalDataThreshold the size above which an inline run becomes external
     */
    public ReportArtifacts(File outputDir, String htmlFilename, String jsonFilename,
                           String xmlFilename, String dataFilename,
                           boolean externalDataForced, long externalDataThreshold) {
        this.outputDir = outputDir;
        this.externalDataForced = externalDataForced;
        this.externalDataThreshold = externalDataThreshold;
        artifacts.put(Kind.HTML, new Artifact(htmlFilename));
        artifacts.put(Kind.JSON, new Artifact(jsonFilename));
        artifacts.put(Kind.DATA, new Artifact(dataFilename));
        if (xmlFilename != null) {
            artifacts.put(Kind.XML, new Artifact(xmlFilename));
        }
        if (!externalDataForced) {
            // Inline until something says otherwise. Recorded as a skip rather than left
            // PLANNED so an inline run's summary reads "DATA : inline" instead of implying
            // a file went missing.
            skipped(Kind.DATA, "inline");
        }
    }

    /**
     * The gate this run was judged by, or null when generation never got that far.
     *
     * <p>Recorded rather than passed to {@link #consoleSummary()} because the artifact set is
     * built before the run is judged — the filenames have to be decided first — and one object
     * carrying "what this generation did" is easier to keep honest than two.
     */
    private GateSummary gate;
    private int configErrors;
    private String loadProblem;
    private String serverMetricsProblem;
    private ServerGateEvaluation serverGates;

    /** The gate this run was judged by, or null when nothing was declared. */
    public GateSummary gate() {
        return gate;
    }

    /** Called once, immediately after {@code GateEvaluation}. Null is accepted and means no gate. */
    public void recordGate(GateSummary gate) {
        this.gate = gate;
    }

    /**
     * The server gates, judged — or not — beside the client gate. The fold lives here, not in
     * the two ingestion paths: {@link #hasConfigProblem()} grows by what the evaluation says is
     * fatal (D3/D4), {@link #serverFailsBuild()} is what {@code failonassert} tests beside the
     * client FAIL, and {@link #verdictLine()} gains one clause per gate. Null is accepted and
     * means no server block.
     */
    public ReportArtifacts recordServerGates(ServerGateEvaluation evaluation) {
        this.serverGates = evaluation;
        return this;
    }

    private long runStart = -1;
    private long runEnd = -1;

    /** The run's window in epoch ms, for the NEXT block. */
    public ReportArtifacts recordRunWindow(long start, long end) {
        this.runStart = start;
        this.runEnd = end;
        return this;
    }

    /**
     * What to do next when the file declares server asserts and nothing was collected for this
     * report (the interim report, or a -g run without server data), or null otherwise: the window
     * to export, the series and the names a CSV must carry, and the command that adds it - the same
     * values the interim Server tab shows and the CI/CD JSON's window and serverData carry.
     *
     * @param jtl the JTL's path when this path knows it (the exporter), or null (the listener)
     */
    public String nextStepsLine(String jtl) {
        if (serverGates == null || !serverGates.declared() || serverGates.evaluated()
                || serverGates.payloadRefusal() != null) {
            return null;
        }
        // The asserts requiredSeries() reads: a disabled one waits for nothing, so it is not counted,
        // and a file whose every assert is disabled has no next step.
        int waiting = 0;
        for (ServerKpi kpi : serverGates.config().kpis()) {
            if (kpi.enabled() && kpi.gate() != null) {
                waiting++;
            }
        }
        if (waiting == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("WEB-INSIGHT-REPORT: NEXT — ")
                .append(waiting).append(" server assert(s) wait for server data.");
        if (runStart >= 0 && runEnd >= 0) {
            sb.append(" Export ").append(java.time.Instant.ofEpochMilli(runStart)).append(" .. ")
                    .append(java.time.Instant.ofEpochMilli(runEnd)).append(" (epoch ms ").append(runStart)
                    .append(" .. ").append(runEnd).append(", a minute either side is fine)");
        }
        List<String> names = new ArrayList<>();
        for (String[] s : serverGates.requiredSeries()) {
            names.add(s[0] + "/" + s[1]);
        }
        if (!names.isEmpty()) {
            sb.append(" as a CSV (time,component,metric,instance,value) with these component/metric names: ")
                    .append(String.join(", ", names));
        }
        sb.append(". Then rebuild: jmeter -g ").append(jtl != null ? jtl : "<the run's JTL>")
                .append(" -o <a new folder> -q <a properties file naming webinsight.report.annotations and")
                .append(" webinsight.report.servermetrics>");
        return sb.toString();
    }

    /** The server gates as recorded, or null. */
    public ServerGateEvaluation serverGates() {
        return serverGates;
    }

    /** True when any server component FAILED — the other half of {@code failonassert}. */
    public boolean serverFailsBuild() {
        return serverGates != null && serverGates.failsBuild();
    }

    /**
     * What the FILE got wrong: rules that could not run, and an annotations file that could not
     * be read at all.
     *
     * <p>Deliberately NOT folded into {@link #anyFailed()}. That question is "did a planned
     * artifact fail to be written", and {@link #incompleteReason()} builds its sentence out of
     * artifact names — so widening it would print
     * {@code INCOMPLETE - the JUnit XML could not be written} for a mistyped metric. Two
     * questions, two predicates, and one flag over both.
     *
     * @param configErrors how many declared checks never ran — {@code GateSummary.configErrors()},
     *                     which {@code NotJudged.isConfigError()} decides per reason
     * @param loadProblem  why the annotations file could not be read at all, or null
     */
    public ReportArtifacts recordConfigProblem(int configErrors, String loadProblem) {
        this.configErrors = configErrors;
        this.loadProblem = loadProblem;
        return this;
    }

    /**
     * True when the gate could not be BUILT from what the file said.
     *
     * <p>Not a verdict, and never confused with one: a rule whose metric does not exist means
     * the gate is not running while its author believes it is, which is the same category as an
     * output directory that cannot be written to. The tool reporting that it could not carry out
     * the instruction — not the tool judging the system under test.
     */
    public boolean hasConfigProblem() {
        return hasClientConfigProblem() || hasServerConfigProblem();
    }

    /**
     * The client half of {@link #hasConfigProblem()}: a client rule that could not run, or an
     * annotations file that could not be read at all (which loses the client rules with it).
     * What {@code failonerror} acts on.
     */
    public boolean hasClientConfigProblem() {
        return configErrors > 0 || loadProblem != null;
    }

    /**
     * The server half: a server check the file asked for never ran — a failed collection, coverage
     * under the floor, a server assert that cannot run, a refused or missing payload. What
     * {@code webinsight.report.server.failonerror} acts on; with it off (the default) these are
     * said loudly and never change the exit code, because server data is a bonus on top of the
     * client report.
     */
    public boolean hasServerConfigProblem() {
        return serverMetricsProblem != null
                || (serverGates != null && serverGates.fatalConfigErrors() > 0);
    }

    /** The client half of {@link #configProblemReason()}, or an empty string. */
    public String clientConfigProblemReason() {
        if (loadProblem != null) {
            return loadProblem + " — no gate ran";
        }
        if (configErrors > 0) {
            return configErrors + " declared check(s) never ran — see the findings and the gate's "
                    + "not-judged reasons; the gate did not judge what the file declared";
        }
        return "";
    }

    /**
     * The console marker for a client config problem, or null. {@code CONFIG ERROR} keeps
     * meaning "this can exit non-zero": it is printed only for the side {@code failonerror} acts on.
     */
    public String clientConfigMarker() {
        return hasClientConfigProblem()
                ? "WEB-INSIGHT-REPORT: CONFIG ERROR — " + clientConfigProblemReason() : null;
    }

    /**
     * The console marker for a server-data problem, or null. Under the opt-in it is a
     * {@code CONFIG ERROR} like the client's, because then it exits non-zero; otherwise it says the
     * check never ran and that the report and the client verdict stand.
     *
     * @param fatal whether {@code webinsight.report.server.failonerror} is on
     */
    public String serverConfigMarker(boolean fatal) {
        if (!hasServerConfigProblem()) {
            return null;
        }
        if (fatal) {
            return "WEB-INSIGHT-REPORT: CONFIG ERROR — " + serverConfigProblemReason();
        }
        return "WEB-INSIGHT-REPORT: SERVER NOT JUDGED — " + serverConfigProblemReason()
                + ". The report and the client verdict are complete and the exit code is unchanged"
                + " (set " + NonZeroExit.FAIL_ON_SERVER_ERROR + "=true to make this fatal)";
    }

    /** The server half of {@link #configProblemReason()}, or an empty string. */
    public String serverConfigProblemReason() {
        StringBuilder reason = new StringBuilder();
        if (serverMetricsProblem != null) {
            reason.append(serverMetricsProblem);
        }
        String server = serverGates == null ? null : serverGates.reportableConfigReason();
        if (server != null) {
            if (reason.length() > 0) {
                reason.append("; ");
            }
            reason.append(server);
        }
        return reason.toString();
    }

    /**
     * True when the file got something wrong that a human should be told about — which is a
     * wider question than {@link #hasConfigProblem()}, and the two must not be conflated.
     *
     * <p>D4 gives {@code failsBuild: false} exactly one power: it silences the <em>exit code</em>
     * for that gate's collection failures. "The marker always prints" is the rest of the same
     * decision. Reading the fatal predicate for the marker as well silenced both, so an observe
     * gate whose token had rotated produced a report, exit 0, and not one word saying its KPIs
     * were never collected — indistinguishable on the console from a gate that passed.
     */
    public boolean hasReportableConfigProblem() {
        return configErrors > 0 || loadProblem != null || serverMetricsProblem != null
                || (serverGates != null && serverGates.reportableConfigErrors() > 0);
    }

    /**
     * The server-metrics payload could not be used — missing, unreadable, another schema's, or
     * another run's — and an enabled assert sits in a gate that builds. A third kind of "the file
     * asked for a check that never ran": every server assert the annotations declared went
     * unjudged because the collector's file did. The generator passes
     * {@code ServerGateEvaluation.fatalPayloadProblem()}, which is null otherwise (A1-7): that case
     * is a WARNING finding, not this.
     *
     * @param problem one sentence naming the file and what is wrong, or null
     */
    public ReportArtifacts recordServerMetricsProblem(String problem) {
        this.serverMetricsProblem = problem;
        return this;
    }

    /**
     * One sentence for the console and for the exception that fails the run — every recorded
     * problem, not the first: a run with one broken client rule and a foreign payload used to
     * print the rule count and never mention the payload, on exactly the runs with two things
     * wrong. The payload's sentence here is the console's, with the absolute path and any parser
     * message; its ERROR finding ({@code serverMetrics.payload}) carries the published sentence
     * instead, and is a finding rather than a counted problem, so it adds nothing to the server
     * clause below — one refused payload is said once here, not twice.
     */
    public String configProblemReason() {
        String client = clientConfigProblemReason();
        String server = serverConfigProblemReason();
        if (client.isEmpty()) {
            return server;
        }
        return server.isEmpty() ? client : client + "; " + server;
    }

    /**
     * The verdict, as one line, for a terminal.
     *
     * <p><b>Why this exists.</b> Nothing that reaches a terminal used to carry it.
     * {@code consoleSummary()} enumerated artifact paths and no status word;
     * {@code GateEvaluation.logSummary} wrote the one gate line at {@code log.info}, to a
     * log4j2 configuration that ships no Console appender, <em>and returned early when there
     * was no gate at all</em>. So a failing gate printed "Web Insight Report generated: HTML :
     * …" and exited 0, and a gate that had silently stopped gating printed the same thing and
     * logged nothing anywhere. Both are the failure this whole feature exists to prevent, and
     * neither was visible on the one channel every operator actually watches.
     *
     * <p>The gateless case is stated rather than omitted, for the same reason the header badge
     * no longer says "ALL PASSED": nothing declared is a fact worth reporting, and silence
     * reads as success.
     */
    public String verdictLine() {
        StringBuilder sb = new StringBuilder();
        if (gate == null || !gate.hasGate()) {
            sb.append("Asserts: NOT EVALUATED — nothing was declared, so nothing was judged");
        } else {
            sb.append("Asserts: ").append(gate.status() == null ? "NOT EVALUATED" : gate.status().name());
            appendCounts(sb, gate);
        }
        // One clause per server gate, the same shape, the word "Server" and the gate's id in
        // front of it: "Server kafka: FAIL — 3 declared, 2 passed, 1 failed". No rollup across
        // gates, here or anywhere (D17). A block that was declared and never judged says so,
        // because on the interim report that is the expected state and silence would read as
        // nothing declared.
        if (serverGates != null && serverGates.declared()) {
            if (!serverGates.evaluated()) {
                // "No payload given" is false when one was given and refused, and it sends the
                // reader to re-run the step that produced the refused file. The refusal's own
                // sentence is in the CONFIG ERROR reason; this names which state it is — in the
                // header chip's word for it (NOT COLLECTED or REFUSED, SF47), so the console and
                // the page describe one run in one word.
                sb.append("; Server: ").append(serverGates.headerChip().getStatusLabel()).append(" — ")
                        .append(serverGates.config().kpis().size())
                        .append(serverGates.payloadRefusal() != null
                                ? " server assert(s) declared, the payload given was refused"
                                : " server assert(s) declared, no payload given");
            }
            // Every REPORTED component, the reserved entry included and named as the Server tab
            // names it (SF22). Walking the declared ones alone said nothing about the asserts that
            // named none - which exit 1 - and nothing about the server at all on a file that
            // declares no component.
            for (Map.Entry<String, GateSummary> e : serverGates.reportedGates().entrySet()) {
                GateSummary g = e.getValue();
                sb.append("; Server ").append(ServerGateEvaluation.displayId(e.getKey()));
                sb.append(": ").append(g.status() == null ? "NOT EVALUATED" : g.status().name());
                appendCounts(sb, g);
            }
        }
        return sb.toString();
    }

    /** " — N declared, a passed, b warned, c failed, d could not run, …" — zero counts omitted. */
    private static void appendCounts(StringBuilder sb, GateSummary gate) {
        sb.append(" — ").append(gate.declared()).append(" declared");
        appendCount(sb, gate.passed(), "passed");
        appendCount(sb, gate.warned(), "warned");
        appendCount(sb, gate.failed(), "failed");
        // Config errors first among the not-judged: those are the reasons that mean a check the
        // FILE asked for never ran, and they are what this line most needs to put in front of
        // someone who is reading a console and nothing else. NotJudged.displayOrder(), not a
        // hand-written list (which named four reasons and would have gone on naming four after
        // the fifth existed) and not values() (which is the wire order, and put "disabled"
        // ahead of "unmatched" on every run that had both).
        for (NotJudged r : NotJudged.displayOrder()) {
            appendCount(sb, gate.notJudged(r), r.label());
        }
    }

    /** True when the client gate FAILED or any building server gate did — what {@code failonassert} acts on. */
    public boolean anyGateFailsBuild() {
        return (gate != null && gate.status() == AssertionStatus.FAIL) || serverFailsBuild();
    }

    /** Zero counts are omitted — a clean gate should read as one short line, not nine zeroes. */
    private static void appendCount(StringBuilder sb, int count, String label) {
        if (count > 0) {
            sb.append(", ").append(count).append(' ').append(label);
        }
    }

    // ---- state -------------------------------------------------------------------

    /** The absolute output directory, resolved against the working directory when null. */
    public File outputDirectory() {
        return outputDir != null ? outputDir.getAbsoluteFile()
                : new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    }

    /** The resolved filename for one artifact, or null when it was never planned (XML). */
    public String filename(Kind kind) {
        Artifact a = artifacts.get(kind);
        return a != null ? a.filename : null;
    }

    /**
     * Where one artifact goes. Null only for an artifact that was never planned.
     *
     * <p>Built with the two-argument {@code File} constructor rather than string
     * concatenation so a null output directory keeps resolving against the process working
     * directory, which is the documented default for the listener.
     */
    public File file(Kind kind) {
        Artifact a = artifacts.get(kind);
        return a != null ? new File(outputDir, a.filename) : null;
    }

    /** The report itself — the one artifact every caller ultimately wants. */
    public File html() {
        return file(Kind.HTML);
    }

    /**
     * Which artifact this file is, or null when it is not one of ours.
     *
     * <p>The publish step hands back the files it could not move, and they have to be turned
     * back into outcomes. Comparing {@link File} values rather than paths is deliberate: these
     * are the very objects {@link #file} produced, so equality is exact and no normalisation
     * question arises.
     */
    public Kind kindOf(File file) {
        if (file == null) {
            return null;
        }
        for (Kind kind : Kind.values()) {
            if (file.equals(file(kind))) {
                return kind;
            }
        }
        return null;
    }

    public Outcome outcome(Kind kind) {
        Artifact a = artifacts.get(kind);
        return a != null ? a.outcome : Outcome.SKIPPED;
    }

    public ReportArtifacts written(Kind kind) {
        Artifact a = artifacts.get(kind);
        if (a != null) {
            a.outcome = Outcome.WRITTEN;
            a.note = null;
        }
        return this;
    }

    /** @param why short reason shown in place of a path: "disabled", "inline". */
    public ReportArtifacts skipped(Kind kind, String why) {
        Artifact a = artifacts.get(kind);
        if (a != null) {
            a.outcome = Outcome.SKIPPED;
            a.note = why;
        }
        return this;
    }

    /**
     * Record that one artifact could not be written.
     *
     * <p>The JSON and XML writers swallow their exceptions by design — a report is still
     * worth having when its CI/CD sibling fails — but until this existed the swallow was
     * total: the console printed unqualified success while the machine-readable file a
     * pipeline consumes was absent.
     */
    public ReportArtifacts failed(Kind kind, Throwable cause) {
        Artifact a = artifacts.get(kind);
        if (a != null) {
            a.outcome = Outcome.FAILED;
            a.note = describe(cause);
        }
        return this;
    }

    /**
     * The manifest embedded in the CI/CD JSON: absolute path and outcome per artifact.
     *
     * <p>Built here rather than in the serializer so there is exactly one place that knows what
     * was written. The JSON's own entry is included and is always {@code WRITTEN} by construction
     * — it is being written as this is serialized — which is the one honest inaccuracy in the
     * file and worth less than omitting its own path.
     */
    public Map<String, Object> manifest() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("outputDirectory", outputDirectory().getPath());
        for (Kind kind : Kind.values()) {
            Artifact a = artifacts.get(kind);
            if (a == null) {
                continue; // never planned — JUnit XML disabled
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("path", file(kind).getAbsolutePath());
            entry.put("outcome", (kind == Kind.JSON && a.outcome == Outcome.PLANNED
                    ? Outcome.WRITTEN : a.outcome).name());
            if (a.note != null) {
                entry.put("detail", a.note);
            }
            out.put(kind.name().toLowerCase(java.util.Locale.ROOT).trim(), entry);
        }
        return out;
    }

    /** True when any planned artifact failed — the JSON/XML swallow made this invisible. */
    public boolean anyFailed() {
        for (Artifact a : artifacts.values()) {
            if (a.outcome == Outcome.FAILED) {
                return true;
            }
        }
        return false;
    }

    /**
     * One sentence naming what failed and why, for the exception that fails the run.
     *
     * <p>The JSON and XML writers swallow their exceptions by design — a report is still worth
     * having when its CI/CD sibling fails — so by the time anyone asks whether the run should go
     * red, the original exception is long gone. This is what replaces it. Naming the artifact
     * matters more than the stack: "the JUnit XML could not be written" is actionable, and
     * "something failed, see the log" is the behaviour being removed.
     *
     * @return null when nothing failed
     */
    public String incompleteReason() {
        StringBuilder sb = new StringBuilder();
        for (Kind kind : Kind.values()) {
            Artifact a = artifacts.get(kind);
            if (a == null || a.outcome != Outcome.FAILED) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : "; ")
              .append(kind.label().trim()).append(' ').append(a.filename)
              .append(": ").append(a.note != null ? a.note : "unknown error");
        }
        if (sb.length() == 0) {
            return null;
        }
        return "Incomplete artifact set in " + outputDirectory().getPath() + " — " + sb;
    }

    /** Promote an inline run to external, once the payload turns out to be too big. */
    public ReportArtifacts externalDataWritten() {
        return written(Kind.DATA);
    }

    // ---- operator-facing text ----------------------------------------------------

    /**
     * One line stating where everything is about to go, logged before any write happens.
     *
     * <p>This is the line that was missing entirely. In every silent-no-output case — a
     * write failure, a rejected filename, the exporter skipping generation — there was no
     * record of the resolved directory or filename, and often no evidence the plugin had run
     * at all. The directory is printed absolute on purpose: a relative
     * {@code webinsight.report.output} resolves against the process working directory, and
     * the {@code -g -o} exporter's own fallback is a directory inside the JMeter
     * installation, so the configured string tells an operator much less than the
     * resolved one.
     */
    public String intentLine() {
        StringBuilder sb = new StringBuilder("Web Insight Report: outputDir=");
        sb.append(outputDirectory().getPath());
        sb.append(" html=").append(filename(Kind.HTML));
        sb.append(" json=").append(filename(Kind.JSON));
        sb.append(" xml=").append(artifacts.containsKey(Kind.XML) ? filename(Kind.XML) : "disabled");
        sb.append(" data=");
        if (externalDataForced) {
            sb.append("external:").append(filename(Kind.DATA));
        } else {
            // Honest about what is not yet known: whether the size threshold trips depends
            // on a payload that has not been serialized when this line is emitted.
            sb.append("inline (external:").append(filename(Kind.DATA))
              .append(" above ").append(externalDataThreshold).append(" chars)");
        }
        return sb.toString();
    }

    /**
     * The multi-line block naming every artifact that was actually written, for stdout.
     *
     * <p>Replaces a single line that named the HTML and nothing else. Nothing in the repo
     * parses the old text — the only occurrences are the two emitting sites — so the format
     * is free.
     */
    public String consoleSummary() {
        // The verdict first. It is the one thing a reader of this block came for, and burying
        // it under four file paths is how a FAIL became something you had to go and look for.
        StringBuilder sb = new StringBuilder(verdictLine())
                .append(System.lineSeparator())
                .append("Web Insight Report generated:");
        for (Kind kind : Kind.values()) {
            Artifact a = artifacts.get(kind);
            sb.append(System.lineSeparator()).append("  ").append(kind.label()).append(" : ");
            if (a == null) {
                sb.append("disabled");
                continue;
            }
            switch (a.outcome) {
                case WRITTEN:
                    sb.append(file(kind).getAbsolutePath());
                    break;
                case SKIPPED:
                    sb.append(a.note != null ? a.note : "skipped");
                    break;
                case FAILED:
                    sb.append("FAILED — ").append(a.note);
                    break;
                case PLANNED:
                default:
                    sb.append("not written");
                    break;
            }
        }
        return sb.toString();
    }

    /** The one line CI greps for on success. */
    public String successMarker() {
        return "WEB-INSIGHT-REPORT: OK " + html().getAbsolutePath();
    }

    /**
     * The one line CI greps for on failure, with the cause named rather than echoed.
     *
     * <p>Static because a failure can happen before there is anything to describe — a
     * rejected filename or an unwritable directory fails ahead of every artifact.
     *
     * <p>The exception class is always printed. {@code e.getMessage()} alone was actively
     * misleading here: for the NIO file exceptions it returns the filename and nothing else,
     * so "output directory is a file" surfaced as a bare path naming the <i>report</i> while
     * the problem was the <i>directory</i>, with no statement that anything was wrong.
     */
    public static String failureLine(Throwable cause) {
        StringBuilder sb = new StringBuilder("WEB-INSIGHT-REPORT: FAILED ");
        sb.append(describe(cause));
        if (cause != null) {
            sb.append(System.lineSeparator()).append("       (")
              .append(cause.getClass().getName());
            Throwable root = rootCause(cause);
            if (root != cause) {
                sb.append(" caused by ").append(root.getClass().getName());
                if (root.getMessage() != null && !root.getMessage().trim().isEmpty()) {
                    sb.append(": ").append(root.getMessage());
                }
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * Turn an exception into a sentence naming the cause.
     *
     * <p>{@code NoSuchFileException.getMessage()} and {@code FileSystemException.getMessage()}
     * return the offending filename with no explanation, which is how three separate
     * failures all reported themselves as an unadorned path. These subclasses carry the
     * missing half — {@code getFile()} and {@code getReason()} — so use it.
     */
    public static String describe(Throwable cause) {
        if (cause == null) {
            return "unknown error";
        }
        if (cause instanceof NoSuchFileException) {
            return "no such file or directory: " + ((FileSystemException) cause).getFile();
        }
        if (cause instanceof AccessDeniedException) {
            return "access denied: " + ((FileSystemException) cause).getFile();
        }
        if (cause instanceof FileSystemException) {
            FileSystemException fse = (FileSystemException) cause;
            String reason = fse.getReason();
            return (reason != null && !reason.trim().isEmpty() ? reason.trim() : "file system error")
                    + ": " + fse.getFile();
        }
        if (cause instanceof InvalidPathException) {
            return "invalid path: " + cause.getMessage();
        }
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return cause.getClass().getSimpleName();
        }
        return message;
    }

    private static Throwable rootCause(Throwable cause) {
        // Bounded rather than while(cause.getCause() != null): a self-referential or cyclic
        // cause chain would otherwise spin forever inside an error path, which is the worst
        // possible place for an infinite loop.
        Throwable current = cause;
        List<Throwable> seen = new ArrayList<>();
        while (current.getCause() != null && !seen.contains(current) && seen.size() < 16) {
            seen.add(current);
            current = current.getCause();
        }
        return current;
    }
}
