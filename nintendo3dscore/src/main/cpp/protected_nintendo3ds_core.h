// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <jni.h>

#include <string>

namespace emuorbit::n3ds {

bool prepareProtectedNintendo3DsCore(
        JNIEnv* env,
        jobject assetManager,
        std::string& error);

void* protectedNintendo3DsCoreHandle();

}  // namespace emuorbit::n3ds
