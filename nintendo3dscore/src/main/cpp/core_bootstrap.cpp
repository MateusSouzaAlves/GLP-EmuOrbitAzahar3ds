// SPDX-License-Identifier: GPL-3.0-or-later
#include "core_bootstrap.h"

#include <dlfcn.h>

#include <algorithm>
#include <cctype>
#include <cstring>
#include <iterator>
#include <mutex>
#include <string_view>

#include "libretro_bootstrap_abi.h"
#include "core_session_registry.h"
#include "protected_nintendo3ds_core.h"

namespace emuorbit::n3ds {
namespace {

std::mutex g_sessionMutex;
CoreEnvironmentReport* g_activeEnvironment = nullptr;

template <typename Function>
bool loadSymbol(void* library, const char* name, Function& target) {
    dlerror();
    target = reinterpret_cast<Function>(dlsym(library, name));
    const char* error = dlerror();
    return target != nullptr && error == nullptr;
}

bool environmentCallback(unsigned command, void* data) {
    CoreEnvironmentReport* report = g_activeEnvironment;
    if (report == nullptr) {
        return false;
    }
    report->callbackCount++;
    switch (command) {
    case kEnvironmentGetCoreOptionsVersion:
        if (data == nullptr) {
            return false;
        }
        *static_cast<unsigned*>(data) = 2;
        report->coreOptionsVersionRequested = true;
        return true;
    case kEnvironmentSetVariables:
    case kEnvironmentSetCoreOptions:
    case kEnvironmentSetCoreOptionsInternational:
    case kEnvironmentSetCoreOptionsV2:
    case kEnvironmentSetCoreOptionsV2International:
        report->coreOptionsRegistered = data != nullptr;
        return data != nullptr;
    case kEnvironmentSetControllerInfo:
        report->controllerInfoRegistered = data != nullptr;
        return data != nullptr;
    case kEnvironmentGetVfsInterface:
        report->vfsInterfaceRequested = true;
        return false;
    default:
        return false;
    }
}

bool nonEmpty(const char* value) {
    return value != nullptr && value[0] != '\0';
}

bool includesExtension(std::string_view extensions, std::string_view required) {
    size_t start = 0;
    while (start <= extensions.size()) {
        const size_t end = extensions.find('|', start);
        const size_t length = end == std::string_view::npos
                ? extensions.size() - start
                : end - start;
        if (length == required.size()) {
            bool equal = true;
            for (size_t index = 0; index < length; ++index) {
                const unsigned char value = static_cast<unsigned char>(
                        extensions[start + index]);
                if (static_cast<char>(std::tolower(value)) != required[index]) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return true;
            }
        }
        if (end == std::string_view::npos) {
            break;
        }
        start = end + 1;
    }
    return false;
}

bool hasRequiredExtensions(std::string_view extensions) {
    constexpr std::string_view required[] = {"3ds", "3dsx", "cci", "cxi"};
    return std::all_of(std::begin(required), std::end(required),
                       [extensions](std::string_view item) {
                           return includesExtension(extensions, item);
                       });
}

}  // namespace

std::unique_ptr<CoreBootstrapSession> CoreBootstrapSession::open(
        const char* libraryPath,
        std::string& error) {
    constexpr const char* kPackagedCoreSoname = "azahar_libretro.so";
    if (libraryPath == nullptr
            || (libraryPath[0] != '/' && std::strcmp(libraryPath, kPackagedCoreSoname) != 0)) {
        error = "The Nintendo 3DS core path must be absolute or the approved split SONAME";
        return nullptr;
    }

    auto session = std::unique_ptr<CoreBootstrapSession>(new CoreBootstrapSession());
    if (!claimCoreSession(session.get())) {
        error = "Another Nintendo 3DS core session is active";
        return nullptr;
    }
    session->ownsCoreSession_ = true;

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (std::strcmp(libraryPath, kPackagedCoreSoname) == 0) {
        session->library_ = protectedNintendo3DsCoreHandle();
        session->libraryProcessResident_ = true;
    } else {
        session->library_ = dlopen(libraryPath, RTLD_NOW | RTLD_LOCAL);
    }
    if (session->library_ == nullptr) {
        error = "Unable to load the Nintendo 3DS core library";
        return nullptr;
    }

    ApiVersionFunction apiVersion = nullptr;
    GetSystemInfoFunction getSystemInfo = nullptr;
    SetEnvironmentFunction setEnvironment = nullptr;
    if (!loadSymbol(session->library_, "retro_api_version", apiVersion)
            || !loadSymbol(session->library_, "retro_get_system_info", getSystemInfo)
            || !loadSymbol(session->library_, "retro_set_environment", setEnvironment)) {
        error = "The Nintendo 3DS core is missing its bootstrap ABI";
        return nullptr;
    }

    session->info_.apiVersion = apiVersion();
    SystemInfo systemInfo{};
    getSystemInfo(&systemInfo);
    if (!nonEmpty(systemInfo.libraryName)
            || !nonEmpty(systemInfo.libraryVersion)
            || !nonEmpty(systemInfo.validExtensions)) {
        error = "The Nintendo 3DS core returned incomplete system information";
        return nullptr;
    }
    session->info_.libraryName = systemInfo.libraryName;
    session->info_.libraryVersion = systemInfo.libraryVersion;
    session->info_.validExtensions = systemInfo.validExtensions;
    session->info_.needFullPath = systemInfo.needFullPath;
    if (session->info_.apiVersion != kLibretroApiVersion
            || session->info_.libraryName != "Azahar"
            || !session->info_.needFullPath
            || !hasRequiredExtensions(session->info_.validExtensions)) {
        error = "The native library is not the expected Nintendo 3DS core";
        return nullptr;
    }

    g_activeEnvironment = &session->info_.environment;
    session->ownsActiveEnvironment_ = true;
    setEnvironment(environmentCallback);
    if (!session->info_.environment.coreOptionsVersionRequested
            || !session->info_.environment.coreOptionsRegistered
            || !session->info_.environment.controllerInfoRegistered) {
        g_activeEnvironment = nullptr;
        session->ownsActiveEnvironment_ = false;
        error = "The Nintendo 3DS core rejected the bootstrap environment";
        return nullptr;
    }
    return session;
}

CoreBootstrapSession::~CoreBootstrapSession() {
    if (ownsActiveEnvironment_) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        if (g_activeEnvironment == &info_.environment) {
            g_activeEnvironment = nullptr;
        }
        ownsActiveEnvironment_ = false;
    }
    if (library_ != nullptr && !libraryProcessResident_) {
        dlclose(library_);
        library_ = nullptr;
    }
    if (ownsCoreSession_) {
        releaseCoreSession(this);
        ownsCoreSession_ = false;
    }
}

const CoreBootstrapInfo& CoreBootstrapSession::info() const {
    return info_;
}

}  // namespace emuorbit::n3ds
