package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.model.ErrorRecord;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.TimeSeriesBucket;
import com.jmeterwebinsightreport.core.model.TimeSeriesBucket.SamplerBucketData;

import java.util.*;

/**
 * Builds specialized chart data structures from ReportData.
 * Extracted from ReportDataSerializer to keep each builder focused.
 */
class ChartDataBuilder {

    /**
     * Build response time distribution histogram data per sampler.
     * Creates bins: 0-50, 50-100, 100-200, 200-500, 500-1000, 1000-2000, 2000-5000, 5000+
     *
     * Sums the actual per-bucket response-time histograms (from each bucket's reservoir sample),
     * extrapolated to the bucket's true sample count. This preserves multi-modal distributions and
     * the tail, unlike binning per-second means (which collapses everything toward the center).
     */
    static Map<String, Object> buildResponseTimeDistribution(List<TimeSeriesBucket> timeSeries,
                                                              Set<String> samplerNames) {
        String[] binLabels = {"0-50", "50-100", "100-200", "200-500", "500-1K", "1K-2K", "2K-5K", "5K+"};

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("binLabels", Arrays.asList(binLabels));

        Map<String, List<Integer>> perSampler = new LinkedHashMap<>(samplerNames.size());
        for (String name : samplerNames) {
            double[] bins = new double[binLabels.length];
            for (TimeSeriesBucket bucket : timeSeries) {
                SamplerBucketData sd = bucket.getPerSamplerData().get(name);
                if (sd == null || sd.getHistogramBins() == null) continue;
                int[] hb = sd.getHistogramBins();
                long reservoir = 0;
                for (int c : hb) reservoir += c;
                if (reservoir == 0) continue;
                // Reservoir holds up to RESERVOIR_SIZE samples; scale to the bucket's true count.
                double scale = (double) sd.getSampleCount() / reservoir;
                for (int i = 0; i < bins.length && i < hb.length; i++) {
                    bins[i] += hb[i] * scale;
                }
            }
            List<Integer> binList = new ArrayList<>(bins.length);
            for (double b : bins) binList.add((int) Math.round(b));
            perSampler.put(name, binList);
        }
        result.put("perSampler", perSampler);
        return result;
    }

    /**
     * Build heatmap data: per-sampler histogram bins over time.
     * Output format: { bins: [...labels], timestamps: [...], perSampler: { name: [[bin0,bin1,...], ...] } }
     */
    static Map<String, Object> buildHeatmapData(List<TimeSeriesBucket> timeSeries, Set<String> samplerNames) {
        String[] binLabels = {"0-50", "50-100", "100-200", "200-500", "500-1K", "1K-2K", "2K-5K", "5K+"};
        int numBins = binLabels.length;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bins", Arrays.asList(binLabels));

        List<Long> timestamps = new ArrayList<>(timeSeries.size());
        for (TimeSeriesBucket bucket : timeSeries) {
            timestamps.add(bucket.getTimestamp().toEpochMilli());
        }
        result.put("timestamps", timestamps);

        // Per-sampler: array of histogram arrays per time bucket. Bins come from a bounded reservoir
        // (up to RESERVOIR_SIZE samples); scale them to the bucket's true sample count so heatmap
        // cell counts reflect actual volume rather than the reservoir size.
        Map<String, List<int[]>> perSampler = new LinkedHashMap<>();
        for (String name : samplerNames) {
            List<int[]> samplerBins = new ArrayList<>(timeSeries.size());
            for (TimeSeriesBucket bucket : timeSeries) {
                SamplerBucketData sd = bucket.getPerSamplerData().get(name);
                if (sd != null && sd.getHistogramBins() != null) {
                    samplerBins.add(scaleBinsToCount(sd.getHistogramBins(), sd.getSampleCount()));
                } else {
                    samplerBins.add(new int[numBins]);
                }
            }
            perSampler.put(name, samplerBins);
        }
        result.put("perSampler", perSampler);
        return result;
    }

    /** Scale reservoir histogram bins up to the bucket's true sample count. */
    private static int[] scaleBinsToCount(int[] bins, long sampleCount) {
        long reservoir = 0;
        for (int c : bins) reservoir += c;
        if (reservoir == 0 || sampleCount <= reservoir) {
            return bins;
        }
        double scale = (double) sampleCount / reservoir;
        int[] scaled = new int[bins.length];
        for (int i = 0; i < bins.length; i++) {
            scaled[i] = (int) Math.round(bins[i] * scale);
        }
        return scaled;
    }


}
