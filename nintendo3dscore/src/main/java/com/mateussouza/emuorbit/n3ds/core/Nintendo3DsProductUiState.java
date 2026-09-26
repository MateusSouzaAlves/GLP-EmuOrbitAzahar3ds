// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Immutable capability-limited state for the Nintendo 3DS gameplay shell. */
public final class Nintendo3DsProductUiState {
    public enum SessionState {
        PREPARING,
        RESTARTING,
        RUNNING,
        PAUSED,
        FAILED
    }

    private static final int[] SUPPORTED_SPEEDS = {1, 2, 4};

    private final SessionState sessionState;
    private final boolean audioEnabled;
    private final int speedMultiplier;

    private Nintendo3DsProductUiState(
            SessionState sessionState,
            boolean audioEnabled,
            int speedMultiplier) {
        this.sessionState = sessionState;
        this.audioEnabled = audioEnabled;
        this.speedMultiplier = requireSupportedSpeed(speedMultiplier);
    }

    public static Nintendo3DsProductUiState preparing() {
        return new Nintendo3DsProductUiState(SessionState.PREPARING, true, 1);
    }

    public static Nintendo3DsProductUiState restoredSession(
            boolean audioEnabled,
            int speedMultiplier,
            boolean paused) {
        return new Nintendo3DsProductUiState(
                paused ? SessionState.PAUSED : SessionState.RUNNING,
                audioEnabled,
                speedMultiplier);
    }

    public Nintendo3DsProductUiState sessionReady(boolean enabledAudio) {
        return new Nintendo3DsProductUiState(SessionState.RUNNING, enabledAudio, speedMultiplier);
    }

    public Nintendo3DsProductUiState togglePause() {
        if (sessionState == SessionState.RUNNING) {
            return new Nintendo3DsProductUiState(SessionState.PAUSED, audioEnabled, speedMultiplier);
        }
        if (sessionState == SessionState.PAUSED) {
            return new Nintendo3DsProductUiState(SessionState.RUNNING, audioEnabled, speedMultiplier);
        }
        return this;
    }

    public Nintendo3DsProductUiState toggleAudio() {
        if (!canControlSession()) {
            return this;
        }
        return withAudioEnabled(!audioEnabled);
    }

    public Nintendo3DsProductUiState withAudioEnabled(boolean enabled) {
        return new Nintendo3DsProductUiState(sessionState, enabled, speedMultiplier);
    }

    public Nintendo3DsProductUiState cycleSpeed() {
        if (!canControlSession()) {
            return this;
        }
        int nextIndex = 0;
        for (int index = 0; index < SUPPORTED_SPEEDS.length; index++) {
            if (SUPPORTED_SPEEDS[index] == speedMultiplier) {
                nextIndex = (index + 1) % SUPPORTED_SPEEDS.length;
                break;
            }
        }
        return new Nintendo3DsProductUiState(
                sessionState,
                audioEnabled,
                SUPPORTED_SPEEDS[nextIndex]);
    }

    public Nintendo3DsProductUiState failed() {
        return new Nintendo3DsProductUiState(SessionState.FAILED, audioEnabled, speedMultiplier);
    }

    public Nintendo3DsProductUiState restarting() {
        if (!canControlSession()) {
            return this;
        }
        return new Nintendo3DsProductUiState(
                SessionState.RESTARTING,
                audioEnabled,
                speedMultiplier);
    }

    public SessionState getSessionState() {
        return sessionState;
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public int getSpeedMultiplier() {
        return speedMultiplier;
    }

    public boolean isPaused() {
        return sessionState == SessionState.PAUSED;
    }

    public boolean canControlSession() {
        return sessionState == SessionState.RUNNING || sessionState == SessionState.PAUSED;
    }

    private static int requireSupportedSpeed(int speedMultiplier) {
        for (int supported : SUPPORTED_SPEEDS) {
            if (supported == speedMultiplier) {
                return speedMultiplier;
            }
        }
        throw new IllegalArgumentException("Velocidade 3DS não suportada: " + speedMultiplier);
    }
}
