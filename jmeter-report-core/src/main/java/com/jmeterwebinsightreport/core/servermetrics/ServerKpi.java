package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.Judged;
import com.jmeterwebinsightreport.core.assertion.Predicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One declared server-metric check: a query against a source, a reducer that folds the series
 * into one number, and the warn/fail bands that judge it — {@code serverMetrics.asserts[n]}, a
 * "server assert" to every reader (D25). The internal name stays, as {@code badge-pass} does.
 *
 * <p>Its own final type, deliberately not an {@code AssertionRule}. The client rule's subject
 * is a sampler target with a fixed metric vocabulary; this one's is a source and a free-form
 * query with a unit the author names. Forcing one into the other would need an extension
 * point at every {@code switch} that today hides a new kind behind a {@code default} arm. What
 * the two share is the {@link Judged} seam — an id, a description, an enabled flag — which is
 * exactly what a gate's arithmetic needs and nothing more.
 *
 * <p>Reducer × band, not nine assert types: see {@link Reducer}. {@code range} is two KPIs or
 * opposite-direction bands, exactly as the client's corridor migration did it.
 *
 * <p>{@code requiresLoad} names a <em>client</em> rule — the load-validity rule the file already
 * declares ({@code load:throughput}) — rather than repeating target and tolerance on every KPI.
 * One declaration of load. The mapper carries the reference verbatim; M4's evaluator resolves
 * it against the client rules, and only then does a FAIL on that rule make the KPI
 * NOT_JUDGED / LOAD_NOT_ACHIEVED, a NOT_JUDGED on it inherited, and a dangling reference
 * INVALID_RULE. {@code divideBy} is the ratio form, carried the same way: M4 divides the
 * reduced value by that load rule's measured value before the bands see it. Until M4 neither
 * is validated — a typo here maps with no finding.
 */
public final class ServerKpi implements Judged {

    private final String id;
    private final boolean idDeclared;
    private final String description;
    private final boolean enabled;
    private final String source;
    private final String query;
    private final String unit;
    private final Reducer reducer;
    private final Across across;
    private final String requiresLoad;
    private final String divideBy;
    private final Predicate warn;
    private final Predicate fail;
    private final String gate;
    private final String pack;
    private final String metric;
    private final Double ceiling;
    private final List<KpiUsability> declarationProblems;
    private final String declarationLabel;

    private ServerKpi(Builder b) {
        this.metric = blankToNull(b.metric);
        this.ceiling = b.ceiling != null && Double.isFinite(b.ceiling) ? b.ceiling : null;
        this.source = b.source;
        // Trimmed here, the one place every reader goes through - the mapper, the collector's
        // series key and request, the evaluator's lookup, the payload writer, the CI/CD JSON and
        // JUnit - as every other text field of an assert already was. A query is a series'
        // identity (plan §7a): kept padded, "lag " asked a CSV for rows nobody had written, found
        // none, and the assert became a NO_MATCH that never fails a build (U7). String.trim, so
        // only what is <= U+0020; any other invisible character at an end - a no-break space, a
        // zero-width, bidi or tag character, a C1 control (KpiUsability.isInvisible's table) - is
        // not trimmed and KpiUsability warns about it instead, because the page trims exactly as
        // this does.
        this.query = b.query == null ? null : b.query.trim();
        this.unit = b.unit == null ? "" : b.unit.trim();
        this.reducer = b.reducer;
        this.across = b.across == null ? Across.defaultValue() : b.across;
        this.requiresLoad = blankToNull(b.requiresLoad);
        this.divideBy = blankToNull(b.divideBy);
        this.warn = b.warn;
        this.fail = b.fail;
        this.gate = b.gate;
        this.pack = blankToNull(b.pack);
        this.enabled = b.enabled;
        this.description = b.description == null ? "" : b.description;
        this.declarationProblems = b.declarationProblems == null
                ? KpiUsability.NONE
                : Collections.unmodifiableList(new ArrayList<>(b.declarationProblems));
        this.idDeclared = b.id != null && !b.id.trim().isEmpty();
        this.id = this.idDeclared ? b.id.trim() : deriveId();
        this.declarationLabel = blankToNull(b.declarationLabel) == null ? this.id : b.declarationLabel.trim();
    }

    /**
     * {@code slug(source)-slug(query)}, for a KPI the author did not name.
     *
     * <p>Ids reach the CI/CD JSON, JUnit testcase names and every failure message, so a derived
     * one has to be stable across runs and readable in a CI log. A query is long and full of
     * braces; the slug keeps its first few words.
     */
    private String deriveId() {
        // A catalogue assert is named after what it judges, which is short and stable:
        // orders-db-connections-used-pct.
        if (metric != null) {
            return (gate == null ? "component" : slug(gate)) + "-" + slug(metric);
        }
        String s = gate != null && !ServerGate.DEFAULT_ID.equals(gate) ? slug(gate)
                : source == null ? "source" : slug(source);
        String q = query == null ? "query" : slug(query);
        if (q.length() > 40) {
            q = q.substring(0, 40).replaceAll("-+$", "");
        }
        return s + "-" + q;
    }

    private static String slug(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "x" : cleaned;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public boolean enabled() { return enabled; }

    /** Whether the AUTHOR named this KPI — a derived id must never be written back. */
    public boolean idDeclared() { return idDeclared; }

    /** The source id as written; resolved against {@code serverMetrics.sources} by the mapper. */
    public String source() { return source; }

    /**
     * The query as written, trimmed of surrounding whitespace ({@link String#trim}), with
     * {@code ${env.*}} and friends unresolved. Never padded, so never a different series than
     * the same text without the spaces.
     */
    public String query() { return query; }

    /**
     * The SERIES' unit: what the collected values are in, as the author (or the catalogue) named
     * it — {@code B} for a database's size, even on a {@code slope} assert about its growth. It is
     * what the CI/CD JSON's {@code unit} carries, a consumed contract, and what a chart of the raw
     * series is labelled in. A verdict's number is not always in it: see {@link #verdictUnit()}.
     * Never null; empty when none.
     */
    public String unit() { return unit; }

    /**
     * The unit a VERDICT number is in — {@code actual}, the bands and the band that breached —
     * wherever one is written for a reader: JUnit's message, {@code criteria:} and {@code actual:},
     * and every {@link KpiUsability} sentence that quotes a band. The page's {@code verdictUnit}
     * mirrors this definition, character for character (SF16).
     *
     * <ul>
     *   <li>The declared {@link #unit()}, for every reducer that keeps the series' level.</li>
     *   <li>{@code /min} appended for a {@code slope}, which the evaluator takes in units per
     *       minute: {@code B} becomes {@code B/min}. With no unit it is {@code /min} alone, the
     *       catalogue's own spelling of a bare count per minute ({@code postgres}
     *       {@code deadlocks}). The suffix is appended whatever the unit says, so an author who
     *       already wrote {@code B/min} on a slope reads {@code B/min/min}: {@code unit} is the
     *       series' unit, and Tutorial 13 says so.</li>
     *   <li>Then, for a {@code divideBy} ratio, {@code " ÷ <rule id>"}, as the board and the
     *       chart's axis write it: {@code % ÷ load:throughput}, {@code B/min ÷ load:throughput},
     *       or {@code ÷ load:throughput} alone when there is no unit to divide.</li>
     * </ul>
     *
     * <p>An unreadable reducer ({@code null}) keeps the declared unit: nothing says it is a rate.
     */
    public String verdictUnit() {
        String verdict = reducer != null && reducer.kind() == Reducer.Kind.SLOPE ? unit + "/min" : unit;
        if (divideBy == null) {
            return verdict;
        }
        return (verdict.isEmpty() ? "" : verdict + " ") + "÷ " + divideBy;
    }

    /** {@code null} when the file's {@code reduce} could not be read — a declaration problem. */
    public Reducer reducer() { return reducer; }

    public Across across() { return across; }

    /** The client load rule this KPI depends on, or {@code null}. */
    public String requiresLoad() { return requiresLoad; }

    /** The client load rule whose measured value divides the reduced value, or {@code null}. */
    public String divideBy() { return divideBy; }

    public Predicate warn() { return warn; }
    public Predicate fail() { return fail; }

    /** True when the KPI declares at least one USABLE band. Without one it can never decide. */
    public boolean hasBands() {
        return warn != null || fail != null;
    }

    /**
     * The gate id as the mapper resolved it: the implicit {@code server} while the file declares
     * no gates, else the name the author wrote (a typo included — the KPI is refused, but it is
     * reported under the name it asked for). {@code null} exactly when the file declared
     * {@code gates} and this KPI named none: an UNDECLARED_GATE declaration problem, so the KPI
     * is NOT_JUDGED / INVALID_RULE and M4 counts it with the block-level config errors rather
     * than under any gate.
     */
    public String gate() { return gate; }

    /** The pack this KPI was materialised from ({@code "kafka@1"}), or {@code null}. */
    public String pack() { return pack; }

    /**
     * The catalogue metric this assert judges ({@code connections.used.pct}), or {@code null}
     * for a custom query (C9). When set, {@link #query()} is the catalogue's query for the
     * collection's backend with the component's match expanded — resolved by the mapper, so
     * everything downstream reads a query either way.
     */
    public String metric() { return metric; }

    /**
     * The declared ceiling of the catalogue {@link #metric()} this assert judges
     * ({@code CatalogueMetric.ceiling()}: 100 on a percentage, 1 on {@code up}), or {@code null}
     * for a custom query or a metric with none. Set by the mapper and read by
     * {@link KpiUsability#check} alone (F2b): nothing judges by it, so a band past it is still
     * judged as written, and only warned about.
     */
    public Double ceiling() { return ceiling; }

    /**
     * What the loader could not make sense of, in the words it used at the time — an unreadable
     * band, a retired key, an unknown source, an undeclared gate, an unreadable reducer. Same
     * reasoning as {@code AssertionRule.declarationProblems()}: by the time a KPI object exists
     * these faults are indistinguishable from absence, so they ride the model.
     *
     * <p>Never null; empty for the overwhelming majority of KPIs.
     */
    public List<KpiUsability> declarationProblems() {
        return declarationProblems;
    }

    /**
     * What the mapper files this assert's {@link #declarationProblems()} under: the id the author
     * wrote, else its position, {@code serverMetrics.asserts[2]} — never the derived {@link #id()},
     * which the page cannot match to a row once the assert is deleted (F15). The same as
     * {@link #id()} for a KPI built without one.
     *
     * <p>So the evaluator's refusal of an id-less assert, filed under the derived id, is the same
     * finding as the mapper's under this label, and is said once (SF21). Two keys for one mistake
     * used to be two findings.
     */
    public String declarationLabel() {
        return declarationLabel;
    }

    /**
     * Human-readable criteria in the {@link #verdictUnit()}, e.g.
     * {@code "warn > 640 msgs  ·  fail > 800 msgs"}, or {@code "fail > 50000000 B/min"} on a slope.
     */
    public String describeCriteria() {
        String verdictUnit = verdictUnit();
        StringBuilder sb = new StringBuilder();
        if (warn != null) {
            sb.append("warn ").append(warn.describe(verdictUnit));
        }
        if (fail != null) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("fail ").append(fail.describe(verdictUnit));
        }
        return sb.toString();
    }

    public static Builder builder(String source, String query) {
        return new Builder(source, query);
    }

    public static final class Builder {
        private final String source;
        private final String query;
        private String id;
        private String description;
        private boolean enabled = true;
        private String unit;
        private Reducer reducer;
        private Across across;
        private String requiresLoad;
        private String divideBy;
        private Predicate warn;
        private Predicate fail;
        private String gate;
        private String pack;
        private String metric;
        private Double ceiling;
        private List<KpiUsability> declarationProblems;
        private String declarationLabel;

        private Builder(String source, String query) {
            this.source = source;
            this.query = query;
        }

        public Builder id(String v) { this.id = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder unit(String v) { this.unit = v; return this; }
        public Builder reducer(Reducer v) { this.reducer = v; return this; }
        public Builder across(Across v) { this.across = v; return this; }
        public Builder requiresLoad(String v) { this.requiresLoad = v; return this; }
        public Builder divideBy(String v) { this.divideBy = v; return this; }
        public Builder warn(Predicate v) { this.warn = v; return this; }
        public Builder fail(Predicate v) { this.fail = v; return this; }
        public Builder gate(String v) { this.gate = v; return this; }
        public Builder pack(String v) { this.pack = v; return this; }
        public Builder metric(String v) { this.metric = v; return this; }
        public Builder ceiling(Double v) { this.ceiling = v; return this; }

        /** The label the mapper reports this assert's problems under; see {@link ServerKpi#declarationLabel()}. */
        public Builder declarationLabel(String v) { this.declarationLabel = v; return this; }

        /** Record something the loader could not make sense of. */
        public Builder declarationProblem(KpiUsability problem) {
            if (problem != null) {
                if (declarationProblems == null) {
                    declarationProblems = new ArrayList<>(2);
                }
                declarationProblems.add(problem);
            }
            return this;
        }

        public ServerKpi build() {
            return new ServerKpi(this);
        }
    }

    @Override
    public String toString() {
        return id + " [" + source + " " + (reducer == null ? "?" : reducer.describe())
                + " " + describeCriteria() + " → " + gate + "]";
    }
}
