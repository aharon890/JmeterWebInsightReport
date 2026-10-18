package com.jmeterwebinsightreport.core.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Write a file by writing a temporary sibling and moving it into place.
 *
 * <p><b>Why.</b> Every artifact used to be written with a plain truncating write. Three
 * consequences, in increasing order of how much they cost:
 *
 * <ul>
 *   <li>A CI job that polls for the report can read a half-written 1.3 MB page, and a process
 *       killed mid-write leaves a truncated file that still satisfies {@code File.exists()} —
 *       and therefore still satisfies the exporter's collision check, so the truncated file
 *       wins and no complete one is ever produced.</li>
 *   <li>A <i>failed</i> run could corrupt a <i>successful</i> one. The external chart payload
 *       was written before the HTML, so when the HTML write failed the payload had already
 *       landed on top of the previous run's payload — leaving that run's intact, still-valid
 *       report rendering the new run's data. Reproduced with md5s.</li>
 *   <li>Nothing could tell a complete file from an incomplete one after the fact.</li>
 * </ul>
 *
 * <p>A temp-then-move fixes all three at once: the target either has its old contents or its
 * new ones, never a prefix of the new ones.
 *
 * <h2>...and why the staging name carries a process id</h2>
 *
 * <p>It was {@code .<name>.tmp} — one deterministic name per target. That is safe for one
 * writer and actively destructive for two: two JMeter runs writing into one output directory
 * shared a single staging file, so one process could publish the <em>other's</em> bytes under
 * its own name, or lose its report entirely. Measured seven times across two platforms; every
 * occurrence exited 0, and the {@code finally} cleanup removed the evidence.
 *
 * <p>Uniqueness costs something, though, and it is worth naming: a deterministic staging file
 * self-heals, because the next run simply overwrites it, whereas a unique one leaves <em>new</em>
 * debris on every SIGKILL. In a {@code jmeter -g -o} output directory that is not untidiness —
 * JMeter refuses to write into a directory that is not empty, so one leftover turns the next run
 * into a startup failure. Hence {@link #sweepStaleStaging}, which is not optional bookkeeping but
 * the other half of this change.
 */
public final class AtomicFileWriter {

    /**
     * {@code .<name>.<owner>-<pid>-<nonce>.tmp}
     *
     * <p>The pid is in the name so the sweep can ask the operating system whether the writer is
     * still alive, rather than guessing from a timestamp. The nonce separates two writers inside
     * one JVM — two Web Insight listeners in one test plan, which is a configuration that exists
     * and used to collide.
     *
     * <p><b>The owner is in the name because a pid is only meaningful inside one namespace, and
     * that was measured the hard way.</b> Two JMeter containers sharing a bind mount each run
     * their process in their own PID namespace, so both were pid 296-308. Container B swept the
     * directory, asked {@code ProcessHandle.of(296).isAlive()} — a question about <em>B's</em>
     * 296 — concluded that A was gone, and deleted A's <b>live</b> staging file. A then failed
     * its own publish with {@code NoSuchFileException} and lost its report. Six of fifty
     * iterations, which is precisely the class of bug the liveness predicate was chosen to
     * avoid; it arrived through a route the predicate did not anticipate.
     */
    private static final Pattern STAGING_NAME =
            Pattern.compile("\\.(?<target>.+)\\.(?<owner>[A-Za-z0-9_-]+)-(?<pid>\\d+)-[0-9a-f]+\\.tmp");

    /**
     * Who this JVM is, for the purpose of "may I ask the OS about that pid?".
     *
     * <p>The hostname, because it is the cheapest thing that differs between two containers and
     * agrees between two processes on one machine — which is exactly the distinction the sweep
     * needs. Docker gives each container a distinct hostname by default; two JMeter runs on one
     * workstation share theirs.
     *
     * <p>Read from the environment before {@code InetAddress}, which can block on a slow DNS
     * resolver for a value we only use as an opaque token.
     */
    private static final String OWNER = resolveOwner();

    /**
     * How old a staging file whose pid still resolves to a live process must be before it is
     * treated as debris anyway.
     *
     * <p>Liveness is the predicate (D-5); this is only a backstop against <b>pid reuse</b>. An
     * operating system recycles process ids, so a long-dead writer's id can come to belong to
     * something unrelated and its debris would then never be swept. Six hours is far beyond any
     * real staging lifetime — a staging file exists only while one artifact is being written,
     * seconds even for a megabyte payload — so this cannot fire on a live writer, however slow
     * the disk. Which is the whole point: an age-only predicate deletes a live writer's staging
     * file on a slow disk, reintroducing the bug being fixed.
     */
    private static final long PID_REUSE_GRACE_MILLIS = TimeUnit.HOURS.toMillis(6);

    private AtomicFileWriter() {
    }

    /** Streams content into the temp file, so a large payload is never held twice in memory. */
    public interface ContentWriter {
        void writeTo(Writer writer) throws IOException;
    }

    public static void write(File target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(File target, byte[] content) throws IOException {
        Path tmp = stagingSibling(target);
        try {
            Files.write(tmp, content);
            publish(tmp, target.toPath());
        } finally {
            // A failed write must not leave debris. It would be misleading on its own, and in a
            // `-g -o` output directory it is worse than that: JMeter refuses to write into a
            // directory that is not empty, so one leftover temp file turns the NEXT run into a
            // startup failure.
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Write via a {@link Writer}, for content that should never be materialised as one big
     * String — the external chart payload is megabytes, and building it as a String and then
     * concatenating a wrapper allocated the whole thing twice inside the JMeter JVM at the very
     * end of a large soak run.
     */
    public static void write(File target, ContentWriter content) throws IOException {
        Path tmp = stage(target, content);
        try {
            publish(tmp, target.toPath());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- the two halves, separately ----------------------------------------------

    /**
     * Write the content to a staging file and stop there, returning where it landed.
     *
     * <p>Exposed so a caller can stage an entire artifact <em>set</em> before publishing any of
     * it. Per-file atomicity says nothing about the set: publish order used to be HTML → payload
     * → JSON → XML with the rendering, serialising and writing of each happening between the
     * renames, so a kill or a full disk anywhere in that span left this run's HTML beside the
     * previous run's JSON — the mixed-content outcome, with no concurrency involved at all.
     * Staging everything first does not make the set atomic (no filesystem primitive replaces N
     * files at once) but it shrinks the window from "the whole render" to "four renames".
     */
    public static Path stage(File target, ContentWriter content) throws IOException {
        Path tmp = stagingSibling(target);
        boolean written = false;
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(tmp), StandardCharsets.UTF_8))) {
            content.writeTo(writer);
            written = true;
        } finally {
            if (!written) {
                // The caller never learns this path, so nothing else can clean it up.
                Files.deleteIfExists(tmp);
            }
        }
        return tmp;
    }

    /** @see #stage(File, ContentWriter) */
    public static Path stage(File target, String content) throws IOException {
        return stage(target, writer -> writer.write(content));
    }

    /**
     * Move a staged file into place.
     *
     * <p>Atomic where the filesystem supports it. Where it does not, a non-atomic replace is
     * still strictly better than truncating the target and writing into it, because the content
     * being moved is already complete.
     */
    public static void publish(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- debris ------------------------------------------------------------------

    /**
     * Remove staging files in {@code target}'s directory left behind by writers that are no
     * longer running.
     *
     * <p>Best effort by construction: a file we cannot delete, a directory we cannot list, a
     * name that does not parse — none of it is worth failing a report over, and all of it is
     * survivable. What is <b>not</b> survivable is deleting a live writer's staging file, so the
     * predicate is process liveness (D-5) and never age alone. A file belonging to this very
     * process is skipped outright: another thread in this JVM may be mid-write, and it is the one
     * writer we can be certain is alive.
     *
     * <p><b>Every staging file in the directory is considered, not only {@code target}'s.</b> It
     * used to list {@code "." + target.getName() + ".*.tmp"} and require the parsed target group
     * to equal this target's name, which made the sweep able to clean up after itself and after
     * nothing else. Debris from any other name was invisible to every process that could have
     * removed it — and the name that matters most is the one this plugin documents for keeping
     * reports apart, {@code ${timestamp}}, which is a <em>different</em> name on every run. A run
     * killed mid-write left {@code .report_20260101_120000.html.host-9-ab12cd34.tmp} that the next
     * run, writing {@code report_20260101_120500.html}, would never look at. Nothing else sweeps,
     * so it accumulated for the life of the directory. Narrowing by name bought nothing: the
     * decision to delete was never the glob's to make. It belongs to {@link #STAGING_NAME}, which
     * proves the file is one of ours, and to the owner and liveness checks below, which prove its
     * writer is gone — and those are exactly as strict for another target's debris as for this
     * one's.
     *
     * @return how many files were removed — for logging and for the test that proves a live
     *         writer's file is not among them
     */
    public static int sweepStaleStaging(File target) {
        File parent = target.getParentFile();
        Path dir = (parent != null ? parent : new File(".")).toPath();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long ownPid = currentPid();
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, ".*.tmp")) {
            for (Path entry : entries) {
                if (isStaleStaging(entry, ownPid)) {
                    try {
                        if (Files.deleteIfExists(entry)) {
                            removed++;
                        }
                    } catch (IOException ignored) {
                        // Held open by something, or read-only. Leaving it is the safe outcome.
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Includes the pattern-syntax and directory-vanished cases. Sweeping is a courtesy.
        }
        return removed;
    }

    /** True when this entry is our own staging pattern and its writer is provably gone. */
    private static boolean isStaleStaging(Path entry, long ownPid) {
        Matcher m = STAGING_NAME.matcher(entry.getFileName().toString());
        if (!m.matches()) {
            return false; // not ours to delete
        }
        if (!OWNER.equals(m.group("owner"))) {
            // Another machine, or another container sharing this mount. Its pids mean nothing
            // here — asking about them answers a question about OUR namespace — so liveness is
            // unavailable and only gross age can speak. This is the branch that stops a sweep
            // from deleting a live writer's staging file across a container boundary.
            return isAncient(entry);
        }
        long pid;
        try {
            pid = Long.parseLong(m.group("pid"));
        } catch (NumberFormatException e) {
            return false;
        }
        if (pid == ownPid) {
            // Another thread in this JVM may be writing it right now.
            return false;
        }
        if (!isAlive(pid)) {
            return true;
        }
        // The pid resolves to something running — but process ids are recycled, so a file this
        // old cannot belong to it. See PID_REUSE_GRACE_MILLIS.
        return isAncient(entry);
    }

    /** Older than any staging file can legitimately be. Conservative on any error. */
    private static boolean isAncient(Path entry) {
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(entry).toMillis();
            return age > PID_REUSE_GRACE_MILLIS;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A filename-safe token identifying this machine or container.
     *
     * <p>Sanitised rather than trusted: a hostname reaches the filesystem here, and a dot in it
     * would break the staging-name pattern the sweep matches on. Truncated because a Kubernetes
     * pod name plus the report name plus the pid and nonce can otherwise approach the 255-byte
     * component limit.
     *
     * <p><b>The allowlist is ASCII, and it has to be the same allowlist {@link #STAGING_NAME}
     * uses.</b> It was {@code Character.isLetterOrDigit}, which keeps Unicode letters, while the
     * pattern's owner group has always been {@code [A-Za-z0-9_-]} — so a host called
     * {@code wörkstation} wrote {@code .report.html.wörkstation-9-ab12cd34.tmp} and then could not
     * recognise it: the {@code ö} is outside the owner class, and no amount of backtracking in the
     * greedy {@code target} group can rescue the match, because the owner group must start
     * immediately after a literal dot and there is none inside the hostname. The sweep did not
     * merely miss that one file — {@link #isStaleStaging} rejects every entry whose name does not
     * parse, so on such a host it was permanently inert and staging debris accumulated forever.
     * Two spellings of one rule is the bug; ASCII is the spelling to keep, because it is the one
     * that is also safe on every filesystem the name has to survive.
     */
    private static String resolveOwner() {
        String raw = System.getenv("HOSTNAME");            // set in Linux containers
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv("COMPUTERNAME");           // Windows
        }
        if (raw == null || raw.trim().isEmpty()) {
            try {
                raw = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                raw = null;
            }
        }
        return sanitizeOwner(raw);
    }

    /**
     * A hostname reduced to the pattern's own alphabet.
     *
     * <p>Package-private so a test can pass the hostname this machine does not have. The defect
     * this exists to pin only appears on a host whose name is not ASCII, which is not a thing a
     * test can arrange about the machine it is running on.
     */
    static String sanitizeOwner(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            // Both writers falling back to the same token puts us back to comparing pids across
            // namespaces, so this is the one case where a shared value is a real (if narrow)
            // risk. Nothing better is available without inventing state on disk.
            return "unknown";
        }
        StringBuilder safe = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && safe.length() < 40; i++) {
            char c = raw.charAt(i);
            safe.append(isOwnerChar(c) ? c : '-');
        }
        return safe.length() == 0 ? "unknown" : safe.toString();
    }

    /**
     * The owner alphabet, in Java rather than in a second regex.
     *
     * <p>Must stay exactly the {@code [A-Za-z0-9_]} half of {@link #STAGING_NAME}'s owner group.
     * The class also admits {@code -}, which is this method's substitute for everything else, so
     * a sanitised name is always matchable.
     */
    private static boolean isOwnerChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_';
    }

    /**
     * Is a process with this id currently running?
     *
     * <p>{@code ProcessHandle} answers this without spawning anything, on every platform the
     * plugin runs on. A false "alive" is the safe direction — it means debris survives one more
     * run — so anything unexpected resolves that way.
     */
    private static boolean isAlive(long pid) {
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * A sibling in the same directory, because a move is only atomic within one filesystem —
     * the system temp directory is frequently a different one.
     *
     * <p>Leading dot so it is at least inconspicuous on Unix if a crash outside our
     * {@code finally} ever strands one.
     */
    static Path stagingSibling(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        // Sweep here rather than at the call sites, because here is the one place every write
        // passes through. A sweep that has to be remembered is a sweep that will be forgotten by
        // the next code path added, and the cost of forgetting it is a `-g -o` run that refuses
        // to start.
        sweepStaleStaging(target);
        return new File(parent, "." + target.getName() + "." + OWNER + "-" + currentPid() + "-"
                + String.format("%08x", ThreadLocalRandom.current().nextInt()) + ".tmp").toPath();
    }
}
