// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Objects;

/** Sparse per-game Nintendo 3DS choices layered over the global settings. */
public final class Nintendo3DsExperienceSettingsOverrides {
    private final Nintendo3DsScreenLayout screenLayout;
    private final Nintendo3DsPerformanceProfile performanceProfile;
    private final Boolean audioEnabled;
    private final Float audioVolume;
    private final Boolean microphoneEnabled;
    private final Boolean virtualControlsVisible;
    private final Integer virtualControlOpacityPercent;

    public Nintendo3DsExperienceSettingsOverrides(
            Nintendo3DsScreenLayout screenLayout,
            Boolean audioEnabled,
            Float audioVolume,
            Boolean microphoneEnabled) {
        this(
                screenLayout,
                null,
                audioEnabled,
                audioVolume,
                microphoneEnabled,
                null,
                null);
    }

    public Nintendo3DsExperienceSettingsOverrides(
            Nintendo3DsScreenLayout screenLayout,
            Nintendo3DsPerformanceProfile performanceProfile,
            Boolean audioEnabled,
            Float audioVolume,
            Boolean microphoneEnabled) {
        this(
                screenLayout,
                performanceProfile,
                audioEnabled,
                audioVolume,
                microphoneEnabled,
                null,
                null);
    }

    public Nintendo3DsExperienceSettingsOverrides(
            Nintendo3DsScreenLayout screenLayout,
            Nintendo3DsPerformanceProfile performanceProfile,
            Boolean audioEnabled,
            Float audioVolume,
            Boolean microphoneEnabled,
            Boolean virtualControlsVisible,
            Integer virtualControlOpacityPercent) {
        this.screenLayout = screenLayout;
        this.performanceProfile = performanceProfile;
        this.audioEnabled = audioEnabled;
        this.audioVolume = audioVolume == null
                ? null
                : Nintendo3DsExperienceSettings.normalizeAudioVolume(audioVolume);
        this.microphoneEnabled = microphoneEnabled;
        this.virtualControlsVisible = virtualControlsVisible;
        this.virtualControlOpacityPercent = virtualControlOpacityPercent == null
                ? null
                : Nintendo3DsExperienceSettings.normalizeVirtualControlOpacity(
                        virtualControlOpacityPercent);
    }

    public static Nintendo3DsExperienceSettingsOverrides none() {
        return new Nintendo3DsExperienceSettingsOverrides(
                null, null, null, null, null, null, null);
    }

    public static Nintendo3DsExperienceSettingsOverrides between(
            Nintendo3DsExperienceSettings global,
            Nintendo3DsExperienceSettings selected) {
        Objects.requireNonNull(global);
        Objects.requireNonNull(selected);
        return new Nintendo3DsExperienceSettingsOverrides(
                global.getScreenLayout() == selected.getScreenLayout()
                        ? null : selected.getScreenLayout(),
                global.getPerformanceProfile() == selected.getPerformanceProfile()
                        ? null : selected.getPerformanceProfile(),
                global.isAudioEnabled() == selected.isAudioEnabled()
                        ? null : selected.isAudioEnabled(),
                Float.compare(global.getAudioVolume(), selected.getAudioVolume()) == 0
                        ? null : selected.getAudioVolume(),
                global.isMicrophoneEnabled() == selected.isMicrophoneEnabled()
                        ? null : selected.isMicrophoneEnabled(),
                global.areVirtualControlsVisible() == selected.areVirtualControlsVisible()
                        ? null : selected.areVirtualControlsVisible(),
                global.getVirtualControlOpacityPercent()
                                == selected.getVirtualControlOpacityPercent()
                        ? null : selected.getVirtualControlOpacityPercent());
    }

    public Nintendo3DsExperienceSettings resolve(Nintendo3DsExperienceSettings global) {
        Objects.requireNonNull(global);
        return new Nintendo3DsExperienceSettings(
                screenLayout == null ? global.getScreenLayout() : screenLayout,
                performanceProfile == null
                        ? global.getPerformanceProfile() : performanceProfile,
                audioEnabled == null ? global.isAudioEnabled() : audioEnabled,
                audioVolume == null ? global.getAudioVolume() : audioVolume,
                microphoneEnabled == null
                        ? global.isMicrophoneEnabled() : microphoneEnabled,
                virtualControlsVisible == null
                        ? global.areVirtualControlsVisible() : virtualControlsVisible,
                virtualControlOpacityPercent == null
                        ? global.getVirtualControlOpacityPercent()
                        : virtualControlOpacityPercent);
    }

    public boolean isEmpty() {
        return screenLayout == null
                && performanceProfile == null
                && audioEnabled == null
                && audioVolume == null
                && microphoneEnabled == null
                && virtualControlsVisible == null
                && virtualControlOpacityPercent == null;
    }

    public Nintendo3DsScreenLayout getScreenLayout() {
        return screenLayout;
    }

    public Boolean getAudioEnabled() {
        return audioEnabled;
    }

    public Nintendo3DsPerformanceProfile getPerformanceProfile() {
        return performanceProfile;
    }

    public Float getAudioVolume() {
        return audioVolume;
    }

    public Boolean getMicrophoneEnabled() {
        return microphoneEnabled;
    }

    public Boolean getVirtualControlsVisible() {
        return virtualControlsVisible;
    }

    public Integer getVirtualControlOpacityPercent() {
        return virtualControlOpacityPercent;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Nintendo3DsExperienceSettingsOverrides)) {
            return false;
        }
        Nintendo3DsExperienceSettingsOverrides value =
                (Nintendo3DsExperienceSettingsOverrides) other;
        return screenLayout == value.screenLayout
                && performanceProfile == value.performanceProfile
                && Objects.equals(audioEnabled, value.audioEnabled)
                && Objects.equals(audioVolume, value.audioVolume)
                && Objects.equals(microphoneEnabled, value.microphoneEnabled)
                && Objects.equals(virtualControlsVisible, value.virtualControlsVisible)
                && Objects.equals(
                        virtualControlOpacityPercent,
                        value.virtualControlOpacityPercent);
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
