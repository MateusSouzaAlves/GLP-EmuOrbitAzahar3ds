// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.regex.Pattern;

/** Private storage for the 3DS-only global and per-game controller layouts. */
public final class Nintendo3DsVirtualControlProfileStore {
    static final String DEFAULT_PREFERENCES_NAME = "n3ds_virtual_control_profiles_v1";
    private static final String GLOBAL_KEY = "global";
    private static final String GAME_PREFIX = "game.";
    private static final Pattern PERSISTENT_ID_PATTERN = Pattern.compile(
            "n3ds-v1:[0-9a-f]{64}:[0-9a-f]{64}(?::[0-9a-f]{64})?");

    private final SharedPreferences preferences;

    public Nintendo3DsVirtualControlProfileStore(Context context) {
        this(context, DEFAULT_PREFERENCES_NAME);
    }

    Nintendo3DsVirtualControlProfileStore(Context context, String preferencesName) {
        preferences = context.getApplicationContext().getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE);
    }

    @Nullable
    public Nintendo3DsVirtualControlProfile loadGlobal() {
        return load(GLOBAL_KEY);
    }

    public boolean saveGlobal(Nintendo3DsVirtualControlProfile profile) {
        return save(GLOBAL_KEY, profile);
    }

    public boolean resetGlobal() {
        return preferences.edit().remove(GLOBAL_KEY).commit();
    }

    @Nullable
    public Nintendo3DsVirtualControlProfile loadForGame(String persistentId) {
        return load(gameKey(persistentId));
    }

    public boolean saveForGame(
            String persistentId,
            Nintendo3DsVirtualControlProfile profile) {
        return save(gameKey(persistentId), profile);
    }

    public boolean removeForGame(String persistentId) {
        return preferences.edit().remove(gameKey(persistentId)).commit();
    }

    public Nintendo3DsVirtualControlProfile resolve(String persistentId) {
        Nintendo3DsVirtualControlProfile game = loadForGame(persistentId);
        if (game != null) {
            return game;
        }
        Nintendo3DsVirtualControlProfile global = loadGlobal();
        return global == null ? Nintendo3DsVirtualControlProfile.identity() : global;
    }

    @Nullable
    private Nintendo3DsVirtualControlProfile load(String key) {
        String encoded;
        try {
            encoded = preferences.getString(key, null);
        } catch (ClassCastException invalidType) {
            return null;
        }
        if (encoded == null || encoded.length() > Nintendo3DsVirtualControlProfileCodec
                .MAXIMUM_DOCUMENT_BYTES * 2) {
            return null;
        }
        try {
            Nintendo3DsVirtualControlProfileCodec.Result decoded =
                    Nintendo3DsVirtualControlProfileCodec.decode(
                            Base64.decode(encoded, Base64.NO_WRAP));
            return decoded.getStatus() == Nintendo3DsVirtualControlProfileCodec.Status.VALID
                    ? decoded.getProfile()
                    : null;
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        }
    }

    private boolean save(String key, Nintendo3DsVirtualControlProfile profile) {
        if (profile == null || containsUnsupportedVersion(key)) {
            return false;
        }
        try {
            String encoded = Base64.encodeToString(
                    Nintendo3DsVirtualControlProfileCodec.encode(profile),
                    Base64.NO_WRAP);
            if (!preferences.edit().putString(key, encoded).commit()) {
                return false;
            }
            return profile.equals(load(key));
        } catch (IOException | IllegalArgumentException failure) {
            return false;
        }
    }

    private boolean containsUnsupportedVersion(String key) {
        try {
            String encoded = preferences.getString(key, null);
            if (encoded == null) {
                return false;
            }
            if (encoded.length() > Nintendo3DsVirtualControlProfileCodec
                    .MAXIMUM_DOCUMENT_BYTES * 2) {
                return false;
            }
            Nintendo3DsVirtualControlProfileCodec.Result decoded =
                    Nintendo3DsVirtualControlProfileCodec.decode(
                            Base64.decode(encoded, Base64.NO_WRAP));
            return decoded.getStatus()
                    == Nintendo3DsVirtualControlProfileCodec.Status.UNSUPPORTED_VERSION;
        } catch (ClassCastException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String gameKey(String persistentId) {
        if (persistentId == null) {
            throw new IllegalArgumentException("Persistent 3DS id is required");
        }
        String normalized = persistentId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!PERSISTENT_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid persistent 3DS id");
        }
        return GAME_PREFIX + normalized;
    }
}
