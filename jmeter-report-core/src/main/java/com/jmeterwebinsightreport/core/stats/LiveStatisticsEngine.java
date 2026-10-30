package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.*;
import com.jmeterwebinsightreport.core.util.FormatUtil;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Aggregation engine for listener mode — receives individual sample data and maintains
 * running statistics per sampler, time-series buckets, and error records. Thread-safe.
 */
public class LiveStatisticsEngine {

    private static final int MAX_ERROR_RECORDS = 500;
    private static final int DEFAULT_MAX_BODY_LENGTH = 16384; // 16KB
    private static final int MAX_HEADERS_LENGTH = 2048;
    private int maxResponseBodyLength = DEFAULT_MAX_BODY_LENGTH;

    /** Patterns for utility samplers that should be auto-hidden. */
    private static final List<Pattern> UTILITY_SAMPLER_PATTERNS = Arrays.asList(
            Pattern.compile("^JSR223.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^Debug.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^BeanShell.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^__.*")  // JMeter internal functions
    );

    private final Map<String, SamplerAggregator> aggregators = new ConcurrentHashMap<>();
    private final Map<Long, TimeBucketAggregator> timeBuckets = new ConcurrentHashMap<>();
    private final List<ErrorRecord> errorRecords = new ArrayList<>();
    /** Unbounded per-(sampler|code) error aggregation — feeds summaries and error charts.
     *  Detailed {@link #errorRecords} are capped for memory, but these counts never are:
     *  {count, earliest start, latest start}, so the grouped view's first and last seen are the
     *  group's whole span, not the first 500 errors' (E2). Guarded by the {@code errorRecords}
     *  monitor. */
    private final Map<String, long[]> errorSummaryCounts = new LinkedHashMap<>();
    private final Map<String, ErrorRecord> errorSummaryFirst = new LinkedHashMap<>();
    /** Real transaction-controller hierarchy, captured from actual sample sub-results
     *  (parent label -> ordered child labels). Used instead of guessing from name prefixes,
     *  which false-positives on REST-style names like "GET /api/users" vs "GET /api/users/{id}".
     *  Every sampler thread can register a child concurrently, so the sets are synchronized;
     *  LinkedHashSet (rather than a concurrent set) keeps child order deterministic. */
    private final Map<String, Set<String>> explicitTransactions = new ConcurrentHashMap<>();
    /** Of those parents, the ones whose sub-results really came from a Transaction Controller.
     *  Embedded resources, redirect chains and retries also produce sub-results, and nesting
     *  them is right (the parent's elapsed time already contains them) — but labelling a plain
     *  HTTP sampler as a Transaction Controller in the report is not. */
    private final Set<String> transactionControllerParents = ConcurrentHashMap.newKeySet();

    private final AtomicLong totalSamples = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicInteger maxActiveThreads = new AtomicInteger();
    private final AtomicLong firstTimestamp = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong lastTimestamp = new AtomicLong(Long.MIN_VALUE);
    /** End of the last sample (start + elapsed). The per-sampler throughput window ends
     *  here, so the test duration — which the header's total-throughput tile and cicd.json
     *  divide by — has to end here too, or one report measures rates over two different
     *  window definitions. */
    private final AtomicLong lastSampleEnd = new AtomicLong(Long.MIN_VALUE);

    private long rampUpDurationMillis;
    private double apdexThreshold = 500;

    /**
     * Record a single sample result (backward-compatible overload).
     */
    public void recordSample(String samplerName, long responseTime, boolean isSuccess,
                             long bytesReceived, long bytesSent, long timestamp) {
        recordSample(samplerName, responseTime, isSuccess, bytesReceived, bytesSent,
                timestamp, -1, -1);
    }

    /**
     * Record a single sample result with connect time and latency.
     */
    public void recordSample(String samplerName, long responseTime, boolean isSuccess,
                             long bytesReceived, long bytesSent, long timestamp,
                             long connectTime, long latency) {
        recordSample(samplerName, responseTime, isSuccess, bytesReceived, bytesSent, timestamp,
                timestamp + Math.max(0, responseTime), connectTime, latency);
    }

    /**
     * Record a single sample result that ended at {@code endTime}. That is
     * {@code timestamp + responseTime} for every sample but one kind: a Transaction Controller's
     * parent sample with "include timers" off, whose elapsed time is its children's and whose
     * pauses are its idle time. It ends after them, and the throughput windows end there (B7).
     */
    public void recordSample(String samplerName, long responseTime, boolean isSuccess,
                             long bytesReceived, long bytesSent, long timestamp, long endTime,
                             long connectTime, long latency) {
        recordSample(samplerName, responseTime, isSuccess, bytesReceived, bytesSent, timestamp,
                endTime, connectTime, latency, 1, isSuccess ? 0 : 1);
    }

    /**
     * Record a result that stands for {@code samples} samples, {@code errors} of them failed: what
     * JMeter's Statistical mode sends from a remote engine, and what a results file saved with
     * {@code jmeter.save.saveservice.sample_count=true} holds. JMeter's Aggregate Report counts it as
     * that many samples ({@code SamplingStatCalculator.addSample}); one per result undercounted every
     * figure of such a run (B6).
     */
    public void recordSample(String samplerName, long responseTime, boolean isSuccess,
                             long bytesReceived, long bytesSent, long timestamp, long endTime,
                             long connectTime, long latency, long samples, long errors) {
        long n = Math.max(1, samples);
        long failed = Math.min(n, Math.max(0, errors));
        // Per-sampler aggregation
        // The aggregator counts Apdex buckets as samples arrive, so it needs the threshold now.
        // setApdexThreshold is always called during setup, before any sample is recorded.
        aggregators.computeIfAbsent(samplerName, k -> new SamplerAggregator(k, apdexThreshold))
                .addSample(responseTime, isSuccess, bytesReceived, bytesSent, timestamp, endTime,
                        connectTime, latency, n, failed);

        // Time-series bucketing (1-second granularity)
        long bucketKey = timestamp / 1000 * 1000;
        timeBuckets.computeIfAbsent(bucketKey, k -> new TimeBucketAggregator(k))
                .addSample(responseTime, samplerName, bytesReceived, bytesSent,
                        connectTime, latency, n, failed);

        // Global counters
        totalSamples.addAndGet(n);
        totalErrors.addAndGet(failed);
        updateMinLong(firstTimestamp, timestamp);
        updateMaxLong(lastTimestamp, timestamp);
        updateMaxLong(lastSampleEnd, endTime);
    }

    /**
     * Record an error with details (backward-compatible overload).
     */
    public void recordError(String samplerName, String responseCode, String responseMessage,
                            long timestamp, String threadName) {
        recordError(samplerName, responseCode, responseMessage, timestamp, threadName,
                null, null, null);
    }

    /**
     * Record an error with full details including URL, headers, and response body.
     */
    public void recordError(String samplerName, String responseCode, String responseMessage,
                            long timestamp, String threadName,
                            String requestUrl, String requestHeaders, String responseBody) {
        synchronized (errorRecords) {
            // Unbounded aggregation for summaries and error charts — updated for every error.
            String key = samplerName + "|" + responseCode;
            long[] c = errorSummaryCounts.get(key);
            if (c == null) {
                c = new long[]{0, timestamp, timestamp};
                errorSummaryCounts.put(key, c);
                ErrorRecord agg = new ErrorRecord();
                agg.setSamplerName(samplerName);
                agg.setResponseCode(responseCode);
                agg.setResponseMessage(responseMessage);
                agg.setTimestamp(timestamp);
                errorSummaryFirst.put(key, agg);
            }
            c[0]++;
            c[1] = Math.min(c[1], timestamp);
            c[2] = Math.max(c[2], timestamp);

            // Detailed records (URL/headers/body) are capped to bound memory.
            if (errorRecords.size() >= MAX_ERROR_RECORDS) {
                return;
            }
            ErrorRecord record = new ErrorRecord();
            record.setSamplerName(samplerName);
            record.setResponseCode(responseCode);
            record.setResponseMessage(responseMessage);
            record.setTimestamp(timestamp);
            record.setThreadName(threadName);
            if (requestUrl != null) {
                record.setRequestUrl(requestUrl);
            }
            if (requestHeaders != null) {
                record.setRequestHeaders(requestHeaders.length() > MAX_HEADERS_LENGTH
                        ? requestHeaders.substring(0, MAX_HEADERS_LENGTH) + "..." : requestHeaders);
            }
            if (responseBody != null) {
                record.setResponseBody(responseBody.length() > maxResponseBodyLength
                        ? responseBody.substring(0, maxResponseBodyLength) + "..." : responseBody);
            }
            errorRecords.add(record);
        }
    }

    /**
     * Update the max active threads seen.
     */
    public void updateActiveThreads(int activeThreads, long timestamp) {
        maxActiveThreads.updateAndGet(current -> Math.max(current, activeThreads));
        // computeIfAbsent, not get: only pre-existing buckets were updated, so any caller
        // ordering where the thread count arrives before the sample — or a timestamp landing
        // in the next second — silently lost the per-bucket value.
        long bucketKey = timestamp / 1000 * 1000;
        timeBuckets.computeIfAbsent(bucketKey, TimeBucketAggregator::new)
                .updateActiveThreads(activeThreads);
    }

    /**
     * Record a real transaction-controller relationship observed from a sample's sub-results.
     * Called by the listener for each (transaction parent, child sample) pair.
     */
    public void recordTransaction(String parentLabel, String childLabel) {
        recordTransaction(parentLabel, childLabel, true);
    }

    /**
     * @param fromTransactionController true when the parent sample was produced by a
     *        Transaction Controller. Any sub-result nests its child so the child's time is not
     *        counted twice, but only a real controller is presented as one in the report.
     */
    public void recordTransaction(String parentLabel, String childLabel,
                                  boolean fromTransactionController) {
        if (parentLabel == null || childLabel == null || parentLabel.equals(childLabel)) {
            return;
        }
        // ConcurrentHashMap makes set *creation* atomic, but the add must be guarded too:
        // every thread finishing the same Transaction Controller mutates the same set, and
        // an unsynchronized HashMap resize can silently drop a child registration — which
        // then leaves that child unmarked and double-counted in the tiles and percentiles.
        explicitTransactions
                .computeIfAbsent(parentLabel, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                .add(childLabel);
        if (fromTransactionController) {
            transactionControllerParents.add(parentLabel);
        }
    }

    public void setRampUpDurationMillis(long rampUpDurationMillis) {
        this.rampUpDurationMillis = rampUpDurationMillis;
    }

    /**
     * @param maxResponseBodyLength characters of response body to keep per error record
     * @throws IllegalArgumentException if negative — a negative limit would make the
     *         truncating substring in {@link #recordError} throw for every failed sample,
     *         and JMeter swallows listener exceptions per event, so error details would
     *         silently disappear while the run still looked healthy.
     */
    public void setMaxResponseBodyLength(int maxResponseBodyLength) {
        if (maxResponseBodyLength < 0) {
            throw new IllegalArgumentException(
                    "maxResponseBodyLength must be >= 0, got " + maxResponseBodyLength);
        }
        this.maxResponseBodyLength = maxResponseBodyLength;
    }

    public void setApdexThreshold(double apdexThreshold) {
        this.apdexThreshold = apdexThreshold;
    }

    public double getApdexThreshold() {
        return apdexThreshold;
    }

    /**
     * Get current statistics snapshot for all samplers.
     */
    public List<SamplerStatistics> getStatisticsSnapshot() {
        final double threshold = this.apdexThreshold;
        return aggregators.values().stream()
                .map(agg -> agg.toStatistics(threshold))
                .sorted(Comparator.comparing(SamplerStatistics::getSamplerName))
                .collect(Collectors.toList());
    }

    /**
     * Get time-series data sorted by timestamp.
     */
    public List<TimeSeriesBucket> getTimeSeries() {
        return timeBuckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue().toBucket())
                .collect(Collectors.toList());
    }

    /**
     * Get recorded errors, in time order: by when the sample started, then by sampler and thread.
     *
     * <p>They are kept in the order they arrived, which is when each sample finished, and the
     * listener and the results file do not see concurrent samples finish in the same order. Two
     * reports of one run listed the same errors in a different order (S36: 22 of 500 positions,
     * campaign F17, E4). Sorted, they read alike, and a "by time" list is in time order.
     */
    public List<ErrorRecord> getErrorRecords() {
        List<ErrorRecord> copy;
        synchronized (errorRecords) {
            copy = new ArrayList<>(errorRecords);
        }
        copy.sort(ERROR_RECORD_ORDER);
        return copy;
    }

    private static final Comparator<ErrorRecord> ERROR_RECORD_ORDER =
            Comparator.comparingLong(ErrorRecord::getTimestamp)
                    .thenComparing(ErrorRecord::getSamplerName, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(ErrorRecord::getThreadName, Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * True when the number of errors exceeded the detailed-record cap, i.e. the detailed
     * {@link #getErrorRecords()} list is a truncated sample of all errors.
     */
    public boolean isErrorRecordsTruncated() {
        return totalErrors.get() > MAX_ERROR_RECORDS;
    }

    /**
     * Build complete ReportData from the engine's current state.
     */
    public ReportData buildReportData(String testName) {
        ReportData data = new ReportData();

        // Metadata
        TestMetadata metadata = new TestMetadata();
        metadata.setTestName(testName);
        long first = firstTimestamp.get();
        long last = lastSampleEnd.get();
        if (first != Long.MAX_VALUE) {
            metadata.setStartTime(Instant.ofEpochMilli(first));
        }
        if (last != Long.MIN_VALUE) {
            metadata.setEndTime(Instant.ofEpochMilli(last));
        }
        if (first != Long.MAX_VALUE && last != Long.MIN_VALUE) {
            metadata.setDurationMillis(last - first);
        }
        metadata.setTotalSamples(totalSamples.get());
        metadata.setTotalErrors(totalErrors.get());
        metadata.setTotalThreads(maxActiveThreads.get());
        /* One instant for both: the formatted text the JSON carries, and the ISO the page turns into
           the reader's zone (SF38). Millisecond precision, which every browser's Date.parse takes. */
        Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        metadata.setReportGeneratedAt(FormatUtil.formatTimestamp(generatedAt));
        metadata.setReportGeneratedAtIso(generatedAt.toString());
        metadata.setRampUpDurationMillis(rampUpDurationMillis);
        data.setMetadata(metadata);

        // The T every Apdex score in this data was computed against. An apdex assertion
        // asserts this configuration as much as it asserts the score, so the number has to
        // travel with the data rather than stay behind in the engine.
        data.setApdexThreshold(apdexThreshold);

        // Sampler statistics
        data.setSamplerStatistics(getStatisticsSnapshot());

        // Time series
        data.setTimeSeries(getTimeSeries());

        // Error records
        data.setErrorRecords(getErrorRecords());
        data.setErrorRecordsTruncated(isErrorRecordsTruncated());

        // Auto-hide utility samplers and 0-request samplers
        data.setHiddenSamplers(computeHiddenSamplers(data.getSamplerStatistics()));

        // Detect transaction controller hierarchy. Must run before the error summaries, which
        // express counts as a percentage of the top-level population — that population is only
        // known once children have been marked.
        data.setTransactionHierarchy(detectTransactionHierarchy(data.getSamplerStatistics()));

        // Error summaries — aggregate from error records
        data.setErrorSummaries(buildErrorSummaries());

        // Overall percentiles across the merged distribution of top-level samplers (true global
        // percentile, not an average of per-sampler percentiles). Done after hierarchy detection so
        // transaction children are excluded (their times are already inside their parent).
        computeOverallPercentiles(data);

        return data;
    }

    /**
     * Merge the response times of all top-level samplers, set the true overall
     * percentiles, and keep every distribution reachable from the report data.
     *
     * <p>The four percentiles below are the ones the report renders. The distributions they came
     * from used to be dropped here, which meant p99.9 — or any quantile a rule names — could
     * not be answered afterwards at any price. They are now attached to {@link ReportData};
     * see {@link com.jmeterwebinsightreport.core.model.PercentileSource} for why that is
     * preferable to deciding the percentile set before the statistics are built.
     *
     * <p>The merged distribution excludes transaction children for the same reason the percentiles
     * always did: their time is already inside their parent, so including them weights those
     * samples twice.
     */
    private void computeOverallPercentiles(ReportData data) {
        ResponseTimeDistribution overall = new ResponseTimeDistribution();
        Map<String, com.jmeterwebinsightreport.core.model.PercentileSource> sources =
                new LinkedHashMap<>();

        for (SamplerStatistics stat : data.getSamplerStatistics()) {
            SamplerAggregator agg = aggregators.get(stat.getSamplerName());
            if (agg == null) {
                continue;
            }
            // Every sampler gets a source, children included: naming a sampler in a rule is an
            // explicit request to judge it, and that has always reached transaction children.
            sources.put(stat.getSamplerName(), agg.percentileSource());
            if (stat.getParentSamplerName() == null) {
                agg.mergePercentilesInto(overall);
            }
        }

        if (overall.getCount() > 0) {
            data.setOverallMedian(overall.getPercentile(50));
            data.setOverallP90(overall.getPercentile(90));
            data.setOverallP95(overall.getPercentile(95));
            data.setOverallP99(overall.getPercentile(99));
        }
        data.attachPercentileSources(sources, overall);
    }

    /**
     * Determine which samplers should be hidden by default.
     * Hides utility samplers (JSR223, Debug, BeanShell) and samplers with 0 requests.
     */
    private List<String> computeHiddenSamplers(List<SamplerStatistics> stats) {
        List<String> hidden = new ArrayList<>();
        for (SamplerStatistics stat : stats) {
            String name = stat.getSamplerName();
            // Hide 0-request samplers
            if (stat.getSampleCount() == 0) {
                hidden.add(name);
                continue;
            }
            // Hide utility samplers matching known patterns
            for (Pattern pattern : UTILITY_SAMPLER_PATTERNS) {
                if (pattern.matcher(name).matches()) {
                    hidden.add(name);
                    break;
                }
            }
        }
        return hidden;
    }

    /**
     * Build the transaction-controller hierarchy from real relationships observed via sample
     * sub-results (see {@link #recordTransaction}). Only genuine parent/child pairs are grouped —
     * there is no name-prefix guessing, so REST-style names are never falsely nested or hidden.
     * A JTL keeps no sub-results, but the parser finds a Transaction Controller's children by its row
     * and calls {@link #recordTransaction} too (B5, {@code TransactionRows}). When no relationship was
     * seen this returns an empty hierarchy and every sampler is treated as top-level.
     */
    private Map<String, List<String>> detectTransactionHierarchy(List<SamplerStatistics> stats) {
        Map<String, List<String>> hierarchy = new LinkedHashMap<>();
        if (stats.isEmpty() || explicitTransactions.isEmpty()) {
            return hierarchy;
        }

        Map<String, SamplerStatistics> statsByName = new LinkedHashMap<>(stats.size());
        for (SamplerStatistics stat : stats) {
            statsByName.put(stat.getSamplerName(), stat);
        }

        for (Map.Entry<String, Set<String>> entry : explicitTransactions.entrySet()) {
            String parent = entry.getKey();
            SamplerStatistics parentStat = statsByName.get(parent);
            if (parentStat == null) continue;

            // Snapshot under the set's own monitor — Collections.synchronizedSet does not
            // guard iteration.
            Set<String> childSet = entry.getValue();
            List<String> observedChildren;
            synchronized (childSet) {
                observedChildren = new ArrayList<>(childSet);
            }

            List<String> children = new ArrayList<>();
            for (String child : observedChildren) {
                SamplerStatistics childStat = statsByName.get(child);
                if (childStat == null || child.equals(parent)) continue;
                children.add(child);
                childStat.setParentSamplerName(parent);
            }
            if (!children.isEmpty()) {
                // Nest every parent that produced sub-results, but only badge the ones that
                // really are Transaction Controllers.
                parentStat.setTransactionController(transactionControllerParents.contains(parent));
                parentStat.setChildSamplerNames(children);
                hierarchy.put(parent, children);
            }
        }

        return hierarchy;
    }

    /**
     * Build error summaries by grouping error records by response code.
     *
     * <p>The rows include transaction children — a failing child is worth showing even though
     * its parent also counts as failed — so "% of all samples" divides by EVERY recorded
     * sample, the population those rows are drawn from. Dividing by the top-level count
     * instead mixed a child-inclusive numerator with a child-free denominator, which can push
     * a row past 100% and makes the rows disagree with their own total.
     */
    private List<ErrorSummary> buildErrorSummaries() {
        long total = totalSamples.get();
        long errors = totalErrors.get();
        if (errors == 0) {
            return Collections.emptyList();
        }

        // Group by sampler + response code — from the UNBOUNDED aggregation (not the capped list),
        // so counts and percentages are correct even when > MAX_ERROR_RECORDS errors occurred.
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, long[]> spans = new LinkedHashMap<>();
        Map<String, ErrorRecord> firstRecords;
        synchronized (errorRecords) {
            for (Map.Entry<String, long[]> e : errorSummaryCounts.entrySet()) {
                counts.put(e.getKey(), e.getValue()[0]);
                spans.put(e.getKey(), new long[]{e.getValue()[1], e.getValue()[2]});
            }
            firstRecords = new LinkedHashMap<>(errorSummaryFirst);
        }

        List<ErrorSummary> result = new ArrayList<>(counts.size());
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            ErrorRecord rec = firstRecords.get(entry.getKey());
            ErrorSummary s = new ErrorSummary();
            s.setSamplerName(rec.getSamplerName());
            s.setResponseCode(rec.getResponseCode());
            s.setResponseMessage(rec.getResponseMessage());
            s.setErrorType(rec.getResponseCode());
            s.setOccurrenceCount(entry.getValue());
            s.setFirstTimestamp(spans.get(entry.getKey())[0]);
            s.setLastTimestamp(spans.get(entry.getKey())[1]);
            s.setPercentageOfErrors(errors > 0 ? (entry.getValue() * 100.0) / errors : 0);
            s.setPercentageOfAllSamples(total > 0 ? (entry.getValue() * 100.0) / total : 0);
            result.add(s);
        }
        result.sort((a, b) -> Long.compare(b.getOccurrenceCount(), a.getOccurrenceCount()));
        return result;
    }

    /**
     * Reset all aggregators.
     */
    public void reset() {
        aggregators.clear();
        timeBuckets.clear();
        synchronized (errorRecords) {
            errorRecords.clear();
            errorSummaryCounts.clear();
            errorSummaryFirst.clear();
        }
        explicitTransactions.clear();
        transactionControllerParents.clear();
        totalSamples.set(0);
        totalErrors.set(0);
        maxActiveThreads.set(0);
        firstTimestamp.set(Long.MAX_VALUE);
        lastTimestamp.set(Long.MIN_VALUE);
        lastSampleEnd.set(Long.MIN_VALUE);
    }

    private static void updateMinLong(AtomicLong target, long value) {
        target.updateAndGet(current -> Math.min(current, value));
    }

    private static void updateMaxLong(AtomicLong target, long value) {
        target.updateAndGet(current -> Math.max(current, value));
    }
}
