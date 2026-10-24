// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Retains one serialized data operation across Activity recreation. No Activity,
 * View, dialog or picker callback is retained by the worker. Accepted I/O is
 * allowed to finish even when the hub is closed; never interrupt a restore. */
public final class Nintendo3DsDataOperationsModel extends AndroidViewModel {
    enum Operation { IMPORT_MII, CREATE_CHECKPOINT, RESTORE_CHECKPOINT, EXPORT_PORTABLE, IMPORT_PORTABLE }

    static final class State {
        final boolean available;
        final boolean busy;
        final Nintendo3DsMiiDataManager.Inspection inspection;
        final boolean hasBackup;
        final long backupDate;
        final long messageId;
        final int messageResource;
        final Object[] messageArgs;

        State(boolean available, boolean busy, Nintendo3DsMiiDataManager.Inspection inspection,
                boolean hasBackup, long backupDate, long messageId, int messageResource, Object[] messageArgs) {
            this.available = available;
            this.busy = busy;
            this.inspection = inspection;
            this.hasBackup = hasBackup;
            this.backupDate = backupDate;
            this.messageId = messageId;
            this.messageResource = messageResource;
            this.messageArgs = messageArgs.clone();
        }

        State busyCopy() {
            return new State(available, true, inspection, hasBackup, backupDate,
                    messageId, messageResource, messageArgs);
        }
    }

    // Retained owners are per hub. The lock also protects completion after a
    // finished hub followed by a newly opened hub, as in the existing contract.
    private static final Object DATA_OPERATION_LOCK = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cleared = new AtomicBoolean();
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(false, true, null, false, 0, 0, 0, new Object[0]));
    private Nintendo3DsStorageLayout layout;
    private Nintendo3DsMiiDataManager mii;
    private long messageSequence;
    private long consumedMessage;

    public Nintendo3DsDataOperationsModel(Application application) {
        super(application);
        worker.execute(() -> {
            State initial;
            synchronized (DATA_OPERATION_LOCK) {
                try {
                    layout = Nintendo3DsStorageLayout.open(application.getFilesDir(), application.getCacheDir());
                    mii = new Nintendo3DsMiiDataManager(layout);
                    initial = inspect(0, 0, new Object[0]);
                } catch (IOException | RuntimeException failure) {
                    initial = unavailable(0, 0, new Object[0]);
                }
            }
            publish(initial);
        });
    }

    LiveData<State> state() { return state; }

    boolean isAvailableAndIdle() {
        State current = state.getValue();
        return !cleared.get() && current != null && current.available && !current.busy;
    }

    boolean consumeMessage(long id) {
        requireMainThread();
        if (id == 0 || id <= consumedMessage) return false;
        consumedMessage = id;
        return true;
    }

    boolean start(Operation operation, Uri document) {
        requireMainThread();
        if (!isAvailableAndIdle()) return false;
        if ((operation == Operation.IMPORT_MII || operation == Operation.EXPORT_PORTABLE
                || operation == Operation.IMPORT_PORTABLE) && document == null) return false;
        state.setValue(state.getValue().busyCopy());
        long resultId = ++messageSequence;
        worker.execute(() -> {
            int message = R.string.n3ds_settings_hub_operation_failed;
            Object[] args = new Object[0];
            State completed;
            synchronized (DATA_OPERATION_LOCK) {
                try {
                    Result result = perform(operation, document);
                    message = result.resource;
                    args = result.args;
                } catch (IOException | RuntimeException failure) {
                    // Storage managers retain their transactional failure policy.
                    // Report failure; never silently replay an accepted mutation.
                }
                try {
                    completed = inspect(resultId, message, args);
                } catch (IOException | RuntimeException failure) {
                    completed = unavailable(resultId, message, args);
                }
            }
            publish(completed);
        });
        return true;
    }

    private Result perform(Operation operation, Uri document) throws IOException {
        // Hold exclusion even while a document provider blocks opening a stream.
        // A finished hub may drain accepted work, but cannot race a new game.
        try (var access = Nintendo3DsDataAccessGuard.beginData()) {
            return performWhileOwned(operation, document);
        }
    }

    private Result performWhileOwned(Operation operation, Uri document) throws IOException {
        Application app = getApplication();
        String revision = Nintendo3DsStorageLayout.CURRENT_CORE_REVISION;
        switch (operation) {
            case IMPORT_MII: {
                var report = mii.importFromSafAfterCoreClosed(app.getContentResolver(), document);
                layout.checkpointAfterCoreClosed(revision);
                return new Result(R.string.n3ds_settings_hub_mii_imported, report.getVisibleMiiCount());
            }
            case CREATE_CHECKPOINT: {
                var report = layout.checkpointAfterCoreClosed(revision);
                return new Result(R.string.n3ds_settings_hub_backup_created, report.getFileCount(),
                        Formatter.formatShortFileSize(app, report.getLogicalBytes()));
            }
            case RESTORE_CHECKPOINT: {
                var report = layout.restoreLatestAfterCoreClosed(revision);
                return new Result(R.string.n3ds_settings_hub_backup_restored, report.getFileCount(),
                        Formatter.formatShortFileSize(app, report.getRestoredBytes()));
            }
            case EXPORT_PORTABLE: {
                try (OutputStream destination = app.getContentResolver().openOutputStream(document, "w")) {
                    if (destination == null) throw new IOException("The selected document is unavailable");
                    var report = layout.exportPortableDataAfterCoreClosed(revision, destination);
                    return new Result(R.string.n3ds_settings_hub_portable_exported, report.getFileCount(),
                            Formatter.formatShortFileSize(app, report.getLogicalBytes()));
                }
            }
            case IMPORT_PORTABLE: {
                try (InputStream source = app.getContentResolver().openInputStream(document)) {
                    if (source == null) throw new IOException("The selected document is unavailable");
                    var report = layout.importPortableDataAfterCoreClosed(revision, source);
                    return new Result(R.string.n3ds_settings_hub_portable_imported, report.getFileCount(),
                            Formatter.formatShortFileSize(app, report.getRestoredBytes()));
                }
            }
            default: throw new IllegalArgumentException("Unsupported 3DS data operation");
        }
    }

    private State inspect(long id, int message, Object[] args) throws IOException {
        var inspection = mii.inspect();
        File manifest = layout.getLatestManifest();
        boolean exists = manifest.isFile();
        return new State(true, false, inspection, exists, exists ? manifest.lastModified() : 0L,
                id, message, args);
    }

    private static State unavailable(long id, int message, Object[] args) {
        return new State(false, false, null, false, 0L, id, message, args);
    }

    private void publish(State value) {
        main.post(() -> { if (!cleared.get()) state.setValue(value); });
    }

    @Override protected void onCleared() {
        cleared.set(true);
        // Graceful drain: completion holds only Application/model, never an old
        // Activity. Recreation does not clear the retained ViewModel at all.
        worker.shutdown();
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("3DS data actions must start on the main thread");
        }
    }

    private static final class Result {
        final int resource;
        final Object[] args;
        Result(int resource, Object... args) { this.resource = resource; this.args = args; }
    }
}
