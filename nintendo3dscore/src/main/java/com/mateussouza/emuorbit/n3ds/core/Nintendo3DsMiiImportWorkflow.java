// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the bounded SAF Mii import off the UI thread and serializes result delivery. */
public final class Nintendo3DsMiiImportWorkflow implements AutoCloseable {
    public interface Listener {
        void onImported(Nintendo3DsMiiDataManager.ImportReport report);

        void onImportFailed(IOException failure);
    }

    private final Nintendo3DsExperimentalHost experimentalHost;
    private final ContentResolver contentResolver;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final AtomicBoolean importInFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public Nintendo3DsMiiImportWorkflow(
            Nintendo3DsExperimentalHost experimentalHost,
            ContentResolver contentResolver,
            Executor workerExecutor,
            Executor callbackExecutor) {
        this.experimentalHost = Objects.requireNonNull(experimentalHost);
        this.contentResolver = Objects.requireNonNull(contentResolver);
        this.workerExecutor = Objects.requireNonNull(workerExecutor);
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
    }

    /**
     * Starts one import. A null URI means that the picker was cancelled and is ignored.
     * Returns false while another import is active or after this workflow has been closed.
     */
    public boolean importSelected(Uri selectedDocument, Listener listener) {
        Listener checkedListener = Objects.requireNonNull(listener);
        if (selectedDocument == null || closed.get()
                || !importInFlight.compareAndSet(false, true)) {
            return false;
        }
        try {
            workerExecutor.execute(() -> importOnWorker(selectedDocument, checkedListener));
            return true;
        } catch (RuntimeException rejected) {
            importInFlight.set(false);
            dispatchFailure(
                    checkedListener,
                    new IOException("Não foi possível iniciar a importação Mii.", rejected));
            return false;
        }
    }

    public boolean isImportInFlight() {
        return importInFlight.get();
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private void importOnWorker(Uri selectedDocument, Listener listener) {
        Nintendo3DsMiiDataManager.ImportReport report = null;
        IOException failure = null;
        try {
            report = experimentalHost.importSelectedMii(contentResolver, selectedDocument);
        } catch (IOException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new IOException("A importação Mii falhou.", exception);
        }
        Nintendo3DsMiiDataManager.ImportReport completedReport = report;
        IOException completedFailure = failure;
        Runnable completion = () -> {
            importInFlight.set(false);
            if (closed.get()) {
                return;
            }
            if (completedFailure == null) {
                listener.onImported(Objects.requireNonNull(completedReport));
            } else {
                listener.onImportFailed(completedFailure);
            }
        };
        try {
            callbackExecutor.execute(completion);
        } catch (RuntimeException rejected) {
            importInFlight.set(false);
            if (!closed.get()) {
                listener.onImportFailed(new IOException(
                        "Não foi possível entregar o resultado da importação Mii.", rejected));
            }
        }
    }

    private void dispatchFailure(Listener listener, IOException failure) {
        if (closed.get()) {
            return;
        }
        try {
            callbackExecutor.execute(() -> {
                if (!closed.get()) {
                    listener.onImportFailed(failure);
                }
            });
        } catch (RuntimeException rejected) {
            if (!closed.get()) {
                listener.onImportFailed(new IOException(
                        "Não foi possível entregar a falha da importação Mii.", rejected));
            }
        }
    }
}
