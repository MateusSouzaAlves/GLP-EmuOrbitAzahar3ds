// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Objects;

/** Resolution-independent position, size and visibility of one 3DS control group. */
public final class Nintendo3DsVirtualControlTransform {
    public static final float MINIMUM_NORMALIZED_OFFSET = -1.0f;
    public static final float MAXIMUM_NORMALIZED_OFFSET = 1.0f;
    public static final float MINIMUM_SCALE = 0.6f;
    public static final float MAXIMUM_SCALE = 1.6f;
    private static final Nintendo3DsVirtualControlTransform IDENTITY =
            new Nintendo3DsVirtualControlTransform(0.0f, 0.0f, 1.0f, true);

    private final float normalizedOffsetX;
    private final float normalizedOffsetY;
    private final float scale;
    private final boolean visible;

    public Nintendo3DsVirtualControlTransform(
            float normalizedOffsetX,
            float normalizedOffsetY,
            float scale,
            boolean visible) {
        this.normalizedOffsetX = requireInRange(
                normalizedOffsetX,
                MINIMUM_NORMALIZED_OFFSET,
                MAXIMUM_NORMALIZED_OFFSET,
                "Horizontal offset");
        this.normalizedOffsetY = requireInRange(
                normalizedOffsetY,
                MINIMUM_NORMALIZED_OFFSET,
                MAXIMUM_NORMALIZED_OFFSET,
                "Vertical offset");
        this.scale = requireInRange(scale, MINIMUM_SCALE, MAXIMUM_SCALE, "Scale");
        this.visible = visible;
    }

    public static Nintendo3DsVirtualControlTransform identity() {
        return IDENTITY;
    }

    public float getNormalizedOffsetX() {
        return normalizedOffsetX;
    }

    public float getNormalizedOffsetY() {
        return normalizedOffsetY;
    }

    public float getScale() {
        return scale;
    }

    public boolean isVisible() {
        return visible;
    }

    public Nintendo3DsVirtualControlTransform withVisible(boolean nextVisible) {
        return new Nintendo3DsVirtualControlTransform(
                normalizedOffsetX,
                normalizedOffsetY,
                scale,
                nextVisible);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Nintendo3DsVirtualControlTransform)) {
            return false;
        }
        Nintendo3DsVirtualControlTransform transform =
                (Nintendo3DsVirtualControlTransform) other;
        return Float.compare(normalizedOffsetX, transform.normalizedOffsetX) == 0
                && Float.compare(normalizedOffsetY, transform.normalizedOffsetY) == 0
                && Float.compare(scale, transform.scale) == 0
                && visible == transform.visible;
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizedOffsetX, normalizedOffsetY, scale, visible);
    }

    private static float requireInRange(
            float value,
            float minimum,
            float maximum,
            String label) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    label + " must be between " + minimum + " and " + maximum);
        }
        return value == -0.0f ? 0.0f : value;
    }
}
