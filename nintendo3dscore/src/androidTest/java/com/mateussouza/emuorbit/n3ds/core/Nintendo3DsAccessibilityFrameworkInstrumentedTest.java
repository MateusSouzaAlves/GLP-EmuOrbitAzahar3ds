// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static org.hamcrest.Matchers.any;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.accessibility.AccessibilityChecks;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** Official Accessibility Test Framework gate for the remaining 3DS surfaces. */
@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsAccessibilityFrameworkInstrumentedTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000L;
    private static final String HASH_A =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String HASH_B =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String PERSISTENT_ID = "n3ds-v1:" + HASH_A + ":" + HASH_B;

    @Before
    public void enableAccessibilityFrameworkChecks() {
        AccessibilityChecks.enable().setRunChecksFromRootView(true);
    }

    @After
    public void restoreAccessibilityFrameworkChecks() {
        AccessibilityChecks.disable();
        Nintendo3DsUiTestConfiguration.clear();
    }

    @Test
    public void settingsHubPassesFrameworkAtLargeFont() {
        Nintendo3DsUiTestConfiguration.set("pt-BR", 1.3f);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(
                context,
                Nintendo3DsSettingsAccessibilityTestActivity.class);
        try (ActivityScenario<Nintendo3DsSettingsAccessibilityTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(isRoot()).perform(scanAccessibilityHierarchy());
        }
    }

    @Test
    public void settingsAndReadinessDialogsPassFrameworkAtLargeFont() {
        Nintendo3DsUiTestConfiguration.set("pt-BR", 1.3f);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<Nintendo3DsSettingsTestActivity> settings =
                     ActivityScenario.launch(new Intent(
                             context,
                             Nintendo3DsSettingsTestActivity.class))) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(isRoot()).perform(scanAccessibilityHierarchy());
        }

        Intent readinessIntent = new Intent(context, Nintendo3DsReadinessTestActivity.class)
                .putExtra(
                        Nintendo3DsReadinessTestActivity.EXTRA_SCENARIO,
                        Nintendo3DsReadinessTestActivity.SCENARIO_WARNING_MII);
        try (ActivityScenario<Nintendo3DsReadinessTestActivity> readiness =
                     ActivityScenario.launch(readinessIntent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(isRoot()).perform(scanAccessibilityHierarchy());
        }
    }

    @Test
    public void productShellPassesFrameworkWithOpenHomebrew() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        File core = new File(arguments.getString("n3dsCorePath", ""));
        File content = new File(arguments.getString("n3dsContentPath", ""));
        assumeTrue("Teste real requer -e n3dsCorePath", core.isFile());
        assumeTrue("Teste real requer -e n3dsContentPath", content.isFile());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Nintendo3DsProductLaunchRequest(
                core,
                content,
                PERSISTENT_ID,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                64L * 1024L * 1024L,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true).createIntent(context);
        try (ActivityScenario<Nintendo3DsProductActivity> scenario =
                     ActivityScenario.launch(intent)) {
            awaitFrames(scenario);
            scenario.onActivity(activity -> assertNotNull(
                    activity.getVirtualControlsForTesting()));
            onView(isRoot()).perform(scanAccessibilityHierarchy());
        }
    }

    @Test
    public void holdsSettingsHubForExplicitTalkBackProbe() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue(
                "Probe interativo requer -e n3dsTalkBackProbe true",
                Boolean.parseBoolean(arguments.getString("n3dsTalkBackProbe", "false")));
        Nintendo3DsUiTestConfiguration.set("pt-BR", 1.3f);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(
                context,
                Nintendo3DsSettingsAccessibilityTestActivity.class);
        try (ActivityScenario<Nintendo3DsSettingsAccessibilityTestActivity> scenario =
                     ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            System.out.println("N3DS_TALKBACK_PROBE_READY");
            SystemClock.sleep(45_000L);
        }
    }

    private static ViewAction scanAccessibilityHierarchy() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return any(View.class);
            }

            @Override
            public String getDescription() {
                return "scan the complete hierarchy with Accessibility Test Framework";
            }

            @Override
            public void perform(UiController uiController, View view) {
                uiController.loopMainThreadUntilIdle();
            }
        };
    }

    private static void awaitFrames(
            ActivityScenario<Nintendo3DsProductActivity> scenario) {
        long deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> ready.set(
                    activity.getRenderedFrameCountForTesting() >= 30));
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for the Nintendo 3DS product host");
    }
}
