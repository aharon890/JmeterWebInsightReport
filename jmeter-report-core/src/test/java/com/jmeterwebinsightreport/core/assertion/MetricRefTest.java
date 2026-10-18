package com.jmeterwebinsightreport.core.assertion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link MetricRef} names a percentile, and that the name reads back as the same percentile.
 *
 * <p>The name is not cosmetic. {@code GatePayloadBuilder} ships it as the rule's metric, the CI/CD
 * JSON and JUnit print it, and a chart finds a rule's line by it, so a name that parses to a
 * different quantile than the rule judged describes a rule nobody wrote.
 */
class MetricRefTest {

    /**
     * RB8 (b). {@code canonicalPercentileName} trimmed a trailing zero "that came from parsing
     * 99.90" - but {@code Double.toString} writes the shortest decimal that reads back as the
     * double, which never ends in a zero except the ".0" of a whole number, and a whole number
     * returns before the trim. The trim fired only on an exponent that ends in 0:
     * {@code p0.0000000001} is 1.0E-10, and was named {@code p1.0E-1}, which parses back as p0.1.
     */
    @Test
    void aPercentileWithAnExponentKeepsItsExponent() {
        MetricRef tiny = MetricRef.parse("p0.0000000001");
        assertNotNull(tiny);
        assertEquals(1.0E-10, tiny.percentile());
        assertEquals("p1.0E-10", tiny.canonicalName());
        assertEquals(1.0E-10, MetricRef.parse(tiny.canonicalName()).percentile(),
                "the name reads back as the percentile the rule judged, not p0.1");

        assertEquals("p1.0E-20", MetricRef.parse("p1e-20").canonicalName());
        assertEquals("p2.5E-10", MetricRef.parse("p0.00000000025").canonicalName());
        // An exponent that does not end in 0 was never cut, and the plain spellings never were.
        assertEquals("p1.0E-4", MetricRef.parse("p0.0001").canonicalName());
        assertEquals("p0.001", MetricRef.parse("p0.001").canonicalName());
        assertEquals("p99.9", MetricRef.parse("p99.90").canonicalName());
        assertEquals("p97.5", MetricRef.parse("p97.5").canonicalName());
        assertEquals("p95", MetricRef.parse("p95.0").canonicalName());
    }

    /**
     * Every percentile's name reads back as that percentile, and as that same name. Swept over a
     * power of ten and a mantissa at every exponent a double in (0, 100) can carry, subnormals
     * included, plus the plain decimals the editor offers.
     */
    @Test
    void everyPercentileNameReadsBackAsItsPercentile() {
        List<Double> ps = new ArrayList<>();
        for (int k = 1; k <= 323; k++) {
            ps.add(Double.parseDouble("1e-" + k));
            ps.add(Double.parseDouble("2.5e-" + k));
            ps.add(Double.parseDouble("9.75e-" + k));
        }
        ps.add(Double.MIN_VALUE);
        for (double p : new double[] {0.001, 0.0015, 0.5, 1.5, 12.34567, 50, 75, 97.5, 99.9, 99.99, 99.999}) {
            ps.add(p);
        }
        List<String> wrong = new ArrayList<>();
        for (double p : ps) {
            if (!(p > 0 && p < 100)) {
                continue;
            }
            MetricRef ref = MetricRef.parse("p" + p);
            assertNotNull(ref, "p" + p);
            String name = ref.canonicalName();
            MetricRef back = MetricRef.parse(name);
            if (back == null || back.percentile() != p || !name.equals(back.canonicalName())) {
                wrong.add(p + " -> " + name + " -> " + (back == null ? "null" : back.percentile()));
            }
        }
        assertTrue(wrong.isEmpty(), "names that read back as another percentile: " + wrong);
    }

    @Test
    void theStandardGridKeepsItsNames() {
        List<String> vocabulary = MetricRef.vocabulary(false);
        for (String name : new String[] {"p50", "p75", "p90", "p95", "p99", "p99.9"}) {
            assertTrue(vocabulary.contains(name), name + " in " + vocabulary);
        }
    }
}
