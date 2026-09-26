// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds an allowlisted diagnostic context that cannot receive content identity or user data. */
final class Nintendo3DsBugReportContext {
    private Nintendo3DsBugReportContext() {
    }

    static Map<String, String> create(
            Nintendo3DsProductUiState state,
            long frameCount,
            String screenLayout,
            String performanceProfile) {
        Objects.requireNonNull(state);
        Map<String, String> context = new LinkedHashMap<>();
        context.put("game_system", "NINTENDO_3DS");
        context.put("emulation_state", state.getSessionState().name());
        context.put("rendered_frames", Long.toString(Math.max(0L, frameCount)));
        context.put("fast_forward_speed", Integer.toString(state.getSpeedMultiplier()));
        context.put("audio_enabled", Boolean.toString(state.isAudioEnabled()));
        context.put("screen_layout", safeValue(screenLayout));
        context.put("performance_profile", safeValue(performanceProfile));
        return Collections.unmodifiableMap(context);
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
