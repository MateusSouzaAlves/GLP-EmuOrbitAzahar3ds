// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.button;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.message;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsReadinessDialogInstrumentedTest {
    @Test
    public void rendersEveryActionPathAndSkipsReady() {
        assertMiiFallbackDialog();
        assertWarningContinueAlternative();
        assertStorageSettingsIntent();
        assertInternalAction(
                Nintendo3DsReadinessTestActivity.SCENARIO_ARCHIVE,
                Nintendo3DsReadinessPresentation.Action.EXTRACT_CONTENT,
                R.string.n3ds_readiness_content_requires_extraction);
        assertInternalAction(
                Nintendo3DsReadinessTestActivity.SCENARIO_CORE_MISSING,
                Nintendo3DsReadinessPresentation.Action.RETRY,
                R.string.n3ds_readiness_core_missing);
        assertInternalAction(
                Nintendo3DsReadinessTestActivity.SCENARIO_ENCRYPTED,
                Nintendo3DsReadinessPresentation.Action.UNDERSTOOD,
                R.string.n3ds_readiness_content_encrypted);
        assertReadyNeedsNoDialog();
    }

    private static void assertMiiFallbackDialog() {
        try (ActivityScenario<Nintendo3DsReadinessTestActivity> scenario =
                     launch(Nintendo3DsReadinessTestActivity.SCENARIO_MII_FALLBACK)) {
            scenario.onActivity(activity -> {
                AlertDialog dialog = requireDialog(activity);
                TextView message = message(dialog);
                Button positive = button(dialog, DialogInterface.BUTTON_POSITIVE);
                Button neutral = button(dialog, DialogInterface.BUTTON_NEUTRAL);
                assertNotNull(message);
                assertNotNull(positive);
                assertNotNull(neutral);
                assertEquals(activity.getString(R.string.n3ds_readiness_mii_fallback),
                        message.getText().toString());
                assertEquals(activity.getString(R.string.n3ds_readiness_action_import_mii),
                        positive.getText().toString());
                assertEquals(activity.getString(R.string.n3ds_readiness_action_continue),
                        neutral.getText().toString());
                assertTrue(positive.isClickable());
                assertTrue(positive.performClick());
                assertEquals(Nintendo3DsReadinessPresentation.Action.NONE,
                        activity.getDispatchedAction());
                assertEquals(0, activity.getDispatchCount());
                assertEquals(1, activity.getExternalIntentCount());
                assertNotNull(activity.getExternalIntent());
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                        activity.getExternalIntent().getAction());
                assertTrue(activity.getExternalIntent().hasCategory(Intent.CATEGORY_OPENABLE));
                assertNull(activity.getActionFailure());
                assertFalse(activity.getDialogController().isShowing());
            });
        }
    }

    private static void assertWarningContinueAlternative() {
        try (ActivityScenario<Nintendo3DsReadinessTestActivity> scenario =
                     launch(Nintendo3DsReadinessTestActivity.SCENARIO_WARNING_MII)) {
            scenario.onActivity(activity -> {
                AlertDialog dialog = requireDialog(activity);
                TextView message = message(dialog);
                Button positive = button(dialog, DialogInterface.BUTTON_POSITIVE);
                Button neutral = button(dialog, DialogInterface.BUTTON_NEUTRAL);
                assertNotNull(message);
                assertNotNull(positive);
                assertNotNull(neutral);
                assertEquals(
                        activity.getString(R.string.n3ds_readiness_mii_optional),
                        message.getText().toString());
                assertEquals(activity.getString(R.string.n3ds_readiness_action_import_mii),
                        positive.getText().toString());
                assertEquals(activity.getString(R.string.n3ds_readiness_action_continue),
                        neutral.getText().toString());
                assertTrue(neutral.performClick());
                assertEquals(Nintendo3DsReadinessPresentation.Action.CONTINUE,
                        activity.getDispatchedAction());
                assertEquals(1, activity.getDispatchCount());
                assertEquals(0, activity.getExternalIntentCount());
                assertNull(activity.getActionFailure());
                assertFalse(activity.getDialogController().isShowing());
            });
        }
    }

    private static void assertStorageSettingsIntent() {
        try (ActivityScenario<Nintendo3DsReadinessTestActivity> scenario =
                     launch(Nintendo3DsReadinessTestActivity.SCENARIO_STORAGE_LOW)) {
            scenario.onActivity(activity -> {
                AlertDialog dialog = requireDialog(activity);
                Button positive = button(dialog, DialogInterface.BUTTON_POSITIVE);
                assertNotNull(positive);
                assertTrue(positive.performClick());
                Intent intent = activity.getExternalIntent();
                assertNotNull(intent);
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
                assertNotNull(intent.getData());
                assertEquals("package", intent.getData().getScheme());
                assertEquals(activity.getPackageName(), intent.getData().getSchemeSpecificPart());
                assertEquals(1, activity.getExternalIntentCount());
                assertEquals(0, activity.getDispatchCount());
                assertNull(activity.getActionFailure());
            });
        }
    }

    private static void assertInternalAction(
            String scenarioName,
            Nintendo3DsReadinessPresentation.Action expectedAction,
            int expectedMessage) {
        try (ActivityScenario<Nintendo3DsReadinessTestActivity> scenario =
                     launch(scenarioName)) {
            scenario.onActivity(activity -> {
                AlertDialog dialog = requireDialog(activity);
                TextView message = message(dialog);
                Button positive = button(dialog, DialogInterface.BUTTON_POSITIVE);
                assertNotNull(message);
                assertNotNull(positive);
                assertEquals(activity.getString(expectedMessage), message.getText().toString());
                assertEquals(activity.getString(expectedAction.getLabelResource()),
                        positive.getText().toString());
                assertTrue(positive.performClick());
                assertEquals(expectedAction, activity.getDispatchedAction());
                assertEquals(1, activity.getDispatchCount());
                assertEquals(0, activity.getExternalIntentCount());
                assertNull(activity.getActionFailure());
                assertFalse(activity.getDialogController().isShowing());
            });
        }
    }

    private static void assertReadyNeedsNoDialog() {
        try (ActivityScenario<Nintendo3DsReadinessTestActivity> scenario =
                     launch(Nintendo3DsReadinessTestActivity.SCENARIO_READY)) {
            scenario.onActivity(activity -> {
                assertFalse(activity.getShowResult());
                assertFalse(activity.getDialogController().isShowing());
                assertEquals(Nintendo3DsReadinessPresentation.Action.NONE,
                        activity.getDispatchedAction());
                assertEquals(0, activity.getDispatchCount());
            });
        }
    }

    private static AlertDialog requireDialog(Nintendo3DsReadinessTestActivity activity) {
        assertTrue(activity.getShowResult());
        assertTrue(activity.getDialogController().isShowing());
        AlertDialog dialog = activity.getDialogController().getActiveDialogForTesting();
        assertNotNull(dialog);
        return dialog;
    }

    private static ActivityScenario<Nintendo3DsReadinessTestActivity> launch(String scenario) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, Nintendo3DsReadinessTestActivity.class)
                .putExtra(Nintendo3DsReadinessTestActivity.EXTRA_SCENARIO, scenario);
        return ActivityScenario.launch(intent);
    }
}
