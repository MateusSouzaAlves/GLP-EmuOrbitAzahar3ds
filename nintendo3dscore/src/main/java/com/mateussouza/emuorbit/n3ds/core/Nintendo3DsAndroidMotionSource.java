// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.Objects;

/*
 * Android sensor owner adapted from Azahar's NDKMotion axis transform. Collection runs on a
 * dedicated looper and is stopped synchronously whenever the host enters the background.
 */
final class Nintendo3DsAndroidMotionSource implements SensorEventListener, AutoCloseable {
    private static final int SENSOR_PERIOD_MICROSECONDS = 1_000_000 / 60;
    private static final float STANDARD_GRAVITY = SensorManager.STANDARD_GRAVITY;
    private static final long THREAD_JOIN_TIMEOUT_MILLIS = 1_000L;

    private final Object lock = new Object();
    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor gyroscope;
    private final Nintendo3DsInputState inputState;

    // Guarded by lock.
    private HandlerThread sensorThread;
    private boolean active;
    private int displayRotation = Surface.ROTATION_0;
    private float rawAccelerometerX;
    private float rawAccelerometerY;
    private float rawAccelerometerZ = STANDARD_GRAVITY;
    private float rawGyroscopeX;
    private float rawGyroscopeY;
    private float rawGyroscopeZ;
    private long accelerometerEvents;
    private long gyroscopeEvents;
    private long startCount;
    private long stopCount;

    Nintendo3DsAndroidMotionSource(Context context, Nintendo3DsInputState inputState) {
        Context applicationContext = Objects.requireNonNull(context).getApplicationContext();
        sensorManager = applicationContext.getSystemService(SensorManager.class);
        accelerometer = sensorManager == null
                ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager == null
                ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        this.inputState = Objects.requireNonNull(inputState);
    }

    void setDisplayRotation(int rotation) {
        validateRotation(rotation);
        synchronized (lock) {
            displayRotation = rotation;
            publishLocked();
        }
    }

    boolean start() {
        synchronized (lock) {
            if (active) {
                return true;
            }
            if (sensorManager == null || (accelerometer == null && gyroscope == null)) {
                return false;
            }
            HandlerThread createdThread = new HandlerThread("EmuOrbit-N3DS-Motion");
            createdThread.start();
            Handler handler = new Handler(createdThread.getLooper());
            boolean accelerometerRegistered = accelerometer != null
                    && sensorManager.registerListener(
                            this,
                            accelerometer,
                            SENSOR_PERIOD_MICROSECONDS,
                            handler);
            boolean gyroscopeRegistered = gyroscope != null
                    && sensorManager.registerListener(
                            this,
                            gyroscope,
                            SENSOR_PERIOD_MICROSECONDS,
                            handler);
            if (!accelerometerRegistered && !gyroscopeRegistered) {
                sensorManager.unregisterListener(this);
                createdThread.quitSafely();
                return false;
            }
            sensorThread = createdThread;
            active = true;
            startCount++;
            return true;
        }
    }

    void stop() {
        HandlerThread thread;
        synchronized (lock) {
            if (!active) {
                inputState.setMotion(0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f);
                return;
            }
            active = false;
            stopCount++;
            thread = sensorThread;
            sensorThread = null;
            inputState.setMotion(0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f);
        }
        sensorManager.unregisterListener(this);
        if (thread != null) {
            thread.quitSafely();
            join(thread);
        }
    }

    Nintendo3DsMotionReport report() {
        synchronized (lock) {
            return new Nintendo3DsMotionReport(
                    active,
                    accelerometer != null,
                    gyroscope != null,
                    accelerometerEvents,
                    gyroscopeEvents,
                    startCount,
                    stopCount);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.values.length < 3) {
            return;
        }
        synchronized (lock) {
            if (!active) {
                return;
            }
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                rawAccelerometerX = event.values[0];
                rawAccelerometerY = event.values[1];
                rawAccelerometerZ = event.values[2];
                accelerometerEvents++;
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                rawGyroscopeX = event.values[0];
                rawGyroscopeY = event.values[1];
                rawGyroscopeZ = event.values[2];
                gyroscopeEvents++;
            } else {
                return;
            }
            publishLocked();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Accuracy changes do not change the most recent physical sample.
    }

    @Override
    public void close() {
        stop();
    }

    static float[] mapAndroidAxes(
            float x,
            float y,
            float z,
            int rotation,
            boolean accelerometer) {
        validateRotation(rotation);
        float mappedX;
        float mappedZ;
        switch (rotation) {
            case Surface.ROTATION_0:
                mappedX = -x;
                mappedZ = y;
                break;
            case Surface.ROTATION_90:
                mappedX = y;
                mappedZ = x;
                break;
            case Surface.ROTATION_180:
                mappedX = x;
                mappedZ = -y;
                break;
            case Surface.ROTATION_270:
                mappedX = -y;
                mappedZ = -x;
                break;
            default:
                throw new AssertionError("Rotação Android 3DS não reconhecida.");
        }
        float mappedY = z;
        if (accelerometer) {
            return new float[]{
                    -mappedX / STANDARD_GRAVITY,
                    -mappedY / STANDARD_GRAVITY,
                    -mappedZ / STANDARD_GRAVITY};
        }
        return new float[]{mappedX, mappedY, mappedZ};
    }

    private void publishLocked() {
        float[] acceleration = mapAndroidAxes(
                rawAccelerometerX,
                rawAccelerometerY,
                rawAccelerometerZ,
                displayRotation,
                true);
        float[] rotation = mapAndroidAxes(
                rawGyroscopeX,
                rawGyroscopeY,
                rawGyroscopeZ,
                displayRotation,
                false);
        inputState.setMotion(
                acceleration[0],
                acceleration[1],
                acceleration[2],
                rotation[0],
                rotation[1],
                rotation[2]);
    }

    private static void validateRotation(int rotation) {
        if (rotation != Surface.ROTATION_0
                && rotation != Surface.ROTATION_90
                && rotation != Surface.ROTATION_180
                && rotation != Surface.ROTATION_270) {
            throw new IllegalArgumentException("Rotação Android 3DS inválida.");
        }
    }

    private static void join(Thread thread) {
        if (thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(THREAD_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
