package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.model.ErrorRecord;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.TimeSeriesBucket;
import com.jmeterwebinsightreport.core.model.TimeSeriesBucket.SamplerBucketData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ChartDataBuilderTest {

    // --- Helper methods ---

    private static TimeSeriesBucket createBucket(Instant timestamp, String samplerName,
                                                  double meanResponseTime, long sampleCount) {
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(timestamp);
        bucket.setSampleCount(sampleCount);
        bucket.setMeanResponseTime(meanResponseTime);

        SamplerBucketData sd = new SamplerBucketData();
        sd.setMeanResponseTime(meanResponseTime);
        sd.setSampleCount(sampleCount);
        // Distribution is now built from the per-bucket histogram bins (populated from the reservoir
        // in production), not from binning the mean. Place all samples in the bin for the mean.
        sd.setHistogramBins(binsForValue(meanResponseTime, (int) sampleCount));

        Map<String, SamplerBucketData> perSampler = new LinkedHashMap<>();
        perSampler.put(samplerName, sd);
        bucket.setPerSamplerData(perSampler);

        return bucket;
    }

    /** Build an 8-bin histogram with {@code count} samples in the bin containing {@code value}. */
    private static int[] binsForValue(double value, int count) {
        long[] boundaries = SamplerBucketData.BIN_BOUNDARIES; // {50,100,200,500,1000,2000,5000}
        int[] bins = new int[boundaries.length + 1];
        int idx = boundaries.length;
        for (int i = 0; i < boundaries.length; i++) {
            if (value < boundaries[i]) { idx = i; break; }
        }
        bins[idx] = count;
        return bins;
    }

    private static ErrorRecord createErrorRecord(long timestamp, String responseCode,
                                                  String samplerName) {
        ErrorRecord er = new ErrorRecord();
        er.setTimestamp(timestamp);
        er.setResponseCode(responseCode);
        er.setSamplerName(samplerName);
        er.setResponseMessage("Error " + responseCode);
        er.setThreadName("Thread-1");
        return er;
    }

    // --- buildResponseTimeDistribution tests ---

    @Test
    @SuppressWarnings("unchecked")
    void buildResponseTimeDistribution_correctBinCount() {
        // Bin labels: 0-50, 50-100, 100-200, 200-500, 500-1K, 1K-2K, 2K-5K, 5K+ = 8 bins
        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        timeSeries.add(createBucket(Instant.now(), "GET /api/test", 100.0, 10));

        Set<String> samplerNames = new LinkedHashSet<>();
        samplerNames.add("GET /api/test");

        Map<String, Object> result = ChartDataBuilder.buildResponseTimeDistribution(timeSeries, samplerNames);

        assertNotNull(result);
        List<String> binLabels = (List<String>) result.get("binLabels");
        assertNotNull(binLabels);
        assertEquals(8, binLabels.size());
        assertEquals("0-50", binLabels.get(0));
        assertEquals("5K+", binLabels.get(7));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildResponseTimeDistribution_countsInCorrectBins() {
        // Create buckets with known mean response times mapping to specific bins
        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        String sampler = "GET /api/test";

        // 25ms -> bin 0 (0-50)
        timeSeries.add(createBucket(Instant.now(), sampler, 25.0, 5));
        // 75ms -> bin 1 (50-100)
        timeSeries.add(createBucket(Instant.now().plusSeconds(1), sampler, 75.0, 3));
        // 150ms -> bin 2 (100-200)
        timeSeries.add(createBucket(Instant.now().plusSeconds(2), sampler, 150.0, 7));
        // 350ms -> bin 3 (200-500)
        timeSeries.add(createBucket(Instant.now().plusSeconds(3), sampler, 350.0, 2));
        // 6000ms -> bin 7 (5K+)
        timeSeries.add(createBucket(Instant.now().plusSeconds(4), sampler, 6000.0, 1));

        Set<String> samplerNames = new LinkedHashSet<>();
        samplerNames.add(sampler);

        Map<String, Object> result = ChartDataBuilder.buildResponseTimeDistribution(timeSeries, samplerNames);
        Map<String, List<Integer>> perSampler = (Map<String, List<Integer>>) result.get("perSampler");
        List<Integer> bins = perSampler.get(sampler);

        assertNotNull(bins);
        assertEquals(8, bins.size());
        assertEquals(5, bins.get(0));  // 0-50: 5 samples at 25ms
        assertEquals(3, bins.get(1));  // 50-100: 3 samples at 75ms
        assertEquals(7, bins.get(2));  // 100-200: 7 samples at 150ms
        assertEquals(2, bins.get(3));  // 200-500: 2 samples at 350ms
        assertEquals(0, bins.get(4));  // 500-1K: none
        assertEquals(0, bins.get(5));  // 1K-2K: none
        assertEquals(0, bins.get(6));  // 2K-5K: none
        assertEquals(1, bins.get(7));  // 5K+: 1 sample at 6000ms
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildResponseTimeDistribution_emptyData() {
        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        Set<String> samplerNames = new LinkedHashSet<>();

        Map<String, Object> result = ChartDataBuilder.buildResponseTimeDistribution(timeSeries, samplerNames);

        assertNotNull(result);
        List<String> binLabels = (List<String>) result.get("binLabels");
        assertEquals(8, binLabels.size());

        Map<String, List<Integer>> perSampler = (Map<String, List<Integer>>) result.get("perSampler");
        assertNotNull(perSampler);
        assertTrue(perSampler.isEmpty(), "No samplers means no per-sampler data");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildResponseTimeDistribution_multipleSamplers() {
        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        String sampler1 = "GET /api/a";
        String sampler2 = "GET /api/b";

        // Bucket with two samplers
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(Instant.now());
        bucket.setSampleCount(20);
        bucket.setMeanResponseTime(100.0);

        SamplerBucketData sd1 = new SamplerBucketData();
        sd1.setMeanResponseTime(30.0); // bin 0 (0-50)
        sd1.setSampleCount(10);
        sd1.setHistogramBins(binsForValue(30.0, 10));

        SamplerBucketData sd2 = new SamplerBucketData();
        sd2.setMeanResponseTime(1500.0); // bin 5 (1K-2K)
        sd2.setSampleCount(10);
        sd2.setHistogramBins(binsForValue(1500.0, 10));

        Map<String, SamplerBucketData> perSamplerMap = new LinkedHashMap<>();
        perSamplerMap.put(sampler1, sd1);
        perSamplerMap.put(sampler2, sd2);
        bucket.setPerSamplerData(perSamplerMap);
        timeSeries.add(bucket);

        Set<String> samplerNames = new LinkedHashSet<>();
        samplerNames.add(sampler1);
        samplerNames.add(sampler2);

        Map<String, Object> result = ChartDataBuilder.buildResponseTimeDistribution(timeSeries, samplerNames);
        Map<String, List<Integer>> perSampler = (Map<String, List<Integer>>) result.get("perSampler");

        assertEquals(2, perSampler.size());
        assertEquals(10, perSampler.get(sampler1).get(0)); // 0-50 bin
        assertEquals(10, perSampler.get(sampler2).get(5)); // 1K-2K bin
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildResponseTimeDistribution_preservesBimodal() {
        // A5: a bucket whose samples are split between a fast and a slow bin must show BOTH humps,
        // not collapse to the mean bin (which the old mean-binning did).
        String sampler = "GET /api/bimodal";
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(Instant.now());
        SamplerBucketData sd = new SamplerBucketData();
        sd.setSampleCount(20);
        sd.setMeanResponseTime(1515.0); // mean lands in the 1K-2K bin — but no sample is actually there
        int[] bins = new int[8];
        bins[0] = 10; // 10 fast samples (0-50)
        bins[6] = 10; // 10 slow samples (2K-5K)
        sd.setHistogramBins(bins);
        Map<String, SamplerBucketData> perSamplerMap = new LinkedHashMap<>();
        perSamplerMap.put(sampler, sd);
        bucket.setPerSamplerData(perSamplerMap);

        Set<String> names = new LinkedHashSet<>(Collections.singletonList(sampler));
        Map<String, Object> result = ChartDataBuilder.buildResponseTimeDistribution(
                Collections.singletonList(bucket), names);
        List<Integer> out = ((Map<String, List<Integer>>) result.get("perSampler")).get(sampler);

        assertEquals(10, out.get(0), "fast hump preserved");
        assertEquals(10, out.get(6), "slow hump preserved");
        assertEquals(0, out.get(5), "no phantom count in the mean bin");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildResponseTimeDistribution_scalesReservoirToTrueCount() {
        // C22/A5: reservoir bins (bounded sample) are scaled up to the bucket's true sample count.
        String sampler = "GET /api/hot";
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(Instant.now());
        SamplerBucketData sd = new SamplerBucketData();
        sd.setSampleCount(1000);          // true volume
        int[] bins = new int[8];
        bins[2] = 100;                    // reservoir of 100 samples, all in 100-200
        sd.setHistogramBins(bins);
        Map<String, SamplerBucketData> perSamplerMap = new LinkedHashMap<>();
        perSamplerMap.put(sampler, sd);
        bucket.setPerSamplerData(perSamplerMap);

        Map<String, Object> result = ChartDataBuilder.buildResponseTimeDistribution(
                Collections.singletonList(bucket),
                new LinkedHashSet<>(Collections.singletonList(sampler)));
        List<Integer> out = ((Map<String, List<Integer>>) result.get("perSampler")).get(sampler);
        assertEquals(1000, out.get(2), "reservoir bins scaled to true sample count");
    }

    // --- buildHeatmapData tests ---

    @Test
    @SuppressWarnings("unchecked")
    void buildHeatmapData_returnsCorrectStructure() {
        Instant now = Instant.ofEpochMilli(1700000000000L);
        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        timeSeries.add(createBucket(now, "GET /api/test", 100.0, 10));

        Set<String> samplerNames = new LinkedHashSet<>();
        samplerNames.add("GET /api/test");

        Map<String, Object> result = ChartDataBuilder.buildHeatmapData(timeSeries, samplerNames);

        assertNotNull(result);
        assertTrue(result.containsKey("bins"), "Result should contain 'bins' key");
        assertTrue(result.containsKey("timestamps"), "Result should contain 'timestamps' key");
        assertTrue(result.containsKey("perSampler"), "Result should contain 'perSampler' key");

        List<String> bins = (List<String>) result.get("bins");
        assertEquals(8, bins.size());

        List<Long> timestamps = (List<Long>) result.get("timestamps");
        assertEquals(1, timestamps.size());
        assertEquals(1700000000000L, timestamps.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildHeatmapData_emptyTimeSeries_returnsEmpty() {
        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        Set<String> samplerNames = new LinkedHashSet<>();

        Map<String, Object> result = ChartDataBuilder.buildHeatmapData(timeSeries, samplerNames);

        assertNotNull(result);
        assertTrue(result.containsKey("bins"));
        List<Long> timestamps = (List<Long>) result.get("timestamps");
        assertNotNull(timestamps);
        assertTrue(timestamps.isEmpty());

        Map<String, ?> perSampler = (Map<String, ?>) result.get("perSampler");
        assertNotNull(perSampler);
        assertTrue(perSampler.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildHeatmapData_includesAllSamplers() {
        Instant now = Instant.ofEpochMilli(1700000000000L);
        String sampler1 = "GET /api/a";
        String sampler2 = "POST /api/b";

        // Create a bucket with two samplers
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(now);
        bucket.setSampleCount(20);
        bucket.setMeanResponseTime(100.0);

        SamplerBucketData sd1 = new SamplerBucketData();
        sd1.setMeanResponseTime(50.0);
        sd1.setSampleCount(10);

        SamplerBucketData sd2 = new SamplerBucketData();
        sd2.setMeanResponseTime(200.0);
        sd2.setSampleCount(10);

        Map<String, SamplerBucketData> perSamplerMap = new LinkedHashMap<>();
        perSamplerMap.put(sampler1, sd1);
        perSamplerMap.put(sampler2, sd2);
        bucket.setPerSamplerData(perSamplerMap);

        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        timeSeries.add(bucket);

        Set<String> samplerNames = new LinkedHashSet<>();
        samplerNames.add(sampler1);
        samplerNames.add(sampler2);

        Map<String, Object> result = ChartDataBuilder.buildHeatmapData(timeSeries, samplerNames);
        Map<String, ?> perSampler = (Map<String, ?>) result.get("perSampler");

        assertEquals(2, perSampler.size());
        assertTrue(perSampler.containsKey(sampler1));
        assertTrue(perSampler.containsKey(sampler2));
    }
}
