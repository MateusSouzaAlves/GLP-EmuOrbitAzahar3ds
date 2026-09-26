// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsLaunchReadinessInstrumentedTest {
    @Test
    public void acceptsMeasuredGalaxyCapabilitiesAndExplainsMiiRequirement() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String run = "n3ds-readiness-" + System.nanoTime();
        File filesRoot = new File(context.getFilesDir(), run);
        File cacheRoot = new File(context.getCacheDir(), run);
        try {
            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    filesRoot,
                    cacheRoot);
            Nintendo3DsMiiDataManager mii = new Nintendo3DsMiiDataManager(storage, () -> false);
            Nintendo3DsAndroidLaunchReadinessInspector inspector =
                    new Nintendo3DsAndroidLaunchReadinessInspector(context, storage, mii);

            Nintendo3DsLaunchReadiness.Result ready = inspector.inspect(
                    core,
                    Nintendo3DsLaunchReadiness.ContentStatus.READY,
                    0,
                    Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                    true);
            assertEquals(Nintendo3DsLaunchReadiness.Severity.READY, ready.getSeverity());
            assertTrue(ready.canLaunch());

            Nintendo3DsLaunchReadiness.Result miiRequired = inspector.inspect(
                    core,
                    Nintendo3DsLaunchReadiness.ContentStatus.READY,
                    0,
                    Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED,
                    true);
            assertEquals(List.of(Nintendo3DsLaunchReadiness.Issue.MII_FALLBACK_ACTIVE),
                    miiRequired.getIssues());
            assertEquals(Nintendo3DsLaunchReadiness.Severity.WARNING,
                    miiRequired.getSeverity());
            assertTrue(miiRequired.canLaunch());

            Nintendo3DsLaunchReadiness.Result unqualified = inspector.inspect(
                    core,
                    Nintendo3DsLaunchReadiness.ContentStatus.READY,
                    0,
                    Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                    false);
            assertEquals(Nintendo3DsLaunchReadiness.Severity.READY,
                    unqualified.getSeverity());
            assertTrue(unqualified.getIssues().isEmpty());
            assertTrue(unqualified.canLaunch());
        } finally {
            deleteTree(cacheRoot);
            deleteTree(filesRoot);
        }
    }

    private static void deleteTree(File root) throws Exception {
        if (!root.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}
