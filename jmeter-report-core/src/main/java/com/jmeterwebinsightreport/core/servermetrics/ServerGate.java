package com.jmeterwebinsightreport.core.servermetrics;

/**
 * A server component (plans/server-metrics-components.md, C1): a {@code serverMetrics.components}
 * entry — {@code "orders-db": {"type": "postgres"}} — the group of server asserts judged together,
 * with its own verdict and its own JUnit suite, the {@link #type()} that picks its metric
 * catalogue, and an optional {@link #match()} selector. The internal name stays "gate", as
 * {@code ServerKpi} kept its own after D25.
 *
 * <p>The client gate is fixed and singular and is not one of these. Server components are
 * plural and declared, so a Kafka failure and a database failure are two JUnit suites instead of
 * one word. There is no combined verdict anywhere.
 *
 * <p><b>Every component builds</b> (C6). There is no {@code failsBuild} and no observe-only
 * component: a metric that should only be watched gets a warn band and no fail band.
 */
public final class ServerGate {

    /** The implicit component every assert belonged to before components were declared. */
    public static final String DEFAULT_ID = "server";

    private final String id;
    private final String label;
    private final String type;
    private final String match;

    private ServerGate(String id, String label, String type, String match) {
        this.id = id;
        this.label = label == null || label.trim().isEmpty() ? id : label.trim();
        this.type = type == null || type.trim().isEmpty() ? null : type.trim();
        this.match = match == null || match.trim().isEmpty() ? null : match.trim();
    }

    /** A gate with no type — the tests' shorthand, and the implicit one. */
    public static ServerGate of(String id, String label) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("a server gate needs an id");
        }
        return new ServerGate(id.trim(), label, null, null);
    }

    /**
     * A declared component: {@code type} is a catalogue type or {@code custom}, {@code match} the
     * optional selector that tells two components of one type apart (C5).
     */
    public static ServerGate component(String id, String description, String type, String match) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("a component needs an id");
        }
        return new ServerGate(id.trim(), description, type, match);
    }

    /** The component type as written ({@code postgres}, {@code kafka}, {@code custom}), or null. */
    public String type() { return type; }

    /** The component's selector, {@code instance="orders-db:5432"}, or null when it has none. */
    public String match() { return match; }

    /** The implicit component, used only while the file declares none. */
    public static ServerGate implicit() {
        return new ServerGate(DEFAULT_ID, "Server", null, null);
    }

    public String id() { return id; }

    /** Never blank; falls back to the id. */
    public String label() { return label; }

    @Override
    public String toString() {
        return id + (type == null ? "" : " [" + type + "]");
    }
}
