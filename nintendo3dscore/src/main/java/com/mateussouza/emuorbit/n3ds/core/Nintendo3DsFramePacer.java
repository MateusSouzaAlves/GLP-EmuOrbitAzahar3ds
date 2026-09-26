// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.concurrent.locks.LockSupport;

/** Deadline-based video pacing that never delegates timing to a blocking audio write. */
final class Nintendo3DsFramePacer {
    static final double NOMINAL_FRAMES_PER_SECOND = 60.0;
    static final float NOMINAL_FRAMES_PER_SECOND_FLOAT = 60.0f;

    interface NanoClock {
        long nanoTime();
    }

    interface NanoSleeper {
        void sleep(long durationNanos);
    }

    private final NanoClock clock;
    private final NanoSleeper sleeper;
    private long nextFrameDeadlineNanos;
    private float pacedSpeed;
    private double pacedFramesPerSecond;

    static Nintendo3DsFramePacer createDefault() {
        return new Nintendo3DsFramePacer(System::nanoTime, LockSupport::parkNanos);
    }

    Nintendo3DsFramePacer(NanoClock clock, NanoSleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    void pace(float speed, long frameStartedNanos) throws InterruptedException {
        pace(speed, NOMINAL_FRAMES_PER_SECOND, frameStartedNanos);
    }

    void pace(float speed, double nominalFramesPerSecond, long frameStartedNanos)
            throws InterruptedException {
        if (!Float.isFinite(speed) || speed < 1.0f || speed > 4.0f) {
            throw new IllegalArgumentException("A velocidade de pacing 3DS deve estar entre 1x e 4x.");
        }
        long frameDurationNanos = frameDurationNanos(speed, nominalFramesPerSecond);
        long now = clock.nanoTime();
        if (nextFrameDeadlineNanos == 0
                || Float.compare(pacedSpeed, speed) != 0
                || Double.compare(pacedFramesPerSecond, nominalFramesPerSecond) != 0
                || now - nextFrameDeadlineNanos > frameDurationNanos * 4) {
            // Anchor the first deadline to the beginning of the frame whose work just
            // completed. Anchoring to "now" would add a complete frame interval after
            // rendering and make a 60 fps producer repeatedly miss a 120 Hz vsync.
            nextFrameDeadlineNanos = Math.min(frameStartedNanos, now);
            pacedSpeed = speed;
            pacedFramesPerSecond = nominalFramesPerSecond;
        }
        nextFrameDeadlineNanos += frameDurationNanos;
        while (true) {
            long remainingNanos = nextFrameDeadlineNanos - clock.nanoTime();
            if (remainingNanos <= 0) {
                // Do not burst several catch-up frames after a costly shader/gameplay frame.
                // Re-anchoring reduces CPU/audio pressure and produces steadier visual cadence.
                nextFrameDeadlineNanos = clock.nanoTime();
                return;
            }
            sleeper.sleep(remainingNanos);
            if (Thread.interrupted()) {
                throw new InterruptedException("Pacing 3DS interrompido.");
            }
        }
    }

    void reset() {
        nextFrameDeadlineNanos = 0;
        pacedSpeed = 0.0f;
        pacedFramesPerSecond = 0.0;
    }

    static long frameDurationNanos(float speed) {
        return frameDurationNanos(speed, NOMINAL_FRAMES_PER_SECOND);
    }

    static long frameDurationNanos(float speed, double nominalFramesPerSecond) {
        if (!Float.isFinite(speed) || speed <= 0.0f) {
            throw new IllegalArgumentException("A velocidade de pacing 3DS deve ser positiva.");
        }
        if (!Double.isFinite(nominalFramesPerSecond)
                || nominalFramesPerSecond < 10.0
                || nominalFramesPerSecond > 240.0) {
            throw new IllegalArgumentException("A taxa nominal 3DS deve estar entre 10 e 240 fps.");
        }
        return Math.max(
                1L,
                (long) (1_000_000_000.0 / (nominalFramesPerSecond * speed)));
    }
}
