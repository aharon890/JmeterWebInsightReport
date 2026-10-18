package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.comparison.ComparisonThresholds;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * Builds {@link ComparisonThresholds} from annotations JSON and/or JMeter -J properties.
 * Precedence, lowest first: defaults, {@code report-annotations.json}, {@code -J} properties.
 *
 * <p>The property lookup arrives as a function rather than a static call to
 * {@code JMeterUtils.getProperty} so this is reachable without standing up JMeter. That is
 * the whole reason it is a class of its own: while the layering lived in a private method
 * of the generator, nothing could test it, and nothing did — the five documented
 * {@code webinsight.compare.*} properties could be deleted outright with every suite green.
 */
final class ComparisonThresholdResolver {

    private static final Logger log = LoggerFactory.getLogger(ComparisonThresholdResolver.class);

    static final String P95_PROPERTY = "webinsight.compare.p95.threshold";
    static final String ERROR_RATE_PROPERTY = "webinsight.compare.errorrate.threshold";
    static final String MEAN_PROPERTY = "webinsight.compare.mean.threshold";
    static final String P99_PROPERTY = "webinsight.compare.p99.threshold";
    static final String THROUGHPUT_PROPERTY = "webinsight.compare.throughput.threshold";

    private ComparisonThresholdResolver() {}

    static ComparisonThresholds resolve(ReportAnnotations annotations,
                                        Function<String, String> lookup) {
        ComparisonThresholds t = new ComparisonThresholds(); // defaults: p95>10%, errorRate>2%

        // Layer 1: from annotations JSON
        if (annotations != null && annotations.getComparisonThresholds() != null) {
            ReportAnnotations.ComparisonThresholdConfig c = annotations.getComparisonThresholds();
            if (c.getP95PctChange() != null) t.setP95PctChangeThreshold(c.getP95PctChange());
            if (c.getErrorRateChange() != null) t.setErrorRateChangeThreshold(c.getErrorRateChange());
            if (c.getMeanPctChange() != null) t.setMeanPctChangeThreshold(c.getMeanPctChange());
            if (c.getP99PctChange() != null) t.setP99PctChangeThreshold(c.getP99PctChange());
            if (c.getThroughputPctChange() != null) t.setThroughputPctChangeThreshold(c.getThroughputPctChange());
        }

        // Layer 2: JMeter -J properties override, one at a time.
        //
        // All five reads used to share a single try/catch, so the FIRST malformed value
        // abandoned every property after it: -Jwebinsight.compare.p95.threshold=abc silently
        // dropped mean, p99 and throughput as well, and the operator was told nothing.
        apply(lookup, P95_PROPERTY, t::setP95PctChangeThreshold);
        apply(lookup, ERROR_RATE_PROPERTY, t::setErrorRateChangeThreshold);
        apply(lookup, MEAN_PROPERTY, t::setMeanPctChangeThreshold);
        apply(lookup, P99_PROPERTY, t::setP99PctChangeThreshold);
        apply(lookup, THROUGHPUT_PROPERTY, t::setThroughputPctChangeThreshold);

        return t;
    }

    /**
     * Apply one property if it is set and parses to a finite number. An unset, empty,
     * unparseable or non-finite value leaves whatever the annotations or the defaults already
     * put there.
     *
     * <p>Non-finite is refused as malformed (L5): {@code Double.parseDouble} reads "Infinity",
     * "-Infinity" and "NaN" as text and overflows "1e400" to Infinity, and a threshold of any of
     * them flags nothing whatever the two runs did — a comparison asked for and never made.
     */
    private static void apply(Function<String, String> lookup, String property, DoubleConsumer setter) {
        String raw;
        try {
            raw = lookup.apply(property);
        } catch (RuntimeException e) {
            // JMeterUtils may not be available (standalone mode). Not worth a warning per
            // property — it is the same answer five times — and the report is still valid.
            return;
        }
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid {} '{}' — keeping the configured or default threshold",
                    property, raw);
            return;
        }
        if (!Double.isFinite(value)) {
            log.warn("Ignoring invalid {} '{}': it is not a finite number — keeping the configured"
                    + " or default threshold", property, raw);
            return;
        }
        setter.accept(value);
    }
}
