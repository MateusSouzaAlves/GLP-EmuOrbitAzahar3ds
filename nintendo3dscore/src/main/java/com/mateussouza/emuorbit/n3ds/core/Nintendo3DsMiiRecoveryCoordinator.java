// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Imports user-selected Mii data and repeats the exact launch preflight off the UI thread. */
public final class Nintendo3DsMiiRecoveryCoordinator implements AutoCloseable {
    public interface Listener {
        void onRecovered(Recovery recovery);

        void onRecoveryFailed(IOException failure);
    }

    public interface PreflightListener {
        void onPrepared(Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch);

        void onPreflightFailed(IOException failure);
    }

    public static final class LaunchRequest {
        private final File coreLibrary;
        private final File content;
        private final Nintendo3DsLaunchReadiness.ContentStatus contentStatus;
        private final long requiredPrivateStorageBytes;
        private final Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement;
        private final boolean deviceQualified;

        public LaunchRequest(
                File coreLibrary,
                File content,
                Nintendo3DsLaunchReadiness.ContentStatus contentStatus,
                long requiredPrivateStorageBytes,
                Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
                boolean deviceQualified) {
            this.coreLibrary = Objects.requireNonNull(coreLibrary);
            this.content = Objects.requireNonNull(content);
            this.contentStatus = Objects.requireNonNull(contentStatus);
            if (requiredPrivateStorageBytes < 0L) {
                throw new IllegalArgumentException(
                        "O espaço privado 3DS necessário não pode ser negativo.");
            }
            this.requiredPrivateStorageBytes = requiredPrivateStorageBytes;
            this.miiRequirement = Objects.requireNonNull(miiRequirement);
            this.deviceQualified = deviceQualified;
        }
    }

    public static final class Recovery {
        private final Nintendo3DsMiiDataManager.ImportReport importReport;
        private final Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch;

        private Recovery(
                Nintendo3DsMiiDataManager.ImportReport importReport,
                Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch) {
            this.importReport = Objects.requireNonNull(importReport);
            this.preparedLaunch = Objects.requireNonNull(preparedLaunch);
        }

        public Nintendo3DsMiiDataManager.ImportReport getImportReport() {
            return importReport;
        }

        public Nintendo3DsExperimentalHost.PreparedLaunch getPreparedLaunch() {
            return preparedLaunch;
        }
    }

    private final Nintendo3DsExperimentalHost experimentalHost;
    private final LaunchRequest request;
    private final Nintendo3DsMiiImportWorkflow importWorkflow;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;

    // Guarded by this.
    private Nintendo3DsExperimentalHost.PreparedLaunch latestLaunch;
    private boolean recoveryInFlight;
    private boolean closed;

    public Nintendo3DsMiiRecoveryCoordinator(
            Nintendo3DsExperimentalHost experimentalHost,
            ContentResolver contentResolver,
            Executor workerExecutor,
            Executor callbackExecutor,
            LaunchRequest request) {
        this.experimentalHost = Objects.requireNonNull(experimentalHost);
        Executor checkedWorker = Objects.requireNonNull(workerExecutor);
        this.workerExecutor = checkedWorker;
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
        this.request = Objects.requireNonNull(request);
        importWorkflow = new Nintendo3DsMiiImportWorkflow(
                experimentalHost,
                Objects.requireNonNull(contentResolver),
                checkedWorker,
                checkedWorker);
    }

    /** Runs the first preflight exactly once and retains its controller identity for recovery. */
    public synchronized Nintendo3DsExperimentalHost.PreparedLaunch prepareInitialLaunch() {
        requireOpen();
        if (latestLaunch != null) {
            throw new IllegalStateException("O preflight inicial Nintendo 3DS já foi executado.");
        }
        latestLaunch = experimentalHost.prepareLaunch(
                request.coreLibrary,
                request.content,
                request.contentStatus,
                request.requiredPrivateStorageBytes,
                request.miiRequirement,
                request.deviceQualified);
        return latestLaunch;
    }

    /** Accepts only a Mii recovery action from the current readiness result. */
    public boolean importSelectedMiiAndReprepare(Uri selectedDocument, Listener listener) {
        Listener checkedListener = Objects.requireNonNull(listener);
        if (selectedDocument == null) {
            return false;
        }
        synchronized (this) {
            if (closed || recoveryInFlight || latestLaunch == null
                    || latestLaunch.getPresentation().getPrimaryAction()
                    != Nintendo3DsReadinessPresentation.Action.IMPORT_MII) {
                return false;
            }
            recoveryInFlight = true;
        }

        boolean started = importWorkflow.importSelected(
                selectedDocument,
                new Nintendo3DsMiiImportWorkflow.Listener() {
                    @Override
                    public void onImported(Nintendo3DsMiiDataManager.ImportReport report) {
                        reprepareAfterImport(report, checkedListener);
                    }

                    @Override
                    public void onImportFailed(IOException failure) {
                        dispatchFailure(checkedListener, failure);
                    }
                });
        if (!started) {
            synchronized (this) {
                recoveryInFlight = false;
            }
        }
        return started;
    }

    /** Repeats the current readiness request after a retry or external storage recovery. */
    public boolean retryPreflight(PreflightListener listener) {
        PreflightListener checkedListener = Objects.requireNonNull(listener);
        synchronized (this) {
            if (closed || recoveryInFlight || latestLaunch == null) {
                return false;
            }
            Nintendo3DsReadinessPresentation.Action action =
                    latestLaunch.getPresentation().getPrimaryAction();
            if (action != Nintendo3DsReadinessPresentation.Action.RETRY
                    && action != Nintendo3DsReadinessPresentation.Action.OPEN_STORAGE) {
                return false;
            }
            recoveryInFlight = true;
        }
        try {
            workerExecutor.execute(() -> reprepareCurrentLaunch(checkedListener));
            return true;
        } catch (RuntimeException rejected) {
            synchronized (this) {
                recoveryInFlight = false;
            }
            dispatchPreflightFailure(checkedListener, new IOException(
                    "Não foi possível iniciar o novo preflight Nintendo 3DS.", rejected));
            return false;
        }
    }

    public synchronized boolean isRecoveryInFlight() {
        return recoveryInFlight;
    }

    public synchronized Nintendo3DsExperimentalHost.PreparedLaunch getLatestLaunch() {
        return latestLaunch;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        recoveryInFlight = false;
        importWorkflow.close();
    }

    private void reprepareAfterImport(
            Nintendo3DsMiiDataManager.ImportReport report,
            Listener listener) {
        Nintendo3DsExperimentalHost.PreparedLaunch previous;
        synchronized (this) {
            if (closed) {
                recoveryInFlight = false;
                return;
            }
            previous = latestLaunch;
        }

        Nintendo3DsExperimentalHost.PreparedLaunch refreshed;
        try {
            refreshed = experimentalHost.reprepareLaunch(
                    previous,
                    request.coreLibrary,
                    request.content,
                    request.contentStatus,
                    request.requiredPrivateStorageBytes,
                    request.miiRequirement,
                    request.deviceQualified);
        } catch (IOException failure) {
            dispatchFailure(listener, failure);
            return;
        } catch (RuntimeException failure) {
            dispatchFailure(listener, new IOException(
                    "O novo preflight Nintendo 3DS falhou após importar o Mii.", failure));
            return;
        }
        synchronized (this) {
            if (closed) {
                recoveryInFlight = false;
                return;
            }
            if (latestLaunch != previous) {
                dispatchFailure(listener, new IOException(
                        "O launch Nintendo 3DS mudou durante a recuperação Mii."));
                return;
            }
            latestLaunch = refreshed;
        }
        dispatchSuccess(listener, new Recovery(report, refreshed));
    }

    private void reprepareCurrentLaunch(PreflightListener listener) {
        Nintendo3DsExperimentalHost.PreparedLaunch previous;
        synchronized (this) {
            if (closed) {
                recoveryInFlight = false;
                return;
            }
            previous = latestLaunch;
        }
        Nintendo3DsExperimentalHost.PreparedLaunch refreshed;
        try {
            refreshed = experimentalHost.reprepareLaunch(
                    previous,
                    request.coreLibrary,
                    request.content,
                    request.contentStatus,
                    request.requiredPrivateStorageBytes,
                    request.miiRequirement,
                    request.deviceQualified);
        } catch (IOException failure) {
            dispatchPreflightFailure(listener, failure);
            return;
        } catch (RuntimeException failure) {
            dispatchPreflightFailure(listener, new IOException(
                    "O novo preflight Nintendo 3DS falhou.", failure));
            return;
        }
        synchronized (this) {
            if (closed) {
                recoveryInFlight = false;
                return;
            }
            if (latestLaunch != previous) {
                dispatchPreflightFailure(listener, new IOException(
                        "O launch Nintendo 3DS mudou durante o novo preflight."));
                return;
            }
            latestLaunch = refreshed;
        }
        dispatchPreflightSuccess(listener, refreshed);
    }

    private void dispatchSuccess(Listener listener, Recovery recovery) {
        dispatch(listener, recovery, null);
    }

    private void dispatchFailure(Listener listener, IOException failure) {
        dispatch(listener, null, Objects.requireNonNull(failure));
    }

    private void dispatch(
            Listener listener,
            Recovery recovery,
            IOException failure) {
        Runnable completion = () -> {
            synchronized (Nintendo3DsMiiRecoveryCoordinator.this) {
                recoveryInFlight = false;
                if (closed) {
                    return;
                }
                if (failure == null) {
                    listener.onRecovered(Objects.requireNonNull(recovery));
                } else {
                    listener.onRecoveryFailed(failure);
                }
            }
        };
        try {
            callbackExecutor.execute(completion);
        } catch (RuntimeException rejected) {
            synchronized (this) {
                recoveryInFlight = false;
                if (!closed) {
                    listener.onRecoveryFailed(new IOException(
                            "Não foi possível entregar o novo preflight Nintendo 3DS.",
                            rejected));
                }
            }
        }
    }

    private void dispatchPreflightSuccess(
            PreflightListener listener,
            Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch) {
        dispatchPreflight(listener, Objects.requireNonNull(preparedLaunch), null);
    }

    private void dispatchPreflightFailure(
            PreflightListener listener,
            IOException failure) {
        dispatchPreflight(listener, null, Objects.requireNonNull(failure));
    }

    private void dispatchPreflight(
            PreflightListener listener,
            Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch,
            IOException failure) {
        Runnable completion = () -> {
            synchronized (Nintendo3DsMiiRecoveryCoordinator.this) {
                recoveryInFlight = false;
                if (closed) {
                    return;
                }
                if (failure == null) {
                    listener.onPrepared(Objects.requireNonNull(preparedLaunch));
                } else {
                    listener.onPreflightFailed(failure);
                }
            }
        };
        try {
            callbackExecutor.execute(completion);
        } catch (RuntimeException rejected) {
            synchronized (this) {
                recoveryInFlight = false;
                if (!closed) {
                    listener.onPreflightFailed(new IOException(
                            "Não foi possível entregar o novo preflight Nintendo 3DS.",
                            rejected));
                }
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "O coordenador de recuperação Mii Nintendo 3DS está fechado.");
        }
    }
}
