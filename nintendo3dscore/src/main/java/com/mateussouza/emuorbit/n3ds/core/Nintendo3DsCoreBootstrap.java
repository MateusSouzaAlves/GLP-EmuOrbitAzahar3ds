// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;

import com.google.android.play.core.splitinstall.SplitInstallHelper;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/** Loads the safe pre-game ABI without creating a gameplay or graphics session. */
public final class Nintendo3DsCoreBootstrap {
    public static final String PACKAGED_CORE_LIBRARY_PATH = "azahar_libretro.so";

    static {
        System.loadLibrary("emuorbit_n3ds_bootstrap");
    }

    private Nintendo3DsCoreBootstrap() {
    }

    public static Nintendo3DsCoreInfo inspect(File coreLibrary) throws IOException {
        File canonical = Objects.requireNonNull(coreLibrary).getCanonicalFile();
        if (!canonical.isFile() || !canonical.canRead()) {
            throw new IOException("A biblioteca do núcleo Nintendo 3DS não está acessível.");
        }
        return new Nintendo3DsCoreInfo(n(canonical.getAbsolutePath()));
    }

    /** Loads and inspects the exact native core delivered inside the installed split. */
    public static Nintendo3DsCoreInfo inspectPackaged(Context context) throws IOException {
        try {
            SplitInstallHelper.loadLibrary(
                    Objects.requireNonNull(context).getApplicationContext(),
                    "azahar_libretro");
        } catch (UnsatisfiedLinkError failure) {
            throw new IOException(
                    "A biblioteca do núcleo Nintendo 3DS não está instalada no split.",
                    failure);
        }
        return new Nintendo3DsCoreInfo(n(PACKAGED_CORE_LIBRARY_PATH));
    }

    private static native String[] n(String absoluteLibraryPath) throws IOException;
}
