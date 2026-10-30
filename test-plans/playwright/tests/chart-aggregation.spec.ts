import { test, expect } from '@playwright/test';
import { loadClean } from './helpers';

/**
 * Chart aggregation math (CORE-5 / CORE-6, B11/B12/B14 remnants).
 *
 * The server emits one bucket per second, and only for seconds that had samples.
 * Collapsing those into a coarser granularity, or combining them across samplers
 * into the Σ Total line, has to respect what each metric means:
 *
 *   - rates (throughput, bytes)  → total over the bucket's full window, the last one too (T3)
 *   - averages and percentiles   → weighted by each second's sample count
 *   - max                        → a maximum, never an average
 *
 * These drive the pure functions directly so the assertions are exact. Windows sit on the clock's
 * grid, multiples of the granularity from the epoch (T2), so the rate tests start on a minute.
 */

/** One-second timestamps starting at t0. */
function seconds(t0: number, n: number): number[] {
  return Array.from({ length: n }, (_, i) => t0 + i * 1000);
}

test.describe('aggregateTimeSeries — metric-aware bucketing', () => {
  test('[R] errorRate is weighted by sample count, not averaged per second', async ({ page }) => {
    await loadClean(page, 'E02');

    // One second with a single failing sample (100% error) and one with 999 clean
    // samples (0%). True rate over the minute is 0.1%, not 50%.
    const value = await page.evaluate(() => {
      const w = window as any;
      const t0 = 1700000000000;
      const timestamps = [t0, t0 + 1000];
      const errorRates = [100, 0];
      const counts = [1, 999];
      const out = w.aggregateTimeSeries(timestamps, errorRates, 60000,
        w.aggOptsForMetric('errorRate', counts));
      return out[0][1];
    });

    expect(value).toBeCloseTo(0.1, 3);
  });

  test('[R] throughput counts idle seconds inside the bucket', async ({ page }) => {
    await loadClean(page, 'E02');

    // 6 active seconds at 10 rps inside an otherwise idle minute → 1 rps, not 10.
    // A second minute follows so the bursty one is a complete window, not the
    // trailing partial bucket.
    const value = await page.evaluate(() => {
      const w = window as any;
      const t0 = 1699999980000;   // on a minute, so the first window is these 60 seconds
      const activeSeconds = [0, 10, 20, 30, 40, 50];
      const timestamps = activeSeconds.map((s) => t0 + s * 1000);
      const throughputs = activeSeconds.map(() => 10);
      for (let s = 60; s < 120; s++) { timestamps.push(t0 + s * 1000); throughputs.push(1); }

      const out = w.aggregateTimeSeries(timestamps, throughputs, 60000,
        w.aggOptsForMetric('throughput'));
      return out[0][1];
    });

    // Averaging only the seconds that had samples would report 10 rps.
    expect(value).toBeCloseTo(1, 6);
  });

  test('[R] a trailing partial bucket is divided by its full window, as JMeter\'s charts divide it (T3)', async ({ page }) => {
    await loadClean(page, 'E02');

    // Two full minutes at 10 rps plus a 10-second tail at 10 rps. JMeter's TimeRateAggregator divides
    // every window by its width, so the tail reads 100 / 60 and the chart ends in the dip the run had.
    // This test used to pin the opposite (the tail divided by the 10 s it covered).
    const values = await page.evaluate(() => {
      const w = window as any;
      const t0 = 1699999980000;
      const timestamps: number[] = [];
      const throughputs: number[] = [];
      for (let s = 0; s < 130; s++) { timestamps.push(t0 + s * 1000); throughputs.push(10); }
      const out = w.aggregateTimeSeries(timestamps, throughputs, 60000,
        w.aggOptsForMetric('throughput'));
      return out.map((p: any[]) => p[1]);
    });

    expect(values.length).toBe(3);
    expect(values[0]).toBeCloseTo(10, 6);
    expect(values[1]).toBeCloseTo(10, 6);
    expect(values[2]).toBeCloseTo(100 / 60, 6);
  });

  test('[R] a single request in its window reads one per window, not one per second (T3)', async ({ page }) => {
    await loadClean(page, 'E02');

    // A run that stops 6 seconds into its last minute: that minute held 60 requests, 1 rps over the
    // window, as JMeter draws it. (pilot-D's one "Warmup Once" request read 1.0/s in a 10 s window.)
    const values = await page.evaluate(() => {
      const w = window as any;
      const t0 = 1699999980000;
      const timestamps: number[] = [];
      const throughputs: number[] = [];
      for (let s = 0; s < 66; s++) { timestamps.push(t0 + s * 1000); throughputs.push(10); }
      return w.aggregateTimeSeries(timestamps, throughputs, 60000,
        w.aggOptsForMetric('throughput')).map((p: any[]) => p[1]);
    });

    expect(values.length).toBe(2);
    expect(values[0]).toBeCloseTo(10, 6);
    expect(values[1]).toBeCloseTo(1, 6);
  });

  test('[R] max takes the maximum, never an average of maxima', async ({ page }) => {
    await loadClean(page, 'E02');
    const value = await page.evaluate(() => {
      const w = window as any;
      const t0 = 1700000000000;
      return w.aggregateTimeSeries(seconds3(t0), [100, 4000, 150], 60000,
        w.aggOptsForMetric('max'))[0][1];
      function seconds3(t: number) { return [t, t + 1000, t + 2000]; }
    });
    expect(value).toBe(4000);
  });

  test('[R] percentiles stay count-weighted', async ({ page }) => {
    await loadClean(page, 'E02');
    const value = await page.evaluate(() => {
      const w = window as any;
      const t0 = 1700000000000;
      // 990 samples at 100 ms, 10 samples at 4000 ms.
      return w.aggregateTimeSeries([t0, t0 + 1000], [100, 4000], 60000,
        w.aggOptsForMetric('p95', [990, 10]))[0][1];
    });
    // (100*990 + 4000*10) / 1000 = 139
    expect(value).toBeCloseTo(139, 6);
  });

  test('[R] raw 1s granularity is untouched by aggregation', async ({ page }) => {
    await loadClean(page, 'E02');
    const out = await page.evaluate(() => {
      const w = window as any;
      const t0 = 1700000000000;
      w.chartGranularity = 1000;
      return w.zipTimeSeries([t0, t0 + 1000], [5, null], w.aggOptsForMetric('throughput'));
    });
    expect(out).toEqual([[1700000000000, 5], [1700000001000, null]]);
  });
});

test.describe('buildPerSamplerSeries — Σ Total line', () => {
  /** Install a synthetic two-sampler dataset, with the Σ Total Java pooled from its second, and read back the Σ line. */
  async function totalSeriesFor(page: any, metric: string) {
    return page.evaluate((m: string) => {
      const w = window as any;
      const t0 = 1700000000000;
      const data = {
        timestamps: [t0],
        throughputs: [1000],
        meanResponseTimes: [139],
        errorRates: [0],
        samplers: [{ name: 'busy' }, { name: 'rare' }],
        perSamplerSeries: {
          busy: { throughput: [990], p95: [100], max: [120], responseTime: [90] },
          rare: { throughput: [10], p95: [4000], max: [5000], responseTime: [3500] },
        },
        // The second's 1,000 samples pooled (T1): its P95 is a sample of the second, 100 ms
        totalSeries: { p95: [100], max: [5000] },
      };
      // Make every sampler and the Total visible.
      w.samplerVisibility = { busy: true, rare: true, [w.TOTAL_SAMPLER_NAME]: true };
      w.chartGranularity = 1000;
      const series = w.buildPerSamplerSeries(data, m);
      const total = series.find((s: any) => s.name === w.TOTAL_SAMPLER_NAME);
      return total ? total.data[0][1] : null;
    }, metric);
  }

  test('[C/R] p95 Total is the percentile of the second Java pooled, not a mean of the samplers\' P95s (T1)', async ({ page }) => {
    await loadClean(page, 'E02');
    const value = await totalSeriesFor(page, 'p95');
    // This test used to pin the count-weighted mean, (100*990 + 4000*10) / 1000 = 139: not a percentile of anything.
    expect(value).toBe(100);
  });

  test('[C/R] max Total is the second\'s maximum', async ({ page }) => {
    await loadClean(page, 'E02');
    const value = await totalSeriesFor(page, 'max');
    expect(value).toBe(5000); // not (120 + 5000)/2 = 2560
  });
});
