package com.jmeterwebinsightreport.core.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact response-time counts, answering percentiles as JMeter's Aggregate Report does (B2): the
 * value at rank round(n * p), p computed in float as the Aggregate Report's columns compute it.
 * {@code ResponseTimeDistributionJMeterParityTest} (report module) holds it to JMeter's own
 * {@code StatCalculator} over random runs; these pin the cases the check named.
 */
class ResponseTimeDistributionTest {

    private static ResponseTimeDistribution of(long... values) {
        ResponseTimeDistribution d = new ResponseTimeDistribution();
        for (long v : values) {
            d.add(v);
        }
        return d;
    }

    @Test
    void nothingRecordedAnswersZero() {
        ResponseTimeDistribution d = new ResponseTimeDistribution();
        assertEquals(0.0, d.getPercentile(95));
        assertEquals(0.0, d.getCdf(100));
        assertEquals(0, d.getCount());
    }

    @Test
    void thePercentileIsTheValueAtRankRoundNTimesP() {
        ResponseTimeDistribution d = of(10, 9, 8, 7, 6, 5, 4, 3, 2, 1);   // added out of order
        assertEquals(5, d.getPercentile(50));     // rank round(5.0) = 5
        assertEquals(9, d.getPercentile(90));     // round(10 * 0.9f = 8.99999976) = 9
        assertEquals(9, d.getPercentile(95));     // round(9.4999998) = 9
        assertEquals(10, d.getPercentile(99));    // round(9.90000010) = 10
        assertEquals(10, d.getPercentile(100));   // p >= 1: the largest
        assertEquals(1, d.getPercentile(0));      // rank 0: the smallest
    }

    @Test
    void theFloatFractionDecidesATieAsTheAggregateReportDoes() {
        // P01 "Browse by Category": 5 requests. 5 * 0.9f = 4.4999999 rounds to rank 4 (25 ms); with
        // the double 0.9, 4.5 would round to rank 5 (72 ms). JMeter's table says 25.
        assertEquals(25, of(17, 17, 17, 25, 72).getPercentile(90));
    }

    @Test
    void theCheckedExamplesReadAsJMeterReadsThem() {
        // P01 "Browse Pets", 6 requests: median rank 3 = 19 (the t-digest said 24), P90 rank
        // round(5.4) = 5 = 30 (the t-digest said 759).
        ResponseTimeDistribution browsePets = of(16, 18, 19, 24, 30, 759);
        assertEquals(19, browsePets.getPercentile(50));
        assertEquals(30, browsePets.getPercentile(90));

        // pilot-A: 110 requests, 109 at 6 ms or less and one of 25 ms. P99 rank round(108.9) = 109.
        ResponseTimeDistribution pilotA = new ResponseTimeDistribution();
        pilotA.add(6, 109);
        pilotA.add(25);
        assertEquals(6, pilotA.getPercentile(99));
    }

    @Test
    void aGapInTheDistributionIsNeverAnsweredWithATimeNoRequestTook() {
        // S24's shape: 99 % about 20 ms, 1 % at 3-8 s. The t-digest's overall P99 read 1,211 ms,
        // between the two groups; the 99th value is a fast request.
        ResponseTimeDistribution d = new ResponseTimeDistribution();
        d.add(20, 990);
        d.add(5000, 10);
        assertEquals(20, d.getPercentile(99));
        assertEquals(5000, d.getPercentile(99.9));   // round(999.0) = 999: the first slow one
    }

    @Test
    void aWeightedAddIsThatManySamples() {
        ResponseTimeDistribution weighted = new ResponseTimeDistribution();
        weighted.add(100, 3);
        weighted.add(200, 1);
        ResponseTimeDistribution plain = of(100, 100, 100, 200);
        assertEquals(4, weighted.getCount());
        for (double p : new double[]{25, 50, 75, 90, 99}) {
            assertEquals(plain.getPercentile(p), weighted.getPercentile(p), "p" + p);
        }
        weighted.add(300, 0);
        assertEquals(4, weighted.getCount(), "a count of 0 adds nothing");
    }

    @Test
    void mergingIsTheUnionAndLeavesTheSourceAsItWas() {
        ResponseTimeDistribution a = of(1, 2, 3, 1000);
        ResponseTimeDistribution b = of(2, 4, 5);
        double before = a.getPercentile(99);

        ResponseTimeDistribution all = new ResponseTimeDistribution();
        all.merge(a);
        all.merge(b);

        assertEquals(7, all.getCount());
        assertEquals(of(1, 2, 2, 3, 4, 5, 1000).getPercentile(90), all.getPercentile(90));
        assertEquals(before, a.getPercentile(99), "the t-digest's merge changed its source; counts do not");
        assertEquals(4, a.getCount());
    }

    @Test
    void theCdfAndTheBinsAreCounts() {
        ResponseTimeDistribution d = of(10, 50, 50, 100, 499, 500, 501, 4000);
        assertEquals(6 / 8.0, d.getCdf(500), 1e-12);    // t <= 500
        assertEquals(6 / 8.0, d.getCdf(500.5), 1e-12);
        assertEquals(1 / 8.0, d.getCdf(49), 1e-12);
        assertEquals(1, d.countBetween(0, 50));        // [0, 50)
        assertEquals(3, d.countBetween(50, 200));
        assertEquals(1, d.countBetween(2000, Long.MAX_VALUE));
    }

    @Test
    void manyDistinctValuesKeepTheirOrder() {
        ResponseTimeDistribution d = new ResponseTimeDistribution();
        for (long v = 5000; v >= 1; v--) {
            d.add(v);
        }
        assertEquals(5000, d.getCount());
        assertEquals(4500, d.getPercentile(90));   // round(5000 * 0.9f) = 4500
        assertEquals(1, d.getPercentile(0));
        assertEquals(5000, d.getPercentile(100));
    }
}
