// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Debug-only owner used to validate the hidden Nintendo 3DS settings UI. */
public final class Nintendo3DsSettingsTestActivity extends Activity
        implements Nintendo3DsExperienceSettingsDialogController.Listener {
    static final String EXTRA_SKIP_AUTOMATIC_DIALOG =
            "com.mateussouza.emuorbit.n3ds.core.SKIP_AUTOMATIC_SETTINGS_DIALOG";
    static final String HASH_A =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String HASH_B =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    static final String PERSISTENT_ID = "n3ds-v1:" + HASH_A + ":" + HASH_B;

    private Nintendo3DsExperienceSettingsStore store;
    private Nintendo3DsExperienceSettingsDialogController dialogController;
    private Nintendo3DsExperienceSettings changedSettings;
    private Nintendo3DsExperienceSettingsDialogController.Scope changedScope;
    private boolean reset;
    private int changeCount;
    private int persistenceFailureCount;
    private int styleInvocationCount;
    private int dismissCount;
    private boolean dismissedWithChange;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Nintendo3DsUiTestConfiguration.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new FrameLayout(this));
        store = new Nintendo3DsExperienceSettingsStore(
                this,
                "n3ds_settings_ui_test_" + System.nanoTime());
        Nintendo3DsProductDialogStyleAdapter productStyle =
                new Nintendo3DsProductDialogStyleAdapter();
        dialogController = new Nintendo3DsExperienceSettingsDialogController(store,
                new Nintendo3DsExperienceSettingsDialogController.StyleAdapter() {
                    @Override
                    public int getThemeResource(
                            Activity activity,
                            Nintendo3DsExperienceSettingsDialogController.Scope scope) {
                        return productStyle.getThemeResource(activity, scope);
                    }

                    @Override
                    public void onDialogShown(
                            Activity activity,
                            androidx.appcompat.app.AlertDialog dialog,
                            Nintendo3DsExperienceSettingsDialogController.Scope scope) {
                        productStyle.onDialogShown(activity, dialog, scope);
                        styleInvocationCount++;
                    }
                });
        if (!getIntent().getBooleanExtra(EXTRA_SKIP_AUTOMATIC_DIALOG, false)) {
            dialogController.showGlobal(this, this);
        }
    }

    @Override
    protected void onDestroy() {
        if (dialogController != null) {
            dialogController.close();
        }
        super.onDestroy();
    }

    @Override
    public void onSettingsChanged(
            Nintendo3DsExperienceSettings settings,
            Nintendo3DsExperienceSettingsDialogController.Scope scope,
            boolean reset) {
        changedSettings = settings;
        changedScope = scope;
        this.reset = reset;
        changeCount++;
    }

    @Override
    public void onPersistenceFailed(
            Nintendo3DsExperienceSettingsDialogController.Scope scope) {
        persistenceFailureCount++;
    }

    @Override
    public void onDialogDismissed(
            Nintendo3DsExperienceSettingsDialogController.Scope scope,
            boolean settingsChanged) {
        dismissCount++;
        dismissedWithChange = settingsChanged;
    }

    public boolean showGame() {
        return dialogController.showForGame(this, PERSISTENT_ID, this);
    }

    public Nintendo3DsExperienceSettingsStore getStore() {
        return store;
    }

    public Nintendo3DsExperienceSettingsDialogController getDialogController() {
        return dialogController;
    }

    public Nintendo3DsExperienceSettings getChangedSettings() {
        return changedSettings;
    }

    public Nintendo3DsExperienceSettingsDialogController.Scope getChangedScope() {
        return changedScope;
    }

    public boolean wasReset() {
        return reset;
    }

    public int getChangeCount() {
        return changeCount;
    }

    public int getPersistenceFailureCount() {
        return persistenceFailureCount;
    }

    public int getStyleInvocationCount() {
        return styleInvocationCount;
    }

    public int getDismissCount() {
        return dismissCount;
    }

    public boolean wasDismissedWithChange() {
        return dismissedWithChange;
    }
}
