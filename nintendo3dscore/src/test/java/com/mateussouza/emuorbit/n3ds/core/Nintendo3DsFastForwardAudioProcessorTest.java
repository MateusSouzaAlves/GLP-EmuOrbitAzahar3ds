// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Nintendo3DsFastForwardAudioProcessorTest {
    @Test
    public void normalSpeedPreservesEveryStereoFrame() {
        Nintendo3DsFastForwardAudioProcessor processor =
                new Nintendo3DsFastForwardAudioProcessor();
        ByteBuffer pcm = stereoFrames(10, 11, 20, 21, 30, 31);

        int outputFrames = processor.process(pcm, 3, 1.0f);

        assertEquals(3, outputFrames);
        assertStereoFrame(pcm, 0, 10, 11);
        assertStereoFrame(pcm, 1, 20, 21);
        assertStereoFrame(pcm, 2, 30, 31);
    }

    @Test
    public void twoTimesCompactsAudioAndPreservesStereoPairs() {
        Nintendo3DsFastForwardAudioProcessor processor =
                new Nintendo3DsFastForwardAudioProcessor();
        ByteBuffer pcm = stereoFrames(10, 11, 20, 21, 30, 31, 40, 41, 50, 51);

        int outputFrames = processor.process(pcm, 5, 2.0f);

        assertEquals(3, outputFrames);
        assertStereoFrame(pcm, 0, 10, 11);
        assertStereoFrame(pcm, 1, 30, 31);
        assertStereoFrame(pcm, 2, 50, 51);
    }

    @Test
    public void fractionalPhaseContinuesAcrossTransferBlocks() {
        Nintendo3DsFastForwardAudioProcessor processor =
                new Nintendo3DsFastForwardAudioProcessor();
        ByteBuffer first = stereoFrames(0, 100, 1, 101, 2, 102);
        ByteBuffer second = stereoFrames(3, 103, 4, 104, 5, 105);

        assertEquals(2, processor.process(first, 3, 2.0f));
        assertStereoFrame(first, 0, 0, 100);
        assertStereoFrame(first, 1, 2, 102);
        assertEquals(1, processor.process(second, 3, 2.0f));
        assertStereoFrame(second, 0, 4, 104);
    }

    @Test
    public void synchronizationUsesTheSpeedActuallyReachedByTheCore() {
        Nintendo3DsFastForwardAudioProcessor processor =
                new Nintendo3DsFastForwardAudioProcessor();
        long startNanos = 1_000_000_000L;
        assertEquals(2.0f, processor.synchronizePlaybackSpeed(
                410, 2.0f, 32768, startNanos), 0.0f);

        float synchronizedSpeed = 1.0f;
        for (int step = 1; step <= 10; step++) {
            synchronizedSpeed = processor.synchronizePlaybackSpeed(
                    410,
                    2.0f,
                    32768,
                    startNanos + step * 10_000_000L);
        }

        assertEquals(1.25f, synchronizedSpeed, 0.01f);
    }

    @Test
    public void rejectsSpeedsOutsideProductRange() {
        Nintendo3DsFastForwardAudioProcessor processor =
                new Nintendo3DsFastForwardAudioProcessor();
        ByteBuffer pcm = stereoFrames(0, 0);

        assertThrows(IllegalArgumentException.class, () -> processor.process(pcm, 1, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> processor.process(pcm, 1, 5.0f));
        assertThrows(IllegalArgumentException.class, () -> processor.process(pcm, 1, Float.NaN));
    }

    private static ByteBuffer stereoFrames(int... samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * Short.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int sample : samples) {
            buffer.putShort((short) sample);
        }
        return buffer;
    }

    private static void assertStereoFrame(
            ByteBuffer buffer,
            int frame,
            int expectedLeft,
            int expectedRight) {
        int offset = frame * 2 * Short.BYTES;
        assertEquals(expectedLeft, buffer.getShort(offset));
        assertEquals(expectedRight, buffer.getShort(offset + Short.BYTES));
    }
}
