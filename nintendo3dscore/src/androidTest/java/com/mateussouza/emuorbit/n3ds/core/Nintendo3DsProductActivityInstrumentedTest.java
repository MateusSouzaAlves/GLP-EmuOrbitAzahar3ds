// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.button;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupMenu;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.mateussouza.emuorbit.advance.data.preferences.GamepadVirtualControlsPreferenceStore;
import com.mateussouza.emuorbit.advance.domain.model.EmulatorSystem;
import com.mateussouza.emuorbit.advance.ui.emulation.input.GamepadVirtualControlsCoordinator;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsProductActivityInstrumentedTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final String HASH_A =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String HASH_B =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String PERSISTENT_ID = "n3ds-v1:" + HASH_A + ":" + HASH_B;

    @Test
    public void gamepadChoiceHidesOnlyVirtualControlsAndCanBeReversedFromMenu()
            throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GamepadVirtualControlsPreferenceStore gamepadStore =
                new GamepadVirtualControlsPreferenceStore(context);
        GamepadVirtualControlsCoordinator.Preference priorPreference =
                gamepadStore.load(EmulatorSystem.N3DS);
        Nintendo3DsExperienceSettingsStore settingsStore =
                new Nintendo3DsExperienceSettingsStore(context);
        Nintendo3DsExperienceSettingsOverrides priorOverrides =
                settingsStore.loadGameOverrides(PERSISTENT_ID);
        assertTrue(gamepadStore.save(
                EmulatorSystem.N3DS,
                GamepadVirtualControlsCoordinator.Preference.UNDECIDED));
        assertTrue(settingsStore.saveGameOverrides(
                PERSISTENT_ID,
                new Nintendo3DsExperienceSettingsOverrides(
                        null, null, null, null, null, true, null)));

        Intent intent = new Nintendo3DsProductLaunchRequest(
                core,
                content,
                PERSISTENT_ID,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true).createIntent(context);
        try (ActivityScenario<Nintendo3DsProductActivity> scenario =
                     ActivityScenario.launch(intent)) {
            await(scenario, activity -> activity.getRenderedFrameCountForTesting() >= 20);
            scenario.onActivity(activity -> activity.setExternalGamepadConnectedForTesting(true));
            await(scenario, activity -> activity.getGamepadVirtualControlsDialogForTesting()
                    != null);
            scenario.onActivity(activity -> {
                AlertDialog dialog = activity.getGamepadVirtualControlsDialogForTesting();
                assertNotNull(dialog);
                assertTrue(activity.getVirtualControlsForTesting()
                        .areControlsVisibleForTesting());
                assertTrue(button(dialog, DialogInterface.BUTTON_POSITIVE).performClick());
            });
            await(scenario, activity -> !activity.getVirtualControlsForTesting()
                    .areControlsVisibleForTesting());
            SystemClock.sleep(200);
            writeScreenshot(context, "n3ds-ux04d-gamepad-hidden.png");
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.getSurfaceViewForTesting().getVisibility());
                long routedBefore = activity.getRoutedInputEventCountForTesting();
                SurfaceView surface = activity.getSurfaceViewForTesting();
                dispatch(surface, MotionEvent.ACTION_DOWN,
                        surface.getWidth() / 2.0f, surface.getHeight() * 0.75f);
                dispatch(surface, MotionEvent.ACTION_UP,
                        surface.getWidth() / 2.0f, surface.getHeight() * 0.75f);
                assertTrue(activity.getRoutedInputEventCountForTesting() >= routedBefore + 2);

                assertTrue(activity.getMenuButtonForTesting().performClick());
                PopupMenu popup = activity.getActionsMenuForTesting();
                assertNotNull(popup);
                assertEquals(
                        activity.getString(R.string.n3ds_product_customize_controls),
                        popup.getMenu().findItem(
                                Nintendo3DsProductActivity.MENU_CUSTOMIZE_CONTROLS_ID)
                                .getTitle().toString());
                assertTrue(popup.getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_CUSTOMIZE_CONTROLS_ID,
                        0));
                Nintendo3DsVirtualControlEditorController editor =
                        activity.getControlEditorForTesting();
                assertTrue(editor.isShowing());
                View previewA = activity.getVirtualControlsForTesting()
                        .getControlForTesting(Nintendo3DsButton.A);
                assertTrue(activity.getVirtualControlsForTesting()
                        .areControlsVisibleForTesting());
                assertFalse(previewA.isEnabled());
                assertTrue(editor.getCancelForTesting().performClick());
                assertFalse(activity.getVirtualControlsForTesting()
                        .areControlsVisibleForTesting());

                assertTrue(activity.getMenuButtonForTesting().performClick());
                popup = activity.getActionsMenuForTesting();
                assertNotNull(popup);
                assertEquals(
                        activity.getString(R.string.n3ds_gamepad_controls_show),
                        popup.getMenu().findItem(
                                Nintendo3DsProductActivity.MENU_GAMEPAD_CONTROLS_ID)
                                .getTitle().toString());
                assertTrue(popup.getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_GAMEPAD_CONTROLS_ID,
                        0));
            });
            await(scenario, activity -> activity.getVirtualControlsForTesting()
                    .areControlsVisibleForTesting());
            scenario.onActivity(activity -> {
                View a = activity.getVirtualControlsForTesting()
                        .getControlForTesting(Nintendo3DsButton.A);
                assertTrue(a.isEnabled());
                dispatch(a, MotionEvent.ACTION_DOWN,
                        a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertTrue(a.isPressed());
                dispatch(a, MotionEvent.ACTION_UP,
                        a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertFalse(a.isPressed());

                activity.setExternalGamepadConnectedForTesting(false);
                assertTrue(activity.getVirtualControlsForTesting()
                        .areControlsVisibleForTesting());
                assertTrue(activity.getMenuButtonForTesting().performClick());
                PopupMenu popup = activity.getActionsMenuForTesting();
                assertNotNull(popup);
                assertNull(popup.getMenu().findItem(
                        Nintendo3DsProductActivity.MENU_GAMEPAD_CONTROLS_ID));
                activity.dismissActionsMenuForTesting();
            });

            scenario.recreate();
            await(scenario, activity -> activity.getRenderedFrameCountForTesting() >= 20);
            scenario.onActivity(activity -> activity.setExternalGamepadConnectedForTesting(true));
            scenario.onActivity(activity -> {
                assertNull(activity.getGamepadVirtualControlsDialogForTesting());
                assertTrue(activity.getVirtualControlsForTesting()
                        .areControlsVisibleForTesting());
                assertNull(activity.getProductFailureForTesting());
            });
        } finally {
            assertTrue(gamepadStore.save(EmulatorSystem.N3DS, priorPreference));
            assertTrue(settingsStore.saveGameOverrides(PERSISTENT_ID, priorOverrides));
        }
    }

    @Test
    public void editsPreviewsSavesAndCancelsPerGameControlProfile() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Nintendo3DsVirtualControlProfileStore store =
                new Nintendo3DsVirtualControlProfileStore(context);
        Nintendo3DsVirtualControlProfile prior = store.loadForGame(PERSISTENT_ID);
        assertTrue(store.removeForGame(PERSISTENT_ID));
        Intent intent = new Nintendo3DsProductLaunchRequest(
                core,
                content,
                PERSISTENT_ID,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true).createIntent(context);
        try (ActivityScenario<Nintendo3DsProductActivity> scenario =
                     ActivityScenario.launch(intent)) {
            await(scenario, activity -> activity.getRenderedFrameCountForTesting() >= 20);
            AtomicLong framesBeforeEditor = new AtomicLong();
            scenario.onActivity(activity -> {
                framesBeforeEditor.set(activity.getRenderedFrameCountForTesting());
                assertTrue(activity.getMenuButtonForTesting().performClick());
                PopupMenu popup = activity.getActionsMenuForTesting();
                assertNotNull(popup);
                assertTrue(popup.getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_CUSTOMIZE_CONTROLS_ID,
                        0));
                Nintendo3DsVirtualControlEditorController editor =
                        activity.getControlEditorForTesting();
                assertTrue(editor.isShowing());
                assertTrue(editor.getLandscapeForTesting().performClick());
                assertEquals(
                        Nintendo3DsVirtualControlOrientation.LANDSCAPE,
                        editor.getSessionForTesting().getOrientation());
                assertTrue(editor.getPortraitForTesting().performClick());
                assertTrue(editor.getNextGroupForTesting().performClick());
                assertEquals(
                        Nintendo3DsVirtualControlGroup.DIRECTIONAL,
                        editor.getSessionForTesting().getSelectedGroup());
                assertTrue(editor.getGroupVisibleForTesting().isChecked());
                editor.getGroupVisibleForTesting().performClick();
                assertFalse(editor.getGroupVisibleForTesting().isChecked());
            });
            await(scenario, activity -> activity.getVirtualControlsForTesting()
                    .getGroupForTesting(Nintendo3DsVirtualControlGroup.DIRECTIONAL)
                    .getVisibility() == View.INVISIBLE);
            SystemClock.sleep(300);
            writeScreenshot(context, "n3ds-ux04b2-editor.png");
            scenario.onActivity(activity -> {
                assertTrue(activity.getRenderedFrameCountForTesting() > framesBeforeEditor.get());
                assertTrue(activity.getControlEditorForTesting()
                        .getSaveForTesting().performClick());
                assertFalse(activity.getControlEditorForTesting().isShowing());
                assertEquals(
                        R.string.n3ds_control_editor_saved,
                        activity.getLastFeedbackResourceForTesting());
            });
            Nintendo3DsVirtualControlProfile saved = store.loadForGame(PERSISTENT_ID);
            assertNotNull(saved);
            assertFalse(saved.getTransform(
                    Nintendo3DsVirtualControlOrientation.PORTRAIT,
                    Nintendo3DsVirtualControlGroup.DIRECTIONAL).isVisible());

            scenario.recreate();
            await(scenario, activity -> activity.getRenderedFrameCountForTesting() >= 20);
            await(scenario, activity -> activity.getVirtualControlsForTesting()
                    .getGroupForTesting(Nintendo3DsVirtualControlGroup.DIRECTIONAL)
                    .getVisibility() == View.INVISIBLE);
            scenario.onActivity(activity -> {
                assertTrue(activity.getMenuButtonForTesting().performClick());
                assertTrue(activity.getActionsMenuForTesting().getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_CUSTOMIZE_CONTROLS_ID,
                        0));
                Nintendo3DsVirtualControlEditorController editor =
                        activity.getControlEditorForTesting();
                assertTrue(editor.getResetForTesting().performClick());
            });
            await(scenario, activity -> activity.getVirtualControlsForTesting()
                    .getGroupForTesting(Nintendo3DsVirtualControlGroup.DIRECTIONAL)
                    .getVisibility() == View.VISIBLE);
            scenario.onActivity(activity -> assertTrue(activity.getControlEditorForTesting()
                    .getCancelForTesting().performClick()));
            await(scenario, activity -> activity.getVirtualControlsForTesting()
                    .getGroupForTesting(Nintendo3DsVirtualControlGroup.DIRECTIONAL)
                    .getVisibility() == View.INVISIBLE);
        } finally {
            if (prior == null) {
                assertTrue(store.removeForGame(PERSISTENT_ID));
            } else {
                assertTrue(store.saveForGame(PERSISTENT_ID, prior));
            }
        }
    }

    @Test
    public void preservesEditorAcrossRotationAndRoutesConcurrentTouchAndQuickSwap()
            throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Nintendo3DsVirtualControlProfileStore store =
                new Nintendo3DsVirtualControlProfileStore(context);
        Nintendo3DsVirtualControlProfile prior = store.loadForGame(PERSISTENT_ID);
        assertTrue(store.removeForGame(PERSISTENT_ID));
        Intent intent = new Nintendo3DsProductLaunchRequest(
                core,
                content,
                PERSISTENT_ID,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true).createIntent(context);
        try (ActivityScenario<Nintendo3DsProductActivity> scenario =
                     ActivityScenario.launch(intent)) {
            await(scenario, activity -> activity.getRenderedFrameCountForTesting() >= 20);
            scenario.onActivity(activity -> {
                if (activity.getResources().getConfiguration().orientation
                        != Configuration.ORIENTATION_PORTRAIT) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            });
            await(scenario, activity ->
                    activity.getResources().getConfiguration().orientation
                            == Configuration.ORIENTATION_PORTRAIT
                            && activity.getRenderedFrameCountForTesting() >= 20);
            scenario.onActivity(activity -> {
                assertTrue(activity.getMenuButtonForTesting().performClick());
                assertTrue(activity.getActionsMenuForTesting().getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_CUSTOMIZE_CONTROLS_ID,
                        0));
                Nintendo3DsVirtualControlEditorController editor =
                        activity.getControlEditorForTesting();
                assertTrue(editor.getNextGroupForTesting().performClick());
                editor.getSessionForTesting().setHorizontalOffset(0.5f);
                editor.getSessionForTesting().setScale(1.1f);
                assertTrue(editor.getPortraitForTesting().performClick());
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            });

            await(scenario, activity ->
                    activity.getResources().getConfiguration().orientation
                            == Configuration.ORIENTATION_LANDSCAPE
                            && activity.getRenderedFrameCountForTesting() >= 20
                            && activity.getControlEditorForTesting().isShowing());
            scenario.onActivity(activity -> {
                Nintendo3DsVirtualControlEditorSession restored =
                        activity.getControlEditorForTesting().getSessionForTesting();
                assertEquals(
                        Nintendo3DsVirtualControlGroup.DIRECTIONAL,
                        restored.getSelectedGroup());
                assertEquals(
                        Nintendo3DsVirtualControlOrientation.PORTRAIT,
                        restored.getOrientation());
                Nintendo3DsVirtualControlTransform transform = restored.getSelectedTransform();
                assertEquals(0.5f, transform.getNormalizedOffsetX(), 0.0f);
                assertEquals(1.1f, transform.getScale(), 0.0f);
                assertGroupsWithinBounds(activity.getVirtualControlsForTesting());
                assertTrue(activity.getControlEditorForTesting()
                        .getCancelForTesting().performClick());
            });
            await(scenario, activity -> !activity.getControlEditorForTesting().isShowing());

            AtomicLong framesBeforeInput = new AtomicLong();
            scenario.onActivity(activity -> {
                Nintendo3DsVirtualControlsOverlay overlay =
                        activity.getVirtualControlsForTesting();
                assertGroupsWithinBounds(overlay);
                View a = overlay.getControlForTesting(Nintendo3DsButton.A);
                View quickSwap = overlay.getControlForTesting(
                        Nintendo3DsButton.HOME_SWAP_SCREENS);
                View lowerScreenSurface = activity.getSurfaceViewForTesting();
                assertTrue(a.isEnabled());
                assertTrue(quickSwap.isEnabled());
                long routedBefore = activity.getRoutedInputEventCountForTesting();

                dispatch(a, MotionEvent.ACTION_DOWN,
                        a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertTrue(a.isPressed());
                dispatch(lowerScreenSurface, MotionEvent.ACTION_DOWN,
                        lowerScreenSurface.getWidth() / 2.0f,
                        lowerScreenSurface.getHeight() * 0.75f);
                dispatch(lowerScreenSurface, MotionEvent.ACTION_UP,
                        lowerScreenSurface.getWidth() / 2.0f,
                        lowerScreenSurface.getHeight() * 0.75f);
                assertTrue("A must remain held while lower-screen touch ends", a.isPressed());
                dispatch(quickSwap, MotionEvent.ACTION_DOWN,
                        quickSwap.getWidth() / 2.0f, quickSwap.getHeight() / 2.0f);
                dispatch(quickSwap, MotionEvent.ACTION_UP,
                        quickSwap.getWidth() / 2.0f, quickSwap.getHeight() / 2.0f);
                dispatch(a, MotionEvent.ACTION_UP,
                        a.getWidth() / 2.0f, a.getHeight() / 2.0f);

                assertFalse(a.isPressed());
                assertTrue(activity.getRoutedInputEventCountForTesting() >= routedBefore + 6);
                framesBeforeInput.set(activity.getRenderedFrameCountForTesting());
            });
            await(scenario, activity ->
                    activity.getRenderedFrameCountForTesting() > framesBeforeInput.get() + 20);
            writeScreenshot(context, "n3ds-ux04c-landscape.png");
        } finally {
            if (prior == null) {
                assertTrue(store.removeForGame(PERSISTENT_ID));
            } else {
                assertTrue(store.saveForGame(PERSISTENT_ID, prior));
            }
        }
    }

    @Test
    public void appliesPerGameControlLayoutAndRestoresItAfterRecreation() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Nintendo3DsVirtualControlProfileStore store =
                new Nintendo3DsVirtualControlProfileStore(context);
        Nintendo3DsVirtualControlProfile prior = store.loadForGame(PERSISTENT_ID);
        Nintendo3DsVirtualControlTransform hiddenActions =
                new Nintendo3DsVirtualControlTransform(-0.45f, -0.35f, 1.2f, false);
        Nintendo3DsVirtualControlProfile profile =
                Nintendo3DsVirtualControlProfile.identity()
                        .withTransform(
                                Nintendo3DsVirtualControlOrientation.PORTRAIT,
                                Nintendo3DsVirtualControlGroup.ACTIONS,
                                hiddenActions)
                        .withTransform(
                                Nintendo3DsVirtualControlOrientation.LANDSCAPE,
                                Nintendo3DsVirtualControlGroup.ACTIONS,
                                hiddenActions);
        assertTrue(store.saveForGame(PERSISTENT_ID, profile));

        Intent intent = new Nintendo3DsProductLaunchRequest(
                core,
                content,
                PERSISTENT_ID,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true).createIntent(context);
        try (ActivityScenario<Nintendo3DsProductActivity> scenario =
                     ActivityScenario.launch(intent)) {
            await(scenario, activity ->
                    activity.getRenderedFrameCountForTesting() >= 20
                            && activity.getVirtualControlsForTesting()
                                    .getGroupForTesting(
                                            Nintendo3DsVirtualControlGroup.ACTIONS)
                                    .getVisibility() == View.INVISIBLE);
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                View actions = activity.getVirtualControlsForTesting().getGroupForTesting(
                        Nintendo3DsVirtualControlGroup.ACTIONS);
                assertEquals(1.2f, actions.getScaleX(), 0.001f);
                assertTrue(activity.getVirtualControlsForTesting().getGroupForTesting(
                        Nintendo3DsVirtualControlGroup.DIRECTIONAL).isShown());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(200);
            writeScreenshot(context, "n3ds-ux04b1-profile.png");

            scenario.recreate();
            await(scenario, activity ->
                    activity.getRenderedFrameCountForTesting() >= 20
                            && activity.getVirtualControlsForTesting()
                                    .getGroupForTesting(
                                            Nintendo3DsVirtualControlGroup.ACTIONS)
                                    .getVisibility() == View.INVISIBLE);
            scenario.onActivity(activity -> assertEquals(
                    1.2f,
                    activity.getVirtualControlsForTesting()
                            .getGroupForTesting(Nintendo3DsVirtualControlGroup.ACTIONS)
                            .getScaleX(),
                    0.001f));
        } finally {
            if (prior == null) {
                assertTrue(store.removeForGame(PERSISTENT_ID));
            } else {
                assertTrue(store.saveForGame(PERSISTENT_ID, prior));
            }
        }
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

    private static void assertGroupsWithinBounds(Nintendo3DsVirtualControlsOverlay overlay) {
        assertTrue(overlay.getWidth() > 0);
        assertTrue(overlay.getHeight() > 0);
        for (Nintendo3DsVirtualControlGroup group : Nintendo3DsVirtualControlGroup.values()) {
            View view = overlay.getGroupForTesting(group);
            assertNotNull(group.name(), view);
            if (view.getVisibility() != View.VISIBLE) {
                continue;
            }
            float visualLeft = view.getX() + view.getPivotX() * (1.0f - view.getScaleX());
            float visualTop = view.getY() + view.getPivotY() * (1.0f - view.getScaleY());
            float visualRight = visualLeft + view.getWidth() * view.getScaleX();
            float visualBottom = visualTop + view.getHeight() * view.getScaleY();
            assertTrue(group.name() + " left=" + visualLeft, visualLeft >= -1.0f);
            assertTrue(group.name() + " top=" + visualTop, visualTop >= -1.0f);
            assertTrue(group.name() + " right=" + visualRight,
                    visualRight <= overlay.getWidth() + 1.0f);
            assertTrue(group.name() + " bottom=" + visualBottom,
                    visualBottom <= overlay.getHeight() + 1.0f);
        }
    }

    private static void dispatch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            assertTrue(view.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    @Test
    public void launchesAppliesSettingsStylesDialogsAndResumesFrames() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Nintendo3DsExperienceSettingsStore store =
                new Nintendo3DsExperienceSettingsStore(context);
        Nintendo3DsExperienceSettingsOverrides prior = store.loadGameOverrides(PERSISTENT_ID);
        Nintendo3DsExperienceSettings global = store.loadGlobal();
        Nintendo3DsExperienceSettings initial = new Nintendo3DsExperienceSettings(
                Nintendo3DsScreenLayout.LARGE_TOP,
                false,
                0.4f,
                false);
        assertTrue(store.saveGameOverrides(
                PERSISTENT_ID,
                Nintendo3DsExperienceSettingsOverrides.between(global, initial)));

        Intent intent = new Nintendo3DsProductLaunchRequest(
                core,
                content,
                PERSISTENT_ID,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true).createIntent(context);
        try (ActivityScenario<Nintendo3DsProductActivity> scenario =
                     ActivityScenario.launch(intent)) {
            await(scenario, activity -> activity.getRenderedFrameCountForTesting() >= 30);
            AtomicLong framesBeforeManualPause = new AtomicLong();
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                assertNotNull(activity.getControllerForTesting());
                assertEquals(
                        Nintendo3DsProductUiState.SessionState.RUNNING,
                        activity.getUiStateForTesting().getSessionState());
                assertEquals(
                        activity.getString(R.string.n3ds_product_running),
                        activity.getStatusViewForTesting().getText().toString());
                assertTrue(activity.getBackButtonForTesting().isEnabled());
                assertTrue(activity.getPauseButtonForTesting().isEnabled());
                assertTrue(activity.getAudioButtonForTesting().isEnabled());
                assertTrue(activity.getSpeedButtonForTesting().isEnabled());
                assertTrue(activity.getOrientationButtonForTesting().isEnabled());
                assertTrue(activity.getMenuButtonForTesting().isEnabled());
                assertTrue(activity.getCurrentFrameWidthForTesting() > 0);
                assertTrue(activity.getCurrentFrameHeightForTesting() > 0);
                assertTrue(
                        "A geometria real do frame 3DS deve substituir o fallback vertical",
                        activity.getCurrentFrameWidthForTesting()
                                >= activity.getCurrentFrameHeightForTesting());
                assertEquals(Nintendo3DsScreenLayout.LARGE_TOP,
                        activity.getControllerForTesting().getScreenLayout());
                assertEquals(
                        Nintendo3DsPerformanceProfile.BALANCED,
                        activity.getControllerForTesting().getPerformanceProfile());
                assertFalse(activity.getUiStateForTesting().isAudioEnabled());
                assertTrue(activity.getAudioButtonForTesting().performClick());
                assertTrue(activity.getUiStateForTesting().isAudioEnabled());
                assertTrue(activity.getSpeedButtonForTesting().performClick());
                assertEquals(2, activity.getUiStateForTesting().getSpeedMultiplier());
                assertTrue(activity.getPauseButtonForTesting().performClick());
                framesBeforeManualPause.set(activity.getRenderedFrameCountForTesting());
            });

            await(scenario, activity -> activity.getUiStateForTesting().isPaused());
            SystemClock.sleep(250);
            scenario.onActivity(activity -> {
                assertEquals(
                        Nintendo3DsProductUiState.SessionState.PAUSED,
                        activity.getUiStateForTesting().getSessionState());
                assertEquals(
                        activity.getString(R.string.n3ds_product_paused),
                        activity.getStatusViewForTesting().getText().toString());
                assertEquals(
                        framesBeforeManualPause.get(),
                        activity.getRenderedFrameCountForTesting());
                assertTrue(activity.getPauseButtonForTesting().performClick());
            });
            await(scenario, activity ->
                    !activity.getUiStateForTesting().isPaused()
                            && activity.getRenderedFrameCountForTesting()
                            > framesBeforeManualPause.get() + 20);
            scenario.onActivity(activity -> {
                assertEquals(
                        Nintendo3DsProductUiState.SessionState.RUNNING,
                        activity.getUiStateForTesting().getSessionState());
                openSettingsFromMenu(activity);
            });

            await(scenario, activity -> activity.getSettingsDialogForTesting().isShowing());
            AtomicLong framesAtDialog = new AtomicLong();
            scenario.onActivity(activity -> {
                framesAtDialog.set(activity.getRenderedFrameCountForTesting());
                Nintendo3DsExperienceSettingsDialogController dialogController =
                        activity.getSettingsDialogForTesting();
                AlertDialog dialog = dialogController.getActiveDialogForTesting();
                assertNotNull(dialog);
                assertEquals(0.82f, dialog.getWindow().getAttributes().dimAmount, 0.001f);
                int maximumWidth = Math.round(
                        560 * activity.getResources().getDisplayMetrics().density);
                WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
                assertTrue(attributes.width > 0 && attributes.width <= maximumWidth);
                dialogController.getLayoutSpinnerForTesting().setSelection(5);
                dialogController.getPerformanceProfileSpinnerForTesting().setSelection(2);
                dialogController.getAudioEnabledForTesting().setChecked(true);
                dialogController.getAudioVolumeForTesting().setProgress(70);
                dialogController.getMicrophoneEnabledForTesting().setChecked(false);
                dialogController.getVirtualControlOpacityForTesting().setProgress(6);
                dialogController.getVirtualControlsVisibleForTesting().setChecked(false);
                assertTrue(button(dialog, DialogInterface.BUTTON_POSITIVE).performClick());
            });

            await(scenario, activity ->
                    activity.getControllerForTesting().getScreenLayout()
                            == Nintendo3DsScreenLayout.SINGLE_BOTTOM
                            && activity.getControllerForTesting().getPerformanceProfile()
                            == Nintendo3DsPerformanceProfile.PERFORMANCE
                            && activity.getRenderedFrameCountForTesting()
                            > framesAtDialog.get() + 20);
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                Nintendo3DsExperienceSettings stored = store.loadGameOverrides(PERSISTENT_ID)
                        .resolve(store.loadGlobal());
                assertEquals(Nintendo3DsScreenLayout.SINGLE_BOTTOM, stored.getScreenLayout());
                assertEquals(
                        Nintendo3DsPerformanceProfile.PERFORMANCE,
                        stored.getPerformanceProfile());
                assertTrue(stored.isAudioEnabled());
                assertEquals(0.7f, stored.getAudioVolume(), 0.001f);
                assertFalse(stored.isMicrophoneEnabled());
                assertFalse(stored.areVirtualControlsVisible());
                assertEquals(80, stored.getVirtualControlOpacityPercent());
                assertFalse(activity.getVirtualControlsForTesting()
                        .areControlsVisibleForTesting());
                assertEquals(80, activity.getVirtualControlsForTesting()
                        .getControlsOpacityPercentForTesting());
                assertTrue(activity.onKeyDown(
                        KeyEvent.KEYCODE_BUTTON_A,
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)));
                assertTrue(activity.onKeyUp(
                        KeyEvent.KEYCODE_BUTTON_A,
                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)));
                openSettingsFromMenu(activity);
            });

            await(scenario, activity -> activity.getSettingsDialogForTesting().isShowing());
            AtomicLong framesBeforeCancel = new AtomicLong();
            scenario.onActivity(activity -> {
                framesBeforeCancel.set(activity.getRenderedFrameCountForTesting());
                AlertDialog dialog = activity.getSettingsDialogForTesting()
                        .getActiveDialogForTesting();
                assertNotNull(dialog);
                assertTrue(button(dialog, DialogInterface.BUTTON_NEGATIVE).performClick());
            });
            await(scenario, activity ->
                    activity.getRenderedFrameCountForTesting() > framesBeforeCancel.get() + 20);
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                assertTrue(activity.getRoutedInputEventCountForTesting() >= 2);
                assertFalse(activity.getSettingsDialogForTesting().isShowing());
            });

            scenario.recreate();
            await(scenario, activity -> activity.getRenderedFrameCountForTesting() >= 20);
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                assertEquals(Nintendo3DsScreenLayout.SINGLE_BOTTOM,
                        activity.getControllerForTesting().getScreenLayout());
                assertEquals(
                        Nintendo3DsPerformanceProfile.PERFORMANCE,
                        activity.getControllerForTesting().getPerformanceProfile());
                assertFalse(activity.getVirtualControlsForTesting()
                        .areControlsVisibleForTesting());
                assertEquals(80, activity.getVirtualControlsForTesting()
                        .getControlsOpacityPercentForTesting());
            });
        } finally {
            if (prior.isEmpty()) {
                assertTrue(store.removeGameOverrides(PERSISTENT_ID));
            } else {
                assertTrue(store.saveGameOverrides(PERSISTENT_ID, prior));
            }
        }
    }

    private static void await(
            ActivityScenario<Nintendo3DsProductActivity> scenario,
            Condition condition) {
        long deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS;
        AtomicBoolean complete = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> complete.set(condition.evaluate(activity)));
            if (complete.get()) {
                return;
            }
            SystemClock.sleep(50);
        }
        throw new AssertionError("Timed out waiting for the Nintendo 3DS product host");
    }

    private static void openSettingsFromMenu(Nintendo3DsProductActivity activity) {
        assertTrue(activity.getMenuButtonForTesting().performClick());
        PopupMenu popup = activity.getActionsMenuForTesting();
        assertNotNull(popup);
        assertTrue(popup.getMenu().performIdentifierAction(
                Nintendo3DsProductActivity.MENU_SETTINGS_ID,
                0));
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate(Nintendo3DsProductActivity activity);
    }
}
