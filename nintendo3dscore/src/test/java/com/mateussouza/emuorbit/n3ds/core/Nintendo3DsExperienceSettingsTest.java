// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Nintendo3DsExperienceSettingsTest {
    @Test
    public void defaultsEnableNormalAudioButKeepMicrophonePrivate() {
        Nintendo3DsExperienceSettings defaults = Nintendo3DsExperienceSettings.defaults();

        assertEquals(Nintendo3DsScreenLayout.DEFAULT, defaults.getScreenLayout());
        assertEquals(
                Nintendo3DsPerformanceProfile.CONSERVATIVE,
                defaults.getPerformanceProfile());
        assertTrue(defaults.isAudioEnabled());
        assertEquals(1.0f, defaults.getAudioVolume(), 0.0f);
        assertFalse(defaults.isMicrophoneEnabled());
        assertTrue(defaults.areVirtualControlsVisible());
        assertEquals(30, defaults.getVirtualControlOpacityPercent());
    }

    @Test
    public void constructorSanitizesLayoutAndVolume() {
        Nintendo3DsExperienceSettings missing = new Nintendo3DsExperienceSettings(
                null,
                null,
                false,
                Float.NaN,
                true);
        Nintendo3DsExperienceSettings low = new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.SINGLE_TOP,
                true,
                -2.0f,
                false);
        Nintendo3DsExperienceSettings high = new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.SINGLE_BOTTOM,
                true,
                4.0f,
                false);

        assertEquals(Nintendo3DsScreenLayout.DEFAULT, missing.getScreenLayout());
        assertEquals(
                Nintendo3DsPerformanceProfile.BALANCED,
                missing.getPerformanceProfile());
        assertEquals(1.0f, missing.getAudioVolume(), 0.0f);
        assertEquals(0.0f, low.getAudioVolume(), 0.0f);
        assertEquals(1.0f, high.getAudioVolume(), 0.0f);
        assertEquals(20,
                Nintendo3DsExperienceSettings.normalizeVirtualControlOpacity(-4));
        assertEquals(70,
                Nintendo3DsExperienceSettings.normalizeVirtualControlOpacity(66));
        assertEquals(100,
                Nintendo3DsExperienceSettings.normalizeVirtualControlOpacity(140));
    }

    @Test
    public void sparseOverridesRoundTripAgainstGlobalSettings() {
        Nintendo3DsExperienceSettings global = new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.SIDE_BY_SIDE,
                Nintendo3DsPerformanceProfile.BALANCED,
                true,
                0.75f,
                false,
                true,
                70);
        Nintendo3DsExperienceSettings selected = new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.LARGE_BOTTOM,
                Nintendo3DsPerformanceProfile.PERFORMANCE,
                false,
                0.75f,
                true,
                false,
                40);

        Nintendo3DsExperienceSettingsOverrides overrides =
                Nintendo3DsExperienceSettingsOverrides.between(global, selected);

        assertEquals(Nintendo3DsScreenLayout.LARGE_BOTTOM, overrides.getScreenLayout());
        assertEquals(
                Nintendo3DsPerformanceProfile.PERFORMANCE,
                overrides.getPerformanceProfile());
        assertEquals(Boolean.FALSE, overrides.getAudioEnabled());
        assertNull(overrides.getAudioVolume());
        assertEquals(Boolean.TRUE, overrides.getMicrophoneEnabled());
        assertEquals(Boolean.FALSE, overrides.getVirtualControlsVisible());
        assertEquals(Integer.valueOf(40), overrides.getVirtualControlOpacityPercent());
        assertEquals(selected, overrides.resolve(global));
        assertFalse(overrides.isEmpty());
    }

    @Test
    public void equalSelectionsProduceNoOverrides() {
        Nintendo3DsExperienceSettings defaults = Nintendo3DsExperienceSettings.defaults();

        Nintendo3DsExperienceSettingsOverrides overrides =
                Nintendo3DsExperienceSettingsOverrides.between(defaults, defaults);

        assertEquals(Nintendo3DsExperienceSettingsOverrides.none(), overrides);
        assertTrue(overrides.isEmpty());
        assertEquals(defaults, overrides.resolve(defaults));
    }
}
