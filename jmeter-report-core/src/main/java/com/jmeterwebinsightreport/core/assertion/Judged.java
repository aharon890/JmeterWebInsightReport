package com.jmeterwebinsightreport.core.assertion;

/**
 * Something a gate can judge: a client rule, or a server-metric KPI.
 *
 * <p>The seam between the two is deliberately this thin. {@link GateSummary} counts results by
 * status and reason and needs nothing about what was judged beyond a name for the row; every
 * consumer that needs more — a target, a metric, a source, a query — asks for the concrete type
 * through {@link GateSummary#ruleResults()} or the server-side equivalent, and cannot be handed
 * the wrong kind by accident.
 *
 * <p>It exists because the alternative was forcing a server KPI into {@link AssertionRule},
 * whose target, metric vocabulary and {@code switch} arms all carry a {@code default} that
 * would have routed a new kind of subject into {@code NO_MATCH} in silence.
 */
public interface Judged {

    /** The id every output names this check by — JUnit testcase, CI/CD entry, report row. */
    String id();

    /** Never null; empty when the author wrote none. */
    String description();

    /** {@code enabled: false} parks the check: NOT_JUDGED / DISABLED, and excluded from evaluated. */
    boolean enabled();
}
