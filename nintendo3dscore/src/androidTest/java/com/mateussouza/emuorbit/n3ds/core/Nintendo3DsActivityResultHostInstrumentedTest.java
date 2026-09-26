// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsActivityResultHostInstrumentedTest {
    private static final long WAIT_TIMEOUT_MILLIS = 10_000;

    @Test
    public void restoresDialogAfterCancelThenImportsAndRepreparesFromActivityResult()
            throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent launch = new Intent(context, Nintendo3DsActivityResultTestActivity.class)
                .putExtra(Nintendo3DsActivityResultTestActivity.EXTRA_CORE_PATH,
                        core.getAbsolutePath())
                .putExtra(Nintendo3DsActivityResultTestActivity.EXTRA_CONTENT_PATH,
                        content.getAbsolutePath());
        Nintendo3DsMiiTestProvider.setPayload(syntheticMiiDatabase());
        try (ActivityScenario<Nintendo3DsActivityResultTestActivity> scenario =
                     ActivityScenario.launch(launch)) {
            await(scenario, activity -> activity.getActivityResultHost() != null
                    && activity.getActivityResultHost().isDialogShowing()
                    && activity.getStyledDialogCount() == 1);

            clickPrimary(scenario);
            await(scenario, activity -> activity.getMiiPickerLaunchCount() == 1);
            scenario.onActivity(activity -> {
                assertNotNull(activity.getLastMiiPickerIntent());
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                        activity.getLastMiiPickerIntent().getAction());
                activity.deliverMiiPickerResult(Activity.RESULT_CANCELED, null);
            });
            await(scenario, activity -> activity.getActivityResultHost().isDialogShowing()
                    && activity.getStyledDialogCount() == 2);

            Intent selected = new Intent().setData(Nintendo3DsMiiTestProvider.DOCUMENT_URI);
            clickPrimary(scenario);
            await(scenario, activity -> activity.getMiiPickerLaunchCount() == 2);
            scenario.onActivity(activity -> {
                assertNotNull(activity.getLastMiiPickerIntent());
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                        activity.getLastMiiPickerIntent().getAction());
                activity.deliverMiiPickerResult(Activity.RESULT_OK, selected);
            });
            await(scenario, activity -> activity.getDeliveredLaunch() != null);

            scenario.onActivity(activity -> {
                assertNull(activity.getFailure());
                assertTrue(activity.isCallbackOnMainThread());
                assertNotNull(activity.getDeliveredLaunch());
                assertTrue(activity.getDeliveredLaunch().hasController());
                assertFalse(activity.getDeliveredLaunch().getPresentation().isVisible());
                assertFalse(activity.getActivityResultHost().isDialogShowing());
                try {
                    assertEquals(Nintendo3DsMiiDataManager.Status.READY,
                            activity.getMiiStatus());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
        } finally {
            Nintendo3DsMiiTestProvider.setPayload(null);
        }
    }

    private static void clickPrimary(
            ActivityScenario<Nintendo3DsActivityResultTestActivity> scenario) {
        scenario.onActivity(activity -> {
            androidx.appcompat.app.AlertDialog dialog = activity.getActivityResultHost()
                    .getDialogControllerForTesting()
                    .getActiveDialogForTesting();
            assertNotNull(dialog);
            assertNotNull(Nintendo3DsMateusDialogTestActions.button(
                    dialog,
                    android.content.DialogInterface.BUTTON_POSITIVE));
            assertTrue(Nintendo3DsMateusDialogTestActions.button(
                    dialog,
                    android.content.DialogInterface.BUTTON_POSITIVE).performClick());
        });
    }

    private static void await(
            ActivityScenario<Nintendo3DsActivityResultTestActivity> scenario,
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
        throw new AssertionError("Timed out waiting for the Activity Result host");
    }

    private static byte[] syntheticMiiDatabase() {
        byte[] database = new byte[0x8 + 100 * 0x5C];
        database[0x8 + 0xC] = 1;
        return database;
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate(Nintendo3DsActivityResultTestActivity activity);
    }
}
