// SPDX-License-Identifier: GPL-3.0-or-later
#include <cstdio>
#include <cstdlib>

#include "libretro_bootstrap_abi.h"

#define N3DS_TEST_EXPORT extern "C" __attribute__((visibility("default")))

N3DS_TEST_EXPORT unsigned retro_api_version() {
    return emuorbit::n3ds::kLibretroApiVersion;
}

N3DS_TEST_EXPORT void retro_get_system_info(emuorbit::n3ds::SystemInfo* info) {
    info->libraryName = "Azahar";
    info->libraryVersion = "n3ds-04-mock";
    info->validExtensions = "3ds|3dsx|cci|cxi|app";
    info->needFullPath = true;
    info->blockExtract = false;
}

N3DS_TEST_EXPORT void retro_set_environment(
        emuorbit::n3ds::EnvironmentCallback callback) {
    unsigned optionsVersion = 0;
    int dummyOptions = 1;
    int dummyControllers = 1;
    int dummyVfs = 1;
    callback(emuorbit::n3ds::kEnvironmentGetVfsInterface, &dummyVfs);
    callback(emuorbit::n3ds::kEnvironmentGetCoreOptionsVersion, &optionsVersion);
    callback(emuorbit::n3ds::kEnvironmentSetCoreOptionsV2, &dummyOptions);
    callback(emuorbit::n3ds::kEnvironmentSetControllerInfo, &dummyControllers);
}

// The production bootstrap deliberately neither resolves nor calls this symbol.
N3DS_TEST_EXPORT bool retro_load_game(const void*) {
    const char* sentinel = std::getenv("N3DS_LOAD_GAME_SENTINEL");
    if (sentinel != nullptr) {
        if (std::FILE* output = std::fopen(sentinel, "wb")) {
            std::fputs("called", output);
            std::fclose(output);
        }
    }
    return false;
}
