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

    /** How a Transaction Controller's own result begins its response message ({@code TransactionSampler}).
     *  JMeter's HTML report finds controller rows by the same text ({@code Sample.isController}). */
    public static final String TRANSACTION_MESSAGE = "Number of samples in transaction";

    /** True for a Transaction Controller's own result, by its response message. */
    public static boolean isTransactionRow(String responseMessage) {
        return responseMessage != null && responseMessage.startsWith(TRANSACTION_MESSAGE);
    }
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
    /** Per group, the failures that were samples of the whole run (top-level): what an overall
     *  code= or class= rule counts. Guarded by the {@code errorRecords} monitor. */
    private final Map<String, Long> errorSummaryTopLevelCounts = new HashMap<>();
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
    /** The names a listener in the plan received at least once on their own (a top-level sample). Such
     *  a name is a top-level row even where it also ran inside a transaction: its row counts every run
     *  of the name, as JMeter's HTML report row does, and is shown, judged by blanket rules and given
     *  a JUnit testcase like any other. Hidden as a child, its own failures were nowhere in sight. */
    private final Set<String> namesSeenTopLevel = ConcurrentHashMap.newKeySet();

    private final AtomicLong totalSamples = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    /** Of {@link #totalSamples}, a Transaction Controller's own results (E3). */
    private final AtomicLong transactionRowSamples = new AtomicLong();
    /** Failed requests seen — every error but a transaction's own: the detail records' population.
     *  Guarded by the {@code errorRecords} monitor. */
    private long requestErrorsSeen;
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

    /** The whole run's row's name. It is no sampler's: the row is the run's, never in the table. */
    public static final String WHOLE_RUN = "Whole run";
    /**
     * Every sample a listener in the test plan received, and only those, as JMeter's Aggregate Report
     * feeds its TOTAL row ({@code StatVisualizer.add}): a transaction's requests are inside it. The
     * whole-run figures read it ({@link ReportData#getWholeRun()}).
     *
     * <p>Per sample, because per name is wrong: the same name can run on its own and inside a
     * transaction in one run, and the transaction hierarchy is a relation between names. Leaving the
     * child names out of the totals left every one of their samples out, the standalone ones too
     * (tails-conversation-flows, 2026-11: 0 errors where JMeter counted 17), and a transaction holding
     * a request of its own name was counted twice. Created again when the Apdex T is set, which is
     * always before the first sample.
     */
    private volatile SamplerAggregator wholeRun = new SamplerAggregator(WHOLE_RUN, apdexThreshold);

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
        recordSample(samplerName, responseTime, isSuccess, bytesReceived, bytesSent, timestamp, endTime,
                connectTime, latency, samples, errors, true);
    }

    /**
     * @param topLevel whether a listener in the test plan received this sample itself: false for a
     *        sub-result (a parent-sample transaction's request, a nested transaction, a redirect hop),
     *        which reached it inside its parent. Only top-level samples are the whole run's.
     */
    public void recordSample(String samplerName, long responseTime, boolean isSuccess,
                             long bytesReceived, long bytesSent, long timestamp, long endTime,
                             long connectTime, long latency, long samples, long errors, boolean topLevel) {
        long n = Math.max(1, samples);
        long failed = Math.min(n, Math.max(0, errors));
        // Per-sampler aggregation
        // The aggregator counts Apdex buckets as samples arrive, so it needs the threshold now.
        // setApdexThreshold is always called during setup, before any sample is recorded.
        aggregators.computeIfAbsent(samplerName, k -> new SamplerAggregator(k, apdexThreshold))
                .addSample(responseTime, isSuccess, bytesReceived, bytesSent, timestamp, endTime,
                        connectTime, latency, n, failed);
        if (topLevel) {
            wholeRun.addSample(responseTime, isSuccess, bytesReceived, bytesSent, timestamp, endTime,
                    connectTime, latency, n, failed);
            namesSeenTopLevel.add(samplerName);
        }

        // Time-series bucketing (1-second granularity). A sample counts in the second it ended, as
        // JMeter's charts key it (TimeStampKeysSelector, end time but for Hits per Second), and its
        // hit in the second it started, which the Throughput line draws as JMeter's Hits per Second
        // does (T7). The end is start + elapsed on both paths, which is where JMeter's HTML report
        // puts a row of the file: a transaction's idle time does not move it (the windows do, B7).
        long endKey = endOf(timestamp, responseTime) / 1000 * 1000;
        timeBuckets.computeIfAbsent(endKey, k -> new TimeBucketAggregator(k))
                .addSample(responseTime, samplerName, bytesReceived, bytesSent,
                        connectTime, latency, n, failed, topLevel);
        long startKey = timestamp / 1000 * 1000;
        timeBuckets.computeIfAbsent(startKey, k -> new TimeBucketAggregator(k)).addHits(samplerName, n, topLevel);

        // Global counters
        totalSamples.addAndGet(n);
        totalErrors.addAndGet(failed);
        updateMinLong(firstTimestamp, timestamp);
        updateMaxLong(lastTimestamp, timestamp);
        updateMaxLong(lastSampleEnd, endTime);
    }

    /**
     * Note that the sample just recorded was a Transaction Controller's own result
     * ({@link #isTransactionRow}). JMeter's HTML report leaves such results out of its Errors tables
     * and out of what their "% of all samples" divides by: a transaction's failure is its requests'
     * failure. The Errors tab does the same (E3); every other figure still counts them.
     */
    public void recordTransactionRow(long samples) {
        transactionRowSamples.addAndGet(Math.max(1, samples));
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
        recordError(samplerName, responseCode, responseMessage, null, timestamp, threadName,
                requestUrl, requestHeaders, responseBody);
    }

    /**
     * Record an error with its first failed assertion's message ({@code failureMessage}), which decides
     * JMeter's type of error for a 2xx failure ({@link ErrorType}).
     */
    public void recordError(String samplerName, String responseCode, String responseMessage,
                            String failureMessage, long timestamp, String threadName,
                            String requestUrl, String requestHeaders, String responseBody) {
        recordError(samplerName, responseCode, responseMessage, failureMessage, timestamp, threadName,
                requestUrl, requestHeaders, responseBody, true);
    }

    /**
     * @param topLevel whether the failed sample was the whole run's: received by a listener in the plan
     *        itself, not inside a parent sample ({@link #recordSample}'s flag of the same name)
     */
    public void recordError(String samplerName, String responseCode, String responseMessage,
                            String failureMessage, long timestamp, String threadName,
                            String requestUrl, String requestHeaders, String responseBody,
                            boolean topLevel) {
        boolean transactionRow = isTransactionRow(responseMessage);
        synchronized (errorRecords) {
            // Unbounded aggregation for summaries and error charts — updated for every error. Grouped
            // per sampler by JMeter's type of error, as the HTML report's Top 5 Errors by Sampler
            // groups them (E1): a code alone made one row of two messages. A transaction's own
            // failures are a group of their own: the asserts count them, the Errors tab does not (E3).
            String key = samplerName + "|" + ErrorType.of(responseCode, responseMessage, failureMessage)
                    + (transactionRow ? "|transaction" : "");
            long[] c = errorSummaryCounts.get(key);
            if (c == null) {
                c = new long[]{0, timestamp, timestamp};
                errorSummaryCounts.put(key, c);
                ErrorRecord agg = new ErrorRecord();
                agg.setSamplerName(samplerName);
                agg.setResponseCode(responseCode);
                agg.setResponseMessage(responseMessage);
                agg.setFailureMessage(failureMessage);
                agg.setTimestamp(timestamp);
                errorSummaryFirst.put(key, agg);
            }
            c[0]++;
            c[1] = Math.min(c[1], timestamp);
            c[2] = Math.max(c[2], timestamp);
            if (topLevel) {
                errorSummaryTopLevelCounts.merge(key, 1L, Long::sum);
            }

            // The detail records are the Errors tab's, so a transaction's own failure takes no place
            // among them: its requests' failures are listed.
            if (transactionRow) {
                return;
            }
            requestErrorsSeen++;
            // Detailed records (URL/headers/body) are capped to bound memory.
            if (errorRecords.size() >= MAX_ERROR_RECORDS) {
                return;
            }
            ErrorRecord record = new ErrorRecord();
            record.setSamplerName(samplerName);
            record.setResponseCode(responseCode);
            record.setResponseMessage(responseMessage);
            record.setFailureMessage(failureMessage);
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

    /** JMeter's series for a response with no code ({@code CodeSeriesSelector}). */
    public static final String EMPTY_RESPONSE_CODE = "EMPTY_RES_CODE";

    /**
     * Count a response by its code in the second it ended (T5), as JMeter's Codes per Second counts it:
     * every sample, successes too, one per result, keyed by end ({@code CodesPerSecondGraphConsumer}).
     * The caller leaves a Transaction Controller's own result out, as that chart does
     * ({@code CountValueSelector(true)}). The chart used to count only the failed samples' codes, from
     * the first 500 errors, by start.
     */
    public void recordResponseCode(String responseCode, long endTime) {
        String code = responseCode == null || responseCode.isEmpty() ? EMPTY_RESPONSE_CODE : responseCode;
        long bucketKey = endTime / 1000 * 1000;
        timeBuckets.computeIfAbsent(bucketKey, TimeBucketAggregator::new).addResponseCode(code);
    }

    /** Where a sample's per-second figures go: the second it ended, start + elapsed (T7). */
    public static long endOf(long start, long elapsed) {
        return start + Math.max(0, elapsed);
    }

    /**
     * Update the max active threads seen, in the second of {@code timestamp}: the sample's end
     * ({@link #endOf}), where JMeter's Active Threads Over Time keys it (T7).
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
        this.wholeRun = new SamplerAggregator(WHOLE_RUN, apdexThreshold);
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
     * Get time-series data sorted by timestamp. Each second's totals are its samples of the whole run,
     * pooled by sample as they arrived ({@link TimeBucketAggregator}).
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
        synchronized (errorRecords) {
            return requestErrorsSeen > MAX_ERROR_RECORDS;
        }
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

        // Sampler statistics, and the whole run's own row
        data.setSamplerStatistics(getStatisticsSnapshot());
        data.setWholeRun(wholeRun.toStatistics(apdexThreshold));

        // Time series
        data.setTimeSeries(getTimeSeries());

        // Error records
        data.setErrorRecords(getErrorRecords());
        data.setErrorRecordsTruncated(isErrorRecordsTruncated());
        data.setRequestSampleCount(totalSamples.get() - transactionRowSamples.get());

        // Auto-hide utility samplers and 0-request samplers
        data.setHiddenSamplers(computeHiddenSamplers(data.getSamplerStatistics()));

        // Detect transaction controller hierarchy. Must run before the error summaries, which
        // express counts as a percentage of the top-level population — that population is only
        // known once children have been marked.
        data.setTransactionHierarchy(detectTransactionHierarchy(data.getSamplerStatistics()));

        // Error summaries — aggregate from error records
        data.setErrorSummaries(buildErrorSummaries());

        // Overall percentiles of the whole run's own distribution: every top-level sample, the true
        // global percentile, not an average of per-sampler percentiles
        computeOverallPercentiles(data);

        return data;
    }

    /**
     * Set the true overall percentiles from the whole run's distribution, and keep every
     * distribution reachable from the report data.
     *
     * <p>The four percentiles below are the ones the report renders. The distributions they came
     * from used to be dropped here, which meant p99.9 — or any quantile a rule names — could
     * not be answered afterwards at any price. They are now attached to {@link ReportData};
     * see {@link com.jmeterwebinsightreport.core.model.PercentileSource} for why that is
     * preferable to deciding the percentile set before the statistics are built.
     *
     * <p>The whole run's distribution holds the samples a listener in the plan received: a
     * transaction's requests are inside it, so including them would weight those samples twice. It
     * is fed per sample ({@link #wholeRun}), so a name that also ran inside a transaction keeps its
     * own runs; merging the top-level names' distributions lost them.
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
        }
        wholeRun.mergePercentilesInto(overall);

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

        // Each parent's children in the order first seen (snapshot under the set's own monitor —
        // Collections.synchronizedSet does not guard iteration), parents by name: the map's own order
        // is a hash map's, and the payload's hierarchy and every choice below are the same every run.
        Map<String, List<String>> observed = new TreeMap<>();
        Map<String, java.util.SortedSet<String>> parentsOf = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : explicitTransactions.entrySet()) {
            String parent = entry.getKey();
            if (!statsByName.containsKey(parent)) continue;
            Set<String> childSet = entry.getValue();
            List<String> children;
            synchronized (childSet) {
                children = new ArrayList<>(childSet);
            }
            observed.put(parent, children);
            for (String child : children) {
                if (statsByName.containsKey(child) && !child.equals(parent)) {
                    parentsOf.computeIfAbsent(child, k -> new java.util.TreeSet<>()).add(parent);
                }
            }
        }

        // One row per name, so one place in the table: the first parent by name that closes no loop.
        // It used to be whichever parent the hash map visited last, and the page listed the row under
        // every parent. A name that also ran on its own is a top-level row (namesSeenTopLevel). Every
        // other parent is named on the row instead.
        Map<String, String> placedUnder = new HashMap<>();
        for (Map.Entry<String, java.util.SortedSet<String>> entry : parentsOf.entrySet()) {
            String child = entry.getKey();
            List<String> elsewhere = new ArrayList<>(entry.getValue());
            if (!namesSeenTopLevel.contains(child)) {
                for (String candidate : entry.getValue()) {
                    if (!closesALoop(child, candidate, placedUnder)) {
                        placedUnder.put(child, candidate);
                        elsewhere.remove(candidate);
                        break;
                    }
                }
            }
            SamplerStatistics childStat = statsByName.get(child);
            childStat.setParentSamplerName(placedUnder.get(child));
            childStat.setAlsoRunsInside(elsewhere);
        }

        for (Map.Entry<String, List<String>> entry : observed.entrySet()) {
            String parent = entry.getKey();
            SamplerStatistics parentStat = statsByName.get(parent);
            List<String> children = new ArrayList<>();
            for (String child : entry.getValue()) {
                if (parent.equals(placedUnder.get(child))) {
                    children.add(child);
                }
            }
            boolean transactionController = transactionControllerParents.contains(parent);
            if (!children.isEmpty() || transactionController) {
                // Nest every parent that produced sub-results, but only badge the ones that
                // really are Transaction Controllers. A Transaction Controller keeps its badge when
                // every name inside it also ran on its own, and so has no row under it.
                parentStat.setTransactionController(transactionController);
                parentStat.setChildSamplerNames(children);
                hierarchy.put(parent, children);
            }
        }

        return hierarchy;
    }

    /** Whether placing {@code child} under {@code parent} would make the child its own ancestor. */
    private static boolean closesALoop(String child, String parent, Map<String, String> placedUnder) {
        Set<String> seen = new HashSet<>();
        for (String p = parent; p != null; p = placedUnder.get(p)) {
            if (p.equals(child) || !seen.add(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build error summaries by grouping error records by response code.
     *
     * <p>Two populations, each over its own: the failed requests (every sample but a Transaction
     * Controller's own result, transaction children included), which are the Errors tab's rows and
     * divide by every request — JMeter's HTML report's Errors table; and a transaction's own failures,
     * which only the asserts read. Each group's percentages divide by its own population, so a
     * population's rows add up to 100% of its errors and never past 100% of its samples.
     */
    private List<ErrorSummary> buildErrorSummaries() {
        long transactionSamples = transactionRowSamples.get();
        long requestSamples = totalSamples.get() - transactionSamples;

        // Group by sampler + response code — from the UNBOUNDED aggregation (not the capped list),
        // so counts and percentages are correct even when > MAX_ERROR_RECORDS errors occurred.
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, long[]> spans = new LinkedHashMap<>();
        Map<String, ErrorRecord> firstRecords;
        Map<String, Long> topLevelCounts;
        synchronized (errorRecords) {
            topLevelCounts = new HashMap<>(errorSummaryTopLevelCounts);
            for (Map.Entry<String, long[]> e : errorSummaryCounts.entrySet()) {
                counts.put(e.getKey(), e.getValue()[0]);
                spans.put(e.getKey(), new long[]{e.getValue()[1], e.getValue()[2]});
            }
            firstRecords = new LinkedHashMap<>(errorSummaryFirst);
        }

        if (counts.isEmpty()) {
            return Collections.emptyList();
        }
        long requestErrors = 0;
        long transactionErrors = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (isTransactionRow(firstRecords.get(entry.getKey()).getResponseMessage())) {
                transactionErrors += entry.getValue();
            } else {
                requestErrors += entry.getValue();
            }
        }

        List<ErrorSummary> result = new ArrayList<>(counts.size());
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            ErrorRecord rec = firstRecords.get(entry.getKey());
            boolean transactionRow = isTransactionRow(rec.getResponseMessage());
            long errors = transactionRow ? transactionErrors : requestErrors;
            long total = transactionRow ? transactionSamples : requestSamples;
            ErrorSummary s = new ErrorSummary();
            s.setTransactionRow(transactionRow);
            s.setSamplerName(rec.getSamplerName());
            s.setResponseCode(rec.getResponseCode());
            s.setResponseMessage(rec.getResponseMessage());
            s.setFailureMessage(rec.getFailureMessage());
            s.setErrorType(rec.getErrorType());
            s.setOccurrenceCount(entry.getValue());
            s.setTopLevelOccurrenceCount(topLevelCounts.getOrDefault(entry.getKey(), 0L));
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
        wholeRun = new SamplerAggregator(WHOLE_RUN, apdexThreshold);
        timeBuckets.clear();
        synchronized (errorRecords) {
            errorRecords.clear();
            errorSummaryCounts.clear();
            errorSummaryFirst.clear();
            errorSummaryTopLevelCounts.clear();
        }
        explicitTransactions.clear();
        transactionControllerParents.clear();
        namesSeenTopLevel.clear();
        totalSamples.set(0);
        totalErrors.set(0);
        transactionRowSamples.set(0);
        synchronized (errorRecords) {
            requestErrorsSeen = 0;
        }
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
