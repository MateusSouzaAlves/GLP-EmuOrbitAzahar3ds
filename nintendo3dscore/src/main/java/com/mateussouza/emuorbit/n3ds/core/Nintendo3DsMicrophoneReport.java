// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Immutable diagnostics for the optional, lifecycle-bound Android microphone source. */
public final class Nintendo3DsMicrophoneReport {
    private final boolean active;
    private final boolean permissionGranted;
    private final boolean microphoneAvailable;
    private final long capturedSamples;
    private final int queuedSamples;
    private final long droppedSamples;
    private final long startCount;
    private final long stopCount;
    private final long captureFailures;

    Nintendo3DsMicrophoneReport(
            boolean active,
            boolean permissionGranted,
            boolean microphoneAvailable,
            long capturedSamples,
            int queuedSamples,
            long droppedSamples,
            long startCount,
            long stopCount,
            long captureFailures) {
        this.active = active;
        this.permissionGranted = permissionGranted;
        this.microphoneAvailable = microphoneAvailable;
        this.capturedSamples = capturedSamples;
        this.queuedSamples = queuedSamples;
        this.droppedSamples = droppedSamples;
        this.startCount = startCount;
        this.stopCount = stopCount;
        this.captureFailures = captureFailures;
    }

    public boolean isActive() { return active; }
    public boolean isPermissionGranted() { return permissionGranted; }
    public boolean isMicrophoneAvailable() { return microphoneAvailable; }
    public long getCapturedSamples() { return capturedSamples; }
    public int getQueuedSamples() { return queuedSamples; }
    public long getDroppedSamples() { return droppedSamples; }
    public long getStartCount() { return startCount; }
    public long getStopCount() { return stopCount; }
    public long getCaptureFailures() { return captureFailures; }
}
