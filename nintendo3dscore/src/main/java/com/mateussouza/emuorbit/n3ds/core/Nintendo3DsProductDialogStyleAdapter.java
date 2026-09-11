// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

/** Concrete, resource-independent dialog styling for the isolated product host. */
public final class Nintendo3DsProductDialogStyleAdapter
        implements Nintendo3DsReadinessDialogController.StyleAdapter,
        Nintendo3DsExperienceSettingsDialogController.StyleAdapter {
    private static final int MAXIMUM_WIDTH_DP = 560;
    private static final int HORIZONTAL_MARGIN_DP = 24;
    private static final int MINIMUM_ACTION_HEIGHT_DP = 48;
    private static final int MAXIMUM_ACTION_LINES = 2;
    private static final float DIM_AMOUNT = 0.82f;

    @Override
    public int getThemeResource(
            Activity activity,
            Nintendo3DsReadinessPresentation.Model model) {
        return 0;
    }

    @Override
    public int getThemeResource(
            Activity activity,
            Nintendo3DsExperienceSettingsDialogController.Scope scope) {
        return 0;
    }

    @Override
    public void onDialogShown(
            Activity activity,
            AlertDialog dialog,
            Nintendo3DsReadinessPresentation.Model model) {
        apply(activity, dialog);
    }

    @Override
    public void onDialogShown(
            Activity activity,
            AlertDialog dialog,
            Nintendo3DsExperienceSettingsDialogController.Scope scope) {
        apply(activity, dialog);
    }

    private static void apply(Activity activity, AlertDialog dialog) {
        int accent = resolveAccent(activity);
        styleButton(dialog, DialogInterface.BUTTON_POSITIVE, accent);
        styleButton(dialog, DialogInterface.BUTTON_NEGATIVE, accent);
        styleButton(dialog, DialogInterface.BUTTON_NEUTRAL, accent);

        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = DIM_AMOUNT;
        window.setAttributes(attributes);

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        float density = activity.getResources().getDisplayMetrics().density;
        int margin = Math.round(HORIZONTAL_MARGIN_DP * density);
        int maximumWidth = Math.round(MAXIMUM_WIDTH_DP * density);
        int responsiveWidth = Math.min(maximumWidth, Math.max(1, screenWidth - margin * 2));
        window.setLayout(responsiveWidth, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void styleButton(AlertDialog dialog, int which, int accent) {
        Button button = dialog.getButton(which);
        if (button == null) {
            return;
        }
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setMaxLines(MAXIMUM_ACTION_LINES);
        button.setEllipsize(null);
        button.setHorizontallyScrolling(false);
        float density = button.getResources().getDisplayMetrics().density;
        button.setMinHeight(Math.round(MINIMUM_ACTION_HEIGHT_DP * density));
        button.setTextColor(accent);
    }

    private static int resolveAccent(Activity activity) {
        TypedValue value = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) {
            if (value.resourceId != 0) {
                return activity.getColor(value.resourceId);
            }
            return value.data;
        }
        return Color.WHITE;
    }
}
