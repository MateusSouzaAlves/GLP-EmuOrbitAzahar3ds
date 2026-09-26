// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.button;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.message;
import static com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMateusDialogTestActions.title;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

/** Rendered large-font and screen-reader contract for the isolated Nintendo 3DS UI. */
@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsAccessibilityInstrumentedTest {
    private static final float LARGE_FONT_SCALE = 1.3f;
    private static final String[] LANGUAGE_TAGS = {
            "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"
    };

    @Test
    public void rendersSettingsAndReadinessForTalkBackAtLargeFontInTenLocales() {
        Set<String> settingsTitles = new HashSet<>();
        Set<String> readinessTitles = new HashSet<>();
        try {
            for (String languageTag : LANGUAGE_TAGS) {
                assertSettingsDialog(languageTag, settingsTitles);
                assertReadinessDialog(languageTag, readinessTitles);
            }
        } finally {
            Nintendo3DsUiTestConfiguration.clear();
        }
        assertEquals(LANGUAGE_TAGS.length, settingsTitles.size());
        assertEquals(LANGUAGE_TAGS.length, readinessTitles.size());
    }

    private static void assertSettingsDialog(
            String languageTag,
            Set<String> localizedTitles) {
        Nintendo3DsUiTestConfiguration.set(languageTag, LARGE_FONT_SCALE);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, Nintendo3DsSettingsTestActivity.class);
        try (ActivityScenario<Nintendo3DsSettingsTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                String variant = languageTag + "/settings/font1.3";
                assertConfiguration(activity, languageTag, variant);
                AlertDialog dialog = activity.getDialogController()
                        .getActiveDialogForTesting();
                assertNotNull(variant, dialog);
                assertTrue(variant, dialog.isShowing());
                assertDialogRendered(dialog, variant);

                TextView title = requireDialogTitle(dialog, variant);
                assertEquals(activity.getString(R.string.n3ds_settings_global_title),
                        title.getText().toString());
                assertTrue(variant, localizedTitles.add(title.getText().toString()));
                assertTextNotEllipsized(title, variant);

                Nintendo3DsExperienceSettingsDialogController controller =
                        activity.getDialogController();
                Spinner layout = controller.getLayoutSpinnerForTesting();
                Spinner performance = controller.getPerformanceProfileSpinnerForTesting();
                CheckBox audio = controller.getAudioEnabledForTesting();
                TextView volumeLabel = controller.getAudioVolumeLabelForTesting();
                SeekBar volume = controller.getAudioVolumeForTesting();
                CheckBox microphone = controller.getMicrophoneEnabledForTesting();
                assertAccessibleAction(layout, variant + "/layout", false);
                assertAccessibleAction(performance, variant + "/performance", false);
                assertAccessibleAction(audio, variant + "/audio", true);
                assertAccessibleAction(microphone, variant + "/microphone", true);
                assertEquals(variant, volume.getId(), volumeLabel.getLabelFor());
                assertFalse(variant, TextUtils.isEmpty(volumeLabel.getText()));
                AccessibilityNodeInfo volumeNode = volume.createAccessibilityNodeInfo();
                try {
                    assertTrue(variant, volumeNode.isVisibleToUser());
                    assertTrue(variant, volumeNode.isEnabled());
                    assertNotNull(variant, volumeNode.getRangeInfo());
                } finally {
                    volumeNode.recycle();
                }
                assertTextNotEllipsized(audio, variant);
                assertTextNotEllipsized(volumeLabel, variant);
                assertTextNotEllipsized(microphone, variant);
                assertDialogButtonsAccessible(dialog, variant);
            });
        } finally {
            Nintendo3DsUiTestConfiguration.clear();
        }
    }

    private static void assertReadinessDialog(
            String languageTag,
            Set<String> localizedTitles) {
        Nintendo3DsUiTestConfiguration.set(languageTag, LARGE_FONT_SCALE);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, Nintendo3DsReadinessTestActivity.class)
                .putExtra(
                        Nintendo3DsReadinessTestActivity.EXTRA_SCENARIO,
                        Nintendo3DsReadinessTestActivity.SCENARIO_WARNING_MII);
        try (ActivityScenario<Nintendo3DsReadinessTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                String variant = languageTag + "/readiness/font1.3";
                assertConfiguration(activity, languageTag, variant);
                AlertDialog dialog = activity.getDialogController()
                        .getActiveDialogForTesting();
                assertNotNull(variant, dialog);
                assertTrue(variant, dialog.isShowing());
                assertDialogRendered(dialog, variant);

                TextView title = requireDialogTitle(dialog, variant);
                TextView message = message(dialog);
                assertNotNull(variant, message);
                assertEquals(activity.getString(R.string.n3ds_readiness_warning_title),
                        title.getText().toString());
                assertTrue(variant, localizedTitles.add(title.getText().toString()));
                assertEquals(
                        activity.getString(R.string.n3ds_readiness_mii_optional),
                        message.getText().toString());
                assertSpeakable(message, variant + "/message");
                assertTextNotEllipsized(title, variant);
                assertTextNotEllipsized(message, variant);
                assertDialogButtonsAccessible(dialog, variant);
            });
        } finally {
            Nintendo3DsUiTestConfiguration.clear();
        }
    }

    private static void assertConfiguration(
            Context context,
            String languageTag,
            String variant) {
        assertEquals(
                variant,
                languageTag,
                context.getResources().getConfiguration().getLocales().get(0).toLanguageTag());
        assertEquals(
                variant,
                LARGE_FONT_SCALE,
                context.getResources().getConfiguration().fontScale,
                0.001f);
        int expectedDirection = "ar".equals(languageTag)
                ? View.LAYOUT_DIRECTION_RTL
                : View.LAYOUT_DIRECTION_LTR;
        assertEquals(
                variant,
                expectedDirection,
                context.getResources().getConfiguration().getLayoutDirection());
    }

    private static void assertDialogRendered(AlertDialog dialog, String variant) {
        Window window = dialog.getWindow();
        assertNotNull(variant, window);
        View decor = window.getDecorView();
        assertTrue(variant + " width", decor.getWidth() > 0);
        assertTrue(variant + " height", decor.getHeight() > 0);
        assertTrue(variant + " horizontal overflow",
                decor.getWidth() <= decor.getResources().getDisplayMetrics().widthPixels);
        assertTrue(variant + " vertical overflow",
                decor.getHeight() <= decor.getResources().getDisplayMetrics().heightPixels);

        AccessibilityNodeInfo rootNode = decor.createAccessibilityNodeInfo();
        try {
            assertTrue(variant + " empty accessibility tree", rootNode.getChildCount() > 0);
        } finally {
            rootNode.recycle();
        }

        Bitmap rendered = Bitmap.createBitmap(
                decor.getWidth(),
                decor.getHeight(),
                Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(rendered));
        Set<Integer> sampledColors = new HashSet<>();
        int xStep = Math.max(1, rendered.getWidth() / 24);
        int yStep = Math.max(1, rendered.getHeight() / 24);
        for (int y = 0; y < rendered.getHeight(); y += yStep) {
            for (int x = 0; x < rendered.getWidth(); x += xStep) {
                sampledColors.add(rendered.getPixel(x, y));
            }
        }
        rendered.recycle();
        assertTrue(variant + " blank render", sampledColors.size() >= 4);
    }

    private static TextView requireDialogTitle(AlertDialog dialog, String variant) {
        TextView titleView = title(dialog);
        assertNotNull(variant, titleView);
        assertSpeakable(titleView, variant + "/title");
        return titleView;
    }

    private static void assertDialogButtonsAccessible(AlertDialog dialog, String variant) {
        for (int which : new int[] {
                DialogInterface.BUTTON_POSITIVE,
                DialogInterface.BUTTON_NEGATIVE,
                DialogInterface.BUTTON_NEUTRAL
        }) {
            Button action = button(dialog, which);
            if (action != null && action.getVisibility() == View.VISIBLE) {
                assertAccessibleAction(action, variant + "/button" + which, false);
                assertTextNotEllipsized(action, variant);
            }
        }
    }

    private static void assertAccessibleAction(
            View view,
            String variant,
            boolean checkable) {
        assertEquals(variant, View.VISIBLE, view.getVisibility());
        assertTrue(variant, view.isEnabled());
        assertTrue(variant, view.isClickable());
        assertTrue(variant, view.isFocusable());
        assertTrue(variant, view.isImportantForAccessibility());
        view.requestRectangleOnScreen(
                new Rect(0, 0, Math.max(1, view.getWidth()), Math.max(1, view.getHeight())),
                true);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        try {
            Rect visibleBounds = new Rect();
            boolean hasVisibleBounds = view.getGlobalVisibleRect(visibleBounds);
            assertTrue(
                    variant
                            + "/shown=" + view.isShown()
                            + "/attached=" + view.isAttachedToWindow()
                            + "/globalVisible=" + hasVisibleBounds
                            + "/bounds=" + visibleBounds,
                    node.isVisibleToUser());
            assertTrue(variant, node.isEnabled());
            assertTrue(variant, node.isClickable());
            assertEquals(variant, checkable, node.isCheckable());
            assertTrue(variant, hasSpeakableText(node));
        } finally {
            node.recycle();
        }
    }

    private static void assertSpeakable(View view, String variant) {
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        try {
            assertTrue(variant, node.isVisibleToUser());
            assertTrue(variant, hasSpeakableText(node));
        } finally {
            node.recycle();
        }
    }

    private static boolean hasSpeakableText(AccessibilityNodeInfo node) {
        return !TextUtils.isEmpty(node.getText())
                || !TextUtils.isEmpty(node.getContentDescription())
                || !TextUtils.isEmpty(node.getHintText());
    }

    private static void assertTextNotEllipsized(TextView view, String variant) {
        assertFalse(variant, TextUtils.isEmpty(view.getText()));
        assertNotNull(variant, view.getLayout());
        for (int line = 0; line < view.getLayout().getLineCount(); line++) {
            assertEquals(variant + " ellipsized", 0,
                    view.getLayout().getEllipsisCount(line));
        }
    }
}
