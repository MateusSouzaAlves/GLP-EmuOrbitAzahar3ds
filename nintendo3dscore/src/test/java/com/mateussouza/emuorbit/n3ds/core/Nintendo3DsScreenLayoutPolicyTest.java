// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;

import android.content.res.Configuration;

import org.junit.Test;

public final class Nintendo3DsScreenLayoutPolicyTest {
    @Test
    public void defaultLayoutBecomesSideBySideInLandscape() {
        assertEquals(
                Nintendo3DsScreenLayout.SIDE_BY_SIDE,
                Nintendo3DsScreenLayoutPolicy.resolve(
                        Nintendo3DsScreenLayout.DEFAULT,
                        Configuration.ORIENTATION_LANDSCAPE));
    }

    @Test
    public void defaultLayoutRemainsNativeInPortrait() {
        assertEquals(
                Nintendo3DsScreenLayout.DEFAULT,
                Nintendo3DsScreenLayoutPolicy.resolve(
                        Nintendo3DsScreenLayout.DEFAULT,
                        Configuration.ORIENTATION_PORTRAIT));
    }

    @Test
    public void explicitUserLayoutIsPreservedInEveryOrientation() {
        assertEquals(
                Nintendo3DsScreenLayout.LARGE_BOTTOM,
                Nintendo3DsScreenLayoutPolicy.resolve(
                        Nintendo3DsScreenLayout.LARGE_BOTTOM,
                        Configuration.ORIENTATION_LANDSCAPE));
    }
}
