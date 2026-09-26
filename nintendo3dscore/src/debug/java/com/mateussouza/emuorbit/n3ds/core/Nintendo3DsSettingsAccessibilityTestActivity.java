// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;

/** Debug-only settings hub owner with deterministic locale and font overrides. */
public final class Nintendo3DsSettingsAccessibilityTestActivity
        extends Nintendo3DsSettingsActivity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Nintendo3DsUiTestConfiguration.wrap(newBase));
    }
}
