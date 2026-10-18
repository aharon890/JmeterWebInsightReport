package com.jmeterwebinsightreport.report.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Arranges a non-zero process status without throwing.
 *
 * <p>Extracted from the listener because both ingestion paths now need it and they must not
 * answer differently. The listener never had a choice — an exception thrown from
 * {@code testEnded} becomes:
 *
 * <pre>WARN o.a.j.e.StandardJMeterEngine: Error encountered during shutdown of
 * com.jmeterwebinsightreport.report.listener.WebInsightReportListener@4392fbe0</pre>
 *
 * <p>...and the process still exits 0, so throwing produces a warning in a log file nobody
 * reads and changes nothing.
 *
 * <p>The exporter <em>does</em> have a choice: an {@code ExportException} propagates and JMeter
 * exits 1 on its own. It is still the wrong one for a <em>configuration</em> error. JMeter
 * renders that exception as {@code ERROR o.a.j.JMeter: An error occurred:} followed by a full
 * stack trace through {@code ReportGenerator.exportData}, which reads as a plugin crash when
 * what actually happened is that someone typed {@code responseTimeish}. The findings, the
 * {@code CONFIG ERROR} marker and one {@code ERROR} line already say everything there is to
 * say; twelve frames of JMeter internals say nothing and bury it.
 *
 * <p>It also cost the tests a real check: {@code log_has_stacktrace} is how N08/N09 catch the
 * variant the file assertions cannot see — an exception caught and logged, leaving a complete
 * set of artifacts and a rule silently unjudged. A stack trace the suite has to expect is a
 * stack trace the suite can no longer notice.
 *
 * <p>A genuine artifact failure still throws on the exporter path. That one <em>is</em>
 * exceptional — an IO error — and JMeter's own reporting of it is appropriate.
 *
 * <p>Why {@link Runtime#halt} from a shutdown hook rather than {@code System.exit} inline: on
 * the listener path {@code testEnded} runs while other listeners still have theirs pending, so
 * exiting inline can cut off a {@code SimpleDataWriter} mid-flush and truncate the JTL —
 * trading a report for a corrupted results file. A hook runs after JMeter's own shutdown has
 * completed, and {@code halt} from inside one sets the process status even though JMeter
 * itself exits 0.
 */
public final class NonZeroExit {

    private static final Logger log = LoggerFactory.getLogger(NonZeroExit.class);

    /** One shutdown hook per JVM, however many reasons it is given. */
    private static final AtomicBoolean ARRANGED = new AtomicBoolean(false);

    private NonZeroExit() {
    }

    /**
     * Registers a shutdown hook that halts the JVM with status 1.
     *
     * @param flag the property that asked for this, e.g. {@code webinsight.report.failonassert}.
     *        Named rather than assumed: two different flags arrange this exit, and the message
     *        hardcoded {@code failonerror=true} for both — so an operator who had deliberately
     *        set {@code failonerror=false} and armed {@code failonassert} was told, on the one
     *        line CI shows them, that a flag they had turned off had failed their build.
     * @param reason what to log and print — already-formatted, user-facing text, not a throwable
     */
    public static void arrange(String flag, String reason) {
        // Every reason is logged; the hook is registered once. A run can have two things wrong at
        // the same time — a lost artifact and a broken gate — and both branches now report
        // independently, which is the point; what must not double is the exit itself, or the
        // shutdown prints "exiting 1 because …" twice and the second hook races a halted JVM.
        log.error("{}=true — this run will exit 1: {}", flag, reason);
        if (!ARRANGED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println("WEB-INSIGHT-REPORT: exiting 1 because " + flag + "=true");
                System.err.flush();
                Runtime.getRuntime().halt(1);
            }, "webinsight-nonzero-exit"));
        } catch (IllegalStateException alreadyShuttingDown) {
            // Too late to register a hook. Nothing useful left to do; the marker has already
            // been printed, which is the part CI can actually key on.
            log.error("Could not arrange a non-zero exit: shutdown is already in progress");
        }
    }

    /** The flag that makes a configuration error fatal. Defaults to true. */
    public static final String FAIL_ON_ERROR = "webinsight.report.failonerror";

    /** The flag that makes a FAIL verdict fatal. Defaults to false, and stays that way. */
    public static final String FAIL_ON_ASSERT = "webinsight.report.failonassert";
}
