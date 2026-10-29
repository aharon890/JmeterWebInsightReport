package com.jmeterwebinsightreport.report.stats;

import com.jmeterwebinsightreport.core.stats.ResponseTimeDistribution;
import org.apache.jorphan.math.StatCalculatorLong;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The report's percentiles against JMeter's own, value for value (B2).
 *
 * <p>The Aggregate Report keeps a {@code StatCalculatorLong} per label and reads each percentile
 * column with {@code getPercentPoint(Float.parseFloat(label) / 100)} ({@code StatGraphVisualizer},
 * the median with {@code getMedian()}). This feeds both the same random runs — small ones, where
 * the rounding and the float decide the rank, and long-tailed ones with gaps, where the t-digest
 * used to answer a time no request took — and demands the same number for every percentile the
 * report shows or a rule can name. Lives in this module because JMeter is on its classpath.
 */
class ResponseTimeDistributionJMeterParityTest {

    private static final String[] PERCENTILES = {"50", "75", "90", "95", "99", "99.9", "99.99", "1", "10", "33.3"};

    @Test
    void everyPercentileEqualsTheAggregateReportsOnRandomRuns() {
        Random random = new Random(20261030L);
        int checked = 0;
        for (int run = 0; run < 400; run++) {
            int n = run < 200 ? 1 + run % 40 : 1 + random.nextInt(5000);
            ResponseTimeDistribution ours = new ResponseTimeDistribution();
            StatCalculatorLong jmeter = new StatCalculatorLong();
            for (int i = 0; i < n; i++) {
                // Mostly fast, a slow tail with a gap between them, as S24 and S12 were.
                long v = random.nextInt(100) < 97 ? 5 + random.nextInt(40) : 3000 + random.nextInt(5000);
                ours.add(v);
                jmeter.addValue(v);
            }
            assertEquals(jmeter.getMedian().longValue(), ours.getPercentile(50), 0.0, "median, n=" + n);
            for (String label : PERCENTILES) {
                float fraction = Float.parseFloat(label) / 100;
                assertEquals(jmeter.getPercentPoint(fraction).longValue(), ours.getPercentile(Double.parseDouble(label)), 0.0,
                        "p" + label + ", n=" + n);
                checked++;
            }
            assertEquals(jmeter.getCount(), ours.getCount());
        }
        assertEquals(4000, checked);
    }
}
