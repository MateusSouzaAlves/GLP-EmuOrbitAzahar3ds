// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.pm.ActivityInfo;

/** Keeps the phone's explicit choice independent of a temporary wide/windowed layout. */
final class Nintendo3DsWindowOrientationPolicy {
    private Nintendo3DsWindowOrientationPolicy() {
    }

    static int phoneChoice(int savedChoice) {
        return savedChoice == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }

    static boolean allowsPhoneChoice(int smallestWidthDp, boolean multiWindow) {
        // Unknown width is not evidence that forcing an orientation is safe.
        return !multiWindow && smallestWidthDp > 0 && smallestWidthDp < 600;
    }

    static int resolve(int savedPhoneChoice, int smallestWidthDp, boolean multiWindow) {
        return allowsPhoneChoice(smallestWidthDp, multiWindow)
                ? phoneChoice(savedPhoneChoice)
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }
}
