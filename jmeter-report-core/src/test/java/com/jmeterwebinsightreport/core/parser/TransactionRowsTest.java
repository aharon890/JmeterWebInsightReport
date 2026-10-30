package com.jmeterwebinsightreport.core.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** B5: which rows of a results file a Transaction Controller wrote as its parent sample's children. */
class TransactionRowsTest {

    /** The parent's label, or null. */
    private static String parentOf(TransactionRows rows, String thread, String label, String message,
                                   long start, long elapsed, long idle) {
        TransactionRows.Nesting n = rows.parentOf(thread, label, message, start, elapsed, idle);
        return n == null ? null : n.parent;
    }

    private static String tx(int n, int failing) {
        return "Number of samples in transaction : " + n + ", number of failing samples : " + failing;
    }

    @Test
    void aParentSampleIsWrittenFirstAndItsNextNRowsOnItsThreadAreItsChildren() {
        TransactionRows rows = new TransactionRows();
        assertNull(parentOf(rows, "TG 1-1", "Checkout", tx(2, 0), 1000, 300, 50));
        assertEquals("Checkout", parentOf(rows, "TG 1-1", "Cart", "OK", 1000, 100, 0));
        assertEquals("Checkout", parentOf(rows, "TG 1-1", "Pay", "OK", 1150, 200, 0));
        assertNull(parentOf(rows, "TG 1-1", "Home", "OK", 1400, 10, 0), "the transaction had two children");
    }

    @Test
    void aControllerWithoutAParentSampleWritesItsRowAfterItsChildrenAndNestsNothing() {
        TransactionRows rows = new TransactionRows();
        assertNull(parentOf(rows, "TG 1-1", "Cart", "OK", 1000, 100, 0));
        assertNull(parentOf(rows, "TG 1-1", "Pay", "OK", 1100, 200, 0));
        assertNull(parentOf(rows, "TG 1-1", "Report Flow", tx(2, 0), 1000, 300, 0));
        assertNull(parentOf(rows, "TG 1-1", "Home", "OK", 1300, 10, 0),
                "the next row starts at the transaction's end, so it is not its child");
        assertNull(parentOf(rows, "TG 1-1", "Next", "OK", 1310, 10, 0));
    }

    @Test
    void theFirstChildMayStartInTheTransactionsIdleTime() {
        // "Include timers" off: elapsed is the children's time, the pauses are idle time, and the
        // transaction really ends at start + elapsed + idle
        TransactionRows rows = new TransactionRows();
        parentOf(rows, "TG 1-1", "Checkout", tx(1, 0), 1000, 100, 500);
        assertEquals("Checkout", parentOf(rows, "TG 1-1", "Cart", "OK", 1400, 100, 0));
    }

    @Test
    void aNestedTransactionCountsAsOneChildAndBringsItsOwn() {
        TransactionRows rows = new TransactionRows();
        parentOf(rows, "TG 1-1", "Outer", tx(2, 0), 1000, 500, 0);
        assertEquals("Outer", parentOf(rows, "TG 1-1", "A", "OK", 1000, 100, 0));
        assertEquals("Outer", parentOf(rows, "TG 1-1", "Inner", tx(2, 0), 1100, 400, 0));
        assertEquals("Inner", parentOf(rows, "TG 1-1", "X", "OK", 1100, 200, 0));
        assertEquals("Inner", parentOf(rows, "TG 1-1", "Y", "OK", 1300, 200, 0));
        assertNull(parentOf(rows, "TG 1-1", "After", "OK", 1600, 10, 0));
    }

    @Test
    void otherThreadsRowsInBetweenAreNotChildren() {
        TransactionRows rows = new TransactionRows();
        parentOf(rows, "TG 1-1", "Checkout", tx(1, 0), 1000, 300, 0);
        assertNull(parentOf(rows, "TG 1-2", "Cart", "OK", 1000, 100, 0));
        assertEquals("Checkout", parentOf(rows, "TG 1-1", "Cart", "OK", 1000, 100, 0));
        assertNull(parentOf(rows, "TG 1-2", "Pay", "OK", 1100, 100, 0));
    }

    @Test
    void withoutAThreadNothingIsNested() {
        TransactionRows rows = new TransactionRows();
        assertNull(parentOf(rows, "", "Checkout", tx(1, 0), 1000, 300, 0));
        assertNull(parentOf(rows, "", "Cart", "OK", 1000, 100, 0));
        assertNull(parentOf(rows, null, "Cart", "OK", 1000, 100, 0));
    }

    @Test
    void theMarkersCountIsReadAndAnythingElseIsNoTransaction() {
        assertEquals(8, TransactionRows.childrenIn(tx(8, 1)));
        assertEquals(0, TransactionRows.childrenIn(tx(0, 0)), "an empty controller nests nothing");
        assertEquals(0, TransactionRows.childrenIn("OK"));
        assertEquals(0, TransactionRows.childrenIn(null));
        assertEquals(0, TransactionRows.childrenIn("Number of samples in transaction : x"));
    }

    @Test
    void aRequestsOwnSubResultsAreItsAndTakeNoPlaceAmongTheTransactionsChildren() {
        // JMeter writes Login's redirect hops right after it, named after it; N counts the direct children
        TransactionRows rows = new TransactionRows();
        rows.parentOf("TG 1-1", "Checkout", tx(2, 0), 1000, 300, 0);
        TransactionRows.Nesting login = rows.parentOf("TG 1-1", "Login", "OK", 1000, 100, 0);
        assertEquals("Checkout", login.parent);
        assertTrue(login.transaction);
        TransactionRows.Nesting hop = rows.parentOf("TG 1-1", "Login-0", "Found", 1000, 40, 0);
        assertEquals("Login", hop.parent);
        assertFalse(hop.transaction, "a request's sub-result, not a transaction's child");
        assertEquals("Login-0", parentOf(rows, "TG 1-1", "Login-0-0", "OK", 1010, 10, 0), "a sub-result of a sub-result");
        assertEquals("Login", parentOf(rows, "TG 1-1", "Login-1", "OK", 1040, 60, 0));
        assertEquals("Checkout", parentOf(rows, "TG 1-1", "Home", "OK", 1100, 200, 0),
                "the transaction's second child, not left at the top level by the hops");
        assertNull(parentOf(rows, "TG 1-1", "After", "OK", 1400, 10, 0));
    }

    @Test
    void subResultsOutsideATransactionStayRows() {
        TransactionRows rows = new TransactionRows();
        assertNull(parentOf(rows, "TG 1-1", "Home Page", "OK", 1000, 100, 0));
        assertNull(parentOf(rows, "TG 1-1", "Home Page-0", "OK", 1000, 50, 0), "S47: nothing anchors them");
    }

    @Test
    void aSubResultIsTheRequestsLabelADashAndAPosition() {
        assertTrue(TransactionRows.isSubResultOf("Login-0", "Login"));
        assertTrue(TransactionRows.isSubResultOf("Login-12", "Login"));
        assertFalse(TransactionRows.isSubResultOf("Login-", "Login"));
        assertFalse(TransactionRows.isSubResultOf("Login-a", "Login"));
        assertFalse(TransactionRows.isSubResultOf("Logins-0", "Login"));
        assertFalse(TransactionRows.isSubResultOf("Login", "Login"));
    }
}
