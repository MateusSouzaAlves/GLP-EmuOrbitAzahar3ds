// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsHardwareRenderHostInstrumentedTest {
    @Test
    public void createsPresentsAndDestroysRealAndroidSwapchain() throws Exception {
        try (Nintendo3DsTestSurfaceOwner surface =
                     new Nintendo3DsTestSurfaceOwner(64, 64)) {
            assertTrue(surface.surface.isValid());
            try (Nintendo3DsHardwareRenderHost host =
                         Nintendo3DsHardwareRenderHost.create(surface.surface, 64, 64)) {
                Nintendo3DsHardwareRenderReport report =
                        host.presentDiagnosticFrame(0x185AB2FF);
                report = host.presentDiagnosticFrame(0x102030FF);

                System.out.println(
                        "N3DS_VULKAN_REPORT device=" + report.getDeviceName()
                                + " api=" + report.getApiVersionMajor()
                                + "." + report.getApiVersionMinor()
                                + "." + report.getApiVersionPatch()
                                + " format=" + report.getSurfaceFormat()
                                + " images=" + report.getSwapchainImageCount()
                                + " frames=" + report.getPresentedFrames());

                assertFalse(report.getDeviceName().isEmpty());
                Bundle arguments = InstrumentationRegistry.getArguments();
                String expectedGpuRegex = arguments.getString("n3dsExpectedGpuRegex", "");
                if (!expectedGpuRegex.isEmpty()) {
                    assertTrue(
                            "O gate remoto exige o driver/GPU Adreno declarado.",
                            Pattern.compile(expectedGpuRegex).matcher(report.getDeviceName()).find());
                }
                assertTrue(report.getApiVersionMajor() >= 1);
                assertTrue(report.getSwapchainImageCount() >= 2);
                assertTrue(report.getPresentedFrames() >= 2);
            }
        }
    }

    @Test
    public void repeatsSurfaceLifecycleWithoutLeakingNativeOwnership() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Nintendo3DsTestSurfaceOwner surface =
                         new Nintendo3DsTestSurfaceOwner(32, 32);
                 Nintendo3DsHardwareRenderHost host =
                         Nintendo3DsHardwareRenderHost.create(surface.surface, 32, 32)) {
                assertTrue(host.presentDiagnosticFrame(0x102030FF).getPresentedFrames() >= 1);
            }
        }
    }
}
