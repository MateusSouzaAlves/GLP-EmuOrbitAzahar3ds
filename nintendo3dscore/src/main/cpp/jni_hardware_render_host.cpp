// SPDX-License-Identifier: GPL-3.0-or-later
#include "jni_hardware_render_host.h"

#include <android/native_window_jni.h>

#include <array>
#include <cstdint>
#include <iterator>
#include <memory>
#include <string>

#include "vulkan_render_host.h"

namespace emuorbit::n3ds {
namespace {

void throwException(JNIEnv* env, const char* className, const char* message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

VulkanRenderHost* requireHost(JNIEnv* env, jlong handle) {
    auto* host = reinterpret_cast<VulkanRenderHost*>(static_cast<intptr_t>(handle));
    if (host == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "O host Vulkan 3DS está fechado.");
        return nullptr;
    }
    if (!host->isOwnerThread()) {
        throwException(
                env,
                "java/lang/IllegalStateException",
                "O host Vulkan 3DS deve permanecer na thread proprietária.");
        return nullptr;
    }
    return host;
}

jlong createRenderHost(
        JNIEnv* env,
        jclass,
        jobject surface,
        jint requestedWidth,
        jint requestedHeight) {
    if (surface == nullptr || requestedWidth <= 0 || requestedHeight <= 0) {
        throwException(
                env,
                "java/io/IOException",
                "Uma Surface válida e dimensões positivas são obrigatórias.");
        return 0;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        throwException(env, "java/io/IOException", "Não foi possível acessar a Surface Android.");
        return 0;
    }
    std::string error;
    std::unique_ptr<VulkanRenderHost> host = VulkanRenderHost::create(
            window,
            static_cast<uint32_t>(requestedWidth),
            static_cast<uint32_t>(requestedHeight),
            nullptr,
            error);
    ANativeWindow_release(window);
    if (host == nullptr) {
        throwException(env, "java/io/IOException", error.c_str());
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(host.release()));
}

jobjectArray presentDiagnosticFrame(JNIEnv* env, jclass, jlong handle, jint rgba) {
    VulkanRenderHost* host = requireHost(env, handle);
    if (host == nullptr) {
        return nullptr;
    }
    std::string error;
    if (!host->presentDiagnosticFrame(static_cast<uint32_t>(rgba), error)) {
        throwException(env, "java/io/IOException", error.c_str());
        return nullptr;
    }
    const VulkanRenderReport& report = host->report();
    const std::array<std::string, 7> values = {
            report.deviceName,
            std::to_string(VK_VERSION_MAJOR(report.apiVersion)),
            std::to_string(VK_VERSION_MINOR(report.apiVersion)),
            std::to_string(VK_VERSION_PATCH(report.apiVersion)),
            std::to_string(report.surfaceFormat),
            std::to_string(report.swapchainImageCount),
            std::to_string(report.presentedFrames),
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

void destroyRenderHost(JNIEnv* env, jclass, jlong handle) {
    VulkanRenderHost* host = requireHost(env, handle);
    if (host != nullptr) {
        delete host;
    }
}

const JNINativeMethod kMethods[] = {
        {const_cast<char*>("c"), const_cast<char*>("(Landroid/view/Surface;II)J"),
         reinterpret_cast<void*>(createRenderHost)},
        {const_cast<char*>("p"), const_cast<char*>("(JI)[Ljava/lang/String;"),
         reinterpret_cast<void*>(presentDiagnosticFrame)},
        {const_cast<char*>("d"), const_cast<char*>("(J)V"),
         reinterpret_cast<void*>(destroyRenderHost)},
};

}  // namespace

bool registerHardwareRenderHost(JNIEnv* env) {
    jclass hostClass = env->FindClass(
            "com/mateussouza/emuorbit/n3ds/core/Nintendo3DsHardwareRenderHost");
    return hostClass != nullptr
            && env->RegisterNatives(
                       hostClass,
                       kMethods,
                       static_cast<jint>(std::size(kMethods))) == JNI_OK;
}

}  // namespace emuorbit::n3ds
