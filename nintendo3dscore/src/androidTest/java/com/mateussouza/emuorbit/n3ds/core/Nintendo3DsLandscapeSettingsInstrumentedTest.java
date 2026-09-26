// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

/** Focal regression for QA3DS-01: every 3DS setting remains reachable in landscape. */
@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsLandscapeSettingsInstrumentedTest {
    private static final float LARGE_FONT_SCALE = 1.3f;

    @Test
    public void landscapeFormReachesOpacityInLargeFontLtrAndRtl() {
        assertLastControlReachable("pt-BR", View.LAYOUT_DIRECTION_LTR);
        assertLastControlReachable("ar", View.LAYOUT_DIRECTION_RTL);
    }

    private static void assertLastControlReachable(
            String languageTag,
            int expectedLayoutDirection) {
        Nintendo3DsUiTestConfiguration.set(languageTag, LARGE_FONT_SCALE);
        Intent intent = new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                Nintendo3DsSettingsTestActivity.class);
        intent.putExtra(Nintendo3DsSettingsTestActivity.EXTRA_SKIP_AUTOMATIC_DIALOG, true);
        try (ActivityScenario<Nintendo3DsSettingsTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            awaitLandscape(scenario);

            scenario.onActivity(activity -> {
                NestedScrollView form = Nintendo3DsExperienceSettingsDialogController
                        .createFormScrollViewForTesting(activity);
                form.setTag("qa3ds-settings-form");
                FrameLayout host = new FrameLayout(activity);
                activity.setContentView(host);
                host.addView(form, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        form.getLayoutParams().height));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            int[] swipe = new int[4];
            scenario.onActivity(activity -> {
                NestedScrollView form = activity.getWindow().getDecorView()
                        .findViewWithTag("qa3ds-settings-form");
                assertEquals(expectedLayoutDirection, form.getLayoutDirection());
                assertTrue("The compact landscape form must have a scroll range",
                        form.canScrollVertically(1));
                int[] location = new int[2];
                form.getLocationOnScreen(location);
                swipe[0] = location[0] + Math.max(4, form.getPaddingLeft() / 2);
                swipe[1] = location[1] + form.getHeight() - 12;
                swipe[2] = swipe[0];
                swipe[3] = location[1] + 12;
            });
            assertTrue("The physical vertical gesture must be injected",
                    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                            .swipe(swipe[0], swipe[1], swipe[2], swipe[3], 24));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(activity -> {
                NestedScrollView form = activity.getWindow().getDecorView()
                        .findViewWithTag("qa3ds-settings-form");
                TextView opacityLabel = findOpacityLabel(form);
                Rect opacityBounds = new Rect();
                opacityLabel.getDrawingRect(opacityBounds);
                form.offsetDescendantRectToMyCoords(opacityLabel, opacityBounds);

                assertTrue("The form must move away from its initial position",
                        form.getScrollY() > 0);
                assertTrue("The opacity control must be inside the visible viewport",
                        opacityBounds.top >= form.getScrollY()
                                && opacityBounds.bottom <= form.getScrollY() + form.getHeight());
                assertTrue("The form must remain attached after the gesture", form.isShown());
            });
        } finally {
            Nintendo3DsUiTestConfiguration.clear();
        }
    }

    private static TextView findOpacityLabel(NestedScrollView form) {
        String prefix = form.getContext().getString(
                R.string.n3ds_settings_control_opacity,
                Nintendo3DsExperienceSettings.DEFAULT_VIRTUAL_CONTROL_OPACITY);
        TextView match = findTextView(form, prefix);
        assertTrue("The opacity label must exist in the form", match != null);
        return match;
    }

    private static TextView findTextView(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (!(view instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView found = findTextView(group.getChildAt(index), text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void awaitLandscape(
            ActivityScenario<Nintendo3DsSettingsTestActivity> scenario) {
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        AtomicBoolean landscape = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> landscape.set(
                    activity.getResources().getConfiguration().orientation
                            == Configuration.ORIENTATION_LANDSCAPE));
            if (landscape.get()) {
                return;
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("The settings host did not enter landscape", landscape.get());
    }
}
