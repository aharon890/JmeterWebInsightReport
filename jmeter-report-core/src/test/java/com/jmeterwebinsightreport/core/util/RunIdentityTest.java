package com.jmeterwebinsightreport.core.util;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The id exists so every artifact of one run can be checked against every other. Two properties
 * matter and nothing else does: the same run must produce the same id from both ingestion paths,
 * and two different runs must not collide.
 *
 * <p>Since the exists-policy started keying on this value, both directions have teeth. A false
 * <em>match</em> means a run silently produces no report at all; a false <em>mismatch</em>
 * replaces a good report with a fatal error page. So the contract is tested, not assumed.
 */
class RunIdentityTest {

    @TempDir
    Path tempDir;

    private static ReportData run(String name, long startMillis, long duration) {
        ReportData data = new ReportData();
        TestMetadata meta = new TestMetadata();
        meta.setTestName(name);
        meta.setStartTime(Instant.ofEpochMilli(startMillis));
        meta.setDurationMillis(duration);
        meta.setTotalSamples(10);
        meta.setTotalErrors(1);
        data.setMetadata(meta);
        return data;
    }

    @Test
    void theSameRunAlwaysYieldsTheSameId() {
        // The whole point of deriving rather than generating: the HTML and the payload compute
        // this independently — in the -e -o flow potentially in different processes — so a
        // random value would have needed threading through both and a bug in either would
        // produce a false mismatch, which is worse than no check.
        ReportData a = run("RUN-ALPHA", 1_700_000_000_000L, 5_000L);
        ReportData b = run("RUN-ALPHA", 1_700_000_000_000L, 5_000L);
        assertEquals(RunIdentity.of(a), RunIdentity.of(b));
        assertEquals(RunIdentity.of(a), RunIdentity.of(a), "must not depend on call count");
    }

    @Test
    void everyContributingFieldChangesTheId() {
        String alpha = RunIdentity.of(run("RUN-ALPHA", 1_700_000_000_000L, 5_000L));
        assertNotEquals(alpha, RunIdentity.of(run("RUN-BETA", 1_700_000_000_000L, 5_000L)),
                "a different test name must change the id");
        assertNotEquals(alpha, RunIdentity.of(run("RUN-ALPHA", 1_700_000_001_000L, 5_000L)),
                "a different start time must change the id");
        assertNotEquals(alpha, RunIdentity.of(run("RUN-ALPHA", 1_700_000_000_000L, 9_000L)),
                "a different duration must change the id");

        ReportData moreSamples = run("RUN-ALPHA", 1_700_000_000_000L, 5_000L);
        moreSamples.getMetadata().setTotalSamples(11);
        assertNotEquals(alpha, RunIdentity.of(moreSamples),
                "a different sample count must change the id");

        ReportData moreErrors = run("RUN-ALPHA", 1_700_000_000_000L, 5_000L);
        moreErrors.getMetadata().setTotalErrors(2);
        assertNotEquals(alpha, RunIdentity.of(moreErrors),
                "a different error count must change the id");
    }

    @Test
    void startTimeSeparatesRunsAtMillisecondResolution() {
        // A7: the ${timestamp} filename placeholder resolves at seconds, so two parallel CI
        // shards starting in the same second overwrite each other. That must NOT propagate into
        // identity — two shards would have to start in the same millisecond to collide here.
        assertNotEquals(RunIdentity.of(run("SHARD", 1_700_000_000_000L, 5_000L)),
                RunIdentity.of(run("SHARD", 1_700_000_000_001L, 5_000L)),
                "one millisecond of separation must be enough");
    }

    @Test
    void theIdIsSixteenLowercaseHexCharacters() {
        String id = RunIdentity.of(run("T", 1L, 1L));
        assertEquals(RunIdentity.LENGTH, id.length(), id);
        assertEquals(16, id.length(), id);
        assertTrue(id.matches("[0-9a-f]{16}"), id);
    }

    /**
     * The value itself, pinned.
     *
     * <p>Stability across JVMs and platforms is the contract, and only a literal can hold a
     * hash function to it: every other test here would still pass if the algorithm changed
     * tomorrow, because they only ever compare two ids computed by the same code. The
     * artifacts of one run can be produced by two different processes, so "same input ⇒ same
     * id" has to survive a rebuild, a JVM upgrade and a different operating system.
     *
     * <p>If this fails, the hash changed. That is a compatibility break for any pair of
     * artifacts written by two plugin versions, and it should be a deliberate decision — not a
     * refactor nobody noticed.
     */
    @Test
    void theIdIsPinnedToAPublishedAlgorithm() {
        // SHA-256("RUN-ALPHA|2023-11-14T22:13:20Z|5000|10|1"), first 8 bytes, lower-case hex.
        assertEquals("2ce6f9bf3178b56f",
                RunIdentity.of(run("RUN-ALPHA", 1_700_000_000_000L, 5_000L)));
    }

    /**
     * Non-ASCII test names hash the same everywhere.
     *
     * <p>The input is encoded explicitly as UTF-8. Under the platform default charset this id
     * would differ between a Windows workstation (windows-1252) and a Linux container (UTF-8) —
     * so the {@code -g} re-export of a JTL captured elsewhere would report a mismatch against
     * its own run, which is precisely the deployment the contract exists for.
     */
    @Test
    void theEncodingIsUtf8AndNotThePlatformDefault() {
        // SHA-256 of the UTF-8 bytes of "Ünïcøde ✓ Tëst|2023-11-14T22:13:20Z|5000|10|1".
        assertEquals("73eae6af23e13d29",
                RunIdentity.of(run("Ünïcøde ✓ Tëst", 1_700_000_000_000L, 5_000L)));
    }

    @Test
    void missingMetadataDoesNotThrow() {
        // A report with no metadata is degenerate but must not crash generation — this runs on
        // the write path of every report.
        assertEquals(RunIdentity.UNKNOWN, RunIdentity.of(null));
        assertEquals("b532c03c2d50e5b1", RunIdentity.of(new ReportData()),
                "an empty ReportData still gets a real id, not a sentinel");
    }

    @Test
    void aNullTestNameIsTolerated() {
        ReportData data = run(null, 1L, 1L);
        assertEquals(RunIdentity.LENGTH, RunIdentity.of(data).length());
    }

    /**
     * <b>D-9, the contract itself: same JTL ⇒ same runId.</b>
     *
     * <p>The listener aggregates samples live; the {@code -g} exporter re-parses the JTL. Both
     * end at {@code LiveStatisticsEngine.buildReportData}, which is why the ids agree — but
     * "why" is not the thing worth pinning. What matters is that they <em>do</em> agree, because
     * the exists-policy uses this to tell "the report already here is mine, skip" from "the
     * report already here belongs to someone else, overwrite and say so". If a future metadata
     * change made one path derive a different id, that policy would quietly degrade into
     * always-overwrite and nothing else would notice.
     */
    @Test
    void aReExportOfARunsOwnJtlDerivesTheSameId() throws IOException {
        long t0 = 1_700_000_000_000L;

        // The exporter's path: parse a JTL off disk.
        String jtl = "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,"
                + "success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,"
                + "IdleTime,Connect\n"
                + row(t0, 120, "GET /a", true)
                + row(t0 + 500, 240, "GET /b", true)
                + row(t0 + 900, 90, "GET /a", false)
                + row(t0 + 1500, 310, "GET /b", true);
        File file = tempDir.resolve("run.jtl").toFile();
        Files.write(file.toPath(), jtl.getBytes(StandardCharsets.UTF_8));
        ReportData reExported = new JtlParser().parse(file, "IDENTITY-CONTRACT");

        // The listener's path: the same samples, recorded live.
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        engine.recordSample("GET /a", 120, true, 100, 10, t0, -1, -1);
        engine.recordSample("GET /b", 240, true, 100, 10, t0 + 500, -1, -1);
        engine.recordSample("GET /a", 90, false, 100, 10, t0 + 900, -1, -1);
        engine.recordSample("GET /b", 310, true, 100, 10, t0 + 1500, -1, -1);
        ReportData live = engine.buildReportData("IDENTITY-CONTRACT");

        assertEquals(RunIdentity.of(live), RunIdentity.of(reExported),
                "a -g re-export must identify itself as the SAME run the listener reported.\n"
                        + "listener fingerprint:  " + RunIdentity.fingerprint(live) + "\n"
                        + "re-export fingerprint: " + RunIdentity.fingerprint(reExported));
    }

    /** ...and the other half of D-9: two executions of the same plan are distinct. */
    @Test
    void twoExecutionsOfTheSamePlanDeriveDifferentIds() {
        long t0 = 1_700_000_000_000L;
        assertNotEquals(RunIdentity.of(execution(t0)), RunIdentity.of(execution(t0 + 60_000)),
                "two runs of one plan, a minute apart, must not be mistaken for each other");
    }

    private static ReportData execution(long start) {
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        engine.recordSample("GET /a", 120, true, 100, 10, start, -1, -1);
        engine.recordSample("GET /b", 240, true, 100, 10, start + 500, -1, -1);
        return engine.buildReportData("SAME-PLAN");
    }

    /**
     * One JTL row in JMeter's default column order:
     * {@code timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,
     * failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect}.
     */
    private static String row(long ts, long elapsed, String label, boolean ok) {
        return ts + "," + elapsed + "," + label + "," + (ok ? "200,OK" : "500,Server Error")
                + ",Thread 1-1,text," + (ok ? "true" : "false") + ",,100,10,1,1,http://h/x,"
                + elapsed + ",0,1\n";
    }
}
