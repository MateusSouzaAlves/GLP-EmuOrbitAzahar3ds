// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.view.Surface;

import java.io.IOException;
import java.util.Objects;

/** Owner-thread host for the isolated Nintendo 3DS Vulkan rendering path. */
public final class Nintendo3DsHardwareRenderHost implements AutoCloseable {
    static {
        System.loadLibrary("emuorbit_n3ds_bootstrap");
    }

    private final Thread ownerThread;
    private long nativeHandle;

    private Nintendo3DsHardwareRenderHost(long nativeHandle) {
        ownerThread = Thread.currentThread();
        this.nativeHandle = nativeHandle;
    }

    public static Nintendo3DsHardwareRenderHost create(
            Surface surface,
            int width,
            int height) throws IOException {
        Surface checkedSurface = Objects.requireNonNull(surface);
        if (!checkedSurface.isValid()) {
            throw new IOException("A Surface Android não está válida.");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("As dimensões Vulkan devem ser positivas.");
        }
        long handle = c(checkedSurface, width, height);
        if (handle == 0) {
            throw new IOException("O host Vulkan 3DS não pôde ser criado.");
        }
        return new Nintendo3DsHardwareRenderHost(handle);
    }

    public Nintendo3DsHardwareRenderReport presentDiagnosticFrame(int rgba)
            throws IOException {
        requireOwnerThread();
        requireOpen();
        return new Nintendo3DsHardwareRenderReport(p(nativeHandle, rgba));
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (nativeHandle != 0) {
            long handle = nativeHandle;
            nativeHandle = 0;
            d(handle);
        }
    }

    private void requireOwnerThread() {
        if (ownerThread != Thread.currentThread()) {
            throw new IllegalStateException(
                    "O host Vulkan 3DS deve permanecer na thread proprietária.");
        }
    }

    private void requireOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("O host Vulkan 3DS está fechado.");
        }
    }

    private static native long c(Surface surface, int width, int height) throws IOException;

    private static native String[] p(long handle, int rgba) throws IOException;

    private static native void d(long handle);
}
