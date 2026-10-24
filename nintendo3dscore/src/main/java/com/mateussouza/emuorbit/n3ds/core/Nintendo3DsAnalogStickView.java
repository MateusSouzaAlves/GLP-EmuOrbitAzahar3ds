// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Fixed-center analog control shared by the virtual circle pad and C-stick. */
final class Nintendo3DsAnalogStickView extends View {
    private static final int NO_POINTER = -1;
    private static final float VISUAL_SCALE = 1.10f;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnAxesChangedListener listener;
    private float horizontal;
    private float vertical;
    private int activePointerId = NO_POINTER;

    Nintendo3DsAnalogStickView(@NonNull Context context) {
        super(context);
        basePaint.setColor(withAlpha(Color.BLACK, 160));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2.0f));
        ringPaint.setColor(withAlpha(Color.WHITE, 180));
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeCap(Paint.Cap.ROUND);
        guidePaint.setColor(withAlpha(Color.WHITE, 210));
        knobPaint.setColor(withAlpha(Color.rgb(48, 48, 48), 220));
        setClickable(true);
        setFocusable(true);
    }

    void setOnAxesChangedListener(@Nullable OnAxesChangedListener listener) {
        this.listener = listener;
    }

    void reset() {
        activePointerId = NO_POINTER;
        setPressed(false);
        updateAxes(0.0f, 0.0f);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float centerX = getWidth() / 2.0f;
        float centerY = getHeight() / 2.0f;
        // Grow only the artwork within the existing hit area: input normalization and
        // the lower-screen touch region must not change for Circle Pad or C-stick.
        float baseRadius = size * 0.36f * VISUAL_SCALE;
        float knobRadius = size * 0.17f * VISUAL_SCALE;
        float travel = Math.max(1.0f, size * (0.36f - 0.17f) - dp(2.0f));
        float knobX = centerX + horizontal * travel;
        float knobY = centerY + vertical * travel;

        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius - dp(1.0f), ringPaint);
        drawDirectionArrows(canvas, centerX, centerY, baseRadius);
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint);
        canvas.drawCircle(knobX, knobY, knobRadius, ringPaint);
    }

    private void drawDirectionArrows(Canvas canvas, float centerX, float centerY, float radius) {
        guidePaint.setStrokeWidth(radius * 0.035f);
        int checkpoint = canvas.save();
        canvas.translate(centerX, centerY);
        for (int direction = 0; direction < 4; direction++) {
            canvas.drawLine(-radius * 0.10f, -radius * 0.67f,
                    0, -radius * 0.82f, guidePaint);
            canvas.drawLine(0, -radius * 0.82f,
                    radius * 0.10f, -radius * 0.67f, guidePaint);
            canvas.rotate(90);
        }
        canvas.restoreToCount(checkpoint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointerId = event.getPointerId(event.getActionIndex());
            getParent().requestDisallowInterceptTouchEvent(true);
            updateFromPointer(event, event.getActionIndex());
            setPressed(true);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && activePointerId != NO_POINTER) {
            int pointerIndex = event.findPointerIndex(activePointerId);
            if (pointerIndex >= 0) {
                updateFromPointer(event, pointerIndex);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP) {
                performClick();
            }
            setPressed(false);
            reset();
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (!enabled) {
            reset();
            setPressed(false);
        }
        super.setEnabled(enabled);
    }

    @Override
    protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    private void updateFromPointer(MotionEvent event, int pointerIndex) {
        Nintendo3DsAnalogStickMath.Axes axes = Nintendo3DsAnalogStickMath.resolve(
                event.getX(pointerIndex), event.getY(pointerIndex), getWidth(), getHeight());
        updateAxes(axes.horizontal, axes.vertical);
    }

    private void updateAxes(float nextHorizontal, float nextVertical) {
        if (Float.compare(horizontal, nextHorizontal) == 0
                && Float.compare(vertical, nextVertical) == 0) {
            return;
        }
        horizontal = nextHorizontal;
        vertical = nextVertical;
        if (listener != null) {
            listener.onAxesChanged(horizontal, vertical);
        }
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    interface OnAxesChangedListener {
        void onAxesChanged(float horizontal, float vertical);
    }
}
