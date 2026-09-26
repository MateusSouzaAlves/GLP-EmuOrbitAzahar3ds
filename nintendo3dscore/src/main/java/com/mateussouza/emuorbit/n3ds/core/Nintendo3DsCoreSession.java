// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Owner-thread lifecycle for one isolated Azahar/Vulkan content session. */
public final class Nintendo3DsCoreSession implements AutoCloseable {
    static {
        System.loadLibrary("emuorbit_n3ds_bootstrap");
    }

    private final Thread ownerThread;
    private long nativeHandle;

    private Nintendo3DsCoreSession(long nativeHandle) {
        ownerThread = Thread.currentThread();
        this.nativeHandle = nativeHandle;
    }

    public static Nintendo3DsCoreSession open(
            Surface surface,
            int width,
            int height,
            String libraryPath,
            String contentPath,
            String systemDirectory,
            String saveDirectory) throws IOException {
        return open(
                surface,
                width,
                height,
                libraryPath,
                contentPath,
                systemDirectory,
                saveDirectory,
                Nintendo3DsScreenLayout.DEFAULT,
                Nintendo3DsPerformanceProfile.BALANCED);
    }

    public static Nintendo3DsCoreSession open(
            Surface surface,
            int width,
            int height,
            String libraryPath,
            String contentPath,
            String systemDirectory,
            String saveDirectory,
            Nintendo3DsScreenLayout screenLayout) throws IOException {
        return open(
                surface,
                width,
                height,
                libraryPath,
                contentPath,
                systemDirectory,
                saveDirectory,
                screenLayout,
                Nintendo3DsPerformanceProfile.BALANCED);
    }

    public static Nintendo3DsCoreSession open(
            Surface surface,
            int width,
            int height,
            String libraryPath,
            String contentPath,
            String systemDirectory,
            String saveDirectory,
            Nintendo3DsScreenLayout screenLayout,
            Nintendo3DsPerformanceProfile performanceProfile) throws IOException {
        Surface checkedSurface = Objects.requireNonNull(surface);
        if (!checkedSurface.isValid()) {
            throw new IOException("A Surface Android não está válida.");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("As dimensões 3DS devem ser positivas.");
        }
        long handle = o(
                checkedSurface,
                width,
                height,
                Objects.requireNonNull(libraryPath),
                Objects.requireNonNull(contentPath),
                Objects.requireNonNull(systemDirectory),
                Objects.requireNonNull(saveDirectory),
                Objects.requireNonNull(screenLayout).getCoreValue(),
                screenLayout.getCoreSwapValue(),
                Objects.requireNonNull(performanceProfile).getCoreValue());
        if (handle == 0) {
            throw new IOException("A sessão Vulkan 3DS não pôde ser criada.");
        }
        return new Nintendo3DsCoreSession(handle);
    }

    public Nintendo3DsCoreFrameReport runFrame() throws IOException {
        requireOwnerThread();
        requireOpen();
        return new Nintendo3DsCoreFrameReport(r(nativeHandle));
    }

    long saveRecoveryState(String destinationPath, long maximumBytes) throws IOException {
        requireOwnerThread();
        requireOpen();
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("O limite do estado de recuperação 3DS deve ser positivo.");
        }
        return s(nativeHandle, Objects.requireNonNull(destinationPath), maximumBytes);
    }

    long restoreRecoveryState(String sourcePath, long maximumBytes) throws IOException {
        requireOwnerThread();
        requireOpen();
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("O limite do estado de recuperação 3DS deve ser positivo.");
        }
        return l(nativeHandle, Objects.requireNonNull(sourcePath), maximumBytes);
    }

    public int drainAudio(ByteBuffer target, int capacityFrames) {
        requireOwnerThread();
        requireOpen();
        ByteBuffer checkedTarget = Objects.requireNonNull(target);
        if (!checkedTarget.isDirect()
                || capacityFrames <= 0
                || checkedTarget.capacity() < capacityFrames * 4L) {
            throw new IllegalArgumentException(
                    "O áudio 3DS requer um ByteBuffer direto com capacidade estéreo suficiente.");
        }
        checkedTarget.clear();
        checkedTarget.order(ByteOrder.nativeOrder());
        int frames = d(nativeHandle, checkedTarget, capacityFrames);
        checkedTarget.limit(frames * 4);
        return frames;
    }

    void updateInput(Nintendo3DsInputState.Snapshot input) {
        requireOwnerThread();
        requireOpen();
        Nintendo3DsInputState.Snapshot checkedInput = Objects.requireNonNull(input);
        u(
                nativeHandle,
                checkedInput.getButtonMask(),
                checkedInput.getCirclePadX(),
                checkedInput.getCirclePadY(),
                checkedInput.getCStickX(),
                checkedInput.getCStickY(),
                checkedInput.getPointerX(),
                checkedInput.getPointerY(),
                checkedInput.isPointerPressed(),
                checkedInput.getAccelerometerX(),
                checkedInput.getAccelerometerY(),
                checkedInput.getAccelerometerZ(),
                checkedInput.getGyroscopeX(),
                checkedInput.getGyroscopeY(),
                checkedInput.getGyroscopeZ());
    }

    void enqueueMicrophoneSamples(short[] samples, int count) {
        requireOwnerThread();
        requireOpen();
        short[] checkedSamples = Objects.requireNonNull(samples);
        if (count <= 0 || count > checkedSamples.length) {
            throw new IllegalArgumentException("A quantidade de amostras do microfone 3DS é inválida.");
        }
        m(nativeHandle, checkedSamples, count);
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (nativeHandle != 0) {
            long handle = nativeHandle;
            nativeHandle = 0;
            x(handle);
        }
    }

    private void requireOwnerThread() {
        if (ownerThread != Thread.currentThread()) {
            throw new IllegalStateException(
                    "A sessão 3DS deve permanecer na thread proprietária.");
        }
    }

    private void requireOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("A sessão 3DS está fechada.");
        }
    }

    private static native long o(
            Surface surface,
            int width,
            int height,
            String libraryPath,
            String contentPath,
            String systemDirectory,
            String saveDirectory,
            String screenLayout,
            String swapScreen,
            String performanceProfile) throws IOException;

    private static native String[] r(long handle) throws IOException;

    private static native long s(long handle, String destinationPath, long maximumBytes)
            throws IOException;

    private static native long l(long handle, String sourcePath, long maximumBytes)
            throws IOException;

    private static native int d(long handle, ByteBuffer target, int capacityFrames);

    private static native void u(
            long handle,
            int buttonMask,
            int circlePadX,
            int circlePadY,
            int cStickX,
            int cStickY,
            int pointerX,
            int pointerY,
            boolean pointerPressed,
            float accelerometerX,
            float accelerometerY,
            float accelerometerZ,
            float gyroscopeX,
            float gyroscopeY,
            float gyroscopeZ);

    private static native void m(long handle, short[] samples, int count);

    private static native void x(long handle);
}
