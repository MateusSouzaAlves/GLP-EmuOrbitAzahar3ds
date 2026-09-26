// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Nintendo3DsSurfaceFrameCaptureTest {
    @Test
    public void preservesAlreadyBoundedSurface() {
        assertArrayEquals(
                new int[]{1_080, 1_920},
                Nintendo3DsSurfaceFrameCapture.boundedSize(1_080, 1_920));
    }

    @Test
    public void boundsLongEdgeAndPreservesAspectRatio() {
        int[] landscape = Nintendo3DsSurfaceFrameCapture.boundedSize(3_840, 2_160);
        assertArrayEquals(new int[]{2_048, 1_152}, landscape);

        int[] portrait = Nintendo3DsSurfaceFrameCapture.boundedSize(2_160, 3_840);
        assertArrayEquals(new int[]{1_152, 2_048}, portrait);
        assertEquals(
                (double) 3_840 / 2_160,
                (double) portrait[1] / portrait[0],
                0.001d);
    }

    @Test
    public void rejectsInvalidDimensions() {
        boolean rejected = false;
        try {
            Nintendo3DsSurfaceFrameCapture.boundedSize(0, 480);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }
}
