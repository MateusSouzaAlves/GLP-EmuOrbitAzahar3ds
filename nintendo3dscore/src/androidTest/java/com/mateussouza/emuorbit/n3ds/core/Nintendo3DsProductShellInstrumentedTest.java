// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.button;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.message;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsProductShellInstrumentedTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final String HASH_A =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String HASH_B =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String PERSISTENT_ID = "n3ds-v1:" + HASH_A + ":" + HASH_B;

    @Test
    public void rendersOnlySupportedProductActionsAndCapturesVisualEvidence() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
            scenario.onActivity(activity -> {
                assertEquals(
                        Configuration.ORIENTATION_LANDSCAPE,
                        activity.getResources().getConfiguration().orientation);
                assertNull(activity.getActionBar());
                assertEquals("Mars3D", activity.getTitleViewForTesting().getText().toString());
                assertEquals(
                        activity.getString(R.string.n3ds_product_running),
                        activity.getStatusViewForTesting().getText().toString());
                assertToolbarAction(activity, activity.getBackButtonForTesting(), true);
                assertToolbarAction(activity, activity.getPauseButtonForTesting(), true);
                assertToolbarAction(activity, activity.getSpeedButtonForTesting(), true);
                assertToolbarAction(activity, activity.getOrientationButtonForTesting(), true);
                assertToolbarAction(activity, activity.getMenuButtonForTesting(), true);
                assertNull(activity.getProductShellForTesting().getBackground());
                assertEquals(
                        View.GONE,
                        activity.getActionsMenuPanelForTesting().getVisibility());
                assertEquals(
                        activity.getActionsMenuPanelForTesting(),
                        activity.getAudioButtonForTesting().getParent());
                assertNotNull(activity.getVirtualControlsForTesting()
                        .getControlForTesting(Nintendo3DsButton.ZL));
                assertNotNull(activity.getVirtualControlsForTesting()
                        .getControlForTesting(Nintendo3DsButton.ZR));
                assertFalse(activity.getUiStateForTesting().isPaused());
                assertTrue(activity.getMenuButtonForTesting().performClick());
            });
            await(scenario, activity -> {
                GridLayout panel = activity.getActionsMenuPanelForTesting();
                if (!panel.isShown() || panel.getChildCount() != 6) {
                    return false;
                }
                int minimum = Math.round(
                        48 * activity.getResources().getDisplayMetrics().density);
                for (int index = 0; index < panel.getChildCount(); index++) {
                    View child = panel.getChildAt(index);
                    if (child.getWidth() < minimum || child.getHeight() < minimum) {
                        return false;
                    }
                }
                return true;
            });
            scenario.onActivity(activity -> {
                PopupMenu popup = activity.getActionsMenuForTesting();
                assertNotNull(popup);
                assertEquals(6, popup.getMenu().size());
                assertTrue(activity.getActionsMenuPanelForTesting().isShown());
                assertEquals(6, activity.getActionsMenuPanelForTesting().getChildCount());
                assertEquals(
                        Nintendo3DsProductActivity.MENU_REPORT_ID,
                        popup.getMenu().getItem(0).getItemId());
                assertEquals(
                        Nintendo3DsProductActivity.MENU_SETTINGS_ID,
                        popup.getMenu().getItem(1).getItemId());
                assertEquals(
                        Nintendo3DsProductActivity.MENU_CUSTOMIZE_CONTROLS_ID,
                        popup.getMenu().getItem(2).getItemId());
                assertEquals(
                        Nintendo3DsProductActivity.MENU_SHARE_ID,
                        popup.getMenu().getItem(3).getItemId());
                assertEquals(
                        Nintendo3DsProductActivity.MENU_AUDIO_ID,
                        popup.getMenu().getItem(4).getItemId());
                assertEquals(
                        Nintendo3DsProductActivity.MENU_RESET_ID,
                        popup.getMenu().getItem(5).getItemId());
                assertIconAction(
                        activity,
                        Nintendo3DsProductActivity.MENU_REPORT_ID);
                assertIconAction(
                        activity,
                        Nintendo3DsProductActivity.MENU_SETTINGS_ID);
                assertIconAction(
                        activity,
                        Nintendo3DsProductActivity.MENU_CUSTOMIZE_CONTROLS_ID);
                assertIconAction(
                        activity,
                        Nintendo3DsProductActivity.MENU_SHARE_ID);
                assertIconAction(
                        activity,
                        Nintendo3DsProductActivity.MENU_AUDIO_ID);
                assertIconAction(
                        activity,
                        Nintendo3DsProductActivity.MENU_RESET_ID);
            });
            SystemClock.sleep(250);
            writeScreenshot(context, "n3ds-ux02-menu.png");
            scenario.onActivity(activity -> {
                PopupMenu popup = activity.getActionsMenuForTesting();
                assertNotNull(popup);
                boolean audioEnabled = activity.getUiStateForTesting().isAudioEnabled();
                assertTrue(popup.getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_AUDIO_ID,
                        0));
                assertFalse(audioEnabled == activity.getUiStateForTesting().isAudioEnabled());
                assertNull(activity.getActionsMenuForTesting());
            });
            SystemClock.sleep(600);
            writeScreenshot(context, "n3ds-ux02-shell.png");
        }
    }

    @Test
    public void resetAndAndroidBackRequireConfirmationAndKeepSessionHealthy() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
            AtomicLong openedBeforeReset = new AtomicLong();
            AtomicLong framesBeforeReset = new AtomicLong();
            scenario.onActivity(activity -> {
                openedBeforeReset.set(activity.getControllerForTesting().getOpenedSessionCount());
                framesBeforeReset.set(activity.getRenderedFrameCountForTesting());
                assertTrue(activity.getMenuButtonForTesting().performClick());
                PopupMenu popup = activity.getActionsMenuForTesting();
                assertNotNull(popup);
                assertTrue(popup.getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_RESET_ID,
                        0));
                AlertDialog reset = activity.getProductDialogForTesting();
                assertNotNull(reset);
                assertTrue(reset.isShowing());
                assertTrue(button(reset, DialogInterface.BUTTON_POSITIVE).performClick());
            });

            await(scenario, activity ->
                    activity.getUiStateForTesting().getSessionState()
                                    == Nintendo3DsProductUiState.SessionState.RUNNING
                            && activity.getControllerForTesting().getOpenedSessionCount()
                                    > openedBeforeReset.get()
                            && activity.getRenderedFrameCountForTesting()
                                    > framesBeforeReset.get() + 20);
            AtomicLong framesBeforeExitCancel = new AtomicLong();
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                framesBeforeExitCancel.set(activity.getRenderedFrameCountForTesting());
                activity.getOnBackPressedDispatcher().onBackPressed();
                AlertDialog exit = activity.getProductDialogForTesting();
                assertNotNull(exit);
                assertTrue(exit.isShowing());
                assertTrue(button(exit, DialogInterface.BUTTON_NEGATIVE).performClick());
                assertFalse(activity.isFinishing());
            });
            await(scenario, activity ->
                    activity.getRenderedFrameCountForTesting()
                            > framesBeforeExitCancel.get() + 20);
        }
    }

    @Test
    public void supportedShellAndMenuSurviveLandscapeRecreation() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
            AtomicBoolean restoredAudioEnabled = new AtomicBoolean();
            AtomicInteger restoredSpeedMultiplier = new AtomicInteger();
            scenario.onActivity(activity -> {
                assertTrue(activity.getAudioButtonForTesting().performClick());
                assertTrue(activity.getSpeedButtonForTesting().performClick());
                assertTrue(activity.getSpeedButtonForTesting().performClick());
                restoredAudioEnabled.set(activity.getUiStateForTesting().isAudioEnabled());
                restoredSpeedMultiplier.set(
                        activity.getUiStateForTesting().getSpeedMultiplier());
                assertTrue(activity.getPauseButtonForTesting().performClick());
                assertTrue(activity.getUiStateForTesting().isPaused());
                assertTrue(activity.getOrientationButtonForTesting().performClick());
            });
            await(scenario, activity ->
                    activity.getResources().getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE
                            && activity.getControllerForTesting() != null
                            && activity.getUiStateForTesting().isPaused());
            scenario.onActivity(activity -> {
                assertEquals("Mars3D", activity.getTitleViewForTesting().getText().toString());
                assertEquals(
                        restoredAudioEnabled.get(),
                        activity.getUiStateForTesting().isAudioEnabled());
                assertEquals(
                        restoredSpeedMultiplier.get(),
                        activity.getUiStateForTesting().getSpeedMultiplier());
                assertToolbarAction(activity, activity.getBackButtonForTesting(), true);
                assertToolbarAction(activity, activity.getPauseButtonForTesting(), true);
                assertToolbarAction(activity, activity.getSpeedButtonForTesting(), true);
                assertToolbarAction(activity, activity.getOrientationButtonForTesting(), true);
                assertToolbarAction(activity, activity.getMenuButtonForTesting(), true);
                View up = activity.getVirtualControlsForTesting()
                        .getControlForTesting(Nintendo3DsButton.UP);
                View leftShoulder = activity.getVirtualControlsForTesting()
                        .getControlForTesting(Nintendo3DsButton.L);
                View leftTrigger = activity.getVirtualControlsForTesting()
                        .getControlForTesting(Nintendo3DsButton.ZL);
                assertNoOverlap(activity.getProductShellForTesting(), leftShoulder);
                assertNoOverlap(activity.getProductShellForTesting(), leftTrigger);
                assertNoOverlap(up, leftShoulder);
                assertNoOverlap(up, leftTrigger);
                assertTrue(activity.getMenuButtonForTesting().performClick());
                PopupMenu popup = activity.getActionsMenuForTesting();
                assertNotNull(popup);
                assertEquals(6, popup.getMenu().size());
            });
            SystemClock.sleep(250);
            writeScreenshot(context, "n3ds-ux02-menu-landscape.png");
            scenario.onActivity(activity -> {
                activity.dismissActionsMenuForTesting();
                assertEquals(
                        View.GONE,
                        activity.getActionsMenuPanelForTesting().getVisibility());
                assertTrue(activity.getPauseButtonForTesting().performClick());
            });
            await(scenario, activity ->
                    !activity.getUiStateForTesting().isPaused()
                            && activity.getRenderedFrameCountForTesting() >= 20);
            SystemClock.sleep(600);
            writeScreenshot(context, "n3ds-ux02-shell-landscape.png");
        }
    }

    @Test
    public void capturesVulkanFrameAndCancelsSanitizedReportWithoutStoppingSession()
            throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
            AtomicReference<Bitmap> captured = new AtomicReference<>();
            AtomicInteger captureFailure = new AtomicInteger(Integer.MIN_VALUE);
            scenario.onActivity(activity -> activity.capturePresentedFrameForTesting(
                    new Nintendo3DsSurfaceFrameCapture.Callback() {
                        @Override
                        public void onCaptured(Bitmap bitmap) {
                            captured.set(bitmap);
                        }

                        @Override
                        public void onFailure(int result) {
                            captureFailure.set(result);
                        }
                    }));
            await(scenario, activity ->
                    captured.get() != null || captureFailure.get() != Integer.MIN_VALUE);
            assertEquals(Integer.MIN_VALUE, captureFailure.get());
            Bitmap frame = captured.get();
            assertNotNull(frame);
            assertTrue(frame.getWidth() > 0);
            assertTrue(frame.getHeight() > 0);
            assertTrue(Math.max(frame.getWidth(), frame.getHeight())
                    <= Nintendo3DsSurfaceFrameCapture.MAXIMUM_EDGE_PIXELS);
            writeBitmap(context, "n3ds-ux03-vulkan-frame.png", frame);
            frame.recycle();

            AtomicLong framesBeforeReport = new AtomicLong();
            AtomicLong openedBeforeReport = new AtomicLong();
            AtomicLong closedBeforeReport = new AtomicLong();
            scenario.onActivity(activity -> {
                framesBeforeReport.set(activity.getRenderedFrameCountForTesting());
                openedBeforeReport.set(
                        activity.getControllerForTesting().getOpenedSessionCount());
                closedBeforeReport.set(
                        activity.getControllerForTesting().getClosedSessionCount());
                assertTrue(activity.getMenuButtonForTesting().performClick());
                assertTrue(activity.getActionsMenuForTesting().getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_REPORT_ID,
                        0));
                AlertDialog report = activity.getReportDialogForTesting();
                assertNotNull(report);
                assertTrue(report.isShowing());
                assertNotNull(activity.getReportInputForTesting());
                assertTrue(activity.getUiStateForTesting().isPaused());
            });
            await(scenario, activity ->
                    activity.getReportDialogForTesting() != null
                            && button(
                                    activity.getReportDialogForTesting(),
                                    DialogInterface.BUTTON_POSITIVE) != null
                            && button(
                                    activity.getReportDialogForTesting(),
                                    DialogInterface.BUTTON_POSITIVE).hasOnClickListeners());
            SystemClock.sleep(300);
            writeScreenshot(context, "n3ds-ux03-report.png");
            scenario.onActivity(activity -> {
                AlertDialog report = activity.getReportDialogForTesting();
                assertNotNull(report);
                activity.getReportInputForTesting().setText("");
                assertTrue(button(report, DialogInterface.BUTTON_POSITIVE).performClick());
                assertTrue(report.isShowing());
                assertTrue(activity.didReportValidationFailForTesting());
                assertTrue(button(report, DialogInterface.BUTTON_NEGATIVE).performClick());
            });
            await(scenario, activity ->
                    !activity.getUiStateForTesting().isPaused()
                            && activity.getRenderedFrameCountForTesting()
                            > framesBeforeReport.get() + 20);
            scenario.onActivity(activity -> {
                assertEquals(
                        "Relatar um problema não pode reabrir a ROM",
                        openedBeforeReport.get(),
                        activity.getControllerForTesting().getOpenedSessionCount());
                assertEquals(
                        "Relatar um problema não pode fechar a sessão nativa",
                        closedBeforeReport.get(),
                        activity.getControllerForTesting().getClosedSessionCount());
            });

            scenario.onActivity(activity ->
                    assertTrue(activity.getOrientationButtonForTesting().performClick()));
            await(scenario, activity ->
                    activity.getResources().getConfiguration().orientation
                            == Configuration.ORIENTATION_LANDSCAPE);
            scenario.onActivity(activity -> {
                assertTrue(activity.getMenuButtonForTesting().performClick());
                assertTrue(activity.getActionsMenuForTesting().getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_REPORT_ID,
                        0));
            });
            await(scenario, activity ->
                    activity.getReportDialogForTesting() != null
                            && button(
                                    activity.getReportDialogForTesting(),
                                    DialogInterface.BUTTON_POSITIVE) != null
                            && button(
                                    activity.getReportDialogForTesting(),
                                    DialogInterface.BUTTON_POSITIVE).hasOnClickListeners());
            scenario.onActivity(activity -> activity.getReportInputForTesting().setText(
                    "Rascunho de teste sem dados privados"));
            scenario.recreate();
            await(scenario, activity ->
                    activity.getReportDialogForTesting() != null
                            && activity.getReportDialogForTesting().isShowing()
                            && activity.getReportInputForTesting() != null
                            && button(
                                    activity.getReportDialogForTesting(),
                                    DialogInterface.BUTTON_POSITIVE) != null
                            && activity.getUiStateForTesting().isPaused());
            SystemClock.sleep(300);
            writeScreenshot(context, "n3ds-ux03-report-landscape.png");
            scenario.onActivity(activity -> {
                AlertDialog report = activity.getReportDialogForTesting();
                assertNotNull(report);
                assertEquals(
                        "Rascunho de teste sem dados privados",
                        activity.getReportInputForTesting().getText().toString());
                assertTrue(button(report, DialogInterface.BUTTON_NEGATIVE).isShown());
                assertTrue(button(report, DialogInterface.BUTTON_POSITIVE).isShown());
                assertTrue(activity.getReportInputForTesting().isShown());
                assertTrue(button(report, DialogInterface.BUTTON_NEGATIVE).performClick());
            });
            await(scenario, activity -> !activity.getUiStateForTesting().isPaused());

            scenario.onActivity(activity -> {
                assertTrue(activity.getMenuButtonForTesting().performClick());
                assertTrue(activity.getActionsMenuForTesting().getMenu().performIdentifierAction(
                        Nintendo3DsProductActivity.MENU_SHARE_ID,
                        0));
                assertEquals(0, activity.getLastFeedbackResourceForTesting());
                assertFalse(activity.isShareInFlightForTesting());
                activity.onFailure(
                        Nintendo3DsReadinessPresentation.Action.RETRY,
                        new IOException("synthetic session failure"));
            });
            await(scenario, activity ->
                    activity.getUiStateForTesting().getSessionState()
                                    == Nintendo3DsProductUiState.SessionState.FAILED
                            && activity.getProductDialogForTesting() != null
                            && activity.getProductDialogForTesting().isShowing());
            scenario.onActivity(activity -> assertSessionFailureDialog(activity));

            scenario.recreate();
            await(scenario, activity ->
                    activity.getUiStateForTesting().getSessionState()
                                    == Nintendo3DsProductUiState.SessionState.FAILED
                            && activity.getProductDialogForTesting() != null
                            && activity.getProductDialogForTesting().isShowing());
            scenario.onActivity(Nintendo3DsProductShellInstrumentedTest::assertSessionFailureDialog);
            SystemClock.sleep(300);
            writeScreenshot(context, "n3ds-ux03-failure-restored.png");
        }
    }

    private static void assertSessionFailureDialog(Nintendo3DsProductActivity activity) {
        AlertDialog failure = activity.getProductDialogForTesting();
        assertNotNull(failure);
        TextView message = message(failure);
        assertNotNull(message);
        assertEquals(
                activity.getString(R.string.n3ds_product_launch_failed),
                message.getText().toString());
        assertTrue(button(failure, DialogInterface.BUTTON_POSITIVE).isShown());
        assertFalse(activity.getPauseButtonForTesting().isEnabled());
        assertFalse(activity.getVirtualControlsForTesting()
                .getControlForTesting(Nintendo3DsButton.A)
                .isEnabled());
        assertNotNull(activity.getProductFailureForTesting());
    }

    private static void assertToolbarAction(
            Nintendo3DsProductActivity activity,
            Button button,
            boolean expectedEnabled) {
        int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
        assertEquals(expectedEnabled, button.isEnabled());
        assertTrue(button.getWidth() >= minimum);
        assertTrue(button.getHeight() >= minimum);
        assertNotNull(button.getContentDescription());
        assertFalse(button.getContentDescription().toString().isBlank());
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    private static void assertIconAction(
            Nintendo3DsProductActivity activity,
            int actionId) {
        Button button = activity.getActionMenuButtonForTesting(actionId);
        assertNotNull(button);
        assertToolbarAction(activity, button, true);
        assertNotNull(button.getCompoundDrawables()[1]);
        assertTrue(button.getText() == null || button.getText().length() == 0);
    }

    private static void assertNoOverlap(View first, View second) {
        Rect firstBounds = new Rect();
        Rect secondBounds = new Rect();
        assertTrue(first.getGlobalVisibleRect(firstBounds));
        assertTrue(second.getGlobalVisibleRect(secondBounds));
        assertFalse(Rect.intersects(firstBounds, secondBounds));
    }

    private static void writeScreenshot(Context context, String name) throws IOException {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .takeScreenshot();
        assertNotNull(screenshot);
        writeBitmap(context, name, screenshot);
        screenshot.recycle();
    }

    private static void writeBitmap(Context context, String name, Bitmap bitmap)
            throws IOException {
        File directory = new File(context.getExternalFilesDir(null), "n3ds-ux02");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File destination = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        System.out.println("N3DS_UX_CAPTURE " + destination.getAbsolutePath());
    }

    private static void await(
            ActivityScenario<Nintendo3DsProductActivity> scenario,
            Condition condition) {
        long deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS;
        AtomicBoolean complete = new AtomicBoolean();
        AtomicReference<Throwable> productFailure = new AtomicReference<>();
        AtomicReference<String> lastState = new AtomicReference<>("activity not observed");
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> {
                complete.set(condition.evaluate(activity));
                productFailure.set(activity.getProductFailureForTesting());
                lastState.set(
                        "state=" + activity.getUiStateForTesting().getSessionState()
                                + ", frames=" + activity.getRenderedFrameCountForTesting());
            });
            if (complete.get()) {
                return;
            }
            if (productFailure.get() != null) {
                throw new AssertionError(
                        "Nintendo 3DS product shell failed while waiting; " + lastState.get(),
                        productFailure.get());
            }
            SystemClock.sleep(50);
        }
        throw new AssertionError(
                "Timed out waiting for the Nintendo 3DS product shell; " + lastState.get());
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate(Nintendo3DsProductActivity activity);
    }
}
