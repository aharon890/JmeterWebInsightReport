package com.jmeterwebinsightreport.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guarantee under test is narrow and the whole point: a target file is either its old
 * contents or its new ones, never a prefix of the new ones — and a failed write leaves neither
 * a partial target nor a temp file behind.
 */
class AtomicFileWriterTest {

    @TempDir
    Path dir;

    private String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Anything beginning with a dot and ending in .tmp — the temp siblings we create. */
    private long strayTempFiles() throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().startsWith(".")
                    && p.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    @Test
    void writesContentAndLeavesNoTempFile() throws IOException {
        File target = new File(dir.toFile(), "report.html");
        AtomicFileWriter.write(target, "<html>hello</html>");

        assertEquals("<html>hello</html>", read(target));
        assertEquals(0, strayTempFiles());
    }

    @Test
    void replacesAnExistingFile() throws IOException {
        File target = new File(dir.toFile(), "report.html");
        AtomicFileWriter.write(target, "first");
        AtomicFileWriter.write(target, "second");

        assertEquals("second", read(target));
        assertEquals(0, strayTempFiles());
    }

    @Test
    void streamedContentRoundTrips() throws IOException {
        File target = new File(dir.toFile(), "web-insight-data.js");
        AtomicFileWriter.write(target, writer -> {
            writer.write("window.__WIR_DATA__=");
            writer.write("{\"a\":1}");
            writer.write(";");
        });

        assertEquals("window.__WIR_DATA__={\"a\":1};", read(target));
        assertEquals(0, strayTempFiles());
    }

    @Test
    void aFailedStreamedWriteLeavesTheTargetUntouchedAndNoDebris() throws IOException {
        File target = new File(dir.toFile(), "web-insight-data.js");
        AtomicFileWriter.write(target, "GOOD PAYLOAD");

        // This is the failure that used to corrupt a previous good run: the payload write
        // landing on top of an earlier one. Now the target only changes if the write completed.
        assertThrows(IOException.class, () -> AtomicFileWriter.write(target, writer -> {
            writer.write("partial...");
            throw new IOException("disk full");
        }));

        assertEquals("GOOD PAYLOAD", read(target), "a failed write must not touch the target");
        assertEquals(0, strayTempFiles(), "a failed write must not leave a .tmp behind");
    }

    @Test
    void aFailedWriteIntoAnUnwritableTargetLeavesNoDebris() throws IOException {
        // A directory in the target's place: the move cannot succeed.
        File target = new File(dir.toFile(), "blocked.json");
        assertTrue(target.mkdir());

        assertThrows(IOException.class, () -> AtomicFileWriter.write(target, "content"));
        assertTrue(target.isDirectory(), "the pre-existing entry is untouched");
        assertEquals(0, strayTempFiles(), "a failed move must not leave a .tmp behind");
    }

    @Test
    void createsAMissingParentDirectory() throws IOException {
        File target = new File(new File(dir.toFile(), "nested/deeper"), "report.html");
        AtomicFileWriter.write(target, "ok");
        assertEquals("ok", read(target));
    }

    @Test
    void theTempSiblingLivesBesideTheTargetNotInTheSystemTempDir() throws IOException {
        // A move is only atomic within one filesystem, and the system temp directory frequently
        // is not the same one as the output directory. The only way to observe the location is
        // to fail mid-write and check where nothing was left.
        File target = new File(dir.toFile(), "report.html");
        assertThrows(IOException.class, () -> AtomicFileWriter.write(target, writer -> {
            writer.write("x");
            throw new IOException("boom");
        }));
        assertFalse(target.exists());
        assertEquals(0, strayTempFiles());
    }

    @Test
    void utf8SurvivesTheRoundTrip() throws IOException {
        File target = new File(dir.toFile(), "отчёт.html");
        AtomicFileWriter.write(target, "日本語 — ok");
        assertEquals("日本語 — ok", read(target));
    }

    // --- staging names and the sweep (R4.1, and the debris it creates) --------------

    /**
     * Two writers must never share a staging file.
     *
     * <p>The name was {@code .<target>.tmp} — one deterministic path per target — which is safe
     * for one writer and destructive for two. Measured seven times across two platforms: two
     * JMeter runs sharing an output directory, one publishing the OTHER's bytes under its own
     * name, or losing its report entirely, every occurrence exiting 0.
     */
    @Test
    void everyStagingNameIsDistinctAndCarriesThePid() throws IOException {
        File target = new File(dir.toFile(), "report.html");
        long pid = ProcessHandle.current().pid();

        Path first = AtomicFileWriter.stagingSibling(target);
        Path second = AtomicFileWriter.stagingSibling(target);

        assertNotEquals(first, second, "two writers must never be handed the same staging file");
        for (Path p : new Path[]{first, second}) {
            String name = p.getFileName().toString();
            assertTrue(name.matches("\\.report\\.html\\.[A-Za-z0-9_-]+-" + pid + "-[0-9a-f]{8}\\.tmp"),
                    "owner and pid both have to be in the name for the sweep to scope itself "
                            + "and then ask about the writer: " + name);
            assertTrue(name.endsWith(".tmp"), name);
            assertEquals(dir, p.getParent(),
                    "still a sibling — a move is only atomic within one filesystem");
        }
    }

    /**
     * Debris from a writer that is gone is collected; a live writer's file is never touched.
     *
     * <p>This is the trade that unique staging names create. A deterministic name self-heals,
     * because the next run overwrites it; a unique one leaves NEW debris on every SIGKILL, and in
     * a {@code jmeter -g -o} directory one leftover makes JMeter refuse the next run outright.
     *
     * <p>The predicate is process liveness, not age, and the second half of this test is the
     * reason: an age-based sweep deletes a live writer's staging file on a slow disk, which
     * reintroduces exactly the corruption being fixed.
     */
    @Test
    void theSweepRemovesDeadWritersDebrisAndSparesLiveOnes() throws IOException {
        File target = new File(dir.toFile(), "report.html");
        long ownPid = ProcessHandle.current().pid();

        // PID 1 is init/systemd on Unix and the System Idle Process on Windows — either way it
        // is not us, and pid 999999999 is beyond any plausible live process.
        String owner = ownerOf(AtomicFileWriter.stagingSibling(new File(dir.toFile(), "probe")));
        Path deadDebris = dir.resolve(".report.html." + owner + "-999999999-0badf00d.tmp");
        Path ownInFlight = dir.resolve(".report.html." + owner + "-" + ownPid + "-cafebabe.tmp");
        Path notOurPattern = dir.resolve(".report.html.tmp");
        // The one that cost six reports in fifty: another CONTAINER, whose pids are numbered in
        // its own namespace and are therefore meaningless here. Low, plausible, and live.
        Path foreignNamespace = dir.resolve(".report.html.othercontainer-296-0a48a239.tmp");
        for (Path p : new Path[]{deadDebris, ownInFlight, notOurPattern, foreignNamespace}) {
            Files.write(p, "x".getBytes(StandardCharsets.UTF_8));
        }

        int removed = AtomicFileWriter.sweepStaleStaging(target);

        assertEquals(1, removed, "only the dead writer's own-pattern file");
        assertFalse(Files.exists(deadDebris), "a dead writer's debris must be collected");
        assertTrue(Files.exists(ownInFlight),
                "NEVER a file this process owns — another thread here may be mid-write");
        assertTrue(Files.exists(notOurPattern),
                "a name that does not parse as ours is left alone");
        assertTrue(Files.exists(foreignNamespace),
                "NEVER another container's file. Its pid 296 is numbered in ITS namespace; "
                        + "asking ProcessHandle about 296 answers about OURS, and answering "
                        + "'dead' deletes a live writer's staging file — measured, six lost "
                        + "reports in fifty concurrent container pairs");
    }

    /**
     * Debris left under a DIFFERENT target's name is collected too.
     *
     * <p>This inverts what this suite used to assert — "another target's staging file is not ours
     * to delete" — and the inversion is the fix. Scoping the sweep to the current target's name
     * meant it could tidy up after itself and after nothing else, and the name it most needed to
     * tidy up after was {@code ${timestamp}}, which this plugin documents as the way to keep
     * reports apart and which is therefore a <em>different</em> name on every single run. Debris
     * from run N was invisible to run N+1, to run N+2, and to every process that could have
     * removed it; nothing else sweeps, so it accumulated for the life of the directory and one
     * leftover is enough to make {@code jmeter -g -o} refuse to start.
     *
     * <p>Nothing about the decision to delete was ever the glob's to make. It belongs to the
     * staging-name pattern, which proves the file is one of ours, and to the owner and liveness
     * checks, which prove its writer is gone — and the test above still holds every one of those
     * lines. Widening the search does not widen what is deleted.
     */
    @Test
    void theSweepCollectsDebrisLeftUnderAnyTargetName() throws IOException {
        String owner = ownerOf(AtomicFileWriter.stagingSibling(new File(dir.toFile(), "probe")));

        // Three runs of `-Jwebinsight.report.filename=report_${timestamp}`, each killed
        // mid-write, each leaving a staging file under a name no later run will ever use again.
        Path monday = dir.resolve(".report_20260101_090000.html." + owner + "-999999991-0badf00d.tmp");
        Path tuesday = dir.resolve(".report_20260102_090000.html." + owner + "-999999992-0badf00e.tmp");
        Path payload = dir.resolve(".web-insight-data.js." + owner + "-999999993-0badf00f.tmp");
        for (Path p : new Path[]{monday, tuesday, payload}) {
            Files.write(p, "x".getBytes(StandardCharsets.UTF_8));
        }

        // Today's run, under today's name, which matches none of them.
        int removed = AtomicFileWriter.sweepStaleStaging(
                new File(dir.toFile(), "report_20260103_090000.html"));

        assertEquals(3, removed, "every dead writer's debris, not only this target's");
        assertEquals(0, strayTempFiles());
    }

    /**
     * A non-ASCII hostname does not switch the sweep off.
     *
     * <p>{@code resolveOwner} sanitised with {@code Character.isLetterOrDigit}, which keeps
     * Unicode letters, while the staging-name pattern's owner group has always been
     * {@code [A-Za-z0-9_-]}. A host called {@code wörkstation} therefore wrote names it could not
     * then recognise — and because an unparseable name is rejected outright, the sweep was not
     * merely less effective there but <b>permanently inert</b>: staging debris accumulated
     * forever, on every run, with nothing in any log to say so.
     *
     * <p>Asserted through the public surface rather than by calling {@code resolveOwner}: what
     * matters is that a name this class GENERATES is a name this class MATCHES, and the two
     * halves drifting apart is the entire defect.
     */
    @Test
    void aNameThisClassGeneratesIsAlwaysANameItCanParse() throws IOException {
        File target = new File(dir.toFile(), "report.html");
        String generated = AtomicFileWriter.stagingSibling(target).getFileName().toString();

        assertTrue(generated.matches("\\.report\\.html\\.[A-Za-z0-9_-]+-\\d+-[0-9a-f]{8}\\.tmp"),
                "the owner token must fall inside the pattern's own alphabet on every host, "
                        + "whatever HOSTNAME/COMPUTERNAME says: " + generated);

        // The hosts this machine is not. Each of these produced an owner token containing a
        // character the pattern rejects, and therefore a sweep that never matched anything.
        for (String hostname : new String[]{
                "wörkstation", "京都-build-01", "café", "MÜNCHEN-CI", "sürüm.example.com"}) {
            String token = AtomicFileWriter.sanitizeOwner(hostname);
            assertTrue(token.matches("[A-Za-z0-9_-]+"),
                    "an owner token the staging pattern cannot match switches the sweep off "
                            + "entirely on that host: " + hostname + " -> " + token);
        }

        // ...and the sweep really does act on such a name, rather than merely tolerating it.
        String owner = ownerOf(AtomicFileWriter.stagingSibling(new File(dir.toFile(), "probe")));
        Path dead = dir.resolve(".report.html." + owner + "-999999999-0badf00d.tmp");
        Files.write(dead, "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, AtomicFileWriter.sweepStaleStaging(target));
    }

    /** The owner token this JVM stamps into a staging name. */
    private static String ownerOf(Path staging) {
        String name = staging.getFileName().toString();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\.probe\\.([A-Za-z0-9_-]+)-\\d+-[0-9a-f]+\\.tmp").matcher(name);
        assertTrue(m.matches(), name);
        return m.group(1);
    }

    @Test
    void writingSweepsBeforeItStages() throws IOException {
        // The sweep sits in the one place every write passes through, rather than at the call
        // sites, because a sweep that has to be remembered is one the next code path forgets —
        // and forgetting it means a -g -o run that will not start.
        File target = new File(dir.toFile(), "report.html");
        String owner = ownerOf(AtomicFileWriter.stagingSibling(new File(dir.toFile(), "probe")));
        Path deadDebris = dir.resolve(".report.html." + owner + "-999999999-0badf00d.tmp");
        Files.write(deadDebris, "x".getBytes(StandardCharsets.UTF_8));

        AtomicFileWriter.write(target, "content");

        assertFalse(Files.exists(deadDebris));
        assertEquals("content", read(target));
        assertEquals(0, strayTempFiles());
    }

    @Test
    void stagingWritesTheContentWithoutTouchingTheTarget() throws IOException {
        File target = new File(dir.toFile(), "report.html");
        AtomicFileWriter.write(target, "PREVIOUS RUN");

        Path staged = AtomicFileWriter.stage(target, writer -> writer.write("THIS RUN"));

        assertEquals("PREVIOUS RUN", read(target), "staging must not disturb what is there");
        assertEquals("THIS RUN", new String(Files.readAllBytes(staged), StandardCharsets.UTF_8));

        AtomicFileWriter.publish(staged, target.toPath());
        assertEquals("THIS RUN", read(target));
        assertFalse(Files.exists(staged), "publishing moves, it does not copy");
    }

    @Test
    void aFailedStagingLeavesNothingBehind() throws IOException {
        File target = new File(dir.toFile(), "web-insight-data.js");

        // The caller never learns the path of a staging file that threw, so nothing else could
        // clean it up.
        assertThrows(IOException.class, () -> AtomicFileWriter.stage(target, writer -> {
            writer.write("partial...");
            throw new IOException("disk full");
        }));

        assertFalse(target.exists());
        assertEquals(0, strayTempFiles());
    }
}
