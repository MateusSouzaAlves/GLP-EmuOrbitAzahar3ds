// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.io.IOException;
import java.util.Objects;

/** Immutable user choices that can be applied before a Nintendo 3DS session starts. */
public final class Nintendo3DsExperienceSettings {
    public static final float MINIMUM_AUDIO_VOLUME = 0.0f;
    public static final float MAXIMUM_AUDIO_VOLUME = 1.0f;

    private final Nintendo3DsScreenLayout screenLayout;
    private final Nintendo3DsPerformanceProfile performanceProfile;
    private final boolean audioEnabled;
    private final float audioVolume;
    private final boolean microphoneEnabled;

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
                microphoneEnabled);
    }

    public Nintendo3DsExperienceSettings(
            Nintendo3DsScreenLayout screenLayout,
            Nintendo3DsPerformanceProfile performanceProfile,
            boolean audioEnabled,
            float audioVolume,
            boolean microphoneEnabled) {
        this.screenLayout = screenLayout == null
                ? Nintendo3DsScreenLayout.DEFAULT
                : screenLayout;
        this.performanceProfile = performanceProfile == null
                ? Nintendo3DsPerformanceProfile.BALANCED
                : performanceProfile;
        this.audioEnabled = audioEnabled;
        this.audioVolume = normalizeAudioVolume(audioVolume);
        this.microphoneEnabled = microphoneEnabled;
    }

    /** Audio is a normal product default; microphone capture always requires explicit opt-in. */
    public static Nintendo3DsExperienceSettings defaults() {
        return new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.DEFAULT,
                Nintendo3DsPerformanceProfile.BALANCED,
                true,
                MAXIMUM_AUDIO_VOLUME,
                false);
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

    /** Applies sanitized settings while keeping permission handling in the Android host. */
    public Nintendo3DsMicrophoneStartResult applyTo(
            Nintendo3DsCoreLifecycleController controller) throws IOException {
        Nintendo3DsCoreLifecycleController checked = Objects.requireNonNull(controller);
        checked.setPerformanceProfile(performanceProfile);
        checked.setScreenLayout(screenLayout);
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
                && microphoneEnabled == value.microphoneEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                screenLayout,
                performanceProfile,
                audioEnabled,
                audioVolume,
                microphoneEnabled);
    }
}
