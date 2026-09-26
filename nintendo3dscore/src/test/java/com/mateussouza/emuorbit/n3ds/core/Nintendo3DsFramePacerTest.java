// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class Nintendo3DsFramePacerTest {
    @Test
    public void pacesNormalSpeedAgainstContinuousDeadlines() throws Exception {
        FakeTime time = new FakeTime();
        Nintendo3DsFramePacer pacer = new Nintendo3DsFramePacer(time, time);

        pacer.pace(1.0f, 0L);
        assertEquals(Nintendo3DsFramePacer.frameDurationNanos(1.0f), time.now);

        time.now += 10_000_000L;
        pacer.pace(1.0f, time.now - 10_000_000L);
        assertEquals(Nintendo3DsFramePacer.frameDurationNanos(1.0f) * 2, time.now);
    }

    @Test
    public void firstDeadlineIncludesWorkAlreadySpentRenderingTheFrame() throws Exception {
        FakeTime time = new FakeTime();
        Nintendo3DsFramePacer pacer = new Nintendo3DsFramePacer(time, time);
        time.now = 8_000_000L;

        pacer.pace(1.0f, 0L);

        assertEquals(Nintendo3DsFramePacer.frameDurationNanos(1.0f), time.now);
    }

    @Test
    public void speedChangeAndLongDelayStartFreshDeadline() throws Exception {
        FakeTime time = new FakeTime();
        Nintendo3DsFramePacer pacer = new Nintendo3DsFramePacer(time, time);
        pacer.pace(1.0f, 0L);

        long beforeFastForward = time.now;
        pacer.pace(2.0f, beforeFastForward);
        assertEquals(
                beforeFastForward + Nintendo3DsFramePacer.frameDurationNanos(2.0f),
                time.now);

        time.now += Nintendo3DsFramePacer.frameDurationNanos(2.0f) * 5;
        long beforeRecovery = time.now;
        pacer.pace(2.0f, beforeRecovery);
        assertEquals(
                beforeRecovery + Nintendo3DsFramePacer.frameDurationNanos(2.0f),
                time.now);
    }

    @Test
    public void followsCoreNominalRateInsteadOfAssumingSixtyFrames() throws Exception {
        FakeTime time = new FakeTime();
        Nintendo3DsFramePacer pacer = new Nintendo3DsFramePacer(time, time);

        pacer.pace(1.0f, 59.831225, 0L);

        assertEquals(
                Nintendo3DsFramePacer.frameDurationNanos(1.0f, 59.831225),
                time.now);
    }

    @Test
    public void missedFrameReanchorsWithoutBurstingCatchUpFrames() throws Exception {
        FakeTime time = new FakeTime();
        Nintendo3DsFramePacer pacer = new Nintendo3DsFramePacer(time, time);
        long duration = Nintendo3DsFramePacer.frameDurationNanos(1.0f);
        pacer.pace(1.0f, 0L);

        time.now += duration;
        pacer.pace(1.0f, time.now - duration);
        long afterMiss = time.now;
        pacer.pace(1.0f, afterMiss);

        assertEquals(afterMiss + duration, time.now);
    }

    @Test
    public void rejectsUnsupportedSpeed() {
        Nintendo3DsFramePacer pacer = new Nintendo3DsFramePacer(() -> 0L, ignored -> { });
        assertThrows(IllegalArgumentException.class, () -> pacer.pace(0.5f, 0L));
        assertThrows(IllegalArgumentException.class, () -> pacer.pace(5.0f, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> pacer.pace(1.0f, Double.NaN, 0L));
    }

    private static final class FakeTime
            implements Nintendo3DsFramePacer.NanoClock, Nintendo3DsFramePacer.NanoSleeper {
        private long now;

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void sleep(long durationNanos) {
            now += durationNanos;
        }
    }
}
