package com.jmeterwebinsightreport.report.generator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the report goes when nobody said, and whether it can actually be written there.
 *
 * <h2>Why the check had to change</h2>
 *
 * <p>The pre-flight guard was {@code File.canWrite()}. On Windows that reflects the DOS
 * read-only attribute and nothing else, so it returns true for a directory an ACL denies —
 * which is the normal way a directory is unwritable on Windows. The fail-fast check therefore
 * never fired: the full ~1.5 MB report rendered, and only then did the write fail, naming the
 * staging file rather than the directory that was actually wrong.
 *
 * <p>A probe write answers the real question, on every platform, for every reason a write can
 * fail — ACLs, read-only mounts, a full disk, a container user with no permission on a bind
 * mount. It costs one create and one delete, once per run, before any rendering happens.
 *
 * <h2>Why the default is a chain and configuration is not</h2>
 *
 * <p>The default output directory is the process working directory. In GUI mode that is usually
 * {@code $JMETER_HOME/bin}, which is read-only on most managed or packaged installs; in a
 * container it may not be writable by the run user. A default that commonly fails is not a
 * default, so it falls back — and the chosen directory is announced, because a report whose
 * location moves is only acceptable if you can find out where it went.
 *
 * <p><b>An explicitly configured directory never falls back.</b> Relocating a path someone
 * asked for is its own bug: it turns "your report is not where you put it" into a silent
 * outcome, and it makes the overwrite target move between runs so files accumulate across
 * locations. Explicit configuration hard-fails instead. The fallback exists only for the case
 * where nobody expressed a preference.
 */
public final class OutputDirectories {

    /** Under the user's home, when neither the results file, the plan nor the CWD will do. */
    static final String HOME_FOLDER = ".jmeter-web-insight-report";

    /** Last resort. Always writable in practice, and self-evidently temporary. */
    static final String TEMP_FOLDER = "web-insight-report";

    private OutputDirectories() {
    }

    /**
     * Create the directory if needed and prove we can write into it, or throw saying why.
     *
     * <p>Order matters. "Exists but is a file" is checked before writability so the message
     * names the right problem: the old code created nothing (because {@code exists()} was true),
     * skipped the check, and surfaced a {@code NoSuchFileException} naming
     * {@code …/blocked/web-insight-report.html} several steps later — telling the operator about
     * the report when the thing that was wrong was the directory.
     */
    public static void requireWritable(File dir) throws IOException {
        if (dir == null) {
            return; // null means the process working directory, which is always there
        }
        if (!dir.exists()) {
            try {
                Files.createDirectories(dir.toPath());
            } catch (IOException e) {
                throw new IOException("Output directory could not be created: "
                        + dir.getAbsolutePath() + " (" + ReportArtifacts.describe(e) + ")", e);
            }
        }
        if (!dir.isDirectory()) {
            throw new IOException("Output directory is not a directory: " + dir.getAbsolutePath());
        }
        probe(dir);
    }

    /**
     * Create and delete a file, so "writable" means writable rather than "not flagged
     * read-only".
     *
     * <p>The probe name carries the process id so two runs probing the same directory at the
     * same moment cannot delete each other's probe and report a failure that is really the other
     * process tidying up.
     */
    private static void probe(File dir) throws IOException {
        Path probe = dir.toPath().resolve(".web-insight-probe." + pid() + ".tmp");
        try {
            Files.write(probe, new byte[0]);
        } catch (IOException e) {
            throw new IOException("Output directory is not writable: " + dir.getAbsolutePath()
                    + " (" + ReportArtifacts.describe(e) + ")", e);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // Written but not removable is strange and not worth failing the run over — the
                // thing we needed to know (that a write succeeds here) has already been answered.
            }
        }
    }

    /** {@link #requireWritable} as a predicate, for choosing between candidates. */
    public static boolean isUsable(File dir) {
        if (dir == null) {
            return false;
        }
        try {
            requireWritable(dir);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The first candidate that can actually be written to, with the standard tail appended.
     *
     * <p>Never returns null. The system temp directory is writable in every environment this
     * plugin has been observed in, and if it somehow is not, returning it anyway produces a
     * failure that names a real path instead of a null.
     *
     * @param preferred directories to try <em>before</em> the process working directory
     */
    public static File resolveDefault(List<File> preferred) {
        return resolveDefault(preferred, null);
    }

    /**
     * @param preferred directories to try before the process working directory — the one place a
     *                  caller can point at that is a stronger signal than where the JVM happens
     *                  to have been launched
     * @param secondary directories to try after it, ahead of the home and temp fallbacks
     * @see #candidates
     */
    public static File resolveDefault(List<File> preferred, List<File> secondary) {
        for (File candidate : candidates(preferred, secondary)) {
            if (isUsable(candidate)) {
                return candidate;
            }
        }
        return new File(System.getProperty("java.io.tmpdir", "."), TEMP_FOLDER);
    }

    /** @see #candidates(List, List) */
    static List<File> candidates(List<File> preferred) {
        return candidates(preferred, null);
    }

    /**
     * The full ordered chain, de-duplicated — the CWD is frequently also the plan's directory.
     *
     * <p>{@code preferred…} → the process working directory → {@code secondary…} → a folder under
     * the user's home → the system temp directory.
     *
     * <h2>Why the working directory sits in the middle rather than at either end</h2>
     *
     * <p>It used to be first, ahead of everything a caller could supply, and the reason was
     * sound as far as it went: putting the caller's chain first broke A10, because the report
     * moved out of the working directory and into the directory the {@code .jmx} had been loaded
     * from — for a repository that meant reports appearing in {@code test-plans/}, next to the
     * plans rather than next to the run. A location that silently moves is the complaint the
     * fallback chain exists to answer, so a chain that moves things is self-defeating.
     *
     * <p>What that argument establishes is that <b>the test plan's directory</b> must not
     * outrank the working directory. It was applied to the whole caller-supplied head, and that
     * swept up the one candidate it does not fit: the directory of the results file. Nobody
     * infers a location from where a {@code .jmx} was loaded — that is JMeter's business, not a
     * statement of intent — whereas {@code -l /data/run.jtl} <em>is</em> the operator naming a
     * directory and putting this run's output in it. Ranking the working directory above that
     * made {@code jmeter -g /data/run.jtl} write the report wherever the shell happened to be,
     * which is what README, {@code WIKI/FEATURES.md}, the export tutorial and both call sites'
     * own javadoc all said it would not do. Four documents and the code cannot all be right; the
     * documents were right about the results file and wrong about the plan.
     *
     * <p>So the split: a results directory goes in {@code preferred}, the plan's directory goes
     * in {@code secondary}, and A10 still passes — it configures nothing, so {@code preferred} is
     * empty and the working directory wins on the first probe.
     */
    static List<File> candidates(List<File> preferred, List<File> secondary) {
        Set<String> seen = new LinkedHashSet<>();
        List<File> ordered = new ArrayList<>();
        addAll(ordered, seen, preferred);
        add(ordered, seen, new File(System.getProperty("user.dir", ".")));
        addAll(ordered, seen, secondary);
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            add(ordered, seen, new File(home, HOME_FOLDER));
        }
        add(ordered, seen, new File(System.getProperty("java.io.tmpdir", "."), TEMP_FOLDER));
        return ordered;
    }

    private static void addAll(List<File> ordered, Set<String> seen, List<File> dirs) {
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            add(ordered, seen, dir);
        }
    }

    private static void add(List<File> ordered, Set<String> seen, File dir) {
        if (dir == null) {
            return;
        }
        String key;
        try {
            key = dir.getAbsoluteFile().getCanonicalPath();
        } catch (IOException e) {
            // A path that cannot be canonicalised (it does not exist yet, most likely) is still
            // a perfectly good candidate — the probe is what decides.
            key = dir.getAbsolutePath();
        }
        if (seen.add(key)) {
            ordered.add(dir);
        }
    }

    private static long pid() {
        try {
            return ProcessHandle.current().pid();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
