// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import com.mateussouza.emuorbit.advance.data.save.sync.SaveSyncRuntime;

/** Process-local exclusion of native writers and explicit durable-data work.
 * Claims never wait for I/O or a UI thread. A busy resource is an explicit error,
 * not permission to import/restore concurrently. No file formats or JNI change. */
final class Nintendo3DsDataAccessGuard {
    private static Thread dataOwner;
    private static int dataDepth;
    private static boolean nativeOwned;
    private static SaveSyncRuntime.DedicatedDataMutationLease dataMutation;

    private Nintendo3DsDataAccessGuard() { }

    static synchronized Lease beginData() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("3DS data operation interrupted");
        if (nativeOwned || (dataOwner != null && dataOwner != Thread.currentThread())) {
            throw new IOException("O armazenamento 3DS está em uso. Termine a operação atual e tente novamente.");
        }
        Lease candidate = new Lease(false, Thread.currentThread(), null);
        // The feature admission is authoritative for actual core unload.
        // Bridge its explicit data operations into background capture epochs;
        // retain one global lease across same-thread nested calls.
        if (dataDepth == 0) dataMutation = SaveSyncRuntime.beginDedicatedDataMutation();
        dataOwner = Thread.currentThread();
        dataDepth++;
        return candidate;
    }

    static synchronized Lease beginNative() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("3DS core start interrupted");
        if (nativeOwned || dataDepth != 0) {
            throw new IOException("O armazenamento 3DS está em uso. Termine a operação atual e tente novamente.");
        }
        Lease candidate = new Lease(true, Thread.currentThread(), SaveSyncRuntime.beginNativeEmulation());
        nativeOwned = true;
        return candidate;
    }
    /** Background capture must NOT nest into an import/checkpoint nor reuse an
     * old global quiescent ticket invalidated by its own data admission. */
    static synchronized Lease beginCapture() throws IOException {
        if (dataDepth != 0) throw new IOException("3DS durable data operation is already active");
        Lease lease = beginData();
        try { lease.checkCapture();return lease; }
        catch (IOException failure) { lease.close();throw failure; }
    }

    static final class Lease implements AutoCloseable {
        private final boolean nativeLease;
        private final Thread owner;
        private final SaveSyncRuntime.EmulationLease runtimeNative;
        private boolean released;

        private Lease(boolean nativeLease, Thread owner, SaveSyncRuntime.EmulationLease runtimeNative) {
            this.nativeLease = nativeLease;
            this.owner = owner;
            this.runtimeNative = runtimeNative;
        }
        void checkCapture() throws IOException {
            synchronized (Nintendo3DsDataAccessGuard.class) {
                if (released || nativeLease || nativeOwned || dataDepth != 1
                        || owner != Thread.currentThread() || dataOwner != owner)
                    throw new IOException("3DS capture lost its feature data owner");
                dataMutation.checkStoppedSaveSync();
            }
        }
        void checkNativeOwner() throws IOException {
            synchronized (Nintendo3DsDataAccessGuard.class) {
                if (released || !nativeLease || !nativeOwned || dataDepth != 0
                        || owner != Thread.currentThread() || Thread.currentThread().isInterrupted())
                    throw new IOException("3DS recovery lost its actual pre-frame core owner");
            }
        }
        void publishCapture(SaveSyncRuntime.Publication publication) throws IOException {
            synchronized (Nintendo3DsDataAccessGuard.class) {
                checkCapture();dataMutation.publishStoppedSaveSync(publication);
            }
        }

        @Override public void close() {
            synchronized (Nintendo3DsDataAccessGuard.class) {
                if (released) return;
                if (owner != Thread.currentThread()) {
                    throw new IllegalStateException("A exclusão 3DS deve ser liberada pela thread proprietária.");
                }
                if (nativeLease) { runtimeNative.close();nativeOwned = false; }
                else if (--dataDepth == 0) { dataMutation.close();dataMutation = null;dataOwner = null; }
                released = true;
            }
        }
    }
}
