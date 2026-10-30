package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.jmeterwebinsightreport.core.comparison.ComparisonResult;
import com.jmeterwebinsightreport.core.comparison.ComparisonThresholds;
import com.jmeterwebinsightreport.core.model.*;
import com.jmeterwebinsightreport.core.model.TimeSeriesBucket.SamplerBucketData;
import com.jmeterwebinsightreport.core.assertion.SamplerVerdicts;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Serializes ReportData to JSON for embedding in the HTML report (consumed by ECharts).
 */
public class ReportDataSerializer {

    private final ObjectMapper objectMapper;

    public ReportDataSerializer() {
        this.objectMapper = new ObjectMapper();

        // Register Instant serializer → epoch millis for JavaScript
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeNumber(value.toEpochMilli());
            }
        });
        objectMapper.registerModule(module);
    }

    /**
     * Serialize report data to JSON string for embedding in the template.
     */
    public String serializeToJson(ReportData reportData) throws JsonProcessingException {
        return objectMapper.writeValueAsString(reportData);
    }

    /**
     * The chart data, as the page reads it inline.
     *
     * <p>This and {@link #externalDataJsContent} are the only two ways in, and each takes every
     * argument (GAP-6). Shorter forms used to fill the rest with null, and one that left out the
     * evaluation built a page with no gate, no metric vocabulary and no annotations document —
     * a payload no report ships, one argument away from shipping.
     *
     * @param evaluation the gate, so the report's Asserts tab has the rules, the
     *        results, the metric vocabulary and the annotations document. The page is one file
     *        on {@code file://} — whatever is not in the payload, it cannot ask for. A run that
     *        declared nothing passes {@code GateEvaluation.none()}; null still builds the gateless
     *        payload, which no report ships.
     * @param serverMetrics the collector's payload, or null — the series the Server tab will
     *        chart and the server asserts will be judged over, carried in the same map as
     *        everything else so it is counted by the external-data threshold and present in
     *        every output (inline, external and streamed all go through here)
     * @param server the server gates as judged, or null — {@code serverGates[]}/{@code serverKpis[]} for the Server tab
     */
    public String serializeChartData(ReportData reportData, ReportAnnotations annotations,
                                      ComparisonResult comparison,
                                      com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation,
                                      com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload serverMetrics,
                                      com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server)
            throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                chartDataOf(reportData, annotations, comparison, evaluation, serverMetrics, server));
    }

    /**
     * The same payload, as the sidecar the report's {@code <script src>} loads, written to any
     * {@link java.io.Writer}.
     *
     * <p>Split out so report generation can <em>stage</em> the payload alongside the rest of the
     * set and publish all four together, rather than committing this one to its final name while
     * the HTML that references it has not been rendered yet. The chart data map is built eagerly
     * here — it is cheap and it is where a serialisation problem should surface — but the JSON
     * itself is still streamed, never materialised as a String: for a large soak run that meant
     * building the whole payload once and then allocating a second full copy to prepend the
     * wrapper, tripling transient heap inside the JMeter JVM at the worst possible moment.
     *
     * @param assignmentPrefix the JS assignment the report's &lt;script src&gt; expects
     * @param evaluation the gate, and the rest as {@link #serializeChartData}, which this takes
     *        argument for argument
     */
    public com.jmeterwebinsightreport.core.util.AtomicFileWriter.ContentWriter externalDataJsContent(
            ReportData reportData, ReportAnnotations annotations, ComparisonResult comparison,
            String assignmentPrefix,
            com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation,
            com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload serverMetrics,
            com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server) {
        Map<String, Object> chartData =
                chartDataOf(reportData, annotations, comparison, evaluation, serverMetrics, server);

        // Through the one sidecar writer the threshold path uses too, which writes a __proto__ key
        // in the form the browser keeps as a key (N2).
        return ObjectLiteralWriter.assignment(assignmentPrefix, writer -> {
            // AUTO_CLOSE_TARGET must be off: writeValue would otherwise close the writer and
            // the trailing ";" would never be written (and the report would fail to parse).
            JsonGenerator generator = objectMapper.getFactory().createGenerator(writer);
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            objectMapper.writeValue(generator, chartData);
            generator.flush();
        });
    }

    /** The one map both forms write, so the inline page and the sidecar cannot carry different data. */
    private Map<String, Object> chartDataOf(ReportData reportData, ReportAnnotations annotations,
            ComparisonResult comparison,
            com.jmeterwebinsightreport.report.gate.GateEvaluation evaluation,
            com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload serverMetrics,
            com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server) {
        Map<String, Object> chartData = buildChartDataMap(reportData);
        addAnnotations(chartData, annotations);
        GatePayloadBuilder.addTo(chartData, evaluation, annotations, reportData);
        addComparison(chartData, comparison);
        ServerMetricsPayloadWriter.addTo(chartData, serverMetrics, server);
        GatePayloadBuilder.addServerTo(chartData, server);
        return chartData;
    }

    private void addAnnotations(Map<String, Object> chartData, ReportAnnotations annotations) {
        if (annotations == null) {
            return;
        }

        List<Map<String, Object>> markers = new ArrayList<>();
        for (ReportAnnotations.TimelineMarker m : annotations.getTimelineMarkers() != null ? annotations.getTimelineMarkers() : java.util.Collections.<ReportAnnotations.TimelineMarker>emptyList()) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("timestamp", m.getTimestamp());
            marker.put("label", m.getLabel());
            marker.put("type", m.getType());
            marker.put("description", m.getDescription());
            marker.put("color", m.getColor());
            markers.add(marker);
        }
        chartData.put("timelineMarkers", markers);

        // Loaded annotation content — serialized so "Save Annotations" can round-trip it instead of
        // overwriting markers/notes/verdict with empties (C24).
        if (annotations.getTestNotes() != null) chartData.put("testNotes", annotations.getTestNotes());
        if (annotations.getSamplerNotes() != null && !annotations.getSamplerNotes().isEmpty()) {
            chartData.put("samplerNotes", annotations.getSamplerNotes());
        }

        // A dead threshold block does NOT reach the chart data. It drew chart marklines that
        // no rule stood behind — a limit line with no verdict, no badge and nothing in the
        // CI/CD JSON — which is a worse lie than not drawing it: the reader sees a threshold on
        // a chart and concludes the run was gated against it. A top-level "thresholds" key is
        // now an unknown key like any other: nothing reads it, nothing reports it, and it rides
        // the annotations document only so a Save writes it back.
    }

    private Map<String, List<Object>> buildTotalSeries(List<TimeSeriesBucket> timeSeries) {
        Map<String, List<Object>> series = new LinkedHashMap<>();
        for (String key : SERIES_METRIC_KEYS) {
            series.put(key, new ArrayList<>(timeSeries.size()));
        }
        for (TimeSeriesBucket bucket : timeSeries) {
            Object[] values = seriesValues(bucket.getTotal());
            for (int m = 0; m < SERIES_METRIC_KEYS.length; m++) {
                series.get(SERIES_METRIC_KEYS[m]).add(values[m]);
            }
        }
        return series;
    }

    private static final String[] SERIES_METRIC_KEYS = {"responseTime", "throughput", "errorRate", "p50", "p90",
            "p95", "p99", "max", "connectTime", "latency", "receivedBytes", "sentBytes"};

    /** One second of one series, in {@link #SERIES_METRIC_KEYS}' order; all null for a second with no sample. */
    private static Object[] seriesValues(SamplerBucketData sd) {
        Object[] v = new Object[SERIES_METRIC_KEYS.length];
        if (sd == null || sd.getSampleCount() == 0) {
            return v;
        }
        v[0] = round2(sd.getMeanResponseTime());
        v[1] = round2(sd.getThroughput());
        v[2] = round2(sd.getErrorRate());
        v[3] = round2(sd.getP50());
        v[4] = round2(sd.getP90());
        v[5] = round2(sd.getP95());
        v[6] = round2(sd.getP99());
        v[7] = round2(sd.getMax());
        v[8] = round2OrNull(sd.getConnectTime());
        v[9] = round2OrNull(sd.getLatency());
        v[10] = round2(sd.getReceivedBytes());
        v[11] = round2(sd.getSentBytes());
        return v;
    }

    private Map<String, Map<String, List<Object>>> buildPerSamplerSeries(List<TimeSeriesBucket> timeSeries) {
        Set<String> allSamplerNames = new LinkedHashSet<>();
        for (TimeSeriesBucket bucket : timeSeries) {
            allSamplerNames.addAll(bucket.getPerSamplerData().keySet());
        }

        int numBuckets = timeSeries.size();
        String[] metricKeys = SERIES_METRIC_KEYS;
        int numMetrics = metricKeys.length;

        Map<String, Object[][]> samplerArrays = new LinkedHashMap<>(allSamplerNames.size());
        for (String name : allSamplerNames) {
            samplerArrays.put(name, new Object[numMetrics][numBuckets]);
        }

        for (int bucketIdx = 0; bucketIdx < numBuckets; bucketIdx++) {
            TimeSeriesBucket bucket = timeSeries.get(bucketIdx);
            for (Map.Entry<String, SamplerBucketData> entry : bucket.getPerSamplerData().entrySet()) {
                Object[][] arrays = samplerArrays.get(entry.getKey());
                Object[] values = seriesValues(entry.getValue());
                for (int m = 0; m < numMetrics; m++) {
                    arrays[m][bucketIdx] = values[m];
                }
            }
        }

        Map<String, Map<String, List<Object>>> result = new LinkedHashMap<>(allSamplerNames.size());
        for (String name : allSamplerNames) {
            Object[][] arrays = samplerArrays.get(name);
            Map<String, List<Object>> series = new LinkedHashMap<>(numMetrics);
            for (int m = 0; m < numMetrics; m++) {
                series.put(metricKeys[m], Arrays.asList(arrays[m]));
            }
            result.put(name, series);
        }
        return result;
    }

    private void addComparison(Map<String, Object> chartData, ComparisonResult comparison) {
        if (comparison == null) return;

        // Add baseline time-series data including per-sampler series
        ReportData baselineData = comparison.getBaseline();
        if (baselineData.getTimeSeries() != null && !baselineData.getTimeSeries().isEmpty()) {
            List<TimeSeriesBucket> baseTs = baselineData.getTimeSeries();
            List<Long> baseTimestamps = new ArrayList<>(baseTs.size());
            List<Double> baseMeanRT = new ArrayList<>(baseTs.size());
            List<Double> baseThroughputs = new ArrayList<>(baseTs.size());
            List<Double> baseErrorRates = new ArrayList<>(baseTs.size());
            for (TimeSeriesBucket b : baseTs) {
                baseTimestamps.add(b.getTimestamp().toEpochMilli());
                baseMeanRT.add(round2(b.getMeanResponseTime()));
                baseThroughputs.add(round2(b.getThroughput()));
                baseErrorRates.add(round2(b.getErrorRate()));
            }
            Map<String, Object> baselineChart = new LinkedHashMap<>();
            baselineChart.put("timestamps", baseTimestamps);
            baselineChart.put("meanResponseTimes", baseMeanRT);
            baselineChart.put("throughputs", baseThroughputs);
            baselineChart.put("errorRates", baseErrorRates);
            baselineChart.put("perSamplerSeries", buildPerSamplerSeries(baseTs));
            baselineChart.put("totalSeries", buildTotalSeries(baseTs));
            chartData.put("baseline", baselineChart);
        }

        // Add sampler diffs
        List<ComparisonResult.SamplerDiff> samplerDiffs = comparison.getSamplerDiffs();
        List<Map<String, Object>> diffs = new ArrayList<>(samplerDiffs.size());
        for (ComparisonResult.SamplerDiff diff : samplerDiffs) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", diff.getSamplerName());
            d.put("isNew", diff.isNew());
            d.put("removed", diff.isRemoved());
            d.put("regression", diff.isRegression());

            SamplerStatistics curr = diff.getCurrent();
            if (curr == null) {
                // Removed sampler: present in the baseline, gone from this run. There are no
                // "current" figures to report — emit the baseline side only, so the compare
                // table can show the row as gone instead of the whole report failing to
                // generate on a NullPointerException.
                SamplerStatistics base = diff.getBaseline();
                if (base != null) {
                    d.put("baseSamples", base.getSampleCount());
                    d.put("baseErrors", base.getErrorCount());
                    d.put("baseErrorRate", round2(base.getErrorRate()));
                    d.put("baseMean", round2(base.getMeanResponseTime()));
                    d.put("baseP95", round2(base.getPercentile95()));
                    d.put("baseP99", round2(base.getPercentile99()));
                    d.put("baseThroughput", round2(base.getThroughput()));
                }
                diffs.add(d);
                continue;
            }

            d.put("currSamples", curr.getSampleCount());
            d.put("currErrors", curr.getErrorCount());
            d.put("currErrorRate", round2(curr.getErrorRate()));
            d.put("currMean", round2(curr.getMeanResponseTime()));
            d.put("currMedian", round2(curr.getMedianResponseTime()));
            d.put("currP90", round2(curr.getPercentile90()));
            d.put("currP95", round2(curr.getPercentile95()));
            d.put("currP99", round2(curr.getPercentile99()));
            d.put("currMin", round2(curr.getMinResponseTime()));
            d.put("currMax", round2(curr.getMaxResponseTime()));
            d.put("currThroughput", round2(curr.getThroughput()));
            d.put("currRecvKBs", round2(curr.getReceivedBytesPerSec() / 1024.0));

            if (!diff.isNew()) {
                SamplerStatistics base = diff.getBaseline();
                d.put("deltaSamples", curr.getSampleCount() - base.getSampleCount());
                d.put("deltaErrors", curr.getErrorCount() - base.getErrorCount());
                d.put("deltaErrorRate", round2(curr.getErrorRate() - base.getErrorRate()));
                d.put("deltaMean", round2(curr.getMeanResponseTime() - base.getMeanResponseTime()));
                d.put("deltaMedian", round2(curr.getMedianResponseTime() - base.getMedianResponseTime()));
                d.put("deltaP90", round2(curr.getPercentile90() - base.getPercentile90()));
                d.put("deltaP95", round2(curr.getPercentile95() - base.getPercentile95()));
                d.put("deltaP99", round2(curr.getPercentile99() - base.getPercentile99()));
                d.put("deltaMin", round2(curr.getMinResponseTime() - base.getMinResponseTime()));
                d.put("deltaMax", round2(curr.getMaxResponseTime() - base.getMaxResponseTime()));
                d.put("deltaThroughput", round2(curr.getThroughput() - base.getThroughput()));
                d.put("deltaRecvKBs", round2((curr.getReceivedBytesPerSec() - base.getReceivedBytesPerSec()) / 1024.0));
                d.put("meanPctChange", round2(diff.getMeanPctChange()));
                d.put("p95PctChange", round2(diff.getP95PctChange()));
            }
            diffs.add(d);
        }
        chartData.put("comparisonDiffs", diffs);
        chartData.put("hasComparison", true);

        // Serialize thresholds for frontend live adjustment
        ComparisonThresholds th = comparison.getThresholds();
        if (th != null) {
            Map<String, Object> thMap = new LinkedHashMap<>();
            thMap.put("p95PctChange", th.getP95PctChangeThreshold());
            thMap.put("errorRateChange", th.getErrorRateChangeThreshold());
            thMap.put("meanPctChange", th.getMeanPctChangeThreshold());
            thMap.put("p99PctChange", th.getP99PctChangeThreshold());
            thMap.put("throughputPctChange", th.getThroughputPctChangeThreshold());
            chartData.put("comparisonThresholds", thMap);
        }
    }

    /**
     * Build chart data as a Map — core logic shared by the inline and the sidecar form.
     */
    /** {start, end, rampUpMillis?} in epoch ms, or null when the run has no samples. */
    static Map<String, Object> runWindowOf(ReportData reportData) {
        com.jmeterwebinsightreport.core.model.TestMetadata meta = reportData.getMetadata();
        if (meta == null || meta.getStartTime() == null || meta.getEndTime() == null) {
            return null;
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("start", meta.getStartTime().toEpochMilli());
        w.put("end", meta.getEndTime().toEpochMilli());
        if (meta.getRampUpDurationMillis() > 0) {
            w.put("rampUpMillis", meta.getRampUpDurationMillis());
        }
        return w;
    }

    private Map<String, Object> buildChartDataMap(ReportData reportData) {
        Map<String, Object> chartData = new LinkedHashMap<>();

        // Which run this payload belongs to. First key in the map so it is readable in the first
        // line of a multi-megabyte web-insight-data.js, and so report.js can refuse a payload
        // that belongs to a different run instead of rendering it under this report's headings.
        chartData.put("runId", com.jmeterwebinsightreport.core.util.RunIdentity.of(reportData));
        chartData.put("runTestName", reportData.getMetadata() != null
                ? reportData.getMetadata().getTestName() : null);
        // The run's exact window, as the collector computes it (earliest sample start .. latest
        // start + elapsed): what an operator exports server data for. The interim report's "how to
        // finish" panel prints it in UTC, local time and epoch ms.
        Map<String, Object> runWindow = runWindowOf(reportData);
        if (runWindow != null) {
            chartData.put("runWindow", runWindow);
        }

        // Time-series arrays (parallel arrays for efficient JS consumption)
        List<TimeSeriesBucket> timeSeries = reportData.getTimeSeries();
        List<Long> timestamps = new ArrayList<>(timeSeries.size());
        List<Double> meanResponseTimes = new ArrayList<>(timeSeries.size());
        List<Double> throughputs = new ArrayList<>(timeSeries.size());
        List<Double> errorRates = new ArrayList<>(timeSeries.size());
        List<Integer> activeThreads = new ArrayList<>(timeSeries.size());

        for (TimeSeriesBucket bucket : timeSeries) {
            timestamps.add(bucket.getTimestamp().toEpochMilli());
            meanResponseTimes.add(round2(bucket.getMeanResponseTime()));
            throughputs.add(round2(bucket.getThroughput()));
            errorRates.add(round2(bucket.getErrorRate()));
            activeThreads.add(bucket.getActiveThreads());
        }

        chartData.put("timestamps", timestamps);
        chartData.put("meanResponseTimes", meanResponseTimes);
        chartData.put("throughputs", throughputs);
        chartData.put("errorRates", errorRates);
        chartData.put("activeThreads", activeThreads);
        // The Σ Total of every per-sampler metric, the top-level samplers pooled per second (T1): a
        // percentile of the second's samples, not a mean of the samplers' percentiles, and no
        // transaction counted beside its requests. The page draws the Σ lines from it alone.
        chartData.put("totalSeries", buildTotalSeries(timeSeries));

        // Sampler summary for bar/comparison charts — includes p50 and max
        List<Map<String, Object>> samplers = new ArrayList<>(
                reportData.getSamplerStatistics() != null ? reportData.getSamplerStatistics().size() : 0);
        if (reportData.getSamplerStatistics() != null) {
            for (SamplerStatistics stat : reportData.getSamplerStatistics()) {
                Map<String, Object> s = new LinkedHashMap<>();
                // What the client evaluator judges, unrounded (R2-1, M7d X) — see exact().
                Map<String, Object> exact = new LinkedHashMap<>();
                s.put("name", stat.getSamplerName());
                s.put("count", stat.getSampleCount());
                s.put("mean", round2(stat.getMeanResponseTime()));
                s.put("median", round2(stat.getMedianResponseTime()));
                s.put("p90", round2(stat.getPercentile90()));
                s.put("p95", round2(stat.getPercentile95()));
                s.put("p99", round2(stat.getPercentile99()));
                exact(exact, "mean", stat.getMeanResponseTime());
                exact(exact, "median", stat.getMedianResponseTime());
                exact(exact, "p90", stat.getPercentile90());
                exact(exact, "p95", stat.getPercentile95());
                exact(exact, "p99", stat.getPercentile99());
                // p75 and p99.9 complete the grid the assert editor offers. They are resolved
                // from the retained digest, so they are real quantiles rather than anything
                // interpolated from the four the table displays; absent a digest the key is
                // omitted entirely, because a wrong tail number is worse than a missing one.
                PercentileSource dist = reportData.percentileSource(stat.getSamplerName());
                if (dist != null && dist.sampleCount() > 0) {
                    double p75 = dist.percentile(75);
                    double p999 = dist.percentile(99.9);
                    s.put("p75", round2(p75));
                    s.put("p999", round2(p999));
                    exact(exact, "p75", p75);
                    exact(exact, "p999", p999);
                }
                s.put("min", round2(stat.getMinResponseTime()));
                s.put("max", round2(stat.getMaxResponseTime()));
                s.put("errorRate", round2(stat.getErrorRate()));
                exact(exact, "min", stat.getMinResponseTime());
                exact(exact, "max", stat.getMaxResponseTime());
                exact(exact, "errorRate", stat.getErrorRate());
                s.put("errorCount", stat.getErrorCount());
                // Full precision, unlike its neighbours: throughput is the number requiresLoad and
                // divideBy judge, and the browser re-judges server KPIs from this payload. Java
                // judges stat.getThroughput() as is, so a round2'd 12.496 req/s sat ON a `lt 12.5`
                // load rule and passed it on the board while the build stood the KPI down (R2-1).
                // The JS formats every number it shows; this one is never displayed raw.
                s.put("throughput", fullPrecision(stat.getThroughput()));
                s.put("stdDev", round2(stat.getStandardDeviation()));
                s.put("receivedBytesPerSec", round2(stat.getReceivedBytesPerSec()));
                s.put("sentBytesPerSec", round2(stat.getSentBytesPerSec()));
                exact(exact, "stdDev", stat.getStandardDeviation());
                exact(exact, "receivedBytesPerSec", stat.getReceivedBytesPerSec());
                exact(exact, "sentBytesPerSec", stat.getSentBytesPerSec());
                // Absent, not zero — see SamplerStatistics.meanConnectTime. NaN is not valid
                // JSON, and null is what the client already reads as "no value": fmtNum returns
                // an empty cell for it, and the per-bucket connectTime/latency arrays beside
                // this one have carried nulls for the same reason since they were added.
                s.put("meanConnectTime", round2OrAbsent(stat.getMeanConnectTime()));
                s.put("meanLatency", round2OrAbsent(stat.getMeanLatency()));
                exact(exact, "meanConnectTime", stat.getMeanConnectTime());
                exact(exact, "meanLatency", stat.getMeanLatency());
                s.put("apdex", stat.getApdexScore());
                if (!exact.isEmpty()) {
                    s.put("exact", exact);
                }
                s.put("isTransactionController", stat.isTransactionController());
                if (stat.getParentSamplerName() != null) {
                    s.put("parent", stat.getParentSamplerName());
                }
                if (stat.getChildSamplerNames() != null) {
                    s.put("children", stat.getChildSamplerNames());
                }
                samplers.add(s);
            }
        }
        chartData.put("samplers", samplers);

        // The run as a whole. The percentiles are of the merged distribution of top-level
        // samplers — the true overall percentile, not an average of per-sampler percentiles —
        // and feed the Σ Total table row and the percentile-bar Total.
        //
        // The volume, rate and quality figures beside them are new, and they are here because
        // a rule can now target {"scope": "overall"}: the editor has to show the observed value
        // it is seeding a band from, and it can only show what the payload carries. They come
        // from OverallMetrics, the same resolver the header tiles and the CI/CD JSON use, so
        // the number in the editor is the number in the verdict.
        if (reportData.getOverallP95() > 0 || reportData.getOverallP99() > 0
                || reportData.getOverallMedian() > 0
                || (reportData.getSamplerStatistics() != null && !reportData.getSamplerStatistics().isEmpty())) {
            OverallMetrics m = OverallMetrics.of(reportData);
            Map<String, Object> overall = new LinkedHashMap<>();
            Map<String, Object> exact = new LinkedHashMap<>();
            overall.put("median", round2(m.getMedian()));
            overall.put("p90", round2(m.getP90()));
            overall.put("p95", round2(m.getP95()));
            overall.put("p99", round2(m.getP99()));
            exact(exact, "median", m.getMedian());
            exact(exact, "p90", m.getP90());
            exact(exact, "p95", m.getP95());
            exact(exact, "p99", m.getP99());
            if (m.hasDistribution()) {
                double p75 = m.percentile(75);
                double p999 = m.percentile(99.9);
                overall.put("p75", round2(p75));
                overall.put("p999", round2(p999));
                exact(exact, "p75", p75);
                exact(exact, "p999", p999);
            }
            overall.put("mean", round2(m.getMean()));
            overall.put("totalSamples", m.getTotalSamples());
            overall.put("errorRate", round2(m.getErrorRate()));
            overall.put("throughput", fullPrecision(m.getThroughput()));   // see samplers[] (R2-1)
            // All bytes over the test duration: the Σ Total row's KB/s columns (B1), which used to
            // add up the rows' own rates.
            overall.put("receivedBytesPerSec", round2(m.getReceivedBytesPerSec()));
            overall.put("sentBytesPerSec", round2(m.getSentBytesPerSec()));
            overall.put("apdex", m.getApdex());
            overall.put("duration", round2(m.getDurationSeconds()));
            overall.put("maxThreads", m.getMaxThreads());
            exact(exact, "mean", m.getMean());
            exact(exact, "errorRate", m.getErrorRate());
            exact(exact, "duration", m.getDurationSeconds());
            exact(exact, "receivedBytesPerSec", m.getReceivedBytesPerSec());
            exact(exact, "sentBytesPerSec", m.getSentBytesPerSec());
            if (!exact.isEmpty()) {
                overall.put("exact", exact);
            }
            chartData.put("overall", overall);
        }

        // The T every Apdex score on this page was computed against. An apdex rule asserts this
        // configuration as much as it asserts the score — "apdex fell below 0.8" means nothing
        // without it — so the report shows it next to any such rule, and cannot until it is here.
        chartData.put("apdexThreshold", reportData.getApdexThreshold());

        // Per-sampler time-series for individual chart lines
        chartData.put("perSamplerSeries", buildPerSamplerSeries(timeSeries));

        // Collect sampler names for histogram
        Set<String> allSamplerNames = new LinkedHashSet<>();
        for (TimeSeriesBucket bucket : timeSeries) {
            allSamplerNames.addAll(bucket.getPerSamplerData().keySet());
        }

        // Response time distribution histogram (global and per-sampler bins)
        chartData.put("responseTimeDistribution", ChartDataBuilder.buildResponseTimeDistribution(timeSeries, allSamplerNames));

        // Heatmap data (per-sampler histogram bins over time)
        chartData.put("heatmapData", ChartDataBuilder.buildHeatmapData(timeSeries, allSamplerNames));

        // Response codes over time


        // Error breakdown data for Error tab charts
        // The failed requests (E3): a transaction's own failures are the asserts', not the Errors tab's
        List<ErrorSummary> requestErrors = reportData.requestErrorSummaries();
        if (!requestErrors.isEmpty()) {
            List<Map<String, Object>> errorsByType = new ArrayList<>();
            List<Map<String, Object>> errorSummaries = new ArrayList<>();
            for (ErrorSummary es : requestErrors) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("code", es.getResponseCode());
                e.put("message", es.getResponseMessage());
                e.put("count", es.getOccurrenceCount());
                e.put("pctErrors", round2(es.getPercentageOfErrors()));
                // Carried, not re-derived in the browser. Everything client-side that shows an
                // error — the by-code chart, the grouped detail view, both CSV exports — needs
                // to know that a 200 in this list is a failure, and shipping the field means
                // there is one classifier rather than two that can disagree.
                e.put("errorClass", es.getErrorClass());
                e.put("errorClassLabel", es.getErrorClassLabel());
                e.put("messageMeaningless", es.isMessageMeaningless());
                errorsByType.add(e);

                // Full error summary for CSV export (includes sampler name)
                Map<String, Object> full = new LinkedHashMap<>();
                full.put("samplerName", es.getSamplerName());
                full.put("responseCode", es.getResponseCode());
                full.put("responseMessage", es.getResponseMessage());
                full.put("occurrenceCount", es.getOccurrenceCount());
                // The group's whole span, for the grouped view's first and last seen (E2): the
                // records it used to read them from are only the first 500 errors.
                full.put("firstTimestamp", es.getFirstTimestamp());
                full.put("lastTimestamp", es.getLastTimestamp());
                full.put("percentageOfErrors", round2(es.getPercentageOfErrors()));
                full.put("percentageOfAllSamples", round2(es.getPercentageOfAllSamples()));
                full.put("errorClass", es.getErrorClass());
                full.put("errorClassLabel", es.getErrorClassLabel());
                full.put("messageMeaningless", es.isMessageMeaningless());
                errorSummaries.add(full);
            }
            chartData.put("errorsByType", errorsByType);
            chartData.put("errorSummaries", errorSummaries);
        }

        // Errors by sampler: the failed requests per sampler, from the same groups as the tab's table
        // (E3). Read off the samplers' error counts, a transaction counted its own failures beside its
        // requests'.
        if (reportData.getSamplerStatistics() != null) {
            Map<String, Long> failedBySampler = new LinkedHashMap<>();
            for (ErrorSummary es : requestErrors) {
                failedBySampler.merge(es.getSamplerName(), es.getOccurrenceCount(), Long::sum);
            }
            List<Map<String, Object>> errorsBySampler = new ArrayList<>();
            for (SamplerStatistics stat : reportData.getSamplerStatistics()) {
                Long failed = failedBySampler.get(stat.getSamplerName());
                if (failed != null && failed > 0) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("name", stat.getSamplerName());
                    e.put("count", failed);
                    e.put("rate", round2(stat.getSampleCount() > 0 ? failed * 100.0 / stat.getSampleCount() : 0));
                    errorsBySampler.add(e);
                }
            }
            chartData.put("errorsBySampler", errorsBySampler);
        }

        // Error records for detail expansion
        if (reportData.getErrorRecords() != null && !reportData.getErrorRecords().isEmpty()) {
            List<Map<String, Object>> errorRecords = new ArrayList<>();
            for (ErrorRecord er : reportData.getErrorRecords()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("timestamp", er.getTimestamp());
                e.put("sampler", er.getSamplerName());
                e.put("code", er.getResponseCode());
                e.put("message", er.getResponseMessage());
                e.put("errorClass", er.getErrorClass());
                e.put("errorClassLabel", er.getErrorClassLabel());
                e.put("messageMeaningless", er.isMessageMeaningless());
                e.put("thread", er.getThreadName());
                if (er.getRequestUrl() != null) e.put("url", er.getRequestUrl());
                if (er.getRequestHeaders() != null) e.put("headers", er.getRequestHeaders());
                if (er.getResponseBody() != null) e.put("body", er.getResponseBody());
                errorRecords.add(e);
            }
            chartData.put("errorRecords", errorRecords);
        }
        // Whether the detailed error records are a truncated sample (charts must then use full aggregates).
        chartData.put("errorRecordsTruncated", reportData.isErrorRecordsTruncated());

        // Test-level totals — needed for correct "% of all samples" math on the client.
        //
        // Two populations, each a JMeter tool's, and each part of the report internally consistent:
        //  - requestSampleCount/requestErrorCount are the failed requests and every request: every
        //    recorded sample but a Transaction Controller's own result, children included. The
        //    Errors tab's rows are drawn from them and its Total divides by them, as JMeter's HTML
        //    report's Errors table does (E3). They were every recorded sample (C20), so a failed
        //    transaction was listed beside the request that failed it.
        //  - topLevelSampleCount/topLevelErrorCount exclude transaction children. That is the
        //    population the header tiles and cicd.json use, since a child's time is already
        //    counted inside its parent (JMeter's Aggregate Report in the test plan).
        chartData.put("requestSampleCount", reportData.getRequestSampleCount());
        chartData.put("requestErrorCount", requestErrors.stream().mapToLong(ErrorSummary::getOccurrenceCount).sum());
        chartData.put("topLevelSampleCount", reportData.getTopLevelSampleCount());
        chartData.put("topLevelErrorCount", reportData.getTopLevelErrorCount());

        // Hidden samplers (auto-hide utility + 0-request)
        if (reportData.getHiddenSamplers() != null && !reportData.getHiddenSamplers().isEmpty()) {
            chartData.put("hiddenSamplers", reportData.getHiddenSamplers());
        }

        // Transaction hierarchy
        if (reportData.getTransactionHierarchy() != null && !reportData.getTransactionHierarchy().isEmpty()) {
            chartData.put("transactionHierarchy", reportData.getTransactionHierarchy());
        }

        // Ramp-up end timestamp
        if (reportData.getMetadata() != null && reportData.getMetadata().getRampUpDurationMillis() > 0
                && reportData.getMetadata().getStartTime() != null) {
            long rampUpEnd = reportData.getMetadata().getStartTime().toEpochMilli()
                    + reportData.getMetadata().getRampUpDurationMillis();
            chartData.put("rampUpEnd", rampUpEnd);
        }

        return chartData;
    }

    /**
     * Serialize a CI/CD-friendly JSON summary for pipeline integration.
     * Delegates to {@link CiCdJsonSerializer}.
     */
    public String serializeCiCdJson(ReportData reportData,
                                      SamplerVerdicts assertResults,
                                      AssertionStatus overallStatus) throws JsonProcessingException {
        return serializeCiCdJson(reportData, assertResults, overallStatus, null);
    }

    /** @param artifacts the manifest of paths actually written, or null to omit it */
    public String serializeCiCdJson(ReportData reportData,
                                      SamplerVerdicts assertResults,
                                      AssertionStatus overallStatus,
                                      Map<String, Object> artifacts) throws JsonProcessingException {
        return serializeCiCdJson(reportData, assertResults, overallStatus, artifacts, null);
    }

    /** @param gate the declared rules and their results, or null when there is no gate */
    public String serializeCiCdJson(ReportData reportData,
                                      SamplerVerdicts assertResults,
                                      AssertionStatus overallStatus,
                                      Map<String, Object> artifacts,
                                      com.jmeterwebinsightreport.core.assertion.GateSummary gate)
            throws JsonProcessingException {
        return serializeCiCdJson(reportData, assertResults, overallStatus, artifacts, gate, null);
    }

    /** @param findings what the loader and validator rejected, published even with no gate */
    public String serializeCiCdJson(ReportData reportData,
                                      SamplerVerdicts assertResults,
                                      AssertionStatus overallStatus,
                                      Map<String, Object> artifacts,
                                      com.jmeterwebinsightreport.core.assertion.GateSummary gate,
                                      java.util.List<com.jmeterwebinsightreport.core.assertion
                                              .AssertionValidator.Finding> findings)
            throws JsonProcessingException {
        return serializeCiCdJson(reportData, assertResults, overallStatus, artifacts, gate, findings, null);
    }

    /** @param server the server gates as judged, or null — {@code serverGates[]} and {@code serverKpis[]} */
    public String serializeCiCdJson(ReportData reportData,
                                      SamplerVerdicts assertResults,
                                      AssertionStatus overallStatus,
                                      Map<String, Object> artifacts,
                                      com.jmeterwebinsightreport.core.assertion.GateSummary gate,
                                      java.util.List<com.jmeterwebinsightreport.core.assertion
                                              .AssertionValidator.Finding> findings,
                                      com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server)
            throws JsonProcessingException {
        return new CiCdJsonSerializer(objectMapper).serialize(
                reportData, assertResults, overallStatus, artifacts, gate,
                findings == null ? java.util.Collections.emptyList() : findings, server);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * The value as measured, for a figure something JUDGES in the browser (R2-1) — except where
     * it is not a number JSON can carry: NaN and the infinities still go through round2, which
     * is what they always did (NaN became 0), because Jackson would write them as bare tokens and
     * the page's JSON would not parse.
     */
    private static double fullPrecision(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? round2(value) : value;
    }

    /**
     * A figure the client evaluator judges, at the precision Java judges it — written into the
     * entry's {@code exact} block only where round2 changed it, so a run of whole numbers carries
     * none. R2-1 carried throughput unrounded because the Server tab re-judges load rules from it;
     * every other metric a client rule names was still 2dp, so a rule re-judged on the Asserts tab
     * after an edit could flip at an edge Java does not (a mean of 12.496 sat ON {@code lt 12.5}).
     * The figures beside it stay 2dp: they are what the charts and tables show. report.js
     * {@code buildRun} lays this block over them for the evaluator and for nothing else.
     */
    private static void exact(Map<String, Object> into, String key, double value) {
        if (!Double.isNaN(value) && !Double.isInfinite(value) && round2(value) != value) {
            into.put(key, value);
        }
    }

    /** round2 that preserves null (absent metric) so it serializes as JSON null, not 0. */
    private static Double round2OrNull(Double value) {
        return value == null ? null : round2(value);
    }

    /**
     * A primitive metric that may be absent, as a number or null — never NaN.
     *
     * <p>The sibling of {@link #round2OrNull} for the model's primitive {@code double} fields,
     * which carry absence as NaN because they have no null. Jackson would serialise NaN as the
     * bare token {@code NaN}, which is not JSON: {@code JSON.parse} throws, so one sampler saved
     * without a Connect column would take the whole payload — and with it every chart, the gate
     * strip and the Asserts tab — down with it.
     */
    private static Double round2OrAbsent(double value) {
        return Double.isNaN(value) ? null : round2(value);
    }
}
