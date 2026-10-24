// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/** FIFO lifecycle work; never waits/cancels JNI. A failure gates frames, not final cleanup. */
final class Nintendo3DsTransitionQueue {
    interface Work { void run() throws Exception; }
    interface Completion { void complete(Throwable failure); }
    private final ExecutorService worker;
    private final Executor callbacks;
    private int pending;
    private boolean closing;
    private Throwable failure;

    Nintendo3DsTransitionQueue(ExecutorService worker, Executor callbacks) {
        this.worker = Objects.requireNonNull(worker);
        this.callbacks = Objects.requireNonNull(callbacks);
    }

    synchronized boolean isIdle() { return pending == 0; }
    synchronized Throwable getFailure() { return failure; }

    boolean submit(Work work, Completion completion) {
        Objects.requireNonNull(work);Objects.requireNonNull(completion);
        synchronized (this) {
            if (closing) return false;
            pending++;
            enqueue(work, completion);
            return true;
        }
    }

    /** Preserve all already-admitted work and the final close, including after a timeout. */
    void finish(Work finalClose, Completion completion) {
        Objects.requireNonNull(finalClose);Objects.requireNonNull(completion);
        synchronized (this) {
            if (closing) return;
            closing = true;pending++;
            enqueue(finalClose, completion);
            worker.shutdown(); // Never shutdownNow(): a queued JNI close is not expendable.
        }
    }

    private void enqueue(Work work, Completion completion) {
        try {
            worker.execute(() -> {
                Throwable result = null;
                try { work.run(); }
                catch (Exception | Error rejected) { result = rejected; }
                synchronized (this) {
                    if (result != null && failure == null) failure = result;
                    pending--;
                }
                complete(completion, result);
            });
        } catch (RuntimeException rejected) {
            pending--;
            if (failure == null) failure = rejected;
            complete(completion, rejected);
        }
    }

    private void complete(Completion completion, Throwable result) {
        try { callbacks.execute(() -> completion.complete(result)); }
        catch (RuntimeException rejected) {
            synchronized (this) { if (failure == null) failure = rejected; }
        }
    }
}
