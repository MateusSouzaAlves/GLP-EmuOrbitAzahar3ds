// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.DialogInterface;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/** Test-only accessors for the app-owned dialog component shared with Nintendo DS. */
final class Nintendo3DsMateusDialogTestActions {
    private Nintendo3DsMateusDialogTestActions() {
    }

    static Button button(AlertDialog dialog, int which) {
        String testTag = switch (which) {
            case DialogInterface.BUTTON_POSITIVE -> "n3ds-test-dialog-positive";
            case DialogInterface.BUTTON_NEGATIVE -> "n3ds-test-dialog-negative";
            case DialogInterface.BUTTON_NEUTRAL -> "n3ds-test-dialog-neutral";
            default -> throw new IllegalArgumentException("Unknown dialog action: " + which);
        };
        Button testButton = findByTag(dialog, testTag);
        if (testButton != null) {
            return testButton;
        }
        Button platformButton = dialog.getButton(which);
        if (platformButton != null) {
            return platformButton;
        }
        String resourceName = switch (which) {
            case DialogInterface.BUTTON_POSITIVE -> "mateus_dialog_positive_button";
            case DialogInterface.BUTTON_NEGATIVE -> "mateus_dialog_negative_button";
            case DialogInterface.BUTTON_NEUTRAL -> "mateus_dialog_neutral_button";
            default -> throw new IllegalArgumentException("Unknown dialog action: " + which);
        };
        int id = dialog.getContext().getResources().getIdentifier(
                resourceName,
                "id",
                dialog.getContext().getPackageName());
        return id == 0 ? null : dialog.findViewById(id);
    }

    static TextView title(AlertDialog dialog) {
        TextView testTitle = findByTag(dialog, "n3ds-test-dialog-title");
        if (testTitle != null) {
            return testTitle;
        }
        return findText(dialog, "mateus_dialog_title", "alertTitle");
    }

    static TextView message(AlertDialog dialog) {
        TextView testMessage = findByTag(dialog, "n3ds-test-dialog-message");
        if (testMessage != null) {
            return testMessage;
        }
        TextView custom = findText(dialog, "mateus_dialog_message", null);
        return custom != null ? custom : dialog.findViewById(android.R.id.message);
    }

    @SuppressWarnings("unchecked")
    private static <T extends android.view.View> T findByTag(
            AlertDialog dialog,
            String tag) {
        if (dialog.getWindow() == null) {
            return null;
        }
        return (T) dialog.getWindow().getDecorView().findViewWithTag(tag);
    }

    private static TextView findText(
            AlertDialog dialog,
            String appResourceName,
            String androidResourceName) {
        int appId = dialog.getContext().getResources().getIdentifier(
                appResourceName,
                "id",
                dialog.getContext().getPackageName());
        if (appId != 0) {
            TextView appView = dialog.findViewById(appId);
            if (appView != null) {
                return appView;
            }
        }
        if (androidResourceName == null) {
            return null;
        }
        int androidId = dialog.getContext().getResources().getIdentifier(
                androidResourceName,
                "id",
                "android");
        return androidId == 0 ? null : dialog.findViewById(androidId);
    }
}
