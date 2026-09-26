#pragma once

#include <jni.h>

namespace emuorbit {

bool loadProtectedCoreContainer(
        JNIEnv* env,
        jobject assetManagerObject,
        void** libraryHandle);

}  // namespace emuorbit
