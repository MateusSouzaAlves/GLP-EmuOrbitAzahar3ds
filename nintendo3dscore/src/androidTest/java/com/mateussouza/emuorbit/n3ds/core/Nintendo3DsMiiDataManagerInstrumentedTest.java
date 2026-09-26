// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsMiiDataManagerInstrumentedTest {
    @Test
    public void exposesOpenDocumentContractAndPublishesInsideAndroidPrivateStorage()
            throws Exception {
        Intent intent = Nintendo3DsMiiDataManager.createSafImportIntent();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("application/octet-stream", intent.getType());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String run = "n3ds-mii-import-" + System.nanoTime();
        File filesRoot = new File(context.getFilesDir(), run);
        File cacheRoot = new File(context.getCacheDir(), run);
        try {
            Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                    filesRoot,
                    cacheRoot);
            Nintendo3DsMiiDataManager manager = new Nintendo3DsMiiDataManager(layout);
            assertThrows(
                    java.io.IOException.class,
                    () -> manager.importFromSafAfterCoreClosed(
                            context.getContentResolver(),
                            Uri.fromFile(new File(context.getFilesDir(), "not-saf.dat"))));
            byte[] database = new byte[0x8 + 100 * 0x5C];
            database[0x8 + 0xC] = 1;

            Nintendo3DsMiiDataManager.ImportReport report =
                    manager.importFromStreamForTesting(new ByteArrayInputStream(database));

            assertEquals(database.length, report.getCopiedBytes());
            assertEquals(1, report.getVisibleMiiCount());
            assertTrue(manager.getDatabaseFile().isFile());
            assertTrue(manager.getDatabaseFile().getCanonicalPath().startsWith(
                    layout.getUserDirectory().getCanonicalPath() + File.separator));
            assertEquals(Nintendo3DsMiiDataManager.Status.READY, manager.inspect().getStatus());
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
