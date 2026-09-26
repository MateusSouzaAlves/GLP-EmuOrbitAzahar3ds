// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.button;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.title;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.LocaleList;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsExperienceSettingsInstrumentedTest {
    private static final String HASH_A =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String HASH_B =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String PERSISTENT_ID = "n3ds-v1:" + HASH_A + ":" + HASH_B;

    @Test
    public void persistsAppliesAndRendersAccessibleLocalizedSettings() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String preferencesName = "n3ds_experience_test_" + System.nanoTime();
        Nintendo3DsExperienceSettingsStore store =
                new Nintendo3DsExperienceSettingsStore(context, preferencesName);

        assertEquals(Nintendo3DsExperienceSettings.defaults(), store.loadGlobal());
        Nintendo3DsExperienceSettings global = new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.SIDE_BY_SIDE,
                Nintendo3DsPerformanceProfile.CONSERVATIVE,
                true,
                0.75f,
                false,
                false,
                70);
        Nintendo3DsExperienceSettings selected = new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.LARGE_BOTTOM,
                Nintendo3DsPerformanceProfile.PERFORMANCE,
                false,
                0.25f,
                true,
                true,
                40);
        assertTrue(store.saveGlobal(global));
        assertEquals(global, store.loadGlobal());

        Nintendo3DsExperienceSettingsOverrides overrides =
                Nintendo3DsExperienceSettingsOverrides.between(global, selected);
        assertTrue(store.saveGameOverrides(PERSISTENT_ID.toUpperCase(), overrides));
        assertEquals(overrides, store.loadGameOverrides(PERSISTENT_ID));
        assertEquals(selected, store.loadGameOverrides(PERSISTENT_ID).resolve(store.loadGlobal()));
        assertEquals(Collections.singletonMap(PERSISTENT_ID, overrides),
                store.listGameOverrides());

        try (Nintendo3DsCoreLifecycleController controller =
                     new Nintendo3DsCoreLifecycleController("unused-core", "unused-content", "", "")) {
            Nintendo3DsExperienceSettings applicable = new Nintendo3DsExperienceSettings(
                    Nintendo3DsScreenLayout.SINGLE_BOTTOM,
                    Nintendo3DsPerformanceProfile.PERFORMANCE,
                    false,
                    0.5f,
                    false);
            assertEquals(
                    Nintendo3DsMicrophoneStartResult.DISABLED,
                    applicable.applyTo(controller));
            assertEquals(Nintendo3DsScreenLayout.SINGLE_BOTTOM, controller.getScreenLayout());
            assertEquals(
                    Nintendo3DsPerformanceProfile.PERFORMANCE,
                    controller.getPerformanceProfile());
            assertEquals(
                    Nintendo3DsMicrophoneStartResult.UNAVAILABLE,
                    new Nintendo3DsExperienceSettings(
                            Nintendo3DsScreenLayout.SINGLE_BOTTOM,
                            true,
                            1.0f,
                            true).applyTo(controller));
        }

        SharedPreferences raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        assertTrue(raw.edit()
                .putString("global.screen_layout", "REMOVED_LAYOUT")
                .putString("global.performance_profile", "REMOVED_PROFILE")
                .putString("global.audio_enabled", "wrong-type")
                .putFloat("global.audio_volume", Float.NaN)
                .putString("global.microphone_enabled", "wrong-type")
                .putString("global.virtual_controls_visible", "wrong-type")
                .putString("global.virtual_control_opacity", "wrong-type")
                .putString("game." + PERSISTENT_ID + ".audio_volume", "wrong-type")
                .commit());
        Nintendo3DsExperienceSettings recovered = store.loadGlobal();
        assertEquals(Nintendo3DsScreenLayout.DEFAULT, recovered.getScreenLayout());
        assertEquals(
                Nintendo3DsExperienceSettings.defaults().getPerformanceProfile(),
                recovered.getPerformanceProfile());
        assertTrue(recovered.isAudioEnabled());
        assertEquals(1.0f, recovered.getAudioVolume(), 0.0f);
        assertFalse(recovered.isMicrophoneEnabled());
        assertTrue(recovered.areVirtualControlsVisible());
        assertEquals(30, recovered.getVirtualControlOpacityPercent());
        assertNull(store.loadGameOverrides(PERSISTENT_ID).getAudioVolume());

        assertThrows(IllegalArgumentException.class,
                () -> store.loadGameOverrides("n3ds-v1:invalid"));
        assertTrue(store.writeAllGameOverrides(Collections.emptyMap(), true));
        assertTrue(store.listGameOverrides().isEmpty());
        assertTrue(store.removeGameOverrides(PERSISTENT_ID));
        assertTrue(store.resetGlobal());
        assertEquals(Nintendo3DsExperienceSettings.defaults(), store.loadGlobal());

        assertSettingsUiAndLocales(context);
    }

    private static void assertSettingsUiAndLocales(Context context) throws IOException {
        Intent intent = new Intent(context, Nintendo3DsSettingsTestActivity.class);
        Nintendo3DsExperienceSettings expectedGlobal =
                new Nintendo3DsExperienceSettings(
                        Nintendo3DsScreenLayout.LARGE_TOP,
                        Nintendo3DsPerformanceProfile.CONSERVATIVE,
                        false,
                        0.4f,
                        true,
                        false,
                        70);
        try (ActivityScenario<Nintendo3DsSettingsTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                Nintendo3DsExperienceSettingsDialogController controller =
                        activity.getDialogController();
                assertTrue(controller.isShowing());
                assertFalse(controller.showGlobal(activity, activity));
                assertEquals(1, activity.getStyleInvocationCount());

                AlertDialog globalDialog = controller.getActiveDialogForTesting();
                assertNotNull(globalDialog);
                assertEquals(
                        activity.getString(R.string.n3ds_settings_global_title),
                        dialogTitle(activity, globalDialog));
                Spinner layout = controller.getLayoutSpinnerForTesting();
                Spinner performance = controller.getPerformanceProfileSpinnerForTesting();
                CheckBox audio = controller.getAudioEnabledForTesting();
                SeekBar volume = controller.getAudioVolumeForTesting();
                TextView volumeLabel = controller.getAudioVolumeLabelForTesting();
                CheckBox microphone = controller.getMicrophoneEnabledForTesting();
                CheckBox virtualControls = controller.getVirtualControlsVisibleForTesting();
                SeekBar controlOpacity = controller.getVirtualControlOpacityForTesting();
                TextView controlOpacityLabel =
                        controller.getVirtualControlOpacityLabelForTesting();
                assertEquals(
                        activity.getString(R.string.n3ds_settings_screen_layout),
                        layout.getContentDescription().toString());
                assertEquals(
                        activity.getString(R.string.n3ds_settings_performance_profile),
                        performance.getContentDescription().toString());
                assertEquals(
                        activity.getString(R.string.n3ds_settings_audio_enabled),
                        audio.getText().toString());
                assertEquals(
                        activity.getString(R.string.n3ds_settings_microphone_enabled),
                        microphone.getText().toString());
                assertEquals(volume.getId(), volumeLabel.getLabelFor());
                assertEquals(controlOpacity.getId(), controlOpacityLabel.getLabelFor());
                layout.setSelection(2);
                performance.setSelection(0);
                audio.setChecked(false);
                volume.setProgress(40);
                assertEquals(
                        activity.getString(R.string.n3ds_settings_audio_volume, 40),
                        volumeLabel.getText().toString());
                microphone.setChecked(true);
                controlOpacity.setProgress(5);
                assertEquals(
                        activity.getString(R.string.n3ds_settings_control_opacity, 70),
                        controlOpacityLabel.getText().toString());
                virtualControls.setChecked(false);
                assertFalse(controlOpacity.isEnabled());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(200);
            writeScreenshot(context, "n3ds-ux04-settings.png");
            scenario.onActivity(activity -> {
                Nintendo3DsExperienceSettingsDialogController controller =
                        activity.getDialogController();
                AlertDialog globalDialog = controller.getActiveDialogForTesting();
                assertNotNull(globalDialog);
                assertEquals(2, controller.getLayoutSpinnerForTesting()
                        .getSelectedItemPosition());
                assertEquals(0, controller.getPerformanceProfileSpinnerForTesting()
                        .getSelectedItemPosition());
                Button save = button(globalDialog, DialogInterface.BUTTON_POSITIVE);
                assertNotNull(save);
                assertTrue(save.performClick());

                assertEquals(expectedGlobal, activity.getStore().loadGlobal());
                assertEquals(expectedGlobal, activity.getChangedSettings());
                assertEquals(
                        Nintendo3DsExperienceSettingsDialogController.Scope.GLOBAL,
                        activity.getChangedScope());
                assertFalse(activity.wasReset());
                assertEquals(1, activity.getChangeCount());
                assertEquals(0, activity.getPersistenceFailureCount());
                assertFalse(controller.isShowing());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Nintendo3DsExperienceSettingsDialogController controller =
                        activity.getDialogController();
                assertTrue(activity.showGame());
                assertEquals(2, activity.getStyleInvocationCount());
                AlertDialog gameDialog = controller.getActiveDialogForTesting();
                assertNotNull(gameDialog);
                assertEquals(
                        activity.getString(R.string.n3ds_settings_game_title),
                        dialogTitle(activity, gameDialog));
                controller.getLayoutSpinnerForTesting().setSelection(3);
                controller.getPerformanceProfileSpinnerForTesting().setSelection(2);
                controller.getAudioEnabledForTesting().setChecked(true);
                controller.getAudioVolumeForTesting().setProgress(40);
                controller.getMicrophoneEnabledForTesting().setChecked(true);
                controller.getVirtualControlsVisibleForTesting().setChecked(true);
                controller.getVirtualControlOpacityForTesting().setProgress(2);
            });
            scenario.onActivity(activity -> {
                Nintendo3DsExperienceSettingsDialogController controller =
                        activity.getDialogController();
                AlertDialog gameDialog = controller.getActiveDialogForTesting();
                assertNotNull(gameDialog);
                assertEquals(3, controller.getLayoutSpinnerForTesting()
                        .getSelectedItemPosition());
                assertEquals(2, controller.getPerformanceProfileSpinnerForTesting()
                        .getSelectedItemPosition());
                assertTrue(button(gameDialog, DialogInterface.BUTTON_POSITIVE).performClick());

                Nintendo3DsExperienceSettingsOverrides savedOverrides =
                        activity.getStore().loadGameOverrides(
                                Nintendo3DsSettingsTestActivity.PERSISTENT_ID);
                assertEquals(Nintendo3DsScreenLayout.LARGE_BOTTOM,
                        savedOverrides.getScreenLayout());
                assertEquals(
                        Nintendo3DsPerformanceProfile.PERFORMANCE,
                        savedOverrides.getPerformanceProfile());
                assertEquals(Boolean.TRUE, savedOverrides.getAudioEnabled());
                assertNull(savedOverrides.getAudioVolume());
                assertNull(savedOverrides.getMicrophoneEnabled());
                assertEquals(Boolean.TRUE, savedOverrides.getVirtualControlsVisible());
                assertEquals(
                        Integer.valueOf(40),
                        savedOverrides.getVirtualControlOpacityPercent());
                assertEquals(2, activity.getChangeCount());
                assertEquals(
                        Nintendo3DsExperienceSettingsDialogController.Scope.GAME,
                        activity.getChangedScope());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertTrue(activity.showGame());
                assertEquals(3, activity.getStyleInvocationCount());
            });
            scenario.onActivity(activity -> {
                Nintendo3DsExperienceSettingsDialogController controller =
                        activity.getDialogController();
                AlertDialog resetDialog = controller.getActiveDialogForTesting();
                assertNotNull(resetDialog);
                assertTrue(button(resetDialog, DialogInterface.BUTTON_NEUTRAL).performClick());
                assertTrue(activity.getStore().loadGameOverrides(
                        Nintendo3DsSettingsTestActivity.PERSISTENT_ID).isEmpty());
                assertEquals(expectedGlobal, activity.getChangedSettings());
                assertTrue(activity.wasReset());
                assertEquals(3, activity.getChangeCount());
                assertEquals(0, activity.getPersistenceFailureCount());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(3, activity.getDismissCount());
                assertTrue(activity.wasDismissedWithChange());
            });
        }

        int[] resources = {
                R.string.n3ds_settings_global_title,
                R.string.n3ds_settings_game_title,
                R.string.n3ds_settings_screen_layout,
                R.string.n3ds_settings_layout_default,
                R.string.n3ds_settings_layout_side_by_side,
                R.string.n3ds_settings_layout_large_top,
                R.string.n3ds_settings_layout_large_bottom,
                R.string.n3ds_settings_layout_single_top,
                R.string.n3ds_settings_layout_single_bottom,
                R.string.n3ds_settings_performance_profile,
                R.string.n3ds_settings_performance_conservative,
                R.string.n3ds_settings_performance_balanced,
                R.string.n3ds_settings_performance_performance,
                R.string.n3ds_settings_audio_enabled,
                R.string.n3ds_settings_microphone_enabled,
                R.string.n3ds_settings_virtual_controls_visible,
                R.string.n3ds_product_customize_controls,
                R.string.n3ds_control_editor_title,
                R.string.n3ds_control_editor_hint,
                R.string.n3ds_control_editor_portrait,
                R.string.n3ds_control_editor_landscape,
                R.string.n3ds_control_editor_previous_group,
                R.string.n3ds_control_editor_next_group,
                R.string.n3ds_control_editor_group_visible,
                R.string.n3ds_control_editor_saved,
                R.string.n3ds_control_editor_save_failed,
                R.string.n3ds_settings_save,
                R.string.n3ds_settings_reset
        };
        for (String tag : new String[] {
                "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"
        }) {
            Configuration configuration = new Configuration(
                    context.getResources().getConfiguration());
            configuration.setLocales(LocaleList.forLanguageTags(tag));
            Context localized = context.createConfigurationContext(configuration);
            for (int resource : resources) {
                assertFalse(tag + " resource must not be empty",
                        localized.getString(resource).trim().isEmpty());
            }
            assertFalse(tag + " volume resource must not be empty",
                    localized.getString(R.string.n3ds_settings_audio_volume, 50)
                            .trim().isEmpty());
            assertFalse(tag + " control opacity resource must not be empty",
                    localized.getString(R.string.n3ds_settings_control_opacity, 50)
                            .trim().isEmpty());
            assertFalse(tag + " horizontal editor resource must not be empty",
                    localized.getString(R.string.n3ds_control_editor_horizontal, 25)
                            .trim().isEmpty());
            assertFalse(tag + " vertical editor resource must not be empty",
                    localized.getString(R.string.n3ds_control_editor_vertical, -25)
                            .trim().isEmpty());
            assertFalse(tag + " scale editor resource must not be empty",
                    localized.getString(R.string.n3ds_control_editor_scale, 100)
                            .trim().isEmpty());
        }
        assertEquals(
                View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("ar")));
    }

    private static void writeScreenshot(Context context, String name) throws IOException {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .takeScreenshot();
        assertNotNull(screenshot);
        File directory = new File(context.getExternalFilesDir(null), "n3ds-ux04");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File destination = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
        System.out.println("N3DS_UX_CAPTURE " + destination.getAbsolutePath());
    }

    private static String dialogTitle(Context context, AlertDialog dialog) {
        TextView titleView = title(dialog);
        assertNotNull(titleView);
        return titleView.getText().toString();
    }
}
