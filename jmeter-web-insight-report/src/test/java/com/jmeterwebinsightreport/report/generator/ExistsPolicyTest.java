package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator.ExistingReport;
import static com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator.inspectExisting;
import static org.junit.jupiter.api.Assertions.*;

/**
 * "A report already exists here" is not one situation, and treating it as one is what made the
 * two ingestion paths disagree: given the same directory, the listener overwrote and the
 * {@code -g} exporter skipped and wrote nothing. Neither is wrong on its own — the disagreement
 * is, and a stale report could therefore suppress a fresh one and still look current.
 *
 * <p>The decision table below is the single policy both paths now apply. Every branch has a test
 * because the fallback matters as much as the mechanism: the wrong answer for "cannot tell" is
 * the silent-no-output failure this whole workstream exists to remove.
 */
class ExistsPolicyTest {

    @TempDir
    Path dir;

    private static ReportData run(String name, long startMillis) {
        ReportData data = new ReportData();
        TestMetadata meta = new TestMetadata();
        meta.setTestName(name);
        meta.setStartTime(Instant.ofEpochMilli(startMillis));
        meta.setDurationMillis(5_000L);
        meta.setTotalSamples(10);
        meta.setTotalErrors(1);
        data.setMetadata(meta);
        return data;
    }

    private void write(String name, String content) throws IOException {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeReportPair(String base, ReportData producedBy) throws IOException {
        write(base + ".html", "<html></html>");
        write(base + ".json", "{\n  \"runId\" : \"" + RunIdentity.of(producedBy) + "\"\n}\n");
    }

    private ExistingReport inspect(ReportData asking) {
        return inspectExisting(dir.toFile(), "report.html", RunIdentity.of(asking));
    }

    @Test
    void anEmptyDirectoryIsNone() {
        assertEquals(ExistingReport.NONE, inspect(run("ANY", 1_700_000_000_000L)));
    }

    @Test
    void aReportFromThisSameRunIsRecognised() throws IOException {
        // The `-n -t … -e -o` case: the listener wrote it moments ago, in this execution. This is
        // the ONLY situation that justifies the exporter writing nothing.
        ReportData mine = run("SHARED", 1_700_000_000_000L);
        writeReportPair("report", mine);

        assertEquals(ExistingReport.THIS_RUN, inspect(mine));
    }

    @Test
    void aReportFromADifferentRunIsRecognisedAsSuch() throws IOException {
        writeReportPair("report", run("EARLIER", 1_600_000_000_000L));

        assertEquals(ExistingReport.ANOTHER_RUN, inspect(run("NOW", 1_700_000_000_000L)));
    }

    @Test
    void twoRunsOfTheSamePlanAreDifferentRuns() throws IOException {
        // The case a filename could never distinguish: same plan, same title, same output name,
        // one minute apart. Only the fingerprint separates them.
        writeReportPair("report", run("NIGHTLY", 1_700_000_000_000L));

        assertEquals(ExistingReport.ANOTHER_RUN, inspect(run("NIGHTLY", 1_700_000_060_000L)));
    }

    // --- the fallback: everything that cannot answer -------------------------------

    @Test
    void aJsonWithNoRunIdIsUnidentified() throws IOException {
        // A report written by a version that predates run identity.
        write("report.html", "<html></html>");
        write("report.json", "{\n  \"testName\" : \"OLD VERSION\"\n}\n");

        assertEquals(ExistingReport.UNIDENTIFIED, inspect(run("NOW", 1_700_000_000_000L)));
    }

    @Test
    void aTruncatedJsonIsUnidentified() throws IOException {
        // A crash mid-write, or a file being written right now by another process.
        write("report.html", "<html></html>");
        write("report.json", "{\n  \"runId\" : \"abc");

        assertEquals(ExistingReport.UNIDENTIFIED, inspect(run("NOW", 1_700_000_000_000L)));
    }

    @Test
    void aJsonWhoseRunIdIsNotAStringIsUnidentified() throws IOException {
        write("report.html", "<html></html>");
        write("report.json", "{\n  \"runId\" : 12345\n}\n");

        assertEquals(ExistingReport.UNIDENTIFIED, inspect(run("NOW", 1_700_000_000_000L)));
    }

    @Test
    void anHtmlWithNoJsonAtAllIsUnidentified() throws IOException {
        // The .json is where identity lives, so an HTML on its own can never prove whose it is —
        // it can only prove that something is there.
        write("report.html", "<html></html>");

        assertEquals(ExistingReport.UNIDENTIFIED, inspect(run("NOW", 1_700_000_000_000L)));
    }

    @Test
    void aJsonWithNoHtmlStillCounts() throws IOException {
        // The reverse of the above, and it matters: losing the HTML to a transient error while
        // the .json survived is exactly the moment you want the next run to overwrite rather
        // than tiptoe around a half-set.
        write("report.json", "{\n  \"runId\" : \"0000000000000000\"\n}\n");

        assertEquals(ExistingReport.ANOTHER_RUN, inspect(run("NOW", 1_700_000_000_000L)));
    }

    @Test
    void aMissingDirectoryIsNone() {
        assertEquals(ExistingReport.NONE, inspectExisting(new File(dir.toFile(), "nope"),
                "report.html", "0000000000000000"));
    }

    @Test
    void aNestedRunIdIsNotMistakenForTheDocumentsOwn() throws IOException {
        // The read is streamed and steps over each top-level value whole, so a "runId" appearing
        // inside the sampler array — or anywhere else nested — cannot answer a question that was
        // asked about the document.
        write("report.html", "<html></html>");
        write("report.json", "{\n"
                + "  \"testName\" : \"NO TOP LEVEL ID\",\n"
                + "  \"samplers\" : [ { \"name\" : \"a\", \"runId\" : \"deadbeefdeadbeef\" } ]\n"
                + "}\n");

        assertNull(WebInsightReportGenerator.readRunId(dir.resolve("report.json").toFile()));
        assertEquals(ExistingReport.UNIDENTIFIED, inspect(run("NOW", 1_700_000_000_000L)));
    }

    @Test
    void aRunIdThatIsNotTheFirstKeyIsStillFound() throws IOException {
        // It is written first today. Nothing should depend on that staying true.
        write("report.json", "{\n  \"testName\" : \"T\",\n  \"totalSamples\" : 10,\n"
                + "  \"runId\" : \"0123456789abcdef\"\n}\n");

        assertEquals("0123456789abcdef",
                WebInsightReportGenerator.readRunId(dir.resolve("report.json").toFile()));
    }

    @Test
    void readingARunIdFromSomethingThatIsNotAFileIsNull() throws IOException {
        assertNull(WebInsightReportGenerator.readRunId(new File(dir.toFile(), "absent.json")));

        File directory = new File(dir.toFile(), "adirectory.json");
        assertTrue(directory.mkdir());
        assertNull(WebInsightReportGenerator.readRunId(directory),
                "a directory where the .json should be must not throw out of a decision path");
    }
}
