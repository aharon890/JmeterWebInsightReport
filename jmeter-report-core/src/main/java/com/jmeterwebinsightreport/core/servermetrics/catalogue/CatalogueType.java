package com.jmeterwebinsightreport.core.servermetrics.catalogue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A component type and its metrics — {@code postgres@1}, {@code kafka@1}.
 *
 * <p>Versioned because a metric's query must never change under an annotations file that names
 * it (plan §5): adding metrics to a version is compatible, renaming or removing one is the next
 * version. {@link #source()} names the published list the metrics were written from, for
 * whoever re-checks them by hand on an exporter's next major version — nothing compares the two
 * automatically: plan §5a's drift check was never built (SF46). {@link #exporter()} is the
 * prerequisite the Add component dialog names on the type's card.
 */
public final class CatalogueType {

    private final String key;
    private final int version;
    private final String label;
    private final String summary;
    private final String exporter;
    private final String source;
    private final Map<String, CatalogueMetric> metrics;

    CatalogueType(String key, int version, String label, String summary, String exporter, String source,
                  Map<String, CatalogueMetric> metrics) {
        this.key = key;
        this.version = version;
        this.label = label;
        this.summary = summary;
        this.exporter = exporter;
        this.source = source;
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    /** As written in an annotations file: {@code "type": "postgres"}. */
    public String key() { return key; }

    public int version() { return version; }

    /** {@code postgres@1}. */
    public String ref() { return key + "@" + version; }

    public String label() { return label; }

    /** One line for the type card: what it lets you judge. */
    public String summary() { return summary; }

    /**
     * What has to be scraping the component for these metrics to exist — {@code postgres_exporter}
     * — short, because the type card names it. A prerequisite the tester must satisfy, not a
     * record of how a run was collected, so it is not the provenance kept off the Server tab
     * (SF46). {@link #source()} is the longer, maintainer-facing note.
     */
    public String exporter() { return exporter; }

    /** The published metric list these were written from, and its version. */
    public String source() { return source; }

    /** By name, in catalogue order. */
    public Map<String, CatalogueMetric> metrics() { return metrics; }

    public CatalogueMetric metric(String name) {
        return name == null ? null : metrics.get(name.trim());
    }

    @Override
    public String toString() {
        return ref();
    }
}
