// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Collects lightweight Android/core/storage evidence for the pure 3DS readiness policy. */
public final class Nintendo3DsAndroidLaunchReadinessInspector {
    private static final Set<String> REQUIRED_EXTENSIONS =
            Set.of("3ds", "3dsx", "cci", "cxi");

    private final Context context;
    private final Nintendo3DsStorageLayout storageLayout;
    private final Nintendo3DsMiiDataManager miiDataManager;

    public Nintendo3DsAndroidLaunchReadinessInspector(
            Context context,
            Nintendo3DsStorageLayout storageLayout,
            Nintendo3DsMiiDataManager miiDataManager) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.storageLayout = Objects.requireNonNull(storageLayout);
        this.miiDataManager = Objects.requireNonNull(miiDataManager);
    }

    public Nintendo3DsLaunchReadiness.Result inspect(
            File coreLibrary,
            Nintendo3DsLaunchReadiness.ContentStatus contentStatus,
            long requiredPrivateStorageBytes,
            Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
            boolean deviceQualified) {
        Nintendo3DsLaunchReadiness.CoreStatus coreStatus = inspectCore(coreLibrary);
        Nintendo3DsMiiDataManager.Status miiStatus;
        try {
            miiStatus = miiDataManager.inspect().getStatus();
        } catch (IOException | RuntimeException unavailable) {
            miiStatus = Nintendo3DsMiiDataManager.Status.MALFORMED;
        }
        long usableStorage;
        try {
            usableStorage = storageLayout.getTransientDirectory().getUsableSpace();
        } catch (RuntimeException unavailable) {
            usableStorage = -1L;
        }
        return Nintendo3DsLaunchReadiness.evaluate(new Nintendo3DsLaunchReadiness.Probe(
                coreStatus,
                hasArm64Abi(),
                hasVulkanHardware(),
                Objects.requireNonNull(contentStatus),
                usableStorage,
                requiredPrivateStorageBytes,
                Objects.requireNonNull(miiRequirement),
                miiStatus,
                deviceQualified));
    }

    private Nintendo3DsLaunchReadiness.CoreStatus inspectCore(File coreLibrary) {
        if (coreLibrary == null || !coreLibrary.isFile() || !coreLibrary.canRead()) {
            return Nintendo3DsLaunchReadiness.CoreStatus.MISSING;
        }
        try {
            return isExpectedCore(Nintendo3DsCoreBootstrap.inspect(coreLibrary))
                    ? Nintendo3DsLaunchReadiness.CoreStatus.READY
                    : Nintendo3DsLaunchReadiness.CoreStatus.INVALID;
        } catch (IOException | RuntimeException | LinkageError invalid) {
            return Nintendo3DsLaunchReadiness.CoreStatus.INVALID;
        }
    }

    static boolean isExpectedCore(Nintendo3DsCoreInfo core) {
        Nintendo3DsCoreInfo checked = Objects.requireNonNull(core);
        return "Azahar".equals(checked.getLibraryName())
                && checked.getApiVersion() == 1
                && checked.needsFullPath()
                && checked.getEnvironmentCallbackCount() > 0
                && checked.isCoreOptionsRegistered()
                && checked.isControllerInfoRegistered()
                && checked.getValidExtensions().containsAll(REQUIRED_EXTENSIONS);
    }

    private static boolean hasArm64Abi() {
        return Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
    }

    private boolean hasVulkanHardware() {
        PackageManager packages = context.getPackageManager();
        return packages.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                && packages.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
    }
}
