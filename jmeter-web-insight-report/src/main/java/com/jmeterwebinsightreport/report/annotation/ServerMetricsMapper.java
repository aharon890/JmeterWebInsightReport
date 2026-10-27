package com.jmeterwebinsightreport.report.annotation;

import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.Across;
import com.jmeterwebinsightreport.core.servermetrics.Durations;
import com.jmeterwebinsightreport.core.servermetrics.KpiUsability;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;
import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerKpi;
import com.jmeterwebinsightreport.core.servermetrics.ServerMetricsConfig;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueMetric;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.CatalogueType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the {@code serverMetrics} block into a {@code ServerMetricsConfig}, collecting what is
 * wrong rather than refusing to continue — the server-side {@link AssertionRuleMapper}, under
 * the same discipline.
 *
 * <p>A file with one bad KPI among nine still yields the other eight and still produces a
 * report, which is the artifact you need in order to fix the bad one. Every fault is recorded
 * twice on purpose and for two different jobs: as a finding (how the author is told, against
 * the file's own label) and as a declaration problem on the KPI (what stops it being judged —
 * findings do not gate).
 *
 * <p>Three rules that are not derivable from a finished KPI and therefore live here:
 * <ul>
 *   <li><b>The default gate.</b> While the file declares no {@code gates}, every KPI belongs to
 *       {@code server}. Once it declares any, an unnamed or unknown gate is INVALID_RULE — a
 *       typo'd gate must not silently become a gate of its own.</li>
 *   <li><b>Secrets by reference.</b> An {@code auth} key must end in {@code Env} and name an
 *       environment variable; a credential in a URL is a {@code ${secret.NAME}}. A literal — in
 *       auth, in userinfo, in a credential-named query parameter, under a secret-looking source
 *       key — makes the SOURCE unusable, and every KPI on it INVALID_RULE with that sentence; in
 *       an {@code environments.*.vars} value it is a config-level problem. The document travels
 *       into every artifact, masked by {@link ServerMetricsJson#publishable()} in the same
 *       shapes.</li>
 *   <li><b>Retired block keys.</b> {@code kpis} is what {@code asserts} was called before D25;
 *       nothing was released under it, so there is no migration — a leftover {@code kpis} is a
 *       config-level error naming {@code asserts}, and is never read as the list (a file that
 *       still says {@code kpis} must not pass as one that declared nothing). {@code packs} is v2
 *       (D26): refused, saying so, and never resolved at load — that would make the judged set a
 *       function of the JAR version, which the project already lived through once with
 *       {@code loadValidity}.</li>
 * </ul>
 *
 * <p>Faults with no KPI to hang on — an unusable source, an unknown {@code defaultEnv}, a
 * retired block key — are recorded as config-level problems as well as findings, so they count
 * even when no KPI names the thing that is wrong (a literal secret on a source nothing uses is
 * still a literal secret). The one fault this class does not collect is shape: a container
 * written as the wrong JSON type fails Jackson before the mapper runs and is a whole-file load
 * problem, see {@link ServerMetricsJson}.
 */
public final class ServerMetricsMapper {

    /** What the mapping produced, and everything it could not accept. */
    public static final class Result {
        private final ServerMetricsConfig config;
        private final List<AssertionValidator.Finding> findings;

        Result(ServerMetricsConfig config, List<AssertionValidator.Finding> findings) {
            this.config = config;
            this.findings = findings;
        }

        public ServerMetricsConfig config() { return config; }
        public List<AssertionValidator.Finding> findings() { return Collections.unmodifiableList(findings); }
    }

    /** The finding id for faults with no KPI or source to hang on. */
    public static final String BLOCK_ID = "serverMetrics";

    /**
     * Gate ids the outputs have already spent: {@code assertions} is the client rule suite,
     * {@code (no gate)} is the orphan suite and the orphans' {@code serverGates[]} entry in the
     * CI/CD JSON ({@code ServerGateEvaluation.NO_GATE_ID}). Both suites are written as
     * {@code "<testName> - <id>"}.
     */
    private static final Set<String> RESERVED_GATE_IDS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("assertions", "(no gate)")));

    private final Catalogue catalogue;

    public ServerMetricsMapper() {
        this(Catalogue.builtIn());
    }

    ServerMetricsMapper(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    public Result map(ServerMetricsJson json) {
        List<AssertionValidator.Finding> findings = new ArrayList<>();
        if (json == null) {
            return new Result(ServerMetricsConfig.none(), findings);
        }
        ServerMetricsConfig.Builder config = ServerMetricsConfig.builder();

        if (json.getUnreadable() != null) {
            // The block could not be bound; the rest of the file was. One block problem, and
            // nothing under it is read — guessing at a half-bound block would judge something the
            // file did not say.
            AssertionValidator.Finding f = error(BLOCK_ID, json.getUnreadable());
            findings.add(f);
            config.problem(f);
            return new Result(config.build(), findings);
        }

        retiredBlockKeys(json, config, findings);
        retiredSources(json, config, findings);
        readComponents(json, config, findings);
        readEnvironments(json, config, findings);
        readCollection(json, config, findings);
        config.requiresLoad(blankToNull(json.getRequiresLoad()));

        // Build once so the KPI pass can resolve sources and gates against what was declared.
        ServerMetricsConfig declared = config.build();
        List<ServerKpi> kpis = new ArrayList<>();
        if (json.getAsserts() != null) {
            int index = 0;
            for (ServerMetricsJson.KpiJson k : json.getAsserts()) {
                ServerKpi kpi = readKpi(k, index++, declared, findings);
                kpis.add(kpi);
                config.kpi(kpi);
            }
        }
        sharedIds(kpis, findings);
        return new Result(config.build(), findings);
    }

    /**
     * A WARNING for every id two or more server asserts are judged under — written twice, padded
     * apart, or derived twice from one source and query (R2-2, R3-8).
     *
     * <p>A warning and not a refusal, which is the lenient call M7c made and D27 keeps: each assert
     * is still judged on its own and in order, so nothing is decided wrongly. What breaks is the
     * NAME — the CI/CD JSON's {@code serverAsserts[]}, the JUnit testcases and every finding print
     * the id, and two entries carrying one cannot be told apart by a reader or a jq line. Refusing
     * would turn a naming collision into a non-zero exit. One finding per shared id, in the order
     * each id first appears; report.js {@code duplicateIdFindings} says the same for the document
     * the page holds, and the parity vectors' document cases pin both.
     */
    private static void sharedIds(List<ServerKpi> kpis, List<AssertionValidator.Finding> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ServerKpi kpi : kpis) {
            counts.merge(kpi.id(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                findings.add(AssertionValidator.finding(AssertionValidator.Severity.WARNING, e.getKey(),
                        sharedIdText(e.getKey(), e.getValue())));
            }
        }
    }

    static String sharedIdText(String id, int count) {
        return count + " server asserts are judged under the id '" + id + "', so the CI/CD JSON, the "
                + "JUnit report and every finding name them alike and cannot tell them apart. Give each its "
                + "own \"id\" (an assert written without one is named after its component and its metric or query)";
    }

    // ---- block level ------------------------------------------------------------

    /**
     * Keys the block no longer takes, and the sentence each is refused with — in this order,
     * whatever the file's. Present with any value, {@code null} and {@code {}} included: Jackson's
     * any-setter records the key either way, and a shape check on a key nobody reads would turn a
     * rename into a whole-file load failure. The browser's {@code RETIRED_BLOCK_KEYS} is this map,
     * in these words, and {@code server-parity-vectors.json}'s {@code blockCases} pin both.
     */
    static final Map<String, String> RETIRED_BLOCK_KEYS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kpis", "\"kpis\" is not a serverMetrics key — server asserts are declared under "
                + "serverMetrics.asserts. Rename \"kpis\" to \"asserts\": nothing under \"kpis\" is "
                + "read, so none of the asserts written there is judged.");
        m.put("packs", "\"packs\" is not available in this version — declare each server assert "
                + "under serverMetrics.asserts directly. Nothing under \"packs\" is read, so a pack "
                + "named here judges nothing.");
        m.put("gates", "\"gates\" is not a serverMetrics key — what a gate grouped is now a "
                + "component: \"components\": {\"orders-db\": {\"type\": \"postgres\"}}, and each "
                + "server assert names its \"component\". Nothing under \"gates\" is read.");
        RETIRED_BLOCK_KEYS = Collections.unmodifiableMap(m);
    }

    private void retiredBlockKeys(ServerMetricsJson json, ServerMetricsConfig.Builder config,
                                  List<AssertionValidator.Finding> findings) {
        Map<String, Object> unknown = json.getUnknownKeys();
        if (unknown == null) {
            return;
        }
        for (Map.Entry<String, String> retired : RETIRED_BLOCK_KEYS.entrySet()) {
            if (unknown.containsKey(retired.getKey())) {
                AssertionValidator.Finding f = error(BLOCK_ID, retired.getValue());
                findings.add(f);
                config.problem(f);
            }
        }
    }

    /**
     * {@code serverMetrics.components} (plan C1): each becomes the group, the verdict and the
     * JUnit suite a gate was, carrying its type and optional match. Always building (C6).
     *
     * <p>An unknown {@code type} is a config-level problem here, once, and every assert on the
     * component is refused with the same sentence in {@link #readKpi} — the route an unusable
     * source took. The component is kept so its asserts get that sentence rather than "is not
     * declared", which is a different fault with a different fix.
     */
    private void readComponents(ServerMetricsJson json, ServerMetricsConfig.Builder config,
                                List<AssertionValidator.Finding> findings) {
        Map<String, ServerMetricsJson.ComponentJson> components = json.getComponents();
        // Declared even when absent: there is no implicit component. An assert must name one.
        config.gatesDeclared();
        if (components == null) {
            return;
        }
        for (Map.Entry<String, ServerMetricsJson.ComponentJson> e : components.entrySet()) {
            String id = e.getKey() == null ? "" : e.getKey().trim();
            String label = BLOCK_ID + ".components." + (id.isEmpty() ? "?" : id);
            if (id.isEmpty()) {
                AssertionValidator.Finding f = error(BLOCK_ID, "a component with an empty id was ignored");
                findings.add(f);
                config.problem(f);
                continue;
            }
            // The JUnit writer names a suite "<testName> - <componentId>", and two suites with one
            // name is a document a CI parser reads as a single suite with the wrong counts.
            if (RESERVED_GATE_IDS.contains(id)) {
                AssertionValidator.Finding f = error(BLOCK_ID, "component id \"" + id + "\" is reserved - "
                        + "the JUnit report already names a suite that, and two suites with one name "
                        + "cannot be told apart. Rename the component");
                findings.add(f);
                config.problem(f);
                continue;
            }
            ServerMetricsJson.ComponentJson c = e.getValue();
            String type = c == null ? null : blankToNull(c.getType());
            if (type == null) {
                AssertionValidator.Finding f = error(label, "no type — one of " + catalogue.vocabulary());
                findings.add(f);
                config.problem(f);
            } else if (!catalogue.knows(type)) {
                AssertionValidator.Finding f = error(label, "type '" + type + "' is not one of "
                        + catalogue.vocabulary());
                findings.add(f);
                config.problem(f);
            }
            if (c != null && c.getUnknownKeys().containsKey("failsBuild")) {
                AssertionValidator.Finding f = error(label, "\"failsBuild\" is not a component key — "
                        + "every component can fail the build. To watch a metric without failing the "
                        + "build, give it only a warn band");
                findings.add(f);
                config.problem(f);
            }
            ServerGate component = ServerGate.component(id, c == null ? null : c.getDescription(), type,
                    c == null ? null : c.getMatch());
            // A match padded with an invisible character is in every catalogue query of the
            // component (F8-residual): a WARNING under the component, beside its own problems and
            // never one of them - config.problem() is what failonerror counts. A custom component
            // never expands its match, and an unknown type has no queries to put it in.
            String padded = catalogue.type(type) == null ? null : KpiUsability.matchPaddingText(component.match());
            if (padded != null) {
                findings.add(AssertionValidator.finding(AssertionValidator.Severity.WARNING, label, padded));
            }
            config.gate(component);
        }
    }

    /**
     * A {@code sources} block is retired (C8): the connection is the collector's, given on its
     * command line, and a report reads only the file the collector wrote. Refused whatever it
     * holds, and never read — a source in the file must not quietly take effect anywhere.
     */
    private void retiredSources(ServerMetricsJson json, ServerMetricsConfig.Builder config,
                                List<AssertionValidator.Finding> findings) {
        if (json.getSources() == null) {
            return;
        }
        AssertionValidator.Finding f = error(BLOCK_ID, "\"sources\" is not a serverMetrics key — "
                + "where the metrics are fetched from is given to wir-collect, never written here: "
                + "--prometheus-url URL (with --prometheus-token-env NAME), --recording DIR, or --csv FILE. "
                + "Nothing under \"sources\" is read");
        findings.add(f);
        config.problem(f);
    }



    private void readEnvironments(ServerMetricsJson json, ServerMetricsConfig.Builder config,
                                  List<AssertionValidator.Finding> findings) {
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        if (json.getEnvironments() != null) {
            for (Map.Entry<String, ServerMetricsJson.EnvironmentJson> e : json.getEnvironments().entrySet()) {
                if (e.getKey() == null || e.getKey().trim().isEmpty()) {
                    AssertionValidator.Finding f = error(BLOCK_ID + ".environments",
                            "an environment with an empty name was ignored");
                    findings.add(f);
                    config.problem(f);
                    continue;
                }
                String name = e.getKey().trim();
                Map<String, String> vars = new LinkedHashMap<>();
                if (e.getValue() != null && e.getValue().getVars() != null) {
                    for (Map.Entry<String, String> v : e.getValue().getVars().entrySet()) {
                        if (v.getValue() == null) {
                            // "${env.ns}" resolving to the text "null" inside a query is a
                            // silent wrong answer; a missing variable is a loud one (M3).
                            findings.add(AssertionValidator.finding(AssertionValidator.Severity.WARNING,
                                    BLOCK_ID + ".environments." + name,
                                    "vars." + v.getKey() + " is null and was ignored"));
                            continue;
                        }
                        // A var is a URL's other way in: "url": "${env.PROM}" with PROM holding
                        // https://bob:pw@host is the userinfo the source-URL check refuses, one
                        // indirection away, and the vars are published with the block. Refused as
                        // a config problem - the var is still kept, so a source using it reports
                        // this, not a second "is not a variable". publishable() masks the value.
                        String credential = ServerMetricsJson.varCredential(v.getValue());
                        if (credential != null) {
                            AssertionValidator.Finding f = error(BLOCK_ID + ".environments." + name,
                                    "vars." + v.getKey() + " carries " + credential + " — environments are "
                                            + "written into every report and sidecar, so a literal here would be "
                                            + "published. Put the credential in auth.<name>Env, or in the source "
                                            + "url as ${secret.NAME}");
                            findings.add(f);
                            config.problem(f);
                        }
                        vars.put(v.getKey(), v.getValue());
                    }
                }
                config.environment(name, vars);
                declared.add(name);
            }
        }
        String defaultEnv = blankToNull(json.getDefaultEnv());
        if (defaultEnv != null && !declared.contains(defaultEnv)) {
            AssertionValidator.Finding f = error(BLOCK_ID + ".defaultEnv", "'" + defaultEnv
                    + "' is not one of the declared environments"
                    + (declared.isEmpty() ? " — none are declared" : ": " + String.join(", ", declared)));
            findings.add(f);
            config.problem(f);
        }
        config.defaultEnv(defaultEnv);
    }

    private void readCollection(ServerMetricsJson json, ServerMetricsConfig.Builder config,
                                List<AssertionValidator.Finding> findings) {
        ServerMetricsJson.CollectionJson c = json.getCollection();
        if (c == null) {
            return;
        }
        if (c.getCoverageFloor() != null) {
            Double floor = asNumber(c.getCoverageFloor());
            if (floor == null || floor < 0 || floor > 1) {
                // Warn and keep the default, as ComparisonThresholdResolver does for a bad
                // threshold: a malformed knob must not silently switch coverage checking off.
                findings.add(AssertionValidator.finding(AssertionValidator.Severity.WARNING,
                        BLOCK_ID + ".collection", "coverageFloor must be a fraction 0..1, not "
                                + describe(c.getCoverageFloor()) + " — using the default "
                                + ServerMetricsConfig.DEFAULT_COVERAGE_FLOOR));
            } else {
                config.coverageFloor(floor);
            }
        }
        if (c.getExcludeRampUp() != null) {
            config.excludeRampUp(c.getExcludeRampUp());
        }
    }

    // ---- KPIs -------------------------------------------------------------------

    private ServerKpi readKpi(ServerMetricsJson.KpiJson k, int index, ServerMetricsConfig declared,
                              List<AssertionValidator.Finding> findings) {
        String label = !blank(k.getId()) ? k.getId().trim() : BLOCK_ID + ".asserts[" + index + "]";

        // The component, its type's catalogue, and the metric — resolved first, because the
        // query, unit, reducer and across all default from the metric.
        String componentId = blankToNull(k.getComponent());
        ServerGate component = componentId == null ? null : declared.gate(componentId);
        CatalogueType type = component == null ? null : catalogue.type(component.type());
        String metricName = blankToNull(k.getMetric());
        CatalogueMetric metric = type == null || metricName == null ? null : type.metric(metricName);
        boolean hasQuery = !blank(k.getQuery());

        // The query is the identity of the series whatever the backend (plan §7a): written for a
        // custom assert, the catalogue's for a metric — its prometheus query with the component's
        // match expanded, or, for a metric with none, its neutral <type>:<metric> identity, which
        // only a CSV can answer (CatalogueMetric.identity). ServerKpi trims it (U7); it is passed
        // here as written.
        String query = hasQuery ? k.getQuery()
                : metric != null ? metric.identity(component.match()) : null;

        ServerKpi.Builder b = ServerKpi.builder(ServerSource.COLLECTION_ID, query)
                .id(k.getId())
                // Every problem below is filed under this label, and the KPI carries it so the
                // evaluator's refusal of the same assert is recognised as the same finding (SF21).
                .declarationLabel(label)
                .description(k.getDescription())
                .enabled(!Boolean.FALSE.equals(k.getEnabled()))
                .unit(blank(k.getUnit()) && metric != null ? metric.unit() : k.getUnit())
                .requiresLoad(blankToNull(k.getRequiresLoad()))
                .divideBy(blankToNull(k.getDivideBy()))
                .pack(blankToNull(k.getPack()))
                .metric(metricName)
                // Read by KpiUsability alone, which warns about a band past it (F2b); nothing judges by it.
                .ceiling(metric != null ? metric.ceiling() : null)
                // The name it asked for, a typo included — refused below, but reported under it.
                .gate(componentId);

        String declaredIds = String.join(", ", declared.gates().keySet());
        if (componentId == null) {
            problem(b, label, findings, KpiUsability.undeclaredComponent(null, declaredIds));
        } else if (component == null) {
            problem(b, label, findings, KpiUsability.undeclaredComponent(componentId, declaredIds));
        } else if (component.type() == null || !catalogue.knows(component.type())) {
            problem(b, label, findings, KpiUsability.unknownType(componentId,
                    component.type() == null ? "(none)" : component.type(), catalogue.vocabulary()));
        } else {
            boolean custom = Catalogue.CUSTOM.equals(component.type());
            if (metricName != null && hasQuery) {
                problem(b, label, findings, KpiUsability.metricOrQuery("declares both \"metric\" and "
                        + "\"query\" — a catalogue metric brings its own query; write one or the other"));
            } else if (metricName == null && !hasQuery) {
                problem(b, label, findings, KpiUsability.metricOrQuery(custom
                        ? "names no \"query\" — every server assert on a custom component writes its own"
                        : "names neither a \"metric\" from " + type.ref() + " nor a \"query\""));
            } else if (metricName != null && custom) {
                problem(b, label, findings, KpiUsability.metricOrQuery("\"metric\" names a catalogue "
                        + "metric, and component '" + componentId + "' is custom, which has no catalogue "
                        + "— write a \"query\""));
            } else if (metricName != null && metric == null) {
                problem(b, label, findings, KpiUsability.unknownMetric(metricName, type.ref(),
                        String.join(", ", type.metrics().keySet())));
            }
        }

        // A catalogue metric brings its reducer; a reduce written on the assert overrides it. An
        // assert that names a metric and no reduce asked for the catalogue's: when the metric did
        // not resolve, that is already refused above, and "reduce.kind is missing" beside it would
        // be two findings for one mistake.
        if (k.getReduce() == null && metricName != null) {
            if (metric != null) {
                b.reducer(metric.reducer());
            }
        } else {
            b.reducer(readReducer(k.getReduce(), label, findings, b));
        }

        if (blank(k.getAcross()) && metric != null) {
            b.across(metric.across());
        } else if (!blank(k.getAcross())) {
            Across across = Across.fromKey(k.getAcross());
            if (across == null) {
                problem(b, label, findings, KpiUsability.unreadableField("across",
                        "'" + k.getAcross() + "' is not one of " + Across.vocabulary()));
            } else {
                b.across(across);
            }
        }

        b.warn(readBand(k.getWarn(), "warn", label, findings, b));
        b.fail(readBand(k.getFail(), "fail", label, findings, b));

        retiredKpiKeys(k, label, findings, b);

        // Unknown keys are ignored in silence, for the reason AssertionRuleMapper gives: a key
        // we do not know cannot change a verdict, and it still round-trips.
        return b.build();
    }

    private Reducer readReducer(Map<String, Object> reduce, String label,
                                List<AssertionValidator.Finding> findings, ServerKpi.Builder b) {
        if (reduce == null || reduce.isEmpty()) {
            problem(b, label, findings, KpiUsability.unknownReducer(null));
            return null;
        }
        Object kindValue = reduce.get("kind");
        String kind = kindValue instanceof String ? (String) kindValue : null;
        Reducer reducer = Reducer.parse(kind);
        if (reducer == null) {
            // A non-text kind is quoted by its VALUE, as describe() quotes every other number,
            // and anything else is named by what it is. String.valueOf printed Jackson's own
            // rendering - '2.0', '1.0E-7', '[1, 2]', '{}' - which the page, reading the same file
            // through JSON.parse, cannot reproduce (review follow-up to M7d X).
            problem(b, label, findings, kindValue == null || kindValue instanceof String
                    ? KpiUsability.unknownReducer(kind)
                    : kindValue instanceof Number
                    ? KpiUsability.unknownReducer(numberText((Number) kindValue))
                    : KpiUsability.reducerKindNotText(describe(kindValue)));
            return null;
        }

        Object overValue = reduce.get("over");
        if (overValue == null) {
            return reducer;
        }
        if (!(overValue instanceof Map)) {
            problem(b, label, findings, KpiUsability.badTolerance("must be an object with "
                    + "sustainedFor or breachBudget, not " + describe(overValue)));
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> over = (Map<String, Object>) overValue;
        boolean hasSustained = over.containsKey("sustainedFor");
        boolean hasBudget = over.containsKey("breachBudget");
        if (hasSustained && hasBudget) {
            problem(b, label, findings, KpiUsability.badTolerance("declares both sustainedFor and "
                    + "breachBudget — exactly one is allowed; they are two tolerances with no "
                    + "defined combination"));
            return null;
        }
        if (!hasSustained && !hasBudget) {
            if (over.isEmpty()) {
                return reducer;
            }
            problem(b, label, findings, KpiUsability.badTolerance("has neither sustainedFor nor "
                    + "breachBudget (found: " + String.join(", ", over.keySet()) + ")"));
            return null;
        }
        if (hasSustained) {
            Object v = over.get("sustainedFor");
            Long millis = v instanceof String ? Durations.parseMillis((String) v) : null;
            if (millis == null) {
                problem(b, label, findings, KpiUsability.badTolerance("sustainedFor must be "
                        + Durations.grammar() + ", not " + describe(v)));
                return null;
            }
            if (millis <= 0) {
                // "0s" IS a duration, and calling it "not a duration such as 30s" sent the author
                // looking for a typo that was not there (U8c). What is wrong is the length: a level
                // held for no time is the plain reducer. Durations rounds to whole milliseconds, so
                // "0.0004s" lands here too, quoted as written. The grammar admits no sign, so this
                // arm is exactly zero.
                problem(b, label, findings, KpiUsability.badTolerance("sustainedFor \"" + v + "\" holds a "
                        + "level for no time at all, which is the reducer without a tolerance — remove "
                        + "reduce.over, or give sustainedFor a duration of at least 1ms"));
                return null;
            }
            return reducer.over(Reducer.Over.sustainedFor(millis));
        }
        Double fraction = asNumber(over.get("breachBudget"));
        if (fraction == null || fraction < 0 || fraction > 1) {
            problem(b, label, findings, KpiUsability.badTolerance("breachBudget must be a fraction "
                    + "0..1 of the window, not " + describe(over.get("breachBudget"))
                    + " — 2% is written 0.02"));
            return null;
        }
        return reducer.over(Reducer.Over.breachBudget(fraction));
    }

    /**
     * Read one band — exactly one operator, a numeric value. The same grammar as a client rule,
     * refused for the same reasons and with the same sentences, so a tester who has learnt one
     * editor has learnt the other.
     */
    private Predicate readBand(Map<String, Object> band, String which, String label,
                               List<AssertionValidator.Finding> findings, ServerKpi.Builder b) {
        if (band == null || band.isEmpty()) {
            return null;
        }
        if (band.size() > 1) {
            problem(b, label, findings, KpiUsability.unreadableBand(which, which + " has " + band.size()
                    + " operators (" + String.join(", ", band.keySet()) + ") — exactly one is allowed"));
            return null;
        }
        Map.Entry<String, Object> entry = band.entrySet().iterator().next();
        Predicate.Op op = Predicate.Op.fromKey(entry.getKey());
        if (op == null) {
            String hint = "outside".equals(entry.getKey()) || "min".equals(entry.getKey())
                    || "max".equals(entry.getKey())
                    ? ". A corridor is two bands in opposite directions, or two server asserts"
                    : "";
            problem(b, label, findings, KpiUsability.unreadableBand(which, which + " operator '"
                    + entry.getKey() + "' is not one of gt, gte, lt, lte" + hint));
            return null;
        }
        Double number = asNumber(entry.getValue());
        if (number == null) {
            problem(b, label, findings, KpiUsability.unreadableBand(which, which + " " + entry.getKey()
                    + " must be a number, not " + describe(entry.getValue())
                    + " — a threshold is a literal, never an interpolated variable"));
            return null;
        }
        return Predicate.of(op, number);
    }

    private void retiredKpiKeys(ServerMetricsJson.KpiJson k, String label,
                                List<AssertionValidator.Finding> findings, ServerKpi.Builder b) {
        Map<String, Object> unknown = k.getUnknownKeys();
        if (unknown == null || unknown.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> retired : RETIRED_KPI_KEYS.entrySet()) {
            if (unknown.containsKey(retired.getKey())) {
                problem(b, label, findings, KpiUsability.retiredKey(retired.getKey(), retired.getValue()));
            }
        }
    }

    /**
     * Keys from the canvas's nine-type grammar that the reducer × band grammar replaced, and
     * what to write instead. Each is here because ignoring it changes what the KPI judges:
     * {@code assert} carried the whole comparison, {@code warnAt} a band, {@code onMissing} and
     * {@code onLoadShortfall} a policy that could turn silence into a verdict, {@code loadValidity}
     * a second declaration of load, {@code scope} a different window, {@code required} a fatality.
     * A key that is merely unknown is ignored in silence; these were ours.
     */
    private static final Map<String, String> RETIRED_KPI_KEYS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("assert", "\"assert\" is not a key of a server assert. A server assert is a reducer "
                + "and a band: write "
                + "\"reduce\": {\"kind\": \"max\"} (or min, avg, last, slope, pN; with an optional "
                + "\"over\": {\"sustainedFor\": \"30s\"} or {\"breachBudget\": 0.02}) and \"warn\"/"
                + "\"fail\" bands with gt, gte, lt or lte, exactly like a client rule.");
        m.put("range", "\"range\" is not a key of a server assert. A corridor is two bands in "
                + "opposite directions (\"fail\": {\"gt\": 800} on one assert, \"fail\": {\"lt\": 0} "
                + "on another), each saying which end was missed.");
        m.put("warnAt", "\"warnAt\" is not a key of a server assert. There is no implicit 80% "
                + "warning; write the warn band you mean: \"warn\": {\"gt\": 640}.");
        m.put("onMissing", "\"onMissing\" is not a key of a server assert. A series that is missing "
                + "is NOT_JUDGED "
                + "with a reason — SOURCE_FAILED, NO_MATCH or LOW_COVERAGE — and the reason, not a "
                + "policy, decides what happens: a collection failure is a config error under "
                + "failonerror, and a query matching nothing is counted and never fatal on its own.");
        m.put("onLoadShortfall", "\"onLoadShortfall\" is not a key of a server assert. Name the "
                + "client load rule "
                + "instead — \"requiresLoad\": \"load:throughput\" — and a run that missed its load "
                + "leaves this server assert NOT_JUDGED / LOAD_NOT_ACHIEVED. A band is never rescaled.");
        m.put("loadValidity", "\"loadValidity\" is not a key of a server assert. Load is declared "
                + "once, as the "
                + "client rule the file already carries; reference it with \"requiresLoad\": "
                + "\"<rule id>\".");
        m.put("required", "\"required\" is not a key of a server assert. Every server assert can "
                + "fail the build; one that should only be watched gets a warn band and no fail band.");
        m.put("source", "\"source\" is not a key of a server assert — where the metrics come from is "
                + "given to wir-collect, never written in the file. Name the \"component\" the assert "
                + "judges instead.");
        m.put("gate", "\"gate\" is not a key of a server assert — name the \"component\" it judges, "
                + "declared under serverMetrics.components.");
        m.put("scope", "\"scope\" is not a key of a server assert. Every server assert is judged "
                + "over one window — the run, "
                + "optionally excluding ramp-up via serverMetrics.collection.excludeRampUp.");
        m.put("overlayOn", "\"overlayOn\" is not a key of a server assert; which client chart a "
                + "series overlays is chosen in the report, not declared in the file.");
        // Misplaced, not retired — but ignoring either judges the KPI WITHOUT the tolerance its
        // author wrote, so a 1-second spike fails the build they tried to tolerate.
        String overHint = " it belongs under reduce.over: \"reduce\": {\"kind\": \"max\", \"over\": "
                + "{\"sustainedFor\": \"30s\"}} (or {\"breachBudget\": 0.02}).";
        String atTop = " is not read at the top level of a server assert, so the assert is refused:";
        m.put("sustainedFor", "\"sustainedFor\"" + atTop + overHint);
        m.put("breachBudget", "\"breachBudget\"" + atTop + overHint);
        m.put("over", "\"over\"" + atTop + overHint);
        RETIRED_KPI_KEYS = Collections.unmodifiableMap(m);
    }

    // ---- helpers ----------------------------------------------------------------

    /** Record the problem ON the KPI (so it refuses) and as a finding (so the author is told). */
    private static void problem(ServerKpi.Builder b, String label,
                                List<AssertionValidator.Finding> findings, KpiUsability u) {
        findings.add(error(label, u.reasonText()));
        b.declarationProblem(u);
    }

    private static AssertionValidator.Finding error(String id, String message) {
        return AssertionValidator.finding(AssertionValidator.Severity.ERROR, id, message);
    }

    /**
     * A number from the file: a JSON number, or text {@code Double.parseDouble} reads once trimmed —
     * never NaN or an infinity, on either arm. {@code null} for anything else.
     *
     * <p>The one copy, so package-visible: {@link AssertionRuleMapper} reads a client band through
     * this and {@link #describe}, as {@link AnnotationLoader} writes a number through
     * {@link #numberText}. AssertionRuleMapper had its own, guarded on the text arm only, and a
     * bare {@code 1e400} reached it as the Number Jackson had already overflowed to Infinity:
     * {@code fail {gt: 1e400}} PASSed every run with no finding and exit 0, and {@code lt 1e400}
     * FAILed every run.
     */
    static Double asNumber(Object value) {
        // A NaN band never fires (every comparison against it is false) — a silent PASS; a NaN
        // coverage floor switches coverage checking off; a NaN breach budget throws out of the
        // reducer; an infinite fail band cannot be breached by any measurement. None is a number,
        // and the guard belongs on BOTH arms: parseDouble accepts "NaN" and "Infinity", and a
        // bare JSON literal like 1e400 overflows to Infinity inside the parser before it ever
        // reaches this method as a String.
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        if (value instanceof String) {
            try {
                double d = Double.parseDouble(((String) value).trim());
                return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * How a refusal names the value it could not read — {@code the text "soon"},
     * {@code the number Infinity}, {@code a list}. Package-visible for {@link AssertionRuleMapper},
     * whose band refusals say it in these words, so a client band and a server band name one wrong
     * value alike. report.js {@code WIR.javaText.describe} is its mirror, for both tabs.
     */
    static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "the text \"" + value + "\"";
        if (value instanceof Boolean) return "a boolean";
        if (value instanceof List) return "a list";
        if (value instanceof Map) return "an object";
        if (value instanceof Number) return "the number " + numberText((Number) value);
        return value.getClass().getSimpleName();
    }

    /**
     * A number from the file, as a refusal quotes it: by its VALUE, never by how it was written.
     * An integral value below 1e15 is written as a long, anything else as {@code Double.toString}
     * — {@code 2.0} and {@code 2} are "2", {@code 1e-7} is "1.0E-7".
     *
     * <p>{@code "the number " + value} printed Jackson's own type — Integer "2" for {@code 2},
     * Double "2.0" for {@code 2.0} — and the report reads the same file through JSON.parse, which
     * cannot tell those two apart. So the board's INVALID sentence and the CI/CD JSON's differed
     * on a file nobody had edited (review:C). report.js {@code jsonNumberText} writes the same
     * text by the same rule; the parity vectors pin both, in both of Java's notations.
     */
    static String numberText(Number value) {
        double d = value.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
