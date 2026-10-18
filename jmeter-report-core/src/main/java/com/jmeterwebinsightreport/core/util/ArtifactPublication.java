package com.jmeterwebinsightreport.core.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage a whole artifact set, then put it on disk in one tight sequence.
 *
 * <p><b>Why per-file atomicity was not enough.</b> {@link AtomicFileWriter} guarantees that each
 * file is either its old contents or its new contents, never a prefix of the new ones. It says
 * nothing about the <em>set</em>. Report generation used to write HTML, then serialise and write
 * the payload, then serialise and write the CI/CD JSON, then build and write the JUnit XML — with
 * all the expensive work happening <em>between</em> the writes. A kill, a full disk or an ACL
 * change anywhere in that span left this run's HTML beside the previous run's JSON: mixed
 * content, no concurrency required. Worse, the HTML landed before the payload it references, so a
 * failed payload write left a brand-new report pointing at the previous run's chart data, from a
 * process that exited 0.
 *
 * <p>Staging everything first shrinks the exposed window from "the whole render" — seconds to
 * minutes on a large run — to "four renames". It does <b>not</b> make the set atomic: no
 * filesystem primitive replaces N files at once, on POSIX or NTFS. That residual window is why
 * every artifact also carries a run identity, so a set that does get mixed says so instead of
 * being believed.
 *
 * <p><b>Publish order is the reverse of the dependency order.</b> The payload goes first and the
 * HTML that loads it second, so a reader who opens the directory mid-publish never finds a page
 * whose {@code <script src>} points at a file that is not there yet. The JSON and XML follow;
 * nothing reads them at open time.
 *
 * <p>Not thread-safe: one instance belongs to one report generation.
 */
public final class ArtifactPublication implements AutoCloseable {

    /** One artifact that could not be moved into place. */
    public static final class Failure {
        private final File target;
        private final IOException cause;

        Failure(File target, IOException cause) {
            this.target = target;
            this.cause = cause;
        }

        public File target() {
            return target;
        }

        public IOException cause() {
            return cause;
        }
    }

    private static final class Staged {
        private final File target;
        private final Path staging;

        Staged(File target, Path staging) {
            this.target = target;
            this.staging = staging;
        }
    }

    private final List<Staged> staged = new ArrayList<>(4);

    /**
     * Write the content to a staging file beside its target, and stop.
     *
     * <p>Throws on failure, and the caller decides how much that matters: a payload that cannot
     * be staged is fatal to the report, a JUnit XML that cannot be staged is worth recording and
     * carrying on from. Nothing is left behind on a throw.
     */
    public void stage(File target, AtomicFileWriter.ContentWriter content) throws IOException {
        staged.add(new Staged(target, AtomicFileWriter.stage(target, content)));
    }

    /** @see #stage(File, AtomicFileWriter.ContentWriter) */
    public void stage(File target, String content) throws IOException {
        stage(target, writer -> writer.write(content));
    }

    /** Has this target already been staged? */
    public boolean isStaged(File target) {
        for (Staged s : staged) {
            if (s.target.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Move every staged file into place, in staging order.
     *
     * <p>Does not stop at the first failure. One unwritable {@code .json} path must not cost the
     * operator the report that was already staged successfully beside it — that is the same
     * judgement the JSON and XML writers have always made, applied to the publish step. Every
     * failure is returned so the caller can mark that artifact and decide whether the set is
     * complete enough to call the run a success.
     *
     * <p>Published entries are dropped from the pending list, so {@link #close} after a partial
     * publish cleans up exactly the ones that never landed.
     *
     * @return the artifacts that could not be published, in order; empty when the whole set landed
     */
    public List<Failure> publish() {
        return publish(null);
    }

    /**
     * Publish the set, abandoning the rest if {@code critical} cannot be published.
     *
     * <p>The report is what every other artifact describes. Publishing a CI/CD JSON beside a
     * report that never landed produces a summary of a file that does not exist — a pipeline
     * reads it, believes it, and gates on numbers whose report cannot be opened. That is the
     * mixed-set outcome this class exists to prevent, arriving from the inside.
     *
     * <p>Measured: with a directory sitting at the HTML's path, the HTML failed its rename and
     * the JSON published happily behind it. Anything not reached stays staged, so {@link #close}
     * removes it and the directory is left as it was.
     *
     * @param critical the artifact nothing may outlive, or null to publish independently
     */
    public List<Failure> publish(File critical) {
        List<Failure> failures = new ArrayList<>(0);
        for (Staged s : new ArrayList<>(staged)) {
            try {
                AtomicFileWriter.publish(s.staging, s.target.toPath());
                staged.remove(s);
            } catch (IOException e) {
                // Name the TARGET, not the staging file.
                //
                // The NIO file exceptions carry the path they failed on, which for a move is the
                // SOURCE — so a failure to publish reported itself as
                // "access denied: .web-insight-report.json.26572-f3daf593.tmp", naming an
                // internal temp file the operator has never heard of and cannot act on, while
                // the thing that was wrong was the destination. Measured, on a run where a
                // directory sat at the .json's path.
                failures.add(new Failure(s.target, new IOException(
                        "could not be moved into place: " + s.target.getAbsolutePath()
                                + " (" + reason(e) + ")", e)));
                if (s.target.equals(critical)) {
                    break;
                }
            }
        }
        return failures.isEmpty() ? Collections.emptyList() : failures;
    }

    /**
     * The short "why" for a failed move, without repeating the path the caller already has.
     *
     * <p>{@code FileSystemException.getMessage()} is the offending filename and nothing else, so
     * it says nothing on its own; {@link java.nio.file.FileSystemException#getReason()} is the
     * half that explains, and the exception type is the fallback when even that is absent.
     */
    private static String reason(IOException e) {
        if (e instanceof java.nio.file.AccessDeniedException) {
            return "access denied";
        }
        if (e instanceof java.nio.file.FileSystemException) {
            String reason = ((java.nio.file.FileSystemException) e).getReason();
            if (reason != null && !reason.trim().isEmpty()) {
                return reason.trim();
            }
            return e.getClass().getSimpleName();
        }
        String message = e.getMessage();
        return message != null && !message.trim().isEmpty()
                ? message.trim() : e.getClass().getSimpleName();
    }

    /**
     * Delete anything staged that was never published.
     *
     * <p>Not merely tidiness. A leftover staging file in a {@code jmeter -g -o} output directory
     * makes JMeter refuse the <em>next</em> run outright, because it will not write into a
     * directory that is not empty — so an abandoned publication would turn one failed run into
     * every subsequent run failing at startup.
     */
    @Override
    public void close() {
        for (Staged s : staged) {
            try {
                Files.deleteIfExists(s.staging);
            } catch (IOException ignored) {
                // Best effort; the sweep will collect it once this process is gone.
            }
        }
        staged.clear();
    }
}
