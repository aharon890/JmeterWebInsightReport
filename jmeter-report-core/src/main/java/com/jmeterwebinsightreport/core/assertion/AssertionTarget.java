package com.jmeterwebinsightreport.core.assertion;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a rule judges: one sampler, several, everything matching a pattern, or the run itself.
 *
 * <h2>Reach</h2>
 *
 * <p>Naming a sampler reaches it, whatever it is — a transaction-controller child, a hidden
 * utility sampler, a row with no requests. Naming is an unambiguous request to check that
 * thing, and refusing it would leave no way to gate on a transaction child at all.
 *
 * <p>A {@code match} pattern reaches only top-level, non-hidden samplers. This is not
 * symmetry for its own sake: if patterns reached children, a blanket {@code match: "*"} rule
 * would judge rows whose time is already counted inside their parent — and utility samplers
 * the report does not even display.
 */
public final class AssertionTarget {

    public enum Kind { SAMPLER, SAMPLERS, MATCH, OVERALL, INVALID }

    /** The name results carry for a run-level match; not a sampler that can ever exist. */
    public static final String OVERALL_KEY = "__overall__";

    /**
     * How the whole run reads wherever it is shown. Display text only: a sampler can be called
     * this, so nothing is ever keyed by it (CORE-1) — {@link #OVERALL_KEY} is the key.
     */
    public static final String OVERALL_LABEL = "Whole run";

    /** The display text for a match key: {@link #OVERALL_LABEL} for the run, else the name. */
    public static String labelOf(String key) {
        return OVERALL_KEY.equals(key) ? OVERALL_LABEL : key;
    }

    private final Kind kind;
    private final List<String> names;
    private final String pattern;
    private final List<String> excludes;

    private AssertionTarget(Kind kind, List<String> names, String pattern, List<String> excludes) {
        this.kind = kind;
        this.names = names;
        this.pattern = pattern;
        this.excludes = excludes;
    }

    public static AssertionTarget sampler(String name) {
        return new AssertionTarget(Kind.SAMPLER, Collections.singletonList(name), null,
                Collections.<String>emptyList());
    }

    public static AssertionTarget samplers(List<String> names) {
        return new AssertionTarget(Kind.SAMPLERS,
                names == null ? Collections.<String>emptyList() : new ArrayList<>(names),
                null, Collections.<String>emptyList());
    }

    public static AssertionTarget match(String pattern, List<String> excludes) {
        return new AssertionTarget(Kind.MATCH, Collections.<String>emptyList(), pattern,
                excludes == null ? Collections.<String>emptyList() : new ArrayList<>(excludes));
    }

    public static AssertionTarget overall() {
        return new AssertionTarget(Kind.OVERALL, Collections.<String>emptyList(), null,
                Collections.<String>emptyList());
    }

    /**
     * A target the file declared and this version cannot read — missing, empty, more than one
     * form, or a scope that is not {@code overall}.
     *
     * <p>The rule needs a target to exist at all, and it has to exist. The loader used to drop
     * the whole rule when it could not read the target: it was then absent from
     * {@code gate.declared}, absent from every count, absent from {@code assertions[]}, had no
     * JUnit testcase, and {@code jq -e '.gate.failed == 0'} passed. Three declared rules became a
     * green build on one. A typo'd metric has always been reported for exactly this reason; a
     * typo'd target is the same mistake and gets the same answer — today that answer is
     * {@code NOT_JUDGED / INVALID_RULE}, counted under {@code gate.notJudged.invalidRule}.
     *
     * <p>Matches nothing, and the evaluator short-circuits on the kind before it ever resolves.
     *
     * @param problem what is wrong, in the words the finding uses — it becomes the target text
     *        in the report, the JUnit failure message and the CI/CD JSON
     */
    public static AssertionTarget unusable(String problem) {
        return new AssertionTarget(Kind.INVALID, Collections.<String>emptyList(), problem,
                Collections.<String>emptyList());
    }

    public Kind kind() {
        return kind;
    }

    public List<String> names() {
        return Collections.unmodifiableList(names);
    }

    public String pattern() {
        return pattern;
    }

    public List<String> excludes() {
        return Collections.unmodifiableList(excludes);
    }

    /**
     * The samplers this target selects, in report order, or a single {@link #OVERALL_KEY}.
     *
     * @throws PatternSyntaxException when the pattern is not a usable regex — the caller
     *         turns that into {@code NOT_JUDGED / INVALID_RULE} with the message attached,
     *         rather than an empty match set that would look like an innocent typo
     */
    public List<String> resolve(ReportData data) {
        if (kind == Kind.OVERALL) {
            return Collections.singletonList(OVERALL_KEY);
        }
        List<SamplerStatistics> all = data.getSamplerStatistics();
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> resolved = new ArrayList<>();
        switch (kind) {
            case SAMPLER:
            case SAMPLERS: {
                // Named: reaches children and hidden rows. Ordered by the rule's list rather
                // than the report's, so an editor showing "matches: a, b" shows them as typed.
                Set<String> present = new LinkedHashSet<>();
                for (SamplerStatistics s : all) {
                    present.add(s.getSamplerName());
                }
                for (String wanted : names) {
                    if (present.contains(wanted) && !resolved.contains(wanted)) {
                        resolved.add(wanted);
                    }
                }
                return resolved;
            }
            case MATCH: {
                Pattern re = compile(pattern);
                List<Pattern> excluded = new ArrayList<>(excludes.size());
                for (String ex : excludes) {
                    excluded.add(compile(ex));
                }
                Set<String> hidden = data.getHiddenSamplers() == null
                        ? Collections.<String>emptySet()
                        : new HashSet<>(data.getHiddenSamplers());

                for (SamplerStatistics s : all) {
                    String name = s.getSamplerName();
                    if (s.getParentSamplerName() != null || hidden.contains(name)) {
                        continue; // blanket selectors see the report's top-level population only
                    }
                    if (!re.matcher(name).matches()) {
                        continue;
                    }
                    boolean dropped = false;
                    for (Pattern ex : excluded) {
                        if (ex.matcher(name).matches()) {
                            dropped = true;
                            break;
                        }
                    }
                    if (!dropped) {
                        resolved.add(name);
                    }
                }
                return resolved;
            }
            default:
                return Collections.emptyList();
        }
    }

    /**
     * Glob by default, {@code regex:} to opt out.
     *
     * <p>{@code POST /api/*} is what a tester types, and every other character in a sampler
     * name — {@code ?}, {@code {id}}, {@code +}, a query string full of {@code &} — is meant
     * literally. Escaping everything and expanding only {@code *} is what makes that true;
     * handing the string to {@link Pattern} directly would silently reinterpret half the
     * REST-style names in a typical plan.
     */
    public static Pattern compile(String glob) {
        if (glob == null) {
            return Pattern.compile("$^"); // matches nothing
        }
        if (glob.startsWith("regex:")) {
            return Pattern.compile(glob.substring("regex:".length()));
        }
        StringBuilder sb = new StringBuilder(glob.length() + 8).append('^');
        String[] segments = glob.split("\\*", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append(".*");
            }
            sb.append(Pattern.quote(segments[i]));
        }
        return Pattern.compile(sb.append('$').toString());
    }

    /** Every character {@link Pattern} and JavaScript {@code RegExp} both treat as syntax. */
    private static final Pattern REGEX_SYNTAX = Pattern.compile("[.*+?^${}()|\\[\\]\\\\/]");

    /**
     * An escaped-and-anchored exclude pattern, undone, for anything a person reads.
     *
     * <p>Nothing WRITES these any more — {@code exactly()} was the writer and it went with the
     * legacy translator that was its only caller. This stays because it is the READER, and the
     * patterns are already sitting in saved annotations files: both the {@code regex:^…$}
     * per-character form and the {@code \Q…\E} form that shipped in 1.4.14. Deleting it would
     * not simplify anything, it would make an old file render its excludes as escaped regex in
     * every report row and JUnit failure.
     *
     * <p>A hand-written {@code regex:} exclude is a pattern its author meant, and stays as
     * typed — only output this class could itself have produced is reversed.
     */
    private static List<String> readable(List<String> patterns) {
        List<String> out = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            out.add(p == null ? null : unquote(p));
        }
        return out;
    }

    private static String unquote(String pattern) {
        if (pattern.startsWith("regex:^") && pattern.endsWith("$")) {
            String body = pattern.substring("regex:^".length(), pattern.length() - 1);
            // Only a string this class escaped: every backslash introduces one syntax character
            // and nothing else appears escaped. Anything richer is the author's own regex.
            StringBuilder sb = new StringBuilder(body.length());
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c != '\\') {
                    if (REGEX_SYNTAX.matcher(String.valueOf(c)).matches()) {
                        return pattern;   // unescaped syntax — not ours
                    }
                    sb.append(c);
                    continue;
                }
                if (i + 1 >= body.length()) {
                    return pattern;
                }
                sb.append(body.charAt(++i));
            }
            return sb.toString();
        }
        // The 1.4.14 shape. Pattern.quote is not reversible in general — a name containing \E
        // splices to \Qa\E\\E\Qb\E — so only the simple, overwhelmingly common case is undone.
        if (pattern.startsWith("regex:\\Q") && pattern.endsWith("\\E")) {
            String body = pattern.substring("regex:\\Q".length(), pattern.length() - "\\E".length());
            return body.contains("\\E") || body.contains("\\Q") ? pattern : body;
        }
        return pattern;
    }

    /** How the target reads in the report and in failure text. */
    public String describe() {
        switch (kind) {
            case OVERALL:  return OVERALL_LABEL;
            case SAMPLER:  return names.isEmpty() ? "" : names.get(0);
            case SAMPLERS: return String.join(", ", names);
            case MATCH:    return excludes.isEmpty()
                    ? String.valueOf(pattern)
                    : pattern + " − " + String.join(", ", readable(excludes));
            // The problem itself: an unusable target has nothing else to show, and the reader
            // needs to know what to fix in the file rather than that "something" was wrong.
            case INVALID:  return pattern == null ? "unusable target" : pattern;
            default:       return "";
        }
    }

    /** A stable, filesystem-and-id-safe stem derived from the target, for auto-generated ids. */
    public String slug() {
        String base;
        switch (kind) {
            case OVERALL:  base = "run"; break;
            case MATCH:    base = pattern == null ? "" : pattern.replace("regex:", ""); break;
            case SAMPLERS: base = names.isEmpty() ? "group" : String.join("-", names); break;
            // Not the problem text — an id built from a sentence is unreadable in a JUnit
            // testcase name. The mapper gives these rules the file's own label anyway.
            case INVALID:  base = "invalid-target"; break;
            default:       base = names.isEmpty() ? "" : names.get(0); break;
        }
        String cleaned = base.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        List<String> parts = new ArrayList<>();
        for (String p : cleaned.split("-")) {
            if (!p.isEmpty()) parts.add(p);
        }
        // Last few segments: a REST path's tail identifies it far better than its host prefix,
        // and ids appear in JUnit testcase names where length is a real cost.
        if (parts.size() > 3) {
            parts = new ArrayList<>(parts.subList(parts.size() - 3, parts.size()));
        }
        String slug = String.join("-", parts);
        if (slug.length() > 28) {
            slug = slug.substring(0, 28);
        }
        return slug.isEmpty() ? "rule" : slug;
    }

    @Override
    public String toString() {
        return kind + "(" + describe() + ")";
    }

    /** Convenience for tests and the translator. */
    static List<String> list(String... values) {
        return Arrays.asList(values);
    }
}
