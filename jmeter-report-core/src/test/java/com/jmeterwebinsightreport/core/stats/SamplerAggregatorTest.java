package com.jmeterwebinsightreport.core.stats;

import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * X1. {@code maxResponseTime} started at {@code Double.MIN_VALUE}, which is the smallest
 * <em>positive</em> double (4.9E-324), not the most negative one. A sampler whose every sample
 * took 0 ms therefore reported a max of 4.9E-324 beside a min of 0: a {@code max gt 0} rule FAILed
 * it, JUnit printed some 320 zeros and the page wrote "5e-324 ms". The first sample decides both
 * extremes now. Every number is compared exactly, because a tolerance would pass the bug.
 */
class SamplerAggregatorTest {

    private static final long TS = 1_700_000_000_000L;

    private static SamplerStatistics of(long... responseTimes) {
        SamplerAggregator agg = new SamplerAggregator("s");
        for (int i = 0; i < responseTimes.length; i++) {
            agg.addSample(responseTimes[i], true, 100, 50, TS + i * 100L, 0, 0);
        }
        return agg.toStatistics();
    }

    @Test
    void anAllZeroSamplerHasAMaxAndMinOfZero() {
        SamplerStatistics s = of(0, 0, 0);
        assertEquals(0.0, s.getMaxResponseTime(), "no sample took 4.9E-324 ms");
        assertEquals(0.0, s.getMinResponseTime());
    }

    @Test
    void aSingleZeroSampleIsItsOwnMaxAndMin() {
        SamplerStatistics s = of(0);
        assertEquals(0.0, s.getMaxResponseTime());
        assertEquals(0.0, s.getMinResponseTime());
    }

    @Test
    void theExtremesAreSamplesTheSamplerHad() {
        SamplerStatistics s = of(0, 25, 7);
        assertEquals(25.0, s.getMaxResponseTime());
        assertEquals(0.0, s.getMinResponseTime());
    }

    /**
     * JMeter never writes a negative elapsed, but a JTL is a text file and the parser takes the
     * long it is given. The max is then the largest sample, as every other max is - not
     * 4.9E-324, a value no sample had, above every sample the sampler did have.
     */
    @Test
    void aSamplerOfNegativeTimesHasItsLargestAsItsMax() {
        SamplerStatistics s = of(-5, -3, -9);
        assertEquals(-3.0, s.getMaxResponseTime());
        assertEquals(-9.0, s.getMinResponseTime());
    }

    /**
     * An aggregator that never saw a sample reports 0 for both, as it always did: the infinities
     * that start the comparison never leave the aggregator. Unreachable through the engine, which
     * creates an aggregator only to add a sample to it, and kept anyway.
     */
    @Test
    void anEmptyAggregatorStillReportsZero() {
        SamplerStatistics s = new SamplerAggregator("empty").toStatistics();
        assertEquals(0L, s.getSampleCount());
        assertEquals(0.0, s.getMaxResponseTime());
        assertEquals(0.0, s.getMinResponseTime());
    }
}
