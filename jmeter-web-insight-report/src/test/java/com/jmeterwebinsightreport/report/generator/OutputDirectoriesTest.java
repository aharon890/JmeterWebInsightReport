package com.jmeterwebinsightreport.report.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two questions: can we write here, and where do we write when nobody said.
 *
 * <p>The first used to be {@code File.canWrite()}, which on Windows reflects only the DOS
 * read-only attribute and returns true for an ACL-denied directory — the normal way a directory
 * is unwritable there. So the fail-fast check never fired: the full report rendered and then
 * failed, naming the staging file rather than the directory that was actually wrong.
 *
 * <p>The second used to be "the process working directory", which in GUI mode is normally
 * {@code $JMETER_HOME/bin} — read-only on most managed installs.
 */
class OutputDirectoriesTest {

    @TempDir
    Path dir;

    @Test
    void createsAMissingDirectoryAndProvesItIsWritable() throws IOException {
        File target = new File(dir.toFile(), "nested/deeper");
        assertFalse(target.exists());

        OutputDirectories.requireWritable(target);

        assertTrue(target.isDirectory());
        assertTrue(OutputDirectories.isUsable(target));
    }

    @Test
    void aFileInTheDirectorysPlaceIsNamedAsTheProblem() throws IOException {
        // The old code created nothing (exists() was true), skipped the check, and surfaced a
        // NoSuchFileException naming …/blocked/web-insight-report.html several steps later — so
        // the operator was told about the report while the thing that was wrong was the
        // directory.
        File blocked = new File(dir.toFile(), "blocked");
        Files.write(blocked.toPath(), new byte[0]);

        IOException e = assertThrows(IOException.class,
                () -> OutputDirectories.requireWritable(blocked));

        assertTrue(e.getMessage().contains("not a directory"), e.getMessage());
        assertTrue(e.getMessage().contains(blocked.getAbsolutePath()),
                "and it must name the directory: " + e.getMessage());
        assertFalse(OutputDirectories.isUsable(blocked));
    }

    @Test
    void theProbeLeavesNothingBehind() throws IOException {
        OutputDirectories.requireWritable(dir.toFile());

        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            assertEquals(0, entries.count(),
                    "the probe writes and deletes; it must not litter the output directory");
        }
    }

    @Test
    void aNullDirectoryIsTheWorkingDirectoryAndIsAlwaysFine() {
        assertDoesNotThrow(() -> OutputDirectories.requireWritable(null));
        assertFalse(OutputDirectories.isUsable(null),
                "but null is not a candidate the resolver can choose");
    }

    // --- the default resolution chain ---------------------------------------------

    @Test
    void theWorkingDirectoryWinsOverEverythingTheCallerRANKSBelowIt() {
        // Nobody whose setup works today sees their report move. The test plan's directory —
        // which is what `secondary` carries — is reached only when the probe write says the
        // working directory cannot be used.
        File besideThePlan = new File(dir.toFile(), "beside-the-jmx");
        assertEquals(new File(System.getProperty("user.dir")).getAbsolutePath(),
                OutputDirectories.resolveDefault(
                        Collections.emptyList(),
                        Collections.singletonList(besideThePlan)).getAbsolutePath());
        assertFalse(besideThePlan.exists(),
                "an unreached candidate must not be created by probing");
    }

    /**
     * ...and the results file is the one thing that outranks it.
     *
     * <p>The chain used to put the working directory ahead of everything a caller could supply,
     * which swept up the results file along with the test plan. Those are not the same kind of
     * signal: {@code -l /data/run.jtl} is an operator naming a directory and putting this run's
     * output in it, while the {@code .jmx}'s directory is only where a file happened to be opened
     * from. Ranking the working directory above the results file made {@code jmeter -g
     * /data/run.jtl} write the report wherever the shell happened to be — which README,
     * FEATURES.md, the export tutorial and both call sites' javadoc all said it would not.
     */
    @Test
    void theResultsDirectoryOutranksTheWorkingDirectory() throws IOException {
        File besideTheJtl = new File(dir.toFile(), "beside-the-jtl");
        assertTrue(besideTheJtl.mkdirs());

        assertEquals(besideTheJtl.getAbsolutePath(),
                OutputDirectories.resolveDefault(
                        Collections.singletonList(besideTheJtl)).getAbsolutePath(),
                "a directory the caller ranked above the CWD must be probed first");
    }

    @Test
    void anUnusableCandidateIsSkippedForTheNextOne() throws IOException {
        File blocked = new File(dir.toFile(), "blocked");
        Files.write(blocked.toPath(), new byte[0]);
        File usable = new File(dir.toFile(), "usable");

        // candidates() is the ordered chain; resolveDefault walks it. Asserting on the chain
        // directly keeps this test independent of whether the working directory happens to be
        // writable on the machine running it.
        List<File> chain = OutputDirectories.candidates(Arrays.asList(blocked, usable));
        assertTrue(chain.contains(blocked) && chain.contains(usable));
        assertFalse(OutputDirectories.isUsable(blocked));
        assertTrue(OutputDirectories.isUsable(usable));
    }

    @Test
    void theChainAlwaysEndsSomewhere() {
        // A default that commonly fails is not a default. With no preference at all the chain
        // still terminates — working directory, then a folder under the user's home, then system
        // temp — and it never returns null, because a null would produce a failure naming
        // nothing.
        File resolved = OutputDirectories.resolveDefault(Collections.emptyList());
        assertNotNull(resolved);
        assertTrue(OutputDirectories.isUsable(resolved),
                "the chain must not hand back somewhere it just proved unusable: " + resolved);
    }

    @Test
    void theWholeChainIsFixedAndInOrder() {
        File resultsDir = new File(dir.toFile(), "beside-the-jtl");
        File planDir = new File(dir.toFile(), "beside-the-jmx");
        List<File> candidates = OutputDirectories.candidates(
                Collections.singletonList(resultsDir), Collections.singletonList(planDir));

        // The working directory sits in the MIDDLE, and that is the whole shape of the decision.
        // A chain that moves a working report somewhere else is the complaint it exists to
        // answer, which is why the test plan's directory does not outrank it -- confirming the
        // originally proposed order put reports in the directory the .jmx was loaded from. But a
        // results filename is a statement of intent in a way a .jmx path is not, so it does.
        assertEquals(resultsDir, candidates.get(0), "the results file's directory leads");
        assertEquals(new File(System.getProperty("user.dir")).getAbsolutePath(),
                candidates.get(1).getAbsolutePath(), "then the process working directory");
        assertEquals(planDir, candidates.get(2), "then the test plan's directory");
        assertEquals(new File(System.getProperty("user.home"), OutputDirectories.HOME_FOLDER),
                candidates.get(3), "then a folder under the user's home");
        assertEquals(new File(System.getProperty("java.io.tmpdir"), OutputDirectories.TEMP_FOLDER),
                candidates.get(4), "and system temp as the last resort");
    }

    @Test
    void theOneArgumentChainStillPutsItsCallersSuggestionsFirst() {
        // The -g -o exporter has only one candidate to offer -- the directory of the JTL it was
        // handed -- and its javadoc promises the chain starts there.
        File besideTheJtl = new File(dir.toFile(), "beside-the-jtl");
        List<File> candidates = OutputDirectories.candidates(
                Collections.singletonList(besideTheJtl));

        assertEquals(besideTheJtl, candidates.get(0));
        assertEquals(new File(System.getProperty("user.dir")).getAbsolutePath(),
                candidates.get(1).getAbsolutePath());
    }

    @Test
    void aCandidateRepeatedLaterInTheChainIsNotProbedTwice() {
        // The working directory is frequently also the test plan's directory, and probing the
        // same place twice would make the announced order misleading.
        File cwd = new File(System.getProperty("user.dir"));
        List<File> candidates = OutputDirectories.candidates(Collections.singletonList(cwd));

        long occurrences = candidates.stream()
                .filter(f -> f.getAbsolutePath().equals(cwd.getAbsolutePath()))
                .count();
        assertEquals(1, occurrences, candidates.toString());
    }
}
