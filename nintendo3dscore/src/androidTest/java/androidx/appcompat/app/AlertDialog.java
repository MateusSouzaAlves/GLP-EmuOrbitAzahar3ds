// SPDX-License-Identifier: GPL-3.0-or-later
package androidx.appcompat.app;

import android.content.Context;

/**
 * Resource-free AppCompat dialog contract for the self-targeted Nintendo 3DS test APK.
 * Production receives the real AppCompat implementation from the base application.
 */
public class AlertDialog extends android.app.AlertDialog {
    public AlertDialog(Context context) {
        super(context);
    }
}
