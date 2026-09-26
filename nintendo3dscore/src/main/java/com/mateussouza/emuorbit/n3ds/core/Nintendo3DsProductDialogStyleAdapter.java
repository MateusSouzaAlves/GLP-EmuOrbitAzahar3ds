// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;

/** Concrete, resource-independent dialog styling for the isolated product host. */
public final class Nintendo3DsProductDialogStyleAdapter
        implements Nintendo3DsReadinessDialogController.StyleAdapter,
        Nintendo3DsExperienceSettingsDialogController.StyleAdapter {
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

    void onProductDialogShown(Activity activity, AlertDialog dialog) {
        apply(activity, dialog);
    }

    private static void apply(Activity activity, AlertDialog dialog) {
        // MateusDialog owns the same responsive sizing, dimming and action styling as the DS.
    }
}
