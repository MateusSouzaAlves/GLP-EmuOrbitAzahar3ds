// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.adreno.target;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Private Firebase host whose compositor consumes the Vulkan swapchain. */
public final class Nintendo3DsAdrenoSurfaceActivity extends Activity {
    public static final String EXTRA_WIDTH = "n3ds.surfaceWidth";
    public static final String EXTRA_HEIGHT = "n3ds.surfaceHeight";

    private static final int DEFAULT_WIDTH = 400;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int MAXIMUM_EXTENT = 4096;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        int width = boundedExtent(getIntent().getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH));
        int height = boundedExtent(getIntent().getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT));
        SurfaceView surfaceView = new SurfaceView(this);
        SurfaceHolder holder = surfaceView.getHolder();
        holder.setFixedSize(width, height);

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams surfaceLayout = new FrameLayout.LayoutParams(
                width,
                height,
                Gravity.CENTER);
        root.addView(surfaceView, surfaceLayout);
        setContentView(root);
    }

    private static int boundedExtent(int value) {
        if (value <= 0 || value > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("Invalid private QA surface extent");
        }
        return value;
    }
}
