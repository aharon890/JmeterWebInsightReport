package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.PercentileSource;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;

/**
 * Per-sampler aggregator that maintains running statistics.
 * Thread-safe via synchronized methods.
 */
class SamplerAggregator {

    private final String samplerName;
    /** Every response time, exactly: percentiles as JMeter's Aggregate Report computes them (B2). */
    private final ResponseTimeDistribution responseTimes = new ResponseTimeDistribution();
    /** Response-time distribution of successful samples only — used for a success-aware Apdex. */
    private final ResponseTimeDistribution successResponseTimes = new ResponseTimeDistribution();
    private long sampleCount;
    private long errorCount;
    private double sumResponseTime;
    /** Welford accumulators. sumSq/n - mean^2 suffers catastrophic cancellation when the
     *  values are large with a small spread (batch jobs averaging ~1 hour with a few ms of
     *  variation exhaust double precision, and max(0, ...) then reports a std dev of 0). */
    private double runningMean;
    private double m2;
    /** The first sample decides both extremes. The max started at {@code Double.MIN_VALUE}, which
     *  is the smallest POSITIVE double (4.9E-324), not the most negative one, so a sampler whose
     *  every sample took 0 ms reported a max of 4.9E-324 and FAILed {@code max gt 0} (X1). An
     *  infinity never leaves this class: {@link #toStatistics} writes 0 for both while
     *  {@code sampleCount} is 0, as it always did. */
    private double minResponseTime = Double.POSITIVE_INFINITY;
    private double maxResponseTime = Double.NEGATIVE_INFINITY;
    private long sumBytesReceived;
    private long sumBytesSent;
    private long sumConnectTime;
    private long sumLatency;
    private long connectTimeCount;
    private long latencyCount;
    private long firstTimestamp = Long.MAX_VALUE;
    private long lastTimestamp = Long.MIN_VALUE;
    /** End of the last sample (start + elapsed). Throughput must be measured over the window
     *  the sampler actually occupied, not just the window in which it started requests. */
    private long lastSampleEnd = Long.MIN_VALUE;

    /** Apdex threshold this aggregator counts against, fixed when it is created. */
    private final double configuredApdexThreshold;
    /** Successful samples with responseTime <= T. Apdex is defined on t <= T, and a t-digest
     *  CDF interpolates, so mass sitting exactly on the threshold contributes only half a
     *  sample: 100 successes all at exactly 500 ms with T=500 scored 0.75 instead of 1.00.
     *  Counting the boundary exactly removes that error entirely. */
    private long satisfiedCount;
    /** Successful samples with T < responseTime <= 4T. */
    private long toleratingCount;

    SamplerAggregator(String samplerName) {
        this(samplerName, DEFAULT_APDEX_THRESHOLD);
    }

    SamplerAggregator(String samplerName, double apdexThreshold) {
        this.samplerName = samplerName;
        this.configuredApdexThreshold = apdexThreshold > 0 ? apdexThreshold : DEFAULT_APDEX_THRESHOLD;
    }

    static final double DEFAULT_APDEX_THRESHOLD = 500;

    synchronized void addSample(long responseTime, boolean isSuccess,
                                 long bytesReceived, long bytesSent, long timestamp,
                                 long connectTime, long latency) {
        addSample(responseTime, isSuccess, bytesReceived, bytesSent, timestamp,
                timestamp + Math.max(0, responseTime), connectTime, latency);
    }

    /**
     * @param endTime when the sample really ended. Usually {@code timestamp + responseTime}, but a
     *        Transaction Controller's parent sample excludes its timers from its elapsed time and
     *        still ends after them (B7): its window has to reach that end, as JMeter's Aggregate
     *        Report reaches {@code getEndTime()}.
     */
    synchronized void addSample(long responseTime, boolean isSuccess,
                                 long bytesReceived, long bytesSent, long timestamp, long endTime,
                                 long connectTime, long latency) {
        addSample(responseTime, isSuccess, bytesReceived, bytesSent, timestamp, endTime,
                connectTime, latency, 1, isSuccess ? 0 : 1);
    }

    /**
     * @param samples how many samples this result stands for: 1, or more for a result JMeter
     *        aggregated (distributed tests in Statistical mode, {@code SampleResult.getSampleCount()}).
     *        As JMeter's {@code StatCalculator.addValue(value, count)} does, it counts as that many
     *        samples of {@code responseTime / samples} ms each (whole ms, as JMeter divides a Long),
     *        and its bytes once (B6).
     * @param errors how many of them failed ({@code SampleResult.getErrorCount()})
     */
    synchronized void addSample(long responseTime, boolean isSuccess,
                                 long bytesReceived, long bytesSent, long timestamp, long endTime,
                                 long connectTime, long latency, long samples, long errors) {
        long n = Math.max(1, samples);
        long failed = Math.min(n, Math.max(0, errors));
        long ok = n - failed;
        long each = responseTime / n;
        double eachExact = (double) responseTime / n;
        sampleCount += n;
        errorCount += failed;
        sumResponseTime += responseTime;
        // Welford for n equal values: the weighted form of the one-value update
        double delta = eachExact - runningMean;
        runningMean += delta * n / sampleCount;
        m2 += delta * (eachExact - runningMean) * n;
        minResponseTime = Math.min(minResponseTime, each);
        maxResponseTime = Math.max(maxResponseTime, each);
        sumBytesReceived += bytesReceived;
        sumBytesSent += bytesSent;
        firstTimestamp = Math.min(firstTimestamp, timestamp);
        lastTimestamp = Math.max(lastTimestamp, timestamp);
        lastSampleEnd = Math.max(lastSampleEnd, endTime);
        responseTimes.add(each, n);
        if (ok > 0) {
            successResponseTimes.add(each, ok);
            if (each <= configuredApdexThreshold) {
                satisfiedCount += ok;
            } else if (each <= configuredApdexThreshold * 4) {
                toleratingCount += ok;
            }
        }

        if (connectTime >= 0) {
            sumConnectTime += connectTime;
            connectTimeCount++;
        }
        if (latency >= 0) {
            sumLatency += latency;
            latencyCount++;
        }
    }

    /** Merge this sampler's response times into a target distribution (for overall percentiles). */
    synchronized void mergePercentilesInto(ResponseTimeDistribution target) {
        target.merge(responseTimes);
    }

    /**
     * A handle on this sampler's distribution that outlives the four percentiles copied into
     * {@link SamplerStatistics}.
     *
     * <p>Returns a view rather than the distribution itself, and reads under this aggregator's
     * monitor: the report is built from {@code testEnded}, but a listener instance is shared
     * by every sampler thread and by RMI workers in distributed mode, so there is no moment
     * at which "no more samples can arrive" is guaranteed by construction. An unsynchronised
     * read racing an {@code add()} walks arrays half-shifted by an insertion, which does not
     * throw — it just returns a wrong number, which is the worst way for a quality gate to fail.
     */
    PercentileSource percentileSource() {
        return new PercentileSource() {
            @Override
            public double percentile(double percentile) {
                synchronized (SamplerAggregator.this) {
                    return responseTimes.getPercentile(percentile);
                }
            }

            @Override
            public long sampleCount() {
                synchronized (SamplerAggregator.this) {
                    return responseTimes.getCount();
                }
            }
        };
    }

    synchronized SamplerStatistics toStatistics() {
        return toStatistics(configuredApdexThreshold);
    }

    synchronized SamplerStatistics toStatistics(double apdexThreshold) {
        SamplerStatistics stats = new SamplerStatistics();
        stats.setSamplerName(samplerName);
        stats.setSampleCount(sampleCount);
        stats.setErrorCount(errorCount);
        stats.setErrorRate(sampleCount > 0 ? (errorCount * 100.0) / sampleCount : 0);
        stats.setMeanResponseTime(sampleCount > 0 ? sumResponseTime / sampleCount : 0);
        stats.setMinResponseTime(sampleCount > 0 ? minResponseTime : 0);
        stats.setMaxResponseTime(sampleCount > 0 ? maxResponseTime : 0);

        // Standard deviation (population), from the Welford accumulators.
        if (sampleCount > 1) {
            stats.setStandardDeviation(Math.sqrt(m2 / sampleCount));
        }

        // Percentiles of the exact distribution, as the Aggregate Report computes them
        if (sampleCount > 0) {
            stats.setMedianResponseTime(responseTimes.getPercentile(50));
            stats.setPercentile90(responseTimes.getPercentile(90));
            stats.setPercentile95(responseTimes.getPercentile(95));
            stats.setPercentile99(responseTimes.getPercentile(99));
        }

        // Throughput (requests per second). The window runs from the first sample's start to
        // the LAST SAMPLE'S END, matching how JMeter's own dashboard measures it. Using the
        // last sample's *start* excluded its elapsed time, so a sampler whose requests all
        // began inside a narrow window reported a rate it never sustained — and a single
        // sample gave a zero-length window, which the old code papered over by reporting
        // "sampleCount per second": one request in a 10-minute test read as 1/s.
        if (sampleCount > 0) {
            long durationMs = lastSampleEnd - firstTimestamp;
            // Everything in the same millisecond with zero elapsed: no measurable window.
            // Charge it one millisecond rather than dividing by zero.
            double durationSec = Math.max(1L, durationMs) / 1000.0;
            stats.setThroughput(sampleCount / durationSec);
            stats.setReceivedBytesPerSec(sumBytesReceived / durationSec);
            stats.setSentBytesPerSec(sumBytesSent / durationSec);
        }

        // Connect time and latency averages
        if (connectTimeCount > 0) {
            stats.setMeanConnectTime((double) sumConnectTime / connectTimeCount);
        }
        if (latencyCount > 0) {
            stats.setMeanLatency((double) sumLatency / latencyCount);
        }
        stats.setTotalReceivedBytes(sumBytesReceived);
        stats.setTotalSentBytes(sumBytesSent);

        // Apdex computation — failures are frustrated regardless of speed (Apdex standard).
        // satisfied/tolerating are counted only among successful samples; the denominator is
        // the full sample count, so failed samples pull the score down.
        if (sampleCount > 0) {
            double apdexT = apdexThreshold > 0 ? apdexThreshold : DEFAULT_APDEX_THRESHOLD;
            double satisfied;
            double tolerating;
            if (apdexT == configuredApdexThreshold) {
                // Exact counts — no interpolation, so samples landing exactly on T or 4T are
                // classified by Apdex's own "t <= T" rule instead of being split in half.
                satisfied = satisfiedCount;
                tolerating = toleratingCount;
            } else {
                // The caller asked for a threshold this aggregator did not count against
                // (only reachable if the threshold is changed after sampling starts). Fall
                // back to the distribution, evaluating just above the boundary so an integer-ms
                // sample equal to T still counts as satisfied.
                long okCount = sampleCount - errorCount;
                satisfied = 0;
                tolerating = 0;
                if (okCount > 0) {
                    double satisfiedFraction = successResponseTimes.getCdf(apdexT + 0.5);
                    double toleratingFraction =
                            successResponseTimes.getCdf(apdexT * 4 + 0.5) - satisfiedFraction;
                    satisfied = okCount * satisfiedFraction;
                    tolerating = okCount * toleratingFraction;
                }
            }
            double apdex = (satisfied + tolerating / 2.0) / sampleCount;
            stats.setApdexScore(Math.round(apdex * 100.0) / 100.0);
            stats.setApdexSatisfied(satisfied);
            stats.setApdexTolerating(tolerating);
        }

        return stats;
    }
}
