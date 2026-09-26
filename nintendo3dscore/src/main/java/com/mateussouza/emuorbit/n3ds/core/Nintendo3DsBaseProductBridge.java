// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * String-only runtime boundary to reusable base-app product services.
 *
 * <p>The production feature always runs with the base APK. Keeping the symbols out of this
 * module's static type graph also lets the private self-contained QA host prove graceful
 * behavior when those optional product services are intentionally absent.</p>
 */
final class Nintendo3DsBaseProductBridge {
    private static final String PLAY_STORE_NAVIGATOR =
            "com.mateussouza.emuorbit.advance.playstore.PlayStoreNavigator";
    private static final String SHARE_ARTIFACT_MANAGER =
            "com.mateussouza.emuorbit.advance.sharing.ShareArtifactManager";
    private static final String BUG_REPORT_SENDER =
            "com.mateussouza.emuorbit.advance.ui.bugreport.BugReportSender";

    private final Context context;

    Nintendo3DsBaseProductBridge(Context context) {
        this.context = Objects.requireNonNull(context);
    }

    boolean isListingPublished() {
        try {
            Object result = staticMethod(
                    PLAY_STORE_NAVIGATOR,
                    "isListingPublished").invoke(null);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    Intent createScreenshotShareIntent(
            Bitmap frame,
            String brandCaption,
            String shareText) throws IOException {
        Objects.requireNonNull(frame);
        try {
            Class<?> managerClass = Class.forName(SHARE_ARTIFACT_MANAGER);
            Object manager = managerClass.getConstructor(Context.class).newInstance(context);
            Object prepared = managerClass
                    .getMethod("createScreenshot", Bitmap.class, String.class)
                    .invoke(manager, frame, brandCaption);
            Object intent = prepared.getClass()
                    .getMethod("createIntent", String.class)
                    .invoke(prepared, shareText);
            if (!(intent instanceof Intent)) {
                throw new IOException("Base share service returned an invalid intent.");
            }
            return (Intent) intent;
        } catch (InvocationTargetException failure) {
            throw mappedIOException("Base share service failed.", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Base share service is unavailable.", failure);
        }
    }

    String listingUrl() throws IOException {
        try {
            Object result = staticMethod(PLAY_STORE_NAVIGATOR, "getListingUrl").invoke(null);
            if (!(result instanceof String) || ((String) result).isBlank()) {
                throw new IOException("Base listing URL is unavailable.");
            }
            return (String) result;
        } catch (InvocationTargetException failure) {
            throw mappedIOException("Base listing service failed.", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Base listing service is unavailable.", failure);
        }
    }

    void sendProblemReport(
            String description,
            String source,
            Map<String, String> reportContext) throws IOException {
        try {
            staticMethod(
                    BUG_REPORT_SENDER,
                    "send",
                    Context.class,
                    String.class,
                    String.class,
                    Map.class).invoke(null, context, description, source, reportContext);
        } catch (InvocationTargetException failure) {
            throw mappedIOException("Base report service failed.", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Base report service is unavailable.", failure);
        }
    }

    private static Method staticMethod(
            String className,
            String methodName,
            Class<?>... parameterTypes) throws ReflectiveOperationException {
        return Class.forName(className).getMethod(methodName, parameterTypes);
    }

    private static IOException mappedIOException(String message, Throwable cause) {
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(message, cause);
    }
}
