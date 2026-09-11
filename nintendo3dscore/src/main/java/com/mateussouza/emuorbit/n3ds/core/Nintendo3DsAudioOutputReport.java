// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Immutable diagnostics for the isolated Nintendo 3DS Android audio output. */
public final class Nintendo3DsAudioOutputReport {
    private final int sampleRate;
    private final int bufferCapacityFrames;
    private final float requestedSpeed;
    private final float synchronizedSpeed;
    private final long inputFrames;
    private final long writtenFrames;
    private final long decimatedFrames;
    private final long droppedFrames;
    private final long shortWrites;
    private final long nonBlockingWriteCalls;
    private final long underruns;
    private final long openedTrackCount;
    private final long releasedTrackCount;
    private final long trackCreationFailures;
    private final long outputFailures;
    private final boolean active;
    private final boolean primed;
    private final boolean playing;

    Nintendo3DsAudioOutputReport(
            int sampleRate,
            int bufferCapacityFrames,
            float requestedSpeed,
            float synchronizedSpeed,
            long inputFrames,
            long writtenFrames,
            long decimatedFrames,
            long droppedFrames,
            long shortWrites,
            long nonBlockingWriteCalls,
            long underruns,
            long openedTrackCount,
            long releasedTrackCount,
            long trackCreationFailures,
            long outputFailures,
            boolean active,
            boolean primed,
            boolean playing) {
        this.sampleRate = sampleRate;
        this.bufferCapacityFrames = bufferCapacityFrames;
        this.requestedSpeed = requestedSpeed;
        this.synchronizedSpeed = synchronizedSpeed;
        this.inputFrames = inputFrames;
        this.writtenFrames = writtenFrames;
        this.decimatedFrames = decimatedFrames;
        this.droppedFrames = droppedFrames;
        this.shortWrites = shortWrites;
        this.nonBlockingWriteCalls = nonBlockingWriteCalls;
        this.underruns = underruns;
        this.openedTrackCount = openedTrackCount;
        this.releasedTrackCount = releasedTrackCount;
        this.trackCreationFailures = trackCreationFailures;
        this.outputFailures = outputFailures;
        this.active = active;
        this.primed = primed;
        this.playing = playing;
    }

    public int getSampleRate() { return sampleRate; }

    public int getBufferCapacityFrames() { return bufferCapacityFrames; }

    public float getRequestedSpeed() { return requestedSpeed; }

    public float getSynchronizedSpeed() { return synchronizedSpeed; }

    public long getInputFrames() { return inputFrames; }

    public long getWrittenFrames() { return writtenFrames; }

    public long getDecimatedFrames() { return decimatedFrames; }

    public long getDroppedFrames() { return droppedFrames; }

    public long getShortWrites() { return shortWrites; }

    public long getNonBlockingWriteCalls() { return nonBlockingWriteCalls; }

    public long getUnderruns() { return underruns; }

    public long getOpenedTrackCount() { return openedTrackCount; }

    public long getReleasedTrackCount() { return releasedTrackCount; }

    public long getTrackCreationFailures() { return trackCreationFailures; }

    public long getOutputFailures() { return outputFailures; }

    public boolean isActive() { return active; }

    public boolean isPrimed() { return primed; }

    public boolean isPlaying() { return playing; }
}
