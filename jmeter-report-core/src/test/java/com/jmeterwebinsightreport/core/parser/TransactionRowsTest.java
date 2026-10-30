package com.jmeterwebinsightreport.core.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** B5: which rows of a results file a Transaction Controller wrote as its parent sample's children. */
class TransactionRowsTest {

    private static String tx(int n, int failing) {
        return "Number of samples in transaction : " + n + ", number of failing samples : " + failing;
    }

    @Test
    void aParentSampleIsWrittenFirstAndItsNextNRowsOnItsThreadAreItsChildren() {
        TransactionRows rows = new TransactionRows();
        assertNull(rows.parentOf("TG 1-1", "Checkout", tx(2, 0), 1000, 300, 50));
        assertEquals("Checkout", rows.parentOf("TG 1-1", "Cart", "OK", 1000, 100, 0));
        assertEquals("Checkout", rows.parentOf("TG 1-1", "Pay", "OK", 1150, 200, 0));
        assertNull(rows.parentOf("TG 1-1", "Home", "OK", 1400, 10, 0), "the transaction had two children");
    }

    @Test
    void aControllerWithoutAParentSampleWritesItsRowAfterItsChildrenAndNestsNothing() {
        TransactionRows rows = new TransactionRows();
        assertNull(rows.parentOf("TG 1-1", "Cart", "OK", 1000, 100, 0));
        assertNull(rows.parentOf("TG 1-1", "Pay", "OK", 1100, 200, 0));
        assertNull(rows.parentOf("TG 1-1", "Report Flow", tx(2, 0), 1000, 300, 0));
        assertNull(rows.parentOf("TG 1-1", "Home", "OK", 1300, 10, 0),
                "the next row starts at the transaction's end, so it is not its child");
        assertNull(rows.parentOf("TG 1-1", "Next", "OK", 1310, 10, 0));
    }

    @Test
    void theFirstChildMayStartInTheTransactionsIdleTime() {
        // "Include timers" off: elapsed is the children's time, the pauses are idle time, and the
        // transaction really ends at start + elapsed + idle
        TransactionRows rows = new TransactionRows();
        rows.parentOf("TG 1-1", "Checkout", tx(1, 0), 1000, 100, 500);
        assertEquals("Checkout", rows.parentOf("TG 1-1", "Cart", "OK", 1400, 100, 0));
    }

    @Test
    void aNestedTransactionCountsAsOneChildAndBringsItsOwn() {
        TransactionRows rows = new TransactionRows();
        rows.parentOf("TG 1-1", "Outer", tx(2, 0), 1000, 500, 0);
        assertEquals("Outer", rows.parentOf("TG 1-1", "A", "OK", 1000, 100, 0));
        assertEquals("Outer", rows.parentOf("TG 1-1", "Inner", tx(2, 0), 1100, 400, 0));
        assertEquals("Inner", rows.parentOf("TG 1-1", "X", "OK", 1100, 200, 0));
        assertEquals("Inner", rows.parentOf("TG 1-1", "Y", "OK", 1300, 200, 0));
        assertNull(rows.parentOf("TG 1-1", "After", "OK", 1600, 10, 0));
    }

    @Test
    void otherThreadsRowsInBetweenAreNotChildren() {
        TransactionRows rows = new TransactionRows();
        rows.parentOf("TG 1-1", "Checkout", tx(1, 0), 1000, 300, 0);
        assertNull(rows.parentOf("TG 1-2", "Cart", "OK", 1000, 100, 0));
        assertEquals("Checkout", rows.parentOf("TG 1-1", "Cart", "OK", 1000, 100, 0));
        assertNull(rows.parentOf("TG 1-2", "Pay", "OK", 1100, 100, 0));
    }

    @Test
    void withoutAThreadNothingIsNested() {
        TransactionRows rows = new TransactionRows();
        assertNull(rows.parentOf("", "Checkout", tx(1, 0), 1000, 300, 0));
        assertNull(rows.parentOf("", "Cart", "OK", 1000, 100, 0));
        assertNull(rows.parentOf(null, "Cart", "OK", 1000, 100, 0));
    }

    @Test
    void theMarkersCountIsReadAndAnythingElseIsNoTransaction() {
        assertEquals(8, TransactionRows.childrenIn(tx(8, 1)));
        assertEquals(0, TransactionRows.childrenIn(tx(0, 0)), "an empty controller nests nothing");
        assertEquals(0, TransactionRows.childrenIn("OK"));
        assertEquals(0, TransactionRows.childrenIn(null));
        assertEquals(0, TransactionRows.childrenIn("Number of samples in transaction : x"));
    }
}
