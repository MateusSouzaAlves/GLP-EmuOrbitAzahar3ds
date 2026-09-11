// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Aggregate-only performance evidence with no game identity, path or user data. */
public final class Nintendo3DsPerformanceSnapshot {
    private final Nintendo3DsPerformanceProfile profile;
    private final long measuredFrames;
    private final int percentileSampleFrames;
    private final double averageFrameMillis;
    private final double p50FrameMillis;
    private final double p95FrameMillis;
    private final double p99FrameMillis;
    private final double maximumFrameMillis;
    private final double effectiveSpeedPercent;
    private final long presentedFrames;
    private final long audioDroppedFrames;
    private final Nintendo3DsDevicePerformanceState deviceState;
    private final Nintendo3DsRegenerableCachePolicy.Report cacheReport;

    Nintendo3DsPerformanceSnapshot(
            Nintendo3DsPerformanceProfile profile,
            long measuredFrames,
            int percentileSampleFrames,
            double averageFrameMillis,
            double p50FrameMillis,
            double p95FrameMillis,
            double p99FrameMillis,
            double maximumFrameMillis,
            double effectiveSpeedPercent,
            long presentedFrames,
            long audioDroppedFrames,
            Nintendo3DsDevicePerformanceState deviceState,
            Nintendo3DsRegenerableCachePolicy.Report cacheReport) {
        this.profile = profile;
        this.measuredFrames = Math.max(0, measuredFrames);
        this.percentileSampleFrames = Math.max(0, percentileSampleFrames);
        this.averageFrameMillis = nonNegativeFinite(averageFrameMillis);
        this.p50FrameMillis = nonNegativeFinite(p50FrameMillis);
        this.p95FrameMillis = nonNegativeFinite(p95FrameMillis);
        this.p99FrameMillis = nonNegativeFinite(p99FrameMillis);
        this.maximumFrameMillis = nonNegativeFinite(maximumFrameMillis);
        this.effectiveSpeedPercent = nonNegativeFinite(effectiveSpeedPercent);
        this.presentedFrames = Math.max(0, presentedFrames);
        this.audioDroppedFrames = Math.max(0, audioDroppedFrames);
        this.deviceState = deviceState;
        this.cacheReport = cacheReport;
    }

    public Nintendo3DsPerformanceProfile getProfile() { return profile; }
    public long getMeasuredFrames() { return measuredFrames; }
    public int getPercentileSampleFrames() { return percentileSampleFrames; }
    public double getAverageFrameMillis() { return averageFrameMillis; }
    public double getP50FrameMillis() { return p50FrameMillis; }
    public double getP95FrameMillis() { return p95FrameMillis; }
    public double getP99FrameMillis() { return p99FrameMillis; }
    public double getMaximumFrameMillis() { return maximumFrameMillis; }
    public double getEffectiveSpeedPercent() { return effectiveSpeedPercent; }
    public long getPresentedFrames() { return presentedFrames; }
    public long getAudioDroppedFrames() { return audioDroppedFrames; }
    public Nintendo3DsDevicePerformanceState getDeviceState() { return deviceState; }
    public Nintendo3DsRegenerableCachePolicy.Report getCacheReport() { return cacheReport; }

    private static double nonNegativeFinite(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
