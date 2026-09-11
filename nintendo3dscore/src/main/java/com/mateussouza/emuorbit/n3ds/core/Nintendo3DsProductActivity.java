// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.play.core.splitcompat.SplitCompat;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hidden product-ready host for readiness, settings, input, Surface and native lifecycle.
 *
 * <p>The Activity is deliberately not exported and the base app does not depend on this module.
 * A future install-time or on-demand feature can launch it through
 * {@link Nintendo3DsProductLaunchRequest} after the N3DS-12 packaging decision.</p>
 */
public final class Nintendo3DsProductActivity extends ComponentActivity
        implements SurfaceHolder.Callback,
        Nintendo3DsActivityResultHost.Listener,
        Nintendo3DsExperienceSettingsDialogController.Listener {
    public static final String EXTRA_RESULT_ACTION = "n3ds.product.result_action";
    public static final String RESULT_ACTION_EXTRACT_CONTENT = "extract_content";

    private static final int DEFAULT_FRAME_WIDTH = 400;
    private static final int DEFAULT_FRAME_HEIGHT = 480;
    private static final long FRAME_STOP_TIMEOUT_SECONDS = 15;

    private final AtomicBoolean frameLoopRunning = new AtomicBoolean();
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicLong routedInputEvents = new AtomicLong();
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(runnable ->
            daemonThread(runnable, "n3ds-product-preflight"));
    private final ExecutorService frameExecutor = Executors.newSingleThreadExecutor(runnable ->
            daemonThread(runnable, "n3ds-product-frames"));

    private ActivityResultLauncher<String> microphonePermissionLauncher;
    private Nintendo3DsProductLaunchRequest launchRequest;
    private Nintendo3DsExperimentalHost experimentalHost;
    private Nintendo3DsActivityResultHost activityResultHost;
    private Nintendo3DsExperienceSettingsStore settingsStore;
    private Nintendo3DsExperienceSettingsDialogController settingsDialogController;
    private Nintendo3DsCoreLifecycleController controller;
    private Nintendo3DsPhysicalControllerRouter inputRouter;
    private SurfaceHolder surfaceHolder;
    private Surface currentSurface;
    private int currentSurfaceWidth;
    private int currentSurfaceHeight;
    private Button settingsButton;
    private Future<?> frameLoop;
    private boolean hostResumed;
    private boolean controllerResumed;
    private boolean microphonePermissionPending;
    private boolean microphonePermissionInFlight;
    private volatile Throwable productFailure;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        SplitCompat.installActivity(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        microphonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onMicrophonePermissionResult);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            launchRequest = Nintendo3DsProductLaunchRequest.fromIntent(getIntent());
            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    getFilesDir(), getCacheDir());
            experimentalHost = new Nintendo3DsExperimentalHost(this, storage);
            settingsStore = new Nintendo3DsExperienceSettingsStore(this);
            Nintendo3DsProductDialogStyleAdapter styleAdapter =
                    new Nintendo3DsProductDialogStyleAdapter();
            settingsDialogController = new Nintendo3DsExperienceSettingsDialogController(
                    settingsStore,
                    styleAdapter);
            activityResultHost = new Nintendo3DsActivityResultHost(
                    this,
                    experimentalHost,
                    new Nintendo3DsMiiRecoveryCoordinator.LaunchRequest(
                            launchRequest.getCoreLibrary(),
                            launchRequest.getContent(),
                            launchRequest.getContentStatus(),
                            launchRequest.getRequiredPrivateStorageBytes(),
                            launchRequest.getMiiRequirement(),
                            launchRequest.isDeviceQualified()),
                    workerExecutor,
                    styleAdapter,
                    this);
        } catch (IOException | IllegalArgumentException failure) {
            failAndFinish(failure);
            return;
        }

        setContentView(createContentView());
        activityResultHost.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hostResumed = true;
        resumeControllerIfReady();
        launchPendingMicrophonePermission();
    }

    @Override
    protected void onPause() {
        hostResumed = false;
        stopFrameLoop();
        if (inputRouter != null) {
            inputRouter.reset();
        }
        if (controller != null && controllerResumed) {
            closeForTransition(controller::onPauseAndAwait);
            controllerResumed = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopFrameLoop();
        if (surfaceHolder != null) {
            surfaceHolder.removeCallback(this);
        }
        if (settingsDialogController != null) {
            settingsDialogController.close();
        }
        if (activityResultHost != null) {
            activityResultHost.close();
        }
        if (experimentalHost != null) {
            closeForTransition(experimentalHost::close);
        }
        frameExecutor.shutdownNow();
        workerExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (inputRouter != null && inputRouter.applyKeyCode(keyCode, true)) {
            routedInputEvents.incrementAndGet();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (inputRouter != null && inputRouter.applyKeyCode(keyCode, false)) {
            routedInputEvents.incrementAndGet();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (inputRouter != null && inputRouter.handleMotion(event)) {
            routedInputEvents.incrementAndGet();
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // surfaceChanged provides the authoritative non-zero dimensions.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        currentSurface = holder.getSurface();
        currentSurfaceWidth = width;
        currentSurfaceHeight = height;
        bindCurrentSurfaceIfReady();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopFrameLoop();
        Surface destroyedSurface = holder.getSurface();
        if (controller != null) {
            closeForTransition(() -> controller.onSurfaceDestroyedAndAwait(destroyedSurface));
        }
        if (currentSurface == destroyedSurface) {
            currentSurface = null;
            currentSurfaceWidth = 0;
            currentSurfaceHeight = 0;
        }
    }

    @Override
    public void onLaunchReady(Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Nintendo3DsCoreLifecycleController prepared = preparedLaunch.requireController();
        if (controller != null && controller != prepared) {
            failAndFinish(new IllegalStateException(
                    "O controlador Nintendo 3DS mudou durante o launch."));
            return;
        }
        controller = prepared;
        inputRouter = new Nintendo3DsPhysicalControllerRouter(prepared);
        Nintendo3DsExperienceSettings settings = settingsStore
                .loadGameOverrides(launchRequest.getPersistentId())
                .resolve(settingsStore.loadGlobal());
        applySettings(settings);
        settingsButton.setEnabled(productFailure == null);
        resumeControllerIfReady();
    }

    @Override
    public void onExtractionRequested() {
        setResult(
                RESULT_FIRST_USER,
                new Intent().putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_EXTRACT_CONTENT));
        finish();
    }

    @Override
    public void onUnderstood() {
        finish();
    }

    @Override
    public void onFailure(
            Nintendo3DsReadinessPresentation.Action action,
            Exception failure) {
        recordFailure(failure);
        Toast.makeText(this, R.string.n3ds_product_launch_failed, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onSettingsChanged(
            Nintendo3DsExperienceSettings settings,
            Nintendo3DsExperienceSettingsDialogController.Scope scope,
            boolean reset) {
        applySettings(settings);
        startFrameLoopIfReady();
    }

    @Override
    public void onPersistenceFailed(
            Nintendo3DsExperienceSettingsDialogController.Scope scope) {
        Toast.makeText(this, R.string.n3ds_product_settings_save_failed, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDialogDismissed(
            Nintendo3DsExperienceSettingsDialogController.Scope scope,
            boolean settingsChanged) {
        if (!settingsChanged) {
            startFrameLoopIfReady();
        }
    }

    Nintendo3DsCoreLifecycleController getControllerForTesting() {
        return controller;
    }

    Nintendo3DsExperienceSettingsDialogController getSettingsDialogForTesting() {
        return settingsDialogController;
    }

    Button getSettingsButtonForTesting() {
        return settingsButton;
    }

    Throwable getProductFailureForTesting() {
        return productFailure;
    }

    long getRenderedFrameCountForTesting() {
        return renderedFrames.get();
    }

    long getRoutedInputEventCountForTesting() {
        return routedInputEvents.get();
    }

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView surfaceView = new Nintendo3DsSurfaceView(this);
        surfaceView.setKeepScreenOn(true);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        surfaceView.setOnTouchListener(this::routeTouch);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        settingsButton = new Button(this);
        settingsButton.setText(R.string.n3ds_product_settings);
        settingsButton.setContentDescription(getString(R.string.n3ds_product_settings));
        settingsButton.setAllCaps(false);
        settingsButton.setEnabled(false);
        settingsButton.setOnClickListener(ignored -> showGameSettings());
        FrameLayout.LayoutParams settingsLayout = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        int margin = Math.round(16 * getResources().getDisplayMetrics().density);
        settingsLayout.setMargins(margin, margin, margin, margin);
        root.addView(settingsButton, settingsLayout);

        surfaceView.requestFocus();
        return root;
    }

    private void showGameSettings() {
        if (controller == null || settingsDialogController == null) {
            return;
        }
        stopFrameLoop();
        try {
            boolean shown = settingsDialogController.showForGame(
                    this,
                    launchRequest.getPersistentId(),
                    this);
            if (!shown) {
                startFrameLoopIfReady();
            }
        } catch (RuntimeException failure) {
            recordFailure(failure);
            startFrameLoopIfReady();
        }
    }

    private void applySettings(Nintendo3DsExperienceSettings settings) {
        Nintendo3DsCoreLifecycleController active = controller;
        if (active == null || active.isClosed()) {
            return;
        }
        stopFrameLoop();
        try {
            Nintendo3DsMicrophoneStartResult microphone = settings.applyTo(active);
            if (microphone == Nintendo3DsMicrophoneStartResult.PERMISSION_REQUIRED) {
                microphonePermissionPending = true;
                launchPendingMicrophonePermission();
            } else {
                microphonePermissionPending = false;
            }
        } catch (IOException | RuntimeException failure) {
            recordFailure(failure);
        }
    }

    private void onMicrophonePermissionResult(boolean granted) {
        microphonePermissionInFlight = false;
        microphonePermissionPending = false;
        Nintendo3DsCoreLifecycleController active = controller;
        if (active == null || active.isClosed()) {
            return;
        }
        if (!granted) {
            Toast.makeText(
                    this,
                    R.string.n3ds_product_microphone_permission_denied,
                    Toast.LENGTH_LONG).show();
            startFrameLoopIfReady();
            return;
        }
        active.setMicrophoneEnabled(true);
        startFrameLoopIfReady();
    }

    private void launchPendingMicrophonePermission() {
        if (!hostResumed || !microphonePermissionPending || microphonePermissionInFlight) {
            return;
        }
        microphonePermissionInFlight = true;
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void resumeControllerIfReady() {
        Nintendo3DsCoreLifecycleController active = controller;
        if (!hostResumed || active == null || active.isClosed()) {
            return;
        }
        if (!controllerResumed) {
            active.setDisplayRotation(currentDisplayRotation());
            active.onResume();
            controllerResumed = true;
        }
        bindCurrentSurfaceIfReady();
    }

    private void bindCurrentSurfaceIfReady() {
        Nintendo3DsCoreLifecycleController active = controller;
        Surface target = currentSurface;
        if (active == null || active.isClosed() || target == null || !target.isValid()
                || currentSurfaceWidth <= 0 || currentSurfaceHeight <= 0) {
            return;
        }
        stopFrameLoop();
        closeForTransition(() -> active.onSurfaceAvailable(
                target,
                currentSurfaceWidth,
                currentSurfaceHeight));
        startFrameLoopIfReady();
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
        Nintendo3DsCoreLifecycleController active = controller;
        if (active != null && !active.isClosed() && view.getWidth() > 0 && view.getHeight() > 0) {
            active.setTouchPositionFromSurface(
                    event.getX(),
                    event.getY(),
                    view.getWidth(),
                    view.getHeight(),
                    DEFAULT_FRAME_WIDTH,
                    DEFAULT_FRAME_HEIGHT,
                    pressed);
            routedInputEvents.incrementAndGet();
        }
        if (action == MotionEvent.ACTION_UP) {
            view.performClick();
        }
        return true;
    }

    private void startFrameLoopIfReady() {
        Nintendo3DsCoreLifecycleController active = controller;
        if (!hostResumed || !controllerResumed || active == null || active.isClosed()
                || settingsDialogController.isShowing() || !active.isReadyForFrames()
                || !frameLoopRunning.compareAndSet(false, true)) {
            return;
        }
        frameLoop = frameExecutor.submit(() -> {
            try {
                while (frameLoopRunning.get()) {
                    if (!active.isReadyForFrames()) {
                        SystemClock.sleep(8);
                        continue;
                    }
                    active.runFrames(1, Nintendo3DsCoreLifecycleController.DEFAULT_TRANSITION_TIMEOUT_MS);
                    renderedFrames.incrementAndGet();
                }
            } catch (IOException | RuntimeException failure) {
                if (frameLoopRunning.get()) {
                    recordFailure(failure);
                }
            } finally {
                frameLoopRunning.set(false);
            }
        });
    }

    private void stopFrameLoop() {
        frameLoopRunning.set(false);
        Future<?> task = frameLoop;
        frameLoop = null;
        if (task == null) {
            return;
        }
        try {
            task.get(FRAME_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            recordFailure(failure);
        } catch (ExecutionException failure) {
            recordFailure(failure.getCause());
        } catch (TimeoutException failure) {
            task.cancel(true);
            recordFailure(failure);
        }
    }

    private void closeForTransition(CheckedTransition transition) {
        try {
            transition.run();
        } catch (IOException | RuntimeException failure) {
            recordFailure(failure);
        }
    }

    private void recordFailure(Throwable failure) {
        if (failure != null && productFailure == null) {
            productFailure = failure;
        }
    }

    private void failAndFinish(Throwable failure) {
        recordFailure(failure);
        Toast.makeText(this, R.string.n3ds_product_launch_failed, Toast.LENGTH_LONG).show();
        finish();
    }

    @SuppressWarnings("deprecation")
    private int currentDisplayRotation() {
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @FunctionalInterface
    private interface CheckedTransition {
        void run() throws IOException;
    }

    private static final class Nintendo3DsSurfaceView extends SurfaceView {
        Nintendo3DsSurfaceView(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
