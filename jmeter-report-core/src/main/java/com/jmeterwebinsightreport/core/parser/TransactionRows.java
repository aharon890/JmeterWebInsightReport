package com.jmeterwebinsightreport.core.parser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Finds, in a results file, the rows a Transaction Controller wrote as its parent sample's
 * sub-results, so {@code -g} nests them as the listener does (B5).
 *
 * <p>A listener in the test plan receives a transaction's parent sample with its children inside it,
 * and the report made during the run nests them: the transaction counts once, its requests are its
 * breakdown. The results file has no hierarchy, and the rebuilt report counted every row — the
 * transaction and its requests both (P11: 450 samples against the run report's 50, campaign F1). Two
 * reports of one run disagreed on every total.
 *
 * <p>The file still says which rows were children, in two facts JMeter writes:
 * <ul>
 *   <li>a transaction row's response message is
 *       {@code Number of samples in transaction : N, number of failing samples : M}
 *       ({@code TransactionSampler}; JMeter's HTML report finds controller rows by the same text,
 *       {@code Sample.isController});</li>
 *   <li>a controller that generates a parent sample has its row written first and its N direct
 *       children right after it, depth first, on its thread ({@code CSVSaveService.saveSubResults});
 *       a controller that does not has its row written after its children, which reached every
 *       listener on their own.</li>
 * </ul>
 * So a transaction row whose thread's next row starts inside the transaction (from its start to its
 * real end, {@code start + elapsed + IdleTime}) generated a parent sample, and its thread's next N
 * rows are its children; a nested transaction row among them brings its own N. Otherwise the next
 * row starts at or after that end and nothing is nested, as nothing was for the listener. This is the
 * rule of the campaign's Aggregate Report referee ({@code --population top-level}), which reproduces
 * the run reports' totals.
 *
 * <p>Sub-results with no marker (embedded resources, redirects: "Home Page-0") cannot be told apart
 * from ordinary rows in a file, by this or by any JMeter tool that reads one; they stay rows.
 *
 * <p>One instance per file, fed every row in file order. Not thread-safe; the parser is single
 * threaded per file.
 */
final class TransactionRows {

    static final String MARKER = "Number of samples in transaction : ";

    /** Per thread: the last transaction row, until its thread's next row says which kind it was. */
    private final Map<String, Pending> pending = new HashMap<>();
    /** Per thread: the parent-sample transactions whose children are still being read, innermost last. */
    private final Map<String, Deque<Open>> open = new HashMap<>();

    /**
     * Read one row and say whose child it is.
     *
     * @param thread  the row's threadName; null or empty when the file has no such column, and then
     *                nothing can be nested
     * @param start   when the sample started
     * @param elapsed its elapsed time
     * @param idle    its IdleTime, 0 when the file has no such column
     * @return the label of the parent-sample transaction this row is a child of, or null
     */
    String parentOf(String thread, String label, String message, long start, long elapsed, long idle) {
        if (thread == null || thread.isEmpty()) {
            return null;
        }
        Pending last = pending.remove(thread);
        if (last != null && start >= last.start && start < last.realEnd) {
            open.computeIfAbsent(thread, k -> new ArrayDeque<>()).addLast(new Open(last.label, last.children));
        }

        String parent = null;
        Deque<Open> stack = open.get(thread);
        if (stack != null && !stack.isEmpty()) {
            Open innermost = stack.peekLast();
            parent = innermost.label;
            if (--innermost.left == 0) {
                stack.removeLast();
            }
        }

        int children = childrenIn(message);
        if (children > 0) {
            pending.put(thread, new Pending(label, start, start + Math.max(0, elapsed) + Math.max(0, idle), children));
        }
        return parent;
    }

    /** The N of "Number of samples in transaction : N, ...", or 0 for a row that is no transaction. */
    static int childrenIn(String message) {
        if (message == null || !message.startsWith(MARKER)) {
            return 0;
        }
        int from = MARKER.length();
        int to = from;
        while (to < message.length() && Character.isDigit(message.charAt(to))) {
            to++;
        }
        if (to == from || to - from > 9) {
            return 0;
        }
        return Integer.parseInt(message.substring(from, to));
    }

    private static final class Pending {
        final String label;
        final long start;
        final long realEnd;
        final int children;

        Pending(String label, long start, long realEnd, int children) {
            this.label = label;
            this.start = start;
            this.realEnd = realEnd;
            this.children = children;
        }
    }

    private static final class Open {
        final String label;
        int left;

        Open(String label, int left) {
            this.label = label;
            this.left = left;
        }
    }
}
