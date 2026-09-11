// SPDX-License-Identifier: MIT
// Minimal declarations derived from libretro.h at Azahar 2126.0 / libretro-common
// 7fc7feeddca391be65c94e6541381467684b814d. Kept intentionally smaller than the
// gameplay ABI so N3DS-04 cannot load content before the hardware-render host exists.
#pragma once

#include <cstdint>

namespace emuorbit::n3ds {

constexpr unsigned kLibretroApiVersion = 1;
constexpr unsigned kEnvironmentSetVariables = 16;
constexpr unsigned kEnvironmentSetControllerInfo = 35;
constexpr unsigned kEnvironmentGetVfsInterface = 45 | 0x10000;
constexpr unsigned kEnvironmentGetCoreOptionsVersion = 52;
constexpr unsigned kEnvironmentSetCoreOptions = 53;
constexpr unsigned kEnvironmentSetCoreOptionsInternational = 54;
constexpr unsigned kEnvironmentSetCoreOptionsV2 = 67;
constexpr unsigned kEnvironmentSetCoreOptionsV2International = 68;

using EnvironmentCallback = bool (*)(unsigned command, void* data);
using ApiVersionFunction = unsigned (*)();

struct SystemInfo {
    const char* libraryName;
    const char* libraryVersion;
    const char* validExtensions;
    bool needFullPath;
    bool blockExtract;
};

using GetSystemInfoFunction = void (*)(SystemInfo* info);
using SetEnvironmentFunction = void (*)(EnvironmentCallback callback);

}  // namespace emuorbit::n3ds
