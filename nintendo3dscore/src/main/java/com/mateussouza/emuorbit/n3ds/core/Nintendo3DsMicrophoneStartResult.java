// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Result of an explicit request to enable Android microphone capture for a 3DS session. */
public enum Nintendo3DsMicrophoneStartResult {
    ACTIVE,
    WAITING_FOR_RESUME,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    DISABLED
}
