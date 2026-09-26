// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Nintendo3DsTouchMapperTest {
    @Test
    public void mapsDefaultLayoutInsidePortraitLetterbox() {
        Nintendo3DsTouchMapper.Result mapped = Nintendo3DsTouchMapper.mapToFrame(
                540.0f, 1404.0f, 1080, 2160, 400, 480);

        assertTrue(mapped.isInsideFrame());
        assertEquals(200, mapped.getFrameX());
        assertEquals(360, mapped.getFrameY());
        assertEquals(0, mapped.getContentLeft());
        assertEquals(432, mapped.getContentTop());
        assertEquals(1080, mapped.getContentRight());
        assertEquals(1728, mapped.getContentBottom());
    }

    @Test
    public void mapsSideBySideLayoutInsideCenteredLandscapeFrame() {
        Nintendo3DsTouchMapper.Result mapped = Nintendo3DsTouchMapper.mapToFrame(
                1866.6666f, 540.0f, 2400, 1080, 720, 240);

        assertTrue(mapped.isInsideFrame());
        assertEquals(560, mapped.getFrameX());
        assertEquals(120, mapped.getFrameY());
        assertEquals(0, mapped.getContentLeft());
        assertEquals(140, mapped.getContentTop());
        assertEquals(2400, mapped.getContentRight());
        assertEquals(940, mapped.getContentBottom());
    }

    @Test
    public void mapsRotatedSurfaceAndClampsInclusiveLastEdge() {
        Nintendo3DsTouchMapper.Result center = Nintendo3DsTouchMapper.mapToFrame(
                1200.0f, 540.0f, 2400, 1080, 400, 480);
        Nintendo3DsTouchMapper.Result last = Nintendo3DsTouchMapper.mapToFrame(
                1650.0f, 1080.0f, 2400, 1080, 400, 480);

        assertTrue(center.isInsideFrame());
        assertEquals(200, center.getFrameX());
        assertEquals(240, center.getFrameY());
        assertEquals(750, center.getContentLeft());
        assertEquals(1650, center.getContentRight());
        assertTrue(last.isInsideFrame());
        assertEquals(399, last.getFrameX());
        assertEquals(479, last.getFrameY());
    }

    @Test
    public void rejectsLetterboxAndInvalidCoordinates() {
        Nintendo3DsTouchMapper.Result outside = Nintendo3DsTouchMapper.mapToFrame(
                1200.0f, 100.0f, 2400, 1080, 720, 240);

        assertFalse(outside.isInsideFrame());
        assertEquals(0, outside.getFrameX());
        assertEquals(0, outside.getFrameY());
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsTouchMapper.mapToFrame(
                        Float.NaN, 0.0f, 100, 100, 400, 480));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsTouchMapper.mapToFrame(
                        0.0f, 0.0f, 0, 100, 400, 480));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsTouchMapper.mapToFrame(
                        0.0f, 0.0f, 10, 1, 1, Integer.MAX_VALUE));
    }
}
