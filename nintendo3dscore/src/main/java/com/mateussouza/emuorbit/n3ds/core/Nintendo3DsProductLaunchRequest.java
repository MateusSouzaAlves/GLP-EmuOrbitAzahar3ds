// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.util.Objects;

/** Validated, explicit launch contract for the still-hidden Nintendo 3DS product host. */
public final class Nintendo3DsProductLaunchRequest {
    static final String EXTRA_CORE_PATH = "n3ds.product.core_path";
    static final String EXTRA_CONTENT_PATH = "n3ds.product.content_path";
    static final String EXTRA_PERSISTENT_ID = "n3ds.product.persistent_id";
    static final String EXTRA_CONTENT_STATUS = "n3ds.product.content_status";
    static final String EXTRA_REQUIRED_STORAGE = "n3ds.product.required_storage";
    static final String EXTRA_MII_REQUIREMENT = "n3ds.product.mii_requirement";
    static final String EXTRA_DEVICE_QUALIFIED = "n3ds.product.device_qualified";

    private final File coreLibrary;
    private final File content;
    private final String persistentId;
    private final Nintendo3DsLaunchReadiness.ContentStatus contentStatus;
    private final long requiredPrivateStorageBytes;
    private final Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement;
    private final boolean deviceQualified;

    public Nintendo3DsProductLaunchRequest(
            File coreLibrary,
            File content,
            String persistentId,
            Nintendo3DsLaunchReadiness.ContentStatus contentStatus,
            long requiredPrivateStorageBytes,
            Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
            boolean deviceQualified) {
        this.coreLibrary = requireAbsolute(coreLibrary, "coreLibrary");
        this.content = requireAbsolute(content, "content");
        this.persistentId = Nintendo3DsExperienceSettingsStore.normalizePersistentId(
                persistentId);
        this.contentStatus = Objects.requireNonNull(contentStatus);
        if (requiredPrivateStorageBytes < 0L) {
            throw new IllegalArgumentException(
                    "O espaço privado 3DS necessário não pode ser negativo.");
        }
        this.requiredPrivateStorageBytes = requiredPrivateStorageBytes;
        this.miiRequirement = Objects.requireNonNull(miiRequirement);
        this.deviceQualified = deviceQualified;
    }

    /** Creates an explicit, non-exported Activity intent without exposing a base-app dependency. */
    public Intent createIntent(Context context) {
        return new Intent(Objects.requireNonNull(context), Nintendo3DsProductActivity.class)
                .putExtra(EXTRA_CORE_PATH, coreLibrary.getAbsolutePath())
                .putExtra(EXTRA_CONTENT_PATH, content.getAbsolutePath())
                .putExtra(EXTRA_PERSISTENT_ID, persistentId)
                .putExtra(EXTRA_CONTENT_STATUS, contentStatus.name())
                .putExtra(EXTRA_REQUIRED_STORAGE, requiredPrivateStorageBytes)
                .putExtra(EXTRA_MII_REQUIREMENT, miiRequirement.name())
                .putExtra(EXTRA_DEVICE_QUALIFIED, deviceQualified);
    }

    public File getCoreLibrary() {
        return coreLibrary;
    }

    public File getContent() {
        return content;
    }

    public String getPersistentId() {
        return persistentId;
    }

    public Nintendo3DsLaunchReadiness.ContentStatus getContentStatus() {
        return contentStatus;
    }

    public long getRequiredPrivateStorageBytes() {
        return requiredPrivateStorageBytes;
    }

    public Nintendo3DsLaunchReadiness.MiiRequirement getMiiRequirement() {
        return miiRequirement;
    }

    public boolean isDeviceQualified() {
        return deviceQualified;
    }

    static Nintendo3DsProductLaunchRequest fromIntent(Intent intent) {
        Intent checked = Objects.requireNonNull(intent);
        return new Nintendo3DsProductLaunchRequest(
                new File(requireString(checked, EXTRA_CORE_PATH)),
                new File(requireString(checked, EXTRA_CONTENT_PATH)),
                requireString(checked, EXTRA_PERSISTENT_ID),
                parseEnum(
                        Nintendo3DsLaunchReadiness.ContentStatus.class,
                        requireString(checked, EXTRA_CONTENT_STATUS)),
                checked.getLongExtra(EXTRA_REQUIRED_STORAGE, -1L),
                parseEnum(
                        Nintendo3DsLaunchReadiness.MiiRequirement.class,
                        requireString(checked, EXTRA_MII_REQUIREMENT)),
                checked.getBooleanExtra(EXTRA_DEVICE_QUALIFIED, false));
    }

    private static File requireAbsolute(File value, String name) {
        File checked = Objects.requireNonNull(value, name);
        if (!checked.isAbsolute()) {
            throw new IllegalArgumentException(name + " deve usar um caminho absoluto.");
        }
        return checked;
    }

    private static String requireString(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Extra obrigatório ausente: " + key);
        }
        return value;
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Valor de launch 3DS inválido: " + value, failure);
        }
    }
}
