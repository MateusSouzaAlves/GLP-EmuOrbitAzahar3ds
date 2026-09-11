// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.EnumSet;
import java.util.Objects;

/** Android gamepad fallback that preserves the distinct controls available on a 3DS. */
public final class Nintendo3DsPhysicalControllerRouter {
    public static final float DEFAULT_DEADZONE = 0.20f;
    public static final float DEFAULT_DIGITAL_AXIS_THRESHOLD = 0.50f;

    interface InputSink {
        void setButtonPressed(Nintendo3DsButton button, boolean pressed);
        void setCirclePad(float horizontal, float vertical);
        void setCStick(float horizontal, float vertical);
    }

    private static final Nintendo3DsButton[] AXIS_BUTTONS = {
            Nintendo3DsButton.UP,
            Nintendo3DsButton.DOWN,
            Nintendo3DsButton.LEFT,
            Nintendo3DsButton.RIGHT,
            Nintendo3DsButton.ZL,
            Nintendo3DsButton.ZR,
    };

    private final InputSink sink;
    private final float deadzone;
    private final float digitalAxisThreshold;
    private final EnumSet<Nintendo3DsButton> keyButtons =
            EnumSet.noneOf(Nintendo3DsButton.class);
    private final EnumSet<Nintendo3DsButton> axisButtons =
            EnumSet.noneOf(Nintendo3DsButton.class);
    private float circlePadX;
    private float circlePadY;
    private float cStickX;
    private float cStickY;

    public Nintendo3DsPhysicalControllerRouter(Nintendo3DsCoreLifecycleController controller) {
        this(new LifecycleInputSink(Objects.requireNonNull(controller)), DEFAULT_DEADZONE);
    }

    Nintendo3DsPhysicalControllerRouter(InputSink sink, float deadzone) {
        this.sink = Objects.requireNonNull(sink);
        if (!Float.isFinite(deadzone) || deadzone < 0.0f || deadzone >= 1.0f) {
            throw new IllegalArgumentException("A zona morta do controle 3DS deve estar entre 0 e 1.");
        }
        this.deadzone = deadzone;
        digitalAxisThreshold = DEFAULT_DIGITAL_AXIS_THRESHOLD;
    }

    public synchronized boolean handleKey(KeyEvent event) {
        Objects.requireNonNull(event);
        if (!isControllerSource(event.getSource())
                || (event.getAction() != KeyEvent.ACTION_DOWN
                && event.getAction() != KeyEvent.ACTION_UP)) {
            return false;
        }
        return applyKeyCode(event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN);
    }

    public synchronized boolean handleMotion(MotionEvent event) {
        Objects.requireNonNull(event);
        if (!isControllerSource(event.getSource())
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        float rightX = preferredAxis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX);
        float rightY = preferredAxis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY);
        float leftTrigger = Math.max(
                finiteOrZero(event.getAxisValue(MotionEvent.AXIS_LTRIGGER)),
                finiteOrZero(event.getAxisValue(MotionEvent.AXIS_BRAKE)));
        float rightTrigger = Math.max(
                finiteOrZero(event.getAxisValue(MotionEvent.AXIS_RTRIGGER)),
                finiteOrZero(event.getAxisValue(MotionEvent.AXIS_GAS)));
        applyAxes(
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                rightX,
                rightY,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                leftTrigger,
                rightTrigger);
        return true;
    }

    synchronized boolean applyKeyCode(int keyCode, boolean pressed) {
        Nintendo3DsButton button = buttonForKeyCode(keyCode);
        if (button == null) {
            return false;
        }
        boolean wasPressed = isPressed(button);
        if (pressed) {
            keyButtons.add(button);
        } else {
            keyButtons.remove(button);
        }
        dispatchButtonTransition(button, wasPressed);
        return true;
    }

    synchronized void applyAxes(
            float leftX,
            float leftY,
            float rightX,
            float rightY,
            float hatX,
            float hatY,
            float leftTrigger,
            float rightTrigger) {
        float nextCirclePadX = normalizeStickAxis(leftX, deadzone);
        float nextCirclePadY = normalizeStickAxis(leftY, deadzone);
        float nextCStickX = normalizeStickAxis(rightX, deadzone);
        float nextCStickY = normalizeStickAxis(rightY, deadzone);
        if (Float.compare(circlePadX, nextCirclePadX) != 0
                || Float.compare(circlePadY, nextCirclePadY) != 0) {
            circlePadX = nextCirclePadX;
            circlePadY = nextCirclePadY;
            sink.setCirclePad(circlePadX, circlePadY);
        }
        if (Float.compare(cStickX, nextCStickX) != 0
                || Float.compare(cStickY, nextCStickY) != 0) {
            cStickX = nextCStickX;
            cStickY = nextCStickY;
            sink.setCStick(cStickX, cStickY);
        }

        EnumSet<Nintendo3DsButton> nextAxisButtons =
                EnumSet.noneOf(Nintendo3DsButton.class);
        addDirectionalAxes(nextAxisButtons, finiteOrZero(hatX), finiteOrZero(hatY));
        if (finiteOrZero(leftTrigger) >= digitalAxisThreshold) {
            nextAxisButtons.add(Nintendo3DsButton.ZL);
        }
        if (finiteOrZero(rightTrigger) >= digitalAxisThreshold) {
            nextAxisButtons.add(Nintendo3DsButton.ZR);
        }
        for (Nintendo3DsButton button : AXIS_BUTTONS) {
            boolean wasPressed = isPressed(button);
            if (nextAxisButtons.contains(button)) {
                axisButtons.add(button);
            } else {
                axisButtons.remove(button);
            }
            dispatchButtonTransition(button, wasPressed);
        }
    }

    public synchronized void reset() {
        EnumSet<Nintendo3DsButton> pressed = EnumSet.copyOf(keyButtons);
        pressed.addAll(axisButtons);
        keyButtons.clear();
        axisButtons.clear();
        for (Nintendo3DsButton button : pressed) {
            sink.setButtonPressed(button, false);
        }
        circlePadX = 0.0f;
        circlePadY = 0.0f;
        cStickX = 0.0f;
        cStickY = 0.0f;
        sink.setCirclePad(0.0f, 0.0f);
        sink.setCStick(0.0f, 0.0f);
    }

    static float normalizeStickAxis(float value, float deadzone) {
        float finite = finiteOrZero(value);
        float clamped = Math.max(-1.0f, Math.min(1.0f, finite));
        float magnitude = Math.abs(clamped);
        if (magnitude <= deadzone) {
            return 0.0f;
        }
        float normalized = (magnitude - deadzone) / (1.0f - deadzone);
        return Math.copySign(normalized, clamped);
    }

    private void addDirectionalAxes(
            EnumSet<Nintendo3DsButton> buttons,
            float horizontal,
            float vertical) {
        if (horizontal <= -digitalAxisThreshold) {
            buttons.add(Nintendo3DsButton.LEFT);
        } else if (horizontal >= digitalAxisThreshold) {
            buttons.add(Nintendo3DsButton.RIGHT);
        }
        if (vertical <= -digitalAxisThreshold) {
            buttons.add(Nintendo3DsButton.UP);
        } else if (vertical >= digitalAxisThreshold) {
            buttons.add(Nintendo3DsButton.DOWN);
        }
    }

    private boolean isPressed(Nintendo3DsButton button) {
        return keyButtons.contains(button) || axisButtons.contains(button);
    }

    private void dispatchButtonTransition(Nintendo3DsButton button, boolean wasPressed) {
        boolean pressed = isPressed(button);
        if (pressed != wasPressed) {
            sink.setButtonPressed(button, pressed);
        }
    }

    private static float preferredAxis(MotionEvent event, int primary, int fallback) {
        InputDevice device = event.getDevice();
        if (device != null && device.getMotionRange(primary, event.getSource()) != null) {
            return event.getAxisValue(primary);
        }
        float primaryValue = event.getAxisValue(primary);
        return primaryValue != 0.0f ? primaryValue : event.getAxisValue(fallback);
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static boolean isControllerSource(int source) {
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static Nintendo3DsButton buttonForKeyCode(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_UP:
            return Nintendo3DsButton.UP;
        case KeyEvent.KEYCODE_DPAD_DOWN:
            return Nintendo3DsButton.DOWN;
        case KeyEvent.KEYCODE_DPAD_LEFT:
            return Nintendo3DsButton.LEFT;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            return Nintendo3DsButton.RIGHT;
        case KeyEvent.KEYCODE_BUTTON_A:
            return Nintendo3DsButton.A;
        case KeyEvent.KEYCODE_BUTTON_B:
            return Nintendo3DsButton.B;
        case KeyEvent.KEYCODE_BUTTON_X:
            return Nintendo3DsButton.X;
        case KeyEvent.KEYCODE_BUTTON_Y:
            return Nintendo3DsButton.Y;
        case KeyEvent.KEYCODE_BUTTON_L1:
            return Nintendo3DsButton.L;
        case KeyEvent.KEYCODE_BUTTON_R1:
            return Nintendo3DsButton.R;
        case KeyEvent.KEYCODE_BUTTON_L2:
            return Nintendo3DsButton.ZL;
        case KeyEvent.KEYCODE_BUTTON_R2:
            return Nintendo3DsButton.ZR;
        case KeyEvent.KEYCODE_BUTTON_SELECT:
            return Nintendo3DsButton.SELECT;
        case KeyEvent.KEYCODE_BUTTON_START:
            return Nintendo3DsButton.START;
        case KeyEvent.KEYCODE_BUTTON_MODE:
            return Nintendo3DsButton.HOME_SWAP_SCREENS;
        default:
            return null;
        }
    }

    private static final class LifecycleInputSink implements InputSink {
        private final Nintendo3DsCoreLifecycleController controller;

        LifecycleInputSink(Nintendo3DsCoreLifecycleController controller) {
            this.controller = controller;
        }

        @Override
        public void setButtonPressed(Nintendo3DsButton button, boolean pressed) {
            controller.setButtonPressed(button, pressed);
        }

        @Override
        public void setCirclePad(float horizontal, float vertical) {
            controller.setCirclePad(horizontal, vertical);
        }

        @Override
        public void setCStick(float horizontal, float vertical) {
            controller.setCStick(horizontal, vertical);
        }
    }
}
