// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Field;

/**
 * Restores dynamic-feature resource IDs inside the self-targeted physical-test APK.
 *
 * <p>The production split assigns the feature package ID {@code 0x80}. Local physical QA
 * deliberately installs only the instrumentation APK, whose copied resources use package ID
 * {@code 0x7f}. The feature R fields are non-final, so this test-only application maps them to the
 * IDs generated for the isolated package before an Activity or test resolves a resource.</p>
 */
public final class Nintendo3DsLocalTestApplication extends Application {
    private static final String LOCAL_TEST_PACKAGE =
            "com.mateussouza.emuorbit.n3ds.core.test";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (!LOCAL_TEST_PACKAGE.equals(base.getPackageName())) {
            return;
        }
        remapResourceType(R.animator.class, "animator");
        remapResourceType(R.drawable.class, "drawable");
        remapResourceType(R.id.class, "id");
        remapResourceType(R.string.class, "string");
    }

    private static void remapResourceType(Class<?> featureType, String typeName) {
        try {
            Class<?> localType = Class.forName(LOCAL_TEST_PACKAGE + ".R$" + typeName);
            for (Field featureField : featureType.getDeclaredFields()) {
                Field localField = localType.getDeclaredField(featureField.getName());
                featureField.setInt(null, localField.getInt(null));
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Failed to map Nintendo 3DS local-test " + typeName + " resources",
                    exception);
        }
    }
}
