package com.jmeterwebinsightreport.core.util;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A cross-process mutex over one output directory, held for the few milliseconds it takes to
 * rename a staged artifact set into place.
 *
 * <h2>Why this exists, and why it is an optimisation rather than the mechanism</h2>
 *
 * <p>Staging the whole set before publishing shrinks the window in which two runs can interleave
 * from "the whole render" to "four renames". It does not close it, and nothing can: no
 * filesystem primitive replaces N files atomically. This lock closes it in practice, by making
 * the four renames mutually exclusive between processes sharing a directory.
 *
 * <p>It is deliberately <b>not</b> load-bearing. Every artifact also carries the run's identity,
 * and the report refuses to chart a payload that is not its own — so a mix that does survive
 * announces itself rather than being believed. That ordering matters, because a lock that
 * silently does nothing is worse than no lock: it would make the residual risk invisible. If the
 * lock cannot be taken, for any reason, publishing proceeds anyway and says so. Last-writer-wins
 * is an acceptable outcome; mixed content is not, and identity is what rules that out.
 *
 * <h2>That it works here was measured, not assumed</h2>
 *
 * <p>Advisory file locking is exactly the kind of thing that is quietly a no-op on some
 * filesystems, and the deployment that matters most for this plugin — two JMeter containers
 * sharing one bind-mounted results directory — runs over Docker Desktop's 9p/drvfs mount, which
 * is a filesystem locking is often assumed broken on. So it was tested before anything depended
 * on it: two containers, one bind-mounted lock file, one holding for eight seconds while the
 * other spun on {@code tryLock}. Twelve pairings, twelve times mutually excluded, no exceptions;
 * a same-container control and a host baseline agreed; and a negative control with two
 * <em>different</em> lock files was correctly detected as interleaved, proving the harness could
 * see a failure if there were one.
 *
 * <p><b>That result is not transitive.</b> It says nothing about SMB or NFS, where advisory
 * locking and atomic rename are both unreliable, and enterprise JMeter writing to a network
 * share is a real deployment. Hence the design above: on a filesystem where this lock is a no-op,
 * the plugin degrades to identity-only, which is the behaviour it would have had anyway.
 *
 * <h2>The in-process layer</h2>
 *
 * <p>{@link FileLock} is held by the <em>JVM</em>, not by a thread. A second attempt to lock the
 * same file from the same JVM throws {@link OverlappingFileLockException} rather than waiting —
 * so two Web Insight listeners in one test plan, or a listener and the {@code -g} exporter in one
 * run, would collide on the very mechanism meant to protect them. A {@link ReentrantLock} per
 * lock-file path sits above it and turns that into an ordinary wait.
 */
public final class PublishLock implements AutoCloseable {

    /** Sits in the output directory, because that is the only thing two containers share. */
    static final String LOCK_FILENAME = ".web-insight-publish.lock";

    /**
     * One monitor per lock-file path, for this JVM.
     *
     * <p>Never cleared. Bounded by the number of distinct output directories one process writes
     * to, which is one in every configuration that is not a bug.
     */
    private static final ConcurrentHashMap<String, ReentrantLock> IN_PROCESS =
            new ConcurrentHashMap<>();

    private final ReentrantLock inProcess;
    private final FileChannel channel;
    private final FileLock fileLock;
    private final String failure;

    private PublishLock(ReentrantLock inProcess, FileChannel channel, FileLock fileLock,
                        String failure) {
        this.inProcess = inProcess;
        this.channel = channel;
        this.fileLock = fileLock;
        this.failure = failure;
    }

    /**
     * Take the lock on this directory, or give up and say why.
     *
     * <p>Never throws. Every failure mode — an unsupported filesystem, a directory that vanished,
     * another process holding on past the timeout — resolves to an un-held lock, because the
     * alternative is refusing to write a report over a mutual-exclusion optimisation.
     *
     * @param directory     the output directory; null means the process working directory
     * @param timeoutMillis how long to wait before proceeding without it
     */
    public static PublishLock acquire(File directory, long timeoutMillis) {
        File dir = directory != null ? directory
                : new File(System.getProperty("user.dir", "."));
        File lockFile = new File(dir, LOCK_FILENAME);
        String key;
        try {
            key = lockFile.getCanonicalPath();
        } catch (IOException e) {
            key = lockFile.getAbsolutePath();
        }

        ReentrantLock monitor = IN_PROCESS.computeIfAbsent(key, k -> new ReentrantLock());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            if (!monitor.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return new PublishLock(null, null, null,
                        "another thread in this JVM held it for longer than " + timeoutMillis + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PublishLock(null, null, null, "interrupted while waiting");
        }

        FileChannel channel = null;
        try {
            Path path = lockFile.toPath();
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = waitForLock(channel, deadline);
            if (lock != null) {
                return new PublishLock(monitor, channel, lock, null);
            }
            closeQuietly(channel);
            monitor.unlock();
            return new PublishLock(null, null, null,
                    "another process held it for longer than " + timeoutMillis + "ms");
        } catch (IOException | RuntimeException e) {
            // Includes filesystems that do not implement locking at all, and a directory that
            // disappeared between the writability probe and here.
            closeQuietly(channel);
            monitor.unlock();
            return new PublishLock(null, null, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Spin on {@code tryLock} rather than blocking in {@code lock()}, so the timeout is real. */
    private static FileLock waitForLock(FileChannel channel, long deadline) throws IOException {
        while (true) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // Should be impossible: the in-process monitor above is held. Treated as
                // contention rather than propagated, because failing a report over it would be
                // a strictly worse outcome than publishing unlocked.
                lock = null;
            }
            if (lock != null) {
                return lock;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** True when this process actually has exclusive access. */
    public boolean isHeld() {
        return fileLock != null;
    }

    /** Why the lock could not be taken, or null when it was. */
    public String failureReason() {
        return failure;
    }

    /**
     * Release, in the reverse of acquisition order.
     *
     * <p>The lock <em>file</em> is deliberately left on disk. Deleting it is the obvious tidy-up
     * and it is a race: another process can be holding a lock on that inode, and once the name is
     * gone a third process creates a fresh file and locks that instead — two holders, which is
     * the one outcome this class exists to prevent. It is an empty dotfile in a directory that
     * already contains the report.
     */
    @Override
    public void close() {
        if (fileLock != null) {
            try {
                fileLock.release();
            } catch (IOException ignored) {
                // The channel close below releases it regardless.
            }
        }
        closeQuietly(channel);
        if (inProcess != null && inProcess.isHeldByCurrentThread()) {
            inProcess.unlock();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Nothing useful left to do while unwinding.
            }
        }
    }
}
