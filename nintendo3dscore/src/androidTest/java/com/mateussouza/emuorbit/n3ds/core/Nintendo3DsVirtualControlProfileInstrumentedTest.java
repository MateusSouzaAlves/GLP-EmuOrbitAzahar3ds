// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsVirtualControlProfileInstrumentedTest {
    private static final String HASH_A =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String HASH_B =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String PERSISTENT_ID = "n3ds-v1:" + HASH_A + ":" + HASH_B;

    @Test
    public void persistsResolvesResetsAndRecoversCorruptProfiles() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String preferencesName = "n3ds_control_profile_test_" + System.nanoTime();
        Nintendo3DsVirtualControlProfileStore store =
                new Nintendo3DsVirtualControlProfileStore(context, preferencesName);
        Nintendo3DsVirtualControlProfile global =
                Nintendo3DsVirtualControlProfile.identity().withTransform(
                        Nintendo3DsVirtualControlOrientation.PORTRAIT,
                        Nintendo3DsVirtualControlGroup.DIRECTIONAL,
                        new Nintendo3DsVirtualControlTransform(0.25f, -0.5f, 1.2f, true));
        Nintendo3DsVirtualControlProfile game = global.withTransform(
                Nintendo3DsVirtualControlOrientation.LANDSCAPE,
                Nintendo3DsVirtualControlGroup.ACTIONS,
                new Nintendo3DsVirtualControlTransform(-0.4f, 0.3f, 0.8f, false));

        assertNull(store.loadGlobal());
        assertEquals(Nintendo3DsVirtualControlProfile.identity(), store.resolve(PERSISTENT_ID));
        assertTrue(store.saveGlobal(global));
        assertEquals(global, store.loadGlobal());
        assertEquals(global, store.resolve(PERSISTENT_ID));
        assertTrue(store.saveForGame(PERSISTENT_ID.toUpperCase(), game));
        assertEquals(game, store.loadForGame(PERSISTENT_ID));
        assertEquals(game, store.resolve(PERSISTENT_ID));
        assertTrue(store.removeForGame(PERSISTENT_ID));
        assertEquals(global, store.resolve(PERSISTENT_ID));

        SharedPreferences raw = context.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE);
        assertTrue(raw.edit().putString("global", "not-base64!").commit());
        assertNull(store.loadGlobal());
        assertEquals(Nintendo3DsVirtualControlProfile.identity(), store.resolve(PERSISTENT_ID));
        assertTrue(raw.edit().putInt("global", 3).commit());
        assertNull(store.loadGlobal());
        assertTrue(store.resetGlobal());
        assertThrows(IllegalArgumentException.class, () -> store.resolve("n3ds-v1:invalid"));
    }
}
