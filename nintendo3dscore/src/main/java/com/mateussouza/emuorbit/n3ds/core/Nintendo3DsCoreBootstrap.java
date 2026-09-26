// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;

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
        Context checkedContext = Objects.requireNonNull(context).getApplicationContext();
        p(checkedContext.getAssets());
        return new Nintendo3DsCoreInfo(n(PACKAGED_CORE_LIBRARY_PATH));
    }

    private static native String[] n(String absoluteLibraryPath) throws IOException;

    private static native void p(android.content.res.AssetManager assetManager)
            throws IOException;
}
