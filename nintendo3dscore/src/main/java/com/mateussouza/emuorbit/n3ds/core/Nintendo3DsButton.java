// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Digital controls exposed by the pinned Azahar libretro input contract. */
public enum Nintendo3DsButton {
    B(0),
    Y(1),
    SELECT(2),
    START(3),
    UP(4),
    DOWN(5),
    LEFT(6),
    RIGHT(7),
    A(8),
    X(9),
    L(10),
    R(11),
    ZL(12),
    ZR(13),
    HOME_SWAP_SCREENS(14);

    private final int mask;

    Nintendo3DsButton(int libretroId) {
        mask = 1 << libretroId;
    }

    int getMask() {
        return mask;
    }
}
