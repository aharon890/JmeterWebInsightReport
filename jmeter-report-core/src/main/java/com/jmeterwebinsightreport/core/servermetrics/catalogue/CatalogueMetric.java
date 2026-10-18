package com.jmeterwebinsightreport.core.servermetrics.catalogue;

import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.Across;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One metric a component type offers: what it means (label, description, unit, which way is
 * worse, how it is reduced by default), how each backend fetches it, and what each tool that
 * exports it calls it.
 *
 * <p>The meaning is shared by every backend; only the fetch differs. {@code queries} holds one
 * query per backend that has one; {@code names} holds, per tool ({@link Catalogue#TOOLS}), the
 * metric to look for in that tool's export and the kind of number it is ({@link ToolName}), or
 * an explicit {@code null} where the tool has no single-metric equivalent. A metric may have no
 * prometheus query at all — a KPI only an exported file can answer — and is then known by its
 * neutral {@link #identity}; a source that fetches by query says it cannot answer it rather
 * than collecting nothing in silence.
 *
 * <p>A query template may carry two slots for a component's optional {@code match} selector
 * (plan C5): {@code ${match}} where the metric has no selector of its own
 * ({@code pg_up${match}} → {@code pg_up{instance="db-1"}}), and {@code ${andMatch}} inside one it
 * already has ({@code {state="active"${andMatch}}}). Without a match both expand to nothing.
 */
public final class CatalogueMetric {

    /** Which way a value is worse. {@code NONE}: usually watched, not judged. */
    public enum Direction {
        HIGHER, LOWER, NONE;

        static Direction fromKey(String key) {
            if (key == null) return NONE;
            switch (key.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "higher": return HIGHER;
                case "lower": return LOWER;
                default: return NONE;
            }
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final String type;
    private final String name;
    private final String label;
    private final String category;
    private final String description;
    private final String unit;
    private final Reducer reducer;
    private final Across across;
    private final Direction direction;
    private final Double ceiling;
    private final String dimension;
    private final boolean recommended;
    private final Predicate safeWarn;
    private final Predicate safeFail;
    private final Map<String, String> queries;
    /** Tool → its name for this metric; a null value is the file's explicit "none". */
    private final Map<String, ToolName> names;

    CatalogueMetric(String type, String name, String label, String category, String description, String unit,
                    Reducer reducer, Across across, Direction direction, Double ceiling, String dimension,
                    boolean recommended, Predicate safeWarn, Predicate safeFail,
                    Map<String, String> queries, Map<String, ToolName> names) {
        this.type = type;
        this.name = name;
        this.label = label;
        this.category = category;
        this.description = description;
        this.unit = unit == null ? "" : unit;
        this.reducer = reducer;
        this.across = across == null ? Across.defaultValue() : across;
        this.direction = direction;
        this.ceiling = ceiling;
        this.dimension = dimension;
        this.recommended = recommended;
        this.safeWarn = safeWarn;
        this.safeFail = safeFail;
        this.queries = Collections.unmodifiableMap(new LinkedHashMap<>(queries));
        this.names = Collections.unmodifiableMap(new LinkedHashMap<>(names));
    }

    /** The key of the type it belongs to: {@code postgres}. */
    public String type() { return type; }

    public String name() { return name; }

    /** {@code postgres.connections.used.pct}: how a sentence about it names it. */
    public String qualifiedName() { return type + "." + name; }

    public String label() { return label; }
    public String category() { return category; }
    public String description() { return description; }
    public String unit() { return unit; }

    /** The reducer an assert gets when it names none of its own. */
    public Reducer reducer() { return reducer; }

    public Across across() { return across; }
    public Direction direction() { return direction; }

    /**
     * The most the metric can read, in its unit — 100 for a percentage, 1 for a 0/1 flag — or
     * null when it has no bound. The page never proposes an upward band at or past it (F2):
     * headroom taken from a 68 % peak alone proposed {@code fail > 102 %}, a band that can never
     * fire. A downward band may sit at it, as {@code up}'s safe {@code fail < 1} does.
     *
     * <p>Nothing judges by it, as nothing judges by {@link #direction()}: a hand-written band past
     * it is still judged as written, and only warned about ({@code KpiUsability}'s
     * BAND_ABOVE_CEILING WARNING, F2b, which reads it through {@code ServerKpi.ceiling()}). It is
     * read here, and checked at load, so that a malformed one
     * — not a number, on a slope (judged per minute, not in this unit), or below the file's own
     * safe default — is a build defect rather than a page that proposes from a wrong bound.
     */
    public Double ceiling() { return ceiling; }

    /** What one line of its chart is, in words: "per consumer group and topic". */
    public String dimension() { return dimension; }

    /** Offered first and starred in the picker. */
    public boolean recommended() { return recommended; }

    /**
     * Bands that hold whatever the hardware — an invariant such as "no offline partitions" —
     * or null. Everything else is seeded from the run, never shipped as a number.
     */
    public Predicate safeWarn() { return safeWarn; }
    public Predicate safeFail() { return safeFail; }

    /** Backend → query template, in catalogue order. Possibly empty: see {@link #identity}. */
    public Map<String, String> queries() { return queries; }

    /** The query for one backend with the match slots expanded, or null when it has none. */
    public String query(String backend, String match) {
        String template = queries.get(backend);
        return template == null ? null : expandMatch(template, match);
    }

    /**
     * What a series of this metric is known by — the key the collector writes it under and the
     * mapper, the evaluator, the payload writer and the page look it up by.
     *
     * <p>The prometheus query with the match expanded, when the metric has one: exactly
     * {@link #query query(PROMETHEUS, match)}, so no payload, golden or collectionId written
     * before this existed moves. Otherwise the neutral {@code <type>:<metric>}, the match trimmed
     * (as {@link #expandMatch} trims it) in braces when it is not blank —
     * {@code postgres:connections.used.pct{instance="db-1"}}: a KPI only an exported file can
     * answer, which no query language is asked for ({@link #neutral()}). The page's
     * {@code catalogueIdentity} writes the same string, character for character.
     */
    public String identity(String match) {
        String query = query(Catalogue.PROMETHEUS, match);
        if (query != null) {
            return query;
        }
        String m = match == null || match.trim().isEmpty() ? null : match.trim();
        return type + ":" + name + (m == null ? "" : "{" + m + "}");
    }

    /**
     * True when the metric has no prometheus query, so its {@link #identity} is the neutral one.
     * Prometheus and a {@code --recording} fetch by query and have nothing to send for it; a CSV
     * finds its rows by component and metric, and answers it like any other.
     */
    public boolean neutral() {
        return !queries.containsKey(Catalogue.PROMETHEUS);
    }

    /**
     * Tool → its name for this metric, for every tool that has one, in {@link Catalogue#TOOLS}
     * order. A tool the file says has none ({@link #declaresNone}) is not in it, and neither is
     * one the file does not mention.
     */
    public Map<String, ToolName> names() {
        Map<String, ToolName> out = new LinkedHashMap<>();
        for (String tool : Catalogue.TOOLS) {
            ToolName n = names.get(tool);
            if (n != null) {
                out.put(tool, n);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** The tool's name for this metric, or null — when it has none, and when the file does not say. */
    public ToolName name(String tool) {
        return names.get(tool);
    }

    /** Whether the file says anything about this tool: a name, or an explicit {@code null}. */
    public boolean declares(String tool) {
        return names.containsKey(tool);
    }

    /**
     * Whether the file says the tool has no single-metric equivalent of this KPI — an explicit
     * {@code null}. Known none, which is not the same as not declared: a reader can say "Telegraf
     * has no such metric" of the one and only "the catalogue does not know" of the other.
     */
    public boolean declaresNone(String tool) {
        return names.containsKey(tool) && names.get(tool) == null;
    }

    /** Expand the two match slots; a blank match removes them. */
    public static String expandMatch(String template, String match) {
        String m = match == null || match.trim().isEmpty() ? null : match.trim();
        return template
                .replace("${match}", m == null ? "" : "{" + m + "}")
                .replace("${andMatch}", m == null ? "" : "," + m);
    }

    @Override
    public String toString() {
        return name;
    }
}
