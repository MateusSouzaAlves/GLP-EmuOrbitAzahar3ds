// SPDX-License-Identifier: GPL-3.0-or-later
#include <cstdlib>
#include <cstdio>
#include <memory>
#include <string>

#include <unistd.h>

#include "core_bootstrap.h"

int main(int argc, char** argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: %s <core.so> <load-game-sentinel>\n", argv[0]);
        return 64;
    }

    std::string error;
    std::unique_ptr<emuorbit::n3ds::CoreBootstrapSession> session =
            emuorbit::n3ds::CoreBootstrapSession::open(argv[1], error);
    if (session == nullptr) {
        std::fprintf(stderr, "BOOTSTRAP_ERROR=%s\n", error.c_str());
        return 65;
    }

    const auto& info = session->info();
    std::printf("BOOTSTRAP_LIBRARY=%s\n", info.libraryName.c_str());
    std::printf("BOOTSTRAP_VERSION=%s\n", info.libraryVersion.c_str());
    std::printf("BOOTSTRAP_EXTENSIONS=%s\n", info.validExtensions.c_str());
    std::printf("BOOTSTRAP_NEEDS_FULLPATH=%d\n", info.needFullPath ? 1 : 0);
    std::printf("BOOTSTRAP_API=%u\n", info.apiVersion);
    std::printf("BOOTSTRAP_ENV_CALLS=%u\n", info.environment.callbackCount);
    std::printf("BOOTSTRAP_CORE_OPTIONS=%d\n",
                info.environment.coreOptionsRegistered ? 1 : 0);
    std::printf("BOOTSTRAP_CONTROLLERS=%d\n",
                info.environment.controllerInfoRegistered ? 1 : 0);
    std::printf("BOOTSTRAP_VFS_REQUESTED=%d\n",
                info.environment.vfsInterfaceRequested ? 1 : 0);
    session.reset();

    const bool loadGameCalled = access(argv[2], F_OK) == 0;
    std::printf("BOOTSTRAP_LOAD_GAME_CALLS=%d\n", loadGameCalled ? 1 : 0);
    if (loadGameCalled) {
        std::fprintf(stderr, "BOOTSTRAP_ERROR=content was loaded before hardware host\n");
        return 66;
    }
    std::printf("BOOTSTRAP_RESULT=PASS\n");
    return EXIT_SUCCESS;
}
