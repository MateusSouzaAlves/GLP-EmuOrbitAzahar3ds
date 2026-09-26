// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import android.hardware.SensorManager;
import android.view.Surface;

import org.junit.Test;

public final class Nintendo3DsAndroidMotionSourceTest {
    @Test
    public void mapsEveryAndroidDisplayRotationLikeAzaharNdkMotion() {
        assertArrayEquals(
                new float[]{-1.0f, 3.0f, 2.0f},
                Nintendo3DsAndroidMotionSource.mapAndroidAxes(
                        1.0f, 2.0f, 3.0f, Surface.ROTATION_0, false),
                0.0f);
        assertArrayEquals(
                new float[]{2.0f, 3.0f, 1.0f},
                Nintendo3DsAndroidMotionSource.mapAndroidAxes(
                        1.0f, 2.0f, 3.0f, Surface.ROTATION_90, false),
                0.0f);
        assertArrayEquals(
                new float[]{1.0f, 3.0f, -2.0f},
                Nintendo3DsAndroidMotionSource.mapAndroidAxes(
                        1.0f, 2.0f, 3.0f, Surface.ROTATION_180, false),
                0.0f);
        assertArrayEquals(
                new float[]{-2.0f, 3.0f, -1.0f},
                Nintendo3DsAndroidMotionSource.mapAndroidAxes(
                        1.0f, 2.0f, 3.0f, Surface.ROTATION_270, false),
                0.0f);
    }

    @Test
    public void convertsAndroidAccelerationToAzaharGravityUnits() {
        float gravity = SensorManager.STANDARD_GRAVITY;
        assertArrayEquals(
                new float[]{1.0f / gravity, -3.0f / gravity, -2.0f / gravity},
                Nintendo3DsAndroidMotionSource.mapAndroidAxes(
                        1.0f, 2.0f, 3.0f, Surface.ROTATION_0, true),
                0.000001f);
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsAndroidMotionSource.mapAndroidAxes(
                        0.0f, 0.0f, 0.0f, 99, false));
    }
}
