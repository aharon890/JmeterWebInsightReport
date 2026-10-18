package com.jmeterwebinsightreport.report.generator;

import java.util.Locale;

/**
 * How a boolean {@code -J} property resolves — including what an unrecognised value means.
 *
 * <p><b>The bug this removes.</b> Every boolean flag was read as
 * {@code "true".equalsIgnoreCase(value)}, with the GUI/JMX field consulted only when the
 * property was {@code null} or empty. So {@code -Jwebinsight.report.junit=yes} matched neither
 * branch: it did not enable JUnit XML, and it did not fall through to the checkbox that was
 * ticked. A typo silently voided a deliberate setting, with no message on any channel.
 *
 * <p><b>Unrecognised resolves to UNSET, not to false.</b> Reporting the typo is only half the
 * fix. Treating it as false would still void the checked box — just noisily — and the operator's
 * two inputs disagree in the one direction that loses information. Falling through means the
 * remaining configuration decides, which is exactly what happens when the property is absent,
 * which is the closest thing to "this value told us nothing".
 *
 * <p><b>Only {@code true} and {@code false} are accepted</b>, case-insensitively and trimmed.
 * Not {@code yes}, not {@code 1}, not {@code on}. A wider vocabulary reads as friendlier and is
 * not: JMeter's own {@code getPropertyAsBoolean} is {@code Boolean.parseBoolean}, so
 * {@code yes} is false there, and a plugin that accepted it would mean the same value in two
 * fields of one test plan did two different things. Rejecting it loudly is the only reading that
 * cannot surprise anyone.
 */
public final class BooleanSetting {

    /** What a configured string resolved to. */
    public enum Value {
        TRUE,
        FALSE,
        /** Absent, blank, or unrecognised — the next configuration source decides. */
        UNSET;

        /** {@code this} when it says something, otherwise the caller's fallback. */
        public boolean orElse(boolean fallback) {
            return this == UNSET ? fallback : this == TRUE;
        }
    }

    private BooleanSetting() {
    }

    public static Value parse(String raw) {
        if (raw == null) {
            return Value.UNSET;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return Value.UNSET;
        }
        if ("true".equals(trimmed)) {
            return Value.TRUE;
        }
        if ("false".equals(trimmed)) {
            return Value.FALSE;
        }
        return Value.UNSET;
    }

    /**
     * The warning to log for this value, or null when there is nothing to say.
     *
     * <p>Returned as a String rather than logged here for the same reason
     * {@link ReportArtifacts} builds its own text: there is no slf4j binding on the test
     * classpath, so wording asserted through a logging framework is not assertable at all.
     *
     * <p>This overload is for a setting whose fallback is a GUI or JMX field.
     */
    public static String warningFor(String propertyName, String raw) {
        return warningFor(propertyName, raw,
                "any GUI or JMX value for this setting still applies");
    }

    /**
     * The same warning, saying what will happen <em>instead</em>.
     *
     * <p><b>Why the caller has to supply that.</b> "Treating it as if it were not set" is
     * accurate and, for one setting, actively misleading. {@code UNSET} means "the next
     * configuration source decides", and for {@code junit} and {@code externaldata} that source
     * resolves to off — the harmless direction, and the one an operator writing
     * {@code junit=yes} was reaching for anyway. For {@code failonerror} it resolves to the
     * default, which is now <b>true</b>: {@code -Jwebinsight.report.failonerror=0} is read as
     * saying nothing, and a run whose artifact set is incomplete then fails, which is the exact
     * opposite of what whoever typed {@code 0} was asking for.
     *
     * <p>That is not an argument for widening the vocabulary — {@code 0} was never an opt-out,
     * only a value indistinguishable from absent back when the default was false, and accepting
     * it here would make one string mean two different things between this plugin and JMeter's
     * own {@code Boolean.parseBoolean}. It is an argument for the warning naming the outcome
     * rather than the mechanism, because the mechanism is the same in both cases and the outcome
     * is not.
     *
     * @param consequence what happens now that this value said nothing, as a clause that reads
     *                    on from an em dash
     */
    public static String warningFor(String propertyName, String raw, String consequence) {
        if (raw == null || raw.trim().isEmpty() || parse(raw) != Value.UNSET) {
            return null;
        }
        return "Ignoring " + propertyName + "='" + raw + "': a boolean property accepts only "
                + "'true' or 'false'. Treating it as if it were not set — " + consequence + ".";
    }

    /**
     * The consequence clause for {@code webinsight.report.failonerror}, in one place.
     *
     * <p>Both ingestion paths read that property and both must say the same thing about it; they
     * already duplicate the read itself, and two spellings of the warning is the drift that
     * duplication produces.
     */
    public static final String FAIL_ON_ERROR_CONSEQUENCE =
            "so the default applies and this run WILL exit non-zero if any artifact fails to be "
                    + "written. Pass 'false' — the only accepted spelling of the opt-out — if that "
                    + "is not what you want";
}
