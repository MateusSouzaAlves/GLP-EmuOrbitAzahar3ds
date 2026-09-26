// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Bounds catch-up PCM work on the core owner thread without skipping the first audio block. */
final class Nintendo3DsAudioDrainPolicy {
    static final int MAX_BLOCKS_PER_FRAME = 4;
    static final long EXTRA_DRAIN_BUDGET_NANOS = 2_500_000L;

    private Nintendo3DsAudioDrainPolicy() {
    }

    static boolean shouldDrainBlock(int blockIndex, long elapsedNanos) {
        if (blockIndex < 0 || blockIndex >= MAX_BLOCKS_PER_FRAME) {
            return false;
        }
        return blockIndex == 0
                || (elapsedNanos >= 0 && elapsedNanos < EXTRA_DRAIN_BUDGET_NANOS);
    }
}
