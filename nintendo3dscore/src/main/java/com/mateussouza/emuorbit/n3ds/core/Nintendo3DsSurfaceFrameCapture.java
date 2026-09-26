// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;

import java.util.Objects;

/** Captures only the frame presented by the Vulkan surface, never content storage. */
final class Nintendo3DsSurfaceFrameCapture {
    static final int MAXIMUM_EDGE_PIXELS = 2_048;

    interface Callback {
        void onCaptured(Bitmap bitmap);

        void onFailure(int result);
    }

    private final Handler callbackHandler;

    Nintendo3DsSurfaceFrameCapture() {
        this(new Handler(Looper.getMainLooper()));
    }

    Nintendo3DsSurfaceFrameCapture(Handler callbackHandler) {
        this.callbackHandler = Objects.requireNonNull(callbackHandler);
    }

    void capture(SurfaceView source, Callback callback) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(callback);
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        Surface surface = source.getHolder().getSurface();
        if (sourceWidth <= 0 || sourceHeight <= 0 || surface == null || !surface.isValid()) {
            callback.onFailure(PixelCopy.ERROR_SOURCE_INVALID);
            return;
        }

        int[] destinationSize = boundedSize(sourceWidth, sourceHeight);
        Bitmap destination;
        try {
            destination = Bitmap.createBitmap(
                    destinationSize[0],
                    destinationSize[1],
                    Bitmap.Config.ARGB_8888);
        } catch (IllegalArgumentException | OutOfMemoryError failure) {
            callback.onFailure(PixelCopy.ERROR_DESTINATION_INVALID);
            return;
        }

        try {
            PixelCopy.request(source, destination, result -> {
                if (result == PixelCopy.SUCCESS) {
                    callback.onCaptured(destination);
                } else {
                    destination.recycle();
                    callback.onFailure(result);
                }
            }, callbackHandler);
        } catch (RuntimeException failure) {
            destination.recycle();
            callback.onFailure(PixelCopy.ERROR_SOURCE_INVALID);
        }
    }

    static int[] boundedSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Capture dimensions must be positive.");
        }
        int longest = Math.max(width, height);
        if (longest <= MAXIMUM_EDGE_PIXELS) {
            return new int[]{width, height};
        }
        double scale = (double) MAXIMUM_EDGE_PIXELS / longest;
        return new int[]{
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale))};
    }
}
