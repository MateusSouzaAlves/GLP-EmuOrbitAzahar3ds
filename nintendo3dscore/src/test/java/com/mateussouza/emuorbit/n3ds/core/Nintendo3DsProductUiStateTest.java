// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class Nintendo3DsProductUiStateTest {
    @Test
    public void preparingAndFailureNeverExposeSessionActions() {
        Nintendo3DsProductUiState preparing = Nintendo3DsProductUiState.preparing();

        assertFalse(preparing.canControlSession());
        assertSame(preparing, preparing.toggleAudio());
        assertSame(preparing, preparing.togglePause());
        assertSame(preparing, preparing.cycleSpeed());

        Nintendo3DsProductUiState failed = preparing.failed();
        assertEquals(Nintendo3DsProductUiState.SessionState.FAILED, failed.getSessionState());
        assertFalse(failed.canControlSession());
    }

    @Test
    public void readySessionExposesOnlySupportedAudioPauseAndSpeedStates() {
        Nintendo3DsProductUiState state = Nintendo3DsProductUiState.preparing()
                .sessionReady(false);

        assertTrue(state.canControlSession());
        assertFalse(state.isAudioEnabled());
        assertEquals(1, state.getSpeedMultiplier());

        state = state.toggleAudio().togglePause();
        assertTrue(state.isAudioEnabled());
        assertTrue(state.isPaused());

        state = state.cycleSpeed();
        assertEquals(2, state.getSpeedMultiplier());
        state = state.cycleSpeed();
        assertEquals(4, state.getSpeedMultiplier());
        state = state.cycleSpeed();
        assertEquals(1, state.getSpeedMultiplier());

        Nintendo3DsProductUiState restarting = state.restarting();
        assertEquals(
                Nintendo3DsProductUiState.SessionState.RESTARTING,
                restarting.getSessionState());
        assertFalse(restarting.canControlSession());
        assertEquals(state.getSpeedMultiplier(), restarting.getSpeedMultiplier());

        state = state.togglePause();
        assertEquals(Nintendo3DsProductUiState.SessionState.RUNNING, state.getSessionState());
    }

    @Test
    public void splitCompatRunsInsideProductionAndLocalUxDeliveryPackages() {
        assertTrue(Nintendo3DsProductRuntimePolicy.requiresSplitCompat(
                "com.mateussouza.emuorbit.advance"));
        assertTrue(Nintendo3DsProductRuntimePolicy.requiresSplitCompat(
                "com.mateussouza.emuorbit.advance.n3ds.uxtest"));
        assertFalse(Nintendo3DsProductRuntimePolicy.requiresSplitCompat(
                "com.mateussouza.emuorbit.n3ds.core.test"));
        assertFalse(Nintendo3DsProductRuntimePolicy.requiresSplitCompat(
                "com.mateussouza.emuorbit.n3ds.adreno.target"));
    }

    @Test
    public void recreationRestoresOnlyValidatedTransientSessionControls() {
        Nintendo3DsProductUiState restored =
                Nintendo3DsProductUiState.restoredSession(false, 4, true);

        assertEquals(Nintendo3DsProductUiState.SessionState.PAUSED,
                restored.getSessionState());
        assertFalse(restored.isAudioEnabled());
        assertEquals(4, restored.getSpeedMultiplier());
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsProductUiState.restoredSession(true, 3, false));
    }
}
