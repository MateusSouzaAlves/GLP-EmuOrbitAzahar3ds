// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure, ordered policy for the experimental Nintendo 3DS pre-launch checks. */
public final class Nintendo3DsLaunchReadiness {
    public enum Severity {
        READY,
        WARNING,
        BLOCKED
    }

    public enum Issue {
        CORE_MISSING(Severity.BLOCKED),
        CORE_INVALID(Severity.BLOCKED),
        ARM64_REQUIRED(Severity.BLOCKED),
        VULKAN_REQUIRED(Severity.BLOCKED),
        CONTENT_UNAVAILABLE(Severity.BLOCKED),
        CONTENT_REQUIRES_EXTRACTION(Severity.BLOCKED),
        CONTENT_ENCRYPTED(Severity.BLOCKED),
        STORAGE_LOW(Severity.BLOCKED),
        MII_DATA_REQUIRED(Severity.BLOCKED),
        MII_DATA_OPTIONAL(Severity.WARNING),
        DEVICE_UNQUALIFIED(Severity.WARNING);

        private final Severity severity;

        Issue(Severity severity) {
            this.severity = severity;
        }

        public Severity getSeverity() {
            return severity;
        }
    }

    public enum CoreStatus {
        MISSING,
        INVALID,
        READY
    }

    public enum ContentStatus {
        READY,
        UNAVAILABLE,
        ARCHIVE_REQUIRES_EXTRACTION,
        ENCRYPTED
    }

    /** The caller sets REQUIRED only with title-specific evidence; UNKNOWN is never guessed. */
    public enum MiiRequirement {
        UNKNOWN,
        NOT_REQUIRED,
        OPTIONAL,
        REQUIRED
    }

    public static final class Probe {
        private final CoreStatus coreStatus;
        private final boolean arm64Available;
        private final boolean vulkanAvailable;
        private final ContentStatus contentStatus;
        private final long availablePrivateStorageBytes;
        private final long requiredPrivateStorageBytes;
        private final MiiRequirement miiRequirement;
        private final Nintendo3DsMiiDataManager.Status miiDataStatus;
        private final boolean deviceQualified;

        public Probe(
                CoreStatus coreStatus,
                boolean arm64Available,
                boolean vulkanAvailable,
                ContentStatus contentStatus,
                long availablePrivateStorageBytes,
                long requiredPrivateStorageBytes,
                MiiRequirement miiRequirement,
                Nintendo3DsMiiDataManager.Status miiDataStatus,
                boolean deviceQualified) {
            this.coreStatus = Objects.requireNonNull(coreStatus);
            this.arm64Available = arm64Available;
            this.vulkanAvailable = vulkanAvailable;
            this.contentStatus = Objects.requireNonNull(contentStatus);
            if (availablePrivateStorageBytes < -1L) {
                throw new IllegalArgumentException(
                        "O espaço privado 3DS disponível deve ser -1 ou não negativo.");
            }
            if (requiredPrivateStorageBytes < 0L) {
                throw new IllegalArgumentException(
                        "O espaço privado 3DS necessário não pode ser negativo.");
            }
            this.availablePrivateStorageBytes = availablePrivateStorageBytes;
            this.requiredPrivateStorageBytes = requiredPrivateStorageBytes;
            this.miiRequirement = Objects.requireNonNull(miiRequirement);
            this.miiDataStatus = Objects.requireNonNull(miiDataStatus);
            this.deviceQualified = deviceQualified;
        }
    }

    public static final class Result {
        private final Severity severity;
        private final List<Issue> issues;

        private Result(Severity severity, List<Issue> issues) {
            this.severity = Objects.requireNonNull(severity);
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        }

        public Severity getSeverity() {
            return severity;
        }

        public List<Issue> getIssues() {
            return issues;
        }

        public Issue getPrimaryIssue() {
            return issues.isEmpty() ? null : issues.get(0);
        }

        public boolean canLaunch() {
            return severity != Severity.BLOCKED;
        }
    }

    private Nintendo3DsLaunchReadiness() {
    }

    public static Result evaluate(Probe probe) {
        Probe checked = Objects.requireNonNull(probe);
        List<Issue> issues = new ArrayList<>();
        switch (checked.coreStatus) {
        case MISSING -> issues.add(Issue.CORE_MISSING);
        case INVALID -> issues.add(Issue.CORE_INVALID);
        case READY -> {
        }
        }
        if (!checked.arm64Available) {
            issues.add(Issue.ARM64_REQUIRED);
        }
        if (!checked.vulkanAvailable) {
            issues.add(Issue.VULKAN_REQUIRED);
        }
        switch (checked.contentStatus) {
        case UNAVAILABLE -> issues.add(Issue.CONTENT_UNAVAILABLE);
        case ARCHIVE_REQUIRES_EXTRACTION -> issues.add(Issue.CONTENT_REQUIRES_EXTRACTION);
        case ENCRYPTED -> issues.add(Issue.CONTENT_ENCRYPTED);
        case READY -> {
        }
        }
        if (checked.availablePrivateStorageBytes >= 0L
                && checked.requiredPrivateStorageBytes
                > checked.availablePrivateStorageBytes) {
            issues.add(Issue.STORAGE_LOW);
        }
        if (checked.miiDataStatus != Nintendo3DsMiiDataManager.Status.READY) {
            if (checked.miiRequirement == MiiRequirement.REQUIRED) {
                issues.add(Issue.MII_DATA_REQUIRED);
            } else if (checked.miiRequirement == MiiRequirement.OPTIONAL) {
                issues.add(Issue.MII_DATA_OPTIONAL);
            }
        }
        if (!checked.deviceQualified) {
            issues.add(Issue.DEVICE_UNQUALIFIED);
        }

        Severity severity = Severity.READY;
        for (Issue issue : issues) {
            if (issue.getSeverity() == Severity.BLOCKED) {
                severity = Severity.BLOCKED;
                break;
            }
            severity = Severity.WARNING;
        }
        return new Result(severity, issues);
    }
}
