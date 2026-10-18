package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.AssertionValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole {@code serverMetrics} block, mapped: gates, sources, KPIs and the run-level
 * defaults. What M4's evaluator will read, and what M5's Server tab will be built from.
 *
 * <p>{@link #gatesDeclared()} is the fact behind the default-gate rule. While the file writes
 * no {@code gates} block, every KPI belongs to the implicit {@code server} gate ({@code
 * failsBuild: true}); once it writes one — even an empty one — an unnamed KPI is a config error
 * rather than a silent member of a default, the same reasoning as an undeclared gate name.
 *
 * <p>{@link #problems()} are config-LEVEL faults with no KPI to hang on: a retired block key
 * (a leftover {@code kpis}, the list's name before D25 made it {@code asserts}; a {@code packs}
 * key, which is v2 and never resolved at load, or the judged set becomes a function of the JAR
 * version), an unusable source, an unknown {@code defaultEnv}. They are ERROR findings under the id {@code serverMetrics}, and
 * M4's exit fold counts {@link #errorCount()} with the gate's config errors so the file cannot
 * ask for something that silently did not happen.
 */
public final class ServerMetricsConfig {

    /** The coverage floor a file that names none gets — canvas §4, 90%. */
    public static final double DEFAULT_COVERAGE_FLOOR = 0.9;

    private final Map<String, ServerGate> gates;
    private final boolean gatesDeclared;
    private final Map<String, ServerSource> sources;
    private final List<ServerKpi> kpis;
    private final Map<String, Map<String, String>> environments;
    private final String defaultEnv;
    private final String requiresLoad;
    private final double coverageFloor;
    private final boolean excludeRampUp;
    private final List<AssertionValidator.Finding> problems;

    private ServerMetricsConfig(Builder b) {
        this.gatesDeclared = b.gatesDeclared;
        Map<String, ServerGate> g = new LinkedHashMap<>(b.gates);
        if (!gatesDeclared) {
            g.put(ServerGate.DEFAULT_ID, ServerGate.implicit());
        }
        this.gates = Collections.unmodifiableMap(g);
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(b.sources));
        this.kpis = Collections.unmodifiableList(new ArrayList<>(b.kpis));
        Map<String, Map<String, String>> envs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : b.environments.entrySet()) {
            envs.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        this.environments = Collections.unmodifiableMap(envs);
        this.defaultEnv = b.defaultEnv;
        this.requiresLoad = b.requiresLoad;
        this.coverageFloor = b.coverageFloor;
        this.excludeRampUp = b.excludeRampUp;
        this.problems = Collections.unmodifiableList(new ArrayList<>(b.problems));
    }

    /**
     * Named environments and their {@code ${env.*}} variables, for interpolation into source
     * URLs and queries only — never into a band (delivery plan D18: a threshold is a literal
     * number, or one drag in the editor replaces the variable with whatever it resolved to).
     */
    public Map<String, Map<String, String>> environments() { return environments; }

    /** The environment used when none is selected, or {@code null}. */
    public String defaultEnv() { return defaultEnv; }

    /**
     * The block a file with no {@code serverMetrics} gets: no declared gates (the implicit
     * {@code server} gate is present, as for any file without a {@code gates} block), no
     * sources, no KPIs, no problems.
     */
    public static ServerMetricsConfig none() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Gates by id, the implicit {@code server} gate included while none are declared. */
    public Map<String, ServerGate> gates() { return gates; }

    /** True when the file wrote a {@code gates} block, even an empty one. */
    public boolean gatesDeclared() { return gatesDeclared; }

    public ServerGate gate(String id) { return gates.get(id); }

    /** Sources by id, unusable ones included (carrying their problem). */
    public Map<String, ServerSource> sources() { return sources; }

    public ServerSource source(String id) { return sources.get(id); }

    /** Every KPI in file order, the broken ones included — each is counted. */
    public List<ServerKpi> kpis() { return kpis; }

    /** The run-level {@code requiresLoad} default, or {@code null}. */
    public String requiresLoad() { return requiresLoad; }

    /** Fraction of the window that must carry data before a KPI is judged. */
    public double coverageFloor() { return coverageFloor; }

    /** Whether the window excludes {@code [startTime, rampUpEnd]} when the run declared a ramp-up. */
    public boolean excludeRampUp() { return excludeRampUp; }

    /** Config-level faults, as findings under the id {@code serverMetrics}. */
    public List<AssertionValidator.Finding> problems() { return problems; }

    /** How many config-level faults are errors — what M4's exit fold adds to the gate's config errors. */
    public int errorCount() {
        int n = 0;
        for (AssertionValidator.Finding f : problems) {
            if (f.severity() == AssertionValidator.Severity.ERROR) {
                n++;
            }
        }
        return n;
    }

    /** True when the file declared anything under {@code serverMetrics} worth judging. */
    public boolean declaresAnything() {
        return !kpis.isEmpty();
    }

    /**
     * This config with {@code source} as its one source — the collection's, built from the
     * collector's flags (C8). Everything else is carried as it is.
     */
    public ServerMetricsConfig withSource(ServerSource source) {
        Builder b = new Builder();
        b.gatesDeclared = gatesDeclared;
        for (ServerGate g : gates.values()) {
            if (gatesDeclared || !ServerGate.DEFAULT_ID.equals(g.id())) {
                b.gates.put(g.id(), g);
            }
        }
        b.sources.put(source.id(), source);
        b.kpis.addAll(kpis);
        b.environments.putAll(environments);
        b.defaultEnv = defaultEnv;
        b.requiresLoad = requiresLoad;
        b.coverageFloor = coverageFloor;
        b.excludeRampUp = excludeRampUp;
        b.problems.addAll(problems);
        return b.build();
    }

    public static final class Builder {
        private final Map<String, ServerGate> gates = new LinkedHashMap<>();
        private boolean gatesDeclared;
        private final Map<String, ServerSource> sources = new LinkedHashMap<>();
        private final List<ServerKpi> kpis = new ArrayList<>();
        private final Map<String, Map<String, String>> environments = new LinkedHashMap<>();
        private String defaultEnv;
        private String requiresLoad;
        private double coverageFloor = DEFAULT_COVERAGE_FLOOR;
        private boolean excludeRampUp = true;
        private final List<AssertionValidator.Finding> problems = new ArrayList<>();

        private Builder() {
        }

        /** Record that the file wrote a {@code gates} block, so the implicit gate is withheld. */
        public Builder gatesDeclared() { this.gatesDeclared = true; return this; }

        public Builder gate(ServerGate gate) {
            gatesDeclared = true;
            gates.put(gate.id(), gate);
            return this;
        }

        public Builder source(ServerSource source) { sources.put(source.id(), source); return this; }
        public Builder kpi(ServerKpi kpi) { kpis.add(kpi); return this; }
        public Builder environment(String name, Map<String, String> vars) {
            environments.put(name, vars == null ? new LinkedHashMap<String, String>() : vars);
            return this;
        }
        public Builder defaultEnv(String v) { this.defaultEnv = v; return this; }
        public Builder requiresLoad(String v) { this.requiresLoad = v; return this; }
        public Builder coverageFloor(double v) { this.coverageFloor = v; return this; }
        public Builder excludeRampUp(boolean v) { this.excludeRampUp = v; return this; }
        public Builder problem(AssertionValidator.Finding f) { problems.add(f); return this; }

        public ServerMetricsConfig build() {
            return new ServerMetricsConfig(this);
        }
    }
}
