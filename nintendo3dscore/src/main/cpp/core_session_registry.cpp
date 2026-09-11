// SPDX-License-Identifier: GPL-3.0-or-later
#include "core_session_registry.h"

#include <mutex>

namespace emuorbit::n3ds {
namespace {

std::mutex g_registryMutex;
const void* g_activeOwner = nullptr;

}  // namespace

bool claimCoreSession(const void* owner) {
    if (owner == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_registryMutex);
    if (g_activeOwner != nullptr) {
        return false;
    }
    g_activeOwner = owner;
    return true;
}

void releaseCoreSession(const void* owner) {
    std::lock_guard<std::mutex> lock(g_registryMutex);
    if (g_activeOwner == owner) {
        g_activeOwner = nullptr;
    }
}

}  // namespace emuorbit::n3ds
