// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public final class Nintendo3DsPerformanceProfileTest {
    @Test
    public void profilesUseOnlyPinnedAzaharOptionsAndBoundedResolution() {
        for (Nintendo3DsPerformanceProfile profile
                : Nintendo3DsPerformanceProfile.values()) {
            Map<String, String> options = profile.getCoreOptions();
            assertEquals(12, options.size());
            assertEquals("enabled", options.get("citra_use_cpu_jit"));
            assertEquals("100", options.get("citra_cpu_clock_percentage"));
            assertEquals("HLE", options.get("citra_audio_emulation"));
            assertEquals("enabled", options.get("citra_use_hw_shader"));
            assertEquals("enabled", options.get("citra_use_shader_jit"));
            assertEquals(
                    profile.isAccurateShaderMultiplicationEnabled() ? "enabled" : "disabled",
                    options.get("citra_shaders_accurate_mul"));
            assertEquals("enabled", options.get("citra_use_disk_shader_cache"));
            assertEquals(
                    Integer.toString(profile.getResolutionScale()),
                    options.get("citra_resolution_factor"));
            assertEquals("none", options.get("citra_texture_filter"));
            assertEquals("GameControlled", options.get("citra_texture_sampling"));
            assertEquals("disabled", options.get("citra_custom_textures"));
            assertEquals("disabled", options.get("citra_dump_textures"));
        }

        assertEquals(1, Nintendo3DsPerformanceProfile.CONSERVATIVE.getResolutionScale());
        assertEquals(2, Nintendo3DsPerformanceProfile.BALANCED.getResolutionScale());
        assertEquals(2, Nintendo3DsPerformanceProfile.PERFORMANCE.getResolutionScale());
        assertTrue(Nintendo3DsPerformanceProfile.CONSERVATIVE
                .isAccurateShaderMultiplicationEnabled());
        assertTrue(Nintendo3DsPerformanceProfile.BALANCED
                .isAccurateShaderMultiplicationEnabled());
        assertFalse(Nintendo3DsPerformanceProfile.PERFORMANCE
                .isAccurateShaderMultiplicationEnabled());
    }

    @Test
    public void optionViewIsImmutable() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> Nintendo3DsPerformanceProfile.BALANCED
                        .getCoreOptions()
                        .put("citra_resolution_factor", "10"));
    }
}
