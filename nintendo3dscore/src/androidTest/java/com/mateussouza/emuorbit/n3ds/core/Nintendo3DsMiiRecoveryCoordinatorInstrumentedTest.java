// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsMiiRecoveryCoordinatorInstrumentedTest {
    @Test
    public void repreparesRequiredMiiReusesPausedOptionalControllerAndRetries() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        runRequiredRecovery(context, core, content);
        runOptionalRecovery(context, core, content);
        runRetryablePreflight(context, core, content);
    }

    private static void runRequiredRecovery(Context context, File core, File content)
            throws Exception {
        Scenario scenario = Scenario.open(context, "required", core, content,
                Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED);
        try {
            Nintendo3DsExperimentalHost.PreparedLaunch initial =
                    scenario.coordinator.prepareInitialLaunch();
            assertTrue(initial.hasController());
            assertEquals(Nintendo3DsReadinessPresentation.Action.IMPORT_MII,
                    initial.getPresentation().getPrimaryAction());
            assertTrue(initial.getPresentation().canContinue());
            assertPickerDispatched(context, scenario.host);

            Nintendo3DsMiiTestProvider.setPayload(syntheticMiiDatabase(1));
            RecoveryCapture capture = new RecoveryCapture();
            assertTrue(scenario.coordinator.importSelectedMiiAndReprepare(
                    Nintendo3DsMiiTestProvider.DOCUMENT_URI, capture));
            assertTrue(scenario.coordinator.isRecoveryInFlight());
            assertFalse(scenario.coordinator.importSelectedMiiAndReprepare(
                    Nintendo3DsMiiTestProvider.DOCUMENT_URI, capture));
            scenario.worker.runAll();

            assertFalse(scenario.coordinator.isRecoveryInFlight());
            assertNull(capture.failure.get());
            Nintendo3DsMiiRecoveryCoordinator.Recovery recovery = capture.recovery.get();
            assertNotNull(recovery);
            assertEquals(1, recovery.getImportReport().getVisibleMiiCount());
            assertTrue(recovery.getPreparedLaunch().hasController());
            assertFalse(recovery.getPreparedLaunch().getPresentation().isVisible());
            assertSame(recovery.getPreparedLaunch(), scenario.coordinator.getLatestLaunch());
            assertFalse(scenario.coordinator.importSelectedMiiAndReprepare(
                    Nintendo3DsMiiTestProvider.DOCUMENT_URI, capture));
        } finally {
            scenario.close();
        }
    }

    private static void runOptionalRecovery(Context context, File core, File content)
            throws Exception {
        Scenario scenario = Scenario.open(context, "optional", core, content,
                Nintendo3DsLaunchReadiness.MiiRequirement.OPTIONAL);
        try {
            Nintendo3DsExperimentalHost.PreparedLaunch initial =
                    scenario.coordinator.prepareInitialLaunch();
            assertTrue(initial.hasController());
            assertEquals(Nintendo3DsReadinessPresentation.Action.IMPORT_MII,
                    initial.getPresentation().getPrimaryAction());
            assertTrue(initial.getPresentation().canContinue());
            Nintendo3DsCoreLifecycleController initialController =
                    initial.requireController();
            assertPickerDispatched(context, scenario.host);

            Nintendo3DsMiiTestProvider.setPayload(syntheticMiiDatabase(2));
            RecoveryCapture capture = new RecoveryCapture();
            assertTrue(scenario.coordinator.importSelectedMiiAndReprepare(
                    Nintendo3DsMiiTestProvider.DOCUMENT_URI, capture));
            scenario.worker.runAll();

            assertNull(capture.failure.get());
            Nintendo3DsMiiRecoveryCoordinator.Recovery recovery = capture.recovery.get();
            assertNotNull(recovery);
            assertSame(initialController, recovery.getPreparedLaunch().requireController());
            assertFalse(recovery.getPreparedLaunch().getPresentation().isVisible());
            scenario.coordinator.close();
            assertFalse(scenario.coordinator.importSelectedMiiAndReprepare(
                    Nintendo3DsMiiTestProvider.DOCUMENT_URI, capture));
        } finally {
            scenario.close();
        }
    }

    private static void runRetryablePreflight(Context context, File core, File content)
            throws Exception {
        File missingCore = new File(core.getParentFile(),
                "missing-retry-core-" + System.nanoTime() + ".so");
        Scenario scenario = Scenario.open(context, "retry", missingCore, content,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED);
        try {
            Nintendo3DsExperimentalHost.PreparedLaunch initial =
                    scenario.coordinator.prepareInitialLaunch();
            assertFalse(initial.hasController());
            assertEquals(Nintendo3DsReadinessPresentation.Action.RETRY,
                    initial.getPresentation().getPrimaryAction());

            PreflightCapture capture = new PreflightCapture();
            assertTrue(scenario.coordinator.retryPreflight(capture));
            assertTrue(scenario.coordinator.isRecoveryInFlight());
            assertFalse(scenario.coordinator.retryPreflight(capture));
            scenario.worker.runAll();

            assertFalse(scenario.coordinator.isRecoveryInFlight());
            assertNull(capture.failure.get());
            assertNotNull(capture.preparedLaunch.get());
            assertFalse(capture.preparedLaunch.get().hasController());
            assertEquals(Nintendo3DsReadinessPresentation.Action.RETRY,
                    capture.preparedLaunch.get().getPresentation().getPrimaryAction());
            assertSame(capture.preparedLaunch.get(), scenario.coordinator.getLatestLaunch());
            scenario.coordinator.close();
            assertFalse(scenario.coordinator.retryPreflight(capture));
        } finally {
            scenario.close();
        }
    }

    private static void assertPickerDispatched(
            Context context,
            Nintendo3DsExperimentalHost host) {
        AtomicReference<Intent> launched = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Nintendo3DsReadinessActionCoordinator actions =
                new Nintendo3DsReadinessActionCoordinator(
                        context.getPackageName(),
                        host,
                        launched::set,
                        new Nintendo3DsReadinessActionCoordinator.Callback() {
                            @Override
                            public void onActionRequested(
                                    Nintendo3DsReadinessPresentation.Action action) {
                                throw new AssertionError("Unexpected internal action: " + action);
                            }

                            @Override
                            public void onActionFailed(
                                    Nintendo3DsReadinessPresentation.Action action,
                                    Exception actionFailure) {
                                failure.set(actionFailure);
                            }
                        });
        assertEquals(Nintendo3DsReadinessActionCoordinator.DispatchResult.EXTERNAL_INTENT,
                actions.dispatch(Nintendo3DsReadinessPresentation.Action.IMPORT_MII));
        assertNull(failure.get());
        assertNotNull(launched.get());
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launched.get().getAction());
        assertFalse(host.hasActiveNativeSession());
    }

    private static byte[] syntheticMiiDatabase(int identifier) {
        byte[] database = new byte[0x8 + 100 * 0x5C];
        database[0x8 + 0xC] = (byte) identifier;
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
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
    }

    private static final class RecoveryCapture
            implements Nintendo3DsMiiRecoveryCoordinator.Listener {
        private final AtomicReference<Nintendo3DsMiiRecoveryCoordinator.Recovery> recovery =
                new AtomicReference<>();
        private final AtomicReference<IOException> failure = new AtomicReference<>();

        @Override
        public void onRecovered(Nintendo3DsMiiRecoveryCoordinator.Recovery value) {
            recovery.set(value);
        }

        @Override
        public void onRecoveryFailed(IOException value) {
            failure.set(value);
        }
    }

    private static final class PreflightCapture
            implements Nintendo3DsMiiRecoveryCoordinator.PreflightListener {
        private final AtomicReference<Nintendo3DsExperimentalHost.PreparedLaunch>
                preparedLaunch = new AtomicReference<>();
        private final AtomicReference<IOException> failure = new AtomicReference<>();

        @Override
        public void onPrepared(Nintendo3DsExperimentalHost.PreparedLaunch value) {
            preparedLaunch.set(value);
        }

        @Override
        public void onPreflightFailed(IOException value) {
            failure.set(value);
        }
    }

    private static final class Scenario implements AutoCloseable {
        private final File filesRoot;
        private final File cacheRoot;
        private final Nintendo3DsExperimentalHost host;
        private final CapturingExecutor worker;
        private final Nintendo3DsMiiRecoveryCoordinator coordinator;

        private Scenario(
                File filesRoot,
                File cacheRoot,
                Nintendo3DsExperimentalHost host,
                CapturingExecutor worker,
                Nintendo3DsMiiRecoveryCoordinator coordinator) {
            this.filesRoot = filesRoot;
            this.cacheRoot = cacheRoot;
            this.host = host;
            this.worker = worker;
            this.coordinator = coordinator;
        }

        static Scenario open(
                Context context,
                String label,
                File core,
                File content,
                Nintendo3DsLaunchReadiness.MiiRequirement requirement) throws Exception {
            String run = "n3ds-mii-recovery-" + label + "-" + System.nanoTime();
            File filesRoot = new File(context.getFilesDir(), run);
            File cacheRoot = new File(context.getCacheDir(), run);
            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    filesRoot, cacheRoot);
            Nintendo3DsExperimentalHost host = new Nintendo3DsExperimentalHost(
                    context, storage);
            CapturingExecutor worker = new CapturingExecutor();
            Nintendo3DsMiiRecoveryCoordinator coordinator =
                    new Nintendo3DsMiiRecoveryCoordinator(
                            host,
                            context.getContentResolver(),
                            worker,
                            Runnable::run,
                            new Nintendo3DsMiiRecoveryCoordinator.LaunchRequest(
                                    core,
                                    content,
                                    Nintendo3DsLaunchReadiness.ContentStatus.READY,
                                    0,
                                    requirement,
                                    true));
            return new Scenario(filesRoot, cacheRoot, host, worker, coordinator);
        }

        @Override
        public void close() throws Exception {
            Nintendo3DsMiiTestProvider.setPayload(null);
            coordinator.close();
            host.close();
            deleteTree(cacheRoot);
            deleteTree(filesRoot);
        }
    }

    private static final class CapturingExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}
