// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsVirtualControlsInstrumentedTest {
    @Test
    public void analogControlsUseTheApprovedLargerSizesWithoutChangingDigitalButtons() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, Nintendo3DsVirtualControlsTestActivity.class);
        try (ActivityScenario<Nintendo3DsVirtualControlsTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                float density = activity.getResources().getDisplayMetrics().density;
                Nintendo3DsVirtualControlsOverlay overlay = activity.getOverlay();
                assertSquareDp(overlay.getCirclePadForTesting(), density, 143);
                assertSquareDp(overlay.getCStickForTesting(), density, 89);
                assertSquareDp(
                        overlay.getControlForTesting(Nintendo3DsButton.UP),
                        density,
                        42);
                assertSquareDp(
                        overlay.getControlForTesting(Nintendo3DsButton.A),
                        density,
                        48);
            });
        }
    }

    @Test
    public void routesEveryDigitalControlAndResetsPressedState() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, Nintendo3DsVirtualControlsTestActivity.class);
        try (ActivityScenario<Nintendo3DsVirtualControlsTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Nintendo3DsVirtualControlsOverlay overlay = activity.getOverlay();
                for (Nintendo3DsButton button : Nintendo3DsButton.values()) {
                    View control = overlay.getControlForTesting(button);
                    assertNotNull(button.name(), control);
                    assertTrue(button.name(), control.isEnabled());
                    assertTrue(button.name(), control.isClickable());
                    AccessibilityNodeInfo node = control.createAccessibilityNodeInfo();
                    try {
                        assertTrue(button.name(),
                                !TextUtils.isEmpty(node.getText())
                                        || !TextUtils.isEmpty(node.getContentDescription()));
                    } finally {
                        node.recycle();
                    }
                }

                View a = overlay.getControlForTesting(Nintendo3DsButton.A);
                dispatch(a, MotionEvent.ACTION_DOWN, a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertTrue(activity.isPressed(Nintendo3DsButton.A));
                dispatch(a, MotionEvent.ACTION_UP, a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertFalse(activity.isPressed(Nintendo3DsButton.A));

                View zl = overlay.getControlForTesting(Nintendo3DsButton.ZL);
                dispatch(zl, MotionEvent.ACTION_DOWN, zl.getWidth() / 2.0f, zl.getHeight() / 2.0f);
                assertTrue(activity.isPressed(Nintendo3DsButton.ZL));
                overlay.reset();
                assertFalse(activity.isPressed(Nintendo3DsButton.ZL));
                assertTrue(activity.getEventCount() >= 4);
            });
        }
    }

    @Test
    public void routesContinuousCircleAndCStickThenNeutralizesThem() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, Nintendo3DsVirtualControlsTestActivity.class);
        try (ActivityScenario<Nintendo3DsVirtualControlsTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Nintendo3DsAnalogStickView circle = activity.getOverlay()
                        .getCirclePadForTesting();
                dispatch(circle, MotionEvent.ACTION_DOWN,
                        circle.getWidth() * 0.85f, circle.getHeight() * 0.25f);
                assertTrue(activity.getCircleX() > 0.0f);
                assertTrue(activity.getCircleY() < 0.0f);
                dispatch(circle, MotionEvent.ACTION_UP,
                        circle.getWidth() * 0.85f, circle.getHeight() * 0.25f);
                assertEquals(0.0f, activity.getCircleX(), 0.001f);
                assertEquals(0.0f, activity.getCircleY(), 0.001f);

                Nintendo3DsAnalogStickView cStick = activity.getOverlay().getCStickForTesting();
                dispatch(cStick, MotionEvent.ACTION_DOWN,
                        cStick.getWidth() * 0.15f, cStick.getHeight() * 0.75f);
                assertTrue(activity.getCStickX() < 0.0f);
                assertTrue(activity.getCStickY() > 0.0f);
                activity.getOverlay().setControlsEnabled(false);
                assertEquals(0.0f, activity.getCStickX(), 0.001f);
                assertEquals(0.0f, activity.getCStickY(), 0.001f);
                assertFalse(cStick.isEnabled());
            });
        }
    }

    @Test
    public void appliesIndependentVisibilityAndSteppedOpacityWithoutStuckInput() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, Nintendo3DsVirtualControlsTestActivity.class);
        try (ActivityScenario<Nintendo3DsVirtualControlsTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Nintendo3DsVirtualControlsOverlay overlay = activity.getOverlay();
                View a = overlay.getControlForTesting(Nintendo3DsButton.A);
                dispatch(a, MotionEvent.ACTION_DOWN,
                        a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertTrue(activity.isPressed(Nintendo3DsButton.A));

                overlay.applyPresentation(false, 44);

                assertFalse(activity.isPressed(Nintendo3DsButton.A));
                assertFalse(overlay.areControlsVisibleForTesting());
                assertEquals(40, overlay.getControlsOpacityPercentForTesting());

                overlay.applyPresentation(true, 73);
                overlay.setControlsEnabled(true);

                assertTrue(overlay.areControlsVisibleForTesting());
                assertEquals(70, overlay.getControlsOpacityPercentForTesting());
                assertTrue(a.isEnabled());
                dispatch(a, MotionEvent.ACTION_DOWN,
                        a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertTrue(a.isPressed());
                assertTrue(activity.isPressed(Nintendo3DsButton.A));
                dispatch(a, MotionEvent.ACTION_UP,
                        a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertFalse(a.isPressed());
                assertFalse(activity.isPressed(Nintendo3DsButton.A));
            });
        }
    }

    @Test
    public void appliesIndependentSafeOrientationProfilesAndHidesOnlyOneGroup() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, Nintendo3DsVirtualControlsTestActivity.class);
        try (ActivityScenario<Nintendo3DsVirtualControlsTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Nintendo3DsVirtualControlsOverlay overlay = activity.getOverlay();
                View actions = overlay.getGroupForTesting(
                        Nintendo3DsVirtualControlGroup.ACTIONS);
                assertNotNull(actions);
                View a = overlay.getControlForTesting(Nintendo3DsButton.A);
                dispatch(a, MotionEvent.ACTION_DOWN,
                        a.getWidth() / 2.0f, a.getHeight() / 2.0f);
                assertTrue(activity.isPressed(Nintendo3DsButton.A));

                Nintendo3DsVirtualControlProfile profile =
                        Nintendo3DsVirtualControlProfile.identity().withTransform(
                                Nintendo3DsVirtualControlOrientation.PORTRAIT,
                                Nintendo3DsVirtualControlGroup.ACTIONS,
                                new Nintendo3DsVirtualControlTransform(
                                        -0.8f,
                                        -0.6f,
                                        1.4f,
                                        false));
                overlay.applyLayoutProfile(
                        profile,
                        Nintendo3DsVirtualControlOrientation.PORTRAIT);
                assertFalse(activity.isPressed(Nintendo3DsButton.A));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Nintendo3DsVirtualControlsOverlay overlay = activity.getOverlay();
                View actions = overlay.getGroupForTesting(
                        Nintendo3DsVirtualControlGroup.ACTIONS);
                assertEquals(View.INVISIBLE, actions.getVisibility());
                assertEquals(1.4f, actions.getScaleX(), 0.001f);
                assertTrue(overlay.getGroupForTesting(
                        Nintendo3DsVirtualControlGroup.DIRECTIONAL).isShown());

                overlay.applyLayoutProfile(
                        Nintendo3DsVirtualControlProfile.identity(),
                        Nintendo3DsVirtualControlOrientation.LANDSCAPE);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View actions = activity.getOverlay().getGroupForTesting(
                        Nintendo3DsVirtualControlGroup.ACTIONS);
                assertEquals(View.VISIBLE, actions.getVisibility());
                assertEquals(1.0f, actions.getScaleX(), 0.001f);
                assertEquals(0.0f, actions.getTranslationX(), 0.001f);
                assertEquals(0.0f, actions.getTranslationY(), 0.001f);
            });
        }
    }

    private static void dispatch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            assertTrue(view.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static void assertSquareDp(View view, float density, int expectedDp) {
        int expectedPx = Math.round(expectedDp * density);
        assertEquals(expectedPx, view.getWidth());
        assertEquals(expectedPx, view.getHeight());
    }
}
