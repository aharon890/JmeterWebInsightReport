package com.jmeterwebinsightreport.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lock is an optimisation over identity, so these tests are about it behaving predictably —
 * including when it cannot be taken, which must never fail a report.
 *
 * <p>Cross-<em>process</em> exclusion is not testable from a unit test and was measured instead:
 * two containers sharing a Docker Desktop bind mount, twelve pairings, twelve mutual exclusions,
 * with a negative control proving the harness could see a violation. What is left to pin here is
 * the in-JVM layer, which exists because {@link java.nio.channels.FileLock} is held by the
 * process rather than by a thread — so a second attempt from the same JVM throws
 * {@code OverlappingFileLockException} instead of waiting, and two Web Insight listeners in one
 * test plan would collide on the very mechanism meant to protect them.
 */
class PublishLockTest {

    @TempDir
    Path dir;

    @Test
    void acquiresAndReleases() {
        try (PublishLock lock = PublishLock.acquire(dir.toFile(), 1_000L)) {
            assertTrue(lock.isHeld());
            assertNull(lock.failureReason());
        }
        // ...and again, which only works if the first release actually happened.
        try (PublishLock lock = PublishLock.acquire(dir.toFile(), 1_000L)) {
            assertTrue(lock.isHeld());
        }
    }

    @Test
    void aSecondThreadInThisJvmWaitsRatherThanThrowing() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            try (PublishLock lock = PublishLock.acquire(dir.toFile(), 2_000L)) {
                assertTrue(lock.isHeld());
                held.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (Throwable t) {
                failure.set(t);
                held.countDown();
            }
        }, "publish-lock-holder");
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "the first thread never took the lock");
        assertNull(failure.get());

        // Short timeout: the point is that this returns an un-held lock rather than throwing
        // OverlappingFileLockException, which is what the raw FileChannel API would do.
        try (PublishLock contended = PublishLock.acquire(dir.toFile(), 200L)) {
            assertFalse(contended.isHeld(), "the lock is held by another thread");
            assertNotNull(contended.failureReason(),
                    "and it must say so, because publishing proceeds without it");
        }

        release.countDown();
        holder.join(5_000);

        try (PublishLock afterRelease = PublishLock.acquire(dir.toFile(), 2_000L)) {
            assertTrue(afterRelease.isHeld(), "the lock must be available once the holder is done");
        }
    }

    @Test
    void theLockFileLivesInTheOutputDirectory() {
        // It has to: the only thing two containers sharing a mount have in common is that
        // directory. A lock in the system temp directory would be per-container and useless.
        try (PublishLock lock = PublishLock.acquire(dir.toFile(), 1_000L)) {
            assertTrue(lock.isHeld());
            assertTrue(Files.exists(dir.resolve(PublishLock.LOCK_FILENAME)));
        }
    }

    @Test
    void theLockFileSurvivesRelease() {
        // Deleting it is the obvious tidy-up and it is a race: another process can be holding a
        // lock on that inode, and once the name is gone a third creates a fresh file and locks
        // that instead — two holders, the one outcome this class exists to prevent.
        try (PublishLock lock = PublishLock.acquire(dir.toFile(), 1_000L)) {
            assertTrue(lock.isHeld());
        }
        assertTrue(Files.exists(dir.resolve(PublishLock.LOCK_FILENAME)),
                "an empty dotfile is a much smaller problem than two simultaneous publishers");
    }

    @Test
    void anUnusableDirectoryYieldsAnUnheldLockRatherThanAnException() {
        // A filesystem with no locking, a directory that vanished between the writability probe
        // and here — every one of them has to resolve to "publish anyway". Refusing to write a
        // report over a mutual-exclusion optimisation would be a strictly worse outcome than the
        // behaviour that existed before the lock did.
        File missing = new File(new File(dir.toFile(), "gone"), "deeper");
        AtomicBoolean threw = new AtomicBoolean(false);
        try (PublishLock lock = PublishLock.acquire(missing, 100L)) {
            assertFalse(lock.isHeld());
            assertNotNull(lock.failureReason());
        } catch (RuntimeException e) {
            threw.set(true);
        }
        assertFalse(threw.get(), "acquire must never throw");
    }

    @Test
    void aNullDirectoryFallsBackToTheWorkingDirectory() {
        // null is what the configuration carries when nobody set an output directory, and it
        // means the process working directory everywhere else in the plugin.
        try (PublishLock lock = PublishLock.acquire(null, 1_000L)) {
            assertTrue(lock.isHeld() || lock.failureReason() != null,
                    "either it worked or it explained itself; silence is the one wrong answer");
        } finally {
            // Best effort: do not leave a lock file in the repository root when the suite runs
            // from there.
            new File(System.getProperty("user.dir", "."), PublishLock.LOCK_FILENAME).delete();
        }
    }
}
