// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class Nintendo3DsReadinessLocalizationInstrumentedTest {
    private static final String[] LANGUAGE_TAGS = {
            "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"
    };

    @Test
    public void resolvesEveryReadinessMessageAndActionInTenLocalesIncludingRtl() {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Set<String> localizedTitles = new HashSet<>();
        for (String languageTag : LANGUAGE_TAGS) {
            Context localized = localizedContext(base, languageTag);
            String title = localized.getString(R.string.n3ds_readiness_blocked_title);
            assertFalse(languageTag + " title", title.isBlank());
            assertTrue(languageTag + " must not fall back", localizedTitles.add(title));

            for (Nintendo3DsLaunchReadiness.Issue issue
                    : Nintendo3DsLaunchReadiness.Issue.values()) {
                assertFalse(
                        languageTag + " " + issue,
                        localized.getString(
                                Nintendo3DsReadinessPresentation.messageResource(issue))
                                .isBlank());
            }
            for (Nintendo3DsReadinessPresentation.Action action
                    : Nintendo3DsReadinessPresentation.Action.values()) {
                if (action != Nintendo3DsReadinessPresentation.Action.NONE) {
                    assertFalse(
                            languageTag + " " + action,
                            localized.getString(action.getLabelResource()).isBlank());
                }
            }

            int expectedDirection = "ar".equals(languageTag)
                    ? View.LAYOUT_DIRECTION_RTL
                    : View.LAYOUT_DIRECTION_LTR;
            assertEquals(expectedDirection,
                    localized.getResources().getConfiguration().getLayoutDirection());
        }
        assertEquals(LANGUAGE_TAGS.length, localizedTitles.size());
    }

    private static Context localizedContext(Context context, String languageTag) {
        Locale locale = Locale.forLanguageTag(languageTag);
        Configuration configuration = new Configuration(
                context.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }
}
