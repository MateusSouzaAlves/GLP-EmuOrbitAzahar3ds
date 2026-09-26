// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.atomic.AtomicReference;

/** Owns a display-consumed cloud surface while preserving the established local fallback. */
final class Nintendo3DsTestSurfaceOwner implements AutoCloseable {
    private static final String ADRENO_TARGET_PACKAGE =
            "com.mateussouza.emuorbit.n3ds.adreno.target";
    private static final String ADRENO_SURFACE_ACTIVITY = ADRENO_TARGET_PACKAGE
            + ".Nintendo3DsAdrenoSurfaceActivity";
    private static final String EXTRA_WIDTH = "n3ds.surfaceWidth";
    private static final String EXTRA_HEIGHT = "n3ds.surfaceHeight";
    private static final long SURFACE_TIMEOUT_MILLIS = 30_000;

    final Surface surface;
    private final SurfaceTexture texture;
    private final Activity activity;

    Nintendo3DsTestSurfaceOwner(int width, int height) {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (ADRENO_TARGET_PACKAGE.equals(targetContext.getPackageName())) {
            texture = null;
            Intent intent = new Intent()
                    .setClassName(targetContext, ADRENO_SURFACE_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_WIDTH, width)
                    .putExtra(EXTRA_HEIGHT, height);
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            activity = instrumentation.startActivitySync(intent);
            instrumentation.waitForIdleSync();
            surface = awaitCompositorSurface(instrumentation, activity);
        } else {
            activity = null;
            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(width, height);
            surface = new Surface(texture);
        }
    }

    @Override
    public void close() {
        if (activity != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
            return;
        }
        surface.release();
        texture.release();
    }

    private static Surface awaitCompositorSurface(
            Instrumentation instrumentation,
            Activity activity) {
        long deadline = SystemClock.elapsedRealtime() + SURFACE_TIMEOUT_MILLIS;
        AtomicReference<Surface> observed = new AtomicReference<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                SurfaceView view = findSurfaceView(activity.getWindow().getDecorView());
                if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
                    return;
                }
                Surface candidate = view.getHolder().getSurface();
                if (candidate != null && candidate.isValid()) {
                    observed.set(candidate);
                }
            });
            Surface candidate = observed.get();
            if (candidate != null) {
                return candidate;
            }
            SystemClock.sleep(50);
        }
        instrumentation.runOnMainSync(activity::finish);
        throw new AssertionError("A superfície consumida do gate Adreno não ficou pronta.");
    }

    private static SurfaceView findSurfaceView(View view) {
        if (view instanceof SurfaceView) {
            return (SurfaceView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            SurfaceView candidate = findSurfaceView(group.getChildAt(index));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}
