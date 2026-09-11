// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

namespace emuorbit::n3ds {

bool claimCoreSession(const void* owner);
void releaseCoreSession(const void* owner);

}  // namespace emuorbit::n3ds
