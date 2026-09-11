// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Lifecycle-bound bridge from readiness UI and Activity Result to the isolated 3DS host.
 *
 * <p>Construct this during {@link ComponentActivity#onCreate} before the Activity reaches
 * {@code STARTED}. The product still decides when the experimental system becomes visible.</p>
 */
public final class Nintendo3DsActivityResultHost implements AutoCloseable {
    @FunctionalInterface
    interface MiiPickerResultCallback {
        void onResult(int resultCode, Intent data);
    }

    @FunctionalInterface
    interface MiiPickerLauncher {
        void launch(Intent intent);
    }

    @FunctionalInterface
    interface MiiPickerRegistration {
        MiiPickerLauncher register(MiiPickerResultCallback callback);
    }

    public interface Listener {
        void onLaunchReady(Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch);

        void onExtractionRequested();

        void onUnderstood();

        void onFailure(
                Nintendo3DsReadinessPresentation.Action action,
                Exception failure);
    }

    private final ComponentActivity activity;
    private final Executor workerExecutor;
    private final Listener listener;
    private final Nintendo3DsMiiRecoveryCoordinator recoveryCoordinator;
    private final Nintendo3DsReadinessDialogController dialogController;
    private final Nintendo3DsReadinessActionCoordinator actionCoordinator;
    private final MiiPickerLauncher miiPickerLauncher;

    private boolean started;
    private boolean closed;

    public Nintendo3DsActivityResultHost(
            ComponentActivity activity,
            Nintendo3DsExperimentalHost experimentalHost,
            Nintendo3DsMiiRecoveryCoordinator.LaunchRequest request,
            Executor workerExecutor,
            Nintendo3DsReadinessDialogController.StyleAdapter styleAdapter,
            Listener listener) {
        this(
                activity,
                experimentalHost,
                request,
                workerExecutor,
                styleAdapter,
                activityResultRegistration(activity),
                listener);
    }

    Nintendo3DsActivityResultHost(
            ComponentActivity activity,
            Nintendo3DsExperimentalHost experimentalHost,
            Nintendo3DsMiiRecoveryCoordinator.LaunchRequest request,
            Executor workerExecutor,
            Nintendo3DsReadinessDialogController.StyleAdapter styleAdapter,
            MiiPickerRegistration miiPickerRegistration,
            Listener listener) {
        this.activity = Objects.requireNonNull(activity);
        this.workerExecutor = Objects.requireNonNull(workerExecutor);
        this.listener = Objects.requireNonNull(listener);
        recoveryCoordinator = new Nintendo3DsMiiRecoveryCoordinator(
                Objects.requireNonNull(experimentalHost),
                activity.getContentResolver(),
                workerExecutor,
                activity::runOnUiThread,
                Objects.requireNonNull(request));
        dialogController = new Nintendo3DsReadinessDialogController(
                Objects.requireNonNull(styleAdapter));
        miiPickerLauncher = Objects.requireNonNull(
                Objects.requireNonNull(miiPickerRegistration).register(
                        this::onMiiPickerResult));
        actionCoordinator = new Nintendo3DsReadinessActionCoordinator(
                activity.getPackageName(),
                experimentalHost,
                this::launchExternalIntent,
                new Nintendo3DsReadinessActionCoordinator.Callback() {
                    @Override
                    public void onActionRequested(
                            Nintendo3DsReadinessPresentation.Action action) {
                        handleInternalAction(action);
                    }

                    @Override
                    public void onActionFailed(
                            Nintendo3DsReadinessPresentation.Action action,
                            Exception failure) {
                        notifyFailure(action, failure);
                        present(recoveryCoordinator.getLatestLaunch());
                    }
                });
    }

    /** Starts the initial readiness inspection on the supplied worker executor. */
    public synchronized boolean start() {
        if (closed || started) {
            return false;
        }
        started = true;
        try {
            workerExecutor.execute(() -> {
                Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch;
                try {
                    preparedLaunch = recoveryCoordinator.prepareInitialLaunch();
                } catch (RuntimeException failure) {
                    postFailure(
                            Nintendo3DsReadinessPresentation.Action.RETRY,
                            asException(failure));
                    return;
                }
                activity.runOnUiThread(() -> present(preparedLaunch));
            });
            return true;
        } catch (RuntimeException rejected) {
            started = false;
            postFailure(
                    Nintendo3DsReadinessPresentation.Action.RETRY,
                    new IOException("Não foi possível iniciar o preflight Nintendo 3DS.", rejected));
            return false;
        }
    }

    /** Rechecks a retryable blocker, including return from application storage settings. */
    public boolean retry() {
        if (isClosed()) {
            return false;
        }
        return recoveryCoordinator.retryPreflight(
                new Nintendo3DsMiiRecoveryCoordinator.PreflightListener() {
                    @Override
                    public void onPrepared(
                            Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch) {
                        present(preparedLaunch);
                    }

                    @Override
                    public void onPreflightFailed(IOException failure) {
                        notifyFailure(Nintendo3DsReadinessPresentation.Action.RETRY, failure);
                        present(recoveryCoordinator.getLatestLaunch());
                    }
                });
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public boolean isDialogShowing() {
        return dialogController.isShowing();
    }

    Nintendo3DsReadinessDialogController getDialogControllerForTesting() {
        return dialogController;
    }

    Nintendo3DsMiiRecoveryCoordinator getRecoveryCoordinatorForTesting() {
        return recoveryCoordinator;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        dialogController.close();
        recoveryCoordinator.close();
    }

    private void onMiiPickerResult(int resultCode, Intent data) {
        if (isClosed()) {
            return;
        }
        Uri selectedDocument = resultCode == Activity.RESULT_OK && data != null
                ? data.getData()
                : null;
        if (selectedDocument == null) {
            present(recoveryCoordinator.getLatestLaunch());
            return;
        }
        boolean startedImport = recoveryCoordinator.importSelectedMiiAndReprepare(
                selectedDocument,
                new Nintendo3DsMiiRecoveryCoordinator.Listener() {
                    @Override
                    public void onRecovered(
                            Nintendo3DsMiiRecoveryCoordinator.Recovery recovery) {
                        present(recovery.getPreparedLaunch());
                    }

                    @Override
                    public void onRecoveryFailed(IOException failure) {
                        notifyFailure(
                                Nintendo3DsReadinessPresentation.Action.IMPORT_MII,
                                failure);
                        present(recoveryCoordinator.getLatestLaunch());
                    }
                });
        if (!startedImport) {
            present(recoveryCoordinator.getLatestLaunch());
        }
    }

    private void launchExternalIntent(Intent intent) {
        Intent checked = new Intent(Objects.requireNonNull(intent));
        if (Intent.ACTION_OPEN_DOCUMENT.equals(checked.getAction())) {
            miiPickerLauncher.launch(checked);
        } else {
            activity.startActivity(checked);
        }
    }

    private void handleInternalAction(Nintendo3DsReadinessPresentation.Action action) {
        if (isClosed()) {
            return;
        }
        switch (action) {
        case RETRY -> retry();
        case CONTINUE -> deliverCurrentLaunch();
        case EXTRACT_CONTENT -> listener.onExtractionRequested();
        case UNDERSTOOD -> listener.onUnderstood();
        default -> notifyFailure(action, new IllegalStateException(
                "Ação interna de readiness Nintendo 3DS inesperada: " + action));
        }
    }

    private void deliverCurrentLaunch() {
        Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch =
                recoveryCoordinator.getLatestLaunch();
        if (preparedLaunch == null || !preparedLaunch.getReadiness().canLaunch()
                || !preparedLaunch.hasController()) {
            notifyFailure(
                    Nintendo3DsReadinessPresentation.Action.CONTINUE,
                    new IllegalStateException("O launch Nintendo 3DS ainda está bloqueado."));
            if (preparedLaunch != null && preparedLaunch.getPresentation().isVisible()) {
                present(preparedLaunch);
            }
            return;
        }
        listener.onLaunchReady(preparedLaunch);
    }

    private void present(Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch) {
        if (isClosed() || preparedLaunch == null
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (!preparedLaunch.getPresentation().isVisible()) {
            deliverCurrentLaunch();
            return;
        }
        try {
            dialogController.show(
                    activity,
                    preparedLaunch.getPresentation(),
                    actionCoordinator);
        } catch (RuntimeException failure) {
            notifyFailure(
                    preparedLaunch.getPresentation().getPrimaryAction(),
                    asException(failure));
        }
    }

    private void postFailure(
            Nintendo3DsReadinessPresentation.Action action,
            Exception failure) {
        activity.runOnUiThread(() -> notifyFailure(action, failure));
    }

    private void notifyFailure(
            Nintendo3DsReadinessPresentation.Action action,
            Exception failure) {
        if (!isClosed()) {
            listener.onFailure(action, failure);
        }
    }

    private static Exception asException(Throwable failure) {
        return failure instanceof Exception
                ? (Exception) failure
                : new IllegalStateException(failure);
    }

    private static MiiPickerRegistration activityResultRegistration(
            ComponentActivity activity) {
        ComponentActivity checkedActivity = Objects.requireNonNull(activity);
        return callback -> {
            ActivityResultLauncher<Intent> launcher = checkedActivity.registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> callback.onResult(result.getResultCode(), result.getData()));
            return launcher::launch;
        };
    }
}
