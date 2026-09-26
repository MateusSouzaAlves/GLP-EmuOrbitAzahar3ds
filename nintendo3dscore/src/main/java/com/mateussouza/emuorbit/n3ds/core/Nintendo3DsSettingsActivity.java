// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.play.core.splitcompat.SplitCompat;
import com.mateussouza.emuorbit.advance.ui.MateusDialog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Feature-owned hub for supported Nintendo 3DS settings and durable user data. */
public class Nintendo3DsSettingsActivity extends ComponentActivity
        implements Nintendo3DsExperienceSettingsDialogController.Listener {
    private static final int COLOR_BACKGROUND = Color.rgb(7, 15, 36);
    private static final int COLOR_PANEL = Color.rgb(18, 31, 58);
    private static final int COLOR_TEXT = Color.rgb(245, 248, 255);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(185, 199, 224);
    private static final int COLOR_ACCENT = Color.rgb(70, 213, 232);
    private static final Object DATA_OPERATION_LOCK = new Object();

    private final ExecutorService dataExecutor = Executors.newSingleThreadExecutor();
    private Nintendo3DsExperienceSettingsDialogController settingsController;
    private Nintendo3DsStorageLayout storageLayout;
    private Nintendo3DsMiiDataManager miiDataManager;
    private TextView miiStatus;
    private TextView backupStatus;
    private Button importMiiButton;
    private Button createBackupButton;
    private Button restoreBackupButton;
    private Button exportPortableButton;
    private Button importPortableButton;
    private boolean operationInFlight;
    private boolean destroyed;

    private final ActivityResultLauncher<Intent> miiDocumentPicker =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                            return;
                        }
                        Uri document = result.getData().getData();
                        if (document != null) {
                            importMii(document);
                        }
                    });

    private final ActivityResultLauncher<String> portableExportPicker =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/zip"),
                    document -> {
                        if (document != null) {
                            exportPortableData(document);
                        }
                    });

    private final ActivityResultLauncher<String[]> portableImportPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    document -> {
                        if (document != null) {
                            confirmPortableImport(document);
                        }
                    });

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (Nintendo3DsProductRuntimePolicy.requiresSplitCompat(base.getPackageName())) {
            SplitCompat.installActivity(this);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        settingsController = new Nintendo3DsExperienceSettingsDialogController(
                new Nintendo3DsExperienceSettingsStore(this),
                new Nintendo3DsProductDialogStyleAdapter());
        try {
            storageLayout = Nintendo3DsStorageLayout.open(getFilesDir(), getCacheDir());
            miiDataManager = new Nintendo3DsMiiDataManager(storageLayout);
        } catch (IOException exception) {
            storageLayout = null;
            miiDataManager = null;
        }
        setContentView(createContent());
        refreshSummaries();
    }

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom);
            return windowInsets;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        Button back = new Button(this);
        back.setId(R.id.n3ds_settings_hub_back);
        back.setText(R.string.n3ds_settings_hub_back);
        back.setAllCaps(false);
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setTextColor(COLOR_ACCENT);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(view -> finish());
        content.addView(back, matchWrap());

        TextView title = text(R.string.n3ds_settings_hub_title, 28, COLOR_TEXT);
        title.setId(R.id.n3ds_settings_hub_title);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        ViewCompat.setAccessibilityHeading(title, true);
        content.addView(title, matchWrap());

        TextView intro = text(R.string.n3ds_settings_hub_intro, 15, COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams introParams = matchWrap();
        introParams.topMargin = dp(8);
        introParams.bottomMargin = dp(20);
        content.addView(intro, introParams);

        content.addView(settingsPanel(), panelParams());
        content.addView(miiPanel(), panelParams());
        content.addView(dataPanel(), panelParams());
        content.addView(helpPanel(), panelParams());
        configureSequentialActionFocus(content);
        return scroll;
    }

    private void configureSequentialActionFocus(View root) {
        int[] actionIds = {
                R.id.n3ds_settings_hub_back,
                R.id.n3ds_settings_hub_open_settings,
                R.id.n3ds_settings_hub_import_mii,
                R.id.n3ds_settings_hub_create_backup,
                R.id.n3ds_settings_hub_restore_backup,
                R.id.n3ds_settings_hub_export_portable,
                R.id.n3ds_settings_hub_import_portable
        };
        for (int index = 0; index < actionIds.length; index++) {
            View action = root.findViewById(actionIds[index]);
            if (action == null) {
                continue;
            }
            if (index > 0) {
                action.setNextFocusUpId(actionIds[index - 1]);
                action.setAccessibilityTraversalAfter(actionIds[index - 1]);
            }
            if (index + 1 < actionIds.length) {
                action.setNextFocusForwardId(actionIds[index + 1]);
                action.setNextFocusDownId(actionIds[index + 1]);
            }
        }
    }

    private View settingsPanel() {
        LinearLayout panel = panel();
        panel.addView(sectionTitle(R.string.n3ds_settings_hub_emulation_title), matchWrap());
        panel.addView(sectionBody(R.string.n3ds_settings_hub_emulation_description), bodyParams());
        Button open = actionButton(R.string.n3ds_settings_hub_open_settings);
        open.setId(R.id.n3ds_settings_hub_open_settings);
        open.setOnClickListener(view -> {
            Nintendo3DsExperienceSettingsDialogController controller = settingsController;
            if (controller != null) {
                controller.showGlobal(this, this);
            }
        });
        panel.addView(open, matchWrap());
        return panel;
    }

    private View miiPanel() {
        LinearLayout panel = panel();
        panel.addView(sectionTitle(R.string.n3ds_settings_hub_mii_title), matchWrap());
        panel.addView(sectionBody(R.string.n3ds_settings_hub_mii_description), bodyParams());
        miiStatus = sectionBody(R.string.n3ds_settings_hub_checking);
        miiStatus.setId(R.id.n3ds_settings_hub_mii_status);
        panel.addView(miiStatus, bodyParams());
        importMiiButton = actionButton(R.string.n3ds_settings_hub_import_mii);
        importMiiButton.setId(R.id.n3ds_settings_hub_import_mii);
        importMiiButton.setOnClickListener(
                view -> miiDocumentPicker.launch(Nintendo3DsMiiDataManager.createSafImportIntent()));
        panel.addView(importMiiButton, matchWrap());
        return panel;
    }

    private View dataPanel() {
        LinearLayout panel = panel();
        panel.addView(sectionTitle(R.string.n3ds_settings_hub_data_title), matchWrap());
        panel.addView(sectionBody(R.string.n3ds_settings_hub_data_description), bodyParams());
        backupStatus = sectionBody(R.string.n3ds_settings_hub_checking);
        backupStatus.setId(R.id.n3ds_settings_hub_backup_status);
        panel.addView(backupStatus, bodyParams());

        boolean stackActions = shouldStackDataActions(
                getResources().getConfiguration().screenWidthDp);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(stackActions
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);
        createBackupButton = actionButton(R.string.n3ds_settings_hub_create_backup);
        createBackupButton.setId(R.id.n3ds_settings_hub_create_backup);
        createBackupButton.setOnClickListener(view -> createCheckpoint());
        actions.addView(createBackupButton, dataActionParams(stackActions, false));
        restoreBackupButton = actionButton(R.string.n3ds_settings_hub_restore_backup);
        restoreBackupButton.setId(R.id.n3ds_settings_hub_restore_backup);
        restoreBackupButton.setEnabled(false);
        restoreBackupButton.setOnClickListener(view -> confirmRestore());
        actions.addView(restoreBackupButton, dataActionParams(stackActions, true));
        panel.addView(actions, matchWrap());

        LinearLayout portableActions = new LinearLayout(this);
        portableActions.setOrientation(stackActions
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
        portableActions.setGravity(Gravity.START);
        exportPortableButton = actionButton(R.string.n3ds_settings_hub_export_portable);
        exportPortableButton.setId(R.id.n3ds_settings_hub_export_portable);
        exportPortableButton.setOnClickListener(view -> portableExportPicker.launch(
                "EmuOrbit-Nintendo-3DS-data.zip"));
        portableActions.addView(
                exportPortableButton,
                dataActionParams(stackActions, false));
        importPortableButton = actionButton(R.string.n3ds_settings_hub_import_portable);
        importPortableButton.setId(R.id.n3ds_settings_hub_import_portable);
        importPortableButton.setOnClickListener(view -> portableImportPicker.launch(
                new String[]{"application/zip", "application/octet-stream"}));
        portableActions.addView(
                importPortableButton,
                dataActionParams(stackActions, true));
        LinearLayout.LayoutParams portableParams = matchWrap();
        portableParams.topMargin = dp(8);
        panel.addView(portableActions, portableParams);
        return panel;
    }

    private View helpPanel() {
        LinearLayout panel = panel();
        panel.addView(sectionTitle(R.string.n3ds_settings_hub_help_title), matchWrap());
        TextView help = sectionBody(R.string.n3ds_settings_hub_help_body);
        help.setId(R.id.n3ds_settings_hub_help_body);
        panel.addView(help, bodyParams());
        return panel;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_PANEL);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(42, 65, 101));
        panel.setBackground(background);
        return panel;
    }

    private TextView sectionTitle(int stringId) {
        TextView view = text(stringId, 19, COLOR_TEXT);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        ViewCompat.setAccessibilityHeading(view, true);
        return view;
    }

    private TextView sectionBody(int stringId) {
        return text(stringId, 14, COLOR_TEXT_SECONDARY);
    }

    private TextView text(int stringId, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(stringId);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    private Button actionButton(int stringId) {
        Button button = new Button(this);
        button.setText(stringId);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams bodyParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        params.bottomMargin = dp(12);
        return params;
    }

    private LinearLayout.LayoutParams panelParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(14);
        return params;
    }

    private LinearLayout.LayoutParams dataActionParams(
            boolean stacked,
            boolean followsAnotherAction) {
        LinearLayout.LayoutParams params = stacked
                ? new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f);
        if (followsAnotherAction) {
            if (stacked) {
                params.topMargin = dp(8);
            } else {
                params.setMarginStart(dp(8));
            }
        }
        return params;
    }

    static boolean shouldStackDataActions(int screenWidthDp) {
        return screenWidthDp < 600;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshSummaries() {
        Nintendo3DsStorageLayout layout = storageLayout;
        Nintendo3DsMiiDataManager manager = miiDataManager;
        if (layout == null || manager == null) {
            renderUnavailable();
            return;
        }
        dataExecutor.execute(() -> {
            try {
                Nintendo3DsMiiDataManager.Inspection inspection;
                boolean hasBackup;
                long backupDate;
                synchronized (DATA_OPERATION_LOCK) {
                    inspection = manager.inspect();
                    File manifest = layout.getLatestManifest();
                    hasBackup = manifest.isFile();
                    backupDate = hasBackup ? manifest.lastModified() : 0L;
                }
                runOnUiThread(() -> renderSummaries(inspection, hasBackup, backupDate));
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(this::renderUnavailable);
            }
        });
    }

    private void renderSummaries(
            Nintendo3DsMiiDataManager.Inspection inspection,
            boolean hasBackup,
            long backupDate) {
        if (destroyed) {
            return;
        }
        switch (inspection.getStatus()) {
            case READY:
                miiStatus.setText(getString(
                        R.string.n3ds_settings_hub_mii_ready,
                        inspection.getVisibleMiiCount(),
                        Formatter.formatShortFileSize(this, inspection.getFileBytes())));
                break;
            case EMPTY:
                miiStatus.setText(R.string.n3ds_settings_hub_mii_empty);
                break;
            case MALFORMED:
                miiStatus.setText(R.string.n3ds_settings_hub_mii_invalid);
                break;
            case MISSING:
            default:
                miiStatus.setText(R.string.n3ds_settings_hub_mii_missing);
                break;
        }
        if (hasBackup) {
            String date = DateFormat.getMediumDateFormat(this).format(new Date(backupDate));
            String time = DateFormat.getTimeFormat(this).format(new Date(backupDate));
            backupStatus.setText(getString(
                    R.string.n3ds_settings_hub_backup_available,
                    date + " " + time));
        } else {
            backupStatus.setText(R.string.n3ds_settings_hub_backup_missing);
        }
        restoreBackupButton.setEnabled(hasBackup && !operationInFlight);
        renderOperationState();
    }

    private void renderUnavailable() {
        if (destroyed || miiStatus == null) {
            return;
        }
        miiStatus.setText(R.string.n3ds_settings_hub_data_unavailable);
        backupStatus.setText(R.string.n3ds_settings_hub_data_unavailable);
        importMiiButton.setEnabled(false);
        createBackupButton.setEnabled(false);
        restoreBackupButton.setEnabled(false);
        exportPortableButton.setEnabled(false);
        importPortableButton.setEnabled(false);
    }

    private void renderOperationState() {
        if (importMiiButton == null) {
            return;
        }
        importMiiButton.setEnabled(!operationInFlight && miiDataManager != null);
        createBackupButton.setEnabled(!operationInFlight && storageLayout != null);
        exportPortableButton.setEnabled(!operationInFlight && storageLayout != null);
        importPortableButton.setEnabled(!operationInFlight && storageLayout != null);
        if (operationInFlight) {
            restoreBackupButton.setEnabled(false);
        }
    }

    private void importMii(Uri document) {
        Nintendo3DsMiiDataManager manager = miiDataManager;
        Nintendo3DsStorageLayout layout = storageLayout;
        if (manager == null || layout == null || !beginOperation()) {
            return;
        }
        dataExecutor.execute(() -> {
            try {
                Nintendo3DsMiiDataManager.ImportReport report;
                synchronized (DATA_OPERATION_LOCK) {
                    report = manager.importFromSafAfterCoreClosed(
                            getContentResolver(), document);
                    layout.checkpointAfterCoreClosed(
                            Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
                }
                completeOperation(getString(
                        R.string.n3ds_settings_hub_mii_imported,
                        report.getVisibleMiiCount()));
            } catch (IOException | RuntimeException exception) {
                failOperation();
            }
        });
    }

    private void createCheckpoint() {
        Nintendo3DsStorageLayout layout = storageLayout;
        if (layout == null || !beginOperation()) {
            return;
        }
        dataExecutor.execute(() -> {
            try {
                Nintendo3DsStorageLayout.CheckpointReport report;
                synchronized (DATA_OPERATION_LOCK) {
                    report = layout.checkpointAfterCoreClosed(
                            Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
                }
                completeOperation(getString(
                        R.string.n3ds_settings_hub_backup_created,
                        report.getFileCount(),
                        Formatter.formatShortFileSize(this, report.getLogicalBytes())));
            } catch (IOException | RuntimeException exception) {
                failOperation();
            }
        });
    }

    private void confirmRestore() {
        if (operationInFlight || storageLayout == null) {
            return;
        }
        new MateusDialog.Builder(this)
                .setAvatar(
                        com.mateussouza.emuorbit.advance.R.drawable
                                .mateus_dialog_restore_save)
                .setTitle(R.string.n3ds_settings_hub_restore_title)
                .setMessage(R.string.n3ds_settings_hub_restore_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.n3ds_settings_hub_restore_confirm,
                        (dialog, which) -> restoreCheckpoint())
                .show();
    }

    private void restoreCheckpoint() {
        Nintendo3DsStorageLayout layout = storageLayout;
        if (layout == null || !beginOperation()) {
            return;
        }
        dataExecutor.execute(() -> {
            try {
                Nintendo3DsStorageLayout.RestoreReport report;
                synchronized (DATA_OPERATION_LOCK) {
                    report = layout.restoreLatestAfterCoreClosed(
                            Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
                }
                completeOperation(getString(
                        R.string.n3ds_settings_hub_backup_restored,
                        report.getFileCount(),
                        Formatter.formatShortFileSize(this, report.getRestoredBytes())));
            } catch (IOException | RuntimeException exception) {
                failOperation();
            }
        });
    }

    private void exportPortableData(Uri document) {
        Nintendo3DsStorageLayout layout = storageLayout;
        if (layout == null || !beginOperation()) {
            return;
        }
        dataExecutor.execute(() -> {
            try (OutputStream destination = getContentResolver().openOutputStream(document, "w")) {
                if (destination == null) {
                    throw new IOException("The selected document is unavailable");
                }
                Nintendo3DsStorageLayout.PortableExportReport report;
                synchronized (DATA_OPERATION_LOCK) {
                    report = layout.exportPortableDataAfterCoreClosed(
                            Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                            destination);
                }
                completeOperation(getString(
                        R.string.n3ds_settings_hub_portable_exported,
                        report.getFileCount(),
                        Formatter.formatShortFileSize(this, report.getLogicalBytes())));
            } catch (IOException | RuntimeException exception) {
                failOperation();
            }
        });
    }

    private void confirmPortableImport(Uri document) {
        if (operationInFlight || storageLayout == null) {
            return;
        }
        new MateusDialog.Builder(this)
                .setAvatar(com.mateussouza.emuorbit.advance.R.drawable.mateus_dialog_import)
                .setTitle(R.string.n3ds_settings_hub_import_portable_title)
                .setMessage(R.string.n3ds_settings_hub_import_portable_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.n3ds_settings_hub_import_portable_confirm,
                        (dialog, which) -> importPortableData(document))
                .show();
    }

    private void importPortableData(Uri document) {
        Nintendo3DsStorageLayout layout = storageLayout;
        if (layout == null || !beginOperation()) {
            return;
        }
        dataExecutor.execute(() -> {
            try (InputStream source = getContentResolver().openInputStream(document)) {
                if (source == null) {
                    throw new IOException("The selected document is unavailable");
                }
                Nintendo3DsStorageLayout.PortableImportReport report;
                synchronized (DATA_OPERATION_LOCK) {
                    report = layout.importPortableDataAfterCoreClosed(
                            Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                            source);
                }
                completeOperation(getString(
                        R.string.n3ds_settings_hub_portable_imported,
                        report.getFileCount(),
                        Formatter.formatShortFileSize(this, report.getRestoredBytes())));
            } catch (IOException | RuntimeException exception) {
                failOperation();
            }
        });
    }

    private boolean beginOperation() {
        if (operationInFlight || destroyed) {
            return false;
        }
        operationInFlight = true;
        renderOperationState();
        return true;
    }

    private void completeOperation(String message) {
        runOnUiThread(() -> {
            if (destroyed) {
                return;
            }
            operationInFlight = false;
            renderOperationState();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            refreshSummaries();
        });
    }

    private void failOperation() {
        runOnUiThread(() -> {
            if (destroyed) {
                return;
            }
            operationInFlight = false;
            renderOperationState();
            Toast.makeText(
                    this,
                    R.string.n3ds_settings_hub_operation_failed,
                    Toast.LENGTH_LONG).show();
            refreshSummaries();
        });
    }

    @Override
    public void onSettingsChanged(
            Nintendo3DsExperienceSettings settings,
            Nintendo3DsExperienceSettingsDialogController.Scope scope,
            boolean reset) {
        // The feature store has already persisted the complete global transaction.
    }

    @Override
    public void onPersistenceFailed(
            Nintendo3DsExperienceSettingsDialogController.Scope scope) {
        Toast.makeText(
                this,
                R.string.n3ds_product_settings_save_failed,
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDialogDismissed(
            Nintendo3DsExperienceSettingsDialogController.Scope scope,
            boolean settingsChanged) {
        // The settings dialog is one destination inside this hub; keep the hub open.
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        Nintendo3DsExperienceSettingsDialogController active = settingsController;
        settingsController = null;
        if (active != null) {
            active.close();
        }
        dataExecutor.shutdownNow();
        super.onDestroy();
    }
}
