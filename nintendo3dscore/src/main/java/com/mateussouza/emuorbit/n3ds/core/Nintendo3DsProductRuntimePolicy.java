// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Keeps dynamic-feature runtime decisions independent from the Android Activity host. */
final class Nintendo3DsProductRuntimePolicy {
    private static final String BASE_APPLICATION_ID = "com.mateussouza.emuorbit.advance";
    private static final String UX_TEST_APPLICATION_SUFFIX = ".n3ds.uxtest";

    private Nintendo3DsProductRuntimePolicy() {
    }

    static boolean requiresSplitCompat(String packageName) {
        return BASE_APPLICATION_ID.equals(packageName)
                || packageName.endsWith(UX_TEST_APPLICATION_SUFFIX);
    }
}
