// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsExperimentalHostInstrumentedTest {
    @Test
    public void allowsAzaharFallbackAndClosesNativeSessionBeforeMiiImport() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String run = "n3ds-experimental-host-" + System.nanoTime();
        File filesRoot = new File(context.getFilesDir(), run);
        File cacheRoot = new File(context.getCacheDir(), run);
        SurfaceTexture texture = new SurfaceTexture(false);
        texture.setDefaultBufferSize(400, 480);
        Surface surface = new Surface(texture);
        Nintendo3DsExperimentalHost host = null;
        try {
            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    filesRoot,
                    cacheRoot);
            host = new Nintendo3DsExperimentalHost(context, storage);

            Nintendo3DsExperimentalHost.PreparedLaunch miiFallback = host.prepareLaunch(
                    core,
                    content,
                    Nintendo3DsLaunchReadiness.ContentStatus.READY,
                    0,
                    Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED,
                    true);
            assertTrue(miiFallback.hasController());
            assertEquals(
                    Nintendo3DsLaunchReadiness.Issue.MII_FALLBACK_ACTIVE,
                    miiFallback.getReadiness().getPrimaryIssue());
            assertEquals(
                    Nintendo3DsReadinessPresentation.Action.IMPORT_MII,
                    miiFallback.getPresentation().getPrimaryAction());
            assertTrue(miiFallback.getPresentation().canContinue());
            assertTrue(miiFallback.getPresentation().isVisible());

            Nintendo3DsCoreLifecycleController controller = miiFallback.requireController();
            controller.onSurfaceAvailable(surface, 400, 480);
            controller.onResume();
            assertTrue(controller.runFrames(2, 120_000).getPresentedFrames() >= 2);
            assertTrue(host.hasActiveNativeSession());

            byte[] database = syntheticMiiDatabase();
            Nintendo3DsMiiDataManager manager = host.getMiiDataManagerForTesting();
            assertThrows(IOException.class, () -> manager.importFromStreamForTesting(
                    new ByteArrayInputStream(database)));

            AtomicReference<Intent> launchedIntent = new AtomicReference<>();
            AtomicReference<Exception> actionFailure = new AtomicReference<>();
            Nintendo3DsReadinessActionCoordinator actionCoordinator =
                    new Nintendo3DsReadinessActionCoordinator(
                            context.getPackageName(),
                            host,
                            launchedIntent::set,
                            new Nintendo3DsReadinessActionCoordinator.Callback() {
                                @Override
                                public void onActionRequested(
                                        Nintendo3DsReadinessPresentation.Action action) {
                                    throw new AssertionError("Unexpected internal action: " + action);
                                }

                                @Override
                                public void onActionFailed(
                                        Nintendo3DsReadinessPresentation.Action action,
                                        Exception failure) {
                                    actionFailure.set(failure);
                                }
                            });
            assertEquals(
                    Nintendo3DsReadinessActionCoordinator.DispatchResult.EXTERNAL_INTENT,
                    actionCoordinator.dispatch(
                            Nintendo3DsReadinessPresentation.Action.IMPORT_MII));
            Intent picker = launchedIntent.get();
            assertNull(actionFailure.get());
            assertTrue(picker != null);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
            assertFalse(host.hasActiveNativeSession());
            assertEquals(controller.getOpenedSessionCount(), controller.getClosedSessionCount());

            manager.importFromStreamForTesting(new ByteArrayInputStream(database));
            assertEquals(Nintendo3DsMiiDataManager.Status.READY,
                    manager.inspect().getStatus());

            controller.onResume();
            assertTrue(controller.runFrames(2, 120_000).getPresentedFrames() >= 2);
            assertTrue(host.hasActiveNativeSession());

            AtomicReference<Nintendo3DsReadinessPresentation.Action> failedAction =
                    new AtomicReference<>();
            Nintendo3DsReadinessActionCoordinator failingCoordinator =
                    new Nintendo3DsReadinessActionCoordinator(
                            context.getPackageName(),
                            host,
                            ignored -> {
                                throw new ActivityNotFoundException("synthetic");
                            },
                            new Nintendo3DsReadinessActionCoordinator.Callback() {
                                @Override
                                public void onActionRequested(
                                        Nintendo3DsReadinessPresentation.Action action) {
                                    throw new AssertionError("Unexpected internal action: " + action);
                                }

                                @Override
                                public void onActionFailed(
                                        Nintendo3DsReadinessPresentation.Action action,
                                        Exception failure) {
                                    failedAction.set(action);
                                }
                            });
            assertEquals(
                    Nintendo3DsReadinessActionCoordinator.DispatchResult.FAILED,
                    failingCoordinator.dispatch(
                            Nintendo3DsReadinessPresentation.Action.OPEN_STORAGE));
            assertEquals(
                    Nintendo3DsReadinessPresentation.Action.OPEN_STORAGE,
                    failedAction.get());
            assertEquals(
                    Nintendo3DsReadinessActionCoordinator.DispatchResult.IGNORED,
                    failingCoordinator.dispatch(Nintendo3DsReadinessPresentation.Action.NONE));
        } finally {
            if (host != null) {
                host.close();
            }
            surface.release();
            texture.release();
            deleteTree(cacheRoot);
            deleteTree(filesRoot);
        }
    }

    private static byte[] syntheticMiiDatabase() {
        byte[] database = new byte[0x8 + 100 * 0x5C];
        database[0x8 + 0xC] = 1;
        return database;
    }

    private static void deleteTree(File root) throws Exception {
        if (!root.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}
