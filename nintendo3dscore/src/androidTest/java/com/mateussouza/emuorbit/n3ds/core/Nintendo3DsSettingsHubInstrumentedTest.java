// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/** Focused device contract for the feature-owned settings, data, Mii, and help hub. */
@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsSettingsHubInstrumentedTest {
    private static final long UI_TIMEOUT_MILLIS = 5_000L;

    @Test
    public void exposesOnlySupportedDestinationsAndCreatesRestorableCheckpoint()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File filesRoot = new File(context.getFilesDir(), "nintendo3ds");
        File cacheRoot = new File(context.getCacheDir(), "nintendo3ds");
        deleteExactTestTree(filesRoot, context.getFilesDir());
        deleteExactTestTree(cacheRoot, context.getCacheDir());

        Intent intent = new Intent(context, Nintendo3DsSettingsActivity.class);
        try (ActivityScenario<Nintendo3DsSettingsActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(
                        Configuration.ORIENTATION_PORTRAIT,
                        activity.getResources().getConfiguration().orientation);
                assertText(activity, R.id.n3ds_settings_hub_title,
                        R.string.n3ds_settings_hub_title);
                assertNotNull(activity.findViewById(R.id.n3ds_settings_hub_open_settings));
                assertNotNull(activity.findViewById(R.id.n3ds_settings_hub_import_mii));
                assertNotNull(activity.findViewById(R.id.n3ds_settings_hub_create_backup));
                assertNotNull(activity.findViewById(R.id.n3ds_settings_hub_restore_backup));
                assertNotNull(activity.findViewById(R.id.n3ds_settings_hub_export_portable));
                assertNotNull(activity.findViewById(R.id.n3ds_settings_hub_import_portable));
                TextView help = activity.findViewById(R.id.n3ds_settings_hub_help_body);
                assertNotNull(help);
                assertEquals(activity.getString(R.string.n3ds_settings_hub_help_body),
                        help.getText().toString());
            });

            waitFor(scenario, activity -> {
                TextView status = activity.findViewById(R.id.n3ds_settings_hub_backup_status);
                return status != null && status.getText().toString().equals(
                        activity.getString(R.string.n3ds_settings_hub_backup_missing));
            });
            scenario.onActivity(activity -> {
                Button restore = activity.findViewById(R.id.n3ds_settings_hub_restore_backup);
                assertFalse(restore.isEnabled());
                activity.findViewById(R.id.n3ds_settings_hub_create_backup).performClick();
            });

            waitFor(scenario, activity -> {
                Button restore = activity.findViewById(R.id.n3ds_settings_hub_restore_backup);
                return restore != null && restore.isEnabled();
            });
            scenario.onActivity(activity -> {
                Button restore = activity.findViewById(R.id.n3ds_settings_hub_restore_backup);
                assertTrue(restore.isEnabled());
                assertTrue(new File(
                        new File(new File(activity.getFilesDir(), "nintendo3ds"), "v1"),
                        "backups/latest.manifest").isFile());
                activity.findViewById(R.id.n3ds_settings_hub_open_settings).performClick();
                View title = activity.findViewById(R.id.n3ds_settings_hub_title);
                assertNotNull(title);
                assertTrue(title.isShown());
            });
        } finally {
            deleteExactTestTree(filesRoot, context.getFilesDir());
            deleteExactTestTree(cacheRoot, context.getCacheDir());
        }
    }

    private static void assertText(
            Nintendo3DsSettingsActivity activity,
            int viewId,
            int stringId) {
        TextView view = activity.findViewById(viewId);
        assertNotNull(view);
        assertEquals(activity.getString(stringId), view.getText().toString());
    }

    private static void waitFor(
            ActivityScenario<Nintendo3DsSettingsActivity> scenario,
            ActivityCondition condition) {
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicBoolean satisfied = new AtomicBoolean();
            scenario.onActivity(activity -> satisfied.set(condition.matches(activity)));
            if (satisfied.get()) {
                return;
            }
            SystemClock.sleep(50L);
        }
        AtomicBoolean finalState = new AtomicBoolean();
        scenario.onActivity(activity -> finalState.set(condition.matches(activity)));
        assertTrue("Timed out waiting for the settings hub", finalState.get());
    }

    private static void deleteExactTestTree(File root, File allowedParent) throws Exception {
        File checkedRoot = root.getCanonicalFile();
        File checkedParent = allowedParent.getCanonicalFile();
        assertTrue(checkedRoot.toPath().startsWith(checkedParent.toPath()));
        if (!checkedRoot.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(checkedRoot.toPath())) {
            for (java.nio.file.Path path :
                    paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }

    private interface ActivityCondition {
        boolean matches(Nintendo3DsSettingsActivity activity);
    }
}
