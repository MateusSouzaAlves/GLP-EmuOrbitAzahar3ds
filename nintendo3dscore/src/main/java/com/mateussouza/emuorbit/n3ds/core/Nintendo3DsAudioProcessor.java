// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.nio.ByteBuffer;

/** Adds the same PCM headroom and anti-alias filtering approved for Nintendo DS. */
final class Nintendo3DsAudioProcessor {
    private static final int BYTES_PER_STEREO_FRAME = 4;
    private static final double MAX_NORMALIZED_CUTOFF = 0.45;

    private float firstPoleLeft;
    private float firstPoleRight;
    private float secondPoleLeft;
    private float secondPoleRight;
    private boolean initialized;

    void process(ByteBuffer pcmBuffer, int frames, float playbackSpeed, float gain) {
        if (!Float.isFinite(playbackSpeed) || playbackSpeed < 1.0f) {
            throw new IllegalArgumentException("Playback speed must be at least 1x.");
        }
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("PCM gain must be between zero and one.");
        }

        int availableFrames = Math.min(
                Math.max(frames, 0),
                pcmBuffer.capacity() / BYTES_PER_STEREO_FRAME);
        if (availableFrames == 0) {
            return;
        }

        double normalizedCutoff = MAX_NORMALIZED_CUTOFF / playbackSpeed;
        float alpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * normalizedCutoff));
        for (int frame = 0; frame < availableFrames; frame++) {
            int offset = frame * BYTES_PER_STEREO_FRAME;
            float left = pcmBuffer.getShort(offset);
            float right = pcmBuffer.getShort(offset + Short.BYTES);
            if (!initialized) {
                firstPoleLeft = left;
                firstPoleRight = right;
                secondPoleLeft = left;
                secondPoleRight = right;
                initialized = true;
            } else {
                firstPoleLeft += alpha * (left - firstPoleLeft);
                firstPoleRight += alpha * (right - firstPoleRight);
                secondPoleLeft += alpha * (firstPoleLeft - secondPoleLeft);
                secondPoleRight += alpha * (firstPoleRight - secondPoleRight);
            }
            pcmBuffer.putShort(offset, toPcm16(secondPoleLeft * gain));
            pcmBuffer.putShort(offset + Short.BYTES, toPcm16(secondPoleRight * gain));
        }
    }

    void reset() {
        firstPoleLeft = 0.0f;
        firstPoleRight = 0.0f;
        secondPoleLeft = 0.0f;
        secondPoleRight = 0.0f;
        initialized = false;
    }

    private static short toPcm16(float sample) {
        int rounded = Math.round(sample);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
    }
}
