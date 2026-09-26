// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.view.Surface;

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
    private final Context appContext;
    private final String libraryPath;
    private final String contentPath;
    private final String systemDirectory;
    private final String saveDirectory;
    private final Nintendo3DsStorageLayout storageLayout;
    private final String coreRevision;
    private final File recoveryStateFile;
    private final ExecutorService coreExecutor;
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

    // Guarded by stateLock.
    private Surface surface;
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
    private long coreSessionSurfaceGeneration = -1;
    private boolean recoveryStateCapturedForSession;
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
                null);
    }

    /** Creates a controller whose closed sessions receive incremental directory checkpoints. */
    public Nintendo3DsCoreLifecycleController(
            Context context,
            String libraryPath,
            String contentPath,
            Nintendo3DsStorageLayout storageLayout,
            String coreRevision) {
        this(
                context,
                libraryPath,
                contentPath,
                Objects.requireNonNull(storageLayout).getSystemDirectory().getAbsolutePath(),
                storageLayout.getSaveDirectory().getAbsolutePath(),
                storageLayout,
                Objects.requireNonNull(coreRevision));
    }

    private Nintendo3DsCoreLifecycleController(
            Context context,
            String libraryPath,
            String contentPath,
            String systemDirectory,
            String saveDirectory,
            Nintendo3DsStorageLayout storageLayout,
            String coreRevision) {
        appContext = context == null ? null : context.getApplicationContext();
        this.libraryPath = Objects.requireNonNull(libraryPath);
        this.contentPath = Objects.requireNonNull(contentPath);
        this.systemDirectory = Objects.requireNonNull(systemDirectory);
        this.saveDirectory = Objects.requireNonNull(saveDirectory);
        this.storageLayout = storageLayout;
        this.coreRevision = coreRevision;
        recoveryStateFile = storageLayout == null
                ? null
                : recoveryStateFile(storageLayout, contentPath, coreRevision);
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
    }

    /* Marks the host resumed. The native session remains lazy until frames are requested. */
    public void onResume() {
        synchronized (stateLock) {
            requireControllerOpen();
            resumed = true;
            if (motionSource != null) {
                motionSource.start();
            }
            if (microphoneRequested && microphoneSource != null) {
                microphoneSource.start();
            }
        }
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
        if (motionSource != null) {
            motionSource.stop();
        }
        if (microphoneSource != null) {
            microphoneSource.stop();
        }
        audioOutput.releaseForTransition();
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
                    && surfaceHeight == height) {
                return;
            }
            surface = checkedSurface;
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
                    performanceProfile);
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
            transition = submitCloseSession();
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
                return regenerableCachePolicy.clearAfterCoreClosed();
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

    /** Recreates the native session with an Azahar-supported screen arrangement. */
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
            transition = submitCloseSession();
        }
        audioOutput.releaseForTransition();
        await(transition, timeoutMs, "troca do layout de telas");
    }

    public Nintendo3DsScreenLayout getScreenLayout() {
        synchronized (stateLock) {
            return screenLayout;
        }
    }

    /** Recreates the native session so startup-only Azahar options change atomically. */
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
            transition = submitCloseSession();
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
        synchronized (stateLock) {
            requireControllerOpen();
            microphoneRequested = enabled;
            if (!enabled) {
                if (microphoneSource != null) {
                    microphoneSource.stop();
                }
                return Nintendo3DsMicrophoneStartResult.DISABLED;
            }
            if (microphoneSource == null) {
                return Nintendo3DsMicrophoneStartResult.UNAVAILABLE;
            }
            if (!resumed) {
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
            surfaceWidth = 0;
            surfaceHeight = 0;
            surfaceGeneration++;
            inputState.clear();
            transition = submitCloseSession();
        }
        if (motionSource != null) {
            motionSource.stop();
        }
        if (microphoneSource != null) {
            microphoneSource.stop();
        }
        audioOutput.releaseForTransition();
        try {
            await(transition, timeoutMs, "encerramento");
        } finally {
            // shutdown() preserves the queued owner-thread close even if the caller timed out.
            coreExecutor.shutdown();
        }
    }

    private Future<Void> submitCloseSession() {
        return coreExecutor.submit(() -> {
            closeCoreSessionAndCheckpointOnOwnerThread();
            return null;
        });
    }

    private Future<Void> submitCaptureRecoveryState() {
        return coreExecutor.submit(() -> {
            captureRecoveryStateOnOwnerThread();
            framePacer.reset();
            return null;
        });
    }

    private Future<Void> submitCloseSessionPreservingRecovery() {
        return coreExecutor.submit(() -> {
            captureRecoveryStateOnOwnerThread();
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
        if (coreSession != null
                && coreSessionSurfaceGeneration == request.surfaceGeneration) {
            return;
        }
        closeCoreSessionAndCheckpointOnOwnerThread();
        openCoreSession(request);
        restoreRecoveryStateOnOwnerThread(request);
    }

    private void openCoreSession(SessionRequest request) throws IOException {
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
    }

    private void captureRecoveryStateOnOwnerThread() {
        if (coreSession == null
                || recoveryStateFile == null
                || recoveryStateCapturedForSession) {
            return;
        }
        File parent = recoveryStateFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            lastRecoveryStateFailure = "Não foi possível preparar o cache de retomada 3DS.";
            return;
        }
        try {
            coreSession.saveRecoveryState(
                    recoveryStateFile.getAbsolutePath(),
                    MAX_RECOVERY_STATE_BYTES);
            recoveryStateCapturedForSession = true;
            capturedRecoveryStateCount.incrementAndGet();
            lastRecoveryStateFailure = null;
        } catch (IOException | RuntimeException exception) {
            lastRecoveryStateFailure = exception.getMessage();
            deleteRecoveryStateFiles();
        }
    }

    private void restoreRecoveryStateOnOwnerThread(SessionRequest request) throws IOException {
        if (coreSession == null || recoveryStateFile == null || !recoveryStateFile.isFile()) {
            return;
        }
        try {
            coreSession.restoreRecoveryState(
                    recoveryStateFile.getAbsolutePath(),
                    MAX_RECOVERY_STATE_BYTES);
            restoredRecoveryStateCount.incrementAndGet();
            lastRecoveryStateFailure = null;
            deleteRecoveryStateFiles();
        } catch (IOException | RuntimeException exception) {
            lastRecoveryStateFailure = exception.getMessage();
            deleteRecoveryStateFiles();
            closeCoreSessionOnOwnerThread();
            openCoreSession(request);
        }
    }

    private void deleteRecoveryStateFiles() {
        if (recoveryStateFile == null) {
            return;
        }
        if (recoveryStateFile.exists() && !recoveryStateFile.delete()) {
            lastRecoveryStateFailure = "Não foi possível remover o estado transitório 3DS.";
        }
        File temporary = new File(recoveryStateFile.getAbsolutePath() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            lastRecoveryStateFailure = "Não foi possível remover o estado transitório incompleto 3DS.";
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
        try {
            coreSession.close();
        } finally {
            coreSession = null;
            coreSessionSurfaceGeneration = -1;
            closedSessionCount.incrementAndGet();
        }
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

        SessionRequest(
                Surface surface,
                int width,
                int height,
                long surfaceGeneration,
                Nintendo3DsScreenLayout screenLayout,
                Nintendo3DsPerformanceProfile performanceProfile) {
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.surfaceGeneration = surfaceGeneration;
            this.screenLayout = screenLayout;
            this.performanceProfile = performanceProfile;
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
