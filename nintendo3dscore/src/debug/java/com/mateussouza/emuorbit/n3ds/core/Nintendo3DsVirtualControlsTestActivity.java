// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

import java.util.EnumMap;

/** Debug-only owner that verifies the virtual controls without loading a game or native core. */
public final class Nintendo3DsVirtualControlsTestActivity extends Activity {
    private final EnumMap<Nintendo3DsButton, Boolean> buttons =
            new EnumMap<>(Nintendo3DsButton.class);
    private Nintendo3DsVirtualControlsOverlay overlay;
    private float circleX;
    private float circleY;
    private float cStickX;
    private float cStickY;
    private int eventCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        overlay = new Nintendo3DsVirtualControlsOverlay(
                this,
                new Nintendo3DsVirtualControlsOverlay.InputSink() {
                    @Override
                    public void setButtonPressed(Nintendo3DsButton button, boolean pressed) {
                        buttons.put(button, pressed);
                        eventCount++;
                    }

                    @Override
                    public void setCirclePad(float horizontal, float vertical) {
                        circleX = horizontal;
                        circleY = vertical;
                        eventCount++;
                    }

                    @Override
                    public void setCStick(float horizontal, float vertical) {
                        cStickX = horizontal;
                        cStickY = vertical;
                        eventCount++;
                    }
                },
                Nintendo3DsVirtualControlsOverlay.Labels.forIsolatedTest(),
                false);
        overlay.setControlsEnabled(true);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    Nintendo3DsVirtualControlsOverlay getOverlay() {
        return overlay;
    }

    boolean isPressed(Nintendo3DsButton button) {
        return Boolean.TRUE.equals(buttons.get(button));
    }

    float getCircleX() {
        return circleX;
    }

    float getCircleY() {
        return circleY;
    }

    float getCStickX() {
        return cStickX;
    }

    float getCStickY() {
        return cStickY;
    }

    int getEventCount() {
        return eventCount;
    }
}
