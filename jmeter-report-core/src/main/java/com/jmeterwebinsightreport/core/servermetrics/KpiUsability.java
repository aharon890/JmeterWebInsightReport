package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.Predicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single answer to <em>can this KPI decide anything?</em> — the server-side
 * {@code Usability}, under the same contract and for the same reason.
 *
 * <p>That reason is written on the client class: the question was once answered twice, the
 * validator's answer was inert because findings do not gate, and a band that could never fire
 * reported PASS on every run while the finding beside it said so. One answer, two readers:
 *
 * <ul>
 *   <li>{@link #refusal(ServerKpi)} is what the evaluator asks. Non-null means the KPI is
 *       {@code NOT_JUDGED / INVALID_RULE} and decides nothing, in either direction.</li>
 *   <li>{@link #check(ServerKpi)} is what the validator asks. Every problem, in report order.</li>
 * </ul>
 *
 * <p><strong>The invariant:</strong> {@code refusal(k) != null} exactly when {@code check(k)}
 * contains an ERROR, and the INVALID_RULE detail is that entry's own sentence. Pinned by
 * {@code KpiUsabilityTest}.
 *
 * <p>What this class does NOT answer is whether the data arrived — {@code SOURCE_FAILED},
 * {@code NO_MATCH}, {@code LOW_COVERAGE} come from {@link CollectionStatus#toReason()}, a
 * property of the collected series. Every check here is a pure function of ONE KPI as written;
 * the three faults that need the rest of the file (an unknown source, an unusable source, an
 * undeclared gate) are resolved by the mapper and recorded on the KPI as declaration problems,
 * the route an unreadable band already takes.
 *
 * <p><strong>Not everything wrong with a KPI refuses it.</strong> A downward band at or below
 * zero is a WARNING here, not the ERROR it is on the client: the client knows every metric is a
 * duration, count, rate or score and none can be negative; a server KPI is a gauge whose range
 * this class does not know, and a {@code slope} is negative on purpose. Refusing would turn a
 * guess about the system under test into a non-zero exit. The verdicts are correct either way,
 * which is what makes it a warning.
 */
public final class KpiUsability {

    public enum Reason {
        /** The assert's component has a type that is neither a catalogue type nor custom. Recorded by the mapper. */
        UNKNOWN_TYPE,
        /** The assert names a metric its component's type does not offer. Recorded by the mapper. */
        UNKNOWN_METRIC,
        /** {@code metric} and {@code query} both, neither, or a metric on a custom component. Recorded by the mapper. */
        METRIC_OR_QUERY,
        /** No query, or a blank one. */
        MISSING_QUERY,
        /** The assert names no component, or one the file does not declare. Recorded by the mapper. */
        UNDECLARED_COMPONENT,
        /** {@code reduce.kind} is not one of the reducers. Recorded by the mapper. */
        UNKNOWN_REDUCER,
        /** {@code reduce.over} could not be read, or declared both forms. Recorded by the mapper. */
        BAD_TOLERANCE,
        /** {@code across} held something unreadable (the one free-text field left). Recorded by the mapper. */
        UNREADABLE_FIELD,
        /** No warn and no fail. Nothing to compare against. */
        NO_BANDS,
        /** A band was declared and could not be read. Recorded by the mapper. */
        BAND_UNREADABLE,
        /** A key from the canvas grammar that this grammar replaced. Recorded by the mapper. */
        RETIRED_KEY,
        /** Warn sits behind fail, so it can never fire. The KPI still judges correctly. */
        WARN_COVERED_BY_FAIL,
        /** A downward band at or below zero on a reducer that is normally non-negative. Suspect, not refused. */
        BAND_NEGATIVE_ONLY,
        /**
         * An upward band that can only fire above the declared ceiling of the catalogue metric the
         * assert judges (F2b). Suspect, not refused: some ceilings are nominal, and the band is
         * judged as written.
         */
        BAND_ABOVE_CEILING,
        /**
         * The query starts or ends with an invisible character that {@link String#trim} leaves:
         * Unicode White_Space (a no-break space above all), a default-ignorable character
         * (zero-width, bidi, variation selector, tag ...) or a C1 control — the fixed table
         * {@code isInvisible} scans. The KPI still judges — it simply matches nothing — so
         * a WARNING, never a refusal.
         */
        INVISIBLE_PADDING
    }

    private final Reason reason;
    private final AssertionValidator.Severity severity;
    private final String sentence;
    private final boolean loaderReported;

    private KpiUsability(Reason reason, AssertionValidator.Severity severity, String sentence) {
        this(reason, severity, sentence, defaultLoaderReported(reason));
    }

    private KpiUsability(Reason reason, AssertionValidator.Severity severity, String sentence,
                         boolean loaderReported) {
        this.reason = reason;
        this.severity = severity;
        this.sentence = sentence;
        this.loaderReported = loaderReported;
    }

    public Reason reason() { return reason; }

    public AssertionValidator.Severity severity() { return severity; }

    /** True when this problem stops the KPI deciding anything. */
    public boolean refuses() { return severity == AssertionValidator.Severity.ERROR; }

    /**
     * True when the mapper has already reported this, at the point it happened, so the validator
     * must not say it a second time — "two findings for one mistake" is a regression the client
     * side has already had and pinned against.
     *
     * <p>Carried per instance rather than per {@link Reason}, because one reason can be raised
     * from both places. {@code BAD_TOLERANCE} is the case: the mapper raises it for an
     * {@code over} clause it could not read, and {@link #check} raises it for one it read
     * perfectly well and that the reducer does not accept. Deciding by reason alone silenced the
     * second — the KPI was refused, and no finding anywhere said why.
     */
    public boolean loaderReported() {
        return loaderReported;
    }

    private static boolean defaultLoaderReported(Reason reason) {
        switch (reason) {
            case UNKNOWN_TYPE:
            case UNKNOWN_METRIC:
            case METRIC_OR_QUERY:
            case UNDECLARED_COMPONENT:
            case UNKNOWN_REDUCER:
            case BAD_TOLERANCE:
            case UNREADABLE_FIELD:
            case BAND_UNREADABLE:
            case RETIRED_KEY:
                return true;
            case MISSING_QUERY:
            case NO_BANDS:
            case WARN_COVERED_BY_FAIL:
            case BAND_NEGATIVE_ONLY:
            case BAND_ABOVE_CEILING:
            case INVISIBLE_PADDING:
                return false;
        }
        throw new IllegalStateException("no reporting decision for " + reason);
    }

    /** The short sentence, as the gate reports it — what reaches NOT_JUDGED / INVALID_RULE. */
    public String reasonText() { return sentence; }

    /** Render as a finding against the given KPI id. */
    public AssertionValidator.Finding asFinding(String kpiId) {
        return AssertionValidator.finding(severity, kpiId, sentence);
    }

    // ---- mapper-only facts ----------------------------------------------------

    /**
     * The assert names no component, or one the file does not declare. A typo'd component must
     * not silently become a component of its own, so it is refused, under the name it asked for.
     */
    public static KpiUsability undeclaredComponent(String component, String declared) {
        boolean none = component == null || component.trim().isEmpty() || "(none)".equals(component);
        return new KpiUsability(Reason.UNDECLARED_COMPONENT, AssertionValidator.Severity.ERROR,
                (none ? "names no component" : "component '" + component + "' is not declared under "
                        + "serverMetrics.components")
                        + (declared == null || declared.isEmpty()
                        ? " — none are declared; add one, e.g. \"components\": {\"orders-db\": "
                        + "{\"type\": \"postgres\"}}"
                        : " (declared: " + declared + ")"));
    }

    /** The assert's component has a type the catalogue does not know. */
    public static KpiUsability unknownType(String component, String type, String vocabulary) {
        return new KpiUsability(Reason.UNKNOWN_TYPE, AssertionValidator.Severity.ERROR,
                "component '" + component + "' has type '" + type + "', which is not one of "
                        + vocabulary);
    }

    /** A metric the component's type does not offer; {@code available} lists the ones it does. */
    public static KpiUsability unknownMetric(String metric, String typeRef, String available) {
        return new KpiUsability(Reason.UNKNOWN_METRIC, AssertionValidator.Severity.ERROR,
                "metric '" + metric + "' is not in " + typeRef + " — it offers: " + available
                        + ". For anything else write a \"query\" instead of a \"metric\"");
    }

    /** {@code metric} and {@code query} both, neither, or a metric on a custom component. */
    public static KpiUsability metricOrQuery(String problem) {
        return new KpiUsability(Reason.METRIC_OR_QUERY, AssertionValidator.Severity.ERROR, problem);
    }

    public static KpiUsability unknownReducer(String kind) {
        return new KpiUsability(Reason.UNKNOWN_REDUCER, AssertionValidator.Severity.ERROR,
                (kind == null || kind.trim().isEmpty()
                        ? "reduce.kind is missing"
                        : "reduce.kind '" + kind + "' is not a reducer")
                        + " — valid here: " + Reducer.vocabulary());
    }

    /**
     * A {@code reduce.kind} that is neither text nor a number — a list, an object, a boolean —
     * named by what it is ({@code described}: "a list"), never by {@code String.valueOf}, which
     * printed Java's own rendering ({@code [1, 2]}, {@code {}}) and could not be reproduced by the
     * page reading the same file. A number is quoted by its value through
     * {@link #unknownReducer}.
     */
    public static KpiUsability reducerKindNotText(String described) {
        return new KpiUsability(Reason.UNKNOWN_REDUCER, AssertionValidator.Severity.ERROR,
                "reduce.kind must be the name of a reducer, not " + described
                        + " — valid here: " + Reducer.vocabulary());
    }

    /** A {@code reduce.over} clause the mapper could not read — already reported at load. */
    public static KpiUsability badTolerance(String problem) {
        return new KpiUsability(Reason.BAD_TOLERANCE, AssertionValidator.Severity.ERROR,
                "reduce.over " + problem);
    }

    /**
     * A {@code reduce.over} clause that read fine and that this reducer does not accept. Found by
     * {@link #check}, never by the mapper — which is why it must say so itself.
     */
    private static KpiUsability badToleranceForReducer(String problem) {
        return new KpiUsability(Reason.BAD_TOLERANCE, AssertionValidator.Severity.ERROR,
                "reduce.over " + problem, false);
    }

    /** Any other field the mapper could not read. The sentence names the field and the grammar. */
    public static KpiUsability unreadableField(String field, String problem) {
        return new KpiUsability(Reason.UNREADABLE_FIELD, AssertionValidator.Severity.ERROR,
                field + " " + problem);
    }

    /** A band that was written and could not be read. {@code which} is "warn" or "fail". */
    public static KpiUsability unreadableBand(String which, String problem) {
        return new KpiUsability(Reason.BAND_UNREADABLE, AssertionValidator.Severity.ERROR,
                problem + " — so this server assert declares a limit it cannot apply, and would report "
                        + "PASS without ever testing " + which);
    }

    /**
     * A key from the canvas grammar. {@code problem} names the replacement; the key is prefixed
     * when the sentence does not already open with it, so a reader always sees which key.
     */
    public static KpiUsability retiredKey(String key, String problem) {
        String sentence = problem != null && problem.startsWith("\"" + key + "\"")
                ? problem
                : "\"" + key + "\": " + problem;
        return new KpiUsability(Reason.RETIRED_KEY, AssertionValidator.Severity.ERROR, sentence);
    }

    // ---- the checks ------------------------------------------------------------

    /**
     * Every problem with this KPI, in report order, worst first.
     *
     * <p>Mapper-recorded facts come first because they are about what was WRITTEN; everything
     * below reasons about what could be parsed out of it.
     */
    public static List<KpiUsability> check(ServerKpi kpi) {
        List<KpiUsability> found = new ArrayList<>(2);
        if (kpi == null) {
            return found;
        }
        checkInto(found, kpi);
        // Last, and outside checkInto: it is a fact about the query alone, so none of the early
        // returns below (no reducer, no bands) may swallow it.
        KpiUsability padded = invisiblePadding(kpi.query());
        if (padded != null) {
            found.add(padded);
        }
        return found;
    }

    private static void checkInto(List<KpiUsability> found, ServerKpi kpi) {
        found.addAll(kpi.declarationProblems());

        if (kpi.query() == null || kpi.query().trim().isEmpty()) {
            // The mapper's metric-or-query refusal already says why there is no query. So does an
            // undeclared component under a catalogue metric (SF21): the metric brings the query,
            // from the component's type, and with no component there is no type to bring it from.
            // "no query" there pointed the author at a field they had no reason to write, beside
            // the one sentence that names the typo.
            boolean componentUnresolved = kpi.metric() != null && hasReason(kpi, Reason.UNDECLARED_COMPONENT);
            if (!hasReason(kpi, Reason.METRIC_OR_QUERY) && !hasReason(kpi, Reason.UNKNOWN_METRIC)
                    && !hasReason(kpi, Reason.UNKNOWN_TYPE) && !componentUnresolved) {
                found.add(new KpiUsability(Reason.MISSING_QUERY, AssertionValidator.Severity.ERROR,
                        "no query — the server assert has nothing to collect"));
            }
            return;
        }
        // The mapper records a null reducer or component as it happens, but this class must not
        // TRUST that it did: the builder is public, and "this method IS the definition of an
        // unusable KPI" is only true if it answers for a KPI built by anyone. Same pattern as
        // NO_BANDS below — add the fact unless it is already there.
        if (kpi.gate() == null && !hasReason(kpi, Reason.UNDECLARED_COMPONENT)) {
            found.add(undeclaredComponent(null, null));
        }
        if (kpi.reducer() == null) {
            if (!hasReason(kpi, Reason.UNKNOWN_REDUCER) && !hasReason(kpi, Reason.BAD_TOLERANCE)) {
                found.add(unknownReducer(null));
            }
            // Nothing below can be trusted to be the real complaint.
            return;
        }
        // A tolerance is defined for the two reducers that pick a level — "the highest level
        // held for 30 s", "the level exceeded at most 2% of the time". On avg, last, pN or
        // slope there is no level to hold, and judging WITHOUT the tolerance the author wrote
        // would fail the build on the spike they tried to tolerate. Refused, not ignored.
        if (!kpi.reducer().toleranceHasLevel() && !hasReason(kpi, Reason.BAD_TOLERANCE)) {
            found.add(badToleranceForReducer("(" + kpi.reducer().over().describe() + ") is only defined "
                    + "on max and min, not on " + kpi.reducer().key()
                    + " - there is no level for it to hold"));
        }

        if (!kpi.hasBands()) {
            if (!hasUnreadableBand(kpi)) {
                found.add(new KpiUsability(Reason.NO_BANDS, AssertionValidator.Severity.ERROR,
                        "no warn or fail band — the server assert can never decide anything"));
            }
            return;
        }

        addIfNegativeOnly(found, kpi, kpi.warn(), "warn");
        addIfNegativeOnly(found, kpi, kpi.fail(), "fail");
        addIfAboveCeiling(found, kpi, kpi.warn(), "warn");
        addIfAboveCeiling(found, kpi, kpi.fail(), "fail");

        KpiUsability deadWarn = warnCoveredByFail(kpi);
        if (deadWarn != null) {
            found.add(deadWarn);
        }
    }

    /**
     * A query that starts or ends with a character {@link String#trim} leaves and nobody can see
     * — a WARNING (Q2), with the sentence the page shows word for word.
     *
     * <p>{@code ServerKpi} trims what is {@code <= U+0020}, and the page trims exactly that
     * ({@code javaTrim}, never {@code String.prototype.trim}, which also strips a no-break space
     * and would split the two sides). A query pasted with a no-break space, a zero-width or bidi
     * character, or any other in {@link #isInvisible}'s table at an end is therefore a different
     * series from the one the source returns: no CSV row is named that, and no recording is keyed
     * by it. Nothing matches, which is NO_MATCH, which never fails a build - the silent pass U7
     * closed for plain spaces. Not refused: the verdict is still the truth about what was asked,
     * and an invisible character is not a reason to exit 1. Not trimmed either: then the run and a
     * hand-written payload or a recording keyed by the text as written would disagree about what
     * it says.
     *
     * <p>Said from the file alone, so it cannot tell padding from a source that carries the same
     * text - a CSV cell padded the same way, or a visible character that ends in a member of the
     * table, as an emoji in its emoji presentation ends in U+FE0F and a subdivision flag in
     * U+E007F. That series matches and is judged, and this WARNING is wrong about it, which is
     * accepted: it decides nothing (Q6). wir-collect and the page's Validate query, which hold what
     * came back, say nothing of it for a series that returned points.
     */
    private static KpiUsability invisiblePadding(String query) {
        String ends = invisibleEnds(query);
        if (ends == null) {
            return null;
        }
        return new KpiUsability(Reason.INVISIBLE_PADDING, AssertionValidator.Severity.WARNING,
                "the query has an invisible character at its " + ends
                        + " that is not trimmed like a plain space, so no series will match it — delete it");
    }

    /**
     * Which ends of {@code text} are a character of {@link #INVISIBLE}, as the
     * {@link Reason#INVISIBLE_PADDING} sentences name them — {@code "end (U+00A0)"},
     * {@code "start (U+200B) and at its end (U+2060)"} — or {@code null} when neither is. The one
     * wording for every text the WARNING is said about: an assert's query, a component's match
     * ({@link #matchPaddingText}) and a CSV metric cell ({@code CsvSource}).
     */
    public static String invisibleEnds(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int first = text.codePointAt(0);
        int last = text.codePointBefore(text.length());
        List<String> ends = new ArrayList<>(2);
        if (isInvisible(first)) {
            ends.add("start (" + codePoint(first) + ")");
        }
        if (isInvisible(last)) {
            ends.add("end (" + codePoint(last) + ")");
        }
        return ends.isEmpty() ? null : String.join(" and at its ", ends);
    }

    /**
     * The INVISIBLE_PADDING WARNING for a component's {@code match} (F8-residual), or {@code null}.
     * {@code match} is the selector as {@code ServerGate} keeps it, trimmed as {@link String#trim}
     * trims, so plain spaces around an invisible character are gone and the sentence names the
     * character itself.
     *
     * <p>A match goes into every catalogue query of its component as it is ({@code ${match}},
     * {@code ${andMatch}}, a neutral identity's braces), so a no-break space at its end is in every
     * series the component asks for: Prometheus parses it as no label selector it knows, a recording
     * is keyed by a query nobody recorded, and every catalogue assert on the component reads
     * NO_MATCH. Filed under the component by the mapper, and only for a catalogue type - a custom
     * component's asserts write their own query and never expand the match. A WARNING, never a
     * refusal and never a config problem, as the query's own is not: the verdicts are still the truth
     * about what was asked.
     */
    public static String matchPaddingText(String match) {
        String ends = invisibleEnds(match);
        return ends == null ? null : "the match has an invisible character at its " + ends
                + " that is not trimmed like a plain space, and it goes into every catalogue query of this "
                + "component as it is, which Prometheus and a recording will not answer — delete it";
    }

    /**
     * The {@link Reason#INVISIBLE_PADDING} sentence for {@code query}, or {@code null} when
     * neither end is invisible. For the collector, which sees the query a source is actually
     * asked — after {@code ${env.*}} is resolved — while {@link #check} sees only what the file
     * wrote: a query written as {@code ${env.q}} with a value that starts with a no-break space
     * passes every check on the file and still matches nothing.
     */
    public static String invisiblePaddingText(String query) {
        KpiUsability padded = invisiblePadding(query);
        return padded == null ? null : padded.reasonText();
    }

    /**
     * {@code text} with every character {@link String#trim} takes and every one {@link #INVISIBLE}
     * names taken off both ends, however they are mixed: what a cell padded with a no-break space
     * was meant to say. For the CSV reader, which fails the series such a cell was meant for rather
     * than read it as another (F8-residual).
     */
    public static String withoutInvisibleEnds(String text) {
        if (text == null) {
            return null;
        }
        int start = 0;
        int end = text.length();
        while (start < end) {
            int cp = text.codePointAt(start);
            if (cp > ' ' && !isInvisible(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = text.codePointBefore(end);
            if (cp > ' ' && !isInvisible(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return text.substring(start, end);
    }

    /**
     * Whether {@code text} starts or ends with a character {@link #invisiblePadding} warns about.
     * For a file that must not carry one at all — the built-in catalogue, which refuses it.
     */
    public static boolean hasInvisibleEnd(String text) {
        return text != null && !text.isEmpty()
                && (isInvisible(text.codePointAt(0)) || isInvisible(text.codePointBefore(text.length())));
    }

    /**
     * The characters {@link #invisiblePadding} names (F8): Unicode White_Space, every
     * Default_Ignorable_Code_Point and DEL with the C1 controls, minus everything at or below
     * U+0020, which {@link String#trim} has already taken off both ends. Maximal, ascending,
     * inclusive ranges.
     *
     * <p>Written out, never asked of {@link Character#getType}: the JVM's Unicode tables move
     * between versions (U+180E was a space separator until Unicode 6.3 and is a format character
     * since), and the page holds the same table in {@code isInvisibleCodePoint}, so a class each
     * side derived from its own runtime would be two classes. The definition is
     * {@code invisibleEnds.ranges} in server-parity-vectors.json; ServerParityTest sweeps every
     * code point against it, and server-parity.test.js holds the page's copy to it the same way.
     */
    private static final int[][] INVISIBLE = {
            {0x007F, 0x00A0},   // DEL, the C1 controls (NEXT LINE among them), NO-BREAK SPACE
            {0x00AD, 0x00AD},   // SOFT HYPHEN
            {0x034F, 0x034F},   // COMBINING GRAPHEME JOINER
            {0x061C, 0x061C},   // ARABIC LETTER MARK
            {0x115F, 0x1160},   // HANGUL CHOSEONG and JUNGSEONG FILLER
            {0x1680, 0x1680},   // OGHAM SPACE MARK
            {0x17B4, 0x17B5},   // KHMER VOWEL INHERENT AQ and AA
            {0x180B, 0x180F},   // MONGOLIAN FREE VARIATION SELECTORS, VOWEL SEPARATOR
            {0x2000, 0x200F},   // EN QUAD .. HAIR SPACE, ZERO WIDTH SPACE / NON-JOINER / JOINER, LRM, RLM
            {0x2028, 0x202F},   // LINE and PARAGRAPH SEPARATOR, the bidi embeddings, NARROW NO-BREAK SPACE
            {0x205F, 0x206F},   // MEDIUM MATHEMATICAL SPACE, WORD JOINER, invisible operators, bidi isolates
            {0x3000, 0x3000},   // IDEOGRAPHIC SPACE
            {0x3164, 0x3164},   // HANGUL FILLER
            {0xFE00, 0xFE0F},   // VARIATION SELECTORS 1-16
            {0xFEFF, 0xFEFF},   // ZERO WIDTH NO-BREAK SPACE (byte-order mark)
            {0xFFA0, 0xFFA0},   // HALFWIDTH HANGUL FILLER
            {0xFFF0, 0xFFF8},   // reserved, default-ignorable
            {0x1BCA0, 0x1BCA3}, // SHORTHAND FORMAT CONTROLS
            {0x1D173, 0x1D17A}, // MUSICAL SYMBOL BEGIN/END BEAM, TIE, SLUR, PHRASE
            {0xE0000, 0xE0FFF}, // TAGS, VARIATION SELECTORS 17-256, reserved
    };

    private static boolean isInvisible(int cp) {
        for (int[] range : INVISIBLE) {
            if (cp < range[0]) {
                return false;
            }
            if (cp <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private static String codePoint(int cp) {
        return String.format(java.util.Locale.ROOT, "U+%04X", cp);
    }

    /**
     * The first thing that stops this KPI deciding anything, or {@code null} when it can.
     *
     * <p>This method IS the server gate's definition of an unusable KPI. Nothing else may
     * manufacture one.
     */
    public static KpiUsability refusal(ServerKpi kpi) {
        for (KpiUsability u : check(kpi)) {
            if (u.refuses()) {
                return u;
            }
        }
        return null;
    }

    private static boolean hasUnreadableBand(ServerKpi kpi) {
        return hasReason(kpi, Reason.BAND_UNREADABLE);
    }

    private static boolean hasReason(ServerKpi kpi, Reason reason) {
        for (KpiUsability u : kpi.declarationProblems()) {
            if (u.reason() == reason) {
                return true;
            }
        }
        return false;
    }

    /**
     * A downward band at or below zero on a reducer that is normally non-negative — a WARNING.
     *
     * <p>The client refuses this shape because it KNOWS its metrics cannot be negative. Here
     * that is a guess: a Prometheus gauge can be anything, and a {@code slope} is negative
     * whenever a value is falling. So the band is flagged as suspect and still judged. On a
     * {@code slope} it is not flagged at all — {@code fail: {lt: 0}} on heap growth is a
     * perfectly good "heap must not shrink" check written the other way round.
     *
     * <p>The one server sentence that does not follow the client's (U8b). The client's
     * {@code BAND_UNREACHABLE} ends "a tolerance meant as a percentage is written as a fraction —
     * 0.2, not 20", which is about its {@code loadValidity} tolerance. A server assert has no such
     * tolerance, and its percentages are 0-100 ({@code connections.used.pct} reads 68): taking that
     * advice turned a 20 % floor into {@code fail < 0.2}, a band that almost never fires.
     */
    private static void addIfNegativeOnly(List<KpiUsability> found, ServerKpi kpi, Predicate band,
                                          String which) {
        if (band == null || kpi.reducer().kind().canBeNegative()) {
            return;
        }
        boolean downward = band.op() == Predicate.Op.LT || band.op() == Predicate.Op.LTE;
        if (!downward) {
            return;
        }
        boolean negativeOnly = band.op() == Predicate.Op.LT ? band.value() <= 0 : band.value() < 0;
        if (!negativeOnly) {
            return;
        }
        // The band in the unit it is compared in (SF16): a ratio's band is per unit of the load.
        found.add(new KpiUsability(Reason.BAND_NEGATIVE_ONLY, AssertionValidator.Severity.WARNING,
                which + " " + band.describe(kpi.verdictUnit()) + " can only fire on a negative value. "
                        + "If this metric is never negative the band never fires and the server assert reports "
                        + "PASS on every run; a band is written in the unit the values are judged in"));
    }

    /**
     * An upward band that can only fire above the ceiling the catalogue declares for the metric —
     * {@code gt} at or past it, {@code gte} past it, the shape {@code Catalogue} refuses in its own
     * safe defaults — a WARNING (F2b).
     *
     * <p>Not refused: the page never proposes such a band, but one typed or dragged is still judged
     * as written, and some ceilings are nominal ({@code connections.used.pct}'s 100 is a share of
     * a setting, not a law), so the sentence is conditional. Exempt where the ceiling does not
     * bound the number judged: a {@code slope} (per minute, not in the metric's unit) and a
     * {@code divideBy} ratio (per unit of the load). Every {@link Across} fold (max, min, avg) and
     * every other reducer stays inside the points' range, so none of them is.
     */
    private static void addIfAboveCeiling(List<KpiUsability> found, ServerKpi kpi, Predicate band,
                                          String which) {
        Double ceiling = kpi.ceiling();
        if (band == null || ceiling == null || kpi.divideBy() != null
                || kpi.reducer().kind() == Reducer.Kind.SLOPE) {
            return;
        }
        boolean aboveOnly = band.op() == Predicate.Op.GT ? band.value() >= ceiling
                : band.op() == Predicate.Op.GTE && band.value() > ceiling;
        if (!aboveOnly) {
            return;
        }
        String unit = kpi.verdictUnit();
        found.add(new KpiUsability(Reason.BAND_ABOVE_CEILING, AssertionValidator.Severity.WARNING,
                which + " " + band.describe(unit) + " can only fire above the ceiling of "
                        + Predicate.trim(ceiling) + (unit.isEmpty() ? "" : " " + unit)
                        + " the catalogue declares for " + kpi.metric() + ". If it never reads above its "
                        + "ceiling the band never fires and the server assert reports PASS on every run"));
    }

    /** A warn band the fail band already covers — almost always swapped values. WARNING. */
    private static KpiUsability warnCoveredByFail(ServerKpi kpi) {
        Predicate warn = kpi.warn();
        Predicate fail = kpi.fail();
        if (warn == null || fail == null) {
            return null;
        }
        boolean upward = warn.op() == Predicate.Op.GT || warn.op() == Predicate.Op.GTE;
        boolean failUpward = fail.op() == Predicate.Op.GT || fail.op() == Predicate.Op.GTE;
        if (upward != failUpward) {
            return null; // opposite directions is a corridor written the long way; leave it
        }
        boolean dead = upward ? warn.value() >= fail.value() : warn.value() <= fail.value();
        if (!dead) {
            return null;
        }
        // Both bands in the unit they are compared in (SF16): "fail > 200 B/min" on a slope, where
        // "fail > 200 B" named a level the band never judges.
        String unit = kpi.verdictUnit();
        return new KpiUsability(Reason.WARN_COVERED_BY_FAIL, AssertionValidator.Severity.WARNING,
                "warn can never fire: fail " + fail.describe(unit) + " is tested first and "
                        + "already covers warn " + warn.describe(unit) + ". Swapped values?");
    }

    /** An empty, immutable list — the common case, allocated once. */
    static final List<KpiUsability> NONE = Collections.emptyList();

    @Override
    public String toString() {
        return severity + " " + reason + ": " + sentence;
    }
}
