// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Nintendo3DsMiiDataManagerTest {
    private static final int HEADER_BYTES = 0x8;
    private static final int RECORD_BYTES = 0x5C;
    private static final int MII_ID_OFFSET = 0xC;
    private static final int VISIBLE_DATABASE_BYTES = HEADER_BYTES + 100 * RECORD_BYTES;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesPinnedAzaharSharedExtdataPathAndReportsMissing() throws Exception {
        Nintendo3DsMiiDataManager manager = createManager("missing");

        assertEquals(
                "nand/data/00000000000000000000000000000000/"
                        + "extdata/00048000/F000000B/user/CFL_DB.dat",
                manager.getDatabaseFile().toPath()
                        .subpath(
                                manager.getDatabaseFile().toPath().getNameCount() - 8,
                                manager.getDatabaseFile().toPath().getNameCount())
                        .toString()
                        .replace(File.separatorChar, '/'));
        Nintendo3DsMiiDataManager.Inspection inspection = manager.inspect();
        assertEquals(Nintendo3DsMiiDataManager.Status.MISSING, inspection.getStatus());
        assertFalse(inspection.isReady());
        assertEquals(0, inspection.getFileBytes());
        assertEquals(0, inspection.getVisibleMiiCount());
    }

    @Test
    public void distinguishesMalformedEmptyAndCoreVisibleMiiRecords() throws Exception {
        Nintendo3DsMiiDataManager manager = createManager("inspection");
        File database = manager.getDatabaseFile();
        assertTrue(database.getParentFile().mkdirs());

        Files.write(database.toPath(), new byte[VISIBLE_DATABASE_BYTES - 1]);
        assertEquals(Nintendo3DsMiiDataManager.Status.MALFORMED,
                manager.inspect().getStatus());

        byte[] data = new byte[VISIBLE_DATABASE_BYTES];
        Files.write(database.toPath(), data);
        assertEquals(Nintendo3DsMiiDataManager.Status.EMPTY, manager.inspect().getStatus());

        markMiiVisible(data, 0, 0x12);
        markMiiVisible(data, 99, 0x34);
        Files.write(database.toPath(), data);
        Nintendo3DsMiiDataManager.Inspection ready = manager.inspect();
        assertEquals(Nintendo3DsMiiDataManager.Status.READY, ready.getStatus());
        assertTrue(ready.isReady());
        assertEquals(2, ready.getVisibleMiiCount());
    }

    @Test
    public void ignoresRecordsBeyondAzaharsOneHundredMiiWindow() throws Exception {
        Nintendo3DsMiiDataManager manager = createManager("bounded-window");
        File database = manager.getDatabaseFile();
        assertTrue(database.getParentFile().mkdirs());
        byte[] data = new byte[HEADER_BYTES + 101 * RECORD_BYTES];
        markMiiVisible(data, 100, 0x7f);
        Files.write(database.toPath(), data);

        Nintendo3DsMiiDataManager.Inspection inspection = manager.inspect();
        assertEquals(Nintendo3DsMiiDataManager.Status.EMPTY, inspection.getStatus());
        assertEquals(0, inspection.getVisibleMiiCount());
    }

    @Test
    public void importsReadyDatabaseWithBoundedAtomicPublication() throws Exception {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("import-files"),
                temporaryFolder.newFolder("import-cache"));
        Nintendo3DsMiiDataManager blocked = new Nintendo3DsMiiDataManager(layout, () -> true);
        byte[] data = new byte[310_560];
        markMiiVisible(data, 4, 0x45);
        IOException activeSession = assertThrows(
                IOException.class,
                () -> blocked.importFromStreamForTesting(new ByteArrayInputStream(data)));
        assertTrue(activeSession.getMessage().contains("Feche a sessão"));
        assertFalse(blocked.getDatabaseFile().exists());

        AtomicBoolean becameActive = new AtomicBoolean();
        Nintendo3DsMiiDataManager guarded =
                new Nintendo3DsMiiDataManager(layout, becameActive::get);
        InputStream activatesDuringCopy = new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                int count = super.read(target, offset, length);
                if (count > 0) {
                    becameActive.set(true);
                }
                return count;
            }
        };
        assertThrows(
                IOException.class,
                () -> guarded.importFromStreamForTesting(activatesDuringCopy));
        assertFalse(guarded.getDatabaseFile().exists());

        becameActive.set(false);
        Nintendo3DsMiiDataManager manager =
                new Nintendo3DsMiiDataManager(layout, becameActive::get);

        Nintendo3DsMiiDataManager.ImportReport report =
                manager.importFromStreamForTesting(new ByteArrayInputStream(data));

        assertEquals(data.length, report.getCopiedBytes());
        assertEquals(64, report.getSha256().length());
        assertEquals(1, report.getVisibleMiiCount());
        assertArrayEquals(data, Files.readAllBytes(manager.getDatabaseFile().toPath()));
        assertEquals(Nintendo3DsMiiDataManager.Status.READY, manager.inspect().getStatus());
        assertNoPendingFiles(manager.getDatabaseFile().getParentFile());
    }

    @Test
    public void rejectedEmptyOrOversizedImportPreservesExistingDatabase() throws Exception {
        Nintendo3DsMiiDataManager manager = createManager("rollback");
        byte[] original = new byte[VISIBLE_DATABASE_BYTES];
        markMiiVisible(original, 2, 0x21);
        manager.importFromStreamForTesting(new ByteArrayInputStream(original));

        IOException empty = assertThrows(
                IOException.class,
                () -> manager.importFromStreamForTesting(
                        new ByteArrayInputStream(new byte[VISIBLE_DATABASE_BYTES])));
        assertTrue(empty.getMessage().contains("não contém um Mii"));
        assertArrayEquals(original, Files.readAllBytes(manager.getDatabaseFile().toPath()));

        IOException oversized = assertThrows(
                IOException.class,
                () -> manager.importFromStreamForTesting(new ByteArrayInputStream(
                        new byte[(int) Nintendo3DsMiiDataManager.MAX_DATABASE_BYTES + 1])));
        assertTrue(oversized.getMessage().contains("1 MiB"));
        assertArrayEquals(original, Files.readAllBytes(manager.getDatabaseFile().toPath()));
        assertNoPendingFiles(manager.getDatabaseFile().getParentFile());
    }

    @Test
    public void sourceReadFailurePreservesExistingDatabaseAndRemovesStagingFile()
            throws Exception {
        Nintendo3DsMiiDataManager manager = createManager("read-failure");
        byte[] original = new byte[VISIBLE_DATABASE_BYTES];
        markMiiVisible(original, 7, 0x66);
        manager.importFromStreamForTesting(new ByteArrayInputStream(original));

        InputStream failing = new InputStream() {
            private int remaining = 100;

            @Override
            public int read() throws IOException {
                if (remaining-- > 0) {
                    return 1;
                }
                throw new IOException("synthetic read failure");
            }
        };
        assertThrows(IOException.class, () -> manager.importFromStreamForTesting(failing));

        assertArrayEquals(original, Files.readAllBytes(manager.getDatabaseFile().toPath()));
        assertNoPendingFiles(manager.getDatabaseFile().getParentFile());
    }

    @Test
    public void sourceThatNeverMakesProgressIsRejectedWithoutPublishing() throws Exception {
        Nintendo3DsMiiDataManager manager = createManager("no-progress");
        InputStream stalled = new InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                return 0;
            }
        };

        IOException failure = assertThrows(
                IOException.class,
                () -> manager.importFromStreamForTesting(stalled));

        assertTrue(failure.getMessage().contains("parou de fornecer dados"));
        assertFalse(manager.getDatabaseFile().exists());
        assertNoPendingFiles(manager.getDatabaseFile().getParentFile());
    }

    private Nintendo3DsMiiDataManager createManager(String name) throws IOException {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder(name + "-files"),
                temporaryFolder.newFolder(name + "-cache"));
        return new Nintendo3DsMiiDataManager(layout);
    }

    private static void markMiiVisible(byte[] data, int record, int value) {
        data[HEADER_BYTES + record * RECORD_BYTES + MII_ID_OFFSET] = (byte) value;
    }

    private static void assertNoPendingFiles(File directory) {
        File[] pending = directory.listFiles((ignored, name) -> name.endsWith(".pending"));
        assertTrue(pending != null);
        assertEquals(0, pending.length);
    }
}
