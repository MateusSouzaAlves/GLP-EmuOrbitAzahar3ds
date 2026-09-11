// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Screen arrangements supported by the pinned Azahar libretro core. */
public enum Nintendo3DsScreenLayout {
    DEFAULT("default", false),
    SIDE_BY_SIDE("side_by_side", false),
    LARGE_TOP("large_screen", false),
    LARGE_BOTTOM("large_screen", true),
    SINGLE_TOP("single_screen", false),
    SINGLE_BOTTOM("single_screen", true);

    private final String coreValue;
    private final boolean bottomScreenProminent;

    Nintendo3DsScreenLayout(String coreValue, boolean bottomScreenProminent) {
        this.coreValue = coreValue;
        this.bottomScreenProminent = bottomScreenProminent;
    }

    String getCoreValue() {
        return coreValue;
    }

    String getCoreSwapValue() {
        return bottomScreenProminent ? "Bottom" : "Top";
    }
}
