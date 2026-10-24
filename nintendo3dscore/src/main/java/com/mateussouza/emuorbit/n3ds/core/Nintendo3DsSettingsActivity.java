// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.play.core.splitcompat.SplitCompat;
import com.mateussouza.emuorbit.advance.ui.GradientMaterialCardView;
import com.mateussouza.emuorbit.advance.ui.MateusDialog;
import com.mateussouza.emuorbit.advance.ui.LoopingVideoBackgroundView;

import java.util.Date;

/** Feature-owned hub for supported Nintendo 3DS settings and durable user data. */
public class Nintendo3DsSettingsActivity extends ComponentActivity
        implements Nintendo3DsExperienceSettingsDialogController.Listener {
    private static final int COLOR_BACKGROUND = Color.rgb(7, 15, 36);
    private static final int COLOR_TEXT = Color.rgb(245, 248, 255);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(185, 199, 224);
    private static final int COLOR_ACCENT = Color.rgb(70, 213, 232);
    private Nintendo3DsExperienceSettingsDialogController settingsController;
    private Nintendo3DsDataOperationsModel dataModel;
    private TextView miiStatus;
    private TextView backupStatus;
    private Button importMiiButton;
    private Button createBackupButton;
    private Button restoreBackupButton;
    private Button exportPortableButton;
    private Button importPortableButton;
    private LinearLayout dataActions;
    private LinearLayout portableActions;
    private AlertDialog confirmationDialog;
    private Uri pendingPortableImport;
    private boolean pendingRestore;
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
                            dataModel.start(Nintendo3DsDataOperationsModel.Operation.IMPORT_MII, document);
                        }
                    });

    private final ActivityResultLauncher<String> portableExportPicker =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/zip"),
                    document -> {
                        if (document != null) {
                            dataModel.start(Nintendo3DsDataOperationsModel.Operation.EXPORT_PORTABLE, document);
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
        applyWindowOrientation(getResources().getConfiguration(), isInMultiWindowMode());
        WindowCompat.enableEdgeToEdge(getWindow());
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The existing dark scroll background protects navigation icons.
            getWindow().setNavigationBarContrastEnforced(false);
        }
        settingsController = new Nintendo3DsExperienceSettingsDialogController(
                new Nintendo3DsExperienceSettingsStore(this),
                new Nintendo3DsProductDialogStyleAdapter());
        dataModel = new ViewModelProvider(this).get(Nintendo3DsDataOperationsModel.class);
        setContentView(createContent());
        dataModel.state().observe(this, this::renderDataState);
        if (savedInstanceState != null) {
            settingsController.restoreGlobalDraft(this, this, savedInstanceState.getBundle("n3ds.hub.settings"));
            String document = savedInstanceState.getString("n3ds.hub.import");
            pendingPortableImport = document == null ? null : Uri.parse(document);
            pendingRestore = savedInstanceState.getBoolean("n3ds.hub.restore");
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyWindowOrientation(configuration, isInMultiWindowMode());
        reflowDataActions();
        settingsController.refreshResponsiveLayout();
        MateusDialog.refreshResponsiveLayout(confirmationDialog);
        ViewCompat.requestApplyInsets(getWindow().getDecorView());
    }

    @Override public void onMultiWindowModeChanged(boolean multiWindow, Configuration configuration) {
        super.onMultiWindowModeChanged(multiWindow, configuration);
        applyWindowOrientation(configuration, multiWindow);
        reflowDataActions();
        settingsController.refreshResponsiveLayout();
        MateusDialog.refreshResponsiveLayout(confirmationDialog);
    }

    private void applyWindowOrientation(Configuration configuration, boolean multiWindow) {
        int requested = !multiWindow && configuration.smallestScreenWidthDp < 600
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (getRequestedOrientation() != requested) setRequestedOrientation(requested);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBundle("n3ds.hub.settings", settingsController.saveGlobalDraft());
        if (pendingPortableImport != null) state.putString("n3ds.hub.import", pendingPortableImport.toString());
        state.putBoolean("n3ds.hub.restore", pendingRestore);
        super.onSaveInstanceState(state);
    }

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // This hub is also reached from Saves. Keep its scroll layer transparent
        // over the same silent video as the library, not an opaque navy page.
        scroll.setBackgroundColor(Color.TRANSPARENT);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, windowInsets) -> {
            Insets safeArea = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(
                    safeArea.left,
                    safeArea.top,
                    safeArea.right,
                    safeArea.bottom);
            return windowInsets;
        });
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                view.removeOnAttachStateChangeListener(this);
                ViewCompat.requestApplyInsets(view);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                // No detached-window action; listener is removed on attachment.
            }
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        content.addView(LayoutInflater.from(this).inflate(
                com.mateussouza.emuorbit.advance.R.layout.view_app_navigation_header,
                content, false));

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

        content.addView(cardPanel(settingsPanel()), panelParams());
        content.addView(cardPanel(miiPanel()), panelParams());
        content.addView(cardPanel(dataPanel()), panelParams());
        content.addView(cardPanel(helpPanel()), panelParams());
        configureSequentialActionFocus(content);
        FrameLayout layers = new FrameLayout(this);
        layers.setBackgroundColor(COLOR_BACKGROUND);
        LoopingVideoBackgroundView background = new LoopingVideoBackgroundView(this);
        background.setId(com.mateussouza.emuorbit.advance.R.id.standalone_video_background);
        background.bindToHostLifecycle(getLifecycle());
        layers.addView(background, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackgroundResource(com.mateussouza.emuorbit.advance.R.drawable.bg_library_scrim);
        scrim.setClickable(false);
        scrim.setFocusable(false);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layers.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        layers.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return layers;
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
        Button open = actionButton(R.string.n3ds_settings_hub_open_settings, panel);
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
        importMiiButton = actionButton(R.string.n3ds_settings_hub_import_mii, panel);
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
        dataActions = actions;
        actions.setOrientation(stackActions
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);
        createBackupButton = actionButton(R.string.n3ds_settings_hub_create_backup, actions);
        createBackupButton.setId(R.id.n3ds_settings_hub_create_backup);
        createBackupButton.setOnClickListener(view ->
                dataModel.start(Nintendo3DsDataOperationsModel.Operation.CREATE_CHECKPOINT, null));
        actions.addView(createBackupButton, dataActionParams(stackActions, false));
        restoreBackupButton = actionButton(R.string.n3ds_settings_hub_restore_backup, actions);
        restoreBackupButton.setId(R.id.n3ds_settings_hub_restore_backup);
        restoreBackupButton.setEnabled(false);
        restoreBackupButton.setOnClickListener(view -> confirmRestore());
        actions.addView(restoreBackupButton, dataActionParams(stackActions, true));
        panel.addView(actions, matchWrap());

        LinearLayout portableActions = new LinearLayout(this);
        this.portableActions = portableActions;
        portableActions.setOrientation(stackActions
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
        portableActions.setGravity(Gravity.START);
        exportPortableButton = actionButton(R.string.n3ds_settings_hub_export_portable, portableActions);
        exportPortableButton.setId(R.id.n3ds_settings_hub_export_portable);
        exportPortableButton.setOnClickListener(view -> portableExportPicker.launch(
                "EmuOrbit-Nintendo-3DS-data.zip"));
        portableActions.addView(
                exportPortableButton,
                dataActionParams(stackActions, false));
        importPortableButton = actionButton(R.string.n3ds_settings_hub_import_portable, portableActions);
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
        return panel;
    }

    private View cardPanel(View content) {
        // Reuse the same surface as the base Saves cards. Keep the inner layer
        // transparent so a solid panel cannot hide the shared brand gradient.
        GradientMaterialCardView card = new GradientMaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(0f);
        card.setStrokeWidth(0);
        card.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        return card;
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

    private Button actionButton(int stringId, ViewGroup parent) {
        Button button = (Button) LayoutInflater.from(this).inflate(
                com.mateussouza.emuorbit.advance.R.layout.view_gradient_primary_action,
                parent, false);
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

    private void reflowDataActions() {
        if (dataActions == null || portableActions == null) return;
        boolean stack = shouldStackDataActions(getResources().getConfiguration().screenWidthDp);
        for (LinearLayout row : new LinearLayout[]{dataActions, portableActions}) {
            int orientation = stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
            if (row.getOrientation() == orientation) continue;
            row.setOrientation(orientation);
            for (int i = 0; i < row.getChildCount(); i++) {
                row.getChildAt(i).setLayoutParams(dataActionParams(stack, i > 0));
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void renderDataState(Nintendo3DsDataOperationsModel.State state) {
        if (destroyed) return;
        if (state.inspection != null) renderSummaries(state.inspection, state.hasBackup, state.backupDate);
        else if (!state.busy) {
            miiStatus.setText(R.string.n3ds_settings_hub_data_unavailable);
            backupStatus.setText(R.string.n3ds_settings_hub_data_unavailable);
        }
        boolean enabled = state.available && !state.busy;
        importMiiButton.setEnabled(enabled);
        createBackupButton.setEnabled(enabled);
        exportPortableButton.setEnabled(enabled);
        importPortableButton.setEnabled(enabled);
        restoreBackupButton.setEnabled(enabled && state.hasBackup);
        if (dataModel.consumeMessage(state.messageId)) {
            Toast.makeText(this, getString(state.messageResource, state.messageArgs), Toast.LENGTH_LONG).show();
        }
        // A process-recreated model may still be inspecting storage in onCreate.
        // Restore a confirmation only after availability is known; never replay
        // the operation merely because the old dialog was visible.
        if (enabled && confirmationDialog == null && !settingsController.isShowing()) {
            if (pendingPortableImport != null) confirmPortableImport(pendingPortableImport);
            else if (pendingRestore) confirmRestore();
        }
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
    }

    private void confirmRestore() {
        if (!dataModel.isAvailableAndIdle() || confirmationDialog != null) {
            return;
        }
        pendingRestore = true;
        confirmationDialog = new MateusDialog.Builder(this)
                .setAvatar(
                        com.mateussouza.emuorbit.advance.R.drawable
                                .mateus_dialog_restore_save)
                .setTitle(R.string.n3ds_settings_hub_restore_title)
                .setMessage(R.string.n3ds_settings_hub_restore_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.n3ds_settings_hub_restore_confirm,
                        (dialog, which) -> dataModel.start(
                                Nintendo3DsDataOperationsModel.Operation.RESTORE_CHECKPOINT, null))
                .setOnDismissListener(dialog -> clearConfirmation())
                .show();
    }

    private void confirmPortableImport(Uri document) {
        if (!dataModel.isAvailableAndIdle() || confirmationDialog != null) {
            return;
        }
        pendingPortableImport = document;
        confirmationDialog = new MateusDialog.Builder(this)
                .setAvatar(com.mateussouza.emuorbit.advance.R.drawable.mateus_dialog_import)
                .setTitle(R.string.n3ds_settings_hub_import_portable_title)
                .setMessage(R.string.n3ds_settings_hub_import_portable_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.n3ds_settings_hub_import_portable_confirm,
                        (dialog, which) -> dataModel.start(
                                Nintendo3DsDataOperationsModel.Operation.IMPORT_PORTABLE, document))
                .setOnDismissListener(dialog -> clearConfirmation())
                .show();
    }

    private void clearConfirmation() {
        confirmationDialog = null;
        pendingRestore = false;
        pendingPortableImport = null;
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
        if (confirmationDialog != null) {
            confirmationDialog.setOnDismissListener(null);
            confirmationDialog.dismiss();
            clearConfirmation();
        }
        super.onDestroy();
    }
}
