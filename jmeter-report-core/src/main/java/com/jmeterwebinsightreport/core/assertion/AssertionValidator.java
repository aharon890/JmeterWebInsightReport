package com.jmeterwebinsightreport.core.assertion;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * Checks rules at load time and says what is wrong with them, by name and with the fix.
 *
 * <p>Loud but non-fatal. A bad rule never stops the report being generated, because the report
 * is the artifact you need in order to debug the configuration — refusing to produce it turns a
 * typo into a blackout. Findings surface in three places: the JMeter log, the CI/CD JSON, and a
 * notice in the report itself.
 *
 * <p>Severity is not the same thing as validity. An unreachable warn band produces correct
 * verdicts, so it is a warning here — but the report's editor still refuses to author one,
 * because there is no reason to create a rule that cannot fire.
 */
public final class AssertionValidator {

    public enum Severity {
        /**
         * The rule cannot decide anything. It is {@code NOT_JUDGED / INVALID_RULE} — it gates in
         * neither direction, and the run exits non-zero because the file asked for a check that
         * never ran.
         *
         * <p>This used to say "renders as INVALID and counts as a failure", from when INVALID was
         * a status that outranked FAIL — a verdict about performance produced by a rule that
         * measured none. What ERROR means now is that {@link Usability#refusal} returns it.
         */
        ERROR,
        /** The rule runs and its verdicts are correct, but it is very likely not what was meant. */
        WARNING
    }

    public static final class Finding {
        private final Severity severity;
        private final String ruleId;
        private final String message;

        Finding(Severity severity, String ruleId, String message) {
            this.severity = severity;
            this.ruleId = ruleId;
            this.message = message;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Finding)) return false;
            Finding f = (Finding) other;
            return severity == f.severity && ruleId.equals(f.ruleId) && message.equals(f.message);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(severity, ruleId, message);
        }

        public Severity severity() { return severity; }
        public String ruleId() { return ruleId; }
        public String message() { return message; }

        /** The line that goes in the log and the report notice. */
        public String describe() {
            return "assertion '" + ruleId + "': " + message;
        }

        @Override
        public String toString() {
            return severity + " " + describe();
        }
    }

    /**
     * Build a finding from outside this class.
     *
     * <p>The JSON mapper produces findings this class cannot: a band with two operators, a
     * target with two forms, a threshold written as a boolean. Those are shape problems, caught
     * before a rule object exists — but they belong in the same list, so the log, the CI/CD
     * JSON and the report notice have one place to read.
     */
    public static Finding finding(Severity severity, String ruleId, String message) {
        return new Finding(severity, ruleId, message);
    }

    /**
     * Validate the rule set against the run it will judge.
     *
     * @param data the run, so an unmatched target can name the samplers that actually exist;
     *        may be null when checking a file with no run in hand
     */
    public List<Finding> validate(List<AssertionRule> rules, ReportData data) {
        List<Finding> findings = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return findings;
        }

        Set<String> seenIds = new HashSet<>();
        for (AssertionRule rule : rules) {
            if (!seenIds.add(rule.id())) {
                findings.add(new Finding(Severity.ERROR, rule.id(),
                        "duplicate id — ids name JUnit testcases and CI/CD entries, so two rules "
                                + "sharing one make the outputs ambiguous"));
            }
            // Everything that is wrong with the rule ON ITS OWN comes from one place, which is
            // the same place the evaluator asks whether the rule may be judged. This class used
            // to answer that question a second time, in its own words, and the two answers drifted
            // — most damagingly for a band that could never fire, which was reported here and
            // judged anyway.
            for (Usability u : Usability.check(rule)) {
                if (!u.loaderReported()) {
                    findings.add(u.asFinding(rule.id()));
                }
            }
            checkTargetMatches(rule, data, findings);
        }
        return findings;
    }

    /**
     * The highest-value check in the set: a target that selects nothing.
     *
     * <p>The message lists the sampler names the run actually contained, because the fix is
     * almost always to copy one of them. A bare "matched nothing" sends the reader back to the
     * JTL to work out what they should have typed.
     */
    private void checkTargetMatches(AssertionRule rule, ReportData data, List<Finding> findings) {
        if (data == null || rule.target().kind() == AssertionTarget.Kind.OVERALL || !rule.enabled()) {
            return;
        }
        if (rule.target().kind() == AssertionTarget.Kind.INVALID) {
            // The loader already said what is wrong with this target. Adding "matched nothing"
            // on top would be two findings for one mistake, and the second is a consequence of
            // the first rather than a separate thing to fix.
            return;
        }
        List<String> matched;
        try {
            matched = rule.target().resolve(data);
        } catch (PatternSyntaxException e) {
            return; // already reported by checkPattern
        }
        // WARNING, not ERROR, and it is no longer the rule's choice. ERROR means the rule cannot
        // decide anything, which is Usability's answer and carries a non-zero exit; an unmatched
        // rule is NOT_JUDGED / NO_MATCH, counts as nothing and deliberately does NOT fail the
        // run — a plan running a subset is legitimate. An ERROR here would be the validator
        // asserting a verdict the evaluator does not produce, and failing builds over a renamed
        // sampler, which is the behaviour `onMissing` was retired for.
        //
        // The finding is what carries this now. The engine says "I judged nothing"; the finding
        // says "and here is the sampler name that no longer exists". Deciding whether that
        // should stop a build is the tester's call, not the report's.
        Severity severity = Severity.WARNING;

        if (!matched.isEmpty()) {
            // Matched rows that all recorded nothing. The evaluator drops them from the judgeable
            // set for a good reason — a row that never ran has latency stored as zero, so every
            // "under X" threshold would pass — but that lands the rule in the same unmatched
            // bucket as a typo, and the two need different fixes. A name that matches nothing is
            // misspelled; a name that matches an empty row is a thread group that never started,
            // a disabled controller, or a run that stopped early.
            List<String> empty = emptyMatches(matched, data);
            if (empty.size() == matched.size()) {
                findings.add(new Finding(severity, rule.id(),
                        "target " + rule.target().describe() + " matched "
                                + String.join(", ", empty)
                                + ", but " + (empty.size() == 1 ? "it recorded" : "none of them recorded")
                                + " no samples in this run — there was nothing to judge. A row "
                                + "with no samples is not fast, it is undefined, so judging it "
                                + "would report a pass nothing earned"));
            }
            return;
        }

        findings.add(new Finding(severity, rule.id(),
                "target " + rule.target().describe() + " matched nothing in this run — the gate "
                        + "never executed" + suggestion(rule, data)
                        + ". This run contained: " + nameList(data)));
    }

    /** The matched samplers that recorded no samples, in match order. */
    private static List<String> emptyMatches(List<String> matched, ReportData data) {
        List<String> out = new ArrayList<>();
        for (SamplerStatistics s : safeStats(data)) {
            if (matched.contains(s.getSamplerName()) && s.getSampleCount() <= 0) {
                out.add(s.getSamplerName());
            }
        }
        return out;
    }

    private String suggestion(AssertionRule rule, ReportData data) {
        if (rule.target().kind() != AssertionTarget.Kind.SAMPLER || rule.target().names().isEmpty()) {
            return "";
        }
        String wanted = rule.target().names().get(0);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (SamplerStatistics s : safeStats(data)) {
            int d = distance(wanted, s.getSamplerName());
            if (d < bestDistance) {
                bestDistance = d;
                best = s.getSamplerName();
            }
        }
        // Only suggest a genuinely near miss. "Did you mean GET /health?" for a totally
        // different name is noise that undermines the suggestions that are right.
        if (best != null && bestDistance <= Math.max(3, wanted.length() / 3)) {
            return ". Did you mean \"" + best + "\"?";
        }
        return "";
    }

    private static String nameList(ReportData data) {
        List<String> names = new ArrayList<>();
        for (SamplerStatistics s : safeStats(data)) {
            names.add(s.getSamplerName());
        }
        if (names.isEmpty()) {
            return "(no samplers at all)";
        }
        // Long plans would otherwise put hundreds of names into one log line.
        if (names.size() > 12) {
            List<String> head = new ArrayList<>(names.subList(0, 12));
            return String.join(", ", head) + " … and " + (names.size() - 12) + " more";
        }
        return String.join(", ", names);
    }

    private static List<SamplerStatistics> safeStats(ReportData data) {
        return data == null || data.getSamplerStatistics() == null
                ? Collections.<SamplerStatistics>emptyList()
                : data.getSamplerStatistics();
    }

    private static int distance(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) prev[j] = j;
        for (int i = 1; i <= x.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[y.length()];
    }
}
