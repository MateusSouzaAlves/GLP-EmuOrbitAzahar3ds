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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class Nintendo3DsStorageLayoutTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createsVersionedPrivateTopologyAndIncrementalAllowlistedCheckpoint()
            throws Exception {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("files"),
                temporaryFolder.newFolder("cache"));

        assertTrue(layout.getSystemDirectory().isDirectory());
        assertTrue(layout.getSaveDirectory().isDirectory());
        assertTrue(layout.getUserDirectory().isDirectory());
        assertTrue(layout.getCacheDirectory().isDirectory());
        assertTrue(layout.getVirtualSdDirectory().isDirectory());
        assertTrue(layout.getTransientDirectory().isDirectory());
        assertTrue(layout.getUserDirectory().toPath().startsWith(
                layout.getSaveDirectory().toPath()));
        assertFalse(layout.getTransientDirectory().toPath().startsWith(
                layout.getSaveDirectory().toPath()));
        assertFalse(layout.isSaveStatePersistenceEnabled());

        write(layout, "nand/title/0001/data/save.bin", "nand-v1");
        write(layout, "sdmc/Nintendo 3DS/private/title/extdata.bin", "sd-v1");
        write(layout, "sysdata/shared_font.bin", "system-data");
        write(layout, "config/qt-config.ini", "user-config");
        write(layout, "cheats/0001.txt", "user-cheat");
        write(layout, "cache/rebuild.bin", "cache");
        write(layout, "shaders/vulkan.bin", "shader");
        write(layout, "states/slot1.cst", "unqualified-state");
        write(layout, "log/azahar_log.txt", "private-log");
        write(layout, "dump/frame.bin", "private-dump");

        Nintendo3DsStorageLayout.CheckpointReport first =
                layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        assertEquals(1, first.getGeneration());
        assertEquals(5, first.getFileCount());
        assertEquals(5, first.getCopiedFiles());
        assertEquals(0, first.getReusedFiles());
        assertTrue(first.getCopiedBytes() > 0);
        assertTrue(first.getSnapshotManifest().isFile());
        assertTrue(layout.getLatestManifest().isFile());

        Set<String> entries = layout.latestEntryPathsForTesting();
        assertTrue(entries.contains("nand/title/0001/data/save.bin"));
        assertTrue(entries.contains("sdmc/Nintendo 3DS/private/title/extdata.bin"));
        assertTrue(entries.contains("sysdata/shared_font.bin"));
        assertTrue(entries.contains("config/qt-config.ini"));
        assertTrue(entries.contains("cheats/0001.txt"));
        assertFalse(entries.contains("cache/rebuild.bin"));
        assertFalse(entries.contains("shaders/vulkan.bin"));
        assertFalse(entries.contains("states/slot1.cst"));
        assertFalse(entries.contains("log/azahar_log.txt"));
        assertFalse(entries.contains("dump/frame.bin"));

        Nintendo3DsStorageLayout.CheckpointReport unchanged =
                layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        assertEquals(1, unchanged.getGeneration());
        assertEquals(0, unchanged.getCopiedFiles());
        assertEquals(5, unchanged.getReusedFiles());
        assertEquals(0, unchanged.getCopiedBytes());

        write(layout, "nand/title/0001/data/save.bin", "nand-v2");
        Nintendo3DsStorageLayout.CheckpointReport changed =
                layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        assertEquals(2, changed.getGeneration());
        assertEquals(1, changed.getCopiedFiles());
        assertEquals(4, changed.getReusedFiles());
    }

    @Test
    public void insufficientSpaceLeavesPublishedManifestUntouched() throws Exception {
        AtomicLong usableBytes = new AtomicLong(Long.MAX_VALUE);
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.openForTesting(
                temporaryFolder.newFolder("limited-files"),
                temporaryFolder.newFolder("limited-cache"),
                usableBytes::get);
        write(layout, "nand/title/0002/data/save.bin", "before");
        layout.checkpointAfterCoreClosed(Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        byte[] published = Files.readAllBytes(layout.getLatestManifest().toPath());

        write(layout, "nand/title/0002/data/save.bin", "after-and-larger");
        usableBytes.set(0);
        IOException failure = assertThrows(
                IOException.class,
                () -> layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION));

        assertTrue(failure.getMessage().contains("Espaço insuficiente"));
        assertArrayEquals(published, Files.readAllBytes(layout.getLatestManifest().toPath()));
    }

    @Test
    public void corruptedLatestManifestIsRebuiltFromImmutableSnapshot() throws Exception {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("corrupt-files"),
                temporaryFolder.newFolder("corrupt-cache"));
        write(layout, "sdmc/title/save.dat", "save");
        layout.checkpointAfterCoreClosed(Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        byte[] corrupt = "not-a-checkpoint\n".getBytes(StandardCharsets.UTF_8);
        Files.write(layout.getLatestManifest().toPath(), corrupt);

        Nintendo3DsStorageLayout.CheckpointReport repaired =
                layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);

        assertEquals(2, repaired.getGeneration());
        assertFalse(java.util.Arrays.equals(
                corrupt, Files.readAllBytes(layout.getLatestManifest().toPath())));
        assertTrue(layout.latestEntryPathsForTesting().contains("sdmc/title/save.dat"));
    }

    @Test
    public void restoresAllDurableTreesAndLeavesRegenerableCacheUntouched() throws Exception {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("restore-files"),
                temporaryFolder.newFolder("restore-cache"));
        write(layout, "nand/title/save.bin", "nand-checkpoint");
        write(layout, "sdmc/title/extdata.bin", "sd-checkpoint");
        write(layout, "config/core.ini", "config-checkpoint");
        write(layout, "cache/pipeline.bin", "cache-before");
        Nintendo3DsStorageLayout.CheckpointReport checkpoint =
                layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);

        write(layout, "nand/title/save.bin", "nand-current");
        Files.delete(new File(layout.getUserDirectory(), "sdmc/title/extdata.bin").toPath());
        write(layout, "sysdata/not-in-checkpoint.bin", "remove-me");
        write(layout, "cache/pipeline.bin", "cache-current");

        Nintendo3DsStorageLayout.RestoreReport restored =
                layout.restoreLatestAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);

        assertEquals(checkpoint.getGeneration(), restored.getGeneration());
        assertFalse(restored.isFallbackSnapshotUsed());
        assertEquals(3, restored.getFileCount());
        assertEquals("nand-checkpoint", read(layout, "nand/title/save.bin"));
        assertEquals("sd-checkpoint", read(layout, "sdmc/title/extdata.bin"));
        assertEquals("config-checkpoint", read(layout, "config/core.ini"));
        assertFalse(new File(
                layout.getUserDirectory(), "sysdata/not-in-checkpoint.bin").exists());
        assertEquals("cache-current", read(layout, "cache/pipeline.bin"));
    }

    @Test
    public void rejectsIncompatibleCoreAndCorruptedObjectBeforeTouchingCurrentData()
            throws Exception {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("integrity-files"),
                temporaryFolder.newFolder("integrity-cache"));
        write(layout, "nand/title/save.bin", "checkpoint");
        layout.checkpointAfterCoreClosed(Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        write(layout, "nand/title/save.bin", "current");

        IOException incompatible = assertThrows(
                IOException.class,
                () -> layout.restoreLatestAfterCoreClosed("azahar-other-revision"));
        assertTrue(incompatible.getMessage().contains("Nenhum checkpoint"));
        assertEquals("current", read(layout, "nand/title/save.bin"));

        File object;
        try (java.util.stream.Stream<java.nio.file.Path> objects =
                     Files.walk(layout.getObjectDirectoryForTesting().toPath())) {
            object = objects
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow()
                    .toFile();
        }
        Files.write(object.toPath(), "corrupt".getBytes(StandardCharsets.UTF_8));
        IOException corrupted = assertThrows(
                IOException.class,
                () -> layout.restoreLatestAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION));
        assertTrue(corrupted.getMessage().contains("Nenhum checkpoint"));
        assertEquals("current", read(layout, "nand/title/save.bin"));
    }

    @Test
    public void restoresExactSnapshotsInBothDirectionsAcrossCoreUpdate() throws Exception {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("core-update-files"),
                temporaryFolder.newFolder("core-update-cache"));
        String oldRevision = Nintendo3DsStorageLayout.CURRENT_CORE_REVISION;
        String newRevision = "azahar-2126.1-update-candidate";

        write(layout, "sdmc/title/save.dat", "old-revision-data");
        Nintendo3DsStorageLayout.CheckpointReport oldCheckpoint =
                layout.checkpointAfterCoreClosed(oldRevision);
        write(layout, "sdmc/title/save.dat", "new-revision-data");
        Nintendo3DsStorageLayout.CheckpointReport newCheckpoint =
                layout.checkpointAfterCoreClosed(newRevision);

        assertEquals(1, oldCheckpoint.getGeneration());
        assertEquals(2, newCheckpoint.getGeneration());
        write(layout, "sdmc/title/save.dat", "damaged-current-data");

        Nintendo3DsStorageLayout.RestoreReport restoredOld =
                layout.restoreLatestAfterCoreClosed(oldRevision);
        assertTrue(restoredOld.isFallbackSnapshotUsed());
        assertEquals(1, restoredOld.getGeneration());
        assertEquals("old-revision-data", read(layout, "sdmc/title/save.dat"));

        Nintendo3DsStorageLayout.RestoreReport restoredNew =
                layout.restoreLatestAfterCoreClosed(newRevision);
        assertTrue(restoredNew.isFallbackSnapshotUsed());
        assertEquals(2, restoredNew.getGeneration());
        assertEquals("new-revision-data", read(layout, "sdmc/title/save.dat"));
    }

    @Test
    public void fallsBackToNewestSnapshotWhoseObjectsRemainIntact() throws Exception {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("fallback-files"),
                temporaryFolder.newFolder("fallback-cache"));
        write(layout, "nand/title/save.bin", "generation-one");
        Nintendo3DsStorageLayout.CheckpointReport first =
                layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        write(layout, "nand/title/save.bin", "generation-two");
        Nintendo3DsStorageLayout.CheckpointReport second =
                layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        assertEquals(1, first.getGeneration());
        assertEquals(2, second.getGeneration());

        File newestObject = null;
        try (java.util.stream.Stream<java.nio.file.Path> objects =
                     Files.walk(layout.getObjectDirectoryForTesting().toPath())) {
            for (java.nio.file.Path object : (Iterable<java.nio.file.Path>) objects::iterator) {
                if (Files.isRegularFile(object)
                        && "generation-two".equals(new String(
                                Files.readAllBytes(object), StandardCharsets.UTF_8))) {
                    newestObject = object.toFile();
                    break;
                }
            }
        }
        assertTrue(newestObject != null);
        Files.write(newestObject.toPath(), "corrupt".getBytes(StandardCharsets.UTF_8));
        write(layout, "nand/title/save.bin", "current-data");

        Nintendo3DsStorageLayout.RestoreReport restored =
                layout.restoreLatestAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);

        assertTrue(restored.isFallbackSnapshotUsed());
        assertEquals(1, restored.getGeneration());
        assertEquals("generation-one", read(layout, "nand/title/save.bin"));

        write(layout, "nand/title/save.bin", "generation-three");
        Nintendo3DsStorageLayout.CheckpointReport next =
                layout.checkpointAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        assertEquals(3, next.getGeneration());
    }

    @Test
    public void injectedMidCommitFailureRollsEveryDurableTreeBack() throws Exception {
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.openForTesting(
                temporaryFolder.newFolder("rollback-files"),
                temporaryFolder.newFolder("rollback-cache"),
                () -> Long.MAX_VALUE,
                (rootName, index) -> {
                    if (index == 1) {
                        throw new IOException("injected restore failure");
                    }
                });
        write(layout, "nand/title/save.bin", "nand-checkpoint");
        write(layout, "sdmc/title/extdata.bin", "sd-checkpoint");
        layout.checkpointAfterCoreClosed(Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        write(layout, "nand/title/save.bin", "nand-current");
        write(layout, "sdmc/title/extdata.bin", "sd-current");

        IOException failure = assertThrows(
                IOException.class,
                () -> layout.restoreLatestAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION));

        assertTrue(failure.getMessage().contains("rollback"));
        assertEquals("nand-current", read(layout, "nand/title/save.bin"));
        assertEquals("sd-current", read(layout, "sdmc/title/extdata.bin"));
    }

    @Test
    public void restoreLowDiskPreflightKeepsCurrentTreeUnchanged() throws Exception {
        AtomicLong usableBytes = new AtomicLong(Long.MAX_VALUE);
        Nintendo3DsStorageLayout layout = Nintendo3DsStorageLayout.openForTesting(
                temporaryFolder.newFolder("restore-low-disk-files"),
                temporaryFolder.newFolder("restore-low-disk-cache"),
                usableBytes::get);
        write(layout, "nand/title/save.bin", "checkpoint");
        layout.checkpointAfterCoreClosed(Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        write(layout, "nand/title/save.bin", "current");
        usableBytes.set(0);

        IOException failure = assertThrows(
                IOException.class,
                () -> layout.restoreLatestAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION));

        assertTrue(failure.getMessage().contains("Espaço insuficiente"));
        assertEquals("current", read(layout, "nand/title/save.bin"));
    }

    @Test
    public void portableArchiveRoundTripsDurableTreesWithoutRegenerableData()
            throws Exception {
        Nintendo3DsStorageLayout source = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("portable-source-files"),
                temporaryFolder.newFolder("portable-source-cache"));
        write(source, "nand/title/save.bin", "portable-save");
        write(source, "sdmc/title/extdata.bin", "portable-extdata");
        write(source, "config/core.ini", "portable-config");
        write(source, "cache/pipeline.bin", "do-not-export");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        Nintendo3DsStorageLayout.PortableExportReport exported =
                source.exportPortableDataAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION, archive);

        assertEquals(3, exported.getFileCount());
        assertEquals(3, exported.getObjectCount());
        assertTrue(exported.getLogicalBytes() > 0);

        Nintendo3DsStorageLayout target = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("portable-target-files"),
                temporaryFolder.newFolder("portable-target-cache"));
        write(target, "nand/title/save.bin", "replace-me");
        write(target, "cache/pipeline.bin", "keep-me");

        Nintendo3DsStorageLayout.PortableImportReport imported =
                target.importPortableDataAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                        new ByteArrayInputStream(archive.toByteArray()));

        assertEquals(3, imported.getFileCount());
        assertEquals(3, imported.getObjectCount());
        assertEquals(exported.getLogicalBytes(), imported.getRestoredBytes());
        assertEquals("portable-save", read(target, "nand/title/save.bin"));
        assertEquals("portable-extdata", read(target, "sdmc/title/extdata.bin"));
        assertEquals("portable-config", read(target, "config/core.ini"));
        assertEquals("keep-me", read(target, "cache/pipeline.bin"));
    }

    @Test
    public void portableImportRejectsAnotherCoreBeforeTouchingCurrentData() throws Exception {
        Nintendo3DsStorageLayout source = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("portable-revision-source-files"),
                temporaryFolder.newFolder("portable-revision-source-cache"));
        write(source, "nand/title/save.bin", "foreign");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        source.exportPortableDataAfterCoreClosed("azahar-foreign-revision", archive);

        Nintendo3DsStorageLayout target = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("portable-revision-target-files"),
                temporaryFolder.newFolder("portable-revision-target-cache"));
        write(target, "nand/title/save.bin", "current");

        IOException failure = assertThrows(
                IOException.class,
                () -> target.importPortableDataAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                        new ByteArrayInputStream(archive.toByteArray())));

        assertTrue(failure.getMessage().contains("outra revisão"));
        assertEquals("current", read(target, "nand/title/save.bin"));
    }

    @Test
    public void portableImportRejectsCorruptedObjectAndUnexpectedEntries() throws Exception {
        Nintendo3DsStorageLayout source = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("portable-integrity-source-files"),
                temporaryFolder.newFolder("portable-integrity-source-cache"));
        write(source, "nand/title/save.bin", "trusted");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        source.exportPortableDataAfterCoreClosed(
                Nintendo3DsStorageLayout.CURRENT_CORE_REVISION, archive);

        Nintendo3DsStorageLayout corruptTarget = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("portable-corrupt-target-files"),
                temporaryFolder.newFolder("portable-corrupt-target-cache"));
        write(corruptTarget, "nand/title/save.bin", "current");
        IOException corruptFailure = assertThrows(
                IOException.class,
                () -> corruptTarget.importPortableDataAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                        new ByteArrayInputStream(rewriteArchive(
                                archive.toByteArray(), true, false))));
        assertTrue(corruptFailure.getMessage().contains("corrompido"));
        assertEquals("current", read(corruptTarget, "nand/title/save.bin"));

        Nintendo3DsStorageLayout extraTarget = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("portable-extra-target-files"),
                temporaryFolder.newFolder("portable-extra-target-cache"));
        write(extraTarget, "nand/title/save.bin", "current");
        IOException extraFailure = assertThrows(
                IOException.class,
                () -> extraTarget.importPortableDataAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                        new ByteArrayInputStream(rewriteArchive(
                                archive.toByteArray(), false, true))));
        assertTrue(extraFailure.getMessage().contains("inesperadas"));
        assertEquals("current", read(extraTarget, "nand/title/save.bin"));
    }

    private static byte[] rewriteArchive(
            byte[] source,
            boolean corruptFirstObject,
            boolean appendUnexpectedEntry) throws IOException {
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        boolean corrupted = false;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream output = new ZipOutputStream(rewritten)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (corruptFirstObject
                            && !corrupted
                            && entry.getName().startsWith("objects/")
                            && count > 0) {
                        buffer[0] ^= 0x01;
                        corrupted = true;
                    }
                    output.write(buffer, 0, count);
                }
                output.closeEntry();
                input.closeEntry();
            }
            if (appendUnexpectedEntry) {
                output.putNextEntry(new ZipEntry("unexpected.bin"));
                output.write(1);
                output.closeEntry();
            }
        }
        return rewritten.toByteArray();
    }

    private static void write(Nintendo3DsStorageLayout layout, String path, String value)
            throws IOException {
        File target = new File(layout.getUserDirectory(), path.replace('/', File.separatorChar));
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Nintendo3DsStorageLayout layout, String path) throws IOException {
        File target = new File(layout.getUserDirectory(), path.replace('/', File.separatorChar));
        return new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
    }
}
