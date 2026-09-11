// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.Button;

import java.util.Objects;

/** Renders one readiness model and dispatches its typed recovery actions. */
public final class Nintendo3DsReadinessDialogController implements AutoCloseable {
    @FunctionalInterface
    public interface ActionListener {
        void onAction(Nintendo3DsReadinessPresentation.Action action);
    }

    /** Lets the product theme the isolated dialog without coupling this module to app resources. */
    public interface StyleAdapter {
        int getThemeResource(
                Activity activity,
                Nintendo3DsReadinessPresentation.Model model);

        void onDialogShown(
                Activity activity,
                AlertDialog dialog,
                Nintendo3DsReadinessPresentation.Model model);
    }

    public static final StyleAdapter DEFAULT_STYLE = new StyleAdapter() {
        @Override
        public int getThemeResource(
                Activity activity,
                Nintendo3DsReadinessPresentation.Model model) {
            return 0;
        }

        @Override
        public void onDialogShown(
                Activity activity,
                AlertDialog dialog,
                Nintendo3DsReadinessPresentation.Model model) {
            // The platform theme remains unchanged.
        }
    };

    private final StyleAdapter styleAdapter;

    private AlertDialog activeDialog;

    public Nintendo3DsReadinessDialogController() {
        this(DEFAULT_STYLE);
    }

    public Nintendo3DsReadinessDialogController(StyleAdapter styleAdapter) {
        this.styleAdapter = Objects.requireNonNull(styleAdapter);
    }

    /**
     * Shows the model when it needs presentation. Must be called on the Android main thread.
     * Returns false for a ready model or while another readiness dialog is already visible.
     */
    public boolean show(
            Activity activity,
            Nintendo3DsReadinessPresentation.Model model,
            ActionListener listener) {
        requireMainThread();
        Activity checkedActivity = Objects.requireNonNull(activity);
        Nintendo3DsReadinessPresentation.Model checkedModel = Objects.requireNonNull(model);
        ActionListener checkedListener = Objects.requireNonNull(listener);
        if (!checkedModel.isVisible()) {
            return false;
        }
        if (checkedActivity.isFinishing() || checkedActivity.isDestroyed()) {
            throw new IllegalStateException("Cannot show readiness for a closing Activity");
        }
        if (activeDialog != null && activeDialog.isShowing()) {
            return false;
        }

        Nintendo3DsReadinessPresentation.Action primary = checkedModel.getPrimaryAction();
        if (primary == Nintendo3DsReadinessPresentation.Action.NONE
                || primary.getLabelResource() == 0) {
            throw new IllegalArgumentException("A visible readiness model needs an action");
        }

        int themeResource = styleAdapter.getThemeResource(checkedActivity, checkedModel);
        if (themeResource < 0) {
            throw new IllegalArgumentException("Readiness dialog theme cannot be negative");
        }
        AlertDialog.Builder builder = themeResource == 0
                ? new AlertDialog.Builder(checkedActivity)
                : new AlertDialog.Builder(checkedActivity, themeResource);
        builder
                .setTitle(checkedModel.getTitleResource())
                .setMessage(checkedModel.formatMessages(checkedActivity))
                .setPositiveButton(primary.getLabelResource(), null);
        boolean hasContinueAlternative = checkedModel.canContinue()
                && primary != Nintendo3DsReadinessPresentation.Action.CONTINUE;
        if (hasContinueAlternative) {
            builder.setNeutralButton(
                    Nintendo3DsReadinessPresentation.Action.CONTINUE.getLabelResource(), null);
        }
        if (primary != Nintendo3DsReadinessPresentation.Action.UNDERSTOOD) {
            builder.setNegativeButton(android.R.string.cancel, null);
        }

        AlertDialog dialog = builder.create();
        activeDialog = dialog;
        dialog.setOnShowListener(ignored -> {
            bindAction(dialog, DialogInterface.BUTTON_POSITIVE, primary, checkedListener);
            if (hasContinueAlternative) {
                bindAction(
                        dialog,
                        DialogInterface.BUTTON_NEUTRAL,
                        Nintendo3DsReadinessPresentation.Action.CONTINUE,
                        checkedListener);
            }
        });
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) {
                activeDialog = null;
            }
        });
        dialog.show();
        try {
            styleAdapter.onDialogShown(checkedActivity, dialog, checkedModel);
        } catch (RuntimeException failure) {
            activeDialog = null;
            dialog.dismiss();
            throw failure;
        }
        return true;
    }

    /** Binds the dialog directly to the isolated action coordinator. */
    public boolean show(
            Activity activity,
            Nintendo3DsReadinessPresentation.Model model,
            Nintendo3DsReadinessActionCoordinator actionCoordinator) {
        Nintendo3DsReadinessActionCoordinator checked =
                Objects.requireNonNull(actionCoordinator);
        return show(activity, model, checked::dispatch);
    }

    public boolean isShowing() {
        return activeDialog != null && activeDialog.isShowing();
    }

    AlertDialog getActiveDialogForTesting() {
        return activeDialog;
    }

    @Override
    public void close() {
        requireMainThread();
        AlertDialog dialog = activeDialog;
        activeDialog = null;
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
    }

    private static void bindAction(
            AlertDialog dialog,
            int buttonId,
            Nintendo3DsReadinessPresentation.Action action,
            ActionListener listener) {
        Button button = dialog.getButton(buttonId);
        if (button == null) {
            throw new IllegalStateException("Expected readiness action button is missing");
        }
        button.setOnClickListener(ignored -> {
            if (!dialog.isShowing()) {
                return;
            }
            dialog.dismiss();
            listener.onAction(action);
        });
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Readiness UI must run on the Android main thread");
        }
    }
}
