// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Focused regression for recovery from an invalid persisted performance profile. */
@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsExperienceSettingsRecoveryInstrumentedTest {
    @Test
    public void invalidProfileUsesApprovedDefault() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String preferencesName = "n3ds_invalid_profile_test_" + System.nanoTime();
        try {
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                    .edit()
                    .putString("global.performance_profile", "invalid-profile")
                    .commit();

            Nintendo3DsExperienceSettings recovered =
                    new Nintendo3DsExperienceSettingsStore(context, preferencesName)
                            .loadGlobal();

            assertEquals(
                    Nintendo3DsExperienceSettings.defaults().getPerformanceProfile(),
                    recovered.getPerformanceProfile());
        } finally {
            context.deleteSharedPreferences(preferencesName);
        }
    }
}
