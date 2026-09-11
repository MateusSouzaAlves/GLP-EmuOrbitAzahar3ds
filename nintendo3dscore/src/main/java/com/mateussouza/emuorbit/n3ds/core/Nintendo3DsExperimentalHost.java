// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Coordinates readiness, native lifecycle and user-owned Mii import for an experimental host.
 *
 * <p>The class remains inside the isolated 3DS module. It does not register or expose the system
 * in the base app, and it never owns the Android {@code Surface}; the eventual product host keeps
 * that lifecycle responsibility through {@link Nintendo3DsCoreLifecycleController}.</p>
 */
public final class Nintendo3DsExperimentalHost implements AutoCloseable {
    private final Context context;
    private final Nintendo3DsStorageLayout storageLayout;
    private final Nintendo3DsMiiDataManager miiDataManager;
    private final Nintendo3DsAndroidLaunchReadinessInspector readinessInspector;

    private volatile Nintendo3DsCoreLifecycleController controller;
    private boolean closed;

    public Nintendo3DsExperimentalHost(
            Context context,
            Nintendo3DsStorageLayout storageLayout) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.storageLayout = Objects.requireNonNull(storageLayout);
        miiDataManager = new Nintendo3DsMiiDataManager(
                storageLayout,
                this::hasActiveNativeSession);
        readinessInspector = new Nintendo3DsAndroidLaunchReadinessInspector(
                this.context,
                storageLayout,
                miiDataManager);
    }

    /** Inspects all preconditions and creates no controller when any blocker is present. */
    public synchronized PreparedLaunch prepareLaunch(
            File coreLibrary,
            File content,
            Nintendo3DsLaunchReadiness.ContentStatus declaredContentStatus,
            long requiredPrivateStorageBytes,
            Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
            boolean deviceQualified) {
        requireOpen();
        Nintendo3DsCoreLifecycleController current = controller;
        if (current != null && !current.isClosed()) {
            throw new IllegalStateException("O host Nintendo 3DS já possui um controlador ativo.");
        }

        Nintendo3DsLaunchReadiness.Result readiness = inspectReadiness(
                coreLibrary,
                content,
                declaredContentStatus,
                requiredPrivateStorageBytes,
                miiRequirement,
                deviceQualified);
        if (!readiness.canLaunch()) {
            controller = null;
            return new PreparedLaunch(readiness, null);
        }

        Nintendo3DsCoreLifecycleController prepared = createController(coreLibrary, content);
        controller = prepared;
        return new PreparedLaunch(readiness, prepared);
    }

    /** Rechecks the same launch, reusing only the exact paused controller from the prior result. */
    synchronized PreparedLaunch reprepareLaunch(
            PreparedLaunch previous,
            File coreLibrary,
            File content,
            Nintendo3DsLaunchReadiness.ContentStatus declaredContentStatus,
            long requiredPrivateStorageBytes,
            Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
            boolean deviceQualified) throws IOException {
        requireOpen();
        PreparedLaunch checkedPrevious = Objects.requireNonNull(previous);
        Nintendo3DsCoreLifecycleController current = controller;
        if (current != checkedPrevious.controller) {
            throw new IllegalStateException(
                    "O launch Nintendo 3DS mudou durante a recuperação Mii.");
        }
        if (hasActiveNativeSession()) {
            throw new IllegalStateException(
                    "O novo preflight Mii exige a sessão Nintendo 3DS suspensa.");
        }

        Nintendo3DsLaunchReadiness.Result readiness = inspectReadiness(
                coreLibrary,
                content,
                declaredContentStatus,
                requiredPrivateStorageBytes,
                miiRequirement,
                deviceQualified);
        if (!readiness.canLaunch()) {
            if (current != null && !current.isClosed()) {
                current.close();
            }
            controller = null;
            return new PreparedLaunch(readiness, null);
        }
        if (current != null && !current.isClosed()) {
            return new PreparedLaunch(readiness, current);
        }

        Nintendo3DsCoreLifecycleController prepared = createController(coreLibrary, content);
        controller = prepared;
        return new PreparedLaunch(readiness, prepared);
    }

    /**
     * Quiesces and closes the native core session before handing the picker intent to the UI.
     *
     * <p>The controller remains reusable after its Android host resumes. If readiness blocked
     * controller creation because Mii data is required, this simply returns the same safe picker.</p>
     */
    public synchronized Intent suspendSessionAndCreateMiiImportIntent() throws IOException {
        requireOpen();
        Nintendo3DsCoreLifecycleController current = controller;
        if (current != null && !current.isClosed()) {
            current.onPauseAndAwait();
        }
        return Nintendo3DsMiiDataManager.createSafImportIntent();
    }

    /** Imports only while the native session is closed; the manager rechecks before publication. */
    public synchronized Nintendo3DsMiiDataManager.ImportReport importSelectedMii(
            Uri selectedDocument) throws IOException {
        requireOpen();
        return miiDataManager.importFromSafAfterCoreClosed(
                context.getContentResolver(),
                selectedDocument);
    }

    /** Variant for hosts that must use a scoped or test resolver. */
    public synchronized Nintendo3DsMiiDataManager.ImportReport importSelectedMii(
            ContentResolver resolver,
            Uri selectedDocument) throws IOException {
        requireOpen();
        return miiDataManager.importFromSafAfterCoreClosed(resolver, selectedDocument);
    }

    public boolean hasActiveNativeSession() {
        Nintendo3DsCoreLifecycleController current = controller;
        return current != null
                && !current.isClosed()
                && current.getOpenedSessionCount() > current.getClosedSessionCount();
    }

    Nintendo3DsMiiDataManager getMiiDataManagerForTesting() {
        return miiDataManager;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        Nintendo3DsCoreLifecycleController current = controller;
        if (current != null) {
            current.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("O host experimental Nintendo 3DS está fechado.");
        }
    }

    private Nintendo3DsLaunchReadiness.Result inspectReadiness(
            File coreLibrary,
            File content,
            Nintendo3DsLaunchReadiness.ContentStatus declaredContentStatus,
            long requiredPrivateStorageBytes,
            Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
            boolean deviceQualified) {
        Nintendo3DsLaunchReadiness.ContentStatus contentStatus =
                normalizeContentStatus(content, declaredContentStatus);
        return readinessInspector.inspect(
                coreLibrary,
                contentStatus,
                requiredPrivateStorageBytes,
                miiRequirement,
                deviceQualified);
    }

    private Nintendo3DsCoreLifecycleController createController(
            File coreLibrary,
            File content) {
        File checkedCore = Objects.requireNonNull(coreLibrary);
        File checkedContent = Objects.requireNonNull(content);
        return new Nintendo3DsCoreLifecycleController(
                context,
                checkedCore.getAbsolutePath(),
                checkedContent.getAbsolutePath(),
                storageLayout,
                Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
    }

    private static Nintendo3DsLaunchReadiness.ContentStatus normalizeContentStatus(
            File content,
            Nintendo3DsLaunchReadiness.ContentStatus declaredStatus) {
        Nintendo3DsLaunchReadiness.ContentStatus checked = Objects.requireNonNull(declaredStatus);
        if (checked != Nintendo3DsLaunchReadiness.ContentStatus.READY) {
            return checked;
        }
        return content != null && content.isFile() && content.canRead()
                ? checked
                : Nintendo3DsLaunchReadiness.ContentStatus.UNAVAILABLE;
    }

    public static final class PreparedLaunch {
        private final Nintendo3DsLaunchReadiness.Result readiness;
        private final Nintendo3DsReadinessPresentation.Model presentation;
        private final Nintendo3DsCoreLifecycleController controller;

        private PreparedLaunch(
                Nintendo3DsLaunchReadiness.Result readiness,
                Nintendo3DsCoreLifecycleController controller) {
            this.readiness = Objects.requireNonNull(readiness);
            presentation = Nintendo3DsReadinessPresentation.from(readiness);
            this.controller = controller;
        }

        public Nintendo3DsLaunchReadiness.Result getReadiness() {
            return readiness;
        }

        public Nintendo3DsReadinessPresentation.Model getPresentation() {
            return presentation;
        }

        public boolean hasController() {
            return controller != null;
        }

        public Nintendo3DsCoreLifecycleController requireController() {
            if (controller == null) {
                throw new IllegalStateException(
                        "O readiness bloqueou a criação do controlador Nintendo 3DS.");
            }
            return controller;
        }
    }
}
