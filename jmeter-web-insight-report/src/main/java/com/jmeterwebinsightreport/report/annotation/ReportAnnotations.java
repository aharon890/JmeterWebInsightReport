package com.jmeterwebinsightreport.report.annotation;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level annotation data loaded from a companion JSON file.
 * Matches the report-annotations.json structure from the design doc.
 */
public class ReportAnnotations {

    private String version = "1.0";
    private String testNotes = "";
    private List<TimelineMarker> timelineMarkers = new ArrayList<>();
    private Map<String, String> samplerNotes = new LinkedHashMap<>();
    private ComparisonThresholdConfig comparisonThresholds;
    private List<AssertionConfig> assertions = new ArrayList<>();
    private LoadValidityConfig loadValidity;
    private ServerMetricsJson serverMetrics;

    /**
     * Keys this version does not recognise, kept verbatim.
     *
     * <p>The report can save this file back, and a save that silently dropped whatever it did
     * not understand would punish anyone who hand-added a key, or who opened a file written by
     * a newer plugin in an older one. Round-tripping the unknown is the difference between an
     * editor and a lossy converter.
     */
    private final Map<String, Object> unknownKeys = new LinkedHashMap<>();

    /** See {@link #migratedRuleId()}. */
    private String migratedRuleId;

    public ReportAnnotations() {
    }

    @JsonAnySetter
    public void setUnknownKey(String name, Object value) {
        unknownKeys.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getUnknownKeys() {
        return unknownKeys;
    }

    public LoadValidityConfig getLoadValidity() { return loadValidity; }
    public void setLoadValidity(LoadValidityConfig loadValidity) { this.loadValidity = loadValidity; }

    /**
     * The {@code serverMetrics} block as written, or {@code null} when the file has none.
     *
     * <p>Typed, like {@code assertions}, so the mapper can name what is wrong in it; captured
     * with its own unknown keys, so a save still round-trips whatever this version does not
     * understand. {@code ServerMetricsMapper} turns it into the config the server gates judge.
     */
    public ServerMetricsJson getServerMetrics() { return serverMetrics; }
    public void setServerMetrics(ServerMetricsJson serverMetrics) { this.serverMetrics = serverMetrics; }

    /**
     * Fold any legacy block into {@code assertions}, so the rest of the system sees one kind of
     * rule.
     *
     * <p>Idempotent by construction — the block is consumed, so a second call has nothing to do —
     * which is what lets every entry point call it without coordinating. {@code GateEvaluation}
     * calls it because that is the one place a run is judged and nothing may reach the evaluator
     * unmigrated; {@code AnnotationLoader} calls it so a caller that only wants to read the
     * document still gets the migrated shape.
     *
     * @return the id of the rule this created, or null when there was nothing to migrate
     */
    public String migrateLegacyBlocks() {
        String id = LoadValidityMigration.apply(this);
        if (id != null) {
            migratedRuleId = id;
        }
        return id;
    }

    /**
     * The id of the rule a legacy block became, whichever call migrated it — or null when none
     * did.
     *
     * <p>Kept because the call that migrates is usually not the one that reports it. Every run
     * loads its file through {@code AnnotationLoader}, which migrates and consumes the block, so
     * {@code GateEvaluation}'s own call found nothing and its WARNING finding never fired on a
     * real run (GAP-1). Not part of the file: no bean accessor, so Jackson never writes it.
     */
    public String migratedRuleId() {
        return migratedRuleId;
    }

    public List<AssertionConfig> getAssertions() { return assertions; }
    public void setAssertions(List<AssertionConfig> assertions) {
        this.assertions = assertions == null ? new ArrayList<AssertionConfig>() : assertions;
    }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getTestNotes() { return testNotes; }
    public void setTestNotes(String testNotes) { this.testNotes = testNotes; }

    public List<TimelineMarker> getTimelineMarkers() { return timelineMarkers; }
    public void setTimelineMarkers(List<TimelineMarker> timelineMarkers) { this.timelineMarkers = timelineMarkers; }

    public Map<String, String> getSamplerNotes() { return samplerNotes; }
    public void setSamplerNotes(Map<String, String> samplerNotes) { this.samplerNotes = samplerNotes; }

    public ComparisonThresholdConfig getComparisonThresholds() { return comparisonThresholds; }
    public void setComparisonThresholds(ComparisonThresholdConfig comparisonThresholds) { this.comparisonThresholds = comparisonThresholds; }

    /**
     * A LEGACY block, read only so it can be migrated.
     *
     * <p>It declared the load the run was supposed to apply, so a shortfall could not pass for a
     * fast system — a real question, and one JMeter cannot answer on its own because it records
     * what was achieved and never what was intended.
     *
     * <p>It is no longer a mechanism. {@link LoadValidityMigration} folds it into
     * {@code assertions} at load, where it becomes an ordinary rule the report can edit, disable
     * and delete. These classes survive only to parse the old shape; nothing writes them back.
     */
    public static class LoadValidityConfig {
        private String metric;
        private Double target;
        private BandConfig warn;
        private BandConfig fail;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }

        public Double getTarget() { return target; }
        public void setTarget(Double target) { this.target = target; }

        public BandConfig getWarn() { return warn; }
        public void setWarn(BandConfig warn) { this.warn = warn; }

        public BandConfig getFail() { return fail; }
        public void setFail(BandConfig fail) { this.fail = fail; }
    }

    /** One band of the legacy block: how far under target was still acceptable. */
    public static class BandConfig {
        private Double tolerance;

        public Double getTolerance() { return tolerance; }
        public void setTolerance(Double tolerance) { this.tolerance = tolerance; }
    }


    /**
     * Comparison regression thresholds from annotations JSON.
     */
    public static class ComparisonThresholdConfig {
        private Double p95PctChange;
        private Double errorRateChange;
        private Double meanPctChange;
        private Double p99PctChange;
        private Double throughputPctChange;

        public ComparisonThresholdConfig() {}

        public Double getP95PctChange() { return p95PctChange; }
        public void setP95PctChange(Double v) { this.p95PctChange = v; }
        public Double getErrorRateChange() { return errorRateChange; }
        public void setErrorRateChange(Double v) { this.errorRateChange = v; }
        public Double getMeanPctChange() { return meanPctChange; }
        public void setMeanPctChange(Double v) { this.meanPctChange = v; }
        public Double getP99PctChange() { return p99PctChange; }
        public void setP99PctChange(Double v) { this.p99PctChange = v; }
        public Double getThroughputPctChange() { return throughputPctChange; }
        public void setThroughputPctChange(Double v) { this.throughputPctChange = v; }
    }

    /**
     * One declared assertion, exactly as written in the file.
     *
     * <p>Deliberately dumb: every field is carried through as typed, including a metric name
     * that does not exist and a band with the wrong number of operators. Validation happens
     * afterwards, in one place, so that every finding can name the rule and suggest a fix
     * instead of surfacing as a Jackson parse error that names a line number.
     */
    public static class AssertionConfig {
        private String id;
        private String description;
        private TargetConfig target;
        private String metric;
        private Map<String, Object> warn;
        private Map<String, Object> fail;
        private Boolean enabled;
        private final Map<String, Object> unknownKeys = new LinkedHashMap<>();
        /**
         * The bands a migration could not build, by {@code warn}/{@code fail}, each with the sentence
         * that says why in the words of the field the file wrote (L3). Not a property: no getter or
         * setter names it, so Jackson neither reads nor writes it, and a band refused here is written
         * back as the band itself — the next run, reading the file with no legacy block, refuses that
         * band in the band reader's words.
         */
        private final Map<String, String> bandRefusals = new LinkedHashMap<>();

        public AssertionConfig() {}

        @JsonAnySetter
        public void setUnknownKey(String name, Object value) { unknownKeys.put(name, value); }

        @JsonAnyGetter
        public Map<String, Object> getUnknownKeys() { return unknownKeys; }

        /** Record that the {@code warn} or {@code fail} band could not be built, and why. */
        void refuseBand(String which, String problem) { bandRefusals.put(which, problem); }

        /** Why the {@code warn} or {@code fail} band could not be built, or null. */
        String bandRefusal(String which) { return bandRefusals.get(which); }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public TargetConfig getTarget() { return target; }
        public void setTarget(TargetConfig target) { this.target = target; }
        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public Map<String, Object> getWarn() { return warn; }
        public void setWarn(Map<String, Object> warn) { this.warn = warn; }
        public Map<String, Object> getFail() { return fail; }
        public void setFail(Map<String, Object> fail) { this.fail = fail; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    /** Exactly one of these forms is meant to be set; the validator says so when several are. */
    public static class TargetConfig {
        private String sampler;
        private List<String> samplers;
        private String match;
        private List<String> exclude;
        private String scope;
        private final Map<String, Object> unknownKeys = new LinkedHashMap<>();

        public TargetConfig() {}

        @JsonAnySetter
        public void setUnknownKey(String name, Object value) { unknownKeys.put(name, value); }

        @JsonAnyGetter
        public Map<String, Object> getUnknownKeys() { return unknownKeys; }

        public String getSampler() { return sampler; }
        public void setSampler(String sampler) { this.sampler = sampler; }
        public List<String> getSamplers() { return samplers; }
        public void setSamplers(List<String> samplers) { this.samplers = samplers; }
        public String getMatch() { return match; }
        public void setMatch(String match) { this.match = match; }
        public List<String> getExclude() { return exclude; }
        public void setExclude(List<String> exclude) { this.exclude = exclude; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }

    /**
     * A marker on the timeline (vertical line on charts).
     */
    public static class TimelineMarker {
        private long timestamp;
        private String label;
        private String type; // "info", "warning", "error", "deployment", "custom"
        private String description;
        private String color;

        public TimelineMarker() {
        }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }
}
