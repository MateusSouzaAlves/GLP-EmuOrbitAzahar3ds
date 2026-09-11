// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Arrays;
import java.util.Objects;

/** Fixed-memory frame sampler sized for a complete 30-minute 60 Hz performance run. */
final class Nintendo3DsPerformanceCollector {
    static final int PERCENTILE_CAPACITY_FRAMES = 120_000;
    private static final double NOMINAL_FRAMES_PER_SECOND = 60.0;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final long[] durationNanos = new long[PERCENTILE_CAPACITY_FRAMES];
    private long measuredFrames;
    private long totalDurationNanos;
    private long maximumDurationNanos;
    private int storedFrames;
    private int nextIndex;
    private long presentedFrames;
    private long audioDroppedFrames;

    synchronized void recordFrame(long elapsedNanos, Nintendo3DsCoreFrameReport report) {
        if (elapsedNanos <= 0) {
            throw new IllegalArgumentException("A duração do frame 3DS deve ser positiva.");
        }
        Nintendo3DsCoreFrameReport checked = Objects.requireNonNull(report);
        durationNanos[nextIndex] = elapsedNanos;
        nextIndex = (nextIndex + 1) % durationNanos.length;
        storedFrames = Math.min(durationNanos.length, storedFrames + 1);
        measuredFrames++;
        totalDurationNanos = Math.addExact(totalDurationNanos, elapsedNanos);
        maximumDurationNanos = Math.max(maximumDurationNanos, elapsedNanos);
        presentedFrames = checked.getPresentedFrames();
        audioDroppedFrames = checked.getAudioDroppedFrames();
    }

    synchronized void reset() {
        measuredFrames = 0;
        totalDurationNanos = 0;
        maximumDurationNanos = 0;
        storedFrames = 0;
        nextIndex = 0;
        presentedFrames = 0;
        audioDroppedFrames = 0;
    }

    synchronized Nintendo3DsPerformanceSnapshot snapshot(
            Nintendo3DsPerformanceProfile profile,
            Nintendo3DsDevicePerformanceState deviceState,
            Nintendo3DsRegenerableCachePolicy.Report cacheReport) {
        long[] sorted = Arrays.copyOf(durationNanos, storedFrames);
        Arrays.sort(sorted);
        double averageMillis = measuredFrames == 0
                ? 0
                : totalDurationNanos / (double) measuredFrames / NANOS_PER_MILLISECOND;
        double effectiveSpeed = averageMillis <= 0
                ? 0
                : (1000.0 / averageMillis) * 100.0 / NOMINAL_FRAMES_PER_SECOND;
        return new Nintendo3DsPerformanceSnapshot(
                Objects.requireNonNull(profile),
                measuredFrames,
                storedFrames,
                averageMillis,
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                maximumDurationNanos / NANOS_PER_MILLISECOND,
                effectiveSpeed,
                presentedFrames,
                audioDroppedFrames,
                Objects.requireNonNull(deviceState),
                Objects.requireNonNull(cacheReport));
    }

    private static double percentileMillis(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int nearestRank = Math.max(1, (int) Math.ceil(percentile * sorted.length));
        return sorted[nearestRank - 1] / NANOS_PER_MILLISECOND;
    }
}
