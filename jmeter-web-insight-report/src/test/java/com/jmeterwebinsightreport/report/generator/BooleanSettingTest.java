package com.jmeterwebinsightreport.report.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an unrecognised boolean property <em>resolves to</em> is the whole point, and reporting it
 * is only half the fix.
 *
 * <p>{@code -Jwebinsight.report.junit=yes} used to match neither the {@code "true"} branch nor
 * the null/empty one, so it did not enable JUnit XML and did not fall through to the checkbox
 * that was ticked. Treating it as {@code false} would still void the ticked box — just noisily —
 * so it resolves to UNSET and the remaining configuration decides, which is what "this value told
 * us nothing" ought to mean.
 */
class BooleanSettingTest {

    @Test
    void trueAndFalseAreAcceptedInAnyCaseAndWithSurroundingSpace() {
        assertEquals(BooleanSetting.Value.TRUE, BooleanSetting.parse("true"));
        assertEquals(BooleanSetting.Value.TRUE, BooleanSetting.parse("TRUE"));
        assertEquals(BooleanSetting.Value.TRUE, BooleanSetting.parse("True"));
        assertEquals(BooleanSetting.Value.TRUE, BooleanSetting.parse("  true  "));
        assertEquals(BooleanSetting.Value.FALSE, BooleanSetting.parse("false"));
        assertEquals(BooleanSetting.Value.FALSE, BooleanSetting.parse("FALSE"));
        assertEquals(BooleanSetting.Value.FALSE, BooleanSetting.parse(" False "));
    }

    @Test
    void absentAndBlankAreUnsetAndSaySoQuietly() {
        assertEquals(BooleanSetting.Value.UNSET, BooleanSetting.parse(null));
        assertEquals(BooleanSetting.Value.UNSET, BooleanSetting.parse(""));
        assertEquals(BooleanSetting.Value.UNSET, BooleanSetting.parse("   "));

        assertNull(BooleanSetting.warningFor("p", null), "an absent property is not a mistake");
        assertNull(BooleanSetting.warningFor("p", ""));
        assertNull(BooleanSetting.warningFor("p", "   "));
    }

    /**
     * Only {@code true} and {@code false}. Not {@code yes}, not {@code 1}, not {@code on}.
     *
     * <p>A wider vocabulary reads as friendlier and is not: JMeter's own
     * {@code getPropertyAsBoolean} is {@code Boolean.parseBoolean}, so {@code yes} is false
     * there, and a plugin that accepted it would make the same value in two fields of one test
     * plan do two different things.
     */
    @Test
    void everythingElseIsUnsetAndIsReported() {
        for (String raw : new String[]{"yes", "no", "1", "0", "on", "off", "y", "n", "tru",
                "TRUE!", "enabled"}) {
            assertEquals(BooleanSetting.Value.UNSET, BooleanSetting.parse(raw), raw);
            String warning = BooleanSetting.warningFor("webinsight.report.junit", raw);
            assertNotNull(warning, "an unrecognised value must never be silent: " + raw);
            assertTrue(warning.contains(raw), "the warning must quote what was configured: " + warning);
            assertTrue(warning.contains("webinsight.report.junit"),
                    "...and name the property: " + warning);
        }
    }

    @Test
    void aRecognisedValueIsNotWarnedAbout() {
        assertNull(BooleanSetting.warningFor("p", "true"));
        assertNull(BooleanSetting.warningFor("p", "FALSE"));
    }

    /**
     * The warning names the outcome, because the mechanism is the same for every flag and the
     * outcome is not.
     *
     * <p>{@code UNSET} means "the next configuration source decides". For {@code junit} and
     * {@code externaldata} that source resolves to off, which is the harmless direction and
     * roughly what somebody typing {@code yes} was reaching for. For {@code failonerror} it
     * resolves to the default, which is <b>true</b> — so {@code failonerror=0} is read as saying
     * nothing and the run then fails, which is the exact opposite of what was asked for. A
     * warning that stops at "treating it as if it were not set — any GUI or JMX value still
     * applies" is true of the mechanism and wrong about the result, and on the {@code -g -o} path
     * there is no GUI or JMX value for it to be talking about at all.
     *
     * <p>Widening the vocabulary would be the other way to close this and is not on offer:
     * {@code 0} was never an opt-out, only a value indistinguishable from absent back when the
     * default was false, and accepting it here would make one string mean two different things
     * between this plugin and JMeter's own {@code Boolean.parseBoolean}.
     */
    @Test
    void theWarningStatesWhatHappensInstead() {
        String warning = BooleanSetting.warningFor("webinsight.report.failonerror", "0",
                BooleanSetting.FAIL_ON_ERROR_CONSEQUENCE);

        assertNotNull(warning);
        assertTrue(warning.contains("exit non-zero"),
                "an operator who typed 0 to opt OUT has to be told the run will fail: " + warning);
        assertTrue(warning.contains("'false'"),
                "...and be given the spelling that works: " + warning);
        assertFalse(warning.contains("GUI"),
                "the GUI-fallback clause is wrong for a property with no GUI field: " + warning);
    }

    @Test
    void aRecognisedValueIsNotWarnedAboutWhateverTheConsequenceWouldHaveBeen() {
        assertNull(BooleanSetting.warningFor("p", "false",
                BooleanSetting.FAIL_ON_ERROR_CONSEQUENCE));
        assertNull(BooleanSetting.warningFor("p", "", BooleanSetting.FAIL_ON_ERROR_CONSEQUENCE));
        assertNull(BooleanSetting.warningFor("p", null, BooleanSetting.FAIL_ON_ERROR_CONSEQUENCE));
    }

    @Test
    void unsetDefersToTheCaller() {
        // The behaviour the whole class exists for: an unrecognised -J value leaves a ticked GUI
        // checkbox in force rather than quietly cancelling it.
        assertTrue(BooleanSetting.parse("yes").orElse(true));
        assertFalse(BooleanSetting.parse("yes").orElse(false));

        // ...and a recognised one overrides whatever the caller would have used.
        assertFalse(BooleanSetting.parse("false").orElse(true));
        assertTrue(BooleanSetting.parse("true").orElse(false));
    }
}
