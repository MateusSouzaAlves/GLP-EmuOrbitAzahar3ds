// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Objects;

/** Edits the isolated Nintendo 3DS settings without depending on base-app resources. */
public final class Nintendo3DsExperienceSettingsDialogController implements AutoCloseable {
    public enum Scope {
        GLOBAL,
        GAME
    }

    public interface Listener {
        void onSettingsChanged(
                Nintendo3DsExperienceSettings settings,
                Scope scope,
                boolean reset);

        void onPersistenceFailed(Scope scope);

        /** Called after a user-driven dismissal so a gameplay host can resume safely. */
        default void onDialogDismissed(Scope scope, boolean settingsChanged) {
            // Optional lifecycle hook.
        }
    }

    /** Lets the eventual product feature apply its theme without a reverse app dependency. */
    public interface StyleAdapter {
        int getThemeResource(Activity activity, Scope scope);

        void onDialogShown(Activity activity, AlertDialog dialog, Scope scope);
    }

    public static final StyleAdapter DEFAULT_STYLE = new StyleAdapter() {
        @Override
        public int getThemeResource(Activity activity, Scope scope) {
            return 0;
        }

        @Override
        public void onDialogShown(Activity activity, AlertDialog dialog, Scope scope) {
            // The platform theme remains unchanged.
        }
    };

    private static final Nintendo3DsScreenLayout[] LAYOUTS = {
            Nintendo3DsScreenLayout.DEFAULT,
            Nintendo3DsScreenLayout.SIDE_BY_SIDE,
            Nintendo3DsScreenLayout.LARGE_TOP,
            Nintendo3DsScreenLayout.LARGE_BOTTOM,
            Nintendo3DsScreenLayout.SINGLE_TOP,
            Nintendo3DsScreenLayout.SINGLE_BOTTOM
    };
    private static final Nintendo3DsPerformanceProfile[] PERFORMANCE_PROFILES = {
            Nintendo3DsPerformanceProfile.CONSERVATIVE,
            Nintendo3DsPerformanceProfile.BALANCED,
            Nintendo3DsPerformanceProfile.PERFORMANCE
    };

    private final Nintendo3DsExperienceSettingsStore store;
    private final StyleAdapter styleAdapter;

    private AlertDialog activeDialog;
    private Form activeForm;

    public Nintendo3DsExperienceSettingsDialogController(
            Nintendo3DsExperienceSettingsStore store) {
        this(store, DEFAULT_STYLE);
    }

    public Nintendo3DsExperienceSettingsDialogController(
            Nintendo3DsExperienceSettingsStore store,
            StyleAdapter styleAdapter) {
        this.store = Objects.requireNonNull(store);
        this.styleAdapter = Objects.requireNonNull(styleAdapter);
    }

    public boolean showGlobal(Activity activity, Listener listener) {
        return show(activity, null, Scope.GLOBAL, listener);
    }

    public boolean showForGame(
            Activity activity,
            String persistentId,
            Listener listener) {
        return show(
                activity,
                Objects.requireNonNull(persistentId),
                Scope.GAME,
                listener);
    }

    public boolean isShowing() {
        return activeDialog != null && activeDialog.isShowing();
    }

    AlertDialog getActiveDialogForTesting() {
        return activeDialog;
    }

    Spinner getLayoutSpinnerForTesting() {
        return requireForm().layoutSpinner;
    }

    Spinner getPerformanceProfileSpinnerForTesting() {
        return requireForm().performanceProfileSpinner;
    }

    CheckBox getAudioEnabledForTesting() {
        return requireForm().audioEnabled;
    }

    SeekBar getAudioVolumeForTesting() {
        return requireForm().audioVolume;
    }

    TextView getAudioVolumeLabelForTesting() {
        return requireForm().volumeLabel;
    }

    CheckBox getMicrophoneEnabledForTesting() {
        return requireForm().microphoneEnabled;
    }

    @Override
    public void close() {
        requireMainThread();
        AlertDialog dialog = activeDialog;
        activeDialog = null;
        activeForm = null;
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
    }

    private boolean show(
            Activity activity,
            String persistentId,
            Scope scope,
            Listener listener) {
        requireMainThread();
        Activity checkedActivity = Objects.requireNonNull(activity);
        Listener checkedListener = Objects.requireNonNull(listener);
        if (checkedActivity.isFinishing() || checkedActivity.isDestroyed()) {
            throw new IllegalStateException("Cannot show settings for a closing Activity");
        }
        if (activeDialog != null && activeDialog.isShowing()) {
            return false;
        }

        Nintendo3DsExperienceSettings global = store.loadGlobal();
        Nintendo3DsExperienceSettings selected = scope == Scope.GLOBAL
                ? global
                : store.loadGameOverrides(persistentId).resolve(global);
        Form form = createForm(checkedActivity, selected);
        int themeResource = styleAdapter.getThemeResource(checkedActivity, scope);
        if (themeResource < 0) {
            throw new IllegalArgumentException("Settings dialog theme cannot be negative");
        }
        AlertDialog.Builder builder = themeResource == 0
                ? new AlertDialog.Builder(checkedActivity)
                : new AlertDialog.Builder(checkedActivity, themeResource);
        builder
                .setTitle(scope == Scope.GLOBAL
                        ? R.string.n3ds_settings_global_title
                        : R.string.n3ds_settings_game_title)
                .setView(form.root)
                .setPositiveButton(R.string.n3ds_settings_save, null)
                .setNeutralButton(R.string.n3ds_settings_reset, null)
                .setNegativeButton(android.R.string.cancel, null);

        AlertDialog dialog = builder.create();
        boolean[] settingsChanged = {false};
        activeDialog = dialog;
        activeForm = form;
        dialog.setOnShowListener(ignored -> {
            bindSave(
                    dialog,
                    form,
                    persistentId,
                    scope,
                    settingsChanged,
                    checkedListener);
            bindReset(dialog, persistentId, scope, settingsChanged, checkedListener);
        });
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) {
                activeDialog = null;
                activeForm = null;
            }
            checkedListener.onDialogDismissed(scope, settingsChanged[0]);
        });
        dialog.show();
        try {
            styleAdapter.onDialogShown(checkedActivity, dialog, scope);
        } catch (RuntimeException failure) {
            activeDialog = null;
            activeForm = null;
            dialog.dismiss();
            throw failure;
        }
        return true;
    }

    private void bindSave(
            AlertDialog dialog,
            Form form,
            String persistentId,
            Scope scope,
            boolean[] settingsChanged,
            Listener listener) {
        Button save = requireButton(dialog, DialogInterface.BUTTON_POSITIVE);
        save.setOnClickListener(ignored -> {
            Nintendo3DsExperienceSettings selected = form.readSettings();
            boolean persisted;
            if (scope == Scope.GLOBAL) {
                persisted = store.saveGlobal(selected);
            } else {
                Nintendo3DsExperienceSettings global = store.loadGlobal();
                persisted = store.saveGameOverrides(
                        persistentId,
                        Nintendo3DsExperienceSettingsOverrides.between(global, selected));
            }
            if (!persisted) {
                listener.onPersistenceFailed(scope);
                return;
            }
            settingsChanged[0] = true;
            dialog.dismiss();
            listener.onSettingsChanged(selected, scope, false);
        });
    }

    private void bindReset(
            AlertDialog dialog,
            String persistentId,
            Scope scope,
            boolean[] settingsChanged,
            Listener listener) {
        Button reset = requireButton(dialog, DialogInterface.BUTTON_NEUTRAL);
        reset.setOnClickListener(ignored -> {
            boolean persisted = scope == Scope.GLOBAL
                    ? store.resetGlobal()
                    : store.removeGameOverrides(persistentId);
            if (!persisted) {
                listener.onPersistenceFailed(scope);
                return;
            }
            Nintendo3DsExperienceSettings resolved = scope == Scope.GLOBAL
                    ? Nintendo3DsExperienceSettings.defaults()
                    : store.loadGlobal();
            settingsChanged[0] = true;
            dialog.dismiss();
            listener.onSettingsChanged(resolved, scope, true);
        });
    }

    private static Form createForm(
            Activity activity,
            Nintendo3DsExperienceSettings selected) {
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding / 2, padding, padding / 2);

        TextView layoutLabel = new TextView(activity);
        layoutLabel.setText(R.string.n3ds_settings_screen_layout);
        Spinner layoutSpinner = new Spinner(activity);
        layoutSpinner.setId(View.generateViewId());
        layoutLabel.setLabelFor(layoutSpinner.getId());
        layoutSpinner.setContentDescription(
                activity.getString(R.string.n3ds_settings_screen_layout));
        String[] layoutLabels = {
                activity.getString(R.string.n3ds_settings_layout_default),
                activity.getString(R.string.n3ds_settings_layout_side_by_side),
                activity.getString(R.string.n3ds_settings_layout_large_top),
                activity.getString(R.string.n3ds_settings_layout_large_bottom),
                activity.getString(R.string.n3ds_settings_layout_single_top),
                activity.getString(R.string.n3ds_settings_layout_single_bottom)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                layoutLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        layoutSpinner.setAdapter(adapter);
        layoutSpinner.setSelection(indexOf(selected.getScreenLayout()));

        TextView performanceProfileLabel = new TextView(activity);
        performanceProfileLabel.setText(R.string.n3ds_settings_performance_profile);
        Spinner performanceProfileSpinner = new Spinner(activity);
        performanceProfileSpinner.setId(View.generateViewId());
        performanceProfileLabel.setLabelFor(performanceProfileSpinner.getId());
        performanceProfileSpinner.setContentDescription(
                activity.getString(R.string.n3ds_settings_performance_profile));
        String[] performanceProfileLabels = {
                activity.getString(R.string.n3ds_settings_performance_conservative),
                activity.getString(R.string.n3ds_settings_performance_balanced),
                activity.getString(R.string.n3ds_settings_performance_performance)
        };
        ArrayAdapter<String> performanceAdapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                performanceProfileLabels);
        performanceAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        performanceProfileSpinner.setAdapter(performanceAdapter);
        performanceProfileSpinner.setSelection(
                indexOf(selected.getPerformanceProfile()));

        CheckBox audioEnabled = new CheckBox(activity);
        audioEnabled.setText(R.string.n3ds_settings_audio_enabled);
        audioEnabled.setChecked(selected.isAudioEnabled());

        TextView volumeLabel = new TextView(activity);
        SeekBar audioVolume = new SeekBar(activity);
        audioVolume.setId(View.generateViewId());
        volumeLabel.setLabelFor(audioVolume.getId());
        audioVolume.setMax(100);
        audioVolume.setProgress(Math.round(selected.getAudioVolume() * 100));
        updateVolumeLabel(activity, volumeLabel, audioVolume.getProgress());
        audioVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateVolumeLabel(activity, volumeLabel, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // No-op.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // No-op.
            }
        });

        CheckBox microphoneEnabled = new CheckBox(activity);
        microphoneEnabled.setText(R.string.n3ds_settings_microphone_enabled);
        microphoneEnabled.setChecked(selected.isMicrophoneEnabled());

        content.addView(layoutLabel);
        content.addView(layoutSpinner, matchWidthWrapHeight());
        content.addView(performanceProfileLabel);
        content.addView(performanceProfileSpinner, matchWidthWrapHeight());
        content.addView(audioEnabled, matchWidthWrapHeight());
        content.addView(volumeLabel);
        content.addView(audioVolume, matchWidthWrapHeight());
        content.addView(microphoneEnabled, matchWidthWrapHeight());

        ScrollView root = new ScrollView(activity);
        root.setFillViewport(true);
        root.addView(content, matchWidthWrapHeight());
        return new Form(
                root,
                layoutSpinner,
                performanceProfileSpinner,
                audioEnabled,
                volumeLabel,
                audioVolume,
                microphoneEnabled);
    }

    private static void updateVolumeLabel(Activity activity, TextView label, int progress) {
        label.setText(activity.getString(R.string.n3ds_settings_audio_volume, progress));
    }

    private static LinearLayout.LayoutParams matchWidthWrapHeight() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int indexOf(Nintendo3DsScreenLayout layout) {
        for (int index = 0; index < LAYOUTS.length; index++) {
            if (LAYOUTS[index] == layout) {
                return index;
            }
        }
        return 0;
    }

    private static int indexOf(Nintendo3DsPerformanceProfile profile) {
        for (int index = 0; index < PERFORMANCE_PROFILES.length; index++) {
            if (PERFORMANCE_PROFILES[index] == profile) {
                return index;
            }
        }
        return 1;
    }

    private static Button requireButton(AlertDialog dialog, int which) {
        Button button = dialog.getButton(which);
        if (button == null) {
            throw new IllegalStateException("Expected settings action button is missing");
        }
        return button;
    }

    private Form requireForm() {
        Form form = activeForm;
        if (form == null) {
            throw new IllegalStateException("No active settings form");
        }
        return form;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Settings UI must run on the Android main thread");
        }
    }

    private static final class Form {
        private final ScrollView root;
        private final Spinner layoutSpinner;
        private final Spinner performanceProfileSpinner;
        private final CheckBox audioEnabled;
        private final TextView volumeLabel;
        private final SeekBar audioVolume;
        private final CheckBox microphoneEnabled;

        private Form(
                ScrollView root,
                Spinner layoutSpinner,
                Spinner performanceProfileSpinner,
                CheckBox audioEnabled,
                TextView volumeLabel,
                SeekBar audioVolume,
                CheckBox microphoneEnabled) {
            this.root = root;
            this.layoutSpinner = layoutSpinner;
            this.performanceProfileSpinner = performanceProfileSpinner;
            this.audioEnabled = audioEnabled;
            this.volumeLabel = volumeLabel;
            this.audioVolume = audioVolume;
            this.microphoneEnabled = microphoneEnabled;
        }

        private Nintendo3DsExperienceSettings readSettings() {
            return new Nintendo3DsExperienceSettings(
                    LAYOUTS[layoutSpinner.getSelectedItemPosition()],
                    PERFORMANCE_PROFILES[performanceProfileSpinner.getSelectedItemPosition()],
                    audioEnabled.isChecked(),
                    audioVolume.getProgress() / 100.0f,
                    microphoneEnabled.isChecked());
        }
    }
}
