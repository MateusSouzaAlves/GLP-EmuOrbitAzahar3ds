// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Global Nintendo 3DS experience settings plus sparse per-game overrides. */
public final class Nintendo3DsExperienceSettingsStore {
    private static final String PREFERENCES_NAME = "nintendo_3ds_experience_settings_v1";
    private static final String GLOBAL_PREFIX = "global.";
    private static final String GAME_PREFIX = "game.";
    private static final String SCREEN_LAYOUT = "screen_layout";
    private static final String PERFORMANCE_PROFILE = "performance_profile";
    private static final String AUDIO_ENABLED = "audio_enabled";
    private static final String AUDIO_VOLUME = "audio_volume";
    private static final String MICROPHONE_ENABLED = "microphone_enabled";
    private static final Pattern PERSISTENT_ID_PATTERN = Pattern.compile(
            "n3ds-v1:[0-9a-f]{64}:[0-9a-f]{64}(?::[0-9a-f]{64})?");

    private final SharedPreferences preferences;

    public Nintendo3DsExperienceSettingsStore(Context context) {
        this(context, PREFERENCES_NAME);
    }

    Nintendo3DsExperienceSettingsStore(Context context, String preferencesName) {
        preferences = Objects.requireNonNull(context)
                .getApplicationContext()
                .getSharedPreferences(Objects.requireNonNull(preferencesName), Context.MODE_PRIVATE);
    }

    public Nintendo3DsExperienceSettings loadGlobal() {
        Nintendo3DsExperienceSettings defaults = Nintendo3DsExperienceSettings.defaults();
        return new Nintendo3DsExperienceSettings(
                readLayout(GLOBAL_PREFIX + SCREEN_LAYOUT, defaults.getScreenLayout()),
                readPerformanceProfile(
                        GLOBAL_PREFIX + PERFORMANCE_PROFILE,
                        defaults.getPerformanceProfile()),
                readBoolean(GLOBAL_PREFIX + AUDIO_ENABLED, defaults.isAudioEnabled()),
                readFloat(GLOBAL_PREFIX + AUDIO_VOLUME, defaults.getAudioVolume()),
                readBoolean(
                        GLOBAL_PREFIX + MICROPHONE_ENABLED,
                        defaults.isMicrophoneEnabled()));
    }

    public boolean saveGlobal(Nintendo3DsExperienceSettings settings) {
        if (settings == null) {
            return false;
        }
        return preferences.edit()
                .putString(GLOBAL_PREFIX + SCREEN_LAYOUT, settings.getScreenLayout().name())
                .putString(
                        GLOBAL_PREFIX + PERFORMANCE_PROFILE,
                        settings.getPerformanceProfile().name())
                .putBoolean(GLOBAL_PREFIX + AUDIO_ENABLED, settings.isAudioEnabled())
                .putFloat(GLOBAL_PREFIX + AUDIO_VOLUME, settings.getAudioVolume())
                .putBoolean(GLOBAL_PREFIX + MICROPHONE_ENABLED, settings.isMicrophoneEnabled())
                .commit();
    }

    public boolean resetGlobal() {
        return preferences.edit()
                .remove(GLOBAL_PREFIX + SCREEN_LAYOUT)
                .remove(GLOBAL_PREFIX + PERFORMANCE_PROFILE)
                .remove(GLOBAL_PREFIX + AUDIO_ENABLED)
                .remove(GLOBAL_PREFIX + AUDIO_VOLUME)
                .remove(GLOBAL_PREFIX + MICROPHONE_ENABLED)
                .commit();
    }

    public Nintendo3DsExperienceSettingsOverrides loadGameOverrides(String persistentId) {
        String prefix = gamePrefix(persistentId);
        return new Nintendo3DsExperienceSettingsOverrides(
                preferences.contains(prefix + SCREEN_LAYOUT)
                        ? readLayout(prefix + SCREEN_LAYOUT, null) : null,
                preferences.contains(prefix + PERFORMANCE_PROFILE)
                        ? readPerformanceProfile(prefix + PERFORMANCE_PROFILE, null) : null,
                preferences.contains(prefix + AUDIO_ENABLED)
                        ? readOptionalBoolean(prefix + AUDIO_ENABLED) : null,
                preferences.contains(prefix + AUDIO_VOLUME)
                        ? readOptionalFloat(prefix + AUDIO_VOLUME) : null,
                preferences.contains(prefix + MICROPHONE_ENABLED)
                        ? readOptionalBoolean(prefix + MICROPHONE_ENABLED) : null);
    }

    public boolean saveGameOverrides(
            String persistentId,
            Nintendo3DsExperienceSettingsOverrides overrides) {
        if (overrides == null) {
            return false;
        }
        String prefix = gamePrefix(persistentId);
        SharedPreferences.Editor editor = preferences.edit();
        removeGameKeys(editor, prefix);
        writeGameOverrides(editor, prefix, overrides);
        return editor.commit();
    }

    public boolean removeGameOverrides(String persistentId) {
        SharedPreferences.Editor editor = preferences.edit();
        removeGameKeys(editor, gamePrefix(persistentId));
        return editor.commit();
    }

    public Map<String, Nintendo3DsExperienceSettingsOverrides> listGameOverrides() {
        Map<String, Nintendo3DsExperienceSettingsOverrides> values = new TreeMap<>();
        for (String key : preferences.getAll().keySet()) {
            String persistentId = persistentIdFromKey(key);
            if (persistentId == null || values.containsKey(persistentId)) {
                continue;
            }
            Nintendo3DsExperienceSettingsOverrides overrides = loadGameOverrides(persistentId);
            if (!overrides.isEmpty()) {
                values.put(persistentId, overrides);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Writes sparse per-game values without touching the global Nintendo 3DS settings. */
    public boolean writeAllGameOverrides(
            Map<String, Nintendo3DsExperienceSettingsOverrides> values,
            boolean replaceExisting) {
        if (values == null || values.containsValue(null)) {
            return false;
        }
        Map<String, Nintendo3DsExperienceSettingsOverrides> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Nintendo3DsExperienceSettingsOverrides> entry
                : values.entrySet()) {
            normalized.put(normalizePersistentId(entry.getKey()), entry.getValue());
        }

        SharedPreferences.Editor editor = preferences.edit();
        if (replaceExisting) {
            for (String persistentId : listGameOverrides().keySet()) {
                removeGameKeys(editor, gamePrefix(persistentId));
            }
        }
        for (Map.Entry<String, Nintendo3DsExperienceSettingsOverrides> entry
                : normalized.entrySet()) {
            String prefix = gamePrefix(entry.getKey());
            removeGameKeys(editor, prefix);
            writeGameOverrides(editor, prefix, entry.getValue());
        }
        return editor.commit();
    }

    private static void writeGameOverrides(
            SharedPreferences.Editor editor,
            String prefix,
            Nintendo3DsExperienceSettingsOverrides overrides) {
        if (overrides.getScreenLayout() != null) {
            editor.putString(prefix + SCREEN_LAYOUT, overrides.getScreenLayout().name());
        }
        if (overrides.getPerformanceProfile() != null) {
            editor.putString(
                    prefix + PERFORMANCE_PROFILE,
                    overrides.getPerformanceProfile().name());
        }
        if (overrides.getAudioEnabled() != null) {
            editor.putBoolean(prefix + AUDIO_ENABLED, overrides.getAudioEnabled());
        }
        if (overrides.getAudioVolume() != null) {
            editor.putFloat(prefix + AUDIO_VOLUME, overrides.getAudioVolume());
        }
        if (overrides.getMicrophoneEnabled() != null) {
            editor.putBoolean(prefix + MICROPHONE_ENABLED, overrides.getMicrophoneEnabled());
        }
    }

    private static void removeGameKeys(SharedPreferences.Editor editor, String prefix) {
        editor.remove(prefix + SCREEN_LAYOUT)
                .remove(prefix + PERFORMANCE_PROFILE)
                .remove(prefix + AUDIO_ENABLED)
                .remove(prefix + AUDIO_VOLUME)
                .remove(prefix + MICROPHONE_ENABLED);
    }

    private String persistentIdFromKey(String key) {
        if (key == null || !key.startsWith(GAME_PREFIX)) {
            return null;
        }
        int fieldSeparator = key.lastIndexOf('.');
        if (fieldSeparator <= GAME_PREFIX.length()) {
            return null;
        }
        String candidate = key.substring(GAME_PREFIX.length(), fieldSeparator);
        return PERSISTENT_ID_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private Nintendo3DsScreenLayout readLayout(
            String key,
            Nintendo3DsScreenLayout fallback) {
        String value = readString(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Nintendo3DsScreenLayout.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private Nintendo3DsPerformanceProfile readPerformanceProfile(
            String key,
            Nintendo3DsPerformanceProfile fallback) {
        String value = readString(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Nintendo3DsPerformanceProfile.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private String readString(String key, String fallback) {
        try {
            return preferences.getString(key, fallback);
        } catch (ClassCastException exception) {
            return fallback;
        }
    }

    private boolean readBoolean(String key, boolean fallback) {
        Boolean value = readOptionalBoolean(key);
        return value == null ? fallback : value;
    }

    private Boolean readOptionalBoolean(String key) {
        if (!preferences.contains(key)) {
            return null;
        }
        try {
            return preferences.getBoolean(key, false);
        } catch (ClassCastException exception) {
            return null;
        }
    }

    private float readFloat(String key, float fallback) {
        Float value = readOptionalFloat(key);
        return value == null ? fallback : value;
    }

    private Float readOptionalFloat(String key) {
        if (!preferences.contains(key)) {
            return null;
        }
        try {
            float value = preferences.getFloat(key, Float.NaN);
            return Float.isFinite(value) ? value : null;
        } catch (ClassCastException exception) {
            return null;
        }
    }

    private static String gamePrefix(String persistentId) {
        return GAME_PREFIX + normalizePersistentId(persistentId) + ".";
    }

    /** Validates and canonicalizes the privacy-preserving ID shared by product entry points. */
    public static String normalizePersistentId(String persistentId) {
        String normalized = persistentId == null
                ? ""
                : persistentId.trim().toLowerCase(Locale.ROOT);
        if (!PERSISTENT_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "O ID persistente 3DS deve seguir o formato n3ds-v1 e hashes SHA-256.");
        }
        return normalized;
    }
}
