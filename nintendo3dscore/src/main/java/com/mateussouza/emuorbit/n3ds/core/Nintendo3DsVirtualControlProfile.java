// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, complete controller layout with independent portrait and landscape presets. */
public final class Nintendo3DsVirtualControlProfile {
    private static final Nintendo3DsVirtualControlProfile IDENTITY = createIdentity();

    private final Map<Nintendo3DsVirtualControlOrientation,
            Map<Nintendo3DsVirtualControlGroup, Nintendo3DsVirtualControlTransform>> transforms;

    private Nintendo3DsVirtualControlProfile(
            Map<Nintendo3DsVirtualControlOrientation,
                    Map<Nintendo3DsVirtualControlGroup,
                            Nintendo3DsVirtualControlTransform>> transforms) {
        this.transforms = copyCompleteTransforms(transforms);
    }

    public static Nintendo3DsVirtualControlProfile identity() {
        return IDENTITY;
    }

    public static Nintendo3DsVirtualControlProfile create(
            Map<Nintendo3DsVirtualControlOrientation,
                    Map<Nintendo3DsVirtualControlGroup,
                            Nintendo3DsVirtualControlTransform>> transforms) {
        return new Nintendo3DsVirtualControlProfile(transforms);
    }

    public Nintendo3DsVirtualControlTransform getTransform(
            Nintendo3DsVirtualControlOrientation orientation,
            Nintendo3DsVirtualControlGroup group) {
        return Objects.requireNonNull(
                Objects.requireNonNull(transforms.get(orientation)).get(group));
    }

    public Map<Nintendo3DsVirtualControlOrientation,
            Map<Nintendo3DsVirtualControlGroup,
                    Nintendo3DsVirtualControlTransform>> getTransforms() {
        return transforms;
    }

    public Nintendo3DsVirtualControlProfile withTransform(
            Nintendo3DsVirtualControlOrientation orientation,
            Nintendo3DsVirtualControlGroup group,
            Nintendo3DsVirtualControlTransform transform) {
        Objects.requireNonNull(orientation);
        Objects.requireNonNull(group);
        Objects.requireNonNull(transform);
        EnumMap<Nintendo3DsVirtualControlOrientation,
                Map<Nintendo3DsVirtualControlGroup,
                        Nintendo3DsVirtualControlTransform>> changed = mutableCopy(transforms);
        changed.get(orientation).put(group, transform);
        return create(changed);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Nintendo3DsVirtualControlProfile)) {
            return false;
        }
        Nintendo3DsVirtualControlProfile profile = (Nintendo3DsVirtualControlProfile) other;
        return transforms.equals(profile.transforms);
    }

    @Override
    public int hashCode() {
        return transforms.hashCode();
    }

    private static Nintendo3DsVirtualControlProfile createIdentity() {
        EnumMap<Nintendo3DsVirtualControlOrientation,
                Map<Nintendo3DsVirtualControlGroup,
                        Nintendo3DsVirtualControlTransform>> values =
                new EnumMap<>(Nintendo3DsVirtualControlOrientation.class);
        for (Nintendo3DsVirtualControlOrientation orientation
                : Nintendo3DsVirtualControlOrientation.values()) {
            EnumMap<Nintendo3DsVirtualControlGroup, Nintendo3DsVirtualControlTransform> groups =
                    new EnumMap<>(Nintendo3DsVirtualControlGroup.class);
            for (Nintendo3DsVirtualControlGroup group
                    : Nintendo3DsVirtualControlGroup.values()) {
                groups.put(group, Nintendo3DsVirtualControlTransform.identity());
            }
            values.put(orientation, groups);
        }
        return new Nintendo3DsVirtualControlProfile(values);
    }

    private static Map<Nintendo3DsVirtualControlOrientation,
            Map<Nintendo3DsVirtualControlGroup,
                    Nintendo3DsVirtualControlTransform>> copyCompleteTransforms(
                            Map<Nintendo3DsVirtualControlOrientation,
                                    Map<Nintendo3DsVirtualControlGroup,
                                            Nintendo3DsVirtualControlTransform>> source) {
        if (source == null
                || source.size() != Nintendo3DsVirtualControlOrientation.values().length) {
            throw new IllegalArgumentException("Every orientation requires a 3DS layout");
        }
        EnumMap<Nintendo3DsVirtualControlOrientation,
                Map<Nintendo3DsVirtualControlGroup,
                        Nintendo3DsVirtualControlTransform>> copied =
                new EnumMap<>(Nintendo3DsVirtualControlOrientation.class);
        for (Nintendo3DsVirtualControlOrientation orientation
                : Nintendo3DsVirtualControlOrientation.values()) {
            Map<Nintendo3DsVirtualControlGroup, Nintendo3DsVirtualControlTransform>
                    sourceGroups = source.get(orientation);
            if (sourceGroups == null
                    || sourceGroups.size() != Nintendo3DsVirtualControlGroup.values().length) {
                throw new IllegalArgumentException(
                        "Every 3DS virtual-control group requires a transform");
            }
            EnumMap<Nintendo3DsVirtualControlGroup, Nintendo3DsVirtualControlTransform> groups =
                    new EnumMap<>(Nintendo3DsVirtualControlGroup.class);
            for (Nintendo3DsVirtualControlGroup group
                    : Nintendo3DsVirtualControlGroup.values()) {
                groups.put(group, Objects.requireNonNull(sourceGroups.get(group)));
            }
            copied.put(orientation, Collections.unmodifiableMap(groups));
        }
        return Collections.unmodifiableMap(copied);
    }

    private static EnumMap<Nintendo3DsVirtualControlOrientation,
            Map<Nintendo3DsVirtualControlGroup,
                    Nintendo3DsVirtualControlTransform>> mutableCopy(
                            Map<Nintendo3DsVirtualControlOrientation,
                                    Map<Nintendo3DsVirtualControlGroup,
                                            Nintendo3DsVirtualControlTransform>> source) {
        EnumMap<Nintendo3DsVirtualControlOrientation,
                Map<Nintendo3DsVirtualControlGroup,
                        Nintendo3DsVirtualControlTransform>> copied =
                new EnumMap<>(Nintendo3DsVirtualControlOrientation.class);
        for (Nintendo3DsVirtualControlOrientation orientation
                : Nintendo3DsVirtualControlOrientation.values()) {
            copied.put(
                    orientation,
                    new EnumMap<>(Objects.requireNonNull(source.get(orientation))));
        }
        return copied;
    }
}
