// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ActivityNotFoundException;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsShareIntentLauncherInstrumentedTest {
    @Test
    public void reportsMissingOrRejectedShareTarget() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("image/png");

        assertFalse(Nintendo3DsShareIntentLauncher.launch(
                share,
                "Share with",
                ignored -> {
                    throw new ActivityNotFoundException();
                }));
        assertFalse(Nintendo3DsShareIntentLauncher.launch(
                share,
                "Share with",
                ignored -> {
                    throw new SecurityException("blocked");
                }));
    }

    @Test
    public void wrapsSupportedShareInChooser() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("image/png");
        AtomicReference<Intent> launched = new AtomicReference<>();

        assertTrue(Nintendo3DsShareIntentLauncher.launch(
                share,
                "Share with",
                launched::set));
        assertTrue(Intent.ACTION_CHOOSER.equals(launched.get().getAction()));
    }
}
