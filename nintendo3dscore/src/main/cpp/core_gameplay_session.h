// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <android/native_window.h>

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "libretro_gameplay_abi.h"
#include "libretro_vulkan_abi.h"

namespace emuorbit::n3ds {

class VulkanRenderHost;

struct CoreGameplayReport {
    std::string deviceName;
    uint32_t apiVersion = 0;
    uint32_t surfaceFormat = 0;
    uint32_t swapchainImageCount = 0;
    uint64_t presentedFrames = 0;
    uint64_t videoFrames = 0;
    uint64_t audioFrames = 0;
    uint64_t audioQueuedFrames = 0;
    uint64_t audioDroppedFrames = 0;
    uint64_t audioPeakQueuedFrames = 0;
    uint64_t inputPollCallbacks = 0;
    uint64_t inputStateCallbacks = 0;
    uint64_t joypadStateCallbacks = 0;
    uint64_t analogStateCallbacks = 0;
    uint64_t rightAnalogStateCallbacks = 0;
    uint64_t pointerStateCallbacks = 0;
    uint16_t lastPolledButtonMask = 0;
    int16_t lastPolledCirclePadX = 0;
    int16_t lastPolledCirclePadY = 0;
    int16_t lastPolledCStickX = 0;
    int16_t lastPolledCStickY = 0;
    int16_t lastPolledPointerX = 0;
    int16_t lastPolledPointerY = 0;
    bool lastPolledPointerPressed = false;
    uint64_t sensorStateCallbacks = 0;
    uint64_t sensorInputCallbacks = 0;
    uint32_t sensorSamplingRateHz = 0;
    float lastPolledAccelerometerX = 0.0F;
    float lastPolledAccelerometerY = 0.0F;
    float lastPolledAccelerometerZ = -1.0F;
    float lastPolledGyroscopeX = 0.0F;
    float lastPolledGyroscopeY = 0.0F;
    float lastPolledGyroscopeZ = 0.0F;
    bool microphoneFrontendSelected = false;
    uint64_t microphoneOpenCallbacks = 0;
    uint64_t microphoneCloseCallbacks = 0;
    uint64_t microphoneStateCallbacks = 0;
    uint64_t microphoneReadCallbacks = 0;
    uint32_t microphoneSamplingRateHz = 0;
    bool microphoneActive = false;
    uint64_t microphoneCapturedSamples = 0;
    uint64_t microphoneQueuedSamples = 0;
    uint64_t microphoneDroppedSamples = 0;
    uint64_t microphoneDeliveredSamples = 0;
    uint64_t microphoneSilentSamples = 0;
    uint64_t environmentCallbacks = 0;
    uint64_t vfsOpenCalls = 0;
    uint64_t vfsReadCalls = 0;
    uint64_t vfsBytesRead = 0;
    uint32_t lastWidth = 0;
    uint32_t lastHeight = 0;
    uint32_t audioSampleRate = 0;
    bool preferredVulkan = false;
    bool hardwareRenderNegotiated = false;
    bool negotiationInterfaceReceived = false;
    bool hardwareInterfaceProvided = false;
    bool contextReset = false;
    bool contentOpenedThroughVfs = false;
    std::string performanceProfile;
    uint64_t performanceOptionRequests = 0;
    uint32_t resolutionFactor = 0;
    bool diskShaderCacheEnabled = false;
};

class CoreGameplaySession final {
public:
    static std::unique_ptr<CoreGameplaySession> open(
            ANativeWindow* window,
            uint32_t requestedWidth,
            uint32_t requestedHeight,
            const char* libraryPath,
            const char* contentPath,
            const char* systemDirectory,
            const char* saveDirectory,
            const char* screenLayout,
            const char* swapScreen,
            const char* performanceProfile,
            std::string& error);

    ~CoreGameplaySession();

    CoreGameplaySession(const CoreGameplaySession&) = delete;
    CoreGameplaySession& operator=(const CoreGameplaySession&) = delete;

    bool runFrame(std::string& error);
    size_t drainAudio(int16_t* output, size_t capacityFrames);
    void updateInput(
            uint16_t buttonMask,
            int16_t circlePadX,
            int16_t circlePadY,
            int16_t cStickX,
            int16_t cStickY,
            int16_t pointerX,
            int16_t pointerY,
            bool pointerPressed,
            float accelerometerX,
            float accelerometerY,
            float accelerometerZ,
            float gyroscopeX,
            float gyroscopeY,
            float gyroscopeZ);
    CoreGameplayReport report() const;
    bool isOwnerThread() const;
    bool isContentPath(const char* path) const;
    void recordVfsOpen(bool content);
    void recordVfsRead(bool content, size_t bytes);
    bool setSensorState(unsigned port, int action, unsigned rate);
    float readSensor(unsigned port, unsigned id);
    void enqueueMicrophone(const int16_t* samples, size_t count);
    void recordMicrophoneOpen(unsigned rate);
    void recordMicrophoneClose();
    void recordMicrophoneState(bool active);
    int readMicrophone(int16_t* samples, size_t count);

private:
    CoreGameplaySession() = default;

    bool loadCoreApi(std::string& error);
    bool handleEnvironment(unsigned command, void* data);
    void handleVideo(const void* data, unsigned width, unsigned height, size_t pitch);
    size_t handleAudioBatch(const int16_t* data, size_t frames);
    void recordInputPoll();
    int16_t readInput(unsigned port, unsigned device, unsigned index, unsigned id);
    void teardown();

    static bool environmentCallback(unsigned command, void* data);
    static void videoCallback(const void* data, unsigned width, unsigned height, size_t pitch);
    static void audioSampleCallback(int16_t left, int16_t right);
    static size_t audioBatchCallback(const int16_t* data, size_t frames);
    static void inputPollCallback();
    static int16_t inputStateCallback(
            unsigned port,
            unsigned device,
            unsigned index,
            unsigned id);

    struct CoreApi {
        ApiVersionFunction apiVersion = nullptr;
        GetSystemInfoFunction getSystemInfo = nullptr;
        GetSystemAvInfoFunction getSystemAvInfo = nullptr;
        SetEnvironmentFunction setEnvironment = nullptr;
        SetVideoRefreshFunction setVideoRefresh = nullptr;
        SetAudioSampleFunction setAudioSample = nullptr;
        SetAudioSampleBatchFunction setAudioSampleBatch = nullptr;
        SetInputPollFunction setInputPoll = nullptr;
        SetInputStateFunction setInputState = nullptr;
        InitFunction init = nullptr;
        DeinitFunction deinit = nullptr;
        LoadGameFunction loadGame = nullptr;
        UnloadGameFunction unloadGame = nullptr;
        RunFunction run = nullptr;
    };

    std::thread::id ownerThread_;
    bool ownsCoreSession_ = false;
    bool initialized_ = false;
    bool contentLoaded_ = false;
    bool contextReset_ = false;
    bool framePresentedThisRun_ = false;
    void* library_ = nullptr;
    CoreApi core_;
    HardwareRenderCallback hardwareCallback_{};
    const VulkanNegotiationInterface* negotiation_ = nullptr;
    std::unique_ptr<VulkanRenderHost> renderHost_;
    std::string libraryPath_;
    std::string contentPath_;
    std::string systemDirectory_;
    std::string saveDirectory_;
    std::string screenLayout_;
    std::string swapScreen_;
    std::string performanceProfile_;
    std::string resolutionFactor_;
    std::string frameError_;
    // Two seconds at the native rate absorb cold shader-compilation bursts while remaining fixed.
    static constexpr size_t kAudioRingCapacityFrames = 65536;
    std::array<int16_t, kAudioRingCapacityFrames * 2> audioRing_{};
    size_t audioReadFrame_ = 0;
    size_t audioQueuedFrames_ = 0;
    std::mutex audioMutex_;
    std::atomic<uint64_t> packedInputState_{0};
    std::atomic<uint64_t> packedCStickState_{0};
    std::atomic<uint64_t> packedPointerState_{0};
    std::atomic<uint64_t> inputPollCallbacks_{0};
    std::atomic<uint64_t> inputStateCallbacks_{0};
    std::atomic<uint64_t> joypadStateCallbacks_{0};
    std::atomic<uint64_t> analogStateCallbacks_{0};
    std::atomic<uint64_t> rightAnalogStateCallbacks_{0};
    std::atomic<uint64_t> pointerStateCallbacks_{0};
    std::atomic<uint16_t> lastPolledButtonMask_{0};
    std::atomic<int16_t> lastPolledCirclePadX_{0};
    std::atomic<int16_t> lastPolledCirclePadY_{0};
    std::atomic<int16_t> lastPolledCStickX_{0};
    std::atomic<int16_t> lastPolledCStickY_{0};
    std::atomic<int16_t> lastPolledPointerX_{0};
    std::atomic<int16_t> lastPolledPointerY_{0};
    std::atomic<bool> lastPolledPointerPressed_{false};
    std::atomic<bool> accelerometerEnabled_{false};
    std::atomic<bool> gyroscopeEnabled_{false};
    std::atomic<uint64_t> sensorStateCallbacks_{0};
    std::atomic<uint64_t> sensorInputCallbacks_{0};
    std::atomic<uint32_t> sensorSamplingRateHz_{0};
    std::atomic<float> accelerometerX_{0.0F};
    std::atomic<float> accelerometerY_{0.0F};
    std::atomic<float> accelerometerZ_{-1.0F};
    std::atomic<float> gyroscopeX_{0.0F};
    std::atomic<float> gyroscopeY_{0.0F};
    std::atomic<float> gyroscopeZ_{0.0F};
    std::atomic<float> lastPolledAccelerometerX_{0.0F};
    std::atomic<float> lastPolledAccelerometerY_{0.0F};
    std::atomic<float> lastPolledAccelerometerZ_{-1.0F};
    std::atomic<float> lastPolledGyroscopeX_{0.0F};
    std::atomic<float> lastPolledGyroscopeY_{0.0F};
    std::atomic<float> lastPolledGyroscopeZ_{0.0F};
    static constexpr size_t kMicrophoneRingCapacitySamples = 48000;
    std::array<int16_t, kMicrophoneRingCapacitySamples> microphoneRing_{};
    size_t microphoneReadSample_ = 0;
    size_t microphoneQueuedSamples_ = 0;
    uint64_t microphoneOpenCallbacks_ = 0;
    uint64_t microphoneCloseCallbacks_ = 0;
    uint64_t microphoneStateCallbacks_ = 0;
    uint64_t microphoneReadCallbacks_ = 0;
    uint32_t microphoneSamplingRateHz_ = 0;
    bool microphoneActive_ = false;
    uint64_t microphoneCapturedSamples_ = 0;
    uint64_t microphoneDroppedSamples_ = 0;
    uint64_t microphoneDeliveredSamples_ = 0;
    uint64_t microphoneSilentSamples_ = 0;
    mutable std::mutex microphoneMutex_;
    mutable std::mutex reportMutex_;
    CoreGameplayReport report_;
};

}  // namespace emuorbit::n3ds
