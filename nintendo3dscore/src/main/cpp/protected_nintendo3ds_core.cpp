// SPDX-License-Identifier: GPL-3.0-or-later
#include "protected_nintendo3ds_core.h"

#include <mutex>

#include "protected_core_container.h"

namespace emuorbit::n3ds {
namespace {

std::mutex g_protectedCoreMutex;
void* g_protectedCoreHandle = nullptr;

}  // namespace

bool prepareProtectedNintendo3DsCore(
        JNIEnv* env,
        jobject assetManager,
        std::string& error) {
    std::lock_guard<std::mutex> lock(g_protectedCoreMutex);
    if (g_protectedCoreHandle != nullptr) {
        return true;
    }
    void* loadedHandle = nullptr;
    if (!emuorbit::loadProtectedCoreContainer(
            env, assetManager, &loadedHandle)) {
        error = "Unable to authenticate and load the Nintendo 3DS core";
        return false;
    }
    g_protectedCoreHandle = loadedHandle;
    return true;
}

void* protectedNintendo3DsCoreHandle() {
    std::lock_guard<std::mutex> lock(g_protectedCoreMutex);
    return g_protectedCoreHandle;
}

}  // namespace emuorbit::n3ds
