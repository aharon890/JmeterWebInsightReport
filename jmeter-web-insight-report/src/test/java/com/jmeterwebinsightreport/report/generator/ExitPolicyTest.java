package com.jmeterwebinsightreport.report.generator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two exit flags, and the asymmetry between them.
 *
 * <p>These were read separately on the two ingestion paths, and one of them was not read on the
 * exporter path at all — so the same annotations file and the same FAIL verdict exited 1 under
 * {@code -n -t} and 0 under {@code -g -o}. The scenario covering the flag only runs the listener
 * path, so nothing noticed. Both paths now call this class, which is the point of it existing.
 */
class ExitPolicyTest {

    private final List<String> warnings = new ArrayList<>();

    private void warn(String w) {
        warnings.add(w);
    }

    // ---- defaults, which point in opposite directions ------------------------

    @Test
    void aConfigErrorIsFatalUnlessTurnedOff() {
        assertTrue(ExitPolicy.failOnError(null, this::warn));
        assertTrue(ExitPolicy.failOnError("", this::warn));
        assertTrue(ExitPolicy.failOnError("true", this::warn));
        assertFalse(ExitPolicy.failOnError("false", this::warn));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aVerdictIsFatalOnlyWhenAsked() {
        // Off by default and it stays off: deciding what a FAIL means is the tester's job.
        assertFalse(ExitPolicy.failOnAssert(null, this::warn));
        assertFalse(ExitPolicy.failOnAssert("", this::warn));
        assertTrue(ExitPolicy.failOnAssert("true", this::warn));
        assertFalse(ExitPolicy.failOnAssert("false", this::warn));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    // ---- the unrecognised value, which is where the danger is ----------------

    @Test
    void anUnrecognisedValueDisarmsFailOnAssertAndSaysSo() {
        // `failonassert=1` is the shape that matters: a plausible-looking value that means
        // nothing, resolving to the UN-GATING direction. It was the only boolean property in the
        // plugin read without a warning, so a pipeline that armed the flag this way got a green
        // build from a red gate in silence.
        assertFalse(ExitPolicy.failOnAssert("1", this::warn));

        assertEquals(1, warnings.size(), warnings.toString());
        String w = warnings.get(0);
        assertTrue(w.contains("webinsight.report.failonassert"), w);
        assertTrue(w.contains("'1'"), w);
        assertTrue(w.contains("will NOT fail this run"),
                "the warning has to say which way the default lands: " + w);
    }

    @Test
    void anUnrecognisedValueLeavesFailOnErrorFailingAndSaysSo() {
        // The mirror image, and why the consequence clause is per-flag rather than shared: "as
        // if it were not set" lands on the FAILING direction for this one.
        assertTrue(ExitPolicy.failOnError("yes", this::warn));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("webinsight.report.failonerror"), warnings.get(0));
        assertNotEquals(ExitPolicy.failOnAssert("yes", w -> { }),
                ExitPolicy.failOnError("yes", w -> { }),
                "the two defaults must not quietly converge");
    }

    @Test
    void caseAndSurroundingSpaceDoNotChangeTheAnswer() {
        assertTrue(ExitPolicy.failOnAssert(" TRUE ", this::warn));
        assertFalse(ExitPolicy.failOnError("  False  ", this::warn));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aNullWarnSinkIsNotAnError() {
        // The exporter and the listener report warnings differently — stderr on one, a logger on
        // the other — and that is the only thing about these two flags that legitimately differs.
        assertFalse(ExitPolicy.failOnAssert("nonsense", null));
        assertTrue(ExitPolicy.failOnError("nonsense", null));
    }

    @Test
    void theFlagNamesAreTheOnesTheMessagesUse() {
        // NonZeroExit prints "exiting 1 because <flag>=true", so a typo here would tell an
        // operator to look for a property that does not exist.
        assertEquals("webinsight.report.failonerror", NonZeroExit.FAIL_ON_ERROR);
        assertEquals("webinsight.report.failonassert", NonZeroExit.FAIL_ON_ASSERT);
    }
}
