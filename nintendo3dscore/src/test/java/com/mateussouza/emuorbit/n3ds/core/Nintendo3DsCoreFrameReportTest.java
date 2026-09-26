// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Nintendo3DsCoreFrameReportTest {
    @Test
    public void parsesRealFrameEvidence() {
        Nintendo3DsCoreFrameReport report = new Nintendo3DsCoreFrameReport(new String[]{
                "Samsung Xclipse 530", "1", "3", "279", "37", "5",
                "2", "2", "1090", "44", "3", "8", "4096", "400", "480",
                "1", "1", "1", "1", "1", "1", "32728", "545", "0", "545",
                "2", "128", "96", "32", "257", "16384", "-8192",
                "16", "12", "-24575", "8192", "82", "16486", "1",
                "4", "24", "60", "0.25", "-0.5", "-0.75", "1.5", "-2.5", "3.5",
                "1", "1", "0", "1", "20", "48000", "1", "4096", "128", "0",
                "3968", "1280", "balanced", "11", "2", "1", "59.831225"
        });

        assertEquals("Samsung Xclipse 530", report.getDeviceName());
        assertEquals(2, report.getPresentedFrames());
        assertEquals(400, report.getWidth());
        assertEquals(480, report.getHeight());
        assertTrue(report.isPreferredVulkan());
        assertTrue(report.isHardwareRenderNegotiated());
        assertTrue(report.isNegotiationInterfaceReceived());
        assertTrue(report.isHardwareInterfaceProvided());
        assertTrue(report.isContextReset());
        assertTrue(report.isContentOpenedThroughVfs());
        assertEquals(32728, report.getAudioSampleRate());
        assertEquals(545, report.getAudioQueuedFrames());
        assertEquals(0, report.getAudioDroppedFrames());
        assertEquals(545, report.getAudioPeakQueuedFrames());
        assertEquals(2, report.getInputPollCallbacks());
        assertEquals(128, report.getInputStateCallbacks());
        assertEquals(96, report.getJoypadStateCallbacks());
        assertEquals(32, report.getAnalogStateCallbacks());
        assertEquals(257, report.getLastPolledButtonMask());
        assertEquals(16384, report.getLastPolledCirclePadX());
        assertEquals(-8192, report.getLastPolledCirclePadY());
        assertEquals(16, report.getRightAnalogStateCallbacks());
        assertEquals(12, report.getPointerStateCallbacks());
        assertEquals(-24575, report.getLastPolledCStickX());
        assertEquals(8192, report.getLastPolledCStickY());
        assertEquals(82, report.getLastPolledPointerX());
        assertEquals(16486, report.getLastPolledPointerY());
        assertTrue(report.isLastPolledPointerPressed());
        assertEquals(4, report.getSensorStateCallbacks());
        assertEquals(24, report.getSensorInputCallbacks());
        assertEquals(60, report.getSensorSamplingRateHz());
        assertEquals(0.25f, report.getLastPolledAccelerometerX(), 0.0f);
        assertEquals(-0.5f, report.getLastPolledAccelerometerY(), 0.0f);
        assertEquals(-0.75f, report.getLastPolledAccelerometerZ(), 0.0f);
        assertEquals(1.5f, report.getLastPolledGyroscopeX(), 0.0f);
        assertEquals(-2.5f, report.getLastPolledGyroscopeY(), 0.0f);
        assertEquals(3.5f, report.getLastPolledGyroscopeZ(), 0.0f);
        assertTrue(report.isMicrophoneFrontendSelected());
        assertEquals(1, report.getMicrophoneOpenCallbacks());
        assertEquals(0, report.getMicrophoneCloseCallbacks());
        assertEquals(1, report.getMicrophoneStateCallbacks());
        assertEquals(20, report.getMicrophoneReadCallbacks());
        assertEquals(48_000, report.getMicrophoneSamplingRateHz());
        assertTrue(report.isMicrophoneActive());
        assertEquals(4096, report.getMicrophoneCapturedSamples());
        assertEquals(128, report.getMicrophoneQueuedSamples());
        assertEquals(0, report.getMicrophoneDroppedSamples());
        assertEquals(3968, report.getMicrophoneDeliveredSamples());
        assertEquals(1280, report.getMicrophoneSilentSamples());
        assertEquals("balanced", report.getPerformanceProfile());
        assertEquals(11, report.getPerformanceOptionRequests());
        assertEquals(2, report.getResolutionFactor());
        assertTrue(report.isDiskShaderCacheEnabled());
        assertEquals(59.831225, report.getNominalFramesPerSecond(), 0.000001);
    }

    @Test
    public void rejectsMalformedWireValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new Nintendo3DsCoreFrameReport(new String[]{"device"}));
        assertThrows(IllegalArgumentException.class,
                () -> new Nintendo3DsCoreFrameReport(new String[]{
                        "device", "x", "0", "0", "0", "2", "1", "1", "0",
                        "1", "1", "1", "1", "400", "480", "1", "1", "1",
                        "1", "1", "1", "32728", "0", "0", "0",
                        "1", "1", "1", "1", "0", "0", "0",
                        "1", "1", "0", "0", "0", "0", "0",
                        "1", "1", "60", "0", "0", "-1", "0", "0", "0",
                        "1", "1", "0", "1", "1", "48000", "1", "0", "0", "0",
                        "0", "128", "balanced", "11", "2", "1", "59.831225"
                }));
    }
}
