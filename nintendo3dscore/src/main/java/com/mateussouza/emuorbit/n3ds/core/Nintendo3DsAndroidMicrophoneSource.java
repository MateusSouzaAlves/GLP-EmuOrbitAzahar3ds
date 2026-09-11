// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.util.Objects;

/*
 * AudioRecord ownership follows EmuOrbit's proven Nintendo DS capture lifecycle, adjusted to
 * Azahar's 48 kHz LibRetro microphone contract. Samples cross threads through a fixed one-second
 * ring, so a stalled shader compilation cannot grow memory without bound.
 */
final class Nintendo3DsAndroidMicrophoneSource implements AutoCloseable {
    static final int SAMPLE_RATE = 48_000;
    static final int QUEUE_CAPACITY_SAMPLES = SAMPLE_RATE;
    private static final int READ_SAMPLES = 2_048;
    private static final long THREAD_JOIN_TIMEOUT_MILLIS = 1_000L;

    private final Object lock = new Object();
    private final Context context;
    private final boolean microphoneAvailable;
    private final short[] sampleRing = new short[QUEUE_CAPACITY_SAMPLES];

    // Guarded by lock.
    private AudioRecord audioRecord;
    private Thread captureThread;
    private boolean running;
    private int readIndex;
    private int queuedSamples;
    private long capturedSamples;
    private long droppedSamples;
    private long startCount;
    private long stopCount;
    private long captureFailures;

    Nintendo3DsAndroidMicrophoneSource(Context context) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        microphoneAvailable = this.context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_MICROPHONE);
    }

    Nintendo3DsMicrophoneStartResult start() {
        synchronized (lock) {
            if (running) {
                return Nintendo3DsMicrophoneStartResult.ACTIVE;
            }
            if (!hasPermission()) {
                return Nintendo3DsMicrophoneStartResult.PERMISSION_REQUIRED;
            }
            if (!microphoneAvailable) {
                return Nintendo3DsMicrophoneStartResult.UNAVAILABLE;
            }
        }
        stop();
        return createAndStartRecorder();
    }

    @SuppressLint("MissingPermission")
    private Nintendo3DsMicrophoneStartResult createAndStartRecorder() {
        int minimumBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBytes <= 0) {
            return Nintendo3DsMicrophoneStartResult.UNAVAILABLE;
        }
        int bufferBytes = Math.max(minimumBytes, READ_SAMPLES * Short.BYTES * 2);
        AudioRecord created = null;
        try {
            created = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
            if (created.getState() != AudioRecord.STATE_INITIALIZED) {
                created.release();
                return Nintendo3DsMicrophoneStartResult.UNAVAILABLE;
            }
            created.startRecording();
            if (created.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                stopAndRelease(created);
                return Nintendo3DsMicrophoneStartResult.UNAVAILABLE;
            }
        } catch (SecurityException error) {
            releaseQuietly(created);
            return Nintendo3DsMicrophoneStartResult.PERMISSION_REQUIRED;
        } catch (IllegalArgumentException | IllegalStateException error) {
            releaseQuietly(created);
            return Nintendo3DsMicrophoneStartResult.UNAVAILABLE;
        }

        synchronized (lock) {
            audioRecord = created;
            running = true;
            startCount++;
            AudioRecord activeRecord = created;
            captureThread = new Thread(
                    () -> captureLoop(activeRecord),
                    "EmuOrbit-N3DS-Microphone");
            captureThread.start();
        }
        return Nintendo3DsMicrophoneStartResult.ACTIVE;
    }

    void stop() {
        AudioRecord record;
        Thread thread;
        synchronized (lock) {
            boolean ownedRecorder = audioRecord != null;
            running = false;
            record = audioRecord;
            thread = captureThread;
            audioRecord = null;
            captureThread = null;
            readIndex = 0;
            queuedSamples = 0;
            if (ownedRecorder) {
                stopCount++;
            }
        }
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // An interrupted or failed recorder may already be stopped.
            }
        }
        join(thread);
        releaseQuietly(record);
    }

    int drain(short[] target) {
        Objects.requireNonNull(target);
        synchronized (lock) {
            int drained = Math.min(target.length, queuedSamples);
            int first = Math.min(drained, sampleRing.length - readIndex);
            System.arraycopy(sampleRing, readIndex, target, 0, first);
            int second = drained - first;
            if (second > 0) {
                System.arraycopy(sampleRing, 0, target, first, second);
            }
            readIndex = (readIndex + drained) % sampleRing.length;
            queuedSamples -= drained;
            return drained;
        }
    }

    Nintendo3DsMicrophoneReport report() {
        synchronized (lock) {
            return new Nintendo3DsMicrophoneReport(
                    running,
                    hasPermission(),
                    microphoneAvailable,
                    capturedSamples,
                    queuedSamples,
                    droppedSamples,
                    startCount,
                    stopCount,
                    captureFailures);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void captureLoop(AudioRecord record) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] samples = new short[READ_SAMPLES];
        while (isRunning(record)) {
            int count;
            try {
                count = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
            } catch (RuntimeException error) {
                recordFailureIfStillRunning(record);
                return;
            }
            if (count > 0) {
                enqueue(samples, count);
            } else if (count < 0) {
                recordFailureIfStillRunning(record);
                return;
            }
        }
    }

    private boolean isRunning(AudioRecord record) {
        synchronized (lock) {
            return running && audioRecord == record;
        }
    }

    private void recordFailureIfStillRunning(AudioRecord record) {
        synchronized (lock) {
            if (running && audioRecord == record) {
                running = false;
                captureFailures++;
            }
        }
    }

    private void enqueue(short[] samples, int count) {
        synchronized (lock) {
            if (!running || count <= 0) {
                return;
            }
            int accepted = Math.min(count, sampleRing.length);
            int sourceOffset = count - accepted;
            long dropped = sourceOffset;
            int overflow = Math.max(0, queuedSamples + accepted - sampleRing.length);
            if (overflow > 0) {
                readIndex = (readIndex + overflow) % sampleRing.length;
                queuedSamples -= overflow;
                dropped += overflow;
            }
            int writeIndex = (readIndex + queuedSamples) % sampleRing.length;
            int first = Math.min(accepted, sampleRing.length - writeIndex);
            System.arraycopy(samples, sourceOffset, sampleRing, writeIndex, first);
            int second = accepted - first;
            if (second > 0) {
                System.arraycopy(samples, sourceOffset + first, sampleRing, 0, second);
            }
            queuedSamples += accepted;
            capturedSamples += count;
            droppedSamples += dropped;
        }
    }

    private boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void stopAndRelease(AudioRecord record) {
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // The recorder may not have transitioned to recording.
        }
        record.release();
    }

    private static void releaseQuietly(AudioRecord record) {
        if (record != null) {
            try {
                record.release();
            } catch (RuntimeException ignored) {
                // Release is best effort after an AudioRecord construction failure.
            }
        }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(THREAD_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
