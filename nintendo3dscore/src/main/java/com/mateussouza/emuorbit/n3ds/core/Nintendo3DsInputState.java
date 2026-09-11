// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/* Thread-safe frontend state sampled once before every owner-thread core frame. */
final class Nintendo3DsInputState {
    private int buttonMask;
    private short circlePadX;
    private short circlePadY;
    private short cStickX;
    private short cStickY;
    private short pointerX;
    private short pointerY;
    private boolean pointerPressed;
    private float accelerometerX;
    private float accelerometerY;
    private float accelerometerZ = -1.0f;
    private float gyroscopeX;
    private float gyroscopeY;
    private float gyroscopeZ;

    synchronized void setButtonPressed(Nintendo3DsButton button, boolean pressed) {
        if (pressed) {
            buttonMask |= button.getMask();
        } else {
            buttonMask &= ~button.getMask();
        }
    }

    synchronized void setCirclePad(float horizontal, float vertical) {
        short normalizedHorizontal = normalizeAxis(horizontal);
        short normalizedVertical = normalizeAxis(vertical);
        circlePadX = normalizedHorizontal;
        circlePadY = normalizedVertical;
    }

    synchronized void setCStick(float horizontal, float vertical) {
        short normalizedHorizontal = normalizeAxis(horizontal);
        short normalizedVertical = normalizeAxis(vertical);
        cStickX = normalizedHorizontal;
        cStickY = normalizedVertical;
    }

    synchronized void setTouchPosition(
            int frameX,
            int frameY,
            int frameWidth,
            int frameHeight,
            boolean pressed) {
        short normalizedX = normalizePointerCoordinate(frameX, frameWidth);
        short normalizedY = normalizePointerCoordinate(frameY, frameHeight);
        pointerX = normalizedX;
        pointerY = normalizedY;
        pointerPressed = pressed;
    }

    synchronized void setMotion(
            float nextAccelerometerX,
            float nextAccelerometerY,
            float nextAccelerometerZ,
            float nextGyroscopeX,
            float nextGyroscopeY,
            float nextGyroscopeZ) {
        requireFiniteMotion(
                nextAccelerometerX,
                nextAccelerometerY,
                nextAccelerometerZ,
                nextGyroscopeX,
                nextGyroscopeY,
                nextGyroscopeZ);
        accelerometerX = nextAccelerometerX;
        accelerometerY = nextAccelerometerY;
        accelerometerZ = nextAccelerometerZ;
        gyroscopeX = nextGyroscopeX;
        gyroscopeY = nextGyroscopeY;
        gyroscopeZ = nextGyroscopeZ;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                buttonMask,
                circlePadX,
                circlePadY,
                cStickX,
                cStickY,
                pointerX,
                pointerY,
                pointerPressed,
                accelerometerX,
                accelerometerY,
                accelerometerZ,
                gyroscopeX,
                gyroscopeY,
                gyroscopeZ);
    }

    synchronized void clear() {
        buttonMask = 0;
        circlePadX = 0;
        circlePadY = 0;
        cStickX = 0;
        cStickY = 0;
        pointerX = 0;
        pointerY = 0;
        pointerPressed = false;
        accelerometerX = 0.0f;
        accelerometerY = 0.0f;
        accelerometerZ = -1.0f;
        gyroscopeX = 0.0f;
        gyroscopeY = 0.0f;
        gyroscopeZ = 0.0f;
    }

    static short normalizeAxis(float value) {
        if (!Float.isFinite(value) || value < -1.0f || value > 1.0f) {
            throw new IllegalArgumentException("O eixo analógico 3DS deve estar entre -1 e 1.");
        }
        int magnitude = Math.round(Math.abs(value) * Short.MAX_VALUE);
        return (short) (value < 0.0f ? -magnitude : magnitude);
    }

    static short normalizePointerCoordinate(int coordinate, int extent) {
        if (extent <= 0) {
            throw new IllegalArgumentException("A dimensão do frame 3DS deve ser positiva.");
        }
        int clamped = Math.max(0, Math.min(coordinate, extent - 1));
        if (extent == 1) {
            return 0;
        }
        long scaled = ((long) clamped * 65534L + (extent - 1L) / 2L) / (extent - 1L);
        return (short) (-Short.MAX_VALUE + scaled);
    }

    private static void requireFiniteMotion(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Os sensores 3DS exigem valores finitos.");
            }
        }
    }

    static final class Snapshot {
        private final int buttonMask;
        private final short circlePadX;
        private final short circlePadY;
        private final short cStickX;
        private final short cStickY;
        private final short pointerX;
        private final short pointerY;
        private final boolean pointerPressed;
        private final float accelerometerX;
        private final float accelerometerY;
        private final float accelerometerZ;
        private final float gyroscopeX;
        private final float gyroscopeY;
        private final float gyroscopeZ;

        Snapshot(
                int buttonMask,
                short circlePadX,
                short circlePadY,
                short cStickX,
                short cStickY,
                short pointerX,
                short pointerY,
                boolean pointerPressed,
                float accelerometerX,
                float accelerometerY,
                float accelerometerZ,
                float gyroscopeX,
                float gyroscopeY,
                float gyroscopeZ) {
            this.buttonMask = buttonMask;
            this.circlePadX = circlePadX;
            this.circlePadY = circlePadY;
            this.cStickX = cStickX;
            this.cStickY = cStickY;
            this.pointerX = pointerX;
            this.pointerY = pointerY;
            this.pointerPressed = pointerPressed;
            this.accelerometerX = accelerometerX;
            this.accelerometerY = accelerometerY;
            this.accelerometerZ = accelerometerZ;
            this.gyroscopeX = gyroscopeX;
            this.gyroscopeY = gyroscopeY;
            this.gyroscopeZ = gyroscopeZ;
        }

        int getButtonMask() { return buttonMask; }
        short getCirclePadX() { return circlePadX; }
        short getCirclePadY() { return circlePadY; }
        short getCStickX() { return cStickX; }
        short getCStickY() { return cStickY; }
        short getPointerX() { return pointerX; }
        short getPointerY() { return pointerY; }
        boolean isPointerPressed() { return pointerPressed; }
        float getAccelerometerX() { return accelerometerX; }
        float getAccelerometerY() { return accelerometerY; }
        float getAccelerometerZ() { return accelerometerZ; }
        float getGyroscopeX() { return gyroscopeX; }
        float getGyroscopeY() { return gyroscopeY; }
        float getGyroscopeZ() { return gyroscopeZ; }
    }
}
