package com.jmeterwebinsightreport.core.parser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
 * <p>A request inside a transaction may have sub-results of its own (redirect hops, embedded
 * resources), which JMeter writes right after it, depth first, named after it — "Login-0",
 * "Login-1", "Login-0-0" ({@code SampleResult.storeSubResult} renames them so) — and which are not
 * among the transaction's N: N counts its direct children ({@code TransactionSampler}). Such rows are
 * nested under their request, as the listener nests them, and take no place among the N; counting
 * them used up the transaction's places and left its last requests at the top level. JMeter does
 * not rename them in functional mode or with {@code subresults.disable_renaming=true}, and then they
 * cannot be told from the next request.
 *
 * <p>Sub-results outside a transaction (S47, "Home Page-0") are left as rows: no JMeter tool that
 * reads a file nests them, and nothing here anchors them.
 *
 * <p>A transaction the test end cut off is written without the message: when a thread stops inside
 * one, {@code JMeterThread} ends it once more ({@code doEndTransactionSampler}) without
 * {@code setTransactionDone}, so its row has no "Number of samples" and its requests still follow it.
 * A listener in the plan received that transaction with its requests inside, as it receives any
 * other (E02: 20 such rows with 40 requests; a listener counts 7,224, every row 7,264). So an unmarked
 * row whose name was a parent-sample transaction earlier in the file nests the rows of its thread
 * that start inside its span. Its own count of children is unknown, so the span alone ends it, and
 * the row itself stays a request, as the listener counts it: the message is what says "transaction"
 * on both paths. The names are the whole file's ({@link #TransactionRows(Set)}): a "Stop Thread" on a
 * sampler error cuts a transaction off before any of its name has completed, and its row is then the
 * file's first of that name. A name that never completed anywhere in the file cannot be told from a
 * request.
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
    /** Per thread: the last request read inside a transaction and its sub-results so far, outermost first. */
    private final Map<String, Deque<String>> requests = new HashMap<>();
    /** The names a transaction row carries anywhere in the file: an unmarked row of one was cut off. */
    private final Set<String> parentSampleTransactions = new HashSet<>();

    /** Reading a file with no knowledge of its transaction names beyond the rows already read. */
    TransactionRows() {
        this(java.util.Collections.emptySet());
    }

    /** @param transactionNames every label a transaction row carries anywhere in the file */
    TransactionRows(Set<String> transactionNames) {
        parentSampleTransactions.addAll(transactionNames);
    }
    /** A cut-off transaction's children: as many as start inside its span. */
    private static final int UNTIL_ITS_SPAN_ENDS = -1;

    /** Whose row a row is: a transaction's ({@code transaction}), or a request's sub-result. */
    static final class Nesting {
        final String parent;
        final boolean transaction;

        Nesting(String parent, boolean transaction) {
            this.parent = parent;
            this.transaction = transaction;
        }
    }

    /**
     * Read one row and say whose child it is.
     *
     * @param thread  the row's threadName; null or empty when the file has no such column, and then
     *                nothing can be nested
     * @param start   when the sample started
     * @param elapsed its elapsed time
     * @param idle    its IdleTime, 0 when the file has no such column
     * @return whose child the row is — a parent-sample transaction's, or a request's inside one — or null
     */
    Nesting parentOf(String thread, String label, String message, long start, long elapsed, long idle) {
        if (thread == null || thread.isEmpty()) {
            return null;
        }
        Deque<String> chain = requests.get(thread);
        if (chain != null) {
            while (!chain.isEmpty() && !isSubResultOf(label, chain.peekLast())) {
                chain.removeLast();
            }
            if (!chain.isEmpty()) {
                String request = chain.peekLast();
                chain.addLast(label);
                return new Nesting(request, false);
            }
            requests.remove(thread);
        }

        Pending last = pending.remove(thread);
        if (last != null && start >= last.start && start < last.realEnd) {
            open.computeIfAbsent(thread, k -> new ArrayDeque<>())
                    .addLast(new Open(last.label, last.children, last.realEnd));
            if (last.children != UNTIL_ITS_SPAN_ENDS) {
                parentSampleTransactions.add(last.label);
            }
        }

        String parent = null;
        Deque<Open> stack = open.get(thread);
        while (stack != null && !stack.isEmpty() && stack.peekLast().left == UNTIL_ITS_SPAN_ENDS
                && start >= stack.peekLast().realEnd) {
            stack.removeLast();
        }
        if (stack != null && !stack.isEmpty()) {
            Open innermost = stack.peekLast();
            parent = innermost.label;
            if (innermost.left != UNTIL_ITS_SPAN_ENDS && --innermost.left == 0) {
                stack.removeLast();
            }
        }

        int children = childrenIn(message);
        long realEnd = start + Math.max(0, elapsed) + Math.max(0, idle);
        if (children > 0) {
            pending.put(thread, new Pending(label, start, realEnd, children));
        } else if (parentSampleTransactions.contains(label)) {
            // Cut off at the test end, or a request named like a transaction: only a transaction's
            // children start inside its span, so the next row of the thread tells them apart
            pending.put(thread, new Pending(label, start, realEnd, UNTIL_ITS_SPAN_ENDS));
            if (parent != null) {
                Deque<String> request = new ArrayDeque<>();
                request.addLast(label);
                requests.put(thread, request);
            }
        } else if (parent != null) {
            Deque<String> request = new ArrayDeque<>();
            request.addLast(label);
            requests.put(thread, request);
        }
        return parent == null ? null : new Nesting(parent, true);
    }

    /** JMeter's name for a sub-result of {@code request}: its label, a dash and a position. */
    static boolean isSubResultOf(String label, String request) {
        if (label == null || request == null || label.length() <= request.length() + 1
                || !label.startsWith(request) || label.charAt(request.length()) != '-') {
            return false;
        }
        for (int i = request.length() + 1; i < label.length(); i++) {
            if (!Character.isDigit(label.charAt(i))) {
                return false;
            }
        }
        return true;
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
        /** Children still to come, or {@link #UNTIL_ITS_SPAN_ENDS}. */
        int left;
        final long realEnd;

        Open(String label, int left, long realEnd) {
            this.label = label;
            this.left = left;
            this.realEnd = realEnd;
        }
    }
}
