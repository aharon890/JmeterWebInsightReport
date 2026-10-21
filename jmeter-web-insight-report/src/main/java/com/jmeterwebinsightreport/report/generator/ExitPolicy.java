package com.jmeterwebinsightreport.report.generator;

import java.util.function.Consumer;

/**
 * Whether this run turns into a non-zero exit, and on whose authority.
 *
 * <p>Two flags, two very different questions, and both paths must answer them identically:
 *
 * <ul>
 *   <li>{@code failonerror} — <em>did the plugin do what it was told?</em> A report it could not
 *       write, or a gate it could not build from the file. Defaults to <b>true</b>.</li>
 *   <li>{@code failonassert} — <em>is this verdict one you want a build to stop on?</em> A
 *       declared assert measured against a real number and breached. Defaults to <b>false</b>,
 *       and stays that way: deciding what a FAIL means is the tester's job.</li>
 * </ul>
 *
 * <p>This class exists because the two ingestion paths were answering them separately.
 * {@link BooleanSetting#FAIL_ON_ERROR_CONSEQUENCE} was already extracted with a comment saying
 * "both ingestion paths read that property and both must say the same thing about it; they
 * already duplicate the read itself, and two spellings of the warning is the drift that
 * duplication produces" — which named the risk and then left the read duplicated. The prediction
 * came true one flag over: {@code failonassert} was read on the listener path and <b>not read at
 * all</b> by the {@code -g -o} exporter, so the same annotations file and the same FAIL verdict
 * exited 1 under {@code -n -t} and 0 under {@code -g -o}. Nothing noticed, because the scenario
 * that covers the flag only runs the listener path.
 *
 * <p>So the read, the default and the warning live here once, and a path cannot forget a flag
 * without deleting a call.
 *
 * <p>The warning matters more for these two than for the rest. Only {@code true} and {@code false}
 * are accepted, so {@code failonassert=1} says nothing and the default applies — which for this
 * one is the <em>un-gating</em> direction: a pipeline that armed the flag with a plausible-looking
 * value gets a green build from a red gate, in silence. It was the only boolean property in the
 * plugin read without a warning.
 */
public final class ExitPolicy {

    private ExitPolicy() {
    }

    /**
     * A configuration error is fatal unless explicitly told otherwise.
     *
     * @param raw   the property value, exactly as read; null or empty means unset
     * @param warn  where an unrecognised value is reported — a logger on one path, stderr on the
     *              other, which is the only thing that legitimately differs between them
     */
    public static boolean failOnError(String raw, Consumer<String> warn) {
        report(warn, BooleanSetting.warningFor(NonZeroExit.FAIL_ON_ERROR, raw,
                BooleanSetting.FAIL_ON_ERROR_CONSEQUENCE));
        return BooleanSetting.parse(raw).orElse(true);
    }

    /**
     * A FAIL verdict is fatal only when asked.
     *
     * <p>The consequence clause is spelled out rather than borrowed from
     * {@code failonerror}: the two defaults point in opposite directions, and telling an operator
     * their unrecognised value is being "treated as if it were not set" is only useful if it also
     * says which way that lands.
     */
    public static boolean failOnAssert(String raw, Consumer<String> warn) {
        report(warn, BooleanSetting.warningFor(NonZeroExit.FAIL_ON_ASSERT, raw,
                "a FAIL verdict will NOT fail this run, which is the default"));
        return BooleanSetting.parse(raw).orElse(false);
    }

    /**
     * A server-data problem is fatal only when asked.
     *
     * <p>Kept apart from {@code failonerror} because the two sides carry different weight: the
     * client report is the product, the server tab a bonus on it. A failed collection, a source
     * that never answered or a payload of another run must not turn a build red by default — they
     * are said loudly on the console, the page and the JSON instead. A team whose pipeline needs
     * server data to be there sets this, and gets back exactly the fold {@code failonerror} used to
     * apply to both sides.
     */
    public static boolean failOnServerError(String raw, Consumer<String> warn) {
        report(warn, BooleanSetting.warningFor(NonZeroExit.FAIL_ON_SERVER_ERROR, raw,
                "a server-data problem will NOT fail this run, which is the default"));
        return BooleanSetting.parse(raw).orElse(false);
    }

    private static void report(Consumer<String> warn, String warning) {
        if (warning != null && warn != null) {
            warn.accept(warning);
        }
    }
}
