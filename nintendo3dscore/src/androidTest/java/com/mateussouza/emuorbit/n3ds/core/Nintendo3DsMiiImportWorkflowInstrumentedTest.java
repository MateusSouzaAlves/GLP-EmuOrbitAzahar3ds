// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsMiiImportWorkflowInstrumentedTest {
    @Test
    public void serializesSafImportPreservesPriorDataAndSuppressesClosedCallbacks()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String run = "n3ds-mii-workflow-" + System.nanoTime();
        File filesRoot = new File(context.getFilesDir(), run);
        File cacheRoot = new File(context.getCacheDir(), run);
        Nintendo3DsExperimentalHost host = null;
        try {
            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    filesRoot, cacheRoot);
            host = new Nintendo3DsExperimentalHost(context, storage);
            CapturingExecutor worker = new CapturingExecutor();
            Nintendo3DsMiiImportWorkflow workflow = new Nintendo3DsMiiImportWorkflow(
                    host,
                    context.getContentResolver(),
                    worker,
                    Runnable::run);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failureCount = new AtomicInteger();
            AtomicReference<Nintendo3DsMiiDataManager.ImportReport> report =
                    new AtomicReference<>();
            AtomicReference<IOException> failure = new AtomicReference<>();
            Nintendo3DsMiiImportWorkflow.Listener listener =
                    new Nintendo3DsMiiImportWorkflow.Listener() {
                        @Override
                        public void onImported(Nintendo3DsMiiDataManager.ImportReport imported) {
                            report.set(imported);
                            successCount.incrementAndGet();
                        }

                        @Override
                        public void onImportFailed(IOException importFailure) {
                            failure.set(importFailure);
                            failureCount.incrementAndGet();
                        }
                    };

            assertFalse(workflow.importSelected(null, listener));
            byte[] firstDatabase = syntheticMiiDatabase(1);
            Nintendo3DsMiiTestProvider.setPayload(firstDatabase);
            assertTrue(workflow.importSelected(Nintendo3DsMiiTestProvider.DOCUMENT_URI, listener));
            assertTrue(workflow.isImportInFlight());
            assertFalse(workflow.importSelected(
                    Nintendo3DsMiiTestProvider.DOCUMENT_URI, listener));
            assertEquals(1, worker.size());
            worker.runNext();

            assertFalse(workflow.isImportInFlight());
            assertEquals(1, successCount.get());
            assertEquals(0, failureCount.get());
            assertNotNull(report.get());
            assertEquals(1, report.get().getVisibleMiiCount());
            assertNull(failure.get());
            assertEquals(Nintendo3DsMiiDataManager.Status.READY,
                    host.getMiiDataManagerForTesting().inspect().getStatus());
            File destination = host.getMiiDataManagerForTesting().getDatabaseFile();
            assertArrayEquals(firstDatabase, Files.readAllBytes(destination.toPath()));

            Nintendo3DsMiiTestProvider.setPayload(new byte[32]);
            assertTrue(workflow.importSelected(Nintendo3DsMiiTestProvider.DOCUMENT_URI, listener));
            worker.runNext();
            assertEquals(1, successCount.get());
            assertEquals(1, failureCount.get());
            assertNotNull(failure.get());
            assertArrayEquals(firstDatabase, Files.readAllBytes(destination.toPath()));

            Nintendo3DsMiiTestProvider.setPayload(syntheticMiiDatabase(2));
            assertTrue(workflow.importSelected(Nintendo3DsMiiTestProvider.DOCUMENT_URI, listener));
            workflow.close();
            worker.runNext();
            assertFalse(workflow.isImportInFlight());
            assertEquals(1, successCount.get());
            assertEquals(1, failureCount.get());
            assertFalse(workflow.importSelected(
                    Nintendo3DsMiiTestProvider.DOCUMENT_URI, listener));
        } finally {
            Nintendo3DsMiiTestProvider.setPayload(null);
            if (host != null) {
                host.close();
            }
            deleteTree(cacheRoot);
            deleteTree(filesRoot);
        }
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

    private static final class CapturingExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
