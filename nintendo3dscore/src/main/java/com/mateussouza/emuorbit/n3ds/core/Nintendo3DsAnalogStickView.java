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
        guidePaint.setStrokeWidth(dp(1.25f));
        guidePaint.setColor(withAlpha(Color.WHITE, 95));
        knobPaint.setColor(withAlpha(Color.rgb(48, 48, 48), 220));
        setClickable(true);
        setFocusable(true);
    }

    void setOnAxesChangedListener(@Nullable OnAxesChangedListener listener) {
        this.listener = listener;
    }

    void reset() {
        activePointerId = NO_POINTER;
        updateAxes(0.0f, 0.0f);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float centerX = getWidth() / 2.0f;
        float centerY = getHeight() / 2.0f;
        float baseRadius = size * 0.36f;
        float knobRadius = size * 0.17f;
        float travel = Math.max(1.0f, baseRadius - knobRadius - dp(2.0f));
        float knobX = centerX + horizontal * travel;
        float knobY = centerY + vertical * travel;

        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius - dp(1.0f), ringPaint);
        canvas.drawLine(centerX - baseRadius * 0.62f, centerY,
                centerX + baseRadius * 0.62f, centerY, guidePaint);
        canvas.drawLine(centerX, centerY - baseRadius * 0.62f,
                centerX, centerY + baseRadius * 0.62f, guidePaint);
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint);
        canvas.drawCircle(knobX, knobY, knobRadius, ringPaint);
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
