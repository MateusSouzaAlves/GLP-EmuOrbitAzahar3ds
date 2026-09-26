// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Nintendo3DsAudioDrainPolicyTest {
    @Test
    public void firstBlockAlwaysRunsEvenAfterExpensiveFrame() {
        assertTrue(Nintendo3DsAudioDrainPolicy.shouldDrainBlock(0, 20_000_000L));
    }

    @Test
    public void extraBlocksStopAtTimeBudgetOrBlockLimit() {
        assertTrue(Nintendo3DsAudioDrainPolicy.shouldDrainBlock(1, 2_499_999L));
        assertTrue(Nintendo3DsAudioDrainPolicy.shouldDrainBlock(3, 0L));
        assertFalse(Nintendo3DsAudioDrainPolicy.shouldDrainBlock(1, 2_500_000L));
        assertFalse(Nintendo3DsAudioDrainPolicy.shouldDrainBlock(4, 0L));
        assertFalse(Nintendo3DsAudioDrainPolicy.shouldDrainBlock(-1, 0L));
        assertFalse(Nintendo3DsAudioDrainPolicy.shouldDrainBlock(1, -1L));
    }
}
