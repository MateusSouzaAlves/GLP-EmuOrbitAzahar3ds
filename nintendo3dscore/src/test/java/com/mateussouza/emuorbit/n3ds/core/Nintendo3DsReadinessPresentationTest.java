// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Nintendo3DsReadinessPresentationTest {
    @Test
    public void readyResultNeedsNoDialogOrAction() {
        Nintendo3DsReadinessPresentation.Model model =
                Nintendo3DsReadinessPresentation.from(evaluate(
                        Nintendo3DsLaunchReadiness.CoreStatus.READY,
                        Nintendo3DsLaunchReadiness.ContentStatus.READY,
                        Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                        Nintendo3DsMiiDataManager.Status.MISSING,
                        true,
                        100,
                        0));

        assertFalse(model.isVisible());
        assertTrue(model.canContinue());
        assertEquals(0, model.getTitleResource());
        assertTrue(model.getMessageResources().isEmpty());
        assertEquals(Nintendo3DsReadinessPresentation.Action.NONE,
                model.getPrimaryAction());
    }

    @Test
    public void mapsEveryIssueToOneUniqueLocalizedResource() {
        Set<Integer> resources = new HashSet<>();
        for (Nintendo3DsLaunchReadiness.Issue issue
                : Nintendo3DsLaunchReadiness.Issue.values()) {
            int resource = Nintendo3DsReadinessPresentation.messageResource(issue);
            assertTrue(resource != 0);
            assertTrue("Mensagem duplicada para " + issue, resources.add(resource));
        }
    }

    @Test
    public void blockedResultUsesOrderedPrimaryActionButPreservesEveryMessage() {
        Nintendo3DsReadinessPresentation.Model model =
                Nintendo3DsReadinessPresentation.from(evaluate(
                        Nintendo3DsLaunchReadiness.CoreStatus.MISSING,
                        Nintendo3DsLaunchReadiness.ContentStatus.ARCHIVE_REQUIRES_EXTRACTION,
                        Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED,
                        Nintendo3DsMiiDataManager.Status.MISSING,
                        false,
                        0,
                        1));

        assertTrue(model.isVisible());
        assertFalse(model.canContinue());
        assertEquals(R.string.n3ds_readiness_blocked_title, model.getTitleResource());
        assertEquals(Nintendo3DsReadinessPresentation.Action.RETRY,
                model.getPrimaryAction());
        assertEquals(4, model.getMessageResources().size());
        assertThrows(UnsupportedOperationException.class,
                () -> model.getMessageResources().add(1));
    }

    @Test
    public void choosesSpecificRecoveryActions() {
        assertAction(
                Nintendo3DsLaunchReadiness.ContentStatus.ARCHIVE_REQUIRES_EXTRACTION,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true,
                100,
                0,
                Nintendo3DsReadinessPresentation.Action.EXTRACT_CONTENT);
        assertAction(
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true,
                0,
                1,
                Nintendo3DsReadinessPresentation.Action.OPEN_STORAGE);
        assertAction(
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED,
                true,
                100,
                0,
                Nintendo3DsReadinessPresentation.Action.IMPORT_MII);
        assertAction(
                Nintendo3DsLaunchReadiness.ContentStatus.ENCRYPTED,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                true,
                100,
                0,
                Nintendo3DsReadinessPresentation.Action.UNDERSTOOD);
    }

    @Test
    public void warningAllowsContinuationAndPrioritizesOptionalMiiAction() {
        Nintendo3DsReadinessPresentation.Model model =
                Nintendo3DsReadinessPresentation.from(evaluate(
                        Nintendo3DsLaunchReadiness.CoreStatus.READY,
                        Nintendo3DsLaunchReadiness.ContentStatus.READY,
                        Nintendo3DsLaunchReadiness.MiiRequirement.OPTIONAL,
                        Nintendo3DsMiiDataManager.Status.EMPTY,
                        false,
                        100,
                        0));

        assertTrue(model.isVisible());
        assertTrue(model.canContinue());
        assertEquals(R.string.n3ds_readiness_warning_title, model.getTitleResource());
        assertEquals(List.of(R.string.n3ds_readiness_mii_optional),
                model.getMessageResources());
        assertEquals(Nintendo3DsReadinessPresentation.Action.IMPORT_MII,
                model.getPrimaryAction());
    }

    private static void assertAction(
            Nintendo3DsLaunchReadiness.ContentStatus content,
            Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
            boolean deviceQualified,
            long available,
            long required,
            Nintendo3DsReadinessPresentation.Action action) {
        Nintendo3DsReadinessPresentation.Model model =
                Nintendo3DsReadinessPresentation.from(evaluate(
                        Nintendo3DsLaunchReadiness.CoreStatus.READY,
                        content,
                        miiRequirement,
                        Nintendo3DsMiiDataManager.Status.MISSING,
                        deviceQualified,
                        available,
                        required));
        assertEquals(action, model.getPrimaryAction());
    }

    private static Nintendo3DsLaunchReadiness.Result evaluate(
            Nintendo3DsLaunchReadiness.CoreStatus core,
            Nintendo3DsLaunchReadiness.ContentStatus content,
            Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
            Nintendo3DsMiiDataManager.Status miiStatus,
            boolean deviceQualified,
            long available,
            long required) {
        return Nintendo3DsLaunchReadiness.evaluate(new Nintendo3DsLaunchReadiness.Probe(
                core,
                true,
                true,
                content,
                available,
                required,
                miiRequirement,
                miiStatus,
                deviceQualified));
    }
}
