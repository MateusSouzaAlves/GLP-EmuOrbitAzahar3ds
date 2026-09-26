// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.animation.AnimatorInflater;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Touch controls for the 3DS frontend input contract.
 *
 * <p>The overlay contains only bounded child hit targets. Its transparent gaps return control to
 * the SurfaceView below, keeping the lower 3DS screen touch-capable.</p>
 */
final class Nintendo3DsVirtualControlsOverlay extends FrameLayout {
    private static final int CIRCLE_PAD_SIZE_DP = 143;
    private static final int C_STICK_SIZE_DP = 89;

    interface InputSink {
        void setButtonPressed(Nintendo3DsButton button, boolean pressed);
        void setCirclePad(float horizontal, float vertical);
        void setCStick(float horizontal, float vertical);
    }

    private final InputSink sink;
    private final Labels labels;
    private final boolean packagedResourcesAvailable;
    private final EnumMap<Nintendo3DsButton, DigitalButtonBinding> buttonBindings =
            new EnumMap<>(Nintendo3DsButton.class);
    private final EnumMap<Nintendo3DsVirtualControlGroup, View> groupViews =
            new EnumMap<>(Nintendo3DsVirtualControlGroup.class);
    private final List<View> controls = new ArrayList<>();
    private final Nintendo3DsAnalogStickView circlePad;
    private final Nintendo3DsAnalogStickView cStick;
    @Nullable
    private View.OnLayoutChangeListener profileLayoutListener;

    Nintendo3DsVirtualControlsOverlay(@NonNull Context context, @NonNull InputSink sink) {
        this(context, sink, Labels.from(context), true);
    }

    Nintendo3DsVirtualControlsOverlay(
            @NonNull Context context,
            @NonNull InputSink sink,
            @NonNull Labels labels,
            boolean packagedResourcesAvailable) {
        super(context);
        this.sink = sink;
        this.labels = labels;
        this.packagedResourcesAvailable = packagedResourcesAvailable;
        setClipChildren(false);
        setClipToPadding(false);
        setMotionEventSplittingEnabled(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        circlePad = createAnalog(
                labels.circlePad,
                sink::setCirclePad);
        addAnchored(circlePad,
                CIRCLE_PAD_SIZE_DP,
                CIRCLE_PAD_SIZE_DP,
                Gravity.BOTTOM | Gravity.START,
                8,
                0,
                0,
                12);
        groupViews.put(Nintendo3DsVirtualControlGroup.CIRCLE_PAD, circlePad);

        cStick = createAnalog(labels.cStick, sink::setCStick);
        addAnchored(cStick,
                C_STICK_SIZE_DP,
                C_STICK_SIZE_DP,
                Gravity.BOTTOM | Gravity.END,
                0,
                0,
                31,
                132);
        groupViews.put(Nintendo3DsVirtualControlGroup.C_STICK, cStick);

        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        addDpad(landscape ? 82 : 11);
        addActionButtons();
        addSystemButtons(landscape ? 18 : 142);
        int topControlOffset = landscape ? 70 : 124;
        addShoulderButtons(topControlOffset);
        Button swap = createButton("⇅", labels.swapScreens,
                Nintendo3DsButton.HOME_SWAP_SCREENS, 17.0f);
        addAnchored(swap, 54, 34, Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                0, topControlOffset, 0, 0);
        groupViews.put(Nintendo3DsVirtualControlGroup.SCREEN_SWAP, swap);
        setControlsEnabled(false);
    }

    void setControlsEnabled(boolean enabled) {
        if (!enabled) {
            reset();
        }
        for (View control : controls) {
            control.setEnabled(enabled);
        }
    }

    void applyPresentation(boolean visible, int opacityPercent) {
        int normalizedOpacity = Nintendo3DsExperienceSettings
                .normalizeVirtualControlOpacity(opacityPercent);
        if (!visible) {
            reset();
        }
        setAlpha(normalizedOpacity / 100.0f);
        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    boolean areControlsVisibleForTesting() {
        return getVisibility() == View.VISIBLE;
    }

    int getControlsOpacityPercentForTesting() {
        return Math.round(getAlpha() * 100.0f);
    }

    void applyLayoutProfile(
            Nintendo3DsVirtualControlProfile profile,
            Nintendo3DsVirtualControlOrientation orientation) {
        if (profile == null || orientation == null) {
            throw new IllegalArgumentException("A complete 3DS control profile is required");
        }
        reset();
        detachProfileLayoutListener();
        profileLayoutListener = (view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> applyLayoutProfileNow(
                        profile,
                        orientation);
        addOnLayoutChangeListener(profileLayoutListener);
        post(() -> applyLayoutProfileNow(profile, orientation));
    }

    View getGroupForTesting(Nintendo3DsVirtualControlGroup group) {
        return groupViews.get(group);
    }

    void reset() {
        for (DigitalButtonBinding binding : buttonBindings.values()) {
            binding.release();
        }
        circlePad.reset();
        cStick.reset();
    }

    View getControlForTesting(Nintendo3DsButton button) {
        DigitalButtonBinding binding = buttonBindings.get(button);
        return binding == null ? null : binding.view;
    }

    Nintendo3DsAnalogStickView getCirclePadForTesting() {
        return circlePad;
    }

    Nintendo3DsAnalogStickView getCStickForTesting() {
        return cStick;
    }

    private Nintendo3DsAnalogStickView createAnalog(
            String contentDescription,
            Nintendo3DsAnalogStickView.OnAxesChangedListener listener) {
        Nintendo3DsAnalogStickView view = new Nintendo3DsAnalogStickView(getContext());
        view.setContentDescription(contentDescription);
        view.setOnAxesChangedListener(listener);
        controls.add(view);
        return view;
    }

    private void addDpad(int startOffsetDp) {
        FrameLayout cluster = new FrameLayout(getContext());
        addAnchored(cluster, 126, 126, Gravity.BOTTOM | Gravity.START,
                startOffsetDp, 0, 0, 140);
        groupViews.put(Nintendo3DsVirtualControlGroup.DIRECTIONAL, cluster);
        addClusterButton(cluster, "↑",
                labels.up,
                Nintendo3DsButton.UP, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 42, 42);
        addClusterButton(cluster, "↓",
                labels.down,
                Nintendo3DsButton.DOWN, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 42, 42);
        addClusterButton(cluster, "←",
                labels.left,
                Nintendo3DsButton.LEFT, Gravity.START | Gravity.CENTER_VERTICAL, 42, 42);
        addClusterButton(cluster, "→",
                labels.right,
                Nintendo3DsButton.RIGHT, Gravity.END | Gravity.CENTER_VERTICAL, 42, 42);
    }

    private void addActionButtons() {
        FrameLayout cluster = new FrameLayout(getContext());
        addAnchored(cluster, 136, 120, Gravity.BOTTOM | Gravity.END, 0, 0, 8, 12);
        groupViews.put(Nintendo3DsVirtualControlGroup.ACTIONS, cluster);
        addClusterButton(cluster, "X",
                labels.x,
                Nintendo3DsButton.X, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 48, 48);
        addClusterButton(cluster, "Y",
                labels.y,
                Nintendo3DsButton.Y, Gravity.START | Gravity.CENTER_VERTICAL, 48, 48);
        addClusterButton(cluster, "A",
                labels.a,
                Nintendo3DsButton.A, Gravity.END | Gravity.CENTER_VERTICAL, 48, 48);
        addClusterButton(cluster, "B",
                labels.b,
                Nintendo3DsButton.B, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 48, 48);
    }

    private void addSystemButtons(int bottomOffsetDp) {
        LinearLayout cluster = new LinearLayout(getContext());
        cluster.setOrientation(LinearLayout.HORIZONTAL);
        cluster.setGravity(Gravity.CENTER);
        addAnchored(cluster, ViewGroup.LayoutParams.WRAP_CONTENT, 34,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, bottomOffsetDp);
        groupViews.put(Nintendo3DsVirtualControlGroup.SYSTEM_BUTTONS, cluster);
        Button select = createButton(
                "SELECT",
                labels.select,
                Nintendo3DsButton.SELECT,
                10.0f);
        cluster.addView(select, linearParams(54, 30, 0));
        Button start = createButton(
                "START",
                labels.start,
                Nintendo3DsButton.START,
                10.0f);
        cluster.addView(start, linearParams(54, 30, 6));
    }

    private void addShoulderButtons(int topOffsetDp) {
        LinearLayout left = new LinearLayout(getContext());
        addAnchored(left, ViewGroup.LayoutParams.WRAP_CONTENT, 36,
                Gravity.TOP | Gravity.START, 8, topOffsetDp, 0, 0);
        groupViews.put(Nintendo3DsVirtualControlGroup.LEFT_SHOULDERS, left);
        left.addView(createButton(
                "L",
                labels.l,
                Nintendo3DsButton.L,
                13.0f), linearParams(54, 34, 0));
        left.addView(createButton("ZL", labels.zl,
                Nintendo3DsButton.ZL, 12.0f), linearParams(54, 34, 5));

        LinearLayout right = new LinearLayout(getContext());
        addAnchored(right, ViewGroup.LayoutParams.WRAP_CONTENT, 36,
                Gravity.TOP | Gravity.END, 0, topOffsetDp, 8, 0);
        groupViews.put(Nintendo3DsVirtualControlGroup.RIGHT_SHOULDERS, right);
        right.addView(createButton("ZR", labels.zr,
                Nintendo3DsButton.ZR, 12.0f), linearParams(54, 34, 0));
        right.addView(createButton(
                "R",
                labels.r,
                Nintendo3DsButton.R,
                13.0f), linearParams(54, 34, 5));
    }

    private void addClusterButton(
            FrameLayout parent,
            String text,
            String contentDescription,
            Nintendo3DsButton button,
            int gravity,
            int widthDp,
            int heightDp) {
        Button view = createButton(text, contentDescription, button, 16.0f);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                dp(widthDp), dp(heightDp), gravity);
        parent.addView(view, layout);
    }

    private Button createButton(
            String text,
            String contentDescription,
            Nintendo3DsButton button,
            float textSizeSp) {
        Button view = new Button(getContext());
        view.setText(text);
        view.setTextSize(textSizeSp);
        view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 0, 0, 0);
        view.setMinimumWidth(0);
        view.setMinimumHeight(0);
        view.setContentDescription(contentDescription);
        if (packagedResourcesAvailable) {
            view.setBackgroundResource(R.drawable.n3ds_control_background);
            view.setStateListAnimator(AnimatorInflater.loadStateListAnimator(
                    getContext(), R.animator.n3ds_control_elevation));
        } else {
            view.setBackgroundColor(Color.rgb(48, 48, 48));
        }
        DigitalButtonBinding binding = new DigitalButtonBinding(view, button);
        buttonBindings.put(button, binding);
        controls.add(view);
        return view;
    }

    private LinearLayout.LayoutParams linearParams(int widthDp, int heightDp, int startMarginDp) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        layout.setMarginStart(dp(startMarginDp));
        return layout;
    }

    private void addAnchored(
            View view,
            int widthDp,
            int heightDp,
            int gravity,
            int startDp,
            int topDp,
            int endDp,
            int bottomDp) {
        int width = widthDp < 0 ? widthDp : dp(widthDp);
        int height = heightDp < 0 ? heightDp : dp(heightDp);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(width, height, gravity);
        layout.setMargins(dp(startDp), dp(topDp), dp(endDp), dp(bottomDp));
        addView(view, layout);
    }

    private void applyLayoutProfileNow(
            Nintendo3DsVirtualControlProfile profile,
            Nintendo3DsVirtualControlOrientation orientation) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        for (Map.Entry<Nintendo3DsVirtualControlGroup, View> entry
                : groupViews.entrySet()) {
            View group = entry.getValue();
            if (group.getWidth() <= 0 || group.getHeight() <= 0) {
                continue;
            }
            Nintendo3DsVirtualControlTransform requested = profile.getTransform(
                    orientation,
                    entry.getKey());
            Nintendo3DsVirtualControlGeometry.AppliedTransform applied =
                    Nintendo3DsVirtualControlGeometry.calculate(
                            getWidth(),
                            getHeight(),
                            getPaddingLeft(),
                            getPaddingTop(),
                            getPaddingRight(),
                            getPaddingBottom(),
                            group.getLeft(),
                            group.getTop(),
                            group.getWidth(),
                            group.getHeight(),
                            requested);
            group.setPivotX(group.getWidth() / 2.0f);
            group.setPivotY(group.getHeight() / 2.0f);
            group.setScaleX(applied.getScale());
            group.setScaleY(applied.getScale());
            group.setTranslationX(applied.getTranslationX());
            group.setTranslationY(applied.getTranslationY());
            group.setVisibility(requested.isVisible() ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void detachProfileLayoutListener() {
        if (profileLayoutListener == null) {
            return;
        }
        removeOnLayoutChangeListener(profileLayoutListener);
        profileLayoutListener = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static final class Labels {
        final String circlePad;
        final String cStick;
        final String up;
        final String down;
        final String left;
        final String right;
        final String a;
        final String b;
        final String x;
        final String y;
        final String l;
        final String r;
        final String zl;
        final String zr;
        final String select;
        final String start;
        final String swapScreens;

        private Labels(String... values) {
            if (values.length != 17) {
                throw new IllegalArgumentException("The 3DS virtual controls require 17 labels.");
            }
            circlePad = values[0];
            cStick = values[1];
            up = values[2];
            down = values[3];
            left = values[4];
            right = values[5];
            a = values[6];
            b = values[7];
            x = values[8];
            y = values[9];
            l = values[10];
            r = values[11];
            zl = values[12];
            zr = values[13];
            select = values[14];
            start = values[15];
            swapScreens = values[16];
        }

        static Labels from(Context context) {
            return new Labels(
                    context.getString(R.string.n3ds_control_circle_pad),
                    context.getString(R.string.n3ds_control_c_stick),
                    context.getString(R.string.n3ds_control_up),
                    context.getString(R.string.n3ds_control_down),
                    context.getString(R.string.n3ds_control_left),
                    context.getString(R.string.n3ds_control_right),
                    context.getString(R.string.n3ds_control_a),
                    context.getString(R.string.n3ds_control_b),
                    context.getString(R.string.n3ds_control_x),
                    context.getString(R.string.n3ds_control_y),
                    context.getString(R.string.n3ds_control_l),
                    context.getString(R.string.n3ds_control_r),
                    context.getString(R.string.n3ds_control_zl),
                    context.getString(R.string.n3ds_control_zr),
                    context.getString(R.string.n3ds_control_select),
                    context.getString(R.string.n3ds_control_start),
                    context.getString(R.string.n3ds_control_swap_screens));
        }

        static Labels forIsolatedTest() {
            return new Labels(
                    "Circle Pad", "C-Stick", "Up", "Down", "Left", "Right",
                    "A button", "B button", "X button", "Y button", "L button", "R button",
                    "ZL button", "ZR button", "Select", "Start", "Swap screens");
        }
    }

    private final class DigitalButtonBinding implements OnTouchListener, OnClickListener {
        private static final long ACCESSIBILITY_PULSE_MILLIS = 80;

        private final Button view;
        private final Nintendo3DsButton button;
        private final Runnable delayedRelease = this::release;
        private boolean pressed;
        private boolean suppressClick;

        DigitalButtonBinding(Button view, Nintendo3DsButton button) {
            this.view = view;
            this.button = button;
            view.setOnTouchListener(this);
            view.setOnClickListener(this);
        }

        @Override
        public boolean onTouch(View ignored, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                setPressed(true);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                setPressed(false);
                if (action == MotionEvent.ACTION_UP) {
                    suppressClick = true;
                    view.performClick();
                    suppressClick = false;
                }
                return true;
            }
            return true;
        }

        @Override
        public void onClick(View ignored) {
            if (suppressClick || !view.isEnabled()) {
                return;
            }
            setPressed(true);
            view.removeCallbacks(delayedRelease);
            view.postDelayed(delayedRelease, ACCESSIBILITY_PULSE_MILLIS);
        }

        void release() {
            view.removeCallbacks(delayedRelease);
            setPressed(false);
        }

        private void setPressed(boolean nextPressed) {
            view.setPressed(nextPressed);
            if (pressed == nextPressed) {
                return;
            }
            pressed = nextPressed;
            sink.setButtonPressed(button, nextPressed);
        }
    }
}
