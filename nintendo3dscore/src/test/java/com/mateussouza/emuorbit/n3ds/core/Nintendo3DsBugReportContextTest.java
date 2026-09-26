// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

public final class Nintendo3DsBugReportContextTest {
    private static final Set<String> EXPECTED_KEYS = Set.of(
            "game_system",
            "emulation_state",
            "rendered_frames",
            "fast_forward_speed",
            "audio_enabled",
            "screen_layout",
            "performance_profile");

    @Test
    public void exposesOnlyAllowlistedTechnicalValues() {
        Map<String, String> context = Nintendo3DsBugReportContext.create(
                Nintendo3DsProductUiState.preparing().sessionReady(true).cycleSpeed(),
                123L,
                "SIDE_BY_SIDE",
                "BALANCED");

        assertEquals(EXPECTED_KEYS, context.keySet());
        assertEquals("NINTENDO_3DS", context.get("game_system"));
        assertEquals("RUNNING", context.get("emulation_state"));
        assertEquals("123", context.get("rendered_frames"));
        assertEquals("2", context.get("fast_forward_speed"));
        assertEquals("true", context.get("audio_enabled"));
        assertFalse(context.keySet().stream().anyMatch(key ->
                key.contains("title") || key.contains("path") || key.contains("rom")
                        || key.contains("mii") || key.contains("save")
                        || key.contains("content")));
    }

    @Test
    public void normalizesBoundsWithoutAcceptingContentMetadata() {
        String longValue = "x".repeat(80);
        Map<String, String> context = Nintendo3DsBugReportContext.create(
                Nintendo3DsProductUiState.preparing(),
                -9L,
                null,
                longValue);

        assertEquals("0", context.get("rendered_frames"));
        assertEquals("unknown", context.get("screen_layout"));
        assertEquals(64, context.get("performance_profile").length());
        boolean immutable = false;
        try {
            context.put("rom_path", "private");
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        assertTrue(immutable);
    }
}
