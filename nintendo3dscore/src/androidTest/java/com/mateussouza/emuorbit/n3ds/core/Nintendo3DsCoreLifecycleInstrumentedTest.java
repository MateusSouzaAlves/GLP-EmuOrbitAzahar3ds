// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsCoreLifecycleInstrumentedTest {
    private static final long ACTIVITY_TIMEOUT_MS = 15_000;
    private static final long FRAME_TIMEOUT_MS = 120_000;

    @Test
    public void closesAndRecreatesOwnerThreadSessionAcrossAndroidLifecycle() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        int framesPerTransition = boundedArgument(
                arguments, "n3dsLifecycleHostFrames", 2, 1, 120);
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyguardManager keyguardManager = targetContext.getSystemService(KeyguardManager.class);
        assumeFalse(
                "Rotação via ActivityScenario requer aparelho desbloqueado.",
                keyguardManager != null && keyguardManager.isKeyguardLocked());
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Intent intent = new Intent(targetContext, Nintendo3DsLifecycleTestActivity.class)
                .putExtra(Nintendo3DsLifecycleTestActivity.EXTRA_CORE_PATH, corePath)
                .putExtra(Nintendo3DsLifecycleTestActivity.EXTRA_CONTENT_PATH, contentPath);

        Nintendo3DsLifecycleTestActivity firstActivity;
        Nintendo3DsLifecycleTestActivity recreatedActivity;
        Nintendo3DsLifecycleTestActivity rotatedActivity;
        Nintendo3DsLifecycleTestActivity foregroundActivity;
        try (ActivityScenario<Nintendo3DsLifecycleTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            firstActivity = awaitReadyActivity(scenario, -1);
            Nintendo3DsCoreLifecycleController firstController = firstActivity.getController();
            assertFrameBatch(firstController, framesPerTransition);
            assertEquals(1, firstController.getOpenedSessionCount());
            assertEquals(0, firstController.getClosedSessionCount());

            scenario.moveToState(Lifecycle.State.CREATED);
            awaitClosedSession(firstController, 1);
            assertFalse(firstController.isReadyForFrames());
            assertTrue(firstController.getCapturedRecoveryStateCount() >= 1);
            assertNull(firstController.getLastRecoveryStateFailure());
            assertNull(firstActivity.getLifecycleFailure());

            scenario.moveToState(Lifecycle.State.RESUMED);
            firstActivity = awaitReadyActivity(scenario, firstActivity.getInstanceId() - 1);
            assertFrameBatch(firstController, framesPerTransition);
            assertEquals(2, firstController.getOpenedSessionCount());
            assertEquals(1, firstController.getClosedSessionCount());
            assertTrue(firstController.getRestoredRecoveryStateCount() >= 1);
            assertNull(firstController.getLastRecoveryStateFailure());

            int firstInstanceId = firstActivity.getInstanceId();
            scenario.recreate();
            recreatedActivity = awaitReadyActivity(scenario, firstInstanceId);
            assertTrue(firstController.isClosed());
            assertEquals(2, firstController.getClosedSessionCount());
            assertNull(firstActivity.getLifecycleFailure());
            assertFrameBatch(recreatedActivity.getController(), framesPerTransition);
            assertTrue(recreatedActivity.getController().getRestoredRecoveryStateCount() >= 1);
            assertNull(recreatedActivity.getController().getLastRecoveryStateFailure());

            int recreatedInstanceId = recreatedActivity.getInstanceId();
            scenario.onActivity(activity -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            rotatedActivity = awaitReadyActivity(scenario, recreatedInstanceId);
            assertEquals(
                    Configuration.ORIENTATION_LANDSCAPE,
                    rotatedActivity.getResources().getConfiguration().orientation);
            assertTrue(recreatedActivity.getController().isClosed());
            assertNull(recreatedActivity.getLifecycleFailure());
            Nintendo3DsCoreFrameReport rotatedReport = assertFrameBatch(
                    rotatedActivity.getController(), framesPerTransition);
            assertTrue(rotatedActivity.getController().getRestoredRecoveryStateCount() >= 1);
            assertNull(rotatedActivity.getController().getLastRecoveryStateFailure());

            scenario.moveToState(Lifecycle.State.CREATED);
            awaitClosedSession(rotatedActivity.getController(), 1);
            scenario.moveToState(Lifecycle.State.RESUMED);
            foregroundActivity = awaitReadyActivity(
                    scenario, rotatedActivity.getInstanceId() - 1);
            if (foregroundActivity != rotatedActivity) {
                assertTrue(rotatedActivity.getController().isClosed());
            }
            Nintendo3DsCoreFrameReport foregroundReport = assertFrameBatch(
                    foregroundActivity.getController(), framesPerTransition);
            assertNull(foregroundActivity.getLifecycleFailure());

            System.out.println(
                    "N3DS_ANDROID_LIFECYCLE lastInstance=" + foregroundActivity.getInstanceId()
                            + " firstSessions=" + firstController.getOpenedSessionCount()
                            + " rotatedSessions="
                            + foregroundActivity.getController().getOpenedSessionCount()
                            + " framesPerTransition=" + framesPerTransition
                            + " rotatedSize=" + rotatedReport.getWidth() + "x"
                            + rotatedReport.getHeight()
                            + " foregroundFrames=" + foregroundReport.getPresentedFrames());
        }

        assertTrue(foregroundActivity.getController().isClosed());
        assertEquals(
                foregroundActivity.getController().getOpenedSessionCount(),
                foregroundActivity.getController().getClosedSessionCount());
        assertNull(foregroundActivity.getLifecycleFailure());
    }

    @Test
    public void survivesRealLauncherSwitchesAndScreenOffOn() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        int cycles = boundedArgument(arguments, "n3dsExternalLifecycleCycles", 3, 1, 20);
        int framesPerTransition = boundedArgument(
                arguments, "n3dsLifecycleHostFrames", 2, 1, 120);
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(targetContext, Nintendo3DsLifecycleTestActivity.class)
                .putExtra(Nintendo3DsLifecycleTestActivity.EXTRA_CORE_PATH, corePath)
                .putExtra(Nintendo3DsLifecycleTestActivity.EXTRA_CONTENT_PATH, contentPath);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        Nintendo3DsLifecycleTestActivity activity = null;
        Nintendo3DsCoreLifecycleController controller = null;
        Set<Integer> activityInstances = new HashSet<>();
        try (ActivityScenario<Nintendo3DsLifecycleTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            activity = awaitResumedDebugActivity();
            controller = activity.getController();
            activityInstances.add(activity.getInstanceId());
            assertFrameBatch(controller, framesPerTransition);

            for (int cycle = 0; cycle < cycles; cycle++) {
                Nintendo3DsLifecycleTestActivity pausedActivity = activity;
                Nintendo3DsCoreLifecycleController pausedController = controller;
                long expectedClosedSessions = pausedController.getClosedSessionCount() + 1;
                assertTrue("Não foi possível abrir o launcher.", device.pressHome());
                awaitClosedSession(pausedController, expectedClosedSessions);
                assertFalse(pausedController.isReadyForFrames());
                assertTrue(pausedController.getCapturedRecoveryStateCount() >= 1);
                assertNull(pausedController.getLastRecoveryStateFailure());
                assertNull(pausedActivity.getLifecycleFailure());

                bringTaskToForeground(targetContext, intent);
                activity = awaitResumedDebugActivity();
                controller = activity.getController();
                activityInstances.add(activity.getInstanceId());
                assertFrameBatch(controller, framesPerTransition);
                assertTrue(controller.getRestoredRecoveryStateCount() >= 1);
                assertNull(controller.getLastRecoveryStateFailure());
            }

            Nintendo3DsLifecycleTestActivity sleepingActivity = activity;
            Nintendo3DsCoreLifecycleController sleepingController = controller;
            long expectedScreenOffCloses = sleepingController.getClosedSessionCount() + 1;
            try {
                device.sleep();
                awaitClosedSession(sleepingController, expectedScreenOffCloses);
                assertFalse(sleepingController.isReadyForFrames());
                assertTrue(sleepingController.getCapturedRecoveryStateCount() >= 1);
                assertNull(sleepingController.getLastRecoveryStateFailure());
                assertNull(sleepingActivity.getLifecycleFailure());
            } finally {
                device.wakeUp();
            }
            bringTaskToForeground(targetContext, intent);
            activity = awaitResumedDebugActivity();
            controller = activity.getController();
            activityInstances.add(activity.getInstanceId());
            Nintendo3DsCoreFrameReport wakeReport = assertFrameBatch(
                    controller, framesPerTransition);
            assertTrue(controller.getRestoredRecoveryStateCount() >= 1);
            assertNull(controller.getLastRecoveryStateFailure());
            assertNull(activity.getLifecycleFailure());

            System.out.println(
                    "N3DS_EXTERNAL_LIFECYCLE launcherCycles=" + cycles
                            + " screenOffOnCycles=1"
                            + " activityInstances=" + activityInstances.size()
                            + " sessions=" + controller.getOpenedSessionCount()
                            + " framesPerTransition=" + framesPerTransition
                            + " wakeFrames=" + wakeReport.getPresentedFrames());

            Nintendo3DsLifecycleTestActivity finishedActivity = activity;
            Nintendo3DsCoreLifecycleController finishedController = controller;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(finishedActivity::finish);
            awaitControllerClosed(finishedController);
            assertEquals(
                    finishedController.getOpenedSessionCount(),
                    finishedController.getClosedSessionCount());
            assertNull(finishedActivity.getLifecycleFailure());
        } finally {
            device.wakeUp();
        }
    }

    private static Nintendo3DsCoreFrameReport assertFrameBatch(
            Nintendo3DsCoreLifecycleController controller,
            int frames) throws Exception {
        Nintendo3DsCoreFrameReport report = null;
        IOException lastLifecycleInvalidation = null;
        for (int attempt = 0; attempt < 4 && report == null; attempt++) {
            try {
                report = controller.runFrames(frames, FRAME_TIMEOUT_MS);
            } catch (IOException exception) {
                String message = exception.getMessage();
                if (message == null || !message.contains("invalidou o frame 3DS pendente")) {
                    throw exception;
                }
                lastLifecycleInvalidation = exception;
                awaitControllerReadyForRetry(controller);
            }
        }
        if (report == null) {
            throw lastLifecycleInvalidation;
        }
        assertTrue(report.isHardwareRenderNegotiated());
        assertTrue(report.isContextReset());
        assertTrue(report.isContentOpenedThroughVfs());
        assertTrue(report.getPresentedFrames() >= frames);
        return report;
    }

    private static void awaitControllerReadyForRetry(
            Nintendo3DsCoreLifecycleController controller) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 3_000;
        while (SystemClock.elapsedRealtime() < deadline
                && !controller.isClosed()
                && !controller.isReadyForFrames()) {
            SystemClock.sleep(50);
        }
        if (!controller.isReadyForFrames()) {
            throw new IOException("O host 3DS não se recuperou da transição esperada.");
        }
        SystemClock.sleep(300);
    }

    private static void bringTaskToForeground(Context context, Intent sourceIntent) {
        Intent foregroundIntent = new Intent(sourceIntent).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(foregroundIntent);
    }

    private static Nintendo3DsLifecycleTestActivity awaitResumedDebugActivity() {
        long deadline = SystemClock.elapsedRealtime() + ACTIVITY_TIMEOUT_MS;
        Nintendo3DsLifecycleTestActivity activity = null;
        Nintendo3DsLifecycleTestActivity stableActivity = null;
        long stableSince = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            activity = Nintendo3DsLifecycleTestActivity.getResumedActivity();
            if (activity != null && activity.getController().isReadyForFrames()) {
                if (activity != stableActivity) {
                    stableActivity = activity;
                    stableSince = SystemClock.elapsedRealtime();
                } else if (SystemClock.elapsedRealtime() - stableSince >= 300) {
                    assertNull(activity.getLifecycleFailure());
                    return activity;
                }
            } else {
                stableActivity = null;
                stableSince = 0;
            }
            SystemClock.sleep(50);
        }
        assertTrue(
                "Activity 3DS externa não ficou pronta dentro do timeout.",
                activity != null && activity.getController().isReadyForFrames());
        return activity;
    }

    private static Nintendo3DsLifecycleTestActivity awaitReadyActivity(
            ActivityScenario<Nintendo3DsLifecycleTestActivity> scenario,
            int previousInstanceId) {
        long deadline = SystemClock.elapsedRealtime() + ACTIVITY_TIMEOUT_MS;
        AtomicReference<Nintendo3DsLifecycleTestActivity> current = new AtomicReference<>();
        Nintendo3DsLifecycleTestActivity stableActivity = null;
        long stableSince = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(current::set);
            Nintendo3DsLifecycleTestActivity activity = current.get();
            if (activity.getInstanceId() != previousInstanceId
                    && activity.getController().isReadyForFrames()) {
                if (activity != stableActivity) {
                    stableActivity = activity;
                    stableSince = SystemClock.elapsedRealtime();
                } else if (SystemClock.elapsedRealtime() - stableSince >= 300) {
                    assertNull(activity.getLifecycleFailure());
                    return activity;
                }
            } else {
                stableActivity = null;
                stableSince = 0;
            }
            SystemClock.sleep(50);
        }
        Nintendo3DsLifecycleTestActivity activity = current.get();
        assertTrue("Activity 3DS não ficou pronta dentro do timeout.",
                activity != null && activity.getController().isReadyForFrames());
        assertNotEquals(previousInstanceId, activity.getInstanceId());
        return activity;
    }

    private static void awaitClosedSession(
            Nintendo3DsCoreLifecycleController controller,
            long expectedClosedSessions) {
        long deadline = SystemClock.elapsedRealtime() + ACTIVITY_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline
                && controller.getClosedSessionCount() < expectedClosedSessions) {
            SystemClock.sleep(50);
        }
        assertEquals(expectedClosedSessions, controller.getClosedSessionCount());
    }

    private static void awaitControllerClosed(Nintendo3DsCoreLifecycleController controller) {
        long deadline = SystemClock.elapsedRealtime() + ACTIVITY_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline && !controller.isClosed()) {
            SystemClock.sleep(50);
        }
        assertTrue("Controlador 3DS não encerrou dentro do timeout.", controller.isClosed());
    }

    private static int boundedArgument(
            Bundle arguments,
            String key,
            int defaultValue,
            int minimum,
            int maximum) {
        String rawValue = arguments.getString(key, Integer.toString(defaultValue));
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(rawValue)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Argumento numérico 3DS inválido: " + key, exception);
        }
    }
}
