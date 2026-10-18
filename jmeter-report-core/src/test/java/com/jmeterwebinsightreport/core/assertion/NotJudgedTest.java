package com.jmeterwebinsightreport.core.assertion;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reason vocabulary is one enum shared by the client gate and every server gate, and three
 * of its decisions are read by consumers that cannot see each other — the JUnit writer, the
 * {@code failonerror} exit, the console line, the parity guards. These tests pin the decisions
 * so a reason added later has to make them explicitly rather than inherit a default.
 */
class NotJudgedTest {

    @Test
    void everyReasonAnswersEveryQuestion() {
        // The switches have no default arm on purpose; this is the runtime half of that
        // guarantee, for the day someone adds one.
        for (NotJudged r : NotJudged.values()) {
            assertFalse(r.countKey().isEmpty(), r + " has no count key");
            assertFalse(r.label().isEmpty(), r + " has no label");
            assertFalse(r.phrase().isEmpty(), r + " has no phrase");
            assertEquals(r.name(), r.wireName(), "the wire name is the constant name");
            // Touch both decisions so an unlisted constant throws here, not in a serializer.
            r.isConfigError();
            r.serverOnly();
        }
    }

    @Test
    void countKeysAreDistinctSoTheSumStaysClosed() {
        // Two reasons sharing a key would publish one count over the other — the exact defect
        // the missing default arm exists to refuse.
        Set<String> keys = new HashSet<>();
        for (NotJudged r : NotJudged.values()) {
            assertTrue(keys.add(r.countKey()), "duplicate count key " + r.countKey());
        }
        assertEquals(NotJudged.values().length, keys.size());
    }

    @Test
    void theOriginalFourKeepTheirWirePosition() {
        // Every pipeline that read gate.notJudged before server metrics existed reads the same
        // keys in the same place. New reasons append; they never interleave.
        NotJudged[] v = NotJudged.values();
        assertEquals(NotJudged.DISABLED, v[0]);
        assertEquals(NotJudged.NO_MATCH, v[1]);
        assertEquals(NotJudged.NO_VALUE, v[2]);
        assertEquals(NotJudged.INVALID_RULE, v[3]);
    }

    @Test
    void displayOrderNamesEveryReasonOnceWithConfigErrorsFirst() {
        // A reason missing here would be counted everywhere and named nowhere — the console
        // and the log would silently drop it. And the client subset must read in the order the
        // console always printed, so the line is stable for every existing run.
        java.util.List<NotJudged> order = NotJudged.displayOrder();
        assertEquals(NotJudged.values().length, order.size());
        assertEquals(NotJudged.values().length, new HashSet<>(order).size(), "no duplicates");

        boolean seenNonError = false;
        for (NotJudged r : order) {
            if (!r.isConfigError()) {
                seenNonError = true;
            } else {
                assertFalse(seenNonError, "config errors must all come first: " + order);
            }
        }

        java.util.List<NotJudged> clientOrder = new java.util.ArrayList<>();
        for (NotJudged r : order) {
            if (!r.serverOnly()) {
                clientOrder.add(r);
            }
        }
        assertEquals(java.util.Arrays.asList(NotJudged.INVALID_RULE, NotJudged.NO_MATCH,
                        NotJudged.NO_VALUE, NotJudged.DISABLED), clientOrder,
                "the client reasons read as the console has always printed them");
    }

    @Test
    void configErrorsAreExactlyTheChecksThatNeverRan() {
        // The line is drawn at whose fault it is. INVALID_RULE is the file; SOURCE_FAILED and
        // LOW_COVERAGE are a declared collection that did not happen. NO_MATCH is legitimate on
        // a subset plan or environment (plans/asserts-config-errors-fail.md §1, reaffirmed for
        // the server side); NO_VALUE is the run's data; LOAD_NOT_ACHIEVED is already a FAIL on
        // the load rule it depends on and must not exit 1 a second time under a different flag.
        assertEquals(EnumSet.of(NotJudged.INVALID_RULE, NotJudged.SOURCE_FAILED, NotJudged.LOW_COVERAGE),
                filter(NotJudged::isConfigError));
    }

    @Test
    void serverOnlyReasonsAreExactlyTheThreeAddedForServerMetrics() {
        // The client parity vectors are refused if they claim one of these, and demanded to
        // cover everything else. Moving a reason across this line moves a test obligation.
        assertEquals(EnumSet.of(NotJudged.SOURCE_FAILED, NotJudged.LOW_COVERAGE, NotJudged.LOAD_NOT_ACHIEVED),
                filter(NotJudged::serverOnly));
    }

    @Test
    void aConfigErrorThatIsNotServerOnlyIsInvalidRuleAlone() {
        // INVALID_RULE is the one config error a client rule can carry, which is why the
        // client exit path used to read invalidRule() directly and now reads configErrors():
        // the two are equal on a client gate, and only there.
        EnumSet<NotJudged> clientConfigErrors = filter(r -> r.isConfigError() && !r.serverOnly());
        assertEquals(EnumSet.of(NotJudged.INVALID_RULE), clientConfigErrors);
    }

    private static EnumSet<NotJudged> filter(java.util.function.Predicate<NotJudged> p) {
        EnumSet<NotJudged> out = EnumSet.noneOf(NotJudged.class);
        for (NotJudged r : NotJudged.values()) {
            if (p.test(r)) {
                out.add(r);
            }
        }
        return out;
    }
}
