// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <memory>
#include <string>

namespace emuorbit::n3ds {

struct CoreEnvironmentReport {
    unsigned callbackCount = 0;
    bool coreOptionsVersionRequested = false;
    bool coreOptionsRegistered = false;
    bool controllerInfoRegistered = false;
    bool vfsInterfaceRequested = false;
};

struct CoreBootstrapInfo {
    std::string libraryName;
    std::string libraryVersion;
    std::string validExtensions;
    bool needFullPath = false;
    unsigned apiVersion = 0;
    CoreEnvironmentReport environment;
};

class CoreBootstrapSession final {
public:
    static std::unique_ptr<CoreBootstrapSession> open(
            const char* libraryPath,
            std::string& error);

    ~CoreBootstrapSession();

    CoreBootstrapSession(const CoreBootstrapSession&) = delete;
    CoreBootstrapSession& operator=(const CoreBootstrapSession&) = delete;

    const CoreBootstrapInfo& info() const;

private:
    CoreBootstrapSession() = default;

    void* library_ = nullptr;
    bool ownsActiveEnvironment_ = false;
    bool ownsCoreSession_ = false;
    CoreBootstrapInfo info_;
};

}  // namespace emuorbit::n3ds
