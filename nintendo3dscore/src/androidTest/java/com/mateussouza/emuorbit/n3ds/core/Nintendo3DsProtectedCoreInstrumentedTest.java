// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Physical proof that the packaged core is authenticated and loaded without a plaintext file. */
@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsProtectedCoreInstrumentedTest {
    private static final long RAW_CORE_BYTES = 23_157_736L;
    private static final String PERSISTENT_ID =
            "n3ds-v1:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef:"
                    + "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    public void packagedCoreLoadsTwiceAndLeavesNoPlaintextCoreInPrivateStorage()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Nintendo3DsCoreInfo first = Nintendo3DsCoreBootstrap.inspectPackaged(context);
        Nintendo3DsCoreInfo second = Nintendo3DsCoreBootstrap.inspectPackaged(context);

        assertEquals("Azahar", first.getLibraryName());
        assertEquals(first.getLibraryName(), second.getLibraryName());
        assertEquals(first.getLibraryVersion(), second.getLibraryVersion());
        assertTrue(first.needsFullPath());
        assertTrue(first.getValidExtensions().contains("3ds"));
        assertTrue(first.getValidExtensions().contains("3dsx"));
        assertTrue(first.getValidExtensions().contains("cci"));
        assertTrue(first.getValidExtensions().contains("cxi"));
        assertFalse(containsPlaintextCore(context.getFilesDir()));
        assertFalse(containsPlaintextCore(context.getCacheDir()));
        assertFalse(containsPlaintextCore(context.getCodeCacheDir()));
        assertFalse(containsPlaintextCore(context.getNoBackupFilesDir()));
    }

    @Test
    public void packagedCoreReachesGameplayThroughTheProductPath() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Nintendo3DsProductLaunchRequest(
                new File(Nintendo3DsCoreBootstrap.PACKAGED_CORE_LIBRARY_PATH),
                content,
                PERSISTENT_ID,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true).createIntent(context);

        try (ActivityScenario<Nintendo3DsProductActivity> scenario =
                     ActivityScenario.launch(intent)) {
            long deadline = SystemClock.uptimeMillis() + 120_000L;
            AtomicBoolean rendered = new AtomicBoolean();
            while (!rendered.get() && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity(activity -> rendered.set(
                        activity.getRenderedFrameCountForTesting() >= 20));
                if (!rendered.get()) {
                    SystemClock.sleep(250L);
                }
            }
            assertTrue("O core protegido não apresentou frames no fluxo de produto", rendered.get());
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                assertEquals(
                        Nintendo3DsProductUiState.SessionState.RUNNING,
                        activity.getUiStateForTesting().getSessionState());
            });
        }
    }

    @Test
    public void packagedCoreRunsFiveContentsAcrossProductLifecycle() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File[] contents = new File[5];
        Set<String> digests = new HashSet<>();
        for (int index = 0; index < contents.length; index++) {
            contents[index] = new File(arguments.getString(
                    "n3dsContentPath" + (index + 1), "")).getCanonicalFile();
            assumeTrue("Teste real requer cinco conteúdos 3DS", contents[index].isFile());
            assertTrue("Os cinco conteúdos precisam ser binariamente distintos",
                    digests.add(sha256(contents[index])));
        }

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long renderedFrames = 0L;
        for (int index = 0; index < contents.length; index++) {
            Intent intent = new Nintendo3DsProductLaunchRequest(
                    new File(Nintendo3DsCoreBootstrap.PACKAGED_CORE_LIBRARY_PATH),
                    contents[index],
                    PERSISTENT_ID,
                    Nintendo3DsLaunchReadiness.ContentStatus.READY,
                    64L * 1024L * 1024L,
                    Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                    true).createIntent(context);
            try (ActivityScenario<Nintendo3DsProductActivity> scenario =
                         ActivityScenario.launch(intent)) {
                awaitFrames(scenario, 12L);
                if (index == 0) {
                    AtomicLong beforePause = new AtomicLong();
                    scenario.onActivity(activity -> beforePause.set(
                            activity.getRenderedFrameCountForTesting()));
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
                    awaitFrames(scenario, beforePause.get() + 4L);
                } else if (index == 1) {
                    scenario.recreate();
                    awaitFrames(scenario, 12L);
                } else if (index == 2) {
                    scenario.onActivity(activity -> activity.setRequestedOrientation(
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                    awaitOrientationAndFrames(
                            scenario, Configuration.ORIENTATION_LANDSCAPE, 12L);
                    scenario.onActivity(activity -> activity.setRequestedOrientation(
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
                    awaitOrientationAndFrames(
                            scenario, Configuration.ORIENTATION_PORTRAIT, 12L);
                }
                AtomicLong observed = new AtomicLong();
                scenario.onActivity(activity -> {
                    assertNull(activity.getProductFailureForTesting());
                    assertEquals(
                            Nintendo3DsProductUiState.SessionState.RUNNING,
                            activity.getUiStateForTesting().getSessionState());
                    observed.set(activity.getRenderedFrameCountForTesting());
                });
                renderedFrames += observed.get();
            }
            assertFalse(containsPlaintextCore(context.getFilesDir()));
            assertFalse(containsPlaintextCore(context.getCacheDir()));
            assertFalse(containsPlaintextCore(context.getCodeCacheDir()));
            assertFalse(containsPlaintextCore(context.getNoBackupFilesDir()));
        }
        System.out.println(String.format(
                Locale.ROOT,
                "N3DS_PROTECTED_FIVE_CONTENTS status=PASS contents=5 frames=%d "
                        + "pauseResume=1 recreation=1 rotation=1 closes=5 plaintextResidual=0",
                renderedFrames));
    }

    private static void awaitFrames(
            ActivityScenario<Nintendo3DsProductActivity> scenario,
            long minimumFrames) {
        long deadline = SystemClock.uptimeMillis() + 120_000L;
        AtomicBoolean ready = new AtomicBoolean();
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                ready.set(activity.getRenderedFrameCountForTesting() >= minimumFrames);
            });
            if (!ready.get()) {
                SystemClock.sleep(250L);
            }
        }
        assertTrue("O core protegido não apresentou frames dentro do prazo", ready.get());
    }

    private static void awaitOrientationAndFrames(
            ActivityScenario<Nintendo3DsProductActivity> scenario,
            int orientation,
            long minimumFrames) {
        long deadline = SystemClock.uptimeMillis() + 120_000L;
        AtomicBoolean ready = new AtomicBoolean();
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                assertNull(activity.getProductFailureForTesting());
                ready.set(activity.getResources().getConfiguration().orientation == orientation
                        && activity.getRenderedFrameCountForTesting() >= minimumFrames);
            });
            if (!ready.get()) {
                SystemClock.sleep(250L);
            }
        }
        assertTrue("O core protegido não estabilizou após a rotação", ready.get());
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (FileInputStream input = new FileInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder encoded = new StringBuilder(64);
            for (byte value : digest.digest()) {
                encoded.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean containsPlaintextCore(File root) throws IOException {
        if (root == null || !root.exists()) {
            return false;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (containsPlaintextCore(child)) {
                    return true;
                }
            } else if (child.length() == RAW_CORE_BYTES && hasElfMagic(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasElfMagic(File file) throws IOException {
        byte[] magic = new byte[4];
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read(magic) == magic.length
                    && magic[0] == 0x7f
                    && magic[1] == 'E'
                    && magic[2] == 'L'
                    && magic[3] == 'F';
        }
    }
}
