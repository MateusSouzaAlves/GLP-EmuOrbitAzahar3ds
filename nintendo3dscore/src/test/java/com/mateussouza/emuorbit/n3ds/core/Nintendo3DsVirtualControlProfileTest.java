// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class Nintendo3DsVirtualControlProfileTest {
    @Test
    public void identityIsCompleteAndEditsOnlyTheRequestedOrientationAndGroup() {
        Nintendo3DsVirtualControlProfile identity = Nintendo3DsVirtualControlProfile.identity();
        for (Nintendo3DsVirtualControlOrientation orientation
                : Nintendo3DsVirtualControlOrientation.values()) {
            for (Nintendo3DsVirtualControlGroup group
                    : Nintendo3DsVirtualControlGroup.values()) {
                assertSame(
                        Nintendo3DsVirtualControlTransform.identity(),
                        identity.getTransform(orientation, group));
            }
        }

        Nintendo3DsVirtualControlTransform changed =
                new Nintendo3DsVirtualControlTransform(0.4f, -0.25f, 1.3f, false);
        Nintendo3DsVirtualControlProfile edited = identity.withTransform(
                Nintendo3DsVirtualControlOrientation.PORTRAIT,
                Nintendo3DsVirtualControlGroup.ACTIONS,
                changed);

        assertEquals(
                changed,
                edited.getTransform(
                        Nintendo3DsVirtualControlOrientation.PORTRAIT,
                        Nintendo3DsVirtualControlGroup.ACTIONS));
        assertSame(
                Nintendo3DsVirtualControlTransform.identity(),
                edited.getTransform(
                        Nintendo3DsVirtualControlOrientation.LANDSCAPE,
                        Nintendo3DsVirtualControlGroup.ACTIONS));
        assertNotEquals(identity, edited);
    }

    @Test
    public void transformRejectsUnsafeValuesAndCanToggleVisibility() {
        assertThrows(IllegalArgumentException.class,
                () -> new Nintendo3DsVirtualControlTransform(-1.01f, 0.0f, 1.0f, true));
        assertThrows(IllegalArgumentException.class,
                () -> new Nintendo3DsVirtualControlTransform(0.0f, Float.NaN, 1.0f, true));
        assertThrows(IllegalArgumentException.class,
                () -> new Nintendo3DsVirtualControlTransform(0.0f, 0.0f, 1.61f, true));

        Nintendo3DsVirtualControlTransform hidden =
                Nintendo3DsVirtualControlTransform.identity().withVisible(false);
        assertFalse(hidden.isVisible());
        assertEquals(1.0f, hidden.getScale(), 0.0f);
        assertTrue(hidden.withVisible(true).isVisible());
    }

    @Test
    public void codecRoundTripsAndRejectsTrailingCorruptAndFutureDocuments() throws Exception {
        Nintendo3DsVirtualControlProfile expected =
                Nintendo3DsVirtualControlProfile.identity().withTransform(
                        Nintendo3DsVirtualControlOrientation.LANDSCAPE,
                        Nintendo3DsVirtualControlGroup.C_STICK,
                        new Nintendo3DsVirtualControlTransform(-0.5f, 0.75f, 0.8f, false));
        byte[] encoded = Nintendo3DsVirtualControlProfileCodec.encode(expected);
        Nintendo3DsVirtualControlProfileCodec.Result decoded =
                Nintendo3DsVirtualControlProfileCodec.decode(encoded);
        assertEquals(Nintendo3DsVirtualControlProfileCodec.Status.VALID, decoded.getStatus());
        assertEquals(expected, decoded.getProfile());

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertEquals(
                Nintendo3DsVirtualControlProfileCodec.Status.CORRUPT,
                Nintendo3DsVirtualControlProfileCodec.decode(trailing).getStatus());
        byte[] future = encoded.clone();
        future[7] = 2;
        assertEquals(
                Nintendo3DsVirtualControlProfileCodec.Status.UNSUPPORTED_VERSION,
                Nintendo3DsVirtualControlProfileCodec.decode(future).getStatus());
        assertEquals(
                Nintendo3DsVirtualControlProfileCodec.Status.CORRUPT,
                Nintendo3DsVirtualControlProfileCodec.decode(new byte[] {1, 2, 3}).getStatus());
    }

    @Test
    public void geometryConstrainsScaledGroupsToTheOverlay() {
        Nintendo3DsVirtualControlGeometry.AppliedTransform applied =
                Nintendo3DsVirtualControlGeometry.calculate(
                        200,
                        300,
                        10,
                        20,
                        10,
                        20,
                        20,
                        40,
                        100,
                        80,
                        new Nintendo3DsVirtualControlTransform(1.0f, -1.0f, 1.6f, true));

        float centerX = 20.0f + 50.0f + applied.getTranslationX();
        float centerY = 40.0f + 40.0f + applied.getTranslationY();
        float halfWidth = 50.0f * applied.getScale();
        float halfHeight = 40.0f * applied.getScale();
        assertTrue(centerX - halfWidth >= 10.0f);
        assertTrue(centerX + halfWidth <= 190.0f);
        assertTrue(centerY - halfHeight >= 20.0f);
        assertTrue(centerY + halfHeight <= 280.0f);
        assertEquals(1.6f, applied.getScale(), 0.001f);
    }
}
