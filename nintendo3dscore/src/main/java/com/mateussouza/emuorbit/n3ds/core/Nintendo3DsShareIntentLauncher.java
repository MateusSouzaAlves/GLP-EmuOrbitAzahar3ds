// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.ActivityNotFoundException;
import android.content.Intent;

import java.util.Objects;

/** Isolated chooser launch boundary with an explicit no-compatible-target result. */
final class Nintendo3DsShareIntentLauncher {
    @FunctionalInterface
    interface Starter {
        void start(Intent intent);
    }

    private Nintendo3DsShareIntentLauncher() {
    }

    static boolean launch(
            Intent shareIntent,
            CharSequence chooserTitle,
            Starter starter) {
        Objects.requireNonNull(shareIntent);
        Objects.requireNonNull(chooserTitle);
        Objects.requireNonNull(starter);
        try {
            starter.start(Intent.createChooser(shareIntent, chooserTitle));
            return true;
        } catch (ActivityNotFoundException | SecurityException failure) {
            return false;
        }
    }
}
