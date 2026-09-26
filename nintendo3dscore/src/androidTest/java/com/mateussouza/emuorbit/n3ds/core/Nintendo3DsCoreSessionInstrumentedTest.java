// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsCoreSessionInstrumentedTest {
    private static final String PROCESS_RECOVERY_ROOT = "n3ds-forced-process-recovery";

    @Test
    public void preparesTransientLifecycleStateForForcedProcessDeath() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = runtimePath(arguments, "n3dsCorePath", "process-state-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "process-state-content.3ds");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File filesRoot = new File(context.getFilesDir(), PROCESS_RECOVERY_ROOT);
        File cacheRoot = new File(context.getCacheDir(), PROCESS_RECOVERY_ROOT);
        deleteExactTestTree(filesRoot, context.getFilesDir());
        deleteExactTestTree(cacheRoot, context.getCacheDir());
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(filesRoot, cacheRoot);

        try (Nintendo3DsTestSurfaceOwner surface =
                     new Nintendo3DsTestSurfaceOwner(400, 480);
             Nintendo3DsCoreLifecycleController controller =
                     new Nintendo3DsCoreLifecycleController(
                             context,
                             corePath,
                             contentPath,
                             storage,
                             Nintendo3DsStorageLayout.CURRENT_CORE_REVISION)) {
            controller.setAudioEnabled(false);
            controller.onSurfaceAvailable(surface.surface, 400, 480);
            controller.onResume();
            controller.runFrames(5, 120_000);
            controller.onPauseAndAwait(120_000);
            assertEquals(1, controller.getCapturedRecoveryStateCount());
            assertNull(controller.getLastRecoveryStateFailure());
        }
        File lifecycleDirectory = new File(storage.getTransientDirectory(), "lifecycle");
        File[] states = lifecycleDirectory.listFiles((directory, name) ->
                name.endsWith(".state"));
        assertTrue(states != null && states.length == 1 && states[0].length() > 0);
        System.out.println("N3DS_PROCESS_RECOVERY_PREPARED bytes=" + states[0].length());
    }

    @Test
    public void restoresTransientLifecycleStateAfterForcedProcessDeath() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = runtimePath(arguments, "n3dsCorePath", "process-state-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "process-state-content.3ds");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File filesRoot = new File(context.getFilesDir(), PROCESS_RECOVERY_ROOT);
        File cacheRoot = new File(context.getCacheDir(), PROCESS_RECOVERY_ROOT);
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(filesRoot, cacheRoot);
        File lifecycleDirectory = new File(storage.getTransientDirectory(), "lifecycle");
        File[] states = lifecycleDirectory.listFiles((directory, name) ->
                name.endsWith(".state"));
        assumeTrue(
                "Execute preparesTransientLifecycleStateForForcedProcessDeath antes deste gate.",
                states != null && states.length == 1 && states[0].length() > 0);

        try {
            try (Nintendo3DsTestSurfaceOwner surface =
                         new Nintendo3DsTestSurfaceOwner(400, 480);
                 Nintendo3DsCoreLifecycleController controller =
                         new Nintendo3DsCoreLifecycleController(
                                 context,
                                 corePath,
                                 contentPath,
                                 storage,
                                 Nintendo3DsStorageLayout.CURRENT_CORE_REVISION)) {
                controller.setAudioEnabled(false);
                controller.onSurfaceAvailable(surface.surface, 400, 480);
                controller.onResume();
                Nintendo3DsCoreFrameReport restored = controller.runFrames(2, 120_000);
                assertTrue(restored.getPresentedFrames() >= 2);
                assertEquals(1, controller.getRestoredRecoveryStateCount());
                assertNull(controller.getLastRecoveryStateFailure());
            }
            File[] leftovers = lifecycleDirectory.listFiles((directory, name) ->
                    name.endsWith(".state") || name.endsWith(".state.tmp"));
            assertTrue(leftovers == null || leftovers.length == 0);
            System.out.println("N3DS_PROCESS_RECOVERY_RESTORED=1");
        } finally {
            deleteExactTestTree(filesRoot, context.getFilesDir());
            deleteExactTestTree(cacheRoot, context.getCacheDir());
        }
    }

    @Test
    public void restoresTransientLifecycleStateAcrossFreshController() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = runtimePath(arguments, "n3dsCorePath", "lifecycle-state-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "lifecycle-state-content.3ds");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testRoot = new File(
                context.getCacheDir(), "n3ds-lifecycle-state-" + System.nanoTime());
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                new File(testRoot, "files"),
                new File(testRoot, "cache"));
        try {
            try (Nintendo3DsTestSurfaceOwner firstSurface =
                         new Nintendo3DsTestSurfaceOwner(400, 480);
                 Nintendo3DsCoreLifecycleController firstController =
                         new Nintendo3DsCoreLifecycleController(
                                 context,
                                 corePath,
                                 contentPath,
                                 storage,
                                 Nintendo3DsStorageLayout.CURRENT_CORE_REVISION)) {
                firstController.setAudioEnabled(false);
                firstController.onSurfaceAvailable(firstSurface.surface, 400, 480);
                firstController.onResume();
                firstController.runFrames(5, 120_000);
                firstController.onPauseAndAwait(120_000);
                assertEquals(1, firstController.getCapturedRecoveryStateCount());
                assertNull(firstController.getLastRecoveryStateFailure());
            }

            try (Nintendo3DsTestSurfaceOwner restoredSurface =
                         new Nintendo3DsTestSurfaceOwner(400, 480);
                 Nintendo3DsCoreLifecycleController restoredController =
                         new Nintendo3DsCoreLifecycleController(
                                 context,
                                 corePath,
                                 contentPath,
                                 storage,
                                 Nintendo3DsStorageLayout.CURRENT_CORE_REVISION)) {
                restoredController.setAudioEnabled(false);
                restoredController.onSurfaceAvailable(restoredSurface.surface, 400, 480);
                restoredController.onResume();
                Nintendo3DsCoreFrameReport restored =
                        restoredController.runFrames(2, 120_000);
                assertTrue(restored.getPresentedFrames() >= 2);
                assertEquals(1, restoredController.getRestoredRecoveryStateCount());
                assertNull(restoredController.getLastRecoveryStateFailure());
            }

            File lifecycleDirectory = new File(storage.getTransientDirectory(), "lifecycle");
            File[] leftovers = lifecycleDirectory.listFiles((directory, name) ->
                    name.endsWith(".state") || name.endsWith(".state.tmp"));
            assertTrue(leftovers == null || leftovers.length == 0);
        } finally {
            deleteExactTestTree(testRoot, context.getCacheDir());
        }
    }

    @Test
    public void sustainsSelectedPerformanceProfileForLongRun() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue(
                "Gate longo 3DS exige -e n3dsLongRunEnabled true.",
                "true".equalsIgnoreCase(arguments.getString("n3dsLongRunEnabled", "false")));
        String corePath = runtimePath(arguments, "n3dsCorePath", "adreno-gate-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "adreno-gate-content.3dsx");
        int durationMinutes = boundedArgument(
                arguments, "n3dsLongRunMinutes", 20, 20, 30);
        int maximumPssGrowthKb = boundedArgument(
                arguments, "n3dsLongRunMaxPssGrowthKb", 524_288, 65_536, 1_048_576);
        Nintendo3DsPerformanceProfile profile = Nintendo3DsPerformanceProfile.valueOf(
                arguments.getString("n3dsPerformanceProfile", "balanced")
                        .trim()
                        .toUpperCase(java.util.Locale.ROOT));
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testRoot = new File(
                context.getCacheDir(),
                "n3ds-long-run-" + profile.getCoreValue() + "-" + System.nanoTime());
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                new File(testRoot, "files"),
                new File(testRoot, "cache"));

        try {
            try (Nintendo3DsTestSurfaceOwner surface = new Nintendo3DsTestSurfaceOwner(400, 480);
                 Nintendo3DsCoreLifecycleController controller =
                         new Nintendo3DsCoreLifecycleController(
                                 context,
                                 corePath,
                                 contentPath,
                                 storage,
                                 Nintendo3DsStorageLayout.CURRENT_CORE_REVISION)) {
                controller.setPerformanceProfile(profile);
                controller.setAudioVolume(0.0f);
                controller.setAudioEnabled(true);
                controller.onSurfaceAvailable(surface.surface, 400, 480);
                controller.onResume();

                controller.runFrames(180, 120_000);
                controller.resetPerformanceMeasurementsAndAwait(120_000);
                Nintendo3DsPerformanceSnapshot baseline = controller.getPerformanceSnapshot();
                long baselinePssKb = baseline.getDeviceState().getProcessPssKilobytes();
                long peakPssKb = baselinePssKb;
                int peakThermalStatus = baseline.getDeviceState().getThermalStatus();
                boolean powerSaveObserved = baseline.getDeviceState().isPowerSaveMode();
                long startedAt = SystemClock.elapsedRealtime();
                long deadline = startedAt + TimeUnit.MINUTES.toMillis(durationMinutes);
                long nextDeviceSample = startedAt + TimeUnit.MINUTES.toMillis(1);

                do {
                    controller.runFrames(120, 120_000);
                    long now = SystemClock.elapsedRealtime();
                    if (now >= nextDeviceSample || now >= deadline) {
                        Nintendo3DsDevicePerformanceState device =
                                controller.getPerformanceSnapshot().getDeviceState();
                        peakPssKb = Math.max(peakPssKb, device.getProcessPssKilobytes());
                        peakThermalStatus = Math.max(peakThermalStatus, device.getThermalStatus());
                        powerSaveObserved |= device.isPowerSaveMode();
                        nextDeviceSample = now + TimeUnit.MINUTES.toMillis(1);
                    }
                } while (SystemClock.elapsedRealtime() < deadline);

                long elapsedMillis = SystemClock.elapsedRealtime() - startedAt;
                Nintendo3DsPerformanceSnapshot result = controller.getPerformanceSnapshot();
                Nintendo3DsAudioOutputReport audio = controller.getAudioOutputReport();
                long pssGrowthKb = Math.max(0, peakPssKb - baselinePssKb);
                assertTrue(elapsedMillis >= TimeUnit.MINUTES.toMillis(durationMinutes));
                assertTrue(result.getMeasuredFrames() > 0);
                assertTrue(result.getPercentileSampleFrames() > 0);
                assertTrue(result.getPercentileSampleFrames()
                        <= Nintendo3DsPerformanceCollector.PERCENTILE_CAPACITY_FRAMES);
                assertTrue(result.getAverageFrameMillis() > 0);
                assertTrue(result.getP95FrameMillis() >= result.getP50FrameMillis());
                assertTrue(result.getP99FrameMillis() >= result.getP95FrameMillis());
                assertTrue(result.getMaximumFrameMillis() >= result.getP99FrameMillis());
                assertTrue("Perfil 3DS não sustentou o piso de 95%.",
                        result.getEffectiveSpeedPercent() >= 95.0);
                assertEquals(0, result.getAudioDroppedFrames());
                assertEquals(0, audio.getDroppedFrames());
                assertEquals(0, audio.getOutputFailures());
                assertTrue("Crescimento PSS do long run 3DS excedeu o teto.",
                        pssGrowthKb <= maximumPssGrowthKb);

                System.out.println(
                        "N3DS_LONG_RUN profile=" + profile.getCoreValue()
                                + " resolution=" + profile.getResolutionScale()
                                + " elapsedMs=" + elapsedMillis
                                + " frames=" + result.getMeasuredFrames()
                                + " samples=" + result.getPercentileSampleFrames()
                                + " avgMs=" + result.getAverageFrameMillis()
                                + " p50Ms=" + result.getP50FrameMillis()
                                + " p95Ms=" + result.getP95FrameMillis()
                                + " p99Ms=" + result.getP99FrameMillis()
                                + " maxMs=" + result.getMaximumFrameMillis()
                                + " speedPercent=" + result.getEffectiveSpeedPercent()
                                + " presented=" + result.getPresentedFrames()
                                + " audioDropped=" + result.getAudioDroppedFrames()
                                + " outputDropped=" + audio.getDroppedFrames()
                                + " outputFailures=" + audio.getOutputFailures()
                                + " baselinePssKb=" + baselinePssKb
                                + " peakPssKb=" + peakPssKb
                                + " pssGrowthKb=" + pssGrowthKb
                                + " peakThermal=" + peakThermalStatus
                                + " powerSave=" + powerSaveObserved);
            }
        } finally {
            deleteExactTestTree(testRoot, context.getCacheDir());
        }
    }

    @Test
    public void comparesAllPerformanceProfilesInShortRealGameRun() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue(
                "Comparação curta exige -e n3dsQuickProfileComparison true.",
                "true".equalsIgnoreCase(
                        arguments.getString("n3dsQuickProfileComparison", "false")));
        String corePath = runtimePath(arguments, "n3dsCorePath", "quick-profile-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "quick-profile-content.3ds");
        int measuredFrames = boundedArgument(
                arguments, "n3dsQuickProfileFrames", 360, 180, 600);
        assumeTrue("Comparação real requer o core 3DS.", new File(corePath).isFile());
        assumeTrue("Comparação real requer conteúdo privado autorizado.",
                new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testRoot = new File(
                context.getCacheDir(), "n3ds-quick-profile-" + System.nanoTime());
        try {
            for (Nintendo3DsPerformanceProfile profile
                    : Nintendo3DsPerformanceProfile.values()) {
                File profileRoot = new File(testRoot, profile.getCoreValue());
                Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                        new File(profileRoot, "files"),
                        new File(profileRoot, "cache"));
                try (Nintendo3DsTestSurfaceOwner surface =
                             new Nintendo3DsTestSurfaceOwner(400, 480);
                     Nintendo3DsCoreLifecycleController controller =
                             new Nintendo3DsCoreLifecycleController(
                                     context,
                                     corePath,
                                     contentPath,
                                     storage,
                                     Nintendo3DsStorageLayout.CURRENT_CORE_REVISION)) {
                    controller.setPerformanceProfile(profile);
                    controller.setAudioVolume(0.0f);
                    controller.setAudioEnabled(true);
                    controller.onSurfaceAvailable(surface.surface, 400, 480);
                    controller.onResume();
                    controller.runFrames(180, 120_000);
                    controller.resetPerformanceMeasurementsAndAwait(120_000);

                    long startedAt = SystemClock.elapsedRealtime();
                    controller.runFrames(measuredFrames, 180_000);
                    long wallMillis = SystemClock.elapsedRealtime() - startedAt;
                    Nintendo3DsPerformanceSnapshot result =
                            controller.getPerformanceSnapshot();
                    Nintendo3DsAudioOutputReport audio = controller.getAudioOutputReport();

                    assertEquals(profile, result.getProfile());
                    assertEquals(measuredFrames, result.getMeasuredFrames());
                    assertTrue(result.getAverageFrameMillis() > 0.0);
                    assertTrue(result.getP95FrameMillis() >= result.getP50FrameMillis());
                    assertEquals(0, result.getAudioDroppedFrames());
                    assertEquals(0, audio.getOutputFailures());
                    System.out.println(
                            "N3DS_QUICK_PROFILE profile=" + profile.getCoreValue()
                                    + " resolution=" + profile.getResolutionScale()
                                    + " frames=" + result.getMeasuredFrames()
                                    + " wallMs=" + wallMillis
                                    + " avgMs=" + result.getAverageFrameMillis()
                                    + " p50Ms=" + result.getP50FrameMillis()
                                    + " p95Ms=" + result.getP95FrameMillis()
                                    + " p99Ms=" + result.getP99FrameMillis()
                                    + " maxMs=" + result.getMaximumFrameMillis()
                                    + " speedPercent=" + result.getEffectiveSpeedPercent()
                                    + " presented=" + result.getPresentedFrames()
                                    + " audioDropped=" + result.getAudioDroppedFrames()
                                    + " outputFailures=" + audio.getOutputFailures());
                }
            }
        } finally {
            deleteExactTestTree(testRoot, context.getCacheDir());
        }
    }

    @Test
    public void measuresFramesAndClearsOnlyRegenerablePrivateCaches() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testRoot = new File(
                context.getCacheDir(),
                "n3ds-performance-cache-policy-" + System.nanoTime());
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                new File(testRoot, "files"),
                new File(testRoot, "cache"));
        File durable = new File(storage.getUserDirectory(), "nand/test/save.bin");
        File coreCache = new File(storage.getCacheDirectory(), "test/cache.bin");
        File shaderCache = new File(storage.getShaderCacheDirectory(), "test/shader.bin");
        File transientFile = new File(storage.getTransientDirectory(), "test/transient.bin");
        writePrivateFile(storage.getUserDirectory(), "nand/test/save.bin", "durable-save");
        writePrivateFile(storage.getCacheDirectory(), "test/cache.bin", "core-cache");
        writePrivateFile(storage.getShaderCacheDirectory(), "test/shader.bin", "shader-cache");
        writePrivateFile(storage.getTransientDirectory(), "test/transient.bin", "transient");

        try {
            try (Nintendo3DsTestSurfaceOwner surface = new Nintendo3DsTestSurfaceOwner(400, 480);
                 Nintendo3DsCoreLifecycleController controller =
                         new Nintendo3DsCoreLifecycleController(
                                 context,
                                 corePath,
                                 contentPath,
                                 storage,
                                 Nintendo3DsStorageLayout.CURRENT_CORE_REVISION)) {
                controller.setPerformanceProfile(Nintendo3DsPerformanceProfile.BALANCED);
                controller.onSurfaceAvailable(surface.surface, 400, 480);
                controller.onResume();
                controller.runFrames(60, 120_000);

                Nintendo3DsPerformanceSnapshot measured = controller.getPerformanceSnapshot();
                assertEquals(Nintendo3DsPerformanceProfile.BALANCED, measured.getProfile());
                assertEquals(60, measured.getMeasuredFrames());
                assertEquals(60, measured.getPercentileSampleFrames());
                assertTrue(measured.getAverageFrameMillis() > 0);
                assertTrue(measured.getP95FrameMillis() > 0);
                assertTrue(measured.getP99FrameMillis() >= measured.getP95FrameMillis());
                assertTrue(measured.getPresentedFrames() >= 60);
                assertTrue(measured.getDeviceState().getProcessPssKilobytes() > 0);
                assertTrue(measured.getCacheReport().getTotalFiles() >= 3);

                Nintendo3DsRegenerableCachePolicy.Report removed =
                        controller.clearRegenerableCachesAndAwait(120_000);
                assertTrue(removed.getTotalFiles() >= 3);
                assertTrue(controller.getClosedSessionCount() >= 1);
                assertTrue(controller.getLastStorageCheckpoint() != null);
                assertTrue(durable.isFile());
                assertFalse(coreCache.exists());
                assertFalse(shaderCache.exists());
                assertFalse(transientFile.exists());

                Nintendo3DsCoreFrameReport reopened = controller.runFrames(2, 120_000);
                assertTrue(reopened.getPresentedFrames() >= 2);
                assertEquals(2, controller.getOpenedSessionCount());

                System.out.println(
                        "N3DS_PERFORMANCE_CACHE frames=" + measured.getMeasuredFrames()
                                + " avgMs=" + measured.getAverageFrameMillis()
                                + " p95Ms=" + measured.getP95FrameMillis()
                                + " p99Ms=" + measured.getP99FrameMillis()
                                + " speedPercent=" + measured.getEffectiveSpeedPercent()
                                + " pssKb=" + measured.getDeviceState().getProcessPssKilobytes()
                                + " thermal=" + measured.getDeviceState().getThermalStatus()
                                + " cacheFiles=" + removed.getTotalFiles()
                                + " cacheBytes=" + removed.getTotalBytes());
            }
        } finally {
            deleteExactTestTree(testRoot, context.getCacheDir());
        }
    }

    @Test
    public void appliesPerformanceProfileBeforeSessionAndRecreatesAtomically() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = runtimePath(arguments, "n3dsCorePath", "adreno-gate-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "adreno-gate-content.3dsx");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-profile-system");
        File saves = new File(files, "n3ds-profile-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        try (Nintendo3DsTestSurfaceOwner surface = new Nintendo3DsTestSurfaceOwner(400, 480);
             Nintendo3DsCoreLifecycleController controller =
                     new Nintendo3DsCoreLifecycleController(
                             corePath,
                             contentPath,
                             system.getAbsolutePath(),
                             saves.getAbsolutePath())) {
            controller.setPerformanceProfile(Nintendo3DsPerformanceProfile.CONSERVATIVE);
            controller.onSurfaceAvailable(surface.surface, 400, 480);
            controller.onResume();

            Nintendo3DsCoreFrameReport conservative = controller.runFrames(2, 120_000);
            assertEquals("conservative", conservative.getPerformanceProfile());
            assertEquals(1, conservative.getResolutionFactor());
            assertTrue(conservative.getPerformanceOptionRequests() >= 11);
            assertTrue(conservative.isDiskShaderCacheEnabled());
            assertEquals(1, controller.getOpenedSessionCount());

            controller.setPerformanceProfile(Nintendo3DsPerformanceProfile.PERFORMANCE);
            assertEquals(1, controller.getClosedSessionCount());
            Nintendo3DsCoreFrameReport performance = controller.runFrames(2, 120_000);
            assertEquals("performance", performance.getPerformanceProfile());
            assertEquals(2, performance.getResolutionFactor());
            assertTrue(performance.getPerformanceOptionRequests() >= 11);
            assertTrue(performance.isDiskShaderCacheEnabled());
            assertEquals(2, controller.getOpenedSessionCount());

            System.out.println(
                    "N3DS_PROFILE_SWITCH first=" + conservative.getPerformanceProfile()
                            + "@" + conservative.getResolutionFactor() + "x"
                            + " second=" + performance.getPerformanceProfile()
                            + "@" + performance.getResolutionFactor() + "x"
                            + " optionRequests=" + performance.getPerformanceOptionRequests()
                            + " sessions=" + controller.getOpenedSessionCount());
        }
    }

    @Test
    public void loadsAzaharNegotiatesVulkanAndPresentsRealFrames() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = runtimePath(arguments, "n3dsCorePath", "adreno-gate-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "adreno-gate-content.3dsx");
        int frameCount = boundedArgument(arguments, "n3dsFrameCount", 2, 2, 600);
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-system");
        File saves = new File(files, "n3ds-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        try (Nintendo3DsTestSurfaceOwner surface = new Nintendo3DsTestSurfaceOwner(400, 480)) {
            Nintendo3DsCoreFrameReport report;
            try (Nintendo3DsCoreSession session = Nintendo3DsCoreSession.open(
                    surface.surface,
                    400,
                    480,
                    corePath,
                    contentPath,
                    system.getAbsolutePath(),
                    saves.getAbsolutePath())) {
                report = session.runFrame();
                for (int frame = 1; frame < frameCount; frame++) {
                    report = session.runFrame();
                }
            }

            System.out.println(
                    "N3DS_CORE_FRAME device=" + report.getDeviceName()
                            + " api=" + report.getApiVersionMajor()
                            + "." + report.getApiVersionMinor()
                            + "." + report.getApiVersionPatch()
                            + " size=" + report.getWidth() + "x" + report.getHeight()
                            + " frames=" + report.getPresentedFrames()
                            + " vfsBytes=" + report.getVfsBytesRead());

            assertTrue(report.getResolutionFactor() >= 1);
            assertEquals(400 * report.getResolutionFactor(), report.getWidth());
            assertEquals(480 * report.getResolutionFactor(), report.getHeight());
            assertTrue(report.getPresentedFrames() >= frameCount);
            assertTrue(report.getVideoFrames() >= frameCount);
            assertTrue(report.isPreferredVulkan());
            assertTrue(report.isHardwareRenderNegotiated());
            assertTrue(report.isNegotiationInterfaceReceived());
            assertTrue(report.isHardwareInterfaceProvided());
            assertTrue(report.isContextReset());
            assertTrue(report.isContentOpenedThroughVfs());
            assertTrue(report.getVfsBytesRead() > 0);
        }
    }

    @Test
    public void forwardsDigitalAnalogAndTouchThroughAzaharLibretroInput() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = runtimePath(arguments, "n3dsCorePath", "adreno-gate-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "adreno-gate-content.3dsx");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-input-system");
        File saves = new File(files, "n3ds-input-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        corePath,
                        contentPath,
                        system.getAbsolutePath(),
                        saves.getAbsolutePath());
        try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                     new Nintendo3DsTestSurfaceOwner(400, 480); controller) {
            controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
            controller.onResume();
            Nintendo3DsCoreFrameReport neutral = controller.runFrames(2, 120_000);
            assertTrue(neutral.getInputPollCallbacks() > 0);
            assertTrue(neutral.getInputStateCallbacks() > 0);
            assertTrue(neutral.getJoypadStateCallbacks() > 0);
            assertTrue(neutral.getAnalogStateCallbacks() > 0);
            assertTrue(neutral.getRightAnalogStateCallbacks() > 0);
            assertTrue(neutral.getPointerStateCallbacks() > 0);
            assertEquals(0, neutral.getLastPolledButtonMask());

            controller.setButtonPressed(Nintendo3DsButton.B, true);
            controller.setButtonPressed(Nintendo3DsButton.A, true);
            controller.setButtonPressed(Nintendo3DsButton.ZL, true);
            controller.setCirclePad(0.5f, -0.25f);
            controller.setCStick(-0.75f, 0.25f);
            controller.setTouchPosition(200, 360, 400, 480, true);
            Nintendo3DsCoreFrameReport active = controller.runFrames(2, 120_000);
            assertEquals(0x1101, active.getLastPolledButtonMask());
            assertEquals(16384, active.getLastPolledCirclePadX());
            assertEquals(-8192, active.getLastPolledCirclePadY());
            assertEquals(-24575, active.getLastPolledCStickX());
            assertEquals(8192, active.getLastPolledCStickY());
            assertEquals(82, active.getLastPolledPointerX());
            assertEquals(16486, active.getLastPolledPointerY());
            assertTrue(active.isLastPolledPointerPressed());

            controller.setButtonPressed(Nintendo3DsButton.A, false);
            controller.setCirclePad(-1.0f, 1.0f);
            controller.setCStick(1.0f, -1.0f);
            controller.setTouchPosition(40, 240, 400, 480, false);
            Nintendo3DsCoreFrameReport changed = controller.runFrames(2, 120_000);
            assertEquals(0x1001, changed.getLastPolledButtonMask());
            assertEquals(-32767, changed.getLastPolledCirclePadX());
            assertEquals(32767, changed.getLastPolledCirclePadY());
            assertEquals(32767, changed.getLastPolledCStickX());
            assertEquals(-32767, changed.getLastPolledCStickY());
            assertEquals(-26197, changed.getLastPolledPointerX());
            assertEquals(68, changed.getLastPolledPointerY());
            assertFalse(changed.isLastPolledPointerPressed());

            controller.onPauseAndAwait(30_000);
            controller.onResume();
            Nintendo3DsCoreFrameReport resumed = controller.runFrames(2, 120_000);
            assertEquals(0, resumed.getLastPolledButtonMask());
            assertEquals(0, resumed.getLastPolledCirclePadX());
            assertEquals(0, resumed.getLastPolledCirclePadY());
            assertEquals(0, resumed.getLastPolledCStickX());
            assertEquals(0, resumed.getLastPolledCStickY());
            assertEquals(0, resumed.getLastPolledPointerX());
            assertEquals(0, resumed.getLastPolledPointerY());
            assertFalse(resumed.isLastPolledPointerPressed());

            System.out.println(
                    "N3DS_INPUT polls=" + active.getInputPollCallbacks()
                            + " states=" + active.getInputStateCallbacks()
                            + " joypadStates=" + active.getJoypadStateCallbacks()
                            + " analogStates=" + active.getAnalogStateCallbacks()
                            + " activeMask=" + active.getLastPolledButtonMask()
                            + " circle=" + active.getLastPolledCirclePadX()
                            + "," + active.getLastPolledCirclePadY()
                            + " rightAnalogStates=" + active.getRightAnalogStateCallbacks()
                            + " cstick=" + active.getLastPolledCStickX()
                            + "," + active.getLastPolledCStickY()
                            + " pointerStates=" + active.getPointerStateCallbacks()
                            + " pointer=" + active.getLastPolledPointerX()
                            + "," + active.getLastPolledPointerY()
                            + "," + active.isLastPolledPointerPressed()
                            + " lifecycleNeutral=" + resumed.getLastPolledButtonMask()
                            + "," + resumed.getLastPolledCStickX()
                            + "," + resumed.getLastPolledPointerX()
                            + "," + resumed.isLastPolledPointerPressed());
        }
        assertEquals(controller.getOpenedSessionCount(), controller.getClosedSessionCount());
    }

    @Test
    public void routesMultipleLayoutsTouchAndSyntheticAndroidGamepadThroughAzahar()
            throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-layout-input-system");
        File saves = new File(files, "n3ds-layout-input-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        corePath,
                        contentPath,
                        system.getAbsolutePath(),
                        saves.getAbsolutePath());
        Nintendo3DsPhysicalControllerRouter gamepad =
                new Nintendo3DsPhysicalControllerRouter(controller);
        try (Nintendo3DsTestSurfaceOwner portrait =
                     new Nintendo3DsTestSurfaceOwner(1080, 2160);
             Nintendo3DsTestSurfaceOwner landscape =
                     new Nintendo3DsTestSurfaceOwner(2400, 1080);
             controller) {
            controller.onSurfaceAvailable(portrait.surface, 1080, 2160);
            controller.onResume();
            assertTrue(controller.setTouchPositionFromSurface(
                    540.0f, 1404.0f, 1080, 2160, 400, 480, true));
            Nintendo3DsCoreFrameReport defaultLayout = controller.runFrames(2, 120_000);
            assertEquals(400, defaultLayout.getWidth());
            assertEquals(480, defaultLayout.getHeight());
            assertEquals(
                    Nintendo3DsInputState.normalizePointerCoordinate(200, 400),
                    defaultLayout.getLastPolledPointerX());
            assertEquals(
                    Nintendo3DsInputState.normalizePointerCoordinate(360, 480),
                    defaultLayout.getLastPolledPointerY());
            assertTrue(defaultLayout.isLastPolledPointerPressed());

            controller.setScreenLayout(Nintendo3DsScreenLayout.SIDE_BY_SIDE, 30_000);
            controller.onSurfaceAvailable(landscape.surface, 2400, 1080, 30_000);
            assertTrue(gamepad.handleKey(gamepadKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)));
            assertTrue(gamepad.handleKey(gamepadKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2)));
            MotionEvent axes = gamepadMotion(
                    0.60f, -0.60f, -0.60f, 0.60f, -1.0f, 0.0f, 1.0f, 0.0f);
            try {
                assertTrue(gamepad.handleMotion(axes));
            } finally {
                axes.recycle();
            }
            assertTrue(controller.setTouchPositionFromSurface(
                    1866.6666f, 540.0f, 2400, 1080, 720, 240, true));
            Nintendo3DsCoreFrameReport sideBySide = controller.runFrames(2, 120_000);
            assertEquals(720, sideBySide.getWidth());
            assertEquals(240, sideBySide.getHeight());
            assertEquals(12608, sideBySide.getLastPolledButtonMask());
            assertEquals(16384, sideBySide.getLastPolledCirclePadX());
            assertEquals(-16384, sideBySide.getLastPolledCirclePadY());
            assertEquals(-16384, sideBySide.getLastPolledCStickX());
            assertEquals(16384, sideBySide.getLastPolledCStickY());
            assertEquals(
                    Nintendo3DsInputState.normalizePointerCoordinate(560, 720),
                    sideBySide.getLastPolledPointerX());
            assertEquals(
                    Nintendo3DsInputState.normalizePointerCoordinate(120, 240),
                    sideBySide.getLastPolledPointerY());
            assertTrue(sideBySide.isLastPolledPointerPressed());

            gamepad.reset();
            assertFalse(controller.setTouchPositionFromSurface(
                    1200.0f, 100.0f, 2400, 1080, 720, 240, true));
            Nintendo3DsCoreFrameReport outside = controller.runFrames(2, 120_000);
            assertEquals(0, outside.getLastPolledButtonMask());
            assertEquals(0, outside.getLastPolledCirclePadX());
            assertEquals(0, outside.getLastPolledCirclePadY());
            assertEquals(0, outside.getLastPolledCStickX());
            assertEquals(0, outside.getLastPolledCStickY());
            assertFalse(outside.isLastPolledPointerPressed());

            controller.setScreenLayout(Nintendo3DsScreenLayout.SINGLE_BOTTOM, 30_000);
            controller.onSurfaceAvailable(portrait.surface, 1080, 2160, 30_000);
            assertTrue(controller.setTouchPositionFromSurface(
                    540.0f, 1080.0f, 1080, 2160, 320, 240, true));
            Nintendo3DsCoreFrameReport singleBottom = controller.runFrames(2, 120_000);
            assertEquals(320, singleBottom.getWidth());
            assertEquals(240, singleBottom.getHeight());
            assertEquals(
                    Nintendo3DsInputState.normalizePointerCoordinate(160, 320),
                    singleBottom.getLastPolledPointerX());
            assertEquals(
                    Nintendo3DsInputState.normalizePointerCoordinate(120, 240),
                    singleBottom.getLastPolledPointerY());
            assertTrue(singleBottom.isLastPolledPointerPressed());

            System.out.println(
                    "N3DS_LAYOUT_INPUT layouts=default:"
                            + defaultLayout.getWidth() + "x" + defaultLayout.getHeight()
                            + ",side_by_side:"
                            + sideBySide.getWidth() + "x" + sideBySide.getHeight()
                            + ",single_bottom:"
                            + singleBottom.getWidth() + "x" + singleBottom.getHeight()
                            + " sidePointer=" + sideBySide.getLastPolledPointerX()
                            + "," + sideBySide.getLastPolledPointerY()
                            + " syntheticMask=" + sideBySide.getLastPolledButtonMask()
                            + " circle=" + sideBySide.getLastPolledCirclePadX()
                            + "," + sideBySide.getLastPolledCirclePadY()
                            + " cstick=" + sideBySide.getLastPolledCStickX()
                            + "," + sideBySide.getLastPolledCStickY()
                            + " outsideReleased=" + !outside.isLastPolledPointerPressed());
        }
        assertEquals(controller.getOpenedSessionCount(), controller.getClosedSessionCount());
    }

    @Test
    public void forwardsLifecycleBoundAndroidMotionThroughAzaharSensors() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File files = context.getFilesDir();
        File system = new File(files, "n3ds-motion-system");
        File saves = new File(files, "n3ds-motion-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        context,
                        corePath,
                        contentPath,
                        system.getAbsolutePath(),
                        saves.getAbsolutePath());
        try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                     new Nintendo3DsTestSurfaceOwner(400, 480); controller) {
            controller.setDisplayRotation(Surface.ROTATION_0);
            controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
            controller.onResume();

            long sensorDeadline = SystemClock.elapsedRealtime() + 5_000;
            Nintendo3DsMotionReport motion = controller.getMotionReport();
            while (SystemClock.elapsedRealtime() < sensorDeadline
                    && (motion.getAccelerometerEvents() == 0
                    || motion.getGyroscopeEvents() == 0)) {
                SystemClock.sleep(20);
                motion = controller.getMotionReport();
            }
            assertTrue(motion.isActive());
            assertTrue(motion.isAccelerometerAvailable());
            assertTrue(motion.isGyroscopeAvailable());
            assertTrue(motion.getAccelerometerEvents() > 0);
            assertTrue(motion.getGyroscopeEvents() > 0);

            Nintendo3DsCoreFrameReport active = controller.runFrames(4, 120_000);
            assertTrue(active.getSensorStateCallbacks() >= 2);
            assertTrue(active.getSensorInputCallbacks() > 0);
            assertEquals(60, active.getSensorSamplingRateHz());
            float accelerationMagnitude = (float) Math.sqrt(
                    active.getLastPolledAccelerometerX()
                            * active.getLastPolledAccelerometerX()
                            + active.getLastPolledAccelerometerY()
                            * active.getLastPolledAccelerometerY()
                            + active.getLastPolledAccelerometerZ()
                            * active.getLastPolledAccelerometerZ());
            assertTrue("Acelerômetro físico 3DS permaneceu neutro.", accelerationMagnitude > 0.2f);

            controller.onPauseAndAwait(30_000);
            Nintendo3DsMotionReport paused = controller.getMotionReport();
            assertFalse(paused.isActive());
            assertEquals(1, paused.getStartCount());
            assertEquals(1, paused.getStopCount());

            controller.onResume();
            Nintendo3DsCoreFrameReport resumed = controller.runFrames(2, 120_000);
            Nintendo3DsMotionReport restarted = controller.getMotionReport();
            assertTrue(restarted.isActive());
            assertEquals(2, restarted.getStartCount());
            assertTrue(resumed.getSensorInputCallbacks() > 0);

            System.out.println(
                    "N3DS_MOTION sensorStateCalls=" + active.getSensorStateCallbacks()
                            + " sensorInputCalls=" + active.getSensorInputCallbacks()
                            + " rateHz=" + active.getSensorSamplingRateHz()
                            + " accel=" + active.getLastPolledAccelerometerX()
                            + "," + active.getLastPolledAccelerometerY()
                            + "," + active.getLastPolledAccelerometerZ()
                            + " gyro=" + active.getLastPolledGyroscopeX()
                            + "," + active.getLastPolledGyroscopeY()
                            + "," + active.getLastPolledGyroscopeZ()
                            + " androidEvents=" + motion.getAccelerometerEvents()
                            + "," + motion.getGyroscopeEvents()
                            + " stoppedInBackground=" + !paused.isActive());
        }
        Nintendo3DsMotionReport closed = controller.getMotionReport();
        assertFalse(closed.isActive());
        assertEquals(closed.getStartCount(), closed.getStopCount());
    }

    @Test
    public void routesPermissionAwareAndroidMicrophoneThroughAzahar() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString(
                "n3dsMicrophoneContentPath",
                arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean permissionGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        File files = context.getFilesDir();
        File system = new File(files, "n3ds-microphone-system");
        File saves = new File(files, "n3ds-microphone-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        context,
                        corePath,
                        contentPath,
                        system.getAbsolutePath(),
                        saves.getAbsolutePath());
        try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                     new Nintendo3DsTestSurfaceOwner(400, 480); controller) {
            assertEquals(
                    Nintendo3DsMicrophoneStartResult.WAITING_FOR_RESUME,
                    controller.setMicrophoneEnabled(true));
            controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
            controller.onResume();
            Nintendo3DsMicrophoneStartResult startResult =
                    controller.setMicrophoneEnabled(true);

            Nintendo3DsMicrophoneReport source = controller.getMicrophoneReport();
            if (permissionGranted) {
                assertEquals(Nintendo3DsMicrophoneStartResult.ACTIVE, startResult);
                long captureDeadline = SystemClock.elapsedRealtime() + 5_000;
                while (SystemClock.elapsedRealtime() < captureDeadline
                        && source.getCapturedSamples() == 0) {
                    SystemClock.sleep(20);
                    source = controller.getMicrophoneReport();
                }
                assertTrue(source.isActive());
                assertTrue(source.isPermissionGranted());
                assertTrue(source.isMicrophoneAvailable());
                assertTrue("AudioRecord 3DS não capturou PCM físico.",
                        source.getCapturedSamples() > 0);
                assertEquals(0, source.getCaptureFailures());
            } else {
                assertEquals(Nintendo3DsMicrophoneStartResult.PERMISSION_REQUIRED, startResult);
                assertFalse(source.isActive());
                assertFalse(source.isPermissionGranted());
                assertEquals(0, source.getCapturedSamples());
            }

            Nintendo3DsCoreFrameReport active = controller.runFrames(30, 120_000);
            source = controller.getMicrophoneReport();
            assertTrue(active.isMicrophoneFrontendSelected());
            assertTrue(active.getMicrophoneOpenCallbacks() > 0);
            assertTrue(active.getMicrophoneStateCallbacks() > 0);
            assertTrue(active.getMicrophoneReadCallbacks() > 0);
            assertEquals(48_000, active.getMicrophoneSamplingRateHz());
            assertTrue(active.isMicrophoneActive());
            assertTrue(active.getMicrophoneQueuedSamples() <= 48_000);
            if (permissionGranted) {
                assertTrue(active.getMicrophoneCapturedSamples() > 0);
                assertTrue(active.getMicrophoneDeliveredSamples() > 0);
            } else {
                assertEquals(0, active.getMicrophoneCapturedSamples());
                assertEquals(0, active.getMicrophoneDeliveredSamples());
                assertTrue(active.getMicrophoneSilentSamples() > 0);
            }

            controller.onPauseAndAwait(30_000);
            Nintendo3DsMicrophoneReport paused = controller.getMicrophoneReport();
            assertFalse(paused.isActive());
            if (permissionGranted) {
                assertEquals(1, paused.getStartCount());
                assertEquals(1, paused.getStopCount());
            }

            controller.onResume();
            controller.runFrames(2, 120_000);
            Nintendo3DsMicrophoneReport restarted = controller.getMicrophoneReport();
            assertEquals(permissionGranted, restarted.isActive());
            if (permissionGranted) {
                assertEquals(2, restarted.getStartCount());
            }

            System.out.println(
                    "N3DS_MICROPHONE permission=" + permissionGranted
                            + " startResult=" + startResult
                            + " androidCaptured=" + source.getCapturedSamples()
                            + " androidQueued=" + source.getQueuedSamples()
                            + " androidDropped=" + source.getDroppedSamples()
                            + " nativeCaptured=" + active.getMicrophoneCapturedSamples()
                            + " nativeQueued=" + active.getMicrophoneQueuedSamples()
                            + " nativeDropped=" + active.getMicrophoneDroppedSamples()
                            + " nativeDelivered=" + active.getMicrophoneDeliveredSamples()
                            + " nativeSilent=" + active.getMicrophoneSilentSamples()
                            + " nativeReads=" + active.getMicrophoneReadCallbacks()
                            + " rateHz=" + active.getMicrophoneSamplingRateHz()
                            + " stoppedInBackground=" + !paused.isActive());
        }
        Nintendo3DsMicrophoneReport closed = controller.getMicrophoneReport();
        assertFalse(closed.isActive());
        assertEquals(closed.getStartCount(), closed.getStopCount());
    }

    @Test
    public void recoversFromRejectedContentAndRecreatesSurfaceSessions() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        int cycles = boundedArgument(arguments, "n3dsLifecycleCycles", 3, 2, 20);
        int framesPerCycle = boundedArgument(
                arguments, "n3dsLifecycleFrames", 2, 2, 300);
        int maxPssGrowthKb = boundedArgument(
                arguments, "n3dsMaxPssGrowthKb", 0, 0, 1_048_576);
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-system");
        File saves = new File(files, "n3ds-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        try (Nintendo3DsTestSurfaceOwner invalidSurface =
                     new Nintendo3DsTestSurfaceOwner(400, 480)) {
            String missingContent = new File(
                    files, "missing-" + System.nanoTime() + ".3ds").getAbsolutePath();
            assertThrows(IOException.class, () -> Nintendo3DsCoreSession.open(
                    invalidSurface.surface,
                    400,
                    480,
                    corePath,
                    missingContent,
                    system.getAbsolutePath(),
                    saves.getAbsolutePath()));
        }

        long firstClosedPssKb = -1;
        long peakAfterFirstPssKb = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            int width = cycle % 2 == 0 ? 400 : 480;
            int height = cycle % 2 == 0 ? 480 : 400;
            try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                         new Nintendo3DsTestSurfaceOwner(width, height);
                 Nintendo3DsCoreSession session = Nintendo3DsCoreSession.open(
                         surfaceOwner.surface,
                         width,
                         height,
                         corePath,
                         contentPath,
                         system.getAbsolutePath(),
                         saves.getAbsolutePath())) {
                Nintendo3DsCoreFrameReport report = null;
                for (int frame = 0; frame < framesPerCycle; frame++) {
                    report = session.runFrame();
                }
                assertTrue(report != null);
                assertTrue(report.getPresentedFrames() >= framesPerCycle);
                assertTrue(report.isContextReset());
                assertTrue(report.isContentOpenedThroughVfs());
            }
            long closedPssKb = Debug.getPss();
            if (firstClosedPssKb < 0) {
                firstClosedPssKb = closedPssKb;
            } else {
                peakAfterFirstPssKb = Math.max(peakAfterFirstPssKb, closedPssKb);
            }
        }
        long growthKb = Math.max(0, peakAfterFirstPssKb - firstClosedPssKb);
        System.out.println(
                "N3DS_LIFECYCLE cycles=" + cycles
                        + " framesPerCycle=" + framesPerCycle
                        + " firstClosedPssKb=" + firstClosedPssKb
                        + " peakAfterFirstPssKb=" + peakAfterFirstPssKb
                        + " growthKb=" + growthKb);
        if (maxPssGrowthKb > 0) {
            assertTrue(
                    "Crescimento PSS excedeu o limite do teste 3DS.",
                    growthKb <= maxPssGrowthKb);
        }
    }

    @Test
    public void capturesBoundedStereoPcmWithoutDroppingAtFrameCadence() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        int frameCount = boundedArgument(arguments, "n3dsAudioFrames", 60, 2, 600);
        int overflowFrameLimit = boundedArgument(
                arguments, "n3dsAudioOverflowFrames", 240, 60, 1200);
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-system");
        File saves = new File(files, "n3ds-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        final int transferCapacityFrames = 8192;
        ByteBuffer pcm = ByteBuffer.allocateDirect(transferCapacityFrames * 4)
                .order(ByteOrder.nativeOrder());
        long drainedFrames = 0;
        long nonZeroSamples = 0;
        long clippedSamples = 0;
        long boundedBacklogFrames = 0;
        Nintendo3DsCoreFrameReport streamingReport = null;
        Nintendo3DsCoreFrameReport overflowReport = null;
        try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                     new Nintendo3DsTestSurfaceOwner(400, 480);
             Nintendo3DsCoreSession session = Nintendo3DsCoreSession.open(
                     surfaceOwner.surface,
                     400,
                     480,
                     corePath,
                     contentPath,
                     system.getAbsolutePath(),
                     saves.getAbsolutePath())) {
            for (int frame = 0; frame < frameCount; frame++) {
                streamingReport = session.runFrame();
                int transferred;
                do {
                    transferred = session.drainAudio(pcm, transferCapacityFrames);
                    drainedFrames += transferred;
                    for (int sample = 0; sample < transferred * 2; sample++) {
                        short value = pcm.getShort(sample * Short.BYTES);
                        if (value != 0) {
                            nonZeroSamples++;
                        }
                        if (value == Short.MIN_VALUE || value == Short.MAX_VALUE) {
                            clippedSamples++;
                        }
                    }
                } while (transferred == transferCapacityFrames);
            }
            for (int frame = 0; frame < overflowFrameLimit; frame++) {
                overflowReport = session.runFrame();
                if (frame >= 59 && overflowReport.getAudioDroppedFrames() > 0) {
                    break;
                }
            }
            int transferred;
            do {
                transferred = session.drainAudio(pcm, transferCapacityFrames);
                boundedBacklogFrames += transferred;
            } while (transferred == transferCapacityFrames);
        }

        assertTrue(streamingReport != null);
        assertTrue(overflowReport != null);
        System.out.println(
                "N3DS_PCM_CAPTURE sampleRate=" + streamingReport.getAudioSampleRate()
                        + " streamedProducedFrames=" + streamingReport.getAudioFrames()
                        + " drainedFrames=" + drainedFrames
                        + " streamingPeakQueuedFrames="
                        + streamingReport.getAudioPeakQueuedFrames()
                        + " streamingDroppedFrames="
                        + streamingReport.getAudioDroppedFrames()
                        + " overflowProducedFrames=" + overflowReport.getAudioFrames()
                        + " overflowQueuedFrames=" + overflowReport.getAudioQueuedFrames()
                        + " overflowDroppedFrames=" + overflowReport.getAudioDroppedFrames()
                        + " boundedBacklogFrames=" + boundedBacklogFrames
                        + " nonZeroSamples=" + nonZeroSamples
                        + " clippedSamples=" + clippedSamples);
        assertTrue(streamingReport.getAudioSampleRate() >= 8000);
        assertTrue(streamingReport.getAudioSampleRate() <= 192000);
        assertTrue(streamingReport.getAudioFrames() > 0);
        assertTrue(drainedFrames > 0);
        assertTrue(nonZeroSamples > 0);
        assertEquals(0, streamingReport.getAudioDroppedFrames());
        assertTrue(streamingReport.getAudioPeakQueuedFrames() > 0);
        assertTrue(streamingReport.getAudioPeakQueuedFrames() <= 65536);
        assertTrue(overflowReport.getAudioDroppedFrames() > 0);
        assertTrue(overflowReport.getAudioQueuedFrames() <= 65536);
        assertTrue(boundedBacklogFrames > 0);
        assertTrue(boundedBacklogFrames <= 65536);
    }

    @Test
    public void validatesAudioFrontendForSilentOrPcmHomebrew() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = runtimePath(arguments, "n3dsCorePath", "adreno-gate-core.so");
        String contentPath = runtimePath(
                arguments, "n3dsContentPath", "adreno-gate-content.3dsx");
        int frameCount = boundedArgument(
                arguments, "n3dsCorpusFrameCount", 12, 2, 60);
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testRoot = new File(
                context.getCacheDir(),
                "n3ds-audio-frontend-" + System.nanoTime());
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                new File(testRoot, "files"),
                new File(testRoot, "cache"));
        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        context,
                        corePath,
                        contentPath,
                        storage,
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        Nintendo3DsAudioOutputReport active;
        Nintendo3DsCoreFrameReport frameReport;
        try {
            try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                         new Nintendo3DsTestSurfaceOwner(400, 480); controller) {
                controller.setAudioVolume(0.0f);
                controller.setAudioEnabled(true);
                controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
                controller.onResume();
                frameReport = controller.runFrames(frameCount, 120_000);
                active = controller.getAudioOutputReport();

                assertTrue(frameReport.getPresentedFrames() >= frameCount);
                assertTrue(frameReport.getVideoFrames() >= frameCount);
                assertTrue(frameReport.isContentOpenedThroughVfs());
                assertEquals(0, frameReport.getAudioDroppedFrames());
                assertEquals(0, active.getDroppedFrames());
                assertEquals(0, active.getTrackCreationFailures());
                assertEquals(0, active.getOutputFailures());
                if (frameReport.getAudioFrames() == 0) {
                    assertEquals(0, active.getInputFrames());
                    assertEquals(0, active.getWrittenFrames());
                    // Azahar may announce a valid sample rate before emitting
                    // PCM. The frontend can prepare one silent AudioTrack, but
                    // it must never start playback or accumulate audio frames.
                    assertTrue(active.getOpenedTrackCount() <= 1);
                    assertEquals(active.getOpenedTrackCount() == 1, active.isActive());
                    assertFalse(active.isPlaying());
                } else {
                    assertTrue(frameReport.getAudioSampleRate() >= 8_000);
                    assertTrue(frameReport.getAudioSampleRate() <= 192_000);
                    assertEquals(frameReport.getAudioSampleRate(), active.getSampleRate());
                    assertEquals(frameReport.getAudioFrames(), active.getInputFrames());
                    assertTrue(active.getWrittenFrames() > 0);
                    assertEquals(1, active.getOpenedTrackCount());
                    assertTrue(active.isActive());
                }

                controller.onPauseAndAwait(30_000);
                Nintendo3DsAudioOutputReport paused = controller.getAudioOutputReport();
                assertFalse(paused.isActive());
                assertFalse(paused.isPlaying());
                assertEquals(paused.getOpenedTrackCount(), paused.getReleasedTrackCount());

                System.out.println(
                        "N3DS_AUDIO_FRONTEND frames=" + frameReport.getPresentedFrames()
                                + " producedFrames=" + frameReport.getAudioFrames()
                                + " sampleRate=" + frameReport.getAudioSampleRate()
                                + " openedTracks=" + active.getOpenedTrackCount()
                                + " outputDropped=" + active.getDroppedFrames()
                                + " outputFailures=" + active.getOutputFailures()
                                + " silent=" + (frameReport.getAudioFrames() == 0));
            }
            Nintendo3DsAudioOutputReport closed = controller.getAudioOutputReport();
            assertFalse(closed.isActive());
            assertEquals(closed.getOpenedTrackCount(), closed.getReleasedTrackCount());
        } finally {
            deleteExactTestTree(testRoot, context.getCacheDir());
        }
    }

    @Test
    public void pacesAudioTrackAndReleasesItAcrossPauseResume() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        int frameCount = boundedArgument(
                arguments, "n3dsAudioPlaybackFrames", 60, 30, 3600);
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-audio-system");
        File saves = new File(files, "n3ds-audio-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        Nintendo3DsAudioOutputReport firstPlayback;
        Nintendo3DsAudioOutputReport resumedPlayback;
        long elapsedMillis;
        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        corePath,
                        contentPath,
                        system.getAbsolutePath(),
                        saves.getAbsolutePath());
        try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                     new Nintendo3DsTestSurfaceOwner(400, 480); controller) {
            controller.setAudioVolume(0.0f);
            controller.setAudioEnabled(true);
            controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
            controller.onResume();

            long startedAt = SystemClock.elapsedRealtime();
            Nintendo3DsCoreFrameReport frameReport = controller.runFrames(frameCount, 120_000);
            elapsedMillis = SystemClock.elapsedRealtime() - startedAt;
            firstPlayback = controller.getAudioOutputReport();

            assertTrue(frameReport.getAudioFrames() > 0);
            assertEquals(0, frameReport.getAudioDroppedFrames());
            assertTrue(firstPlayback.isActive());
            assertTrue(firstPlayback.isPrimed());
            assertTrue(firstPlayback.isPlaying());
            assertEquals(frameReport.getAudioSampleRate(), firstPlayback.getSampleRate());
            assertTrue(frameReport.getAudioFrames() >= firstPlayback.getInputFrames());
            assertEquals(
                    firstPlayback.getInputFrames(),
                    firstPlayback.getWrittenFrames()
                            + firstPlayback.getDecimatedFrames()
                            + firstPlayback.getDroppedFrames()
                            + firstPlayback.getPendingFrames());
            assertEquals(0, firstPlayback.getDroppedFrames());
            assertEquals(1, firstPlayback.getOpenedTrackCount());
            assertEquals(0, firstPlayback.getReleasedTrackCount());
            assertEquals(0, firstPlayback.getTrackCreationFailures());
            assertEquals(0, firstPlayback.getOutputFailures());
            assertTrue(firstPlayback.getBufferCapacityFrames() > 0);
            assertTrue(
                    "Underruns 3DS excederam o orçamento medido de aquecimento.",
                    firstPlayback.getUnderruns() <= Math.max(16, frameCount / 10));

            long generatedAudioMillis = firstPlayback.getWrittenFrames() * 1000L
                    / firstPlayback.getSampleRate();
            long bufferedAudioMillis = firstPlayback.getBufferCapacityFrames() * 1000L
                    / firstPlayback.getSampleRate();
            long minimumPacedMillis = Math.max(
                    0,
                    generatedAudioMillis - bufferedAudioMillis - 400);
            assertTrue(
                    "AudioTrack não aplicou pacing compatível com o áudio produzido.",
                    elapsedMillis >= minimumPacedMillis);

            controller.onPauseAndAwait();
            Nintendo3DsAudioOutputReport paused = controller.getAudioOutputReport();
            assertFalse(paused.isActive());
            assertFalse(paused.isPlaying());
            assertEquals(1, paused.getReleasedTrackCount());
            assertEquals(0, controller.getClosedSessionCount());
            assertEquals(
                    "A pausa deve descartar somente o bloco parcial que ainda estava pendente.",
                    firstPlayback.getPendingFrames(),
                    paused.getDroppedFrames());
            assertTrue(paused.getDroppedFrames()
                    <= Nintendo3DsAudioOutput.TRANSFER_CAPACITY_FRAMES);

            controller.onResume();
            controller.runFrames(2, 120_000);
            resumedPlayback = controller.getAudioOutputReport();
            for (int prerollFrame = 2;
                 prerollFrame < 120 && !resumedPlayback.isPlaying();
                 prerollFrame++) {
                controller.runFrames(1, 120_000);
                resumedPlayback = controller.getAudioOutputReport();
            }
            assertTrue(resumedPlayback.isActive());
            assertTrue(resumedPlayback.isPlaying());
            assertEquals(2, resumedPlayback.getOpenedTrackCount());
            assertEquals(1, resumedPlayback.getReleasedTrackCount());
            assertEquals(1, controller.getOpenedSessionCount());
            assertEquals(0, controller.getClosedSessionCount());
            assertTrue(resumedPlayback.getWrittenFrames() > firstPlayback.getWrittenFrames());
            assertEquals(
                    "A retomada não deve descartar áudio além do bloco parcial da pausa.",
                    paused.getDroppedFrames(),
                    resumedPlayback.getDroppedFrames());

            System.out.println(
                    "N3DS_AUDIOTRACK sampleRate=" + firstPlayback.getSampleRate()
                            + " nominalFps=" + frameReport.getNominalFramesPerSecond()
                            + " inputFrames=" + firstPlayback.getInputFrames()
                            + " frames=" + firstPlayback.getWrittenFrames()
                            + " pendingFrames=" + firstPlayback.getPendingFrames()
                            + " droppedFrames=" + firstPlayback.getDroppedFrames()
                            + " elapsedMs=" + elapsedMillis
                            + " bufferFrames=" + firstPlayback.getBufferCapacityFrames()
                            + " underruns=" + firstPlayback.getUnderruns()
                            + " shortWrites=" + firstPlayback.getShortWrites()
                            + " openedTracks=" + resumedPlayback.getOpenedTrackCount()
                            + " releasedBeforeClose="
                            + resumedPlayback.getReleasedTrackCount());
        }
        Nintendo3DsAudioOutputReport closed = controller.getAudioOutputReport();
        assertFalse(closed.isActive());
        assertEquals(closed.getOpenedTrackCount(), closed.getReleasedTrackCount());
    }

    @Test
    public void interruptsInFlightAudioWriteBeforePauseReturns() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-audio-cancel-system");
        File saves = new File(files, "n3ds-audio-cancel-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        ExecutorService caller = Executors.newSingleThreadExecutor();
        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        corePath,
                        contentPath,
                        system.getAbsolutePath(),
                        saves.getAbsolutePath());
        try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                     new Nintendo3DsTestSurfaceOwner(400, 480); controller) {
            controller.setAudioVolume(0.0f);
            controller.setAudioEnabled(true);
            controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
            controller.onResume();

            Future<Nintendo3DsCoreFrameReport> inFlight = caller.submit(
                    () -> controller.runFrames(600, 120_000));
            long readyDeadline = SystemClock.elapsedRealtime() + 30_000;
            Nintendo3DsAudioOutputReport active = controller.getAudioOutputReport();
            while (SystemClock.elapsedRealtime() < readyDeadline
                    && (!active.isPlaying() || active.getWrittenFrames() == 0)) {
                SystemClock.sleep(20);
                active = controller.getAudioOutputReport();
            }
            assertTrue("AudioTrack 3DS não iniciou para o teste de cancelamento.",
                    active.isPlaying() && active.getWrittenFrames() > 0);

            long pauseStartedAt = SystemClock.elapsedRealtime();
            controller.onPauseAndAwait(10_000);
            long pauseElapsedMillis = SystemClock.elapsedRealtime() - pauseStartedAt;
            ExecutionException invalidated = assertThrows(
                    ExecutionException.class,
                    () -> inFlight.get(10, TimeUnit.SECONDS));
            assertTrue(invalidated.getCause() instanceof IOException);

            Nintendo3DsAudioOutputReport paused = controller.getAudioOutputReport();
            assertFalse(paused.isActive());
            assertFalse(paused.isPlaying());
            assertEquals(paused.getOpenedTrackCount(), paused.getReleasedTrackCount());
            assertEquals(0, paused.getOutputFailures());
            assertTrue(paused.getDroppedFrames() <= Nintendo3DsAudioOutput.TRANSFER_CAPACITY_FRAMES);
            assertTrue("Pause 3DS não interrompeu a escrita dentro do timeout medido.",
                    pauseElapsedMillis < 5_000);

            System.out.println(
                    "N3DS_AUDIO_CANCEL pauseMs=" + pauseElapsedMillis
                            + " writtenFrames=" + paused.getWrittenFrames()
                            + " transitionDroppedFrames=" + paused.getDroppedFrames()
                            + " openedTracks=" + paused.getOpenedTrackCount()
                            + " releasedTracks=" + paused.getReleasedTrackCount());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    public void keepsFastForwardAudioNonBlockingAndAccountsForEveryFrame() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        int framesPerSpeed = boundedArgument(
                arguments, "n3dsFastForwardFrames", 300, 120, 600);
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        File files = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir();
        File system = new File(files, "n3ds-fast-forward-system");
        File saves = new File(files, "n3ds-fast-forward-saves");
        assertTrue(system.isDirectory() || system.mkdirs());
        assertTrue(saves.isDirectory() || saves.mkdirs());

        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        corePath,
                        contentPath,
                        system.getAbsolutePath(),
                        saves.getAbsolutePath());
        try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                     new Nintendo3DsTestSurfaceOwner(400, 480); controller) {
            controller.setAudioVolume(0.0f);
            controller.setAudioEnabled(true);
            controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
            controller.onResume();

            controller.runFrames(120, 120_000);
            Nintendo3DsAudioOutputReport warmup = controller.getAudioOutputReport();
            assertEquals(0, warmup.getDroppedFrames());
            assertEquals(0, warmup.getDecimatedFrames());

            long previousInput = warmup.getInputFrames();
            long previousWritten = warmup.getWrittenFrames();
            long previousDecimated = warmup.getDecimatedFrames();
            long previousDropped = warmup.getDroppedFrames();
            long previousPending = warmup.getPendingFrames();
            long previousNonBlockingCalls = warmup.getNonBlockingWriteCalls();
            for (float speed : new float[]{2.0f, 4.0f}) {
                controller.setFastForwardSpeed(speed);
                long startedAt = SystemClock.elapsedRealtime();
                Nintendo3DsCoreFrameReport frameReport = controller.runFrames(
                        framesPerSpeed,
                        120_000);
                long elapsedMillis = SystemClock.elapsedRealtime() - startedAt;
                Nintendo3DsAudioOutputReport output = controller.getAudioOutputReport();

                long segmentInput = output.getInputFrames() - previousInput;
                long segmentWritten = output.getWrittenFrames() - previousWritten;
                long segmentDecimated = output.getDecimatedFrames() - previousDecimated;
                long segmentDropped = output.getDroppedFrames() - previousDropped;
                long segmentNonBlockingCalls = output.getNonBlockingWriteCalls()
                        - previousNonBlockingCalls;

                assertEquals(speed, output.getRequestedSpeed(), 0.0f);
                assertTrue(output.getSynchronizedSpeed() >= 1.0f);
                assertTrue(output.getSynchronizedSpeed() <= speed);
                assertTrue(segmentInput > 0);
                assertTrue(segmentWritten > 0);
                assertTrue(segmentDecimated > 0);
                assertTrue(segmentNonBlockingCalls > 0);
                assertEquals(
                        "Todo frame PCM 3DS deve ser escrito, decimado, pendente ou descartado explicitamente.",
                        segmentInput + previousPending,
                        segmentWritten + segmentDecimated + segmentDropped
                                + output.getPendingFrames());
                assertTrue(frameReport.getAudioFrames() >= output.getInputFrames());
                assertEquals(0, output.getOutputFailures());
                assertTrue(output.isActive());
                assertTrue(output.isPrimed());
                assertTrue(output.isPlaying());

                System.out.println(
                        "N3DS_FAST_FORWARD requested=" + speed
                                + " synchronized=" + output.getSynchronizedSpeed()
                                + " videoFrames=" + framesPerSpeed
                                + " inputFrames=" + segmentInput
                                + " writtenFrames=" + segmentWritten
                                + " decimatedFrames=" + segmentDecimated
                                + " outputDroppedFrames=" + segmentDropped
                                + " nonBlockingCalls=" + segmentNonBlockingCalls
                                + " elapsedMs=" + elapsedMillis
                                + " bufferFrames=" + output.getBufferCapacityFrames()
                                + " underruns=" + output.getUnderruns());
                assertTrue(
                        "Fast-forward 3DS descartou mais que um buffer de saída.",
                        segmentDropped <= output.getBufferCapacityFrames());

                previousInput = output.getInputFrames();
                previousWritten = output.getWrittenFrames();
                previousDecimated = output.getDecimatedFrames();
                previousDropped = output.getDroppedFrames();
                previousPending = output.getPendingFrames();
                previousNonBlockingCalls = output.getNonBlockingWriteCalls();
            }
        }
        Nintendo3DsAudioOutputReport closed = controller.getAudioOutputReport();
        assertEquals(closed.getOpenedTrackCount(), closed.getReleasedTrackCount());
    }

    @Test
    public void checkpointsPrivateAzaharTreesOnlyAfterOwnerThreadShutdown() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                new File(context.getFilesDir(), "n3ds-storage-evidence"),
                new File(context.getCacheDir(), "n3ds-storage-evidence"));
        writePrivateFile(storage.getUserDirectory(), "nand/test/save.bin", "durable-save");
        writePrivateFile(storage.getUserDirectory(), "sdmc/test/extdata.bin", "durable-extdata");
        writePrivateFile(storage.getUserDirectory(), "cache/test/cache.bin", "regenerable-cache");
        writePrivateFile(storage.getUserDirectory(), "shaders/test/shader.bin", "regenerable-shader");
        writePrivateFile(storage.getUserDirectory(), "states/test/state.cst", "unqualified-state");

        Nintendo3DsCoreLifecycleController controller =
                new Nintendo3DsCoreLifecycleController(
                        context,
                        corePath,
                        contentPath,
                        storage,
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                     new Nintendo3DsTestSurfaceOwner(400, 480); controller) {
            controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
            controller.onResume();
            controller.runFrames(2, 120_000);
            assertEquals(0, controller.getClosedSessionCount());
            assertEquals(null, controller.getLastStorageCheckpoint());

            controller.restartSessionAndAwait(120_000);
            Nintendo3DsStorageLayout.CheckpointReport first =
                    controller.getLastStorageCheckpoint();
            assertTrue(first != null);
            assertEquals(1, controller.getOpenedSessionCount());
            assertEquals(1, controller.getClosedSessionCount());
            assertTrue(first.getFileCount() >= 2);
            assertEquals(first.getFileCount(), first.getCopiedFiles() + first.getReusedFiles());
            assertTrue(first.getReusedFiles() >= 2 || first.getCopiedFiles() >= 2);
            assertTrue(first.getSnapshotManifest().isFile());

            Set<String> entries = storage.latestEntryPathsForTesting();
            assertTrue(entries.contains("nand/test/save.bin"));
            assertTrue(entries.contains("sdmc/test/extdata.bin"));
            assertFalse(entries.contains("cache/test/cache.bin"));
            assertFalse(entries.contains("shaders/test/shader.bin"));
            assertFalse(entries.contains("states/test/state.cst"));

            controller.runFrames(2, 120_000);
            controller.restartSessionAndAwait(120_000);
            Nintendo3DsStorageLayout.CheckpointReport second =
                    controller.getLastStorageCheckpoint();
            assertEquals(first.getGeneration(), second.getGeneration());
            assertEquals(0, second.getCopiedFiles());
            assertEquals(0, second.getCopiedBytes());
            assertTrue(second.getReusedFiles() >= 2);

            Files.write(
                    storage.getLatestManifest().toPath(),
                    "corrupted-latest-manifest".getBytes(StandardCharsets.UTF_8));
            writePrivateFile(storage.getUserDirectory(), "nand/test/save.bin", "mutated");
            Nintendo3DsStorageLayout.RestoreReport restore =
                    storage.restoreLatestAfterCoreClosed(
                            Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
            assertEquals(second.getGeneration(), restore.getGeneration());
            assertTrue(restore.isFallbackSnapshotUsed());
            assertEquals("durable-save", readPrivateFile(
                    storage.getUserDirectory(), "nand/test/save.bin"));
            assertEquals("regenerable-cache", readPrivateFile(
                    storage.getUserDirectory(), "cache/test/cache.bin"));

            Nintendo3DsCoreFrameReport reopened = controller.runFrames(2, 120_000);
            assertTrue(reopened.getPresentedFrames() >= 2);
            controller.restartSessionAndAwait(120_000);

            System.out.println(
                    "N3DS_STORAGE generation=" + restore.getGeneration()
                            + " files=" + restore.getFileCount()
                            + " logicalBytes=" + restore.getRestoredBytes()
                            + " copiedFiles=" + second.getCopiedFiles()
                            + " copiedBytes=" + second.getCopiedBytes()
                            + " reusedFiles=" + second.getReusedFiles()
                            + " cacheExcluded=true statesExcluded=true"
                            + " restored=true fallback=true"
                            + " reopenedFrames=" + reopened.getPresentedFrames()
                            + " opened=" + controller.getOpenedSessionCount()
                            + " closed=" + controller.getClosedSessionCount());
        }
        assertEquals(controller.getOpenedSessionCount(), controller.getClosedSessionCount());
    }

    @Test
    public void preservesNativeTitleDataAcrossPrivateContentMatrix() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String corePath = arguments.getString("n3dsCorePath", "");
        int expectedContentCount = boundedArgument(
                arguments, "n3dsExpectedContentCount", 3, 1, 5);
        int framesPerContent = boundedArgument(
                arguments, "n3dsPersistenceFrames", 120, 2, 1800);
        int expectedTitleSaveCount = boundedArgument(
                arguments, "n3dsExpectedTitleSaveCount", 1, 1, 5);
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());

        List<String> contentPaths = privateContentPaths(arguments);
        assumeTrue(
                "Matriz privada requer todos os conteúdos declarados.",
                contentPaths.size() >= expectedContentCount);
        contentPaths = new ArrayList<>(contentPaths.subList(0, expectedContentCount));

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String runId = Long.toString(SystemClock.elapsedRealtimeNanos());
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                new File(context.getFilesDir(), "n3ds-storage-matrix-" + runId),
                new File(context.getCacheDir(), "n3ds-storage-matrix-" + runId));

        PersistenceMatrixRun initialRun = runPersistenceMatrix(
                context,
                corePath,
                Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                storage,
                contentPaths,
                framesPerContent);

        Set<String> checkpointEntries = storage.latestEntryPathsForTesting();
        Set<String> titleRoots = titleSaveRoots(checkpointEntries);
        assertTrue(
                "A matriz privada não produziu o mínimo de saves nativos esperado.",
                titleRoots.size() >= expectedTitleSaveCount);
        String nativeSaveEntry = firstNonEmptyTitleSave(storage, checkpointEntries);
        File nativeSave = new File(
                storage.getUserDirectory(),
                nativeSaveEntry.replace('/', File.separatorChar));
        byte[] expectedSave = Files.readAllBytes(nativeSave.toPath());
        assertTrue(expectedSave.length > 0);
        Files.write(nativeSave.toPath(), "damaged-matrix-save".getBytes(StandardCharsets.UTF_8));
        Nintendo3DsStorageLayout.RestoreReport restored =
                storage.restoreLatestAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        assertFalse(restored.isFallbackSnapshotUsed());
        assertArrayEquals(expectedSave, Files.readAllBytes(nativeSave.toPath()));

        PersistenceMatrixRun reopenedRun = runPersistenceMatrix(
                context,
                corePath,
                Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                storage,
                contentPaths,
                2);

        Set<String> reopenedTitleRoots = titleSaveRoots(storage.latestEntryPathsForTesting());
        assertTrue(reopenedTitleRoots.containsAll(titleRoots));
        int openedSessions = initialRun.openedSessions + reopenedRun.openedSessions;
        int closedSessions = initialRun.closedSessions + reopenedRun.closedSessions;
        assertEquals(openedSessions, closedSessions);
        System.out.println(
                "N3DS_PERSISTENCE_MATRIX contents=" + contentPaths.size()
                        + " framesPerContent=" + framesPerContent
                        + " titleSaveRoots=" + titleRoots.size()
                        + " checkpointEntries=" + checkpointEntries.size()
                        + " restoredBytes=" + restored.getRestoredBytes()
                        + " generation=" + initialRun.finalGeneration
                        + " reopenedContents=" + contentPaths.size()
                        + " opened=" + openedSessions
                        + " closed=" + closedSessions);
    }

    @Test
    public void recordsInteractiveSessionSaveMutation() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue(
                "Validação jogável requer -e n3dsInteractiveValidation true.",
                Boolean.parseBoolean(arguments.getString(
                        "n3dsInteractiveValidation", "false")));
        String corePath = arguments.getString("n3dsCorePath", "");
        String contentPath = arguments.getString("n3dsContentPath", "");
        int interactiveSeconds = boundedArgument(
                arguments, "n3dsInteractiveSeconds", 180, 30, 1800);
        boolean requireSaveMutation = Boolean.parseBoolean(arguments.getString(
                "n3dsInteractiveRequireSave", "true"));
        boolean requireExistingTitleRoot = Boolean.parseBoolean(arguments.getString(
                "n3dsInteractiveRequireExistingTitleRoot", "false"));
        List<Nintendo3DsInteractiveInputPlan.Step> scriptedInputPlan =
                Nintendo3DsInteractiveInputPlan.parse(
                        arguments.getString("n3dsInteractiveInputPlan", ""),
                        TimeUnit.SECONDS.toMillis(interactiveSeconds));
        assumeTrue("Teste real requer -e n3dsCorePath", new File(corePath).isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", new File(contentPath).isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                context.getFilesDir(), context.getCacheDir());
        Map<String, String> before = snapshotNativeTitleFiles(storage.getUserDirectory());
        Set<String> beforeTitleRoots = titleSaveRoots(before.keySet());
        if (requireExistingTitleRoot) {
            assertTrue(
                    "A regressão estrita requer ao menos uma raiz de título preexistente.",
                    !beforeTitleRoots.isEmpty());
        }
        Intent intent = new Intent(context, Nintendo3DsLifecycleTestActivity.class)
                .putExtra(Nintendo3DsLifecycleTestActivity.EXTRA_CORE_PATH, corePath)
                .putExtra(Nintendo3DsLifecycleTestActivity.EXTRA_CONTENT_PATH, contentPath)
                .putExtra(Nintendo3DsLifecycleTestActivity.EXTRA_INTERACTIVE, true);

        long frames;
        long inputEvents;
        Throwable lifecycleFailure;
        try (ActivityScenario<Nintendo3DsLifecycleTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            Nintendo3DsLifecycleTestActivity activity = awaitInteractiveActivity(scenario);
            long deadline = SystemClock.elapsedRealtime()
                    + TimeUnit.SECONDS.toMillis(interactiveSeconds);
            runInteractiveInputPlan(activity, scriptedInputPlan, deadline);
            while (SystemClock.elapsedRealtime() < deadline) {
                assertInteractiveActivityHealthy(activity);
                SystemClock.sleep(250);
            }
            frames = activity.getInteractiveFrameCount();
            inputEvents = activity.getInteractiveInputEventCount();
            lifecycleFailure = activity.getLifecycleFailure();
        }

        assertEquals(null, lifecycleFailure);
        assertTrue("A sessão interativa 3DS não apresentou frames suficientes.", frames >= 60);
        assertTrue("A sessão interativa 3DS não recebeu entrada roteada.", inputEvents >= 2);
        Map<String, String> after = snapshotNativeTitleFiles(storage.getUserDirectory());
        Set<String> changedEntries = new TreeSet<>(before.keySet());
        changedEntries.addAll(after.keySet());
        changedEntries.removeIf(path -> Objects.equals(before.get(path), after.get(path)));
        Set<String> changedTitleRoots = titleSaveRoots(changedEntries);
        Set<String> changedExistingTitleRoots = new TreeSet<>(changedTitleRoots);
        changedExistingTitleRoots.retainAll(beforeTitleRoots);
        System.out.println(
                "N3DS_INTERACTIVE_SESSION interactiveSeconds=" + interactiveSeconds
                        + " frames=" + frames
                        + " inputEvents=" + inputEvents
                        + " scriptedInputSteps=" + scriptedInputPlan.size()
                        + " requireSaveMutation=" + requireSaveMutation
                        + " requireExistingTitleRoot=" + requireExistingTitleRoot
                        + " changedEntries=" + changedEntries.size()
                        + " changedTitleRoots=" + changedTitleRoots.size()
                        + " changedExistingTitleRoots=" + changedExistingTitleRoots.size()
                        + " beforeEntries=" + before.size()
                        + " beforeTitleRoots=" + beforeTitleRoots.size()
                        + " afterEntries=" + after.size());
        if (requireSaveMutation) {
            assertTrue(
                    "A sessão interativa não alterou nenhum save nativo de título.",
                    !changedEntries.isEmpty());
            assertTrue(
                    "A sessão interativa não identificou a raiz do título alterado.",
                    !changedTitleRoots.isEmpty());
        }
        if (requireExistingTitleRoot) {
            assertTrue(
                    "A sessão interativa não alterou uma raiz de título preexistente.",
                    !changedExistingTitleRoots.isEmpty());
        }
    }

    private static void runInteractiveInputPlan(
            Nintendo3DsLifecycleTestActivity activity,
            List<Nintendo3DsInteractiveInputPlan.Step> steps,
            long deadline) {
        for (Nintendo3DsInteractiveInputPlan.Step step : steps) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                break;
            }
            assertInteractiveActivityHealthy(activity);
            switch (step.getKind()) {
                case WAIT:
                    awaitInteractiveStep(activity, step.getDurationMillis(), deadline);
                    break;
                case BUTTON:
                    activity.setScriptedButtonPressed(step.getButton(), true);
                    try {
                        awaitInteractiveStep(activity, step.getDurationMillis(), deadline);
                    } finally {
                        if (!activity.getController().isClosed()) {
                            activity.setScriptedButtonPressed(step.getButton(), false);
                        }
                    }
                    break;
                case CIRCLE:
                    activity.setScriptedCirclePad(step.getHorizontal(), step.getVertical());
                    try {
                        awaitInteractiveStep(activity, step.getDurationMillis(), deadline);
                    } finally {
                        if (!activity.getController().isClosed()) {
                            activity.setScriptedCirclePad(0.0f, 0.0f);
                        }
                    }
                    break;
                case CIRCLE_BUTTON:
                    activity.setScriptedCirclePad(step.getHorizontal(), step.getVertical());
                    try {
                        activity.setScriptedButtonPressed(step.getButton(), true);
                        awaitInteractiveStep(activity, step.getDurationMillis(), deadline);
                    } finally {
                        if (!activity.getController().isClosed()) {
                            activity.setScriptedButtonPressed(step.getButton(), false);
                            activity.setScriptedCirclePad(0.0f, 0.0f);
                        }
                    }
                    break;
                case CIRCLE_BUTTONS:
                    activity.setScriptedCirclePad(step.getHorizontal(), step.getVertical());
                    boolean firstButtonPressed = false;
                    boolean secondButtonPressed = false;
                    try {
                        activity.setScriptedButtonPressed(step.getButton(), true);
                        firstButtonPressed = true;
                        activity.setScriptedButtonPressed(step.getSecondButton(), true);
                        secondButtonPressed = true;
                        awaitInteractiveStep(activity, step.getDurationMillis(), deadline);
                    } finally {
                        if (!activity.getController().isClosed()) {
                            if (secondButtonPressed) {
                                activity.setScriptedButtonPressed(
                                        step.getSecondButton(), false);
                            }
                            if (firstButtonPressed) {
                                activity.setScriptedButtonPressed(step.getButton(), false);
                            }
                            activity.setScriptedCirclePad(0.0f, 0.0f);
                        }
                    }
                    break;
                case TOUCH:
                    activity.setScriptedTouchFromSurfaceFraction(
                            step.getHorizontal(), step.getVertical(), true);
                    try {
                        awaitInteractiveStep(activity, step.getDurationMillis(), deadline);
                    } finally {
                        if (!activity.getController().isClosed()) {
                            activity.setScriptedTouchFromSurfaceFraction(
                                    step.getHorizontal(), step.getVertical(), false);
                        }
                    }
                    break;
                default:
                    throw new AssertionError("Passo interativo não suportado: " + step.getKind());
            }
        }
    }

    private static void awaitInteractiveStep(
            Nintendo3DsLifecycleTestActivity activity,
            long durationMillis,
            long deadline) {
        long now = SystemClock.elapsedRealtime();
        if (now >= deadline) {
            return;
        }
        long stepDeadline = Math.min(deadline, Math.addExact(now, durationMillis));
        while (SystemClock.elapsedRealtime() < stepDeadline) {
            assertInteractiveActivityHealthy(activity);
            long remaining = stepDeadline - SystemClock.elapsedRealtime();
            SystemClock.sleep(Math.min(250, Math.max(1, remaining)));
        }
    }

    private static void assertInteractiveActivityHealthy(
            Nintendo3DsLifecycleTestActivity activity) {
        assertTrue("A Activity jogável 3DS saiu do modo interativo.",
                activity.isInteractiveMode());
        assertTrue("A sessão jogável 3DS foi encerrada antes do prazo.",
                !activity.getController().isClosed());
        assertTrue("A Activity jogável 3DS perdeu o primeiro plano.",
                activity.getController().isReadyForFrames());
    }

    @Test
    public void preservesExactSnapshotsAcrossAzaharCoreUpdateAndRollback() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String baseCorePath = arguments.getString("n3dsCorePath", "");
        String updateCorePath = arguments.getString("n3dsUpdateCorePath", "");
        String updateCoreRevision = arguments.getString("n3dsUpdateCoreRevision", "");
        int expectedContentCount = boundedArgument(
                arguments, "n3dsExpectedContentCount", 3, 1, 5);
        int framesPerContent = boundedArgument(
                arguments, "n3dsUpdateFrames", 120, 2, 1800);
        assumeTrue("Teste real requer o core base.", new File(baseCorePath).isFile());
        assumeTrue("Teste real requer o core de atualização.", new File(updateCorePath).isFile());
        assumeTrue("Teste real requer revisão de atualização fixada.",
                updateCoreRevision.matches("[0-9a-f]{40}"));

        List<String> contentPaths = privateContentPaths(arguments);
        assumeTrue(
                "Atualização requer todos os conteúdos privados declarados.",
                contentPaths.size() >= expectedContentCount);
        contentPaths = new ArrayList<>(contentPaths.subList(0, expectedContentCount));

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String runId = Long.toString(SystemClock.elapsedRealtimeNanos());
        Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                new File(context.getFilesDir(), "n3ds-core-update-" + runId),
                new File(context.getCacheDir(), "n3ds-core-update-" + runId));

        PersistenceMatrixRun baseRun = runPersistenceMatrix(
                context,
                baseCorePath,
                Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                storage,
                contentPaths,
                framesPerContent);
        Set<String> baseEntries = storage.latestEntryPathsForTesting();
        Set<String> baseTitleRoots = titleSaveRoots(baseEntries);
        assertTrue(baseTitleRoots.size() >= 2);
        String nativeEntry = firstNonEmptyTitleSave(storage, baseEntries);
        File nativeFile = new File(
                storage.getUserDirectory(),
                nativeEntry.replace('/', File.separatorChar));
        byte[] baseBytes = Files.readAllBytes(nativeFile.toPath());

        PersistenceMatrixRun updateRun = runPersistenceMatrix(
                context,
                updateCorePath,
                updateCoreRevision,
                storage,
                contentPaths,
                framesPerContent);
        assertTrue(updateRun.finalGeneration > baseRun.finalGeneration);
        Set<String> updateEntries = storage.latestEntryPathsForTesting();
        assertTrue(titleSaveRoots(updateEntries).containsAll(baseTitleRoots));
        assertTrue(nativeFile.isFile());
        byte[] updateBytes = Files.readAllBytes(nativeFile.toPath());
        assertTrue(updateBytes.length > 0);

        Files.write(nativeFile.toPath(), "damaged-update-save".getBytes(StandardCharsets.UTF_8));
        Nintendo3DsStorageLayout.RestoreReport restoredUpdate =
                storage.restoreLatestAfterCoreClosed(updateCoreRevision);
        assertFalse(restoredUpdate.isFallbackSnapshotUsed());
        assertArrayEquals(updateBytes, Files.readAllBytes(nativeFile.toPath()));

        Nintendo3DsStorageLayout.RestoreReport restoredBase =
                storage.restoreLatestAfterCoreClosed(
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
        assertTrue(restoredBase.isFallbackSnapshotUsed());
        assertArrayEquals(baseBytes, Files.readAllBytes(nativeFile.toPath()));

        Nintendo3DsStorageLayout.RestoreReport restoredUpdateAgain =
                storage.restoreLatestAfterCoreClosed(updateCoreRevision);
        assertTrue(restoredUpdateAgain.isFallbackSnapshotUsed());
        assertArrayEquals(updateBytes, Files.readAllBytes(nativeFile.toPath()));

        PersistenceMatrixRun reopenedRun = runPersistenceMatrix(
                context,
                updateCorePath,
                updateCoreRevision,
                storage,
                contentPaths,
                2);
        int openedSessions = baseRun.openedSessions
                + updateRun.openedSessions
                + reopenedRun.openedSessions;
        int closedSessions = baseRun.closedSessions
                + updateRun.closedSessions
                + reopenedRun.closedSessions;
        assertEquals(openedSessions, closedSessions);
        System.out.println(
                "N3DS_CORE_UPDATE contents=" + contentPaths.size()
                        + " framesPerRevision=" + framesPerContent
                        + " baseGeneration=" + baseRun.finalGeneration
                        + " updateGeneration=" + updateRun.finalGeneration
                        + " baseTitleRoots=" + baseTitleRoots.size()
                        + " updateEntries=" + updateEntries.size()
                        + " restoredUpdateBytes=" + restoredUpdate.getRestoredBytes()
                        + " rollbackGeneration=" + restoredBase.getGeneration()
                        + " forwardGeneration=" + restoredUpdateAgain.getGeneration()
                        + " reopenedContents=" + contentPaths.size()
                        + " opened=" + openedSessions
                        + " closed=" + closedSessions);
    }

    private static PersistenceMatrixRun runPersistenceMatrix(
            Context context,
            String corePath,
            String coreRevision,
            Nintendo3DsStorageLayout storage,
            List<String> contentPaths,
            int framesPerContent) throws Exception {
        int openedSessions = 0;
        int closedSessions = 0;
        long finalGeneration = 0;
        for (String contentPath : contentPaths) {
            try (Nintendo3DsTestSurfaceOwner surfaceOwner =
                         new Nintendo3DsTestSurfaceOwner(400, 480);
                 Nintendo3DsCoreLifecycleController controller =
                         new Nintendo3DsCoreLifecycleController(
                                 context,
                                 corePath,
                                 contentPath,
                                 storage,
                                 coreRevision)) {
                controller.onSurfaceAvailable(surfaceOwner.surface, 400, 480);
                controller.onResume();
                Nintendo3DsCoreFrameReport report =
                        controller.runFrames(framesPerContent, 180_000);
                assertTrue(report.getPresentedFrames() >= framesPerContent);
                controller.restartSessionAndAwait(120_000);
                Nintendo3DsStorageLayout.CheckpointReport checkpoint =
                        controller.getLastStorageCheckpoint();
                assertTrue(checkpoint != null);
                assertTrue(checkpoint.getGeneration() >= finalGeneration);
                finalGeneration = checkpoint.getGeneration();
                openedSessions += controller.getOpenedSessionCount();
                closedSessions += controller.getClosedSessionCount();
            }
        }
        return new PersistenceMatrixRun(finalGeneration, openedSessions, closedSessions);
    }

    private static List<String> privateContentPaths(Bundle arguments) {
        List<String> paths = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            String key = index == 1 ? "n3dsContentPath" : "n3dsContentPath" + index;
            String path = arguments.getString(key, "");
            if (!path.isEmpty() && new File(path).isFile()) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static Nintendo3DsLifecycleTestActivity awaitInteractiveActivity(
            ActivityScenario<Nintendo3DsLifecycleTestActivity> scenario) {
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        AtomicReference<Nintendo3DsLifecycleTestActivity> current = new AtomicReference<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(current::set);
            Nintendo3DsLifecycleTestActivity activity = current.get();
            if (activity != null
                    && activity.isInteractiveMode()
                    && activity.getController().isReadyForFrames()) {
                return activity;
            }
            SystemClock.sleep(50);
        }
        throw new AssertionError("A Activity jogável 3DS não ficou pronta.");
    }

    private static Map<String, String> snapshotNativeTitleFiles(File userDirectory)
            throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        if (!userDirectory.isDirectory()) {
            return files;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths =
                     Files.walk(userDirectory.toPath())) {
            for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String relative = userDirectory.toPath().relativize(path)
                        .toString().replace(File.separatorChar, '/');
                if (!relative.contains("/title/00040000/")) {
                    continue;
                }
                files.put(relative, path.toFile().length() + ":" + sha256(path.toFile()));
            }
        }
        return files;
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível no Android.", exception);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file.toPath())) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder hex = new StringBuilder(digest.getDigestLength() * 2);
        for (byte value : digest.digest()) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static Set<String> titleSaveRoots(Set<String> entries) {
        Set<String> roots = new HashSet<>();
        for (String entry : entries) {
            String[] segments = entry.split("/");
            for (int index = 0; index + 2 < segments.length; index++) {
                if ("title".equals(segments[index]) && "00040000".equals(segments[index + 1])) {
                    roots.add(segments[index + 1] + "/" + segments[index + 2]);
                }
            }
        }
        return roots;
    }

    private static String firstNonEmptyTitleSave(
            Nintendo3DsStorageLayout storage,
            Set<String> entries) throws IOException {
        for (String entry : entries) {
            if (!entry.contains("/title/00040000/")) {
                continue;
            }
            File candidate = new File(
                    storage.getUserDirectory(),
                    entry.replace('/', File.separatorChar));
            if (candidate.isFile() && candidate.length() > 0) {
                return entry;
            }
        }
        throw new IOException("A matriz 3DS não produziu save de título não vazio.");
    }

    private static void writePrivateFile(File root, String relativePath, String contents)
            throws IOException {
        File target = new File(root, relativePath.replace('/', File.separatorChar));
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteExactTestTree(File root, File allowedParent) throws IOException {
        java.nio.file.Path parent = allowedParent.getCanonicalFile().toPath();
        java.nio.file.Path checked = root.getCanonicalFile().toPath();
        if (checked.equals(parent) || !checked.startsWith(parent)) {
            throw new IOException("A limpeza focal 3DS saiu da raiz temporária permitida.");
        }
        if (!Files.exists(checked)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(checked)) {
            for (java.nio.file.Path path : paths.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String readPrivateFile(File root, String relativePath) throws IOException {
        File target = new File(root, relativePath.replace('/', File.separatorChar));
        return new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
    }

    private static String runtimePath(Bundle arguments, String key, String cloudFileName) {
        String configured = arguments.getString(key, "");
        if (!configured.isEmpty()) {
            return configured;
        }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new File(context.getFilesDir(), cloudFileName).getAbsolutePath();
    }

    private static int boundedArgument(
            Bundle arguments,
            String key,
            int defaultValue,
            int minimum,
            int maximum) {
        String rawValue = arguments.getString(key, Integer.toString(defaultValue));
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(rawValue)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Argumento numérico 3DS inválido: " + key, exception);
        }
    }

    private static KeyEvent gamepadKey(int action, int keyCode) {
        long now = SystemClock.uptimeMillis();
        return new KeyEvent(
                now,
                now,
                action,
                keyCode,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_GAMEPAD);
    }

    private static MotionEvent gamepadMotion(
            float leftX,
            float leftY,
            float rightX,
            float rightY,
            float hatX,
            float hatY,
            float leftTrigger,
            float rightTrigger) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = leftX;
        coordinates.y = leftY;
        coordinates.setAxisValue(MotionEvent.AXIS_Z, rightX);
        coordinates.setAxisValue(MotionEvent.AXIS_RZ, rightY);
        coordinates.setAxisValue(MotionEvent.AXIS_HAT_X, hatX);
        coordinates.setAxisValue(MotionEvent.AXIS_HAT_Y, hatY);
        coordinates.setAxisValue(MotionEvent.AXIS_LTRIGGER, leftTrigger);
        coordinates.setAxisValue(MotionEvent.AXIS_RTRIGGER, rightTrigger);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_MOVE,
                1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coordinates},
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD,
                0);
    }

    private static final class PersistenceMatrixRun {
        private final long finalGeneration;
        private final int openedSessions;
        private final int closedSessions;

        private PersistenceMatrixRun(
                long finalGeneration,
                int openedSessions,
                int closedSessions) {
            this.finalGeneration = finalGeneration;
            this.openedSessions = openedSessions;
            this.closedSessions = closedSessions;
        }
    }
}
