// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.graphics.PixelFormat;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import androidx.annotation.RequiresApi;
import com.mateussouza.emuorbit.advance.nintendo3ds.delivery.Nintendo3DsAndroidCompatibility;

/** App-owned producer target. ProductActivity binds it through the serial lifecycle owner.
 * The framework SurfaceView/control is only a read-only presentation anchor.
 * Requires API29; the base app still supports API26. Entry is guarded before loading
 * this presentation path. No API33 setHidden call. */
@RequiresApi(Nintendo3DsAndroidCompatibility.MINIMUM_SDK)
final class Nintendo3DsOwnedLayer {
    final Surface surface;
    final int width, height;
    final Nintendo3DsSurfaceLifetime lifetime;
    private final SurfaceControl control;

    Nintendo3DsOwnedLayer(SurfaceView anchor, int width, int height) {
        requireMainThread();
        requireAnchor(anchor);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Positive target extent required");
        this.width = width;
        this.height = height;
        // Unparented until fully configured. setHidden() would unnecessarily require API33.
        control = new SurfaceControl.Builder().setName("EmuOrbit-3DS-owned-layer")
                .setBufferSize(width, height).setFormat(PixelFormat.RGBA_8888).setOpaque(true).build();
        Surface child;
        try { child = new Surface(control); }
        catch (RuntimeException | Error failure) { control.release(); throw failure; }
        surface = child;
        lifetime = new Nintendo3DsSurfaceLifetime(() -> {
            // Only actual native close AND completed captures may reach this callback.
            // A failed transaction retains resources rather than abandoning a live producer.
            try (var transaction = new SurfaceControl.Transaction()) {
                transaction.setVisibility(control, false).reparent(control, null).apply();
            }
            child.release();
            control.release();
        });
        try { attachTo(anchor); }
        catch (RuntimeException | Error failure) {
            try { lifetime.retire(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void attachTo(SurfaceView anchor) {
        requireMainThread();
        if (lifetime.isRetired()) throw new IllegalStateException("Retired 3DS target cannot be presented again");
        requireAnchor(anchor);
        try (var transaction = new SurfaceControl.Transaction()) {
            // Default child origin is 0,0; buffers are 1:1 with the anchor. Inherit its
            // crop/transform instead of calling API33-only setPosition/setCrop.
            transaction.reparent(control, anchor.getSurfaceControl()).setLayer(control, 1)
                    .setVisibility(control, true).apply();
        }
    }

    void detachAnchor() {
        requireMainThread();
        if (lifetime.isReleased()) return;
        try (var transaction = new SurfaceControl.Transaction()) {
            transaction.setVisibility(control, false).reparent(control, null).apply();
        }
    }

    void retire() {
        requireMainThread();
        try { detachAnchor(); }
        finally { lifetime.retire(); }
    }

    private static void requireAnchor(SurfaceView anchor) {
        if (anchor == null || anchor.getSurfaceControl() == null || !anchor.getSurfaceControl().isValid())
            throw new IllegalStateException("A live framework anchor is required");
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Anchor changes belong to the Android UI thread");
    }
}
