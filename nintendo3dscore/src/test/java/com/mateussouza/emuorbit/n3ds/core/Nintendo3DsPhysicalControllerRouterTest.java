// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.EnumMap;

public final class Nintendo3DsPhysicalControllerRouterTest {
    @Test
    public void mapsEveryDistinct3DsGamepadButton() {
        RecordingSink sink = new RecordingSink();
        Nintendo3DsPhysicalControllerRouter router =
                new Nintendo3DsPhysicalControllerRouter(sink, 0.20f);

        assertTrue(router.applyKeyCode(KeyEvent.KEYCODE_BUTTON_A, true));
        assertTrue(router.applyKeyCode(KeyEvent.KEYCODE_BUTTON_L1, true));
        assertTrue(router.applyKeyCode(KeyEvent.KEYCODE_BUTTON_R1, true));
        assertTrue(router.applyKeyCode(KeyEvent.KEYCODE_BUTTON_L2, true));
        assertTrue(router.applyKeyCode(KeyEvent.KEYCODE_BUTTON_R2, true));
        assertTrue(router.applyKeyCode(KeyEvent.KEYCODE_BUTTON_MODE, true));
        assertFalse(router.applyKeyCode(KeyEvent.KEYCODE_BUTTON_THUMBL, true));

        assertTrue(sink.isPressed(Nintendo3DsButton.A));
        assertTrue(sink.isPressed(Nintendo3DsButton.L));
        assertTrue(sink.isPressed(Nintendo3DsButton.R));
        assertTrue(sink.isPressed(Nintendo3DsButton.ZL));
        assertTrue(sink.isPressed(Nintendo3DsButton.ZR));
        assertTrue(sink.isPressed(Nintendo3DsButton.HOME_SWAP_SCREENS));
    }

    @Test
    public void remapsBothSticksAndDigitalAxesAfterDeadzone() {
        RecordingSink sink = new RecordingSink();
        Nintendo3DsPhysicalControllerRouter router =
                new Nintendo3DsPhysicalControllerRouter(sink, 0.20f);

        router.applyAxes(0.60f, -0.60f, -0.60f, 0.60f, -1.0f, 0.0f, 1.0f, 0.0f);

        assertEquals(0.50f, sink.circlePadX, 0.0001f);
        assertEquals(-0.50f, sink.circlePadY, 0.0001f);
        assertEquals(-0.50f, sink.cStickX, 0.0001f);
        assertEquals(0.50f, sink.cStickY, 0.0001f);
        assertTrue(sink.isPressed(Nintendo3DsButton.LEFT));
        assertTrue(sink.isPressed(Nintendo3DsButton.ZL));

        router.applyAxes(0.19f, -0.19f, Float.NaN, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f);
        assertEquals(0.0f, sink.circlePadX, 0.0f);
        assertEquals(0.0f, sink.circlePadY, 0.0f);
        assertEquals(0.0f, sink.cStickX, 0.0f);
        assertFalse(sink.isPressed(Nintendo3DsButton.LEFT));
        assertFalse(sink.isPressed(Nintendo3DsButton.ZL));
        assertTrue(sink.isPressed(Nintendo3DsButton.DOWN));
        assertTrue(sink.isPressed(Nintendo3DsButton.ZR));
    }

    @Test
    public void overlappingKeyAndAxisDoNotReleaseEarlyAndResetNeutralizesAll() {
        RecordingSink sink = new RecordingSink();
        Nintendo3DsPhysicalControllerRouter router =
                new Nintendo3DsPhysicalControllerRouter(sink, 0.20f);

        router.applyKeyCode(KeyEvent.KEYCODE_DPAD_UP, true);
        router.applyAxes(1.0f, 0.0f, 0.0f, -1.0f, 0.0f, -1.0f, 0.0f, 0.0f);
        router.applyKeyCode(KeyEvent.KEYCODE_DPAD_UP, false);
        assertTrue(sink.isPressed(Nintendo3DsButton.UP));

        router.reset();
        assertFalse(sink.isPressed(Nintendo3DsButton.UP));
        assertEquals(0.0f, sink.circlePadX, 0.0f);
        assertEquals(0.0f, sink.circlePadY, 0.0f);
        assertEquals(0.0f, sink.cStickX, 0.0f);
        assertEquals(0.0f, sink.cStickY, 0.0f);
    }

    @Test
    public void rejectsInvalidDeadzoneAndClampsOutOfRangeAxes() {
        RecordingSink sink = new RecordingSink();
        assertThrows(IllegalArgumentException.class,
                () -> new Nintendo3DsPhysicalControllerRouter(sink, 1.0f));
        Nintendo3DsPhysicalControllerRouter router =
                new Nintendo3DsPhysicalControllerRouter(sink, 0.0f);

        router.applyAxes(2.0f, -2.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        assertEquals(1.0f, sink.circlePadX, 0.0f);
        assertEquals(-1.0f, sink.circlePadY, 0.0f);
    }

    private static final class RecordingSink
            implements Nintendo3DsPhysicalControllerRouter.InputSink {
        private final EnumMap<Nintendo3DsButton, Boolean> buttons =
                new EnumMap<>(Nintendo3DsButton.class);
        private float circlePadX;
        private float circlePadY;
        private float cStickX;
        private float cStickY;

        @Override
        public void setButtonPressed(Nintendo3DsButton button, boolean pressed) {
            buttons.put(button, pressed);
        }

        @Override
        public void setCirclePad(float horizontal, float vertical) {
            circlePadX = horizontal;
            circlePadY = vertical;
        }

        @Override
        public void setCStick(float horizontal, float vertical) {
            cStickX = horizontal;
            cStickY = vertical;
        }

        boolean isPressed(Nintendo3DsButton button) {
            return buttons.getOrDefault(button, false);
        }
    }
}
