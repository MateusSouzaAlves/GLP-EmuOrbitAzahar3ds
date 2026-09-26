// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public final class Nintendo3DsAudioProcessorTest {
    @Test
    public void constantSignalKeepsStereoBalanceAndAddsHeadroom() {
        Nintendo3DsAudioProcessor processor = new Nintendo3DsAudioProcessor();
        ByteBuffer pcm = stereoFrames(10000, -10000, 10000, -10000);

        processor.process(pcm, 2, 1.0f, 0.90f);

        assertStereoFrame(pcm, 0, 9000, -9000);
        assertStereoFrame(pcm, 1, 9000, -9000);
    }

    @Test
    public void fastForwardFiltersHighFrequenciesBeforeDecimation() {
        ByteBuffer normalSpeed = alternatingSignal(96, 20000);
        ByteBuffer fourTimesSpeed = alternatingSignal(96, 20000);

        new Nintendo3DsAudioProcessor().process(normalSpeed, 96, 1.0f, 1.0f);
        new Nintendo3DsAudioProcessor().process(fourTimesSpeed, 96, 4.0f, 1.0f);

        assertTrue(averageAbsoluteLevel(fourTimesSpeed, 16)
                < averageAbsoluteLevel(normalSpeed, 16) * 0.35);
    }

    @Test
    public void filterStateIsContinuousAndCanBeReset() {
        Nintendo3DsAudioProcessor processor = new Nintendo3DsAudioProcessor();
        processor.process(stereoFrames(10000, 10000), 1, 2.0f, 1.0f);
        ByteBuffer continuous = stereoFrames(-10000, -10000);

        processor.process(continuous, 1, 2.0f, 1.0f);

        assertTrue(continuous.getShort(0) > -10000);
        processor.reset();
        ByteBuffer reset = stereoFrames(-10000, -10000);
        processor.process(reset, 1, 2.0f, 1.0f);
        assertStereoFrame(reset, 0, -10000, -10000);
    }

    @Test
    public void loudMusicKeepsStableHeadroomWithoutDynamicGainPumping() {
        Nintendo3DsAudioProcessor processor = new Nintendo3DsAudioProcessor();
        ByteBuffer pcm = stereoFrames(32767, -32767, 32767, -32767);

        processor.process(pcm, 2, 1.0f, 0.90f);

        for (int frame = 0; frame < 2; frame++) {
            int left = pcm.getShort(frame * 4);
            int right = pcm.getShort(frame * 4 + Short.BYTES);
            assertEquals(29490, left);
            assertEquals(-left, right);
        }
    }

    private static ByteBuffer alternatingSignal(int frames, int amplitude) {
        ByteBuffer buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder());
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) (frame % 2 == 0 ? amplitude : -amplitude);
            buffer.putShort(sample);
            buffer.putShort(sample);
        }
        return buffer;
    }

    private static double averageAbsoluteLevel(ByteBuffer buffer, int skippedFrames) {
        long total = 0L;
        int samples = 0;
        for (int frame = skippedFrames; frame < buffer.capacity() / 4; frame++) {
            int offset = frame * 4;
            total += Math.abs((int) buffer.getShort(offset));
            total += Math.abs((int) buffer.getShort(offset + Short.BYTES));
            samples += 2;
        }
        return total / (double) samples;
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
            ByteBuffer buffer, int frame, int expectedLeft, int expectedRight) {
        int offset = frame * 2 * Short.BYTES;
        assertEquals(expectedLeft, buffer.getShort(offset));
        assertEquals(expectedRight, buffer.getShort(offset + Short.BYTES));
    }
}
