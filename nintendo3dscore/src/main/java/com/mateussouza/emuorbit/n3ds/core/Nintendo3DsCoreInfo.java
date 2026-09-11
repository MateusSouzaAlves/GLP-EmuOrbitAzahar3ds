// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable result of the pre-game libretro bootstrap handshake. */
public final class Nintendo3DsCoreInfo {
    private final String libraryName;
    private final String libraryVersion;
    private final Set<String> validExtensions;
    private final boolean needsFullPath;
    private final int apiVersion;
    private final int environmentCallbackCount;
    private final boolean coreOptionsRegistered;
    private final boolean controllerInfoRegistered;
    private final boolean vfsInterfaceRequested;

    Nintendo3DsCoreInfo(String[] values) {
        if (values == null || values.length != 9) {
            throw new IllegalArgumentException("Invalid Nintendo 3DS bootstrap result");
        }
        libraryName = requireText(values[0], "library name");
        libraryVersion = requireText(values[1], "library version");
        validExtensions = parseExtensions(values[2]);
        needsFullPath = parseFlag(values[3]);
        apiVersion = parseNonNegative(values[4], "API version");
        environmentCallbackCount = parseNonNegative(values[5], "callback count");
        coreOptionsRegistered = parseFlag(values[6]);
        controllerInfoRegistered = parseFlag(values[7]);
        vfsInterfaceRequested = parseFlag(values[8]);
    }

    public String getLibraryName() {
        return libraryName;
    }

    public String getLibraryVersion() {
        return libraryVersion;
    }

    public Set<String> getValidExtensions() {
        return validExtensions;
    }

    public boolean needsFullPath() {
        return needsFullPath;
    }

    public int getApiVersion() {
        return apiVersion;
    }

    public int getEnvironmentCallbackCount() {
        return environmentCallbackCount;
    }

    public boolean isCoreOptionsRegistered() {
        return coreOptionsRegistered;
    }

    public boolean isControllerInfoRegistered() {
        return controllerInfoRegistered;
    }

    public boolean isVfsInterfaceRequested() {
        return vfsInterfaceRequested;
    }

    private static Set<String> parseExtensions(String raw) {
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        for (String extension : requireText(raw, "extensions").split("\\|")) {
            String normalized = extension.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                extensions.add(normalized);
            }
        }
        if (extensions.isEmpty()) {
            throw new IllegalArgumentException("Nintendo 3DS extensions are empty");
        }
        return Collections.unmodifiableSet(extensions);
    }

    private static boolean parseFlag(String value) {
        if ("1".equals(value)) {
            return true;
        }
        if ("0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("Invalid Nintendo 3DS bootstrap flag");
    }

    private static int parseNonNegative(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Converted to a stable contract error below.
        }
        throw new IllegalArgumentException("Invalid Nintendo 3DS " + label);
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Nintendo 3DS " + label + " is empty");
        }
        return normalized;
    }
}
