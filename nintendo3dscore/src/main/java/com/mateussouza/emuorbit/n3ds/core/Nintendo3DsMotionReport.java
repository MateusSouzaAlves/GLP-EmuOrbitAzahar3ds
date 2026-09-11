// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Immutable diagnostics for the Android motion source owned by a 3DS controller. */
public final class Nintendo3DsMotionReport {
    private final boolean active;
    private final boolean accelerometerAvailable;
    private final boolean gyroscopeAvailable;
    private final long accelerometerEvents;
    private final long gyroscopeEvents;
    private final long startCount;
    private final long stopCount;

    Nintendo3DsMotionReport(
            boolean active,
            boolean accelerometerAvailable,
            boolean gyroscopeAvailable,
            long accelerometerEvents,
            long gyroscopeEvents,
            long startCount,
            long stopCount) {
        this.active = active;
        this.accelerometerAvailable = accelerometerAvailable;
        this.gyroscopeAvailable = gyroscopeAvailable;
        this.accelerometerEvents = accelerometerEvents;
        this.gyroscopeEvents = gyroscopeEvents;
        this.startCount = startCount;
        this.stopCount = stopCount;
    }

    public boolean isActive() { return active; }
    public boolean isAccelerometerAvailable() { return accelerometerAvailable; }
    public boolean isGyroscopeAvailable() { return gyroscopeAvailable; }
    public long getAccelerometerEvents() { return accelerometerEvents; }
    public long getGyroscopeEvents() { return gyroscopeEvents; }
    public long getStartCount() { return startCount; }
    public long getStopCount() { return stopCount; }
}
