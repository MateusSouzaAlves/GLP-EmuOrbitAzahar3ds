// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/* Debug-only Android host used to prove real process and Surface lifecycle transitions. */
public final class Nintendo3DsLifecycleTestActivity extends Activity
        implements SurfaceHolder.Callback {
    public static final String EXTRA_CORE_PATH = "n3ds.corePath";
    public static final String EXTRA_CONTENT_PATH = "n3ds.contentPath";
    public static final String EXTRA_INTERACTIVE = "n3ds.interactive";

    private static final int DEFAULT_FRAME_WIDTH = 400;
    private static final int DEFAULT_FRAME_HEIGHT = 480;
    private static final long INTERACTIVE_STOP_TIMEOUT_SECONDS = 15;

    private static final AtomicInteger NEXT_INSTANCE_ID = new AtomicInteger();
    private static final AtomicReference<Nintendo3DsLifecycleTestActivity> RESUMED_ACTIVITY =
            new AtomicReference<>();

    private final int instanceId = NEXT_INSTANCE_ID.incrementAndGet();
    private final AtomicBoolean interactiveLoopRunning = new AtomicBoolean();
    private final AtomicLong interactiveFrames = new AtomicLong();
    private final AtomicLong interactiveInputEvents = new AtomicLong();
    private final ExecutorService interactiveExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "n3ds-debug-interactive");
        thread.setDaemon(true);
        return thread;
    });
    private Nintendo3DsCoreLifecycleController controller;
    private SurfaceView surfaceView;
    private Nintendo3DsExperimentalHost experimentalHost;
    private Nintendo3DsLaunchReadiness.Result launchReadiness;
    private Nintendo3DsPhysicalControllerRouter inputRouter;
    private SurfaceHolder surfaceHolder;
    private Future<?> interactiveLoop;
    private boolean interactiveMode;
    private volatile Throwable lifecycleFailure;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Debug-only: lets the lifecycle harness resume after screen-on without changing,
        // dismissing, or learning the owner's secure keyguard credential.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        interactiveMode = getIntent().getBooleanExtra(EXTRA_INTERACTIVE, false);

        try {
            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    getFilesDir(), getCacheDir());
            experimentalHost = new Nintendo3DsExperimentalHost(this, storage);
            Nintendo3DsExperimentalHost.PreparedLaunch prepared =
                    experimentalHost.prepareLaunch(
                            new File(requireExtra(EXTRA_CORE_PATH)),
                            new File(requireExtra(EXTRA_CONTENT_PATH)),
                            Nintendo3DsLaunchReadiness.ContentStatus.READY,
                            64L * 1024L * 1024L,
                            Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                            true);
            launchReadiness = prepared.getReadiness();
            controller = prepared.requireController();
            inputRouter = new Nintendo3DsPhysicalControllerRouter(controller);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível preparar o armazenamento privado 3DS.", exception);
        }

        surfaceView = new SurfaceView(this);
        surfaceView.setKeepScreenOn(true);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        if (interactiveMode) {
            surfaceView.setOnTouchListener(this::routeTouch);
            surfaceView.requestFocus();
        }
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        setContentView(surfaceView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.onResume();
        RESUMED_ACTIVITY.set(this);
        startInteractiveLoop();
    }

    @Override
    protected void onPause() {
        RESUMED_ACTIVITY.compareAndSet(this, null);
        stopInteractiveLoop();
        if (inputRouter != null) {
            inputRouter.reset();
        }
        closeForTransition(() -> controller.onPauseAndAwait());
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        RESUMED_ACTIVITY.compareAndSet(this, null);
        stopInteractiveLoop();
        interactiveExecutor.shutdownNow();
        if (surfaceHolder != null) {
            surfaceHolder.removeCallback(this);
        }
        if (experimentalHost != null) {
            closeForTransition(experimentalHost::close);
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (interactiveMode && inputRouter != null
                && (event.getAction() == KeyEvent.ACTION_DOWN
                || event.getAction() == KeyEvent.ACTION_UP)) {
            boolean handled = inputRouter.applyKeyCode(
                    event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN);
            if (handled) {
                interactiveInputEvents.incrementAndGet();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (interactiveMode && inputRouter != null && inputRouter.handleMotion(event)) {
            interactiveInputEvents.incrementAndGet();
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // surfaceChanged supplies authoritative non-zero dimensions.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        closeForTransition(() -> controller.onSurfaceAvailable(
                holder.getSurface(), width, height));
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Surface destroyedSurface = holder.getSurface();
        closeForTransition(() -> controller.onSurfaceDestroyedAndAwait(destroyedSurface));
    }

    public Nintendo3DsCoreLifecycleController getController() {
        return controller;
    }

    public int getInstanceId() {
        return instanceId;
    }

    public Throwable getLifecycleFailure() {
        return lifecycleFailure;
    }

    public Nintendo3DsLaunchReadiness.Result getLaunchReadiness() {
        return launchReadiness;
    }

    public boolean isInteractiveMode() {
        return interactiveMode;
    }

    public long getInteractiveFrameCount() {
        return interactiveFrames.get();
    }

    public long getInteractiveInputEventCount() {
        return interactiveInputEvents.get();
    }

    void setScriptedButtonPressed(Nintendo3DsButton button, boolean pressed) {
        requireInteractiveController();
        controller.setButtonPressed(button, pressed);
        interactiveInputEvents.incrementAndGet();
    }

    void setScriptedCirclePad(float horizontal, float vertical) {
        requireInteractiveController();
        controller.setCirclePad(horizontal, vertical);
        interactiveInputEvents.incrementAndGet();
    }

    void setScriptedTouchFromSurfaceFraction(
            float surfaceFractionX,
            float surfaceFractionY,
            boolean pressed) {
        requireInteractiveController();
        if (!Float.isFinite(surfaceFractionX)
                || !Float.isFinite(surfaceFractionY)
                || surfaceFractionX < 0.0f
                || surfaceFractionX > 1.0f
                || surfaceFractionY < 0.0f
                || surfaceFractionY > 1.0f) {
            throw new IllegalArgumentException("As coordenadas de touch devem estar entre 0 e 1.");
        }
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("A Surface 3DS ainda não possui dimensões válidas.");
        }
        controller.setTouchPositionFromSurface(
                surfaceFractionX * width,
                surfaceFractionY * height,
                width,
                height,
                DEFAULT_FRAME_WIDTH,
                DEFAULT_FRAME_HEIGHT,
                pressed);
        interactiveInputEvents.incrementAndGet();
    }

    public static Nintendo3DsLifecycleTestActivity getResumedActivity() {
        return RESUMED_ACTIVITY.get();
    }

    private String requireExtra(String name) {
        String value = getIntent().getStringExtra(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Extra obrigatório ausente: " + name);
        }
        return value;
    }

    private void requireInteractiveController() {
        if (!interactiveMode || controller == null || controller.isClosed()) {
            throw new IllegalStateException("O host interativo 3DS não está disponível.");
        }
    }

    private boolean routeTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL) {
            return false;
        }
        boolean pressed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width > 0 && height > 0 && controller != null && !controller.isClosed()) {
            controller.setTouchPositionFromSurface(
                    event.getX(),
                    event.getY(),
                    width,
                    height,
                    DEFAULT_FRAME_WIDTH,
                    DEFAULT_FRAME_HEIGHT,
                    pressed);
            interactiveInputEvents.incrementAndGet();
        }
        return true;
    }

    private void startInteractiveLoop() {
        if (!interactiveMode || !interactiveLoopRunning.compareAndSet(false, true)) {
            return;
        }
        interactiveLoop = interactiveExecutor.submit(() -> {
            try {
                while (interactiveLoopRunning.get()) {
                    if (!controller.isReadyForFrames()) {
                        SystemClock.sleep(8);
                        continue;
                    }
                    controller.runFrames(1, 30_000);
                    interactiveFrames.incrementAndGet();
                }
            } catch (IOException | RuntimeException exception) {
                if (interactiveLoopRunning.get() && lifecycleFailure == null) {
                    lifecycleFailure = exception;
                }
            } finally {
                interactiveLoopRunning.set(false);
            }
        });
    }

    private void stopInteractiveLoop() {
        interactiveLoopRunning.set(false);
        Future<?> task = interactiveLoop;
        interactiveLoop = null;
        if (task == null) {
            return;
        }
        try {
            task.get(INTERACTIVE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordLifecycleFailure(exception);
        } catch (ExecutionException exception) {
            recordLifecycleFailure(exception.getCause());
        } catch (TimeoutException exception) {
            task.cancel(true);
            recordLifecycleFailure(exception);
        }
    }

    private void recordLifecycleFailure(Throwable failure) {
        if (failure != null && lifecycleFailure == null) {
            lifecycleFailure = failure;
        }
    }

    private void closeForTransition(CheckedTransition transition) {
        try {
            transition.run();
        } catch (IOException | RuntimeException exception) {
            recordLifecycleFailure(exception);
        }
    }

    @FunctionalInterface
    private interface CheckedTransition {
        void run() throws IOException;
    }
}
