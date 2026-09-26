// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Objects;

/** Converts normalized editor values to a transform constrained to the overlay safe area. */
final class Nintendo3DsVirtualControlGeometry {
    private Nintendo3DsVirtualControlGeometry() {
    }

    static AppliedTransform calculate(
            int parentWidth,
            int parentHeight,
            int paddingLeft,
            int paddingTop,
            int paddingRight,
            int paddingBottom,
            int childLeft,
            int childTop,
            int childWidth,
            int childHeight,
            Nintendo3DsVirtualControlTransform requested) {
        Objects.requireNonNull(requested);
        int contentWidth = parentWidth - paddingLeft - paddingRight;
        int contentHeight = parentHeight - paddingTop - paddingBottom;
        if (contentWidth <= 0 || contentHeight <= 0 || childWidth <= 0 || childHeight <= 0) {
            throw new IllegalArgumentException("Parent and child bounds must be positive");
        }

        float scale = Math.min(
                requested.getScale(),
                Math.min(
                        contentWidth / (float) childWidth,
                        contentHeight / (float) childHeight));
        float halfWidth = childWidth * scale / 2.0f;
        float halfHeight = childHeight * scale / 2.0f;
        float minimumCenterX = paddingLeft + halfWidth;
        float maximumCenterX = parentWidth - paddingRight - halfWidth;
        float minimumCenterY = paddingTop + halfHeight;
        float maximumCenterY = parentHeight - paddingBottom - halfHeight;
        float baseCenterX = childLeft + childWidth / 2.0f;
        float baseCenterY = childTop + childHeight / 2.0f;
        float safeBaseCenterX = clamp(baseCenterX, minimumCenterX, maximumCenterX);
        float safeBaseCenterY = clamp(baseCenterY, minimumCenterY, maximumCenterY);
        float requestedCenterX = interpolateFromAnchor(
                safeBaseCenterX,
                minimumCenterX,
                maximumCenterX,
                requested.getNormalizedOffsetX());
        float requestedCenterY = interpolateFromAnchor(
                safeBaseCenterY,
                minimumCenterY,
                maximumCenterY,
                requested.getNormalizedOffsetY());
        return new AppliedTransform(
                requestedCenterX - baseCenterX,
                requestedCenterY - baseCenterY,
                scale);
    }

    private static float interpolateFromAnchor(
            float anchor,
            float minimum,
            float maximum,
            float normalizedOffset) {
        if (normalizedOffset >= 0.0f) {
            return anchor + normalizedOffset * (maximum - anchor);
        }
        return anchor + -normalizedOffset * (minimum - anchor);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    static final class AppliedTransform {
        private final float translationX;
        private final float translationY;
        private final float scale;

        private AppliedTransform(float translationX, float translationY, float scale) {
            this.translationX = translationX;
            this.translationY = translationY;
            this.scale = scale;
        }

        float getTranslationX() {
            return translationX;
        }

        float getTranslationY() {
            return translationY;
        }

        float getScale() {
            return scale;
        }
    }
}
