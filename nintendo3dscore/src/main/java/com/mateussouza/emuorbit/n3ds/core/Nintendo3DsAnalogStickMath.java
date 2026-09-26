// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Pure geometry used by the Nintendo 3DS virtual circle pad and C-stick. */
final class Nintendo3DsAnalogStickMath {
    private static final float DEAD_ZONE = 0.08f;

    private Nintendo3DsAnalogStickMath() {
    }

    static Axes resolve(float touchX, float touchY, int width, int height) {
        if (!Float.isFinite(touchX) || !Float.isFinite(touchY) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Virtual-stick geometry must be finite and positive.");
        }
        float centerX = width / 2.0f;
        float centerY = height / 2.0f;
        float radius = Math.max(1.0f, Math.min(width, height) * 0.36f);
        float horizontal = (touchX - centerX) / radius;
        float vertical = (touchY - centerY) / radius;
        float magnitude = (float) Math.hypot(horizontal, vertical);
        if (magnitude <= DEAD_ZONE) {
            return Axes.NEUTRAL;
        }
        if (magnitude > 1.0f) {
            horizontal /= magnitude;
            vertical /= magnitude;
        }
        return new Axes(horizontal, vertical);
    }

    static final class Axes {
        static final Axes NEUTRAL = new Axes(0.0f, 0.0f);

        final float horizontal;
        final float vertical;

        Axes(float horizontal, float vertical) {
            this.horizontal = horizontal;
            this.vertical = vertical;
        }
    }
}
