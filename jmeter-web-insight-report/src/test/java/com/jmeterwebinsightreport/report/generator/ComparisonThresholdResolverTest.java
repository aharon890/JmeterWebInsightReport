package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.comparison.ComparisonThresholds;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The five {@code webinsight.compare.*} properties are documented in README.md,
 * WIKI/FEATURES.md and WIKI/Tutorial/06-Compare-Tab.md, and before this file nothing
 * tested them: the layering lived in a private method that read JMeterUtils statically
 * from inside a full generate() run, so deleting it wholesale left every suite green.
 *
 * The property names are written here as literals, deliberately. Sharing a constant with
 * the resolver would make a rename invisible — the test and the code would move together
 * while every documented -J flag silently stopped working.
 *
 * Log output is not asserted: there is no slf4j binding on the test classpath, so a
 * logger in a unit test is a no-op (see ReportArtifactsTest). These assert behaviour.
 */
class ComparisonThresholdResolverTest {

    private static final String P95 = "webinsight.compare.p95.threshold";
    private static final String ERR = "webinsight.compare.errorrate.threshold";
    private static final String MEAN = "webinsight.compare.mean.threshold";
    private static final String P99 = "webinsight.compare.p99.threshold";
    private static final String TP = "webinsight.compare.throughput.threshold";

    /** A lookup over a plain map — no JMeterUtils, no static state to reset. */
    private static Map<String, String> props(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static ReportAnnotations annotationsWith(Double p95, Double err, Double mean,
                                                    Double p99, Double throughput) {
        ReportAnnotations.ComparisonThresholdConfig c =
                new ReportAnnotations.ComparisonThresholdConfig();
        c.setP95PctChange(p95);
        c.setErrorRateChange(err);
        c.setMeanPctChange(mean);
        c.setP99PctChange(p99);
        c.setThroughputPctChange(throughput);
        ReportAnnotations a = new ReportAnnotations();
        a.setComparisonThresholds(c);
        return a;
    }

    private static ComparisonThresholds resolve(ReportAnnotations a, Map<String, String> p) {
        return ComparisonThresholdResolver.resolve(a, p::get);
    }

    // ---- defaults ------------------------------------------------------------

    @Test
    void nothingConfigured_keepsTheDocumentedDefaults() {
        ComparisonThresholds t = resolve(null, props());

        assertEquals(10.0, t.getP95PctChangeThreshold());
        assertEquals(2.0, t.getErrorRateChangeThreshold());
        assertEquals(-1.0, t.getMeanPctChangeThreshold());
        assertEquals(-1.0, t.getP99PctChangeThreshold());
        assertEquals(-1.0, t.getThroughputPctChangeThreshold());
    }

    @Test
    void annotationsPresentButEmpty_keepsTheDefaults() {
        ComparisonThresholds t = resolve(new ReportAnnotations(), props());

        assertEquals(10.0, t.getP95PctChangeThreshold());
        assertEquals(2.0, t.getErrorRateChangeThreshold());
    }

    // ---- layer 1: annotations ------------------------------------------------

    @Test
    void annotationsSupplyAllFiveThresholds() {
        ComparisonThresholds t = resolve(annotationsWith(1.0, 2.5, 3.0, 4.0, 5.0), props());

        assertEquals(1.0, t.getP95PctChangeThreshold());
        assertEquals(2.5, t.getErrorRateChangeThreshold());
        assertEquals(3.0, t.getMeanPctChangeThreshold());
        assertEquals(4.0, t.getP99PctChangeThreshold());
        assertEquals(5.0, t.getThroughputPctChangeThreshold());
    }

    @Test
    void anAnnotationFieldLeftNull_keepsThatMetricsDefault() {
        // Only mean is configured; the other four must not be zeroed by the null.
        ComparisonThresholds t = resolve(annotationsWith(null, null, 42.0, null, null), props());

        assertEquals(42.0, t.getMeanPctChangeThreshold());
        assertEquals(10.0, t.getP95PctChangeThreshold());
        assertEquals(2.0, t.getErrorRateChangeThreshold());
        assertEquals(-1.0, t.getP99PctChangeThreshold());
        assertEquals(-1.0, t.getThroughputPctChangeThreshold());
    }

    // ---- layer 2: -J properties ----------------------------------------------

    @Test
    void propertiesSupplyAllFiveThresholds() {
        ComparisonThresholds t = resolve(null,
                props(P95, "11", ERR, "12", MEAN, "13", P99, "14", TP, "15"));

        assertEquals(11.0, t.getP95PctChangeThreshold());
        assertEquals(12.0, t.getErrorRateChangeThreshold());
        assertEquals(13.0, t.getMeanPctChangeThreshold());
        assertEquals(14.0, t.getP99PctChangeThreshold());
        assertEquals(15.0, t.getThroughputPctChangeThreshold());
    }

    @Test
    void propertiesOverrideAnnotations() {
        // The documented precedence: -J beats report-annotations.json.
        ComparisonThresholds t = resolve(annotationsWith(1.0, 1.0, 1.0, 1.0, 1.0),
                props(P95, "99", ERR, "98", MEAN, "97", P99, "96", TP, "95"));

        assertEquals(99.0, t.getP95PctChangeThreshold());
        assertEquals(98.0, t.getErrorRateChangeThreshold());
        assertEquals(97.0, t.getMeanPctChangeThreshold());
        assertEquals(96.0, t.getP99PctChangeThreshold());
        assertEquals(95.0, t.getThroughputPctChangeThreshold());
    }

    @Test
    void anAbsentProperty_leavesTheAnnotationValueStanding() {
        ComparisonThresholds t = resolve(annotationsWith(7.0, 7.0, 7.0, 7.0, 7.0),
                props(P95, "20"));

        assertEquals(20.0, t.getP95PctChangeThreshold());
        assertEquals(7.0, t.getErrorRateChangeThreshold());
        assertEquals(7.0, t.getMeanPctChangeThreshold());
        assertEquals(7.0, t.getP99PctChangeThreshold());
        assertEquals(7.0, t.getThroughputPctChangeThreshold());
    }

    @Test
    void aDisabledThresholdCanBeSetExplicitly() {
        // -1 is the documented "disabled" value; it must survive as a real setting.
        ComparisonThresholds t = resolve(null, props(P95, "-1"));

        assertEquals(-1.0, t.getP95PctChangeThreshold());
        assertFalse(t.isEnabled(t.getP95PctChangeThreshold()));
    }

    // ---- malformed input -----------------------------------------------------

    @Test
    void aMalformedProperty_costsOnlyThatProperty() {
        // Was: all five reads shared one try/catch, so the first bad value abandoned
        // every property after it — mean, p99 and throughput were never read at all.
        ComparisonThresholds t = resolve(null,
                props(P95, "abc", ERR, "12", MEAN, "13", P99, "14", TP, "15"));

        assertEquals(10.0, t.getP95PctChangeThreshold(), "the bad value must fall back to the default");
        assertEquals(12.0, t.getErrorRateChangeThreshold());
        assertEquals(13.0, t.getMeanPctChangeThreshold());
        assertEquals(14.0, t.getP99PctChangeThreshold());
        assertEquals(15.0, t.getThroughputPctChangeThreshold());
    }

    @Test
    void aMalformedProperty_fallsBackToTheAnnotationValueNotTheDefault() {
        ComparisonThresholds t = resolve(annotationsWith(3.0, null, null, null, null),
                props(P95, "not-a-number"));

        assertEquals(3.0, t.getP95PctChangeThreshold());
    }

    // ---- non-finite input (L5) -------------------------------------------------
    //
    // Double.parseDouble accepts "Infinity", "-Infinity" and "NaN" as text, and overflows "1e400"
    // to Infinity, so each of these used to be taken in silence: a p95 threshold of Infinity (or
    // NaN, which every comparison reads as false) flags no regression whatever the runs did. Each
    // is refused like a malformed value — a warning, and the configured or default value kept.

    @Test
    void anOverflowingProperty_1e400_isRefusedAndKeepsTheAnnotationValue() {
        ComparisonThresholds t = resolve(annotationsWith(3.0, null, null, null, null),
                props(P95, "1e400", ERR, "6"));

        assertEquals(3.0, t.getP95PctChangeThreshold(), "1e400 overflows to Infinity and must be refused");
        assertEquals(6.0, t.getErrorRateChangeThreshold(), "a refused value costs only its own property");
    }

    @Test
    void anInfinityProperty_isRefusedAndKeepsTheDefault() {
        ComparisonThresholds t = resolve(null, props(P95, "Infinity"));

        assertEquals(10.0, t.getP95PctChangeThreshold());
    }

    @Test
    void aNegativeInfinityProperty_isRefusedAndKeepsTheAnnotationValue() {
        ComparisonThresholds t = resolve(annotationsWith(null, null, 9.0, null, null),
                props(MEAN, "-Infinity"));

        assertEquals(9.0, t.getMeanPctChangeThreshold());
    }

    @Test
    void aNaNProperty_isRefusedAndKeepsTheDefault() {
        ComparisonThresholds t = resolve(null, props(ERR, "NaN"));

        assertEquals(2.0, t.getErrorRateChangeThreshold());
    }

    @Test
    void anEmptyProperty_isTreatedAsUnset() {
        // -Jwebinsight.compare.p95.threshold= arrives as "", which parses to nothing.
        // Same shape as webinsight.error.body.maxsize in WebInsightReportListener.
        ComparisonThresholds t = resolve(annotationsWith(4.0, null, null, null, null),
                props(P95, "", ERR, "6"));

        assertEquals(4.0, t.getP95PctChangeThreshold());
        assertEquals(6.0, t.getErrorRateChangeThreshold());
    }

    // ---- the lookup itself ---------------------------------------------------

    @Test
    void aLookupThatThrows_doesNotSinkTheReport() {
        // JMeterUtils is absent in standalone mode; the report is still worth having.
        ComparisonThresholds t = ComparisonThresholdResolver.resolve(
                annotationsWith(8.0, null, null, null, null),
                key -> { throw new IllegalStateException("JMeterUtils not initialised"); });

        assertEquals(8.0, t.getP95PctChangeThreshold());
        assertEquals(2.0, t.getErrorRateChangeThreshold());
    }
}
