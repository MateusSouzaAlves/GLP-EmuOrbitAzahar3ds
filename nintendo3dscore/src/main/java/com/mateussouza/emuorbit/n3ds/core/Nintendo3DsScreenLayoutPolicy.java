// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.res.Configuration;

import java.util.Objects;

/** Resolves the responsive behavior of the user-facing default Nintendo 3DS layout. */
final class Nintendo3DsScreenLayoutPolicy {
    private Nintendo3DsScreenLayoutPolicy() {
    }

    static Nintendo3DsScreenLayout resolve(
            Nintendo3DsScreenLayout configured,
            int orientation) {
        Nintendo3DsScreenLayout checked = Objects.requireNonNull(configured);
        if (checked == Nintendo3DsScreenLayout.DEFAULT
                && orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return Nintendo3DsScreenLayout.SIDE_BY_SIDE;
        }
        return checked;
    }
}
