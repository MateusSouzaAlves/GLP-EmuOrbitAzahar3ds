// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class Nintendo3DsRegenerableCachePolicyTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void measuresAndClearsOnlyExplicitRegenerableTrees() throws Exception {
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                temporaryFolder.newFolder("files"),
                temporaryFolder.newFolder("cache"));
        File durable = write(storage.getUserDirectory(), "nand/title/save.bin", "durable");
        File coreCache = write(storage.getCacheDirectory(), "pipeline/cache.bin", "cache");
        File shaderCache = write(storage.getShaderCacheDirectory(), "vulkan/shader.bin", "shader");
        File transientFile = write(storage.getTransientDirectory(), "session/tmp.bin", "temp");
        Nintendo3DsRegenerableCachePolicy policy =
                new Nintendo3DsRegenerableCachePolicy(storage);

        Nintendo3DsRegenerableCachePolicy.Report before = policy.inspect();
        assertEquals(3, before.getTotalFiles());
        assertEquals(15, before.getTotalBytes());

        Nintendo3DsRegenerableCachePolicy.Report removed = policy.clearAfterCoreClosed();
        assertEquals(before.getTotalFiles(), removed.getTotalFiles());
        assertEquals(before.getTotalBytes(), removed.getTotalBytes());
        assertTrue(durable.isFile());
        assertFalse(coreCache.exists());
        assertFalse(shaderCache.exists());
        assertFalse(transientFile.exists());
        assertTrue(storage.getCacheDirectory().isDirectory());
        assertTrue(storage.getShaderCacheDirectory().isDirectory());
        assertTrue(storage.getTransientDirectory().isDirectory());
        assertEquals(0, policy.inspect().getTotalFiles());
    }

    private static File write(File root, String relativePath, String contents) throws Exception {
        File target = new File(root, relativePath.replace('/', File.separatorChar));
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return target;
    }
}
