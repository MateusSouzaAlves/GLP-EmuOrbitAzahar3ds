// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.google.android.play.core.splitcompat.SplitCompat;
import com.mateussouza.emuorbit.advance.nintendo3ds.delivery.Nintendo3DsDeliveryProofStore;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs real Azahar/Vulkan frames from the installed split in the isolated test package. */
public final class Nintendo3DsDeliveryProbeActivity extends Activity
        implements SurfaceHolder.Callback {
    private static final String EXTRA_RUN_ID = "n3ds.delivery.test.run_id";
    private static final String EXTRA_CONTENT_PATH = "n3ds.delivery.test.content_path";
    private static final int PROOF_FRAME_COUNT = 60;

    private final AtomicBoolean started = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "n3ds-delivery-probe");
        thread.setDaemon(true);
        return thread;
    });
    private String runId;
    private File content;
    private Nintendo3DsCoreLifecycleController controller;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        SplitCompat.installActivity(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            runId = requireText(getIntent().getStringExtra(EXTRA_RUN_ID), "runId");
            content = new File(requireText(
                    getIntent().getStringExtra(EXTRA_CONTENT_PATH),
                    "contentPath")).getCanonicalFile();
            if (!content.isAbsolute() || !content.isFile() || !content.canRead()) {
                throw new IOException("A homebrew externa da prova 3DS não está acessível.");
            }
            record("PROBE:BEGIN:contentBytes=" + content.length());
            SurfaceView surfaceView = new SurfaceView(this);
            surfaceView.getHolder().addCallback(this);
            setContentView(surfaceView);
        } catch (IOException | RuntimeException failure) {
            fail(failure);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // surfaceChanged supplies the authoritative dimensions.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (width <= 0 || height <= 0 || !started.compareAndSet(false, true)) {
            return;
        }
        Surface surface = holder.getSurface();
        worker.execute(() -> runProbe(surface, width, height));
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        closeController();
    }

    @Override
    protected void onDestroy() {
        closeController();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void runProbe(Surface surface, int width, int height) {
        try {
            Nintendo3DsCoreInfo info = Nintendo3DsCoreBootstrap.inspectPackaged(this);
            if (!Nintendo3DsAndroidLaunchReadinessInspector.isExpectedCore(info)) {
                throw new IOException("O split não contém o núcleo Azahar esperado.");
            }
            record("PROBE:CORE:" + info.getLibraryName() + ':' + info.getLibraryVersion());

            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    getFilesDir(), getCacheDir());
            Nintendo3DsCoreLifecycleController active = new Nintendo3DsCoreLifecycleController(
                    this,
                    Nintendo3DsCoreBootstrap.PACKAGED_CORE_LIBRARY_PATH,
                    content.getAbsolutePath(),
                    storage,
                    Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
            controller = active;
            active.onResume();
            active.onSurfaceAvailable(surface, width, height);
            Nintendo3DsCoreFrameReport report = active.runFrames(
                    PROOF_FRAME_COUNT,
                    120_000L);
            if (report.getPresentedFrames() < PROOF_FRAME_COUNT
                    || report.getVideoFrames() < PROOF_FRAME_COUNT
                    || !report.isContentOpenedThroughVfs()
                    || !report.isHardwareRenderNegotiated()) {
                throw new IOException("A execução do split não produziu evidência Vulkan completa.");
            }
            record("PROBE:SUCCESS:presented=" + report.getPresentedFrames()
                    + ":video=" + report.getVideoFrames()
                    + ":vfsBytes=" + report.getVfsBytesRead()
                    + ":device=" + report.getDeviceName());
            closeController();
            runOnUiThread(this::finish);
        } catch (IOException | RuntimeException | LinkageError failure) {
            fail(failure);
        }
    }

    private synchronized void closeController() {
        Nintendo3DsCoreLifecycleController active = controller;
        controller = null;
        if (active == null) {
            return;
        }
        try {
            active.onPauseAndAwait();
            active.close();
        } catch (IOException | RuntimeException failure) {
            if (runId != null) {
                record("PROBE:CLOSE_FAILURE:" + failure.getClass().getSimpleName());
            }
        }
    }

    private void fail(Throwable failure) {
        if (runId != null) {
            record("PROBE:FAILURE:" + failure.getClass().getSimpleName());
        }
        closeController();
        runOnUiThread(this::finish);
    }

    private void record(String event) {
        Nintendo3DsDeliveryProofStore.append(this, runId, event);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty() || checked.length() > 1_024) {
            throw new IllegalArgumentException(label + " inválido.");
        }
        return checked;
    }
}
