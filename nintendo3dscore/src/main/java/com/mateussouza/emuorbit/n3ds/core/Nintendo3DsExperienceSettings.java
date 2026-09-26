// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.io.IOException;
import java.util.Objects;

/** Immutable user choices that can be applied before a Nintendo 3DS session starts. */
public final class Nintendo3DsExperienceSettings {
    public static final float MINIMUM_AUDIO_VOLUME = 0.0f;
    public static final float MAXIMUM_AUDIO_VOLUME = 1.0f;
    public static final int MINIMUM_VIRTUAL_CONTROL_OPACITY = 20;
    public static final int MAXIMUM_VIRTUAL_CONTROL_OPACITY = 100;
    public static final int VIRTUAL_CONTROL_OPACITY_STEP = 10;
    public static final int DEFAULT_VIRTUAL_CONTROL_OPACITY = 30;

    private final Nintendo3DsScreenLayout screenLayout;
    private final Nintendo3DsPerformanceProfile performanceProfile;
    private final boolean audioEnabled;
    private final float audioVolume;
    private final boolean microphoneEnabled;
    private final boolean virtualControlsVisible;
    private final int virtualControlOpacityPercent;

    public Nintendo3DsExperienceSettings(
            Nintendo3DsScreenLayout screenLayout,
            boolean audioEnabled,
            float audioVolume,
            boolean microphoneEnabled) {
        this(
                screenLayout,
                Nintendo3DsPerformanceProfile.BALANCED,
                audioEnabled,
                audioVolume,
                microphoneEnabled,
                true,
                DEFAULT_VIRTUAL_CONTROL_OPACITY);
    }

    public Nintendo3DsExperienceSettings(
            Nintendo3DsScreenLayout screenLayout,
            Nintendo3DsPerformanceProfile performanceProfile,
            boolean audioEnabled,
            float audioVolume,
            boolean microphoneEnabled) {
        this(
                screenLayout,
                performanceProfile,
                audioEnabled,
                audioVolume,
                microphoneEnabled,
                true,
                DEFAULT_VIRTUAL_CONTROL_OPACITY);
    }

    public Nintendo3DsExperienceSettings(
            Nintendo3DsScreenLayout screenLayout,
            Nintendo3DsPerformanceProfile performanceProfile,
            boolean audioEnabled,
            float audioVolume,
            boolean microphoneEnabled,
            boolean virtualControlsVisible,
            int virtualControlOpacityPercent) {
        this.screenLayout = screenLayout == null
                ? Nintendo3DsScreenLayout.DEFAULT
                : screenLayout;
        this.performanceProfile = performanceProfile == null
                ? Nintendo3DsPerformanceProfile.BALANCED
                : performanceProfile;
        this.audioEnabled = audioEnabled;
        this.audioVolume = normalizeAudioVolume(audioVolume);
        this.microphoneEnabled = microphoneEnabled;
        this.virtualControlsVisible = virtualControlsVisible;
        this.virtualControlOpacityPercent = normalizeVirtualControlOpacity(
                virtualControlOpacityPercent);
    }

    /** Audio is a normal product default; microphone capture always requires explicit opt-in. */
    public static Nintendo3DsExperienceSettings defaults() {
        return new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.DEFAULT,
                Nintendo3DsPerformanceProfile.CONSERVATIVE,
                true,
                MAXIMUM_AUDIO_VOLUME,
                false,
                true,
                DEFAULT_VIRTUAL_CONTROL_OPACITY);
    }

    public Nintendo3DsScreenLayout getScreenLayout() {
        return screenLayout;
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public Nintendo3DsPerformanceProfile getPerformanceProfile() {
        return performanceProfile;
    }

    public float getAudioVolume() {
        return audioVolume;
    }

    public boolean isMicrophoneEnabled() {
        return microphoneEnabled;
    }

    public boolean areVirtualControlsVisible() {
        return virtualControlsVisible;
    }

    public int getVirtualControlOpacityPercent() {
        return virtualControlOpacityPercent;
    }

    /** Applies sanitized settings while keeping permission handling in the Android host. */
    public Nintendo3DsMicrophoneStartResult applyTo(
            Nintendo3DsCoreLifecycleController controller) throws IOException {
        return applyTo(controller, screenLayout);
    }

    Nintendo3DsMicrophoneStartResult applyTo(
            Nintendo3DsCoreLifecycleController controller,
            Nintendo3DsScreenLayout effectiveScreenLayout) throws IOException {
        Nintendo3DsCoreLifecycleController checked = Objects.requireNonNull(controller);
        checked.setPerformanceProfile(performanceProfile);
        checked.setScreenLayout(Objects.requireNonNull(effectiveScreenLayout));
        checked.setAudioVolume(audioVolume);
        checked.setAudioEnabled(audioEnabled);
        return checked.setMicrophoneEnabled(microphoneEnabled);
    }

    public static float normalizeAudioVolume(float audioVolume) {
        if (!Float.isFinite(audioVolume)) {
            return MAXIMUM_AUDIO_VOLUME;
        }
        return Math.max(MINIMUM_AUDIO_VOLUME,
                Math.min(MAXIMUM_AUDIO_VOLUME, audioVolume));
    }

    public static int normalizeVirtualControlOpacity(int opacityPercent) {
        int clamped = Math.max(
                MINIMUM_VIRTUAL_CONTROL_OPACITY,
                Math.min(MAXIMUM_VIRTUAL_CONTROL_OPACITY, opacityPercent));
        return Math.round(clamped / (float) VIRTUAL_CONTROL_OPACITY_STEP)
                * VIRTUAL_CONTROL_OPACITY_STEP;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Nintendo3DsExperienceSettings)) {
            return false;
        }
        Nintendo3DsExperienceSettings value = (Nintendo3DsExperienceSettings) other;
        return screenLayout == value.screenLayout
                && performanceProfile == value.performanceProfile
                && audioEnabled == value.audioEnabled
                && Float.compare(audioVolume, value.audioVolume) == 0
                && microphoneEnabled == value.microphoneEnabled
                && virtualControlsVisible == value.virtualControlsVisible
                && virtualControlOpacityPercent == value.virtualControlOpacityPercent;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                screenLayout,
                performanceProfile,
                audioEnabled,
                audioVolume,
                microphoneEnabled,
                virtualControlsVisible,
                virtualControlOpacityPercent);
    }
}
