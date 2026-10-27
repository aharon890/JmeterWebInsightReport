package com.jmeterwebinsightreport.core.servermetrics.collect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How much of a window a series actually covers — the figure every KPI row shows and the
 * floor every KPI is judged against.
 *
 * <p>Expected points: one per step from {@code start} to {@code end} inclusive. Actual points:
 * the distinct slots that carry at least one point, across every instance — an instance that
 * vanished halfway through still counts for the half it was there, and two instances in the
 * same slot count once. A point belongs to the slot whose centre is nearest, so a point within
 * half a step of {@code start} or {@code end} covers the edge slot: the source's grid and the
 * JTL's millisecond edges never align, and under a floor rule the last slot of every run was
 * unreachable and no series could ever reach 100%. Points further out are ignored, not
 * penalised — a source that returns a little extra around the edges is normal.
 *
 * <p>By slot rather than by raw count, so a source that answers at a finer step than declared
 * cannot report 200% and a coarser one cannot hide a gap behind duplicates.
 */
public final class Coverage {

    private Coverage() {
    }

    /** Fraction 0..1, or NaN when the window cannot hold a single step. */
    public static double of(List<ServerMetricsPayload.InstanceRecord> instances,
                            ServerMetricsPayload.Window window, long stepMillis) {
        if (stepMillis <= 0 || window == null || window.durationMillis() < 0) {
            return Double.NaN;
        }
        long expected = window.durationMillis() / stepMillis + 1;
        Set<Long> slots = new HashSet<>();
        for (ServerMetricsPayload.InstanceRecord inst : instances) {
            for (ServerMetricsPayload.Point p : inst.getPoints()) {
                long slot = Math.round((p.getTs() - window.getStart()) / (double) stepMillis);
                if (slot < 0 || slot >= expected) {
                    continue;
                }
                slots.add(slot);
            }
        }
        double covered = Math.min(slots.size(), expected);
        return covered / expected;
    }

    /**
     * The longest stretch of the window in which no instance has a point, by {@link #of}'s slots:
     * from the last covered slot before it (or the window's start) to the first covered slot after
     * it (or the window's end), as two epoch-millisecond times; null when no two slots in a row are
     * empty. Two, as Grafana's alerting waits two evaluation intervals before it calls a series
     * NoData: one missed scrape is noise. What a LOW_COVERAGE reason names (B1, "name the gap"): a
     * target that went away with its exporter writes no 0, only a hole, and Grafana's state history
     * and Alertmanager give such a stretch as its start and end.
     */
    public static long[] longestGap(List<ServerMetricsPayload.InstanceRecord> instances,
                                    ServerMetricsPayload.Window window, long stepMillis) {
        if (stepMillis <= 0 || window == null || window.durationMillis() < 0) {
            return null;
        }
        long expected = window.durationMillis() / stepMillis + 1;
        // The covered slots, sorted: memory by points, never by window/step (a month at 1 ms is 2.6e9
        // slots - the Stage 5 review).
        java.util.TreeSet<Long> covered = new java.util.TreeSet<>();
        for (ServerMetricsPayload.InstanceRecord inst : instances) {
            for (ServerMetricsPayload.Point p : inst.getPoints()) {
                long slot = Math.round((p.getTs() - window.getStart()) / (double) stepMillis);
                if (slot >= 0 && slot < expected) {
                    covered.add(slot);
                }
            }
        }
        long bestStart = -1;
        long bestLength = 0;
        long previous = -1;   // a covered slot before the window, so a gap at its start counts from slot 0
        for (long slot : covered) {
            if (slot - previous - 1 > bestLength) {
                bestLength = slot - previous - 1;
                bestStart = previous + 1;
            }
            previous = slot;
        }
        if (expected - previous - 1 > bestLength) {
            bestLength = expected - previous - 1;
            bestStart = previous + 1;
        }
        if (bestLength < 2) {
            return null;
        }
        long from = bestStart == 0 ? window.getStart() : window.getStart() + (bestStart - 1) * stepMillis;
        long to = bestStart + bestLength >= expected ? window.getEnd()
                : window.getStart() + (bestStart + bestLength) * stepMillis;
        return new long[] {from, to};
    }

    /**
     * How far apart a series' points are, in milliseconds: each instance's
     * {@linkplain #mostCommonSpacing most common spacing} between consecutive points, and the
     * smallest of those over the instances; 0 when no instance has two points. The smallest (5.9,
     * N6): coverage counts the union of a series' slots, so one sparse instance beside dense ones
     * leaves none empty, and Grafana's interval follows the finest series rather than the sparsest. What
     * {@code wir-collect} compares with {@code --step}: an export at a panel's 60 s interval, counted
     * in 15 s slots, covers a quarter of them at most and reads LOW_COVERAGE for a reason nothing
     * else says.
     */
    public static long spacingMillis(List<ServerMetricsPayload.InstanceRecord> instances) {
        long finest = 0;
        for (ServerMetricsPayload.InstanceRecord inst : instances) {
            List<Long> times = new ArrayList<>();
            for (ServerMetricsPayload.Point p : inst.getPoints()) {
                times.add(p.getTs());
            }
            long spacing = mostCommonSpacing(times);
            if (spacing > 0 && (finest == 0 || spacing < finest)) {
                finest = spacing;
            }
        }
        return finest;
    }

    /**
     * The most common gap between consecutive timestamps, which ascend: the smaller on a tie, and 0
     * when there is no gap above 0. The one rule for an export's period ({@code Conversion.period})
     * and a series' spacing ({@link #spacingMillis}), so the two cannot disagree.
     */
    static long mostCommonSpacing(Iterable<Long> ascending) {
        return spacingCounts(ascending)[0];
    }

    /**
     * {@link #mostCommonSpacing}'s answer with what it was decided from: {@code {spacing, how many
     * gaps were that spacing, how many gaps there were}}. {@code CsvSource.intervalOf} floors a
     * measured step on the last two, so it counts with this rule rather than a copy of it.
     */
    static long[] spacingCounts(Iterable<Long> ascending) {
        TreeMap<Long, Integer> spacings = new TreeMap<>();
        Long previous = null;
        int gaps = 0;
        for (Long t : ascending) {
            if (previous != null && t > previous) {
                spacings.merge(t - previous, 1, Integer::sum);
                gaps++;
            }
            previous = t;
        }
        long common = 0;
        int most = 0;
        // Ascending, and only a strictly larger count replaces: a tie keeps the smaller spacing.
        for (Map.Entry<Long, Integer> e : spacings.entrySet()) {
            if (e.getValue() > most) {
                most = e.getValue();
                common = e.getKey();
            }
        }
        return new long[] {common, most, gaps};
    }
}
