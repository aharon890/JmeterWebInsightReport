package com.jmeterwebinsightreport.report.listener.rebuild;

import com.jmeterwebinsightreport.report.generator.ReportArtifacts;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one click of <b>Rebuild report with server data</b> did, in the words the result dialog shows
 * and Copy copies. No Swing, so a test reads exactly what the user would.
 *
 * <p><b>What it shows, and what it leaves out (R7).</b> The verdict line, the collector's own lines
 * (per-series status, coverage, "rows are 3 h outside the run - pass --time-zone", near-miss names,
 * the measured step), why a side was not judged, a missing artifact, and the warnings the request
 * and the rebuild raised. Not the console markers: {@code CONFIG ERROR} and "the exit code is
 * unchanged" describe a process exit, and a button in a GUI session has none.
 */
public final class RebuildResult {

    private final ReportArtifacts artifacts;
    private final File html;
    private final String failure;
    private final String serverDataRead;
    private final List<String> collectorLines;
    private final List<String> warnings;

    RebuildResult(ReportArtifacts artifacts, String failure, String serverDataRead, List<String> collectorLines,
                  List<String> warnings) {
        this.artifacts = artifacts;
        this.html = artifacts == null ? null : artifacts.html();
        this.failure = failure;
        this.serverDataRead = serverDataRead;
        this.collectorLines = Collections.unmodifiableList(new ArrayList<>(collectorLines));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /** What was written, or null when the rebuild failed before it wrote the report. */
    public ReportArtifacts artifacts() {
        return artifacts;
    }

    /** The report's HTML file, or null when none was written. */
    public File html() {
        return artifacts != null && artifacts.outcome(ReportArtifacts.Kind.HTML) == ReportArtifacts.Outcome.WRITTEN
                ? html : null;
    }

    /** Why no report was written, as a sentence, or null. */
    public String failure() {
        return failure;
    }

    /** The collector's lines, as it printed them, in order. */
    public List<String> collectorLines() {
        return collectorLines;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** The dialog's title. */
    public String headline() {
        if (html() == null) {
            return "Rebuild failed";
        }
        return "Report rebuilt";
    }

    /** The whole dialog, as text: what it shows and what Copy copies. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        if (html() != null) {
            sb.append("Report rebuilt: ").append(RebuildRequest.slashes(html())).append('\n');
        } else {
            sb.append("Rebuild failed: ").append(failure != null ? failure : "the report was not written").append('\n');
        }
        if (artifacts != null) {
            section(sb, "Verdict", Collections.singletonList(artifacts.verdictLine()));
        }
        if (serverDataRead != null) {
            section(sb, "Server data", Collections.singletonList(serverDataRead));
        }
        section(sb, "What the collector said", collectorLines);
        if (artifacts != null) {
            String server = artifacts.serverConfigProblemReason();
            if (!server.isEmpty()) {
                section(sb, "Server side not judged", Collections.singletonList(server));
            }
            String client = artifacts.clientConfigProblemReason();
            if (!client.isEmpty()) {
                section(sb, "Client asserts not judged", Collections.singletonList(client));
            }
            if (artifacts.anyFailed()) {
                section(sb, "Incomplete", Collections.singletonList(artifacts.incompleteReason()));
            }
        }
        List<String> bullets = new ArrayList<>();
        for (String warning : warnings) {
            bullets.add("- " + warning);
        }
        section(sb, "Notes", bullets);
        return sb.toString();
    }

    private static void section(StringBuilder sb, String heading, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append('\n').append(heading).append('\n');
        for (String line : lines) {
            sb.append(line).append('\n');
        }
    }
}
