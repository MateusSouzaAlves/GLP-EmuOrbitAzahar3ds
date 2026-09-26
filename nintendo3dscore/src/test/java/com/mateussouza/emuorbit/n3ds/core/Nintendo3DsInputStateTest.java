// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Nintendo3DsInputStateTest {
    @Test
    public void combinesAndReleasesPinnedAzaharDigitalBits() {
        Nintendo3DsInputState state = new Nintendo3DsInputState();

        state.setButtonPressed(Nintendo3DsButton.B, true);
        state.setButtonPressed(Nintendo3DsButton.A, true);
        state.setButtonPressed(Nintendo3DsButton.ZL, true);
        assertEquals(0x1101, state.snapshot().getButtonMask());

        state.setButtonPressed(Nintendo3DsButton.A, false);
        assertEquals(0x1001, state.snapshot().getButtonMask());
        state.clear();
        assertEquals(0, state.snapshot().getButtonMask());
    }

    @Test
    public void normalizesCirclePadSymmetricallyAndClearsIt() {
        Nintendo3DsInputState state = new Nintendo3DsInputState();

        state.setCirclePad(0.5f, -0.25f);
        Nintendo3DsInputState.Snapshot active = state.snapshot();
        assertEquals(16384, active.getCirclePadX());
        assertEquals(-8192, active.getCirclePadY());

        state.clear();
        assertEquals(0, state.snapshot().getCirclePadX());
        assertEquals(0, state.snapshot().getCirclePadY());
    }

    @Test
    public void rejectsInvalidCirclePadValuesWithoutPartialUpdate() {
        Nintendo3DsInputState state = new Nintendo3DsInputState();
        state.setCirclePad(0.25f, -0.5f);

        assertThrows(IllegalArgumentException.class, () -> state.setCirclePad(1.01f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> state.setCirclePad(0.0f, Float.NaN));
        Nintendo3DsInputState.Snapshot unchanged = state.snapshot();
        assertEquals(8192, unchanged.getCirclePadX());
        assertEquals(-16384, unchanged.getCirclePadY());
    }

    @Test
    public void normalizesCStickAndCompositeFrameTouch() {
        Nintendo3DsInputState state = new Nintendo3DsInputState();

        state.setCStick(-0.75f, 0.25f);
        state.setTouchPosition(200, 360, 400, 480, true);
        Nintendo3DsInputState.Snapshot active = state.snapshot();
        assertEquals(-24575, active.getCStickX());
        assertEquals(8192, active.getCStickY());
        assertEquals(82, active.getPointerX());
        assertEquals(16486, active.getPointerY());
        assertTrue(active.isPointerPressed());

        state.setTouchPosition(-10, 600, 400, 480, false);
        Nintendo3DsInputState.Snapshot clamped = state.snapshot();
        assertEquals(-32767, clamped.getPointerX());
        assertEquals(32767, clamped.getPointerY());
        assertFalse(clamped.isPointerPressed());
    }

    @Test
    public void rejectsInvalidSecondaryAxesAndTouchDimensionsWithoutPartialUpdate() {
        Nintendo3DsInputState state = new Nintendo3DsInputState();
        state.setCStick(0.5f, -0.5f);
        state.setTouchPosition(40, 240, 400, 480, true);

        assertThrows(IllegalArgumentException.class, () -> state.setCStick(-1.1f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> state.setTouchPosition(0, 0, 0, 480, false));
        Nintendo3DsInputState.Snapshot unchanged = state.snapshot();
        assertEquals(16384, unchanged.getCStickX());
        assertEquals(-16384, unchanged.getCStickY());
        assertTrue(unchanged.isPointerPressed());
    }

    @Test
    public void publishesFiniteMotionAndRestoresStationaryNeutralState() {
        Nintendo3DsInputState state = new Nintendo3DsInputState();
        state.setMotion(0.25f, -0.5f, -0.75f, 1.5f, -2.5f, 3.5f);

        Nintendo3DsInputState.Snapshot active = state.snapshot();
        assertEquals(0.25f, active.getAccelerometerX(), 0.0f);
        assertEquals(-0.5f, active.getAccelerometerY(), 0.0f);
        assertEquals(-0.75f, active.getAccelerometerZ(), 0.0f);
        assertEquals(1.5f, active.getGyroscopeX(), 0.0f);
        assertEquals(-2.5f, active.getGyroscopeY(), 0.0f);
        assertEquals(3.5f, active.getGyroscopeZ(), 0.0f);

        assertThrows(IllegalArgumentException.class,
                () -> state.setMotion(0.0f, Float.NaN, 0.0f, 0.0f, 0.0f, 0.0f));
        assertEquals(-0.5f, state.snapshot().getAccelerometerY(), 0.0f);

        state.clear();
        Nintendo3DsInputState.Snapshot neutral = state.snapshot();
        assertEquals(0.0f, neutral.getAccelerometerX(), 0.0f);
        assertEquals(0.0f, neutral.getAccelerometerY(), 0.0f);
        assertEquals(-1.0f, neutral.getAccelerometerZ(), 0.0f);
        assertEquals(0.0f, neutral.getGyroscopeX(), 0.0f);
        assertEquals(0.0f, neutral.getGyroscopeY(), 0.0f);
        assertEquals(0.0f, neutral.getGyroscopeZ(), 0.0f);
    }
}
