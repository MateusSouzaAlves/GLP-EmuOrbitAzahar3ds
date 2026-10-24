// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import com.mateussouza.emuorbit.advance.data.save.protection.v2.CommonSaveResumeFenceFile;
import com.mateussouza.emuorbit.advance.data.save.protection.v2.SaveResumeFence;
import com.mateussouza.emuorbit.advance.data.storage.Nintendo3DsSaveDescriptor;
import android.view.Surface;
import com.mateussouza.emuorbit.advance.data.storage.Nintendo3DsLoadedSaveMapping;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Serializes the Android Surface and process lifecycle onto the core's owner thread.
 *
 * <p>The caller retains ownership of each {@link Surface}. A pause drains pending owner-thread
 * work and releases active Android producers. If Android destroys or replaces the Surface, the
 * native session is closed safely before the driver target disappears.
 */
public final class Nintendo3DsCoreLifecycleController implements AutoCloseable {
    public static final long DEFAULT_TRANSITION_TIMEOUT_MS = 30_000;
    static final long MAX_RECOVERY_STATE_BYTES = 128L * 1024L * 1024L;

    private final Object stateLock = new Object();
    private final Object producerLock = new Object();
    private final Context appContext;
    private final String libraryPath;
    private final String contentPath;
    private final String systemDirectory;
    private final String saveDirectory;
    private final Nintendo3DsStorageLayout storageLayout;
    private final String coreRevision;
    private final String saveMappingLibraryId;
    private final Runnable afterNativeClosed;
    private final File recoveryStateFile;
    private final ExecutorService coreExecutor;
    private final com.mateussouza.emuorbit.advance.data.save.sync.SaveSyncRuntime.ForegroundPreparationLease preparationLease;
    private final Nintendo3DsAudioOutput audioOutput = new Nintendo3DsAudioOutput();
    private final Nintendo3DsInputState inputState = new Nintendo3DsInputState();
    private final Nintendo3DsAndroidMotionSource motionSource;
    private final Nintendo3DsAndroidMicrophoneSource microphoneSource;
    private final Nintendo3DsPerformanceCollector performanceCollector =
            new Nintendo3DsPerformanceCollector();
    private final Nintendo3DsRegenerableCachePolicy regenerableCachePolicy;
    private final AtomicLong openedSessionCount = new AtomicLong();
    private final AtomicLong closedSessionCount = new AtomicLong();
    private final AtomicLong capturedRecoveryStateCount = new AtomicLong();
    private final AtomicLong restoredRecoveryStateCount = new AtomicLong();
    private volatile Nintendo3DsStorageLayout.CheckpointReport lastStorageCheckpoint;
    private volatile String lastRecoveryStateFailure;
    private volatile String lastSaveMappingFailure;
    private volatile RecoveryException recoveryFailure;

    // Guarded by stateLock.
    private Surface surface;
    private Nintendo3DsSurfaceLifetime surfaceLifetime;
    private int surfaceWidth;
    private int surfaceHeight;
    private long surfaceGeneration;
    private boolean resumed;
    private boolean closed;
    private boolean microphoneRequested;
    private Nintendo3DsScreenLayout screenLayout = Nintendo3DsScreenLayout.DEFAULT;
    private Nintendo3DsPerformanceProfile performanceProfile =
            Nintendo3DsPerformanceProfile.BALANCED;
    private float fastForwardSpeed = 1.0f;

    // Accessed only by coreExecutor's single owner thread.
    private Nintendo3DsCoreSession coreSession;
    private Nintendo3DsSurfaceLifetime.Lease coreSessionSurfaceLease;
    private boolean backupCloseNotified;
    private long coreSessionSurfaceGeneration = -1;
    private boolean recoveryStateCapturedForSession;
    private boolean recoveryStateRequired;
    private SaveResumeFence explicitRestoreFence;
    private SaveResumeFence.Token preparedRestoreFence;
    private final Nintendo3DsFramePacer framePacer = Nintendo3DsFramePacer.createDefault();
    private final short[] microphoneTransfer = new short[4_096];

    public Nintendo3DsCoreLifecycleController(
            String libraryPath,
            String contentPath,
            String systemDirectory,
            String saveDirectory) {
        this(
                null,
                libraryPath,
                contentPath,
                systemDirectory,
                saveDirectory,
                null,
                null,
                null,
                null);
    }

    /** Creates a controller with lifecycle-bound Android accelerometer and gyroscope input. */
    public Nintendo3DsCoreLifecycleController(
            Context context,
            String libraryPath,
            String contentPath,
            String systemDirectory,
            String saveDirectory) {
        this(
                context,
                libraryPath,
                contentPath,
                systemDirectory,
                saveDirectory,
                null,
                null,
                null,
                null);
    }

    /** Creates a controller whose closed sessions receive incremental directory checkpoints. */
    public Nintendo3DsCoreLifecycleController(
            Context context,
            String libraryPath,
            String contentPath,
            Nintendo3DsStorageLayout storageLayout,
            String coreRevision) {
        this(context, libraryPath, contentPath, storageLayout, coreRevision, null);
    }

    /** The persistent library identity is unchanged; only product launches
     * with that identity produce a local observed-save mapping journal. */
    public Nintendo3DsCoreLifecycleController(
            Context context, String libraryPath, String contentPath,
            Nintendo3DsStorageLayout storageLayout, String coreRevision,
            String saveMappingLibraryId) {
        this(context, libraryPath, contentPath, storageLayout, coreRevision, saveMappingLibraryId, null);
    }

    /** Optional stopped-native notification runs on the owner, after confirmed
     * JNI unload and preparation release, even when the caller timed out. It
     * only schedules work; a worker must independently acquire stopped data. */
    public Nintendo3DsCoreLifecycleController(
            Context context, String libraryPath, String contentPath,
            Nintendo3DsStorageLayout storageLayout, String coreRevision,
            String saveMappingLibraryId, Runnable afterNativeClosed) {
        this(
                context,
                libraryPath,
                contentPath,
                Objects.requireNonNull(storageLayout).getSystemDirectory().getAbsolutePath(),
                storageLayout.getSaveDirectory().getAbsolutePath(),
                storageLayout,
                Objects.requireNonNull(coreRevision),
                saveMappingLibraryId,
                afterNativeClosed);
    }

    private Nintendo3DsCoreLifecycleController(
            Context context,
            String libraryPath,
            String contentPath,
            String systemDirectory,
            String saveDirectory,
            Nintendo3DsStorageLayout storageLayout,
            String coreRevision,
            String saveMappingLibraryId,
            Runnable afterNativeClosed) {
        appContext = context == null ? null : context.getApplicationContext();
        this.libraryPath = Objects.requireNonNull(libraryPath);
        this.contentPath = Objects.requireNonNull(contentPath);
        this.systemDirectory = Objects.requireNonNull(systemDirectory);
        this.saveDirectory = Objects.requireNonNull(saveDirectory);
        this.storageLayout = storageLayout;
        this.coreRevision = coreRevision;
        this.saveMappingLibraryId = saveMappingLibraryId;
        this.afterNativeClosed = afterNativeClosed == null ? this::enqueueBackupAfterNativeClosed : afterNativeClosed;
        recoveryStateFile = storageLayout == null
                ? null
                : recoveryStateFile(storageLayout, contentPath, coreRevision);
        recoveryStateRequired = recoveryStateFile != null && recoveryStateFile.exists();
        regenerableCachePolicy = storageLayout == null
                ? null
                : new Nintendo3DsRegenerableCachePolicy(storageLayout);
        motionSource = context == null
                ? null
                : new Nintendo3DsAndroidMotionSource(context, inputState);
        microphoneSource = context == null
                ? null
                : new Nintendo3DsAndroidMicrophoneSource(context);
        coreExecutor = Executors.newSingleThreadExecutor(new CoreThreadFactory());
        preparationLease = context == null ? null
                : com.mateussouza.emuorbit.advance.data.save.sync.SaveSyncRuntime.beginForegroundPreparation();
    }

    /* Marks the host resumed. The native session remains lazy until frames are requested. */
    public void onResume() {
        synchronized (producerLock) {
            boolean startMicrophone;
            synchronized (stateLock) {
                requireControllerOpen();
                resumed = true;
                startMicrophone = microphoneRequested;
            }
            if (motionSource != null) {
                motionSource.start();
            }
            if (startMicrophone && microphoneSource != null) {
                microphoneSource.start();
            }
        }
    }

    /** Admission only: never joins producers or waits for JNI on the UI thread. */
    void requestPause() {
        synchronized (stateLock) { resumed = false; inputState.clear(); }
    }

    /* Suspends producers and drains frames without unloading the title or native core. */
    public void onPauseAndAwait() throws IOException {
        onPauseAndAwait(DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public void onPauseAndAwait(long timeoutMs) throws IOException {
        Future<Void> transition;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            resumed = false;
            inputState.clear();
            transition = submitCaptureRecoveryState();
        }
        stopAndroidProducers();
        await(transition, timeoutMs, "pausa");
    }

    /* Replaces the render target after safely closing any session tied to the old Surface. */
    public void onSurfaceAvailable(Surface nextSurface, int width, int height) throws IOException {
        onSurfaceAvailable(nextSurface, width, height, DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public void onSurfaceAvailable(
            Surface nextSurface,
            int width,
            int height,
            long timeoutMs) throws IOException {
        bindSurface(nextSurface, width, height, null, timeoutMs);
    }

    /** Only app-owned targets may outlive the framework surfaceDestroyed callback. */
    void onOwnedSurfaceAvailable(Surface nextSurface, int width, int height,
            Nintendo3DsSurfaceLifetime lifetime) throws IOException {
        bindSurface(nextSurface, width, height, Objects.requireNonNull(lifetime), DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    private void bindSurface(Surface nextSurface, int width, int height,
            Nintendo3DsSurfaceLifetime lifetime, long timeoutMs) throws IOException {
        Surface checkedSurface = Objects.requireNonNull(nextSurface);
        if (!checkedSurface.isValid()) {
            throw new IOException("A Surface 3DS recebida não está válida.");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("As dimensões da Surface 3DS devem ser positivas.");
        }

        Future<Void> transition;
        synchronized (stateLock) {
            requireControllerOpen();
            if (surface == checkedSurface
                    && surfaceWidth == width
                    && surfaceHeight == height && surfaceLifetime == lifetime) {
                return;
            }
            surface = checkedSurface;
            surfaceLifetime = lifetime;
            surfaceWidth = width;
            surfaceHeight = height;
            surfaceGeneration++;
            inputState.clear();
            transition = submitCloseSessionPreservingRecovery();
        }
        audioOutput.releaseForTransition();
        await(transition, timeoutMs, "substituição da Surface");
    }

    /* Closes only the matching Surface, protecting a new target from a stale callback. */
    public void onSurfaceDestroyedAndAwait(Surface destroyedSurface) throws IOException {
        onSurfaceDestroyedAndAwait(destroyedSurface, DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public void onSurfaceDestroyedAndAwait(Surface destroyedSurface, long timeoutMs)
            throws IOException {
        Objects.requireNonNull(destroyedSurface);
        Future<Void> transition;
        synchronized (stateLock) {
            if (closed || surface != destroyedSurface) {
                return;
            }
            surface = null;
            surfaceLifetime = null;
            surfaceWidth = 0;
            surfaceHeight = 0;
            surfaceGeneration++;
            inputState.clear();
            transition = submitCloseSessionPreservingRecovery();
        }
        audioOutput.releaseForTransition();
        await(transition, timeoutMs, "perda da Surface");
    }

    /* Runs a bounded frame batch without ever moving the native session off its owner thread. */
    public Nintendo3DsCoreFrameReport runFrames(int frameCount) throws IOException {
        return runFrames(frameCount, DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public Nintendo3DsCoreFrameReport runFrames(int frameCount, long timeoutMs) throws IOException {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("A quantidade de frames 3DS deve ser positiva.");
        }

        SessionRequest request;
        synchronized (stateLock) {
            requireControllerOpen();
            if (!resumed) {
                throw new IOException("A sessão 3DS não pode avançar enquanto o host está pausado.");
            }
            if (surface == null || !surface.isValid()) {
                throw new IOException("A sessão 3DS aguarda uma Surface válida.");
            }
            request = new SessionRequest(
                    surface,
                    surfaceWidth,
                    surfaceHeight,
                    surfaceGeneration,
                    screenLayout,
                    performanceProfile,
                    surfaceLifetime);
        }

        Future<Nintendo3DsCoreFrameReport> frames = coreExecutor.submit(() -> {
            ensureRequestStillCurrent(request);
            ensureCoreSession(request);
            Nintendo3DsCoreFrameReport report = null;
            try {
                for (int frame = 0; frame < frameCount; frame++) {
                    // A lifecycle callback can invalidate a long batch between frames.
                    ensureRequestStillCurrent(request);
                    long frameStartedNanos = System.nanoTime();
                    drainMicrophoneOnOwnerThread();
                    coreSession.updateInput(inputState.snapshot());
                    report = coreSession.runFrame();
                    recoveryStateCapturedForSession = false;
                    audioOutput.consume(
                            coreSession,
                            report.getAudioSampleRate(),
                            report.getAudioQueuedFrames());
                    performanceCollector.recordFrame(
                            Math.max(1L, System.nanoTime() - frameStartedNanos),
                            report);
                    paceFrame(
                            request,
                            frameStartedNanos,
                            report.getNominalFramesPerSecond());
                }
                return Objects.requireNonNull(report);
            } catch (SessionInvalidatedException expectedTransition) {
                throw expectedTransition;
            } catch (IOException | RuntimeException exception) {
                try {
                    closeCoreSessionAndCheckpointOnOwnerThread();
                } catch (IOException checkpointFailure) {
                    exception.addSuppressed(checkpointFailure);
                }
                throw exception;
            }
        });
        return await(frames, timeoutMs, "execução de frames");
    }

    public boolean isReadyForFrames() {
        synchronized (stateLock) {
            return !closed && resumed && surface != null && surface.isValid();
        }
    }

    public boolean isClosed() {
        synchronized (stateLock) {
            return closed;
        }
    }

    public long getOpenedSessionCount() {
        return openedSessionCount.get();
    }

    public long getClosedSessionCount() {
        return closedSessionCount.get();
    }

    public long getCapturedRecoveryStateCount() {
        return capturedRecoveryStateCount.get();
    }

    public long getRestoredRecoveryStateCount() {
        return restoredRecoveryStateCount.get();
    }

    public String getLastRecoveryStateFailure() {
        return lastRecoveryStateFailure;
    }

    /** Allows a paused host to surface terminal recovery failure without requesting a frame. */
    public RecoveryException getRecoveryFailure() {
        return recoveryFailure;
    }

    public Nintendo3DsStorageLayout.CheckpointReport getLastStorageCheckpoint() {
        return lastStorageCheckpoint;
    }

    /** Returns aggregate-only performance and cache evidence without game identity or paths. */
    public Nintendo3DsPerformanceSnapshot getPerformanceSnapshot() throws IOException {
        Nintendo3DsPerformanceProfile profile;
        synchronized (stateLock) {
            requireControllerOpen();
            profile = performanceProfile;
        }
        Nintendo3DsRegenerableCachePolicy.Report cacheReport = regenerableCachePolicy == null
                ? Nintendo3DsRegenerableCachePolicy.Report.empty()
                : regenerableCachePolicy.inspect();
        return performanceCollector.snapshot(
                profile,
                Nintendo3DsDevicePerformanceState.capture(appContext),
                cacheReport);
    }

    /** Starts a new measurement window between frame batches without recreating the core. */
    public void resetPerformanceMeasurementsAndAwait() throws IOException {
        resetPerformanceMeasurementsAndAwait(DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public void resetPerformanceMeasurementsAndAwait(long timeoutMs) throws IOException {
        Future<Void> reset;
        synchronized (stateLock) {
            requireControllerOpen();
            reset = coreExecutor.submit(() -> {
                performanceCollector.reset();
                return null;
            });
        }
        await(reset, timeoutMs, "reinício da medição de desempenho");
    }

    /**
     * Checkpoints and closes the current native session on its owner thread.
     * The current Surface, lifecycle and product settings remain attached, so
     * the next requested frame starts the title again from a fresh core session.
     */
    public void restartSessionAndAwait() throws IOException {
        restartSessionAndAwait(DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public void restartSessionAndAwait(long timeoutMs) throws IOException {
        Future<Void> transition;
        synchronized (stateLock) {
            requireControllerOpen();
            surfaceGeneration++;
            inputState.clear();
            transition = coreExecutor.submit(() -> {
                closeCoreSessionAndCheckpointOnOwnerThread();
                // A confirmed restart skips recovery for the next native open,
                // but does not delete the user's previous checkpoint. A later
                // successful normal capture may replace it atomically as usual.
                recoveryStateRequired = false;
                recoveryFailure = null;
                lastRecoveryStateFailure = null;
                return null;
            });
        }
        audioOutput.releaseForTransition();
        await(transition, timeoutMs, "reinício da sessão");
        performanceCollector.reset();
    }

    /**
     * Closes and checkpoints the core on its owner thread, then clears only regenerable trees.
     * The Surface and resumed state remain intact, so the next frame lazily reopens the session.
     */
    public Nintendo3DsRegenerableCachePolicy.Report clearRegenerableCachesAndAwait()
            throws IOException {
        return clearRegenerableCachesAndAwait(DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public Nintendo3DsRegenerableCachePolicy.Report clearRegenerableCachesAndAwait(
            long timeoutMs) throws IOException {
        if (regenerableCachePolicy == null) {
            throw new IllegalStateException(
                    "A limpeza de cache 3DS exige armazenamento privado configurado.");
        }
        Future<Nintendo3DsRegenerableCachePolicy.Report> transition;
        synchronized (stateLock) {
            requireControllerOpen();
            surfaceGeneration++;
            inputState.clear();
            transition = coreExecutor.submit(() -> {
                closeCoreSessionAndCheckpointOnOwnerThread();
                Nintendo3DsRegenerableCachePolicy.Report report =
                        regenerableCachePolicy.clearAfterCoreClosed();
                recoveryStateCapturedForSession = false;
                recoveryStateRequired = false;
                recoveryFailure = null;
                lastRecoveryStateFailure = null;
                return report;
            });
        }
        audioOutput.releaseForTransition();
        return await(transition, timeoutMs, "limpeza do cache regenerável");
    }

    /* Audio stays opt-in until the experimental product host explicitly enables it. */
    public void setAudioEnabled(boolean enabled) {
        synchronized (stateLock) {
            requireControllerOpen();
            audioOutput.setEnabled(enabled);
        }
    }

    public void setAudioVolume(float volume) {
        synchronized (stateLock) {
            requireControllerOpen();
            audioOutput.setVolume(volume);
        }
    }

    public void setFastForwardSpeed(float speed) {
        synchronized (stateLock) {
            requireControllerOpen();
            audioOutput.setFastForwardSpeed(speed);
            fastForwardSpeed = speed;
        }
    }

    public void setButtonPressed(Nintendo3DsButton button, boolean pressed) {
        synchronized (stateLock) {
            requireControllerOpen();
            inputState.setButtonPressed(Objects.requireNonNull(button), pressed);
        }
    }

    public void setCirclePad(float horizontal, float vertical) {
        synchronized (stateLock) {
            requireControllerOpen();
            inputState.setCirclePad(horizontal, vertical);
        }
    }

    public void setCStick(float horizontal, float vertical) {
        synchronized (stateLock) {
            requireControllerOpen();
            inputState.setCStick(horizontal, vertical);
        }
    }

    /** Recreates the render session with an Azahar-supported screen arrangement and recovery. */
    public void setScreenLayout(Nintendo3DsScreenLayout nextLayout) throws IOException {
        setScreenLayout(nextLayout, DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public void setScreenLayout(Nintendo3DsScreenLayout nextLayout, long timeoutMs)
            throws IOException {
        Nintendo3DsScreenLayout checkedLayout = Objects.requireNonNull(nextLayout);
        Future<Void> transition;
        synchronized (stateLock) {
            requireControllerOpen();
            if (screenLayout == checkedLayout) {
                return;
            }
            screenLayout = checkedLayout;
            surfaceGeneration++;
            inputState.clear();
            // The layout affects startup-only renderer options, not the user's
            // decision to reset the game. Keep the same recovery path as Surface replacement.
            transition = submitCloseSessionPreservingRecovery();
        }
        audioOutput.releaseForTransition();
        await(transition, timeoutMs, "troca do layout de telas");
    }

    public Nintendo3DsScreenLayout getScreenLayout() {
        synchronized (stateLock) {
            return screenLayout;
        }
    }

    /** Recreates the render session with recovery so startup-only options change atomically. */
    public void setPerformanceProfile(Nintendo3DsPerformanceProfile nextProfile)
            throws IOException {
        setPerformanceProfile(nextProfile, DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public void setPerformanceProfile(
            Nintendo3DsPerformanceProfile nextProfile,
            long timeoutMs) throws IOException {
        Nintendo3DsPerformanceProfile checkedProfile = Objects.requireNonNull(nextProfile);
        Future<Void> transition;
        synchronized (stateLock) {
            requireControllerOpen();
            if (performanceProfile == checkedProfile) {
                return;
            }
            performanceProfile = checkedProfile;
            surfaceGeneration++;
            inputState.clear();
            transition = submitCloseSessionPreservingRecovery();
        }
        audioOutput.releaseForTransition();
        await(transition, timeoutMs, "troca do perfil de desempenho");
        performanceCollector.reset();
    }

    public Nintendo3DsPerformanceProfile getPerformanceProfile() {
        synchronized (stateLock) {
            return performanceProfile;
        }
    }

    /** Publishes touch coordinates already mapped to the complete frame presented by Azahar. */
    public void setTouchPosition(
            int frameX,
            int frameY,
            int frameWidth,
            int frameHeight,
            boolean pressed) {
        synchronized (stateLock) {
            requireControllerOpen();
            inputState.setTouchPosition(
                    frameX,
                    frameY,
                    frameWidth,
                    frameHeight,
                    pressed);
        }
    }

    /** Maps a Surface coordinate to the current composite core frame before publishing touch. */
    public boolean setTouchPositionFromSurface(
            float surfaceX,
            float surfaceY,
            int surfaceWidth,
            int surfaceHeight,
            int frameWidth,
            int frameHeight,
            boolean pressed) {
        synchronized (stateLock) {
            requireControllerOpen();
            Nintendo3DsTouchMapper.Result mapped = Nintendo3DsTouchMapper.mapToFrame(
                    surfaceX,
                    surfaceY,
                    surfaceWidth,
                    surfaceHeight,
                    frameWidth,
                    frameHeight);
            inputState.setTouchPosition(
                    mapped.isInsideFrame() ? mapped.getFrameX() : 0,
                    mapped.isInsideFrame() ? mapped.getFrameY() : 0,
                    frameWidth,
                    frameHeight,
                    pressed && mapped.isInsideFrame());
            return mapped.isInsideFrame();
        }
    }

    /** Updates the display rotation used by the Azahar-compatible motion axis transform. */
    public void setDisplayRotation(int rotation) {
        synchronized (stateLock) {
            requireControllerOpen();
            if (motionSource == null) {
                throw new IllegalStateException("A fonte Android de movimento 3DS não foi configurada.");
            }
            motionSource.setDisplayRotation(rotation);
        }
    }

    public Nintendo3DsMotionReport getMotionReport() {
        if (motionSource == null) {
            return new Nintendo3DsMotionReport(false, false, false, 0, 0, 0, 0);
        }
        return motionSource.report();
    }

    /*
     * Enables opt-in capture. PERMISSION_REQUIRED tells the UI to request RECORD_AUDIO and call
     * this method again after approval.
     */
    public Nintendo3DsMicrophoneStartResult setMicrophoneEnabled(boolean enabled) {
        synchronized (producerLock) {
            boolean currentlyResumed;
            synchronized (stateLock) {
                requireControllerOpen();
                microphoneRequested = enabled;
                currentlyResumed = resumed;
            }
            if (!enabled) {
                if (microphoneSource != null) {
                    microphoneSource.stop();
                }
                return Nintendo3DsMicrophoneStartResult.DISABLED;
            }
            if (microphoneSource == null) {
                return Nintendo3DsMicrophoneStartResult.UNAVAILABLE;
            }
            if (!currentlyResumed) {
                return Nintendo3DsMicrophoneStartResult.WAITING_FOR_RESUME;
            }
            return microphoneSource.start();
        }
    }

    public Nintendo3DsMicrophoneReport getMicrophoneReport() {
        if (microphoneSource == null) {
            return new Nintendo3DsMicrophoneReport(
                    false, false, false, 0, 0, 0, 0, 0, 0);
        }
        return microphoneSource.report();
    }

    public Nintendo3DsAudioOutputReport getAudioOutputReport() {
        return audioOutput.report();
    }

    @Override
    public void close() throws IOException {
        close(DEFAULT_TRANSITION_TIMEOUT_MS);
    }

    public void close(long timeoutMs) throws IOException {
        Future<Void> transition;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            resumed = false;
            surface = null;
            surfaceLifetime = null;
            surfaceWidth = 0;
            surfaceHeight = 0;
            surfaceGeneration++;
            inputState.clear();
            transition = submitCloseSession();
        }
        stopAndroidProducers();
        try {
            await(transition, timeoutMs, "encerramento");
        } finally {
            // shutdown() preserves the queued owner-thread close even if the caller timed out.
            coreExecutor.shutdown();
        }
    }

    private void stopAndroidProducers() {
        synchronized (producerLock) {
            if (motionSource != null) motionSource.stop();
            if (microphoneSource != null) microphoneSource.stop();
            audioOutput.releaseForTransition();
        }
    }

    private Future<Void> submitCloseSession() {
        return coreExecutor.submit(() -> {
            try {
                closeCoreSessionAndCheckpointOnOwnerThread();
            } finally {
                // A checkpoint exception occurs AFTER actual native unload.
                // Failed unload retains coreSession and its actual native
                // lease; do not count it as closed or admit optional copies.
                if (coreSession == null && preparationLease != null) preparationLease.close();
                // Count only actual CoreSession.close() returns, not isClosed,
                // Activity destruction, a caller timeout or an advisory lease.
                if (coreSession == null && closedSessionCount.get() > 0
                        && closedSessionCount.get() == openedSessionCount.get() && !backupCloseNotified) {
                    backupCloseNotified = true;
                    try { afterNativeClosed.run(); }
                    catch (RuntimeException | LinkageError optional) {
                        // Do not mask a checkpoint exception after actual unload
                        // or report scheduling as a prepared/uploaded save.
                        android.util.Log.w("EmuOrbitSaveProtection", "3DS stopped backup scheduling deferred");
                    }
                }
            }
            return null;
        });
    }

    private void enqueueBackupAfterNativeClosed() {
        // Feature -> base is a direct, R8-remappable call. No string lookup or
        // worker type is added to the isolated self-target dependency closure.
        if (appContext != null && saveMappingLibraryId != null
                && com.mateussouza.emuorbit.advance.data.save.protection.SaveProtectionCoverage.supports(
                        com.mateussouza.emuorbit.advance.domain.model.EmulatorSystem.N3DS))
            com.mateussouza.emuorbit.advance.data.save.protection.v2.Nintendo3DsBackupDispatch.enqueue(appContext);
    }

    private Future<Void> submitCaptureRecoveryState() {
        return coreExecutor.submit(() -> {
            try {
                captureRecoveryStateOnOwnerThread();
            } catch (RecoveryException failure) {
                closeAfterRecoveryFailureOnOwnerThread(failure, true);
                throw failure;
            } finally {
                // A frame already in JNI may have created a track AFTER the caller's
                // early mute. Retire output again on the serial owner after that frame.
                audioOutput.releaseForTransition();
                framePacer.reset();
            }
            return null;
        });
    }

    private Future<Void> submitCloseSessionPreservingRecovery() {
        return coreExecutor.submit(() -> {
            try {
                captureRecoveryStateOnOwnerThread();
            } catch (RecoveryException failure) {
                closeAfterRecoveryFailureOnOwnerThread(failure, true);
                throw failure;
            }
            closeCoreSessionAndCheckpointOnOwnerThread();
            return null;
        });
    }

    private void ensureRequestStillCurrent(SessionRequest request) throws IOException {
        synchronized (stateLock) {
            if (closed
                    || !resumed
                    || surface != request.surface
                    || surfaceGeneration != request.surfaceGeneration) {
                throw new SessionInvalidatedException(
                        "A transição de lifecycle invalidou o frame 3DS pendente.");
            }
        }
        if (!request.surface.isValid()) {
            throw new SessionInvalidatedException(
                    "A Surface 3DS foi invalidada antes da criação do contexto.");
        }
    }

    private void ensureCoreSession(SessionRequest request) throws IOException {
        // Never label a cold replacement as recovery after capture/restore failed.
        if (recoveryFailure != null) {
            throw recoveryFailure;
        }
        if (coreSession != null
                && coreSessionSurfaceGeneration == request.surfaceGeneration) {
            return;
        }
        closeCoreSessionAndCheckpointOnOwnerThread();
        openCoreSession(request);
        restoreRecoveryStateOnOwnerThread(request);
    }

    private void openCoreSession(SessionRequest request) throws IOException {
        // Reconcile durable per-title changes BEFORE PENDING mapping/JNI. The
        // existing OBSERVED mapping must survive a failed typed native open so
        // the current stopped owner can compensate using its original roots.
        Nintendo3DsSaveRestoreOwner restore = Nintendo3DsSaveRestoreOwner.prepare(
                appContext, storageLayout, contentPath, saveMappingLibraryId);
        prepareExplicitRestoreFence();
        // Journal preparation is additive and outside the game's save tree.
        // An unsupported/changed mapping must not break previously playable
        // games. It stays PENDING/unqualified, not silently cloud-supported.
        Nintendo3DsLoadedSaveMapping.Attempt mapping = null;
        lastSaveMappingFailure = null;
        if (restore == null && storageLayout != null && saveMappingLibraryId != null) {
            try {
                Thread nativeOwner = Thread.currentThread();
                mapping = Nintendo3DsLoadedSaveMapping.begin(
                        storageLayout.getSaveDirectory().toPath().getParent().resolve("save-mappings"),
                        saveMappingLibraryId, coreRevision, () -> {
                            if (Thread.currentThread() != nativeOwner)
                                throw new IOException("3DS mapping left its native owner");
                        });
                File image = new File(contentPath);
                mapping.inspectSource(image.toPath(), image.getName(), storageLayout.getUserDirectory().toPath());
            } catch (IOException | RuntimeException unqualified) {
                lastSaveMappingFailure = unqualified.getMessage();mapping = null;
            }
        }
        try {
            if (request.lifetime != null) {
                try { coreSessionSurfaceLease = request.lifetime.borrow(); }
                catch (IllegalStateException retired) {
                    throw new SessionInvalidatedException("O alvo próprio 3DS foi retirado antes da abertura.");
                }
            }
            coreSession = Nintendo3DsCoreSession.open(
                    request.surface,
                    request.width,
                    request.height,
                    libraryPath,
                    contentPath,
                    systemDirectory,
                    saveDirectory,
                    request.screenLayout,
                    request.performanceProfile);
            coreSessionSurfaceGeneration = request.surfaceGeneration;
            recoveryStateCapturedForSession = false;
            openedSessionCount.incrementAndGet();
            // CoreSession.open returns only after the native loadGame/graphics
            // setup succeeds. Seal before runFrame or technical auto-resume.
            if (mapping != null) {
                try { mapping.afterNativeOpened(); }
                catch (IOException | RuntimeException unqualified) {
                    lastSaveMappingFailure = unqualified.getMessage();
                }
            }
            if (restore != null) restore.accepted(coreSession);
        } catch (IOException | RuntimeException failedOpen) {
            if (coreSession == null && coreSessionSurfaceLease != null) {
                // CoreSession.open returned/failed with its existing Java/JNI cleanup guard.
                // A caller timeout never executes this branch on behalf of the owner.
                var unusedTarget = coreSessionSurfaceLease;
                coreSessionSurfaceLease = null;
                unusedTarget.close();
            }
            if (restore != null) {
                // No guest frame, technical auto-resume or storage checkpoint
                // may promote an unaccepted/partially restored native session.
                try {
                    closeCoreSessionOnOwnerThread();restore.failedAfterNativeClosed();
                } catch (IOException | RuntimeException compensationFailure) {
                    failedOpen.addSuppressed(compensationFailure);
                }
            }
            throw failedOpen;
        }
    }

    private void prepareExplicitRestoreFence() throws IOException {
        explicitRestoreFence = null;preparedRestoreFence = null;
        if (appContext == null || storageLayout == null || saveMappingLibraryId == null) return;
        var expected = appContext.getFilesDir().toPath().toRealPath().resolve("nintendo3ds/v1/save/Azahar");
        if (!storageLayout.getUserDirectory().toPath().toRealPath().equals(expected)) return;
        Nintendo3DsSaveDescriptor descriptor;
        try { File image = new File(contentPath);descriptor = Nintendo3DsSaveDescriptor.inspectPrepared(image, image.getName()); }
        catch (IOException unsupported) { return; }
        explicitRestoreFence = CommonSaveResumeFenceFile.openNintendo3Ds(appContext,
                Nintendo3DsSaveRestoreOwner.gameKey(descriptor));
        preparedRestoreFence = explicitRestoreFence.read();
        if (preparedRestoreFence != null && preparedRestoreFence.active) recoveryStateRequired = false;
    }

    private void checkPreparedRestoreFence() throws IOException {
        if (explicitRestoreFence == null) return;
        var current = explicitRestoreFence.read();
        if (current == null ? preparedRestoreFence != null : preparedRestoreFence == null
                || !current.epoch.equals(preparedRestoreFence.epoch)
                || !current.requestId.equals(preparedRestoreFence.requestId)
                || current.active != preparedRestoreFence.active)
            throw new IOException("3DS automatic resume belongs to an older recovery choice");
    }

    public String getLastSaveMappingFailure() { return lastSaveMappingFailure; }

    private void captureRecoveryStateOnOwnerThread() throws RecoveryException {
        if (coreSession == null
                || recoveryStateFile == null
                || recoveryStateCapturedForSession) {
            return;
        }
        if (recoveryFailure != null) {
            throw recoveryFailure;
        }
        recoveryStateRequired = true;
        try {
            checkPreparedRestoreFence();
            File parent = recoveryStateFile.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                throw new IOException("Não foi possível preparar o cache de retomada 3DS.");
            }
            coreSession.saveRecoveryState(
                    recoveryStateFile.getAbsolutePath(),
                    MAX_RECOVERY_STATE_BYTES);
            checkPreparedRestoreFence();
            if (explicitRestoreFence != null && preparedRestoreFence != null && preparedRestoreFence.active) {
                if (!explicitRestoreFence.acknowledgeNewAutomaticWrite(preparedRestoreFence))
                    throw new IOException("3DS automatic recovery fence changed during write");
                preparedRestoreFence = explicitRestoreFence.read();
            }
            recoveryStateCapturedForSession = true;
            capturedRecoveryStateCount.incrementAndGet();
            lastRecoveryStateFailure = null;
        } catch (IOException | RuntimeException exception) {
            // The native writer commits atomically. Keep any prior valid checkpoint.
            throw failRecovery(exception);
        }
    }

    private void restoreRecoveryStateOnOwnerThread(SessionRequest request) throws IOException {
        checkPreparedRestoreFence();
        if (preparedRestoreFence != null && preparedRestoreFence.active) return;
        if (coreSession == null || recoveryStateFile == null || !recoveryStateRequired) {
            return;
        }
        try {
            if (!recoveryStateFile.isFile()) {
                throw new IOException("O estado necessário para retomar o 3DS não está disponível.");
            }
            coreSession.restoreRecoveryState(
                    recoveryStateFile.getAbsolutePath(),
                    MAX_RECOVERY_STATE_BYTES);
            restoredRecoveryStateCount.incrementAndGet();
            deleteRecoveryStateFiles();
            recoveryStateRequired = false;
            lastRecoveryStateFailure = null;
        } catch (IOException | RuntimeException exception) {
            RecoveryException failure = failRecovery(exception);
            // A rejected state may have partially mutated the new core. Close it,
            // retain the checkpoint for an explicit retry/clear, and deliver no frame.
            closeAfterRecoveryFailureOnOwnerThread(failure, false);
            throw failure;
        }
    }

    private RecoveryException failRecovery(Throwable cause) {
        RecoveryException failure = new RecoveryException(cause);
        recoveryFailure = failure;
        lastRecoveryStateFailure = cause.getMessage();
        return failure;
    }

    private void closeAfterRecoveryFailureOnOwnerThread(
            RecoveryException failure, boolean checkpoint) {
        try {
            if (checkpoint) {
                closeCoreSessionAndCheckpointOnOwnerThread();
            } else {
                // Do not promote storage from a partially restored native session.
                closeCoreSessionOnOwnerThread();
            }
        } catch (IOException | RuntimeException checkpointFailure) {
            failure.addSuppressed(checkpointFailure);
        }
    }

    private void deleteRecoveryStateFiles() throws IOException {
        if (recoveryStateFile == null) {
            return;
        }
        File temporary = new File(recoveryStateFile.getAbsolutePath() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Não foi possível remover o estado transitório incompleto 3DS.");
        }
        if (recoveryStateFile.exists() && !recoveryStateFile.delete()) {
            throw new IOException("Não foi possível remover o estado transitório 3DS.");
        }
        recoveryStateCapturedForSession = false;
    }

    private void drainMicrophoneOnOwnerThread() {
        if (microphoneSource == null || coreSession == null) {
            return;
        }
        // The producer queue is fixed, so this bound drains one complete snapshot at most.
        int transfers = Nintendo3DsAndroidMicrophoneSource.QUEUE_CAPACITY_SAMPLES
                / microphoneTransfer.length + 1;
        for (int transfer = 0; transfer < transfers; transfer++) {
            int count = microphoneSource.drain(microphoneTransfer);
            if (count == 0) {
                return;
            }
            coreSession.enqueueMicrophoneSamples(microphoneTransfer, count);
        }
    }

    private boolean closeCoreSessionOnOwnerThread() {
        audioOutput.releaseForTransition();
        framePacer.reset();
        if (coreSession == null) {
            return false;
        }
        coreSession.close();
        coreSession = null;
        coreSessionSurfaceGeneration = -1;
        closedSessionCount.incrementAndGet();
        var closedTarget = coreSessionSurfaceLease;
        coreSessionSurfaceLease = null;
        if (closedTarget != null) closedTarget.close(); // Only AFTER the actual native close return.
        return true;
    }

    private void closeCoreSessionAndCheckpointOnOwnerThread() throws IOException {
        if (closeCoreSessionOnOwnerThread() && storageLayout != null) {
            lastStorageCheckpoint = storageLayout.checkpointAfterCoreClosed(coreRevision);
        }
    }

    private void paceFrame(
            SessionRequest request,
            long frameStartedNanos,
            double nominalFramesPerSecond) throws IOException {
        float speed;
        synchronized (stateLock) {
            speed = fastForwardSpeed;
        }
        try {
            framePacer.pace(speed, nominalFramesPerSecond, frameStartedNanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Pacing 3DS interrompido.", exception);
        }
        ensureRequestStillCurrent(request);
    }

    private void requireControllerOpen() {
        if (closed) {
            throw new IllegalStateException("O controlador de lifecycle 3DS está fechado.");
        }
    }

    private static <T> T await(Future<T> future, long timeoutMs, String transition)
            throws IOException {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("O timeout de lifecycle 3DS deve ser positivo.");
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Lifecycle 3DS interrompido durante " + transition + ".", exception);
        } catch (TimeoutException exception) {
            throw new IOException("Lifecycle 3DS excedeu o timeout durante " + transition + ".", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IOException("Lifecycle 3DS falhou durante " + transition + ".", cause);
        }
    }

    private static final class SessionInvalidatedException extends IOException {
        SessionInvalidatedException(String message) {
            super(message);
        }
    }

    /** Terminal for automatic recovery; a reset/clear must be explicitly requested. */
    public static final class RecoveryException extends IOException {
        private RecoveryException(Throwable cause) {
            super("Não foi possível retomar a sessão Nintendo 3DS.", cause);
        }
    }

    private static File recoveryStateFile(
            Nintendo3DsStorageLayout storageLayout,
            String contentPath,
            String coreRevision) {
        File content = new File(contentPath);
        String identity = Objects.toString(coreRevision, "unknown")
                + '\n' + content.getAbsolutePath()
                + '\n' + content.length()
                + '\n' + content.lastModified();
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível para retomada 3DS.", exception);
        }
        StringBuilder name = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            name.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return new File(
                new File(storageLayout.getTransientDirectory(), "lifecycle"),
                name + ".state");
    }

    private static final class SessionRequest {
        private final Surface surface;
        private final int width;
        private final int height;
        private final long surfaceGeneration;
        private final Nintendo3DsScreenLayout screenLayout;
        private final Nintendo3DsPerformanceProfile performanceProfile;
        private final Nintendo3DsSurfaceLifetime lifetime;

        SessionRequest(
                Surface surface,
                int width,
                int height,
                long surfaceGeneration,
                Nintendo3DsScreenLayout screenLayout,
                Nintendo3DsPerformanceProfile performanceProfile,
                Nintendo3DsSurfaceLifetime lifetime) {
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.surfaceGeneration = surfaceGeneration;
            this.screenLayout = screenLayout;
            this.performanceProfile = performanceProfile;
            this.lifetime = lifetime;
        }
    }

    private static final class CoreThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(() -> {
                try {
                    // The core owns presentation timing and creates the Vulkan/pipeline workers.
                    // DISPLAY is Android's normal render-thread priority: applying it before the
                    // native session opens also lets those workers inherit the same scheduling
                    // class, reducing long first-use shader stalls without changing emulation
                    // accuracy, resolution or clock settings.
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_DISPLAY);
                } catch (RuntimeException | LinkageError ignored) {
                    // Host-side JVM tests and vendor schedulers may not expose this hint. The
                    // controller remains functional at the platform's default priority.
                }
                runnable.run();
            }, "EmuOrbit-N3DS-Core");
            thread.setDaemon(false);
            return thread;
        }
    }
}
