// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>

#include <array>
#include <iterator>
#include <memory>
#include <string>

#include "core_bootstrap.h"
#include "jni_core_gameplay_session.h"
#include "jni_hardware_render_host.h"

namespace {

using emuorbit::n3ds::CoreBootstrapSession;

void throwIOException(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

jobjectArray inspectCore(JNIEnv* env, jclass, jstring libraryPath) {
    if (libraryPath == nullptr) {
        throwIOException(env, "Nintendo 3DS core path is required");
        return nullptr;
    }
    const char* path = env->GetStringUTFChars(libraryPath, nullptr);
    if (path == nullptr) {
        return nullptr;
    }
    std::string error;
    std::unique_ptr<CoreBootstrapSession> session =
            CoreBootstrapSession::open(path, error);
    env->ReleaseStringUTFChars(libraryPath, path);
    if (session == nullptr) {
        throwIOException(env, error.c_str());
        return nullptr;
    }

    const auto& info = session->info();
    const auto& environment = info.environment;
    const std::array<std::string, 9> values = {
            info.libraryName,
            info.libraryVersion,
            info.validExtensions,
            info.needFullPath ? "1" : "0",
            std::to_string(info.apiVersion),
            std::to_string(environment.callbackCount),
            environment.coreOptionsRegistered ? "1" : "0",
            environment.controllerInfoRegistered ? "1" : "0",
            environment.vfsInterfaceRequested ? "1" : "0",
    };
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(values.size()), stringClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }
    for (size_t index = 0; index < values.size(); ++index) {
        jstring value = env->NewStringUTF(values[index].c_str());
        if (value == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }
    return result;
}

const JNINativeMethod kMethods[] = {
        {const_cast<char*>("n"), const_cast<char*>("(Ljava/lang/String;)[Ljava/lang/String;"),
         reinterpret_cast<void*>(inspectCore)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK
            || env == nullptr) {
        return JNI_ERR;
    }
    jclass bridgeClass = env->FindClass(
            "com/mateussouza/emuorbit/n3ds/core/Nintendo3DsCoreBootstrap");
    if (bridgeClass == nullptr
            || env->RegisterNatives(
                    bridgeClass,
                    kMethods,
                    static_cast<jint>(std::size(kMethods))) != JNI_OK
            || !emuorbit::n3ds::registerCoreGameplaySession(env)
            || !emuorbit::n3ds::registerHardwareRenderHost(env)) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
