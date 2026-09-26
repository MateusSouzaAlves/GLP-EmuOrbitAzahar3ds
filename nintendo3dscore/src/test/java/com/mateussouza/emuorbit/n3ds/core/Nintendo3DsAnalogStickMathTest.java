// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class Nintendo3DsAnalogStickMathTest {
    @Test
    public void centersAndAppliesDeadZone() {
        assertAxes(0.0f, 0.0f, Nintendo3DsAnalogStickMath.resolve(50, 50, 100, 100));
        assertAxes(0.0f, 0.0f, Nintendo3DsAnalogStickMath.resolve(52, 50, 100, 100));
    }

    @Test
    public void preservesContinuousAxesInsideTravel() {
        Nintendo3DsAnalogStickMath.Axes axes =
                Nintendo3DsAnalogStickMath.resolve(68, 41, 100, 100);
        assertAxes(0.5f, -0.25f, axes);
    }

    @Test
    public void clampsDiagonalAndOutsideTouchesToUnitCircle() {
        Nintendo3DsAnalogStickMath.Axes diagonal =
                Nintendo3DsAnalogStickMath.resolve(100, 100, 100, 100);
        assertEquals(1.0f, (float) Math.hypot(diagonal.horizontal, diagonal.vertical), 0.001f);
        assertEquals(diagonal.horizontal, diagonal.vertical, 0.001f);

        assertAxes(1.0f, 0.0f, Nintendo3DsAnalogStickMath.resolve(500, 50, 100, 100));
    }

    @Test
    public void rejectsInvalidGeometry() {
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsAnalogStickMath.resolve(Float.NaN, 0, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsAnalogStickMath.resolve(0, 0, 0, 100));
    }

    private static void assertAxes(
            float horizontal,
            float vertical,
            Nintendo3DsAnalogStickMath.Axes actual) {
        assertEquals(horizontal, actual.horizontal, 0.001f);
        assertEquals(vertical, actual.vertical, 0.001f);
    }
}
