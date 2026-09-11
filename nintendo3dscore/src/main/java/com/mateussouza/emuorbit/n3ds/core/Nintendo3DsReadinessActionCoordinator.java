// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import java.io.IOException;
import java.util.Objects;

/** Applies readiness actions without coupling the isolated module to the base app. */
public final class Nintendo3DsReadinessActionCoordinator {
    public enum DispatchResult {
        IGNORED,
        CALLBACK,
        EXTERNAL_INTENT,
        FAILED
    }

    @FunctionalInterface
    public interface ExternalIntentLauncher {
        void launch(Intent intent);
    }

    public interface Callback {
        void onActionRequested(Nintendo3DsReadinessPresentation.Action action);

        void onActionFailed(
                Nintendo3DsReadinessPresentation.Action action,
                Exception failure);
    }

    private final String applicationPackage;
    private final Nintendo3DsExperimentalHost experimentalHost;
    private final ExternalIntentLauncher externalIntentLauncher;
    private final Callback callback;

    public static Nintendo3DsReadinessActionCoordinator forActivity(
            Activity activity,
            Nintendo3DsExperimentalHost experimentalHost,
            Callback callback) {
        Activity checked = Objects.requireNonNull(activity);
        return new Nintendo3DsReadinessActionCoordinator(
                checked.getPackageName(),
                experimentalHost,
                checked::startActivity,
                callback);
    }

    /** Creates a coordinator whose launcher may be backed by the Activity Result API. */
    public Nintendo3DsReadinessActionCoordinator(
            String applicationPackage,
            Nintendo3DsExperimentalHost experimentalHost,
            ExternalIntentLauncher externalIntentLauncher,
            Callback callback) {
        this.applicationPackage = requirePackage(applicationPackage);
        this.experimentalHost = Objects.requireNonNull(experimentalHost);
        this.externalIntentLauncher = Objects.requireNonNull(externalIntentLauncher);
        this.callback = Objects.requireNonNull(callback);
    }

    public DispatchResult dispatch(Nintendo3DsReadinessPresentation.Action action) {
        Nintendo3DsReadinessPresentation.Action checked = Objects.requireNonNull(action);
        if (checked == Nintendo3DsReadinessPresentation.Action.NONE) {
            return DispatchResult.IGNORED;
        }
        if (checked == Nintendo3DsReadinessPresentation.Action.OPEN_STORAGE) {
            return launchExternal(checked, createApplicationStorageIntent(applicationPackage));
        }
        if (checked == Nintendo3DsReadinessPresentation.Action.IMPORT_MII) {
            try {
                Intent picker = experimentalHost.suspendSessionAndCreateMiiImportIntent();
                return launchExternal(checked, picker);
            } catch (IOException | RuntimeException failure) {
                callback.onActionFailed(checked, asException(failure));
                return DispatchResult.FAILED;
            }
        }
        callback.onActionRequested(checked);
        return DispatchResult.CALLBACK;
    }

    static Intent createApplicationStorageIntent(String applicationPackage) {
        return new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requirePackage(applicationPackage), null));
    }

    private DispatchResult launchExternal(
            Nintendo3DsReadinessPresentation.Action action,
            Intent intent) {
        try {
            externalIntentLauncher.launch(new Intent(intent));
            return DispatchResult.EXTERNAL_INTENT;
        } catch (RuntimeException failure) {
            callback.onActionFailed(action, failure);
            return DispatchResult.FAILED;
        }
    }

    private static String requirePackage(String applicationPackage) {
        String checked = Objects.requireNonNull(applicationPackage).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("Application package is required");
        }
        return checked;
    }

    private static Exception asException(Throwable failure) {
        return failure instanceof Exception
                ? (Exception) failure
                : new IllegalStateException(failure);
    }
}
