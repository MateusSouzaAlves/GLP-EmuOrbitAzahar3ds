// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.res.Configuration;

/** Profile variants persisted independently for portrait and landscape. */
public enum Nintendo3DsVirtualControlOrientation {
    PORTRAIT,
    LANDSCAPE;

    static Nintendo3DsVirtualControlOrientation from(Configuration configuration) {
        return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? LANDSCAPE
                : PORTRAIT;
    }
}
