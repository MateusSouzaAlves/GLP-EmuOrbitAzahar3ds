// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class Nintendo3DsLaunchReadinessTest {
    @Test
    public void readyEvidenceLaunchesWithoutIssues() {
        Nintendo3DsLaunchReadiness.Result result = evaluate(
                Nintendo3DsLaunchReadiness.CoreStatus.READY,
                true,
                true,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                1024,
                512,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                Nintendo3DsMiiDataManager.Status.MISSING,
                true);

        assertEquals(Nintendo3DsLaunchReadiness.Severity.READY, result.getSeverity());
        assertTrue(result.canLaunch());
        assertTrue(result.getIssues().isEmpty());
        assertNull(result.getPrimaryIssue());
    }

    @Test
    public void ordersActionableBlockersWithoutRepeatingQualificationGuidance() {
        Nintendo3DsLaunchReadiness.Result result = evaluate(
                Nintendo3DsLaunchReadiness.CoreStatus.MISSING,
                false,
                false,
                Nintendo3DsLaunchReadiness.ContentStatus.ARCHIVE_REQUIRES_EXTRACTION,
                10,
                11,
                Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED,
                Nintendo3DsMiiDataManager.Status.EMPTY,
                false);

        assertEquals(Nintendo3DsLaunchReadiness.Severity.BLOCKED, result.getSeverity());
        assertFalse(result.canLaunch());
        assertEquals(List.of(
                        Nintendo3DsLaunchReadiness.Issue.CORE_MISSING,
                        Nintendo3DsLaunchReadiness.Issue.ARM64_REQUIRED,
                        Nintendo3DsLaunchReadiness.Issue.VULKAN_REQUIRED,
                        Nintendo3DsLaunchReadiness.Issue.CONTENT_REQUIRES_EXTRACTION,
                        Nintendo3DsLaunchReadiness.Issue.STORAGE_LOW,
                        Nintendo3DsLaunchReadiness.Issue.MII_FALLBACK_ACTIVE),
                result.getIssues());
        assertEquals(Nintendo3DsLaunchReadiness.Issue.CORE_MISSING,
                result.getPrimaryIssue());
    }

    @Test
    public void unqualifiedDeviceDoesNotCreateARepeatingLaunchWarning() {
        Nintendo3DsLaunchReadiness.Result result = evaluate(
                Nintendo3DsLaunchReadiness.CoreStatus.READY,
                true,
                true,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                1024,
                0,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                Nintendo3DsMiiDataManager.Status.MISSING,
                false);

        assertEquals(Nintendo3DsLaunchReadiness.Severity.READY, result.getSeverity());
        assertTrue(result.getIssues().isEmpty());
    }

    @Test
    public void reportsEncryptedAndUnavailableContentSeparately() {
        assertEquals(
                List.of(Nintendo3DsLaunchReadiness.Issue.CONTENT_ENCRYPTED),
                withContent(Nintendo3DsLaunchReadiness.ContentStatus.ENCRYPTED).getIssues());
        assertEquals(
                List.of(Nintendo3DsLaunchReadiness.Issue.CONTENT_UNAVAILABLE),
                withContent(Nintendo3DsLaunchReadiness.ContentStatus.UNAVAILABLE).getIssues());
    }

    @Test
    public void miiIsNeverGuessedAndRequirementControlsSeverity() {
        Nintendo3DsLaunchReadiness.Result unknown = withMii(
                Nintendo3DsLaunchReadiness.MiiRequirement.UNKNOWN,
                Nintendo3DsMiiDataManager.Status.MISSING);
        Nintendo3DsLaunchReadiness.Result optional = withMii(
                Nintendo3DsLaunchReadiness.MiiRequirement.OPTIONAL,
                Nintendo3DsMiiDataManager.Status.EMPTY);
        Nintendo3DsLaunchReadiness.Result required = withMii(
                Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED,
                Nintendo3DsMiiDataManager.Status.MALFORMED);
        Nintendo3DsLaunchReadiness.Result present = withMii(
                Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED,
                Nintendo3DsMiiDataManager.Status.READY);

        assertEquals(Nintendo3DsLaunchReadiness.Severity.READY, unknown.getSeverity());
        assertEquals(List.of(Nintendo3DsLaunchReadiness.Issue.MII_DATA_OPTIONAL),
                optional.getIssues());
        assertEquals(Nintendo3DsLaunchReadiness.Severity.WARNING, optional.getSeverity());
        assertEquals(List.of(Nintendo3DsLaunchReadiness.Issue.MII_FALLBACK_ACTIVE),
                required.getIssues());
        assertEquals(Nintendo3DsLaunchReadiness.Severity.WARNING, required.getSeverity());
        assertTrue(required.canLaunch());
        assertTrue(present.canLaunch());
    }

    @Test
    public void unknownStorageDoesNotInventALowSpaceFailure() {
        Nintendo3DsLaunchReadiness.Result result = evaluate(
                Nintendo3DsLaunchReadiness.CoreStatus.READY,
                true,
                true,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                -1,
                Long.MAX_VALUE,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                Nintendo3DsMiiDataManager.Status.MISSING,
                true);

        assertTrue(result.canLaunch());
        assertTrue(result.getIssues().isEmpty());
    }

    @Test
    public void invalidStorageEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> evaluate(
                Nintendo3DsLaunchReadiness.CoreStatus.READY,
                true,
                true,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                -2,
                0,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                Nintendo3DsMiiDataManager.Status.MISSING,
                true));
        assertThrows(IllegalArgumentException.class, () -> evaluate(
                Nintendo3DsLaunchReadiness.CoreStatus.READY,
                true,
                true,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                0,
                -1,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                Nintendo3DsMiiDataManager.Status.MISSING,
                true));
    }

    @Test
    public void expectedCoreContractMatchesPinnedAzahar() {
        assertTrue(Nintendo3DsAndroidLaunchReadinessInspector.isExpectedCore(core(
                "Azahar", "3ds|3dsx|cci|cxi|app", "1", "1", "4", "1", "1")));
        assertFalse(Nintendo3DsAndroidLaunchReadinessInspector.isExpectedCore(core(
                "Other", "3ds|3dsx|cci|cxi", "1", "1", "4", "1", "1")));
        assertFalse(Nintendo3DsAndroidLaunchReadinessInspector.isExpectedCore(core(
                "Azahar", "3ds|3dsx|cci", "1", "1", "4", "1", "1")));
        assertFalse(Nintendo3DsAndroidLaunchReadinessInspector.isExpectedCore(core(
                "Azahar", "3ds|3dsx|cci|cxi", "0", "1", "4", "1", "1")));
    }

    @Test
    public void issueListIsImmutable() {
        Nintendo3DsLaunchReadiness.Result result = withContent(
                Nintendo3DsLaunchReadiness.ContentStatus.ENCRYPTED);
        assertThrows(UnsupportedOperationException.class,
                () -> result.getIssues().add(Nintendo3DsLaunchReadiness.Issue.CORE_INVALID));
    }

    private static Nintendo3DsLaunchReadiness.Result withContent(
            Nintendo3DsLaunchReadiness.ContentStatus status) {
        return evaluate(
                Nintendo3DsLaunchReadiness.CoreStatus.READY,
                true,
                true,
                status,
                1,
                0,
                Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED,
                Nintendo3DsMiiDataManager.Status.MISSING,
                true);
    }

    private static Nintendo3DsLaunchReadiness.Result withMii(
            Nintendo3DsLaunchReadiness.MiiRequirement requirement,
            Nintendo3DsMiiDataManager.Status status) {
        return evaluate(
                Nintendo3DsLaunchReadiness.CoreStatus.READY,
                true,
                true,
                Nintendo3DsLaunchReadiness.ContentStatus.READY,
                1,
                0,
                requirement,
                status,
                true);
    }

    private static Nintendo3DsLaunchReadiness.Result evaluate(
            Nintendo3DsLaunchReadiness.CoreStatus core,
            boolean arm64,
            boolean vulkan,
            Nintendo3DsLaunchReadiness.ContentStatus content,
            long available,
            long required,
            Nintendo3DsLaunchReadiness.MiiRequirement miiRequirement,
            Nintendo3DsMiiDataManager.Status miiStatus,
            boolean qualified) {
        return Nintendo3DsLaunchReadiness.evaluate(new Nintendo3DsLaunchReadiness.Probe(
                core,
                arm64,
                vulkan,
                content,
                available,
                required,
                miiRequirement,
                miiStatus,
                qualified));
    }

    private static Nintendo3DsCoreInfo core(
            String name,
            String extensions,
            String fullPath,
            String api,
            String callbackCount,
            String options,
            String controllers) {
        return new Nintendo3DsCoreInfo(new String[]{
                name, "fbd3fb0", extensions, fullPath, api, callbackCount,
                options, controllers, "1"
        });
    }
}
