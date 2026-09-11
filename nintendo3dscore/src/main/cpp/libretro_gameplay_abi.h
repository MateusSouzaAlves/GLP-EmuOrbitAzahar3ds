// SPDX-License-Identifier: MIT
// Copyright (C) 2010-2024 The RetroArch team
// Minimal gameplay declarations derived from libretro.h at RetroArch
// 81478f2aa2abb942cfacb2109cbc25a4bd3b46ca. The full notice remains recorded
// in nintendo3dscore/compliance/THIRD_PARTY_NOTICES.txt.
#pragma once

#include <climits>
#include <cstddef>
#include <cstdint>

#include "libretro_bootstrap_abi.h"

namespace emuorbit::n3ds {

constexpr unsigned kEnvironmentExperimental = 0x10000;
constexpr unsigned kEnvironmentGetCanDupe = 3;
constexpr unsigned kEnvironmentSetMessage = 6;
constexpr unsigned kEnvironmentGetSystemDirectory = 9;
constexpr unsigned kEnvironmentSetPixelFormat = 10;
constexpr unsigned kEnvironmentSetInputDescriptors = 11;
constexpr unsigned kEnvironmentSetHwRender = 14;
constexpr unsigned kEnvironmentGetVariable = 15;
constexpr unsigned kEnvironmentGetVariableUpdate = 17;
constexpr unsigned kEnvironmentSetSupportNoGame = 18;
constexpr unsigned kEnvironmentGetRumbleInterface = 23;
constexpr unsigned kEnvironmentGetSensorInterface = 25 | kEnvironmentExperimental;
constexpr unsigned kEnvironmentGetLogInterface = 27;
constexpr unsigned kEnvironmentGetCoreAssetsDirectory = 30;
constexpr unsigned kEnvironmentGetSaveDirectory = 31;
constexpr unsigned kEnvironmentSetMemoryMaps = 36 | kEnvironmentExperimental;
constexpr unsigned kEnvironmentSetGeometry = 37;
constexpr unsigned kEnvironmentGetLanguage = 39;
constexpr unsigned kEnvironmentGetHwRenderInterface = 41 | kEnvironmentExperimental;
constexpr unsigned kEnvironmentSetHwRenderContextNegotiationInterface =
        43 | kEnvironmentExperimental;
constexpr unsigned kEnvironmentSetHwSharedContext = 44 | kEnvironmentExperimental;
constexpr unsigned kEnvironmentGetPreferredHwRender = 56;
constexpr unsigned kEnvironmentGetHwRenderContextNegotiationInterfaceSupport =
        73 | kEnvironmentExperimental;
constexpr unsigned kEnvironmentGetMicrophoneInterface = 75 | kEnvironmentExperimental;
constexpr unsigned kEnvironmentSetSerializationQuirks = 87;

constexpr unsigned kLanguagePortugueseBrazil = 7;
constexpr unsigned kPixelFormatXrgb8888 = 1;
constexpr unsigned kHwContextVulkan = 6;
constexpr unsigned kDeviceJoypad = 1;
constexpr unsigned kDeviceAnalog = 5;
constexpr unsigned kDevicePointer = 6;
constexpr unsigned kDeviceIndexAnalogLeft = 0;
constexpr unsigned kDeviceIndexAnalogRight = 1;
constexpr unsigned kDeviceIdAnalogX = 0;
constexpr unsigned kDeviceIdAnalogY = 1;
constexpr unsigned kDeviceIdJoypadMask = 256;
constexpr unsigned kDeviceIdPointerX = 0;
constexpr unsigned kDeviceIdPointerY = 1;
constexpr unsigned kDeviceIdPointerPressed = 2;
constexpr unsigned kMicrophoneInterfaceVersion = 1;
constexpr unsigned kVfsFileAccessRead = 1U << 0U;
constexpr unsigned kVfsFileAccessWrite = 1U << 1U;
constexpr unsigned kVfsFileAccessReadWrite = kVfsFileAccessRead | kVfsFileAccessWrite;
constexpr unsigned kVfsFileAccessUpdateExisting = 1U << 2U;
constexpr int kVfsSeekStart = 0;
constexpr int kVfsSeekCurrent = 1;
constexpr int kVfsSeekEnd = 2;
constexpr int kSensorAccelerometerEnable = 0;
constexpr int kSensorAccelerometerDisable = 1;
constexpr int kSensorGyroscopeEnable = 2;
constexpr int kSensorGyroscopeDisable = 3;
constexpr unsigned kSensorAccelerometerX = 0;
constexpr unsigned kSensorAccelerometerY = 1;
constexpr unsigned kSensorAccelerometerZ = 2;
constexpr unsigned kSensorGyroscopeX = 3;
constexpr unsigned kSensorGyroscopeY = 4;
constexpr unsigned kSensorGyroscopeZ = 5;
inline const void* const kHardwareFrameBufferValid = reinterpret_cast<const void*>(-1);

using VideoRefreshCallback = void (*)(const void*, unsigned, unsigned, size_t);
using AudioSampleCallback = void (*)(int16_t, int16_t);
using AudioSampleBatchCallback = size_t (*)(const int16_t*, size_t);
using InputPollCallback = void (*)();
using InputStateCallback = int16_t (*)(unsigned, unsigned, unsigned, unsigned);
using HardwareContextCallback = void (*)();
using HardwareGetCurrentFramebuffer = uintptr_t (*)();
using HardwareProcedure = void (*)();
using HardwareGetProcAddress = HardwareProcedure (*)(const char*);

struct HardwareRenderCallback {
    int contextType;
    HardwareContextCallback contextReset;
    HardwareGetCurrentFramebuffer getCurrentFramebuffer;
    HardwareGetProcAddress getProcAddress;
    bool depth;
    bool stencil;
    bool bottomLeftOrigin;
    unsigned versionMajor;
    unsigned versionMinor;
    bool cacheContext;
    HardwareContextCallback contextDestroy;
    bool debugContext;
};

struct GameGeometry {
    unsigned baseWidth;
    unsigned baseHeight;
    unsigned maxWidth;
    unsigned maxHeight;
    float aspectRatio;
};

struct SystemTiming {
    double fps;
    double sampleRate;
};

struct SystemAvInfo {
    GameGeometry geometry;
    SystemTiming timing;
};

struct GameInfo {
    const char* path;
    const void* data;
    size_t size;
    const char* meta;
};

struct Variable {
    const char* key;
    const char* value;
};

struct Message {
    const char* message;
    unsigned frames;
};

using LogFunction = void (*)(int level, const char* format, ...);
struct LogCallback {
    LogFunction log;
};

struct VulkanNegotiationSupport {
    int interfaceType;
    unsigned interfaceVersion;
};

struct VfsFileHandle;
using VfsGetPath = const char* (*)(VfsFileHandle*);
using VfsOpen = VfsFileHandle* (*)(const char*, unsigned, unsigned);
using VfsClose = int (*)(VfsFileHandle*);
using VfsSize = int64_t (*)(VfsFileHandle*);
using VfsTell = int64_t (*)(VfsFileHandle*);
using VfsSeek = int64_t (*)(VfsFileHandle*, int64_t, int);
using VfsRead = int64_t (*)(VfsFileHandle*, void*, uint64_t);
using VfsWrite = int64_t (*)(VfsFileHandle*, const void*, uint64_t);
using VfsFlush = int (*)(VfsFileHandle*);
using VfsRemove = int (*)(const char*);
using VfsRename = int (*)(const char*, const char*);
using VfsTruncate = int64_t (*)(VfsFileHandle*, int64_t);

struct VfsInterface {
    VfsGetPath getPath;
    VfsOpen open;
    VfsClose close;
    VfsSize size;
    VfsTell tell;
    VfsSeek seek;
    VfsRead read;
    VfsWrite write;
    VfsFlush flush;
    VfsRemove remove;
    VfsRename rename;
    VfsTruncate truncate;
};

struct VfsInterfaceInfo {
    uint32_t requiredInterfaceVersion;
    VfsInterface* interface;
};

using SetSensorState = bool (*)(unsigned, int, unsigned);
using GetSensorInput = float (*)(unsigned, unsigned);
struct SensorInterface {
    SetSensorState setSensorState;
    GetSensorInput getSensorInput;
};

struct Microphone;
struct MicrophoneParameters {
    unsigned rate;
};
using OpenMicrophone = Microphone* (*)(const MicrophoneParameters*);
using CloseMicrophone = void (*)(Microphone*);
using GetMicrophoneParameters = bool (*)(const Microphone*, MicrophoneParameters*);
using SetMicrophoneState = bool (*)(Microphone*, bool);
using GetMicrophoneState = bool (*)(const Microphone*);
using ReadMicrophone = int (*)(Microphone*, int16_t*, size_t);

struct MicrophoneInterface {
    unsigned interfaceVersion;
    OpenMicrophone open;
    CloseMicrophone close;
    GetMicrophoneParameters getParameters;
    SetMicrophoneState setState;
    GetMicrophoneState getState;
    ReadMicrophone read;
};

using GetSystemAvInfoFunction = void (*)(SystemAvInfo*);
using SetVideoRefreshFunction = void (*)(VideoRefreshCallback);
using SetAudioSampleFunction = void (*)(AudioSampleCallback);
using SetAudioSampleBatchFunction = void (*)(AudioSampleBatchCallback);
using SetInputPollFunction = void (*)(InputPollCallback);
using SetInputStateFunction = void (*)(InputStateCallback);
using InitFunction = void (*)();
using DeinitFunction = void (*)();
using LoadGameFunction = bool (*)(const GameInfo*);
using UnloadGameFunction = void (*)();
using RunFunction = void (*)();

}  // namespace emuorbit::n3ds
