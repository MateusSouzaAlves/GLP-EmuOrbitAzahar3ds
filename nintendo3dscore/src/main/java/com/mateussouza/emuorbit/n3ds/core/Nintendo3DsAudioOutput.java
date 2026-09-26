// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/*
 * Owns one bounded, lifecycle-aware Android output for Nintendo 3DS PCM.
 *
 * Writes never block the core/render owner thread. Frame pacing is handled independently by the
 * lifecycle controller, while the bounded native queue and AudioTrack capacity absorb ordinary
 * scheduling jitter without turning audio backpressure into visible frame stalls.
 */
final class Nintendo3DsAudioOutput {
    static final int AUDIO_PREROLL_MILLIS = 160;
    static final int TRANSFER_CAPACITY_FRAMES = 2048;
    static final int OUTPUT_BUFFER_MILLIS = 500;

    private static final int BYTES_PER_STEREO_FRAME = 4;
    private static final float PCM_HEADROOM_GAIN = 0.90f;
    private static final int MIN_SAMPLE_RATE = 8000;
    private static final int MAX_SAMPLE_RATE = 192000;

    private final Object lock = new Object();
    private final Object transferLock = new Object();
    private final ByteBuffer transferBuffer = ByteBuffer.allocateDirect(
            TRANSFER_CAPACITY_FRAMES * BYTES_PER_STEREO_FRAME)
            .order(ByteOrder.nativeOrder());
    private final Nintendo3DsFastForwardAudioProcessor fastForwardProcessor =
            new Nintendo3DsFastForwardAudioProcessor();
    private final Nintendo3DsAudioProcessor audioProcessor =
            new Nintendo3DsAudioProcessor();

    // Counters and AudioTrack state are guarded by lock. transferBuffer is guarded by
    // transferLock so lifecycle transitions can safely discard only an exact pending block.
    private boolean enabled;
    private float volume = 1.0f;
    private float requestedSpeed = 1.0f;
    private float lastSynchronizedSpeed = 1.0f;
    private AudioTrack audioTrack;
    private long generation;
    private int activeSampleRate;
    private int lastSampleRate;
    private int lastBufferCapacityFrames;
    private int activeTrackUnderruns;
    private int bytesQueuedBeforePlay;
    private boolean primed;
    private long inputFrames;
    private long writtenFrames;
    private long decimatedFrames;
    private long droppedFrames;
    private long shortWrites;
    private long nonBlockingWriteCalls;
    private long completedUnderruns;
    private long openedTrackCount;
    private long releasedTrackCount;
    private long trackCreationFailures;
    private long outputFailures;

    Nintendo3DsAudioOutput() {
        transferBuffer.limit(0);
    }

    void setEnabled(boolean enabled) {
        AudioTrack detached = null;
        synchronized (lock) {
            if (this.enabled == enabled) {
                return;
            }
            this.enabled = enabled;
            if (!enabled) {
                detached = detachTrackLocked();
            }
        }
        releaseTrack(detached);
        if (!enabled) {
            synchronized (transferLock) {
                dropPendingFrames();
            }
            fastForwardProcessor.reset();
            audioProcessor.reset();
        }
    }

    void setVolume(float volume) {
        if (!Float.isFinite(volume) || volume < 0.0f || volume > 1.0f) {
            throw new IllegalArgumentException("O volume 3DS deve estar entre 0 e 1.");
        }
        AudioTrack invalidTrack = null;
        synchronized (lock) {
            this.volume = volume;
            if (audioTrack != null && setTrackVolume(audioTrack, volume) != AudioTrack.SUCCESS) {
                outputFailures++;
                invalidTrack = detachTrackLocked();
            }
        }
        releaseTrack(invalidTrack);
    }

    void setFastForwardSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 1.0f || speed > 4.0f) {
            throw new IllegalArgumentException("A velocidade 3DS deve estar entre 1x e 4x.");
        }
        AudioTrack detached = null;
        synchronized (lock) {
            if (Float.compare(requestedSpeed, speed) == 0) {
                return;
            }
            requestedSpeed = speed;
            detached = detachTrackLocked();
        }
        releaseTrack(detached);
        synchronized (transferLock) {
            dropPendingFrames();
        }
        fastForwardProcessor.reset();
        audioProcessor.reset();
    }

    void consume(Nintendo3DsCoreSession session, int sampleRate, long queuedInputFrames) {
        synchronized (transferLock) {
            consumeOnOwnerThread(session, sampleRate, queuedInputFrames);
        }
    }

    private void consumeOnOwnerThread(
            Nintendo3DsCoreSession session,
            int sampleRate,
            long queuedInputFrames) {
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) {
            return;
        }
        float speed;
        synchronized (lock) {
            speed = requestedSpeed;
        }
        float synchronizedSpeed = fastForwardProcessor.synchronizePlaybackSpeed(
                (int) Math.min(Math.max(queuedInputFrames, 0), Integer.MAX_VALUE),
                speed,
                sampleRate,
                System.nanoTime());
        synchronized (lock) {
            lastSynchronizedSpeed = synchronizedSpeed;
        }
        AudioTrack track = ensureTrack(sampleRate);
        track = recoverUnderrunIfNeeded(track, sampleRate);
        boolean outputAvailable = track != null;

        // Complete an earlier partial write before draining new PCM. If the platform accepts the
        // remainder, continue in this same frame instead of leaving valid native audio stranded.
        if (transferBuffer.hasRemaining()) {
            if (outputAvailable) {
                writePending(track);
            } else {
                dropPendingFrames();
            }
            if (transferBuffer.hasRemaining()
                    || (outputAvailable && !isCurrentTrack(track))) {
                return;
            }
        }
        if (!isEnabled()) {
            return;
        }

        // A short non-blocking write used to make the next video frame spend its only audio turn
        // on old PCM. Under a demanding game that could starve AudioTrack even while the native
        // ring still contained sound. Drain a small bounded burst so audio catches up without
        // turning the render thread into an unbounded retry loop.
        long drainStartedNanos = System.nanoTime();
        for (int block = 0; isEnabled(); block++) {
            if (!Nintendo3DsAudioDrainPolicy.shouldDrainBlock(
                    block, System.nanoTime() - drainStartedNanos)) {
                break;
            }
            transferBuffer.clear();
            int frames = session.drainAudio(transferBuffer, TRANSFER_CAPACITY_FRAMES);
            if (frames <= 0) {
                transferBuffer.limit(0);
                return;
            }
            if (!outputAvailable) {
                // Keep the bounded native ring fresh without filtering/decimating PCM that
                // cannot be played. Muted or temporarily unavailable output must not add work
                // to an already expensive emulation frame.
                recordReceivedFrames(frames, 0);
                transferBuffer.position(0);
                transferBuffer.limit(frames * BYTES_PER_STEREO_FRAME);
                dropPendingFrames();
                continue;
            }
            audioProcessor.process(
                    transferBuffer,
                    frames,
                    synchronizedSpeed,
                    PCM_HEADROOM_GAIN);
            int outputFrames = fastForwardProcessor.process(
                    transferBuffer,
                    frames,
                    synchronizedSpeed);
            int intentionallyDecimatedFrames = frames - outputFrames;
            recordReceivedFrames(frames, intentionallyDecimatedFrames);
            transferBuffer.position(0);
            transferBuffer.limit(outputFrames * BYTES_PER_STEREO_FRAME);
            if (transferBuffer.hasRemaining()) {
                writePending(track);
                if (transferBuffer.hasRemaining() || !isCurrentTrack(track)) {
                    return;
                }
            }
        }
    }

    void releaseForTransition() {
        AudioTrack detached;
        synchronized (lock) {
            detached = detachTrackLocked();
        }
        releaseTrack(detached);
        synchronized (transferLock) {
            dropPendingFrames();
        }
        fastForwardProcessor.reset();
        audioProcessor.reset();
    }

    Nintendo3DsAudioOutputReport report() {
        synchronized (transferLock) {
            synchronized (lock) {
            AudioTrack track = audioTrack;
            boolean active = track != null;
            boolean playing = false;
            long underruns = completedUnderruns;
            if (track != null) {
                underruns += readUnderruns(track);
                try {
                    playing = track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
                } catch (IllegalStateException ignored) {
                    playing = false;
                }
            }
            return new Nintendo3DsAudioOutputReport(
                    lastSampleRate,
                    lastBufferCapacityFrames,
                    requestedSpeed,
                    lastSynchronizedSpeed,
                    inputFrames,
                    writtenFrames,
                    decimatedFrames,
                    droppedFrames,
                    transferBuffer.remaining() / BYTES_PER_STEREO_FRAME,
                    shortWrites,
                    nonBlockingWriteCalls,
                    underruns,
                    openedTrackCount,
                    releasedTrackCount,
                    trackCreationFailures,
                    outputFailures,
                    active,
                    primed,
                    playing);
            }
        }
    }

    private AudioTrack ensureTrack(int sampleRate) {
        AudioTrack staleTrack;
        long expectedGeneration;
        synchronized (lock) {
            if (!enabled) {
                return null;
            }
            if (audioTrack != null && activeSampleRate == sampleRate) {
                return audioTrack;
            }
            staleTrack = detachTrackLocked();
            expectedGeneration = generation;
        }
        releaseTrack(staleTrack);

        AudioTrack createdTrack = createAudioTrack(sampleRate);
        AudioTrack orphanedTrack = null;
        AudioTrack result = null;
        synchronized (lock) {
            if (createdTrack == null) {
                trackCreationFailures++;
            } else if (!enabled || generation != expectedGeneration || audioTrack != null) {
                orphanedTrack = createdTrack;
            } else if (setTrackVolume(createdTrack, volume) != AudioTrack.SUCCESS) {
                trackCreationFailures++;
                outputFailures++;
                orphanedTrack = createdTrack;
            } else {
                audioTrack = createdTrack;
                activeSampleRate = sampleRate;
                lastSampleRate = sampleRate;
                lastBufferCapacityFrames = readBufferCapacityFrames(createdTrack);
                activeTrackUnderruns = readUnderruns(createdTrack);
                openedTrackCount++;
                result = createdTrack;
            }
        }
        releaseTrack(orphanedTrack);
        return result;
    }

    private AudioTrack createAudioTrack(int sampleRate) {
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferSize <= 0) {
                return null;
            }
            int bufferSize = Math.max(
                    minBufferSize * 2,
                    sampleRate * BYTES_PER_STEREO_FRAME
                            * OUTPUT_BUFFER_MILLIS / 1000);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            if (track.getState() == AudioTrack.STATE_INITIALIZED) {
                return track;
            }
            track.release();
        } catch (IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException ignored) {
            // The core remains usable and the bounded native queue prevents memory growth.
        }
        return null;
    }

    private void recordBytesQueuedAndStart(AudioTrack track, int writtenBytes) {
        boolean shouldStart = false;
        synchronized (lock) {
            if (track == audioTrack && !primed) {
                bytesQueuedBeforePlay += writtenBytes;
                int prerollBytes = Math.max(
                        BYTES_PER_STEREO_FRAME,
                        activeSampleRate * BYTES_PER_STEREO_FRAME
                                * AUDIO_PREROLL_MILLIS / 1000);
                if (bytesQueuedBeforePlay >= prerollBytes) {
                    primed = true;
                    shouldStart = true;
                }
            }
        }
        if (shouldStart) {
            try {
                track.play();
            } catch (IllegalStateException exception) {
                synchronized (lock) {
                    outputFailures++;
                }
                invalidateTrack(track);
            }
        }
    }

    private void writePending(AudioTrack track) {
        int requestedBytes = transferBuffer.remaining();
        int result;
        try {
            result = track.write(
                    transferBuffer,
                    requestedBytes,
                    AudioTrack.WRITE_NON_BLOCKING);
        } catch (IllegalStateException exception) {
            result = AudioTrack.ERROR_INVALID_OPERATION;
        }
        synchronized (lock) {
            nonBlockingWriteCalls++;
        }
        if (result < 0) {
            if (isCurrentTrack(track)) {
                synchronized (lock) {
                    outputFailures++;
                }
                dropPendingFrames();
                invalidateTrack(track);
            } else {
                dropPendingFrames();
            }
            return;
        }
        if (result == 0) {
            if (!isCurrentTrack(track)) {
                dropPendingFrames();
            }
            return;
        }
        if (result < requestedBytes) {
            synchronized (lock) {
                shortWrites++;
            }
        }
        if (result % BYTES_PER_STEREO_FRAME != 0) {
            synchronized (lock) {
                outputFailures++;
            }
            dropPendingFrames();
            invalidateTrack(track);
            return;
        }
        synchronized (lock) {
            writtenFrames += result / BYTES_PER_STEREO_FRAME;
        }
        recordBytesQueuedAndStart(track, result);
        if (!isCurrentTrack(track)) {
            dropPendingFrames();
        }
    }

    private AudioTrack recoverUnderrunIfNeeded(AudioTrack track, int sampleRate) {
        if (track == null) {
            return null;
        }
        int observedUnderruns = readUnderruns(track);
        boolean recover;
        synchronized (lock) {
            recover = track == audioTrack
                    && primed
                    && observedUnderruns > activeTrackUnderruns;
            if (track == audioTrack && !recover) {
                activeTrackUnderruns = Math.max(activeTrackUnderruns, observedUnderruns);
            }
        }
        if (!recover) {
            return track;
        }

        // A long shader/driver frame can exhaust even a generously sized streaming buffer.
        // Recreate and pre-roll instead of letting AudioTrack auto-restart a disabled stream on
        // the next write, which is the path that produces repeated pops and follow-on underruns.
        invalidateTrack(track);
        return ensureTrack(sampleRate);
    }

    private void recordReceivedFrames(int receivedInputFrames, int intentionallyDecimatedFrames) {
        synchronized (lock) {
            inputFrames += receivedInputFrames;
            decimatedFrames += intentionallyDecimatedFrames;
        }
    }

    /** Must be called while holding transferLock. */
    private void dropPendingFrames() {
        int pendingFrames = transferBuffer.remaining() / BYTES_PER_STEREO_FRAME;
        if (pendingFrames > 0) {
            synchronized (lock) {
                droppedFrames += pendingFrames;
            }
        }
        transferBuffer.position(0);
        transferBuffer.limit(0);
    }

    private boolean isEnabled() {
        synchronized (lock) {
            return enabled;
        }
    }

    private boolean isCurrentTrack(AudioTrack track) {
        synchronized (lock) {
            return enabled && track != null && track == audioTrack;
        }
    }

    private void invalidateTrack(AudioTrack track) {
        AudioTrack detached = null;
        synchronized (lock) {
            if (track == audioTrack) {
                detached = detachTrackLocked();
            }
        }
        releaseTrack(detached);
    }

    private AudioTrack detachTrackLocked() {
        generation++;
        AudioTrack detached = audioTrack;
        if (detached != null) {
            completedUnderruns += readUnderruns(detached);
            releasedTrackCount++;
        }
        audioTrack = null;
        activeSampleRate = 0;
        activeTrackUnderruns = 0;
        bytesQueuedBeforePlay = 0;
        primed = false;
        return detached;
    }

    private static int setTrackVolume(AudioTrack track, float volume) {
        try {
            return track.setVolume(volume);
        } catch (IllegalStateException exception) {
            return AudioTrack.ERROR_INVALID_OPERATION;
        }
    }

    private static int readBufferCapacityFrames(AudioTrack track) {
        try {
            return Math.max(0, track.getBufferSizeInFrames());
        } catch (IllegalStateException exception) {
            return 0;
        }
    }

    private static int readUnderruns(AudioTrack track) {
        try {
            return Math.max(0, track.getUnderrunCount());
        } catch (IllegalStateException exception) {
            return 0;
        }
    }

    private static void releaseTrack(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause();
            }
            track.flush();
        } catch (IllegalStateException ignored) {
            // The platform may already have invalidated a concurrently detached track.
        }
        try {
            track.release();
        } catch (IllegalStateException ignored) {
            // Release stays idempotent from the controller's perspective.
        }
    }
}
