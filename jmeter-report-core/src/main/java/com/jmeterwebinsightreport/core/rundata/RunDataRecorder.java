package com.jmeterwebinsightreport.core.rundata;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A JMeter run's run data, recorded while the report is built and published beside it: what
 * {@code webinsight.report.rundata=true} writes (spec 001, decision 9). The listener's samplers call it from many
 * threads at once; the {@code -g} path from one.
 *
 * <p>The samples go to a staging file first, so a run that never finishes leaves nothing in the output folder. A
 * failed sample's response body is cut where the report cuts it ({@code webinsight.error.body.maxsize}), so the file
 * carries exactly the bodies the report carries (decision 11). A write that fails is remembered, not thrown: the run
 * goes on, and {@link #publish} says so.
 */
public final class RunDataRecorder implements Closeable {

    private final File staging;
    private final RunDataWriter writer;
    private final int maxBodyLength;
    private final AtomicLong ids = new AtomicLong();
    private volatile IOException failure;
    private boolean closed;

    private RunDataRecorder(File staging, RunDataWriter writer, int maxBodyLength) {
        this.staging = staging;
        this.writer = writer;
        this.maxBodyLength = maxBodyLength;
    }

    /** Opens a staging file for a run described by {@code header}. */
    public static RunDataRecorder open(RunHeader header, int maxBodyLength) throws IOException {
        File staging = File.createTempFile("wir-run-data-", ".jsonl.gz");
        staging.deleteOnExit();
        return new RunDataRecorder(staging, new RunDataWriter(staging, header), maxBodyLength);
    }

    /** A fresh sample id, for a producer that names parents by its own samples (the listener). */
    public String nextId() {
        return "s" + ids.incrementAndGet();
    }

    /** Records one sample, its body cut as the report cuts it. Never throws. */
    public void sample(RunSample s) {
        if (failure != null) {
            return;
        }
        String body = s.responseBody;
        if (body != null && body.length() > maxBodyLength) {
            s.responseBody = body.substring(0, maxBodyLength) + "...";
        }
        try {
            writer.sample(s);
        } catch (IOException e) {
            failure = e;
        } finally {
            s.responseBody = body;
        }
    }

    /**
     * Closes the file and moves it beside the report: {@code <report name>-run-data.jsonl.gz}.
     *
     * @return the file written
     * @throws IOException when a sample could not be written, or the file could not be moved
     */
    public synchronized File publish(File outputDir, String htmlFileName) throws IOException {
        close();
        if (failure != null) {
            Files.deleteIfExists(staging.toPath());
            throw new IOException("the run data could not be written: " + failure.getMessage(), failure);
        }
        String base = htmlFileName.endsWith(".html")
                ? htmlFileName.substring(0, htmlFileName.length() - ".html".length()) : htmlFileName;
        File target = new File(outputDir, base + RunDataFormat.FILE_SUFFIX);
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Another file system than the temporary folder's: copy, then drop the staging file
            Files.copy(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(staging.toPath());
        }
        return target;
    }

    /** Drops the staging file: the run produced no report to put it beside. */
    public synchronized void discard() {
        try {
            close();
        } catch (IOException ignored) {
            // nothing to keep
        }
        staging.delete();
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            writer.close();
        }
    }
}
