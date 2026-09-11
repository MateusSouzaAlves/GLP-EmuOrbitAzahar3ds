// SPDX-License-Identifier: GPL-3.0-or-later
#include "jni_core_gameplay_session.h"

#include <android/native_window_jni.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <iterator>
#include <limits>
#include <memory>
#include <string>

#include "core_gameplay_session.h"

namespace emuorbit::n3ds {
namespace {

void throwException(JNIEnv* env, const char* className, const char* message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

bool copyString(JNIEnv* env, jstring source, std::string& destination) {
    if (source == nullptr) {
        return false;
    }
    const char* characters = env->GetStringUTFChars(source, nullptr);
    if (characters == nullptr) {
        return false;
    }
    destination = characters;
    env->ReleaseStringUTFChars(source, characters);
    return true;
}

CoreGameplaySession* requireSession(JNIEnv* env, jlong handle) {
    auto* session = reinterpret_cast<CoreGameplaySession*>(static_cast<intptr_t>(handle));
    if (session == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "A sessão 3DS está fechada.");
        return nullptr;
    }
    if (!session->isOwnerThread()) {
        throwException(
                env,
                "java/lang/IllegalStateException",
                "A sessão 3DS deve permanecer na thread proprietária.");
        return nullptr;
    }
    return session;
}

jlong createSession(
        JNIEnv* env,
        jclass,
        jobject surface,
        jint width,
        jint height,
        jstring libraryPath,
        jstring contentPath,
        jstring systemDirectory,
        jstring saveDirectory,
        jstring screenLayout,
        jstring swapScreen,
        jstring performanceProfile) {
    if (surface == nullptr || width <= 0 || height <= 0) {
        throwException(env, "java/io/IOException", "A Surface 3DS e suas dimensões são obrigatórias.");
        return 0;
    }
    std::string library;
    std::string content;
    std::string system;
    std::string save;
    std::string layout;
    std::string swap;
    std::string profile;
    if (!copyString(env, libraryPath, library)
            || !copyString(env, contentPath, content)
            || !copyString(env, systemDirectory, system)
            || !copyString(env, saveDirectory, save)
            || !copyString(env, screenLayout, layout)
            || !copyString(env, swapScreen, swap)
            || !copyString(env, performanceProfile, profile)) {
        if (!env->ExceptionCheck()) {
            throwException(env, "java/io/IOException", "Os caminhos da sessão 3DS são obrigatórios.");
        }
        return 0;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        throwException(env, "java/io/IOException", "Não foi possível acessar a Surface 3DS.");
        return 0;
    }
    std::string error;
    std::unique_ptr<CoreGameplaySession> session = CoreGameplaySession::open(
            window,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            library.c_str(),
            content.c_str(),
            system.c_str(),
            save.c_str(),
            layout.c_str(),
            swap.c_str(),
            profile.c_str(),
            error);
    ANativeWindow_release(window);
    if (session == nullptr) {
        throwException(env, "java/io/IOException", error.c_str());
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session.release()));
}

jobjectArray runFrame(JNIEnv* env, jclass, jlong handle) {
    CoreGameplaySession* session = requireSession(env, handle);
    if (session == nullptr) {
        return nullptr;
    }
    std::string error;
    if (!session->runFrame(error)) {
        throwException(env, "java/io/IOException", error.c_str());
        return nullptr;
    }
    const CoreGameplayReport report = session->report();
    const std::array<std::string, 64> values = {
            report.deviceName,
            std::to_string(VK_VERSION_MAJOR(report.apiVersion)),
            std::to_string(VK_VERSION_MINOR(report.apiVersion)),
            std::to_string(VK_VERSION_PATCH(report.apiVersion)),
            std::to_string(report.surfaceFormat),
            std::to_string(report.swapchainImageCount),
            std::to_string(report.presentedFrames),
            std::to_string(report.videoFrames),
            std::to_string(report.audioFrames),
            std::to_string(report.environmentCallbacks),
            std::to_string(report.vfsOpenCalls),
            std::to_string(report.vfsReadCalls),
            std::to_string(report.vfsBytesRead),
            std::to_string(report.lastWidth),
            std::to_string(report.lastHeight),
            report.preferredVulkan ? "1" : "0",
            report.hardwareRenderNegotiated ? "1" : "0",
            report.negotiationInterfaceReceived ? "1" : "0",
            report.hardwareInterfaceProvided ? "1" : "0",
            report.contextReset ? "1" : "0",
            report.contentOpenedThroughVfs ? "1" : "0",
            std::to_string(report.audioSampleRate),
            std::to_string(report.audioQueuedFrames),
            std::to_string(report.audioDroppedFrames),
            std::to_string(report.audioPeakQueuedFrames),
            std::to_string(report.inputPollCallbacks),
            std::to_string(report.inputStateCallbacks),
            std::to_string(report.joypadStateCallbacks),
            std::to_string(report.analogStateCallbacks),
            std::to_string(report.lastPolledButtonMask),
            std::to_string(report.lastPolledCirclePadX),
            std::to_string(report.lastPolledCirclePadY),
            std::to_string(report.rightAnalogStateCallbacks),
            std::to_string(report.pointerStateCallbacks),
            std::to_string(report.lastPolledCStickX),
            std::to_string(report.lastPolledCStickY),
            std::to_string(report.lastPolledPointerX),
            std::to_string(report.lastPolledPointerY),
            report.lastPolledPointerPressed ? "1" : "0",
            std::to_string(report.sensorStateCallbacks),
            std::to_string(report.sensorInputCallbacks),
            std::to_string(report.sensorSamplingRateHz),
            std::to_string(report.lastPolledAccelerometerX),
            std::to_string(report.lastPolledAccelerometerY),
            std::to_string(report.lastPolledAccelerometerZ),
            std::to_string(report.lastPolledGyroscopeX),
            std::to_string(report.lastPolledGyroscopeY),
            std::to_string(report.lastPolledGyroscopeZ),
            report.microphoneFrontendSelected ? "1" : "0",
            std::to_string(report.microphoneOpenCallbacks),
            std::to_string(report.microphoneCloseCallbacks),
            std::to_string(report.microphoneStateCallbacks),
            std::to_string(report.microphoneReadCallbacks),
            std::to_string(report.microphoneSamplingRateHz),
            report.microphoneActive ? "1" : "0",
            std::to_string(report.microphoneCapturedSamples),
            std::to_string(report.microphoneQueuedSamples),
            std::to_string(report.microphoneDroppedSamples),
            std::to_string(report.microphoneDeliveredSamples),
            std::to_string(report.microphoneSilentSamples),
            report.performanceProfile,
            std::to_string(report.performanceOptionRequests),
            std::to_string(report.resolutionFactor),
            report.diskShaderCacheEnabled ? "1" : "0",
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

jint drainAudio(JNIEnv* env, jclass, jlong handle, jobject output, jint capacityFrames) {
    CoreGameplaySession* session = requireSession(env, handle);
    if (session == nullptr) {
        return 0;
    }
    if (output == nullptr || capacityFrames <= 0
            || capacityFrames > std::numeric_limits<jint>::max() / 4) {
        throwException(env, "java/lang/IllegalArgumentException", "Buffer de áudio 3DS inválido.");
        return 0;
    }
    auto* samples = static_cast<int16_t*>(env->GetDirectBufferAddress(output));
    const jlong byteCapacity = env->GetDirectBufferCapacity(output);
    const jlong requiredBytes = static_cast<jlong>(capacityFrames) * 4;
    if (samples == nullptr || byteCapacity < requiredBytes) {
        throwException(
                env,
                "java/lang/IllegalArgumentException",
                "O áudio 3DS requer um ByteBuffer direto com capacidade estéreo suficiente.");
        return 0;
    }
    return static_cast<jint>(
            session->drainAudio(samples, static_cast<size_t>(capacityFrames)));
}

void updateInput(
        JNIEnv* env,
        jclass,
        jlong handle,
        jint buttonMask,
        jint circlePadX,
        jint circlePadY,
        jint cStickX,
        jint cStickY,
        jint pointerX,
        jint pointerY,
        jboolean pointerPressed,
        jfloat accelerometerX,
        jfloat accelerometerY,
        jfloat accelerometerZ,
        jfloat gyroscopeX,
        jfloat gyroscopeY,
        jfloat gyroscopeZ) {
    CoreGameplaySession* session = requireSession(env, handle);
    if (session == nullptr) {
        return;
    }
    if (buttonMask < 0 || buttonMask > std::numeric_limits<uint16_t>::max()
            || circlePadX < std::numeric_limits<int16_t>::min()
            || circlePadX > std::numeric_limits<int16_t>::max()
            || circlePadY < std::numeric_limits<int16_t>::min()
            || circlePadY > std::numeric_limits<int16_t>::max()
            || cStickX < std::numeric_limits<int16_t>::min()
            || cStickX > std::numeric_limits<int16_t>::max()
            || cStickY < std::numeric_limits<int16_t>::min()
            || cStickY > std::numeric_limits<int16_t>::max()
            || pointerX < std::numeric_limits<int16_t>::min()
            || pointerX > std::numeric_limits<int16_t>::max()
            || pointerY < std::numeric_limits<int16_t>::min()
            || pointerY > std::numeric_limits<int16_t>::max()
            || !std::isfinite(accelerometerX)
            || !std::isfinite(accelerometerY)
            || !std::isfinite(accelerometerZ)
            || !std::isfinite(gyroscopeX)
            || !std::isfinite(gyroscopeY)
            || !std::isfinite(gyroscopeZ)) {
        throwException(env, "java/lang/IllegalArgumentException", "Estado de entrada 3DS inválido.");
        return;
    }
    session->updateInput(
            static_cast<uint16_t>(buttonMask),
            static_cast<int16_t>(circlePadX),
            static_cast<int16_t>(circlePadY),
            static_cast<int16_t>(cStickX),
            static_cast<int16_t>(cStickY),
            static_cast<int16_t>(pointerX),
            static_cast<int16_t>(pointerY),
            pointerPressed == JNI_TRUE,
            accelerometerX,
            accelerometerY,
            accelerometerZ,
            gyroscopeX,
            gyroscopeY,
            gyroscopeZ);
}

void enqueueMicrophone(
        JNIEnv* env,
        jclass,
        jlong handle,
        jshortArray input,
        jint count) {
    CoreGameplaySession* session = requireSession(env, handle);
    if (session == nullptr) {
        return;
    }
    if (input == nullptr || count <= 0 || count > env->GetArrayLength(input)) {
        throwException(env, "java/lang/IllegalArgumentException", "Microfone 3DS inválido.");
        return;
    }
    jshort* samples = env->GetShortArrayElements(input, nullptr);
    if (samples == nullptr) {
        return;
    }
    session->enqueueMicrophone(
            reinterpret_cast<const int16_t*>(samples),
            static_cast<size_t>(count));
    env->ReleaseShortArrayElements(input, samples, JNI_ABORT);
}

void destroySession(JNIEnv* env, jclass, jlong handle) {
    CoreGameplaySession* session = requireSession(env, handle);
    if (session != nullptr) {
        delete session;
    }
}

const JNINativeMethod kMethods[] = {
        {const_cast<char*>("o"),
         const_cast<char*>("(Landroid/view/Surface;IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J"),
         reinterpret_cast<void*>(createSession)},
        {const_cast<char*>("r"), const_cast<char*>("(J)[Ljava/lang/String;"),
         reinterpret_cast<void*>(runFrame)},
        {const_cast<char*>("d"), const_cast<char*>("(JLjava/nio/ByteBuffer;I)I"),
         reinterpret_cast<void*>(drainAudio)},
        {const_cast<char*>("u"), const_cast<char*>("(JIIIIIIIZFFFFFF)V"),
         reinterpret_cast<void*>(updateInput)},
        {const_cast<char*>("m"), const_cast<char*>("(J[SI)V"),
         reinterpret_cast<void*>(enqueueMicrophone)},
        {const_cast<char*>("x"), const_cast<char*>("(J)V"),
         reinterpret_cast<void*>(destroySession)},
};

}  // namespace

bool registerCoreGameplaySession(JNIEnv* env) {
    jclass sessionClass = env->FindClass(
            "com/mateussouza/emuorbit/n3ds/core/Nintendo3DsCoreSession");
    return sessionClass != nullptr
            && env->RegisterNatives(
                       sessionClass,
                       kMethods,
                       static_cast<jint>(std::size(kMethods))) == JNI_OK;
}

}  // namespace emuorbit::n3ds
