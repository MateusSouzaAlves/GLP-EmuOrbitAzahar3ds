// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Process-local configuration override used only by isolated UI instrumentation hosts. */
final class Nintendo3DsUiTestConfiguration {
    private static String languageTag;
    private static float fontScale;

    private Nintendo3DsUiTestConfiguration() {
    }

    static synchronized void set(String requestedLanguageTag, float requestedFontScale) {
        if (requestedLanguageTag == null || requestedLanguageTag.isBlank()) {
            throw new IllegalArgumentException("A UI test language tag is required");
        }
        if (!Float.isFinite(requestedFontScale) || requestedFontScale <= 0f) {
            throw new IllegalArgumentException("A positive UI test font scale is required");
        }
        languageTag = requestedLanguageTag;
        fontScale = requestedFontScale;
    }

    static synchronized void clear() {
        languageTag = null;
        fontScale = 0f;
    }

    static synchronized Context wrap(Context base) {
        if (languageTag == null) {
            return base;
        }
        Locale locale = Locale.forLanguageTag(languageTag);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }
}
