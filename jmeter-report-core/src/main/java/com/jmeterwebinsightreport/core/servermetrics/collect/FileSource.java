package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.util.RunIdentity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A {@code file} source: recorded {@code query_range} responses on disk, one per query.
 *
 * <p>The file for a query is {@code <path>/<digest>.json}, where the digest is
 * {@link RunIdentity#digest} of the resolved query — 16 hex characters, stable across JVMs,
 * and free of every character a query contains that a filename cannot. Nothing writes the
 * recordings yet: a person names a file with {@link #filenameFor} (or
 * {@code printf '%s' "<query>" | sha256sum | cut -c1-16}, as C35 does) and saves the response
 * a Prometheus {@code query_range} call returned.
 *
 * <p>A relative {@code path} is resolved against the annotations file's directory, not the
 * working directory the collector was launched from, so {@code "path": "server-metrics/recorded"}
 * means the same thing from every shell. The collector passes that base in; a {@code null}
 * base falls back to the working directory.
 *
 * <p>Two jobs, one mechanism. For a user it is the offline replay path — record once against
 * a real Prometheus, replay in a pipeline with no network. For the suites it is to be the
 * deterministic fixture: M5's E26 is to read recordings under {@code test-plans/} and add that
 * directory to the fixture stamp, so a changed recording regenerates the fixtures like a
 * changed source would.
 *
 * <p>A missing file is an <em>empty</em> answer, not a failure: nothing was recorded for that
 * query, which reads as NO_MATCH and sends the reader to the query — the same thing a live
 * source says when a selector matches no series. An unreadable file is FAILED.
 *
 * <p>A catalogue metric with no prometheus query has nothing recorded under any digest: its
 * request is neutral, and {@link SeriesSource}'s default answers it
 * {@link SeriesSource.Answer#noQuery} without looking — a file named after the digest of
 * {@code <type>:<metric>} is not a recording of anything Prometheus answered.
 */
public final class FileSource implements SeriesSource {

    private final File baseDir;

    /** Relative paths against the working directory. */
    public FileSource() {
        this(null);
    }

    /** @param baseDir what a relative {@code path} is resolved against — the annotations file's directory */
    public FileSource(File baseDir) {
        this.baseDir = baseDir;
    }

    public static String filenameFor(String resolvedQuery) {
        return RunIdentity.digest(resolvedQuery) + ".json";
    }

    @Override
    public Answer collect(ServerSource source, String resolvedQuery, ServerMetricsPayload.Window window) {
        File dir = new File(source.path());
        if (!dir.isAbsolute() && baseDir != null) {
            dir = new File(baseDir, source.path());
        }
        if (!dir.isDirectory()) {
            return Answer.failed("recording directory does not exist: " + dir.getAbsolutePath());
        }
        File file = new File(dir, filenameFor(resolvedQuery));
        if (!file.isFile()) {
            return Answer.of(new java.util.ArrayList<ServerMetricsPayload.InstanceRecord>());
        }
        String body;
        try {
            body = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Answer.failed("recording could not be read: " + file.getAbsolutePath() + " - " + e.getMessage());
        }
        PrometheusResponse.Parsed parsed = PrometheusResponse.parse(body, source.clockOffsetMs());
        if (parsed.failed()) {
            return Answer.failed("recording " + file.getName() + ": " + parsed.error());
        }
        return Answer.of(parsed.instances());
    }
}
