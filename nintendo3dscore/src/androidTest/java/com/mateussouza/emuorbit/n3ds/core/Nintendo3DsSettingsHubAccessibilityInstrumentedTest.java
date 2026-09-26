// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pairwise physical gate for the programmatic 3DS settings/data hub. */
@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsSettingsHubAccessibilityInstrumentedTest {
    private static final float LARGE_FONT_SCALE = 1.3f;
    private static final String[] PHYSICAL_LANGUAGE_TAGS = {"pt-BR", "en", "ar"};
    private static final int[] ACTION_IDS = {
            R.id.n3ds_settings_hub_back,
            R.id.n3ds_settings_hub_open_settings,
            R.id.n3ds_settings_hub_import_mii,
            R.id.n3ds_settings_hub_create_backup,
            R.id.n3ds_settings_hub_restore_backup,
            R.id.n3ds_settings_hub_export_portable,
            R.id.n3ds_settings_hub_import_portable
    };

    @Test
    public void responsiveBreakpointMatchesTheAutomatedWidthMatrix() {
        assertTrue(Nintendo3DsSettingsActivity.shouldStackDataActions(320));
        assertFalse(Nintendo3DsSettingsActivity.shouldStackDataActions(640));
        assertFalse(Nintendo3DsSettingsActivity.shouldStackDataActions(800));
    }

    @Test
    public void largeFontHubIsReadableFocusableAndRtlAwareInPhysicalLocales() {
        Set<String> localizedIntroductions = new HashSet<>();
        try {
            for (String languageTag : PHYSICAL_LANGUAGE_TAGS) {
                assertHub(languageTag, localizedIntroductions);
            }
        } finally {
            Nintendo3DsUiTestConfiguration.clear();
        }
        assertEquals(PHYSICAL_LANGUAGE_TAGS.length, localizedIntroductions.size());
    }

    private static void assertHub(
            String languageTag,
            Set<String> localizedIntroductions) {
        Nintendo3DsUiTestConfiguration.set(languageTag, LARGE_FONT_SCALE);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(
                context,
                Nintendo3DsSettingsAccessibilityTestActivity.class);
        try (ActivityScenario<Nintendo3DsSettingsAccessibilityTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                String variant = languageTag + "/settings-hub/font1.3";
                Locale observedLocale = activity.getResources().getConfiguration()
                        .getLocales().get(0);
                assertEquals(variant, languageTag, observedLocale.toLanguageTag());
                assertEquals(
                        variant,
                        LARGE_FONT_SCALE,
                        activity.getResources().getConfiguration().fontScale,
                        0.001f);

                View root = activity.findViewById(android.R.id.content);
                assertNotNull(variant, root);
                assertTrue(variant, root instanceof ViewGroup);
                View scroll = ((ViewGroup) root).getChildAt(0);
                WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(root);
                assertNotNull(variant + "/window-insets", windowInsets);
                Insets systemBars = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars());
                assertEquals(variant + "/status-inset", systemBars.top,
                        scroll.getPaddingTop());
                assertEquals(variant + "/navigation-inset", systemBars.bottom,
                        scroll.getPaddingBottom());
                int expectedDirection = "ar".equals(languageTag)
                        ? View.LAYOUT_DIRECTION_RTL
                        : View.LAYOUT_DIRECTION_LTR;
                assertEquals(variant, expectedDirection, root.getLayoutDirection());

                TextView title = activity.findViewById(R.id.n3ds_settings_hub_title);
                assertNotNull(variant, title);
                assertEquals(
                        variant,
                        activity.getString(R.string.n3ds_settings_hub_title),
                        title.getText().toString());
                assertTrue(
                        variant,
                        localizedIntroductions.add(
                                activity.getString(R.string.n3ds_settings_hub_intro)));
                assertTrue(variant, title.isAccessibilityHeading());
                assertTextNotEllipsized(title, variant + "/title");

                boolean stacked = Nintendo3DsSettingsActivity.shouldStackDataActions(
                        activity.getResources().getConfiguration().screenWidthDp);
                assertDataActionOrientation(
                        activity.findViewById(R.id.n3ds_settings_hub_create_backup),
                        stacked,
                        variant + "/checkpoint-actions");
                assertDataActionOrientation(
                        activity.findViewById(R.id.n3ds_settings_hub_export_portable),
                        stacked,
                        variant + "/portable-actions");

                for (int actionId : ACTION_IDS) {
                    Button action = activity.findViewById(actionId);
                    assertAccessibleAction(action, root, variant + "/" + actionId);
                }
                assertForwardFocusOrder(activity, variant);
            });
            if ("pt-BR".equals(languageTag) || "ar".equals(languageTag)) {
                scenario.onActivity(activity -> {
                    View dataAction = activity.findViewById(
                            R.id.n3ds_settings_hub_create_backup);
                    assertNotNull(dataAction);
                    dataAction.requestFocus();
                    dataAction.requestRectangleOnScreen(
                            new Rect(0, 0, dataAction.getWidth(), dataAction.getHeight()),
                            false);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(250L);
                writeScreenshot(context, "n3ds-settings-hub-" + languageTag + ".png");
            }
        } finally {
            Nintendo3DsUiTestConfiguration.clear();
        }
    }

    private static void assertDataActionOrientation(
            Button firstAction,
            boolean stacked,
            String variant) {
        assertNotNull(variant, firstAction);
        assertTrue(variant, firstAction.getParent() instanceof LinearLayout);
        LinearLayout parent = (LinearLayout) firstAction.getParent();
        assertEquals(
                variant,
                stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL,
                parent.getOrientation());
    }

    private static void assertAccessibleAction(
            Button action,
            View root,
            String variant) {
        assertNotNull(variant, action);
        assertEquals(variant, View.VISIBLE, action.getVisibility());
        assertTrue(variant, action.isClickable());
        assertTrue(variant, action.isFocusable());
        assertTrue(variant, action.isImportantForAccessibility());
        assertFalse(variant, TextUtils.isEmpty(action.getText()));
        int minimum = Math.round(48 * action.getResources().getDisplayMetrics().density);
        assertTrue(variant + "/width", action.getWidth() >= minimum);
        assertTrue(variant + "/height", action.getHeight() >= minimum);
        assertHorizontalBoundsInside(action, root, variant);
        assertTextNotEllipsized(action, variant);

        AccessibilityNodeInfo node = action.createAccessibilityNodeInfo();
        try {
            assertEquals(variant, action.isEnabled(), node.isEnabled());
            assertTrue(variant, node.isClickable());
            assertTrue(variant, !TextUtils.isEmpty(node.getText())
                    || !TextUtils.isEmpty(node.getContentDescription()));
        } finally {
            node.recycle();
        }
    }

    private static void assertHorizontalBoundsInside(
            View child,
            View root,
            String variant) {
        int left = 0;
        View current = child;
        while (current != root) {
            left += current.getLeft();
            Object parent = current.getParent();
            assertTrue(variant + "/detached", parent instanceof View);
            current = (View) parent;
        }
        assertTrue(variant + "/left-overflow", left >= 0);
        assertTrue(
                variant + "/right-overflow",
                left + child.getWidth() <= root.getWidth());
    }

    private static void assertForwardFocusOrder(
            Nintendo3DsSettingsAccessibilityTestActivity activity,
            String variant) {
        for (int index = 0; index < ACTION_IDS.length - 1; index++) {
            View current = activity.findViewById(ACTION_IDS[index]);
            View next = activity.findViewById(ACTION_IDS[index + 1]);
            assertNotNull(variant + "/focus-current-" + index, current);
            assertNotNull(variant + "/focus-next-" + index, next);
            assertEquals(
                    variant + "/focus-" + index,
                    ACTION_IDS[index + 1],
                    current.getNextFocusForwardId());
            assertEquals(
                    variant + "/talkback-order-" + index,
                    ACTION_IDS[index],
                    next.getAccessibilityTraversalAfter());
        }
    }

    private static void assertTextNotEllipsized(TextView view, String variant) {
        assertNotNull(variant, view.getLayout());
        for (int line = 0; line < view.getLayout().getLineCount(); line++) {
            assertEquals(
                    variant + "/ellipsized-line-" + line,
                    0,
                    view.getLayout().getEllipsisCount(line));
        }
    }

    private static void writeScreenshot(Context context, String name) {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .takeScreenshot();
        assertNotNull(name, screenshot);
        File directory = new File(context.getExternalFilesDir(null), "ux3ds07a");
        assertTrue(name, directory.isDirectory() || directory.mkdirs());
        File destination = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            assertTrue(name, screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (IOException exception) {
            throw new AssertionError("Could not write " + destination, exception);
        } finally {
            screenshot.recycle();
        }
        System.out.println("N3DS_UX_CAPTURE " + destination.getAbsolutePath());
    }
}
