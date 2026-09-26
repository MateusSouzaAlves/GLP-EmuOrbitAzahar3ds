// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Maps the pure readiness result to localized, host-neutral presentation data. */
public final class Nintendo3DsReadinessPresentation {
    public enum Action {
        NONE(0),
        UNDERSTOOD(R.string.n3ds_readiness_action_understood),
        RETRY(R.string.n3ds_readiness_action_retry),
        OPEN_STORAGE(R.string.n3ds_readiness_action_open_storage),
        IMPORT_MII(R.string.n3ds_readiness_action_import_mii),
        EXTRACT_CONTENT(R.string.n3ds_readiness_action_extract_content),
        CONTINUE(R.string.n3ds_readiness_action_continue);

        private final int labelResource;

        Action(int labelResource) {
            this.labelResource = labelResource;
        }

        public int getLabelResource() {
            return labelResource;
        }
    }

    public static final class Model {
        private final boolean visible;
        private final boolean canContinue;
        private final int titleResource;
        private final List<Integer> messageResources;
        private final Action primaryAction;

        private Model(
                boolean visible,
                boolean canContinue,
                int titleResource,
                List<Integer> messageResources,
                Action primaryAction) {
            this.visible = visible;
            this.canContinue = canContinue;
            this.titleResource = titleResource;
            this.messageResources = Collections.unmodifiableList(
                    new ArrayList<>(messageResources));
            this.primaryAction = Objects.requireNonNull(primaryAction);
        }

        public boolean isVisible() {
            return visible;
        }

        public boolean canContinue() {
            return canContinue;
        }

        public int getTitleResource() {
            return titleResource;
        }

        public List<Integer> getMessageResources() {
            return messageResources;
        }

        public Action getPrimaryAction() {
            return primaryAction;
        }

        public String formatMessages(Context context) {
            Context checked = Objects.requireNonNull(context);
            StringBuilder text = new StringBuilder();
            for (int resource : messageResources) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(checked.getString(resource));
            }
            return text.toString();
        }
    }

    private Nintendo3DsReadinessPresentation() {
    }

    public static Model from(Nintendo3DsLaunchReadiness.Result readiness) {
        Nintendo3DsLaunchReadiness.Result checked = Objects.requireNonNull(readiness);
        if (checked.getSeverity() == Nintendo3DsLaunchReadiness.Severity.READY) {
            return new Model(false, true, 0, List.of(), Action.NONE);
        }

        List<Integer> messages = new ArrayList<>();
        for (Nintendo3DsLaunchReadiness.Issue issue : checked.getIssues()) {
            messages.add(messageResource(issue));
        }
        int title = checked.getSeverity() == Nintendo3DsLaunchReadiness.Severity.BLOCKED
                ? R.string.n3ds_readiness_blocked_title
                : R.string.n3ds_readiness_warning_title;
        return new Model(
                true,
                checked.canLaunch(),
                title,
                messages,
                actionForPrimaryIssue(checked.getPrimaryIssue()));
    }

    static int messageResource(Nintendo3DsLaunchReadiness.Issue issue) {
        return switch (Objects.requireNonNull(issue)) {
        case CORE_MISSING -> R.string.n3ds_readiness_core_missing;
        case CORE_INVALID -> R.string.n3ds_readiness_core_invalid;
        case ARM64_REQUIRED -> R.string.n3ds_readiness_arm64_required;
        case VULKAN_REQUIRED -> R.string.n3ds_readiness_vulkan_required;
        case CONTENT_UNAVAILABLE -> R.string.n3ds_readiness_content_unavailable;
        case CONTENT_REQUIRES_EXTRACTION ->
                R.string.n3ds_readiness_content_requires_extraction;
        case CONTENT_ENCRYPTED -> R.string.n3ds_readiness_content_encrypted;
        case STORAGE_LOW -> R.string.n3ds_readiness_storage_low;
        case MII_FALLBACK_ACTIVE -> R.string.n3ds_readiness_mii_fallback;
        case MII_DATA_OPTIONAL -> R.string.n3ds_readiness_mii_optional;
        case DEVICE_UNQUALIFIED -> R.string.n3ds_readiness_device_unqualified;
        };
    }

    private static Action actionForPrimaryIssue(Nintendo3DsLaunchReadiness.Issue issue) {
        if (issue == null) {
            return Action.NONE;
        }
        return switch (issue) {
        case CORE_MISSING, CONTENT_UNAVAILABLE -> Action.RETRY;
        case CONTENT_REQUIRES_EXTRACTION -> Action.EXTRACT_CONTENT;
        case STORAGE_LOW -> Action.OPEN_STORAGE;
        case MII_FALLBACK_ACTIVE, MII_DATA_OPTIONAL -> Action.IMPORT_MII;
        case DEVICE_UNQUALIFIED -> Action.CONTINUE;
        case CORE_INVALID, ARM64_REQUIRED, VULKAN_REQUIRED, CONTENT_ENCRYPTED ->
                Action.UNDERSTOOD;
        };
    }
}
