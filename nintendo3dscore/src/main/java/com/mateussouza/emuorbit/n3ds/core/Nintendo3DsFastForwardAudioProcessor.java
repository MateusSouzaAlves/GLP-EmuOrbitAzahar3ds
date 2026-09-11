// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.nio.ByteBuffer;

/* Keeps 3DS PCM duration aligned with the fast-forward speed actually reached. */
final class Nintendo3DsFastForwardAudioProcessor {
    private static final int BYTES_PER_STEREO_FRAME = 4;
    private static final long SPEED_MEASUREMENT_WINDOW_NANOS = 100_000_000L;

    private float previousSpeed = 1.0f;
    private double nextInputFrame;
    private float requestedSpeed = 1.0f;
    private float synchronizedSpeed = 1.0f;
    private long measurementStartedAtNanos;
    private long measurementFrames;

    synchronized float synchronizePlaybackSpeed(
            int inputFrames,
            float targetSpeed,
            int sampleRate,
            long nowNanos) {
        validateSpeed(targetSpeed);
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("A taxa de amostragem 3DS deve ser positiva.");
        }

        if (targetSpeed == 1.0f) {
            requestedSpeed = targetSpeed;
            synchronizedSpeed = 1.0f;
            measurementStartedAtNanos = nowNanos;
            measurementFrames = 0L;
            return synchronizedSpeed;
        }

        if (Float.compare(requestedSpeed, targetSpeed) != 0
                || measurementStartedAtNanos == 0L) {
            requestedSpeed = targetSpeed;
            // Decimate immediately so a full AudioTrack cannot throttle fast-forward at 1x.
            synchronizedSpeed = targetSpeed;
            measurementStartedAtNanos = nowNanos;
            measurementFrames = 0L;
            return synchronizedSpeed;
        }

        long elapsedNanos = nowNanos - measurementStartedAtNanos;
        if (elapsedNanos <= 0L) {
            return synchronizedSpeed;
        }
        measurementFrames += Math.max(inputFrames, 0);
        if (elapsedNanos < SPEED_MEASUREMENT_WINDOW_NANOS) {
            return synchronizedSpeed;
        }

        double achievedSpeed = measurementFrames * 1_000_000_000.0
                / (sampleRate * (double) elapsedNanos);
        synchronizedSpeed = (float) Math.max(
                1.0,
                Math.min(targetSpeed, achievedSpeed));
        measurementStartedAtNanos = nowNanos;
        measurementFrames = 0L;
        return synchronizedSpeed;
    }

    synchronized int process(ByteBuffer pcmBuffer, int inputFrames, float speed) {
        validateSpeed(speed);
        int availableFrames = Math.min(
                Math.max(inputFrames, 0),
                pcmBuffer.capacity() / BYTES_PER_STEREO_FRAME);
        if (speed == 1.0f) {
            previousSpeed = speed;
            nextInputFrame = 0.0;
            return availableFrames;
        }

        if (Float.compare(previousSpeed, speed) != 0) {
            previousSpeed = speed;
            nextInputFrame = 0.0;
        }

        int outputFrames = 0;
        double inputFrame = nextInputFrame;
        while (inputFrame < availableFrames) {
            int sourceOffset = (int) inputFrame * BYTES_PER_STEREO_FRAME;
            int targetOffset = outputFrames * BYTES_PER_STEREO_FRAME;
            pcmBuffer.putShort(targetOffset, pcmBuffer.getShort(sourceOffset));
            pcmBuffer.putShort(
                    targetOffset + Short.BYTES,
                    pcmBuffer.getShort(sourceOffset + Short.BYTES));
            outputFrames++;
            inputFrame += speed;
        }
        nextInputFrame = inputFrame - availableFrames;
        return outputFrames;
    }

    synchronized void reset() {
        previousSpeed = 1.0f;
        nextInputFrame = 0.0;
        requestedSpeed = 1.0f;
        synchronizedSpeed = 1.0f;
        measurementStartedAtNanos = 0L;
        measurementFrames = 0L;
    }

    private static void validateSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 1.0f || speed > 4.0f) {
            throw new IllegalArgumentException("A velocidade de áudio 3DS deve estar entre 1x e 4x.");
        }
    }
}
