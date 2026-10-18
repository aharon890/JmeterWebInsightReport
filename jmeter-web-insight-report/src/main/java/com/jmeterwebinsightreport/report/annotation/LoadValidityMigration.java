package com.jmeterwebinsightreport.report.annotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a legacy {@code loadValidity} block into an ordinary entry in {@code assertions}.
 *
 * <p><b>Why this exists rather than a translator.</b> The block used to expand into a rule stamped
 * {@code Origin.LOAD}, which the report then refused to edit, disable, delete or retune — because
 * the rule was derived from a block the GUI could not write, so an edit would have had nowhere to
 * be saved. The refusal message offered "Duplicate it to get an editable copy", and that made it
 * worse: the duplicate landed in {@code assertions} while the block survived the save, so the file
 * then declared the same check twice and the next run counted it twice.
 *
 * <p>There is no derived rule any more. The block is migrated <em>into</em> {@code assertions} at
 * load, where it is an ordinary rule with no special origin — editable, disableable, deletable,
 * exactly like one written by hand. That is the whole of the fix: the rule stops being special.
 *
 * <p><b>Idempotent, and that is load-bearing.</b> A tester who never opens the report keeps getting
 * the same rule on every run and loses nothing. One who opens it and saves gets a file with the
 * rule in {@code assertions} and no block at all. In between — a file that somehow carries both —
 * the id guard stops a second copy being appended, because two rules sharing an id make the JUnit
 * testcase names and the CI/CD entries ambiguous.
 *
 * <p>The block is <b>not</b> preserved as an unknown key afterwards. It is consumed: leaving it in
 * place would mean the saved file still declares a check that has moved, which is the double
 * declaration this class exists to end.
 */
public final class LoadValidityMigration {

    private LoadValidityMigration() {
    }

    /** The metric a block with no metric names, matching what the block always defaulted to. */
    private static final String DEFAULT_METRIC = "throughput";

    /**
     * Rewrite {@code annotations} in place, consuming any {@code loadValidity} block.
     *
     * @return the id of the rule that was added, or null when there was nothing to migrate
     */
    static String apply(ReportAnnotations annotations) {
        if (annotations == null) {
            return null;
        }
        ReportAnnotations.LoadValidityConfig block = annotations.getLoadValidity();
        if (block == null) {
            return null;
        }
        // Consume it first, so every early return below still leaves the block gone. A block that
        // produces no rule is still a block that must not be written back — otherwise it would sit
        // in the file forever, declaring nothing, and be migrated again on every single run.
        annotations.setLoadValidity(null);

        Double target = block.getTarget();
        if (target == null) {
            return null;   // never declared anything: no target means no floor to compare against
        }
        // A finite target at or below zero declares nothing either. A non-finite one is not a
        // target below zero but no number at all — "-Infinity" and "NaN" are what Jackson binds
        // from those texts, and a bare 1e400 is Infinity by the time it is bound — so it builds the
        // rule, and the rule is refused in a sentence that names the field (L3, floorBand).
        if (isFinite(target) && target <= 0) {
            return null;
        }
        if (block.getWarn() == null && block.getFail() == null) {
            return null;   // the consequence was always declared, never defaulted
        }

        String metric = block.getMetric() == null || block.getMetric().trim().isEmpty()
                ? DEFAULT_METRIC
                : block.getMetric().trim();
        String id = "load:" + metric;

        List<ReportAnnotations.AssertionConfig> assertions =
                annotations.getAssertions() == null
                        ? new ArrayList<ReportAnnotations.AssertionConfig>()
                        : new ArrayList<>(annotations.getAssertions());

        for (ReportAnnotations.AssertionConfig existing : assertions) {
            if (id.equalsIgnoreCase(existing.getId())) {
                // Already migrated and saved, and the block was hand-restored beside it. Keep the
                // one the tester can edit.
                annotations.setAssertions(assertions);
                return null;
            }
        }

        ReportAnnotations.AssertionConfig rule = new ReportAnnotations.AssertionConfig();
        rule.setId(id);
        rule.setDescription("Load validity — asked for " + trim(target) + " " + metric
                + ". A run that never reached its target load cannot validate a threshold"
                + " written for that load.");

        ReportAnnotations.TargetConfig scope = new ReportAnnotations.TargetConfig();
        scope.setScope("overall");
        rule.setTarget(scope);
        rule.setMetric(metric);

        if (block.getWarn() != null) {
            rule.setWarn(floorBand(rule, "warn", target, block.getWarn()));
        }
        if (block.getFail() != null) {
            rule.setFail(floorBand(rule, "fail", target, block.getFail()));
        }

        assertions.add(rule);
        annotations.setAssertions(assertions);
        return id;
    }

    /**
     * {@code lt target × (1 − tolerance)} — worse means smaller for applied load.
     *
     * <p>A tolerance of 1 or more cannot express a check, and this no longer pretends otherwise.
     * The upper end used to be clamped to 1 on the reasoning that "1 means any shortfall counts,
     * which is the strictest thing the band can honestly express" — and the arithmetic says the
     * opposite. {@code 1 − 1} is zero, so the clamp produced {@code lt 0}: a band nothing can
     * breach, which is the most LENIENT outcome available and precisely the "accept any throughput
     * at all" the clamp was written to rule out. It converted one unreachable band into another
     * and read as a fix.
     *
     * <p>So only the lower end is clamped now, and an out-of-range tolerance is left to reach
     * {@link com.jmeterwebinsightreport.core.assertion.Usability}, which refuses any downward band
     * at or below zero and says — in the sentence written for this exact mistake — that a
     * tolerance meant as a percentage is written as a fraction, 0.2 rather than 20. The rule is
     * then NOT_JUDGED / INVALID_RULE and the run exits non-zero, which is the honest answer: the
     * file asked for a check that cannot be built.
     *
     * <p>Keeping the raw value also makes the message diagnostic rather than generic —
     * {@code tolerance: 20} against a target of 1000 reports {@code < -19000}, and the −19 is the
     * mistake itself, visible in the number.
     *
     * <p><b>A target or tolerance that is not a finite number refuses the band</b> (L3), in a
     * sentence naming the field: {@code loadValidity.target must be a finite number, not NaN}. The
     * band is still what the arithmetic gives — NaN, an infinity — so it is written back as Jackson
     * writes one, as text, and the next run refuses that in the band reader's words. Before, NaN and
     * Infinity reached the band reader as the number, which refused them as {@code fail lt must be a
     * number, not the number NaN}: true of a band the file never wrote. A -Infinity tolerance was
     * clamped to 0, a band of the target itself that the run judged; it is not clamped now.
     */
    private static Map<String, Object> floorBand(ReportAnnotations.AssertionConfig rule, String which,
                                                 double target, ReportAnnotations.BandConfig band) {
        double tolerance = band.getTolerance() == null ? 0 : band.getTolerance();
        if (!isFinite(target)) {
            rule.refuseBand(which, "loadValidity.target must be a finite number, not " + target);
        } else if (!isFinite(tolerance)) {
            rule.refuseBand(which, "loadValidity." + which + ".tolerance must be a finite number, not "
                    + tolerance);
        }
        Map<String, Object> out = new LinkedHashMap<>(1);
        out.put("lt", target * (1 - (isFinite(tolerance) ? Math.max(0, tolerance) : tolerance)));
        return out;
    }

    private static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    /** An integral value as a long, else Double.toString — and an infinity as itself, never as
        {@code (long) Infinity}, which is 9223372036854775807. */
    private static String trim(double d) {
        return isFinite(d) && d == Math.rint(d)
                ? String.valueOf((long) d)
                : String.valueOf(d);
    }

    /** For the finding that tells the tester the file has moved on. */
    public static String describe(String migratedId) {
        return "the legacy loadValidity block was migrated into assertions[] as '" + migratedId
                + "'. It is now an ordinary rule you can edit, disable or delete from the Asserts"
                + " tab. Save the file from that tab to make the change permanent — until you do,"
                + " it is re-created on every run.";
    }
}
