// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe presets composed exclusively from options exposed by the pinned Azahar core. */
public enum Nintendo3DsPerformanceProfile {
    CONSERVATIVE("conservative", "1", true),
    BALANCED("balanced", "2", true),
    PERFORMANCE("performance", "2", false);

    private final String coreValue;
    private final String resolutionFactor;
    private final boolean accurateShaderMultiplication;
    private final Map<String, String> coreOptions;

    Nintendo3DsPerformanceProfile(
            String coreValue,
            String resolutionFactor,
            boolean accurateShaderMultiplication) {
        this.coreValue = coreValue;
        this.resolutionFactor = resolutionFactor;
        this.accurateShaderMultiplication = accurateShaderMultiplication;
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("citra_use_cpu_jit", "enabled");
        options.put("citra_cpu_clock_percentage", "100");
        options.put("citra_use_hw_shader", "enabled");
        options.put("citra_use_shader_jit", "enabled");
        options.put(
                "citra_shaders_accurate_mul",
                accurateShaderMultiplication ? "enabled" : "disabled");
        options.put("citra_use_disk_shader_cache", "enabled");
        options.put("citra_resolution_factor", resolutionFactor);
        options.put("citra_texture_filter", "none");
        options.put("citra_texture_sampling", "GameControlled");
        options.put("citra_custom_textures", "disabled");
        options.put("citra_dump_textures", "disabled");
        coreOptions = Collections.unmodifiableMap(options);
    }

    String getCoreValue() {
        return coreValue;
    }

    public int getResolutionScale() {
        return Integer.parseInt(resolutionFactor);
    }

    public boolean isAccurateShaderMultiplicationEnabled() {
        return accurateShaderMultiplication;
    }

    /** Exposes an immutable diagnostic view used to audit the frontend/core contract. */
    public Map<String, String> getCoreOptions() {
        return coreOptions;
    }
}
