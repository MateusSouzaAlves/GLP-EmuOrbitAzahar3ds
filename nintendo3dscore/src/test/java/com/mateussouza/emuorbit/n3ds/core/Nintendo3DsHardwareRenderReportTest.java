// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class Nintendo3DsHardwareRenderReportTest {
    @Test
    public void parsesNativeVulkanReport() {
        Nintendo3DsHardwareRenderReport report = new Nintendo3DsHardwareRenderReport(
                new String[] {"Mali-G615", "1", "3", "0", "37", "3", "7"});

        assertEquals("Mali-G615", report.getDeviceName());
        assertEquals(1, report.getApiVersionMajor());
        assertEquals(3, report.getApiVersionMinor());
        assertEquals(0, report.getApiVersionPatch());
        assertEquals(37, report.getSurfaceFormat());
        assertEquals(3, report.getSwapchainImageCount());
        assertEquals(7L, report.getPresentedFrames());
    }

    @Test
    public void rejectsIncompleteOrNonPositiveReports() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Nintendo3DsHardwareRenderReport(new String[] {"GPU"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Nintendo3DsHardwareRenderReport(
                        new String[] {"GPU", "1", "1", "0", "37", "0", "1"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Nintendo3DsHardwareRenderReport(
                        new String[] {"GPU", "1", "1", "0", "37", "2", "0"}));
    }
}
