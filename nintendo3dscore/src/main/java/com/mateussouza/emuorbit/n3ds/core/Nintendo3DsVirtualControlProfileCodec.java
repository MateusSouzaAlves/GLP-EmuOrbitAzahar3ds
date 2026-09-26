// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/** Bounded and versioned binary representation of one complete 3DS controller profile. */
final class Nintendo3DsVirtualControlProfileCodec {
    static final int MAXIMUM_DOCUMENT_BYTES = 4 * 1024;
    private static final int MAGIC = 0x4E335650; // N3VP
    private static final int CURRENT_VERSION = 1;

    enum Status {
        VALID,
        CORRUPT,
        UNSUPPORTED_VERSION
    }

    static final class Result {
        private final Status status;
        private final Nintendo3DsVirtualControlProfile profile;

        private Result(Status status, Nintendo3DsVirtualControlProfile profile) {
            this.status = status;
            this.profile = profile;
        }

        Status getStatus() {
            return status;
        }

        Nintendo3DsVirtualControlProfile getProfile() {
            return profile;
        }
    }

    private Nintendo3DsVirtualControlProfileCodec() {
    }

    static byte[] encode(Nintendo3DsVirtualControlProfile profile) throws IOException {
        if (profile == null) {
            throw new IllegalArgumentException("Profile is required");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(CURRENT_VERSION);
            for (Nintendo3DsVirtualControlOrientation orientation
                    : Nintendo3DsVirtualControlOrientation.values()) {
                for (Nintendo3DsVirtualControlGroup group
                        : Nintendo3DsVirtualControlGroup.values()) {
                    Nintendo3DsVirtualControlTransform transform =
                            profile.getTransform(orientation, group);
                    output.writeFloat(transform.getNormalizedOffsetX());
                    output.writeFloat(transform.getNormalizedOffsetY());
                    output.writeFloat(transform.getScale());
                    output.writeBoolean(transform.isVisible());
                }
            }
        }
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAXIMUM_DOCUMENT_BYTES) {
            throw new IOException("3DS virtual-control profile exceeds its storage limit");
        }
        return encoded;
    }

    static Result decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_DOCUMENT_BYTES) {
            return corrupt();
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                return corrupt();
            }
            int version = input.readInt();
            if (version < 1 || version > CURRENT_VERSION) {
                return new Result(Status.UNSUPPORTED_VERSION, null);
            }
            EnumMap<Nintendo3DsVirtualControlOrientation,
                    Map<Nintendo3DsVirtualControlGroup,
                            Nintendo3DsVirtualControlTransform>> transforms =
                    new EnumMap<>(Nintendo3DsVirtualControlOrientation.class);
            for (Nintendo3DsVirtualControlOrientation orientation
                    : Nintendo3DsVirtualControlOrientation.values()) {
                EnumMap<Nintendo3DsVirtualControlGroup, Nintendo3DsVirtualControlTransform>
                        groups = new EnumMap<>(Nintendo3DsVirtualControlGroup.class);
                for (Nintendo3DsVirtualControlGroup group
                        : Nintendo3DsVirtualControlGroup.values()) {
                    groups.put(group, new Nintendo3DsVirtualControlTransform(
                            input.readFloat(),
                            input.readFloat(),
                            input.readFloat(),
                            input.readBoolean()));
                }
                transforms.put(orientation, groups);
            }
            if (input.available() != 0) {
                return corrupt();
            }
            return new Result(
                    Status.VALID,
                    Nintendo3DsVirtualControlProfile.create(transforms));
        } catch (IOException | IllegalArgumentException | ClassCastException ignored) {
            return corrupt();
        }
    }

    private static Result corrupt() {
        return new Result(Status.CORRUPT, null);
    }
}
