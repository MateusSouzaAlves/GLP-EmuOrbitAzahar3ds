// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Objects;

/** Immutable proof that a frame reached a real Android Vulkan swapchain. */
public final class Nintendo3DsHardwareRenderReport {
    private final String deviceName;
    private final int apiVersionMajor;
    private final int apiVersionMinor;
    private final int apiVersionPatch;
    private final int surfaceFormat;
    private final int swapchainImageCount;
    private final long presentedFrames;

    Nintendo3DsHardwareRenderReport(String[] values) {
        if (values == null || values.length != 7) {
            throw new IllegalArgumentException("Invalid Nintendo 3DS Vulkan report");
        }
        deviceName = requireText(values[0], "device name");
        apiVersionMajor = parseNonNegativeInt(values[1], "API major version");
        apiVersionMinor = parseNonNegativeInt(values[2], "API minor version");
        apiVersionPatch = parseNonNegativeInt(values[3], "API patch version");
        surfaceFormat = parseNonNegativeInt(values[4], "surface format");
        swapchainImageCount = parsePositiveInt(values[5], "swapchain image count");
        presentedFrames = parsePositiveLong(values[6], "presented-frame count");
    }

    public String getDeviceName() {
        return deviceName;
    }

    public int getApiVersionMajor() {
        return apiVersionMajor;
    }

    public int getApiVersionMinor() {
        return apiVersionMinor;
    }

    public int getApiVersionPatch() {
        return apiVersionPatch;
    }

    public int getSurfaceFormat() {
        return surfaceFormat;
    }

    public int getSwapchainImageCount() {
        return swapchainImageCount;
    }

    public long getPresentedFrames() {
        return presentedFrames;
    }

    private static int parseNonNegativeInt(String value, String label) {
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

    private static int parsePositiveInt(String value, String label) {
        int parsed = parseNonNegativeInt(value, label);
        if (parsed > 0) {
            return parsed;
        }
        throw new IllegalArgumentException("Invalid Nintendo 3DS " + label);
    }

    private static long parsePositiveLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Converted to a stable contract error below.
        }
        throw new IllegalArgumentException("Invalid Nintendo 3DS " + label);
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value).trim();
        if (!normalized.isEmpty()) {
            return normalized;
        }
        throw new IllegalArgumentException("Nintendo 3DS " + label + " is empty");
    }
}
