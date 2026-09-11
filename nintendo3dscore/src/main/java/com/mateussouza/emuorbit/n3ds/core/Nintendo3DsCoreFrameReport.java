// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Objects;

/** Immutable evidence emitted after a real Azahar/Vulkan frame is presented. */
public final class Nintendo3DsCoreFrameReport {
    private static final int FIELD_COUNT = 64;

    private final String deviceName;
    private final int apiVersionMajor;
    private final int apiVersionMinor;
    private final int apiVersionPatch;
    private final int surfaceFormat;
    private final int swapchainImageCount;
    private final long presentedFrames;
    private final long videoFrames;
    private final long audioFrames;
    private final long environmentCallbacks;
    private final long vfsOpenCalls;
    private final long vfsReadCalls;
    private final long vfsBytesRead;
    private final int width;
    private final int height;
    private final boolean preferredVulkan;
    private final boolean hardwareRenderNegotiated;
    private final boolean negotiationInterfaceReceived;
    private final boolean hardwareInterfaceProvided;
    private final boolean contextReset;
    private final boolean contentOpenedThroughVfs;
    private final int audioSampleRate;
    private final long audioQueuedFrames;
    private final long audioDroppedFrames;
    private final long audioPeakQueuedFrames;
    private final long inputPollCallbacks;
    private final long inputStateCallbacks;
    private final long joypadStateCallbacks;
    private final long analogStateCallbacks;
    private final int lastPolledButtonMask;
    private final int lastPolledCirclePadX;
    private final int lastPolledCirclePadY;
    private final long rightAnalogStateCallbacks;
    private final long pointerStateCallbacks;
    private final int lastPolledCStickX;
    private final int lastPolledCStickY;
    private final int lastPolledPointerX;
    private final int lastPolledPointerY;
    private final boolean lastPolledPointerPressed;
    private final long sensorStateCallbacks;
    private final long sensorInputCallbacks;
    private final int sensorSamplingRateHz;
    private final float lastPolledAccelerometerX;
    private final float lastPolledAccelerometerY;
    private final float lastPolledAccelerometerZ;
    private final float lastPolledGyroscopeX;
    private final float lastPolledGyroscopeY;
    private final float lastPolledGyroscopeZ;
    private final boolean microphoneFrontendSelected;
    private final long microphoneOpenCallbacks;
    private final long microphoneCloseCallbacks;
    private final long microphoneStateCallbacks;
    private final long microphoneReadCallbacks;
    private final int microphoneSamplingRateHz;
    private final boolean microphoneActive;
    private final long microphoneCapturedSamples;
    private final long microphoneQueuedSamples;
    private final long microphoneDroppedSamples;
    private final long microphoneDeliveredSamples;
    private final long microphoneSilentSamples;
    private final String performanceProfile;
    private final long performanceOptionRequests;
    private final int resolutionFactor;
    private final boolean diskShaderCacheEnabled;

    Nintendo3DsCoreFrameReport(String[] fields) {
        Objects.requireNonNull(fields);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException("Relatório de frame 3DS incompleto.");
        }
        deviceName = Objects.requireNonNull(fields[0]);
        apiVersionMajor = parseInt(fields[1]);
        apiVersionMinor = parseInt(fields[2]);
        apiVersionPatch = parseInt(fields[3]);
        surfaceFormat = parseInt(fields[4]);
        swapchainImageCount = parseInt(fields[5]);
        presentedFrames = parseLong(fields[6]);
        videoFrames = parseLong(fields[7]);
        audioFrames = parseLong(fields[8]);
        environmentCallbacks = parseLong(fields[9]);
        vfsOpenCalls = parseLong(fields[10]);
        vfsReadCalls = parseLong(fields[11]);
        vfsBytesRead = parseLong(fields[12]);
        width = parseInt(fields[13]);
        height = parseInt(fields[14]);
        preferredVulkan = parseBoolean(fields[15]);
        hardwareRenderNegotiated = parseBoolean(fields[16]);
        negotiationInterfaceReceived = parseBoolean(fields[17]);
        hardwareInterfaceProvided = parseBoolean(fields[18]);
        contextReset = parseBoolean(fields[19]);
        contentOpenedThroughVfs = parseBoolean(fields[20]);
        audioSampleRate = parseInt(fields[21]);
        audioQueuedFrames = parseLong(fields[22]);
        audioDroppedFrames = parseLong(fields[23]);
        audioPeakQueuedFrames = parseLong(fields[24]);
        inputPollCallbacks = parseLong(fields[25]);
        inputStateCallbacks = parseLong(fields[26]);
        joypadStateCallbacks = parseLong(fields[27]);
        analogStateCallbacks = parseLong(fields[28]);
        lastPolledButtonMask = parseInt(fields[29]);
        lastPolledCirclePadX = parseInt(fields[30]);
        lastPolledCirclePadY = parseInt(fields[31]);
        rightAnalogStateCallbacks = parseLong(fields[32]);
        pointerStateCallbacks = parseLong(fields[33]);
        lastPolledCStickX = parseInt(fields[34]);
        lastPolledCStickY = parseInt(fields[35]);
        lastPolledPointerX = parseInt(fields[36]);
        lastPolledPointerY = parseInt(fields[37]);
        lastPolledPointerPressed = parseBoolean(fields[38]);
        sensorStateCallbacks = parseLong(fields[39]);
        sensorInputCallbacks = parseLong(fields[40]);
        sensorSamplingRateHz = parseInt(fields[41]);
        lastPolledAccelerometerX = parseFloat(fields[42]);
        lastPolledAccelerometerY = parseFloat(fields[43]);
        lastPolledAccelerometerZ = parseFloat(fields[44]);
        lastPolledGyroscopeX = parseFloat(fields[45]);
        lastPolledGyroscopeY = parseFloat(fields[46]);
        lastPolledGyroscopeZ = parseFloat(fields[47]);
        microphoneFrontendSelected = parseBoolean(fields[48]);
        microphoneOpenCallbacks = parseLong(fields[49]);
        microphoneCloseCallbacks = parseLong(fields[50]);
        microphoneStateCallbacks = parseLong(fields[51]);
        microphoneReadCallbacks = parseLong(fields[52]);
        microphoneSamplingRateHz = parseInt(fields[53]);
        microphoneActive = parseBoolean(fields[54]);
        microphoneCapturedSamples = parseLong(fields[55]);
        microphoneQueuedSamples = parseLong(fields[56]);
        microphoneDroppedSamples = parseLong(fields[57]);
        microphoneDeliveredSamples = parseLong(fields[58]);
        microphoneSilentSamples = parseLong(fields[59]);
        performanceProfile = Objects.requireNonNull(fields[60]);
        performanceOptionRequests = parseLong(fields[61]);
        resolutionFactor = parseInt(fields[62]);
        diskShaderCacheEnabled = parseBoolean(fields[63]);
    }

    public String getDeviceName() { return deviceName; }
    public int getApiVersionMajor() { return apiVersionMajor; }
    public int getApiVersionMinor() { return apiVersionMinor; }
    public int getApiVersionPatch() { return apiVersionPatch; }
    public int getSurfaceFormat() { return surfaceFormat; }
    public int getSwapchainImageCount() { return swapchainImageCount; }
    public long getPresentedFrames() { return presentedFrames; }
    public long getVideoFrames() { return videoFrames; }
    public long getAudioFrames() { return audioFrames; }
    public long getEnvironmentCallbacks() { return environmentCallbacks; }
    public long getVfsOpenCalls() { return vfsOpenCalls; }
    public long getVfsReadCalls() { return vfsReadCalls; }
    public long getVfsBytesRead() { return vfsBytesRead; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isPreferredVulkan() { return preferredVulkan; }
    public boolean isHardwareRenderNegotiated() { return hardwareRenderNegotiated; }
    public boolean isNegotiationInterfaceReceived() { return negotiationInterfaceReceived; }
    public boolean isHardwareInterfaceProvided() { return hardwareInterfaceProvided; }
    public boolean isContextReset() { return contextReset; }
    public boolean isContentOpenedThroughVfs() { return contentOpenedThroughVfs; }
    public int getAudioSampleRate() { return audioSampleRate; }
    public long getAudioQueuedFrames() { return audioQueuedFrames; }
    public long getAudioDroppedFrames() { return audioDroppedFrames; }
    public long getAudioPeakQueuedFrames() { return audioPeakQueuedFrames; }
    public long getInputPollCallbacks() { return inputPollCallbacks; }
    public long getInputStateCallbacks() { return inputStateCallbacks; }
    public long getJoypadStateCallbacks() { return joypadStateCallbacks; }
    public long getAnalogStateCallbacks() { return analogStateCallbacks; }
    public int getLastPolledButtonMask() { return lastPolledButtonMask; }
    public int getLastPolledCirclePadX() { return lastPolledCirclePadX; }
    public int getLastPolledCirclePadY() { return lastPolledCirclePadY; }
    public long getRightAnalogStateCallbacks() { return rightAnalogStateCallbacks; }
    public long getPointerStateCallbacks() { return pointerStateCallbacks; }
    public int getLastPolledCStickX() { return lastPolledCStickX; }
    public int getLastPolledCStickY() { return lastPolledCStickY; }
    public int getLastPolledPointerX() { return lastPolledPointerX; }
    public int getLastPolledPointerY() { return lastPolledPointerY; }
    public boolean isLastPolledPointerPressed() { return lastPolledPointerPressed; }
    public long getSensorStateCallbacks() { return sensorStateCallbacks; }
    public long getSensorInputCallbacks() { return sensorInputCallbacks; }
    public int getSensorSamplingRateHz() { return sensorSamplingRateHz; }
    public float getLastPolledAccelerometerX() { return lastPolledAccelerometerX; }
    public float getLastPolledAccelerometerY() { return lastPolledAccelerometerY; }
    public float getLastPolledAccelerometerZ() { return lastPolledAccelerometerZ; }
    public float getLastPolledGyroscopeX() { return lastPolledGyroscopeX; }
    public float getLastPolledGyroscopeY() { return lastPolledGyroscopeY; }
    public float getLastPolledGyroscopeZ() { return lastPolledGyroscopeZ; }
    public boolean isMicrophoneFrontendSelected() { return microphoneFrontendSelected; }
    public long getMicrophoneOpenCallbacks() { return microphoneOpenCallbacks; }
    public long getMicrophoneCloseCallbacks() { return microphoneCloseCallbacks; }
    public long getMicrophoneStateCallbacks() { return microphoneStateCallbacks; }
    public long getMicrophoneReadCallbacks() { return microphoneReadCallbacks; }
    public int getMicrophoneSamplingRateHz() { return microphoneSamplingRateHz; }
    public boolean isMicrophoneActive() { return microphoneActive; }
    public long getMicrophoneCapturedSamples() { return microphoneCapturedSamples; }
    public long getMicrophoneQueuedSamples() { return microphoneQueuedSamples; }
    public long getMicrophoneDroppedSamples() { return microphoneDroppedSamples; }
    public long getMicrophoneDeliveredSamples() { return microphoneDeliveredSamples; }
    public long getMicrophoneSilentSamples() { return microphoneSilentSamples; }
    public String getPerformanceProfile() { return performanceProfile; }
    public long getPerformanceOptionRequests() { return performanceOptionRequests; }
    public int getResolutionFactor() { return resolutionFactor; }
    public boolean isDiskShaderCacheEnabled() { return diskShaderCacheEnabled; }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Campo numérico 3DS inválido.", exception);
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Contador 3DS inválido.", exception);
        }
    }

    private static float parseFloat(String value) {
        try {
            float parsed = Float.parseFloat(value);
            if (!Float.isFinite(parsed)) {
                throw new NumberFormatException("non-finite");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Sensor 3DS inválido.", exception);
        }
    }

    private static boolean parseBoolean(String value) {
        if ("1".equals(value)) {
            return true;
        }
        if ("0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("Campo booleano 3DS inválido.");
    }
}
