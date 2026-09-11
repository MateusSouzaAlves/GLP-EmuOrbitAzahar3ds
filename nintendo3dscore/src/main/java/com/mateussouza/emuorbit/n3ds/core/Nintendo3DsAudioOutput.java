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
 * Normal-speed blocking writes intentionally let the hardware output pace the core. Lifecycle
 * transitions may detach and release the AudioTrack concurrently, which wakes a blocked write
 * without moving any native core operation away from its owner thread.
 */
final class Nintendo3DsAudioOutput {
    static final int AUDIO_PREROLL_MILLIS = 60;
    static final int TRANSFER_CAPACITY_FRAMES = 2048;

    private static final int BYTES_PER_STEREO_FRAME = 4;
    private static final int MIN_SAMPLE_RATE = 8000;
    private static final int MAX_SAMPLE_RATE = 192000;

    private final Object lock = new Object();
    private final ByteBuffer transferBuffer = ByteBuffer.allocateDirect(
            TRANSFER_CAPACITY_FRAMES * BYTES_PER_STEREO_FRAME)
            .order(ByteOrder.nativeOrder());
    private final Nintendo3DsFastForwardAudioProcessor fastForwardProcessor =
            new Nintendo3DsFastForwardAudioProcessor();

    // Guarded by lock, except transferBuffer which is used only by the core owner thread.
    private boolean enabled;
    private float volume = 1.0f;
    private float requestedSpeed = 1.0f;
    private float lastSynchronizedSpeed = 1.0f;
    private AudioTrack audioTrack;
    private long generation;
    private int activeSampleRate;
    private int lastSampleRate;
    private int lastBufferCapacityFrames;
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
            fastForwardProcessor.reset();
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
        fastForwardProcessor.reset();
    }

    void consume(Nintendo3DsCoreSession session, int sampleRate, long queuedInputFrames) {
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
        boolean outputAvailable = track != null;
        int writeMode = speed > 1.0f
                ? AudioTrack.WRITE_NON_BLOCKING
                : AudioTrack.WRITE_BLOCKING;

        while (isEnabled() && (outputAvailable ? isCurrentTrack(track) : true)) {
            int frames = session.drainAudio(transferBuffer, TRANSFER_CAPACITY_FRAMES);
            if (frames <= 0) {
                return;
            }
            int outputFrames = fastForwardProcessor.process(
                    transferBuffer,
                    frames,
                    synchronizedSpeed);
            int intentionallyDecimatedFrames = frames - outputFrames;
            if (!outputAvailable) {
                recordTransfer(
                        frames,
                        0,
                        intentionallyDecimatedFrames,
                        outputFrames,
                        false);
                continue;
            }

            int expectedBytes = outputFrames * BYTES_PER_STEREO_FRAME;
            transferBuffer.position(0);
            transferBuffer.limit(expectedBytes);
            int writtenBytes = 0;
            boolean writeFailed = false;
            while (transferBuffer.hasRemaining() && isCurrentTrack(track)) {
                int requestedBytes = transferBuffer.remaining();
                int result;
                try {
                    result = track.write(
                            transferBuffer,
                            requestedBytes,
                            writeMode);
                } catch (IllegalStateException exception) {
                    result = AudioTrack.ERROR_INVALID_OPERATION;
                }
                if (writeMode == AudioTrack.WRITE_NON_BLOCKING) {
                    synchronized (lock) {
                        nonBlockingWriteCalls++;
                    }
                }
                if (result <= 0) {
                    // A lifecycle transition intentionally releases the track to wake this write.
                    // Count only failures from a track which is still the active destination.
                    writeFailed = result < 0 && isCurrentTrack(track);
                    break;
                }
                writtenBytes += result;
                recordBytesQueuedAndStart(track, result);
                if (result < requestedBytes) {
                    synchronized (lock) {
                        shortWrites++;
                    }
                }
            }

            int completeWrittenFrames = Math.min(
                    outputFrames,
                    writtenBytes / BYTES_PER_STEREO_FRAME);
            int remainingFrames = outputFrames - completeWrittenFrames;
            recordTransfer(
                    frames,
                    completeWrittenFrames,
                    intentionallyDecimatedFrames,
                    remainingFrames,
                    writeFailed);
            if (writeFailed) {
                invalidateTrack(track);
                return;
            }
            if (!isCurrentTrack(track)) {
                return;
            }
        }
    }

    void releaseForTransition() {
        AudioTrack detached;
        synchronized (lock) {
            detached = detachTrackLocked();
        }
        releaseTrack(detached);
        fastForwardProcessor.reset();
    }

    Nintendo3DsAudioOutputReport report() {
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
                    sampleRate * BYTES_PER_STEREO_FRAME);
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

    private void recordTransfer(
            int receivedInputFrames,
            int completeWrittenFrames,
            int intentionallyDecimatedFrames,
            int remainingFrames,
            boolean writeFailed) {
        synchronized (lock) {
            inputFrames += receivedInputFrames;
            writtenFrames += completeWrittenFrames;
            decimatedFrames += intentionallyDecimatedFrames;
            droppedFrames += remainingFrames;
            if (writeFailed) {
                outputFailures++;
            }
        }
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
