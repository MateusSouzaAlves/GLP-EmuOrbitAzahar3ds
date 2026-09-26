// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.File;

public final class Nintendo3DsProductLaunchRequestTest {
    private static final String HASH_A =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String HASH_B =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    public void acceptsOnlyExplicitAbsoluteAndPrivacyPreservingLaunchData() {
        File root = new File(System.getProperty("java.io.tmpdir")).getAbsoluteFile();
        File core = new File(root, "core.so");
        File content = new File(root, "game.3ds");

        Nintendo3DsProductLaunchRequest request = new Nintendo3DsProductLaunchRequest(
                core,
                content,
                ("n3ds-v1:" + HASH_A + ":" + HASH_B).toUpperCase(),
                Nintendo3DsLaunchReadiness.ContentStatus.ARCHIVE_REQUIRES_EXTRACTION,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.UNKNOWN,
                false);

        assertEquals(core, request.getCoreLibrary());
        assertEquals(content, request.getContent());
        assertEquals("n3ds-v1:" + HASH_A + ":" + HASH_B, request.getPersistentId());
        assertEquals(Nintendo3DsLaunchReadiness.ContentStatus.ARCHIVE_REQUIRES_EXTRACTION,
                request.getContentStatus());
        assertEquals(64L * 1024L * 1024L, request.getRequiredPrivateStorageBytes());
        assertEquals(Nintendo3DsLaunchReadiness.MiiRequirement.UNKNOWN,
                request.getMiiRequirement());
        assertFalse(request.isDeviceQualified());

        assertThrows(IllegalArgumentException.class, () -> new Nintendo3DsProductLaunchRequest(
                new File("relative-core.so"),
                content,
                "n3ds-v1:" + HASH_A + ":" + HASH_B,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                0L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true));
        assertThrows(IllegalArgumentException.class, () -> new Nintendo3DsProductLaunchRequest(
                core,
                content,
                "game-title",
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                0L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true));
        assertThrows(IllegalArgumentException.class, () -> new Nintendo3DsProductLaunchRequest(
                core,
                content,
                "n3ds-v1:" + HASH_A + ":" + HASH_B,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                -1L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true));
    }

    @Test
    public void acceptsOnlyThePinnedPackagedCoreSentinelAsRelativeCore() {
        File root = new File(System.getProperty("java.io.tmpdir")).getAbsoluteFile();
        Nintendo3DsProductLaunchRequest request = new Nintendo3DsProductLaunchRequest(
                new File(Nintendo3DsCoreBootstrap.PACKAGED_CORE_LIBRARY_PATH),
                new File(root, "game.3dsx"),
                "n3ds-v1:" + HASH_A + ":" + HASH_B,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.UNKNOWN,
                false);

        assertEquals(
                Nintendo3DsCoreBootstrap.PACKAGED_CORE_LIBRARY_PATH,
                request.getCoreLibrary().getPath());
        assertEquals(
                Nintendo3DsCoreBootstrap.PACKAGED_CORE_LIBRARY_PATH,
                Nintendo3DsProductLaunchRequest.serializeCorePath(
                        request.getCoreLibrary()));
    }
}
