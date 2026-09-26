// SPDX-License-Identifier: GPL-3.0-or-later
// The VFS and minimal frontend callback flow were promoted from EmuOrbit's
// device probe after validation against Azahar 2126.0 at fbd3fb02f71e5f9e.
#include "core_gameplay_session.h"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

#include "core_session_registry.h"
#include "protected_nintendo3ds_core.h"
#include "vulkan_render_host.h"

namespace emuorbit::n3ds {

struct VfsFileHandle {
    std::FILE* file;
    std::string path;
    bool content;
};

struct Microphone {
    CoreGameplaySession* owner;
    unsigned rate;
    std::atomic<bool> active;
};

namespace {

std::atomic<CoreGameplaySession*> g_activeSession{nullptr};
std::mutex g_coreLibraryMutex;
void* g_residentCoreLibrary = nullptr;
std::string g_residentCoreLibraryPath;

void* openCoreLibrary(
        const std::string& path,
        bool& processResident,
        std::string& error) {
    std::lock_guard<std::mutex> lock(g_coreLibraryMutex);
    if (g_residentCoreLibrary != nullptr) {
        if (g_residentCoreLibraryPath != path) {
            error = "A different Nintendo 3DS core is already resident in this process";
            return nullptr;
        }
        processResident = true;
        return g_residentCoreLibrary;
    }
    if (path == "azahar_libretro.so") {
        void* protectedLibrary = protectedNintendo3DsCoreHandle();
        if (protectedLibrary == nullptr) {
            error = "The protected Nintendo 3DS core was not prepared";
            return nullptr;
        }
        g_residentCoreLibrary = protectedLibrary;
        g_residentCoreLibraryPath = path;
        processResident = true;
        return protectedLibrary;
    }
    processResident = false;
    return dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
}

bool retainCoreLibraryForProcess(
        void* library,
        const std::string& path,
        std::string& error) {
    std::lock_guard<std::mutex> lock(g_coreLibraryMutex);
    if (g_residentCoreLibrary != nullptr) {
        if (g_residentCoreLibrary != library || g_residentCoreLibraryPath != path) {
            error = "The resident Nintendo 3DS core identity changed unexpectedly";
            return false;
        }
        return true;
    }
    g_residentCoreLibrary = library;
    g_residentCoreLibraryPath = path;
    return true;
}

uint64_t packInputState(uint16_t buttonMask, int16_t circlePadX, int16_t circlePadY) {
    return static_cast<uint64_t>(buttonMask)
            | (static_cast<uint64_t>(static_cast<uint16_t>(circlePadX)) << 16U)
            | (static_cast<uint64_t>(static_cast<uint16_t>(circlePadY)) << 32U);
}

uint16_t unpackButtonMask(uint64_t state) {
    return static_cast<uint16_t>(state & 0xffffU);
}

int16_t unpackCirclePadX(uint64_t state) {
    return static_cast<int16_t>((state >> 16U) & 0xffffU);
}

int16_t unpackCirclePadY(uint64_t state) {
    return static_cast<int16_t>((state >> 32U) & 0xffffU);
}

uint64_t packAxes(int16_t x, int16_t y) {
    return static_cast<uint64_t>(static_cast<uint16_t>(x))
            | (static_cast<uint64_t>(static_cast<uint16_t>(y)) << 16U);
}

int16_t unpackAxisX(uint64_t state) {
    return static_cast<int16_t>(state & 0xffffU);
}

int16_t unpackAxisY(uint64_t state) {
    return static_cast<int16_t>((state >> 16U) & 0xffffU);
}

uint64_t packPointerState(int16_t x, int16_t y, bool pressed) {
    return packAxes(x, y) | (static_cast<uint64_t>(pressed ? 1U : 0U) << 32U);
}

bool unpackPointerPressed(uint64_t state) {
    return ((state >> 32U) & 1U) != 0;
}

template <typename Function>
bool loadSymbol(void* library, const char* name, Function& target) {
    dlerror();
    target = reinterpret_cast<Function>(dlsym(library, name));
    return target != nullptr && dlerror() == nullptr;
}

bool absolutePath(const char* path) {
    return path != nullptr && path[0] == '/';
}

bool loadableCorePath(const char* path) {
    return absolutePath(path)
            || (path != nullptr && std::strcmp(path, "azahar_libretro.so") == 0);
}

bool supportedScreenLayout(const char* value) {
    return value != nullptr
            && (std::strcmp(value, "default") == 0
                    || std::strcmp(value, "side_by_side") == 0
                    || std::strcmp(value, "large_screen") == 0
                    || std::strcmp(value, "single_screen") == 0);
}

bool supportedSwapScreen(const char* value) {
    return value != nullptr
            && (std::strcmp(value, "Top") == 0 || std::strcmp(value, "Bottom") == 0);
}

bool supportedPerformanceProfile(const char* value) {
    return value != nullptr
            && (std::strcmp(value, "conservative") == 0
                    || std::strcmp(value, "balanced") == 0
                    || std::strcmp(value, "performance") == 0);
}

const char* resolutionFactorForProfile(const char* value) {
    if (std::strcmp(value, "conservative") == 0) {
        return "1";
    }
    return "2";
}

void coreLog(int level, const char* format, ...) {
    int priority = ANDROID_LOG_INFO;
    if (level >= 3) {
        priority = ANDROID_LOG_ERROR;
    } else if (level == 2) {
        priority = ANDROID_LOG_WARN;
    } else if (level == 0) {
        priority = ANDROID_LOG_DEBUG;
    }
    va_list arguments;
    va_start(arguments, format);
    __android_log_vprint(priority, "EmuOrbit-N3DS-Core", format, arguments);
    va_end(arguments);
}

const char* vfsGetPath(VfsFileHandle* stream) {
    return stream == nullptr ? nullptr : stream->path.c_str();
}

VfsFileHandle* vfsOpen(const char* path, unsigned mode, unsigned) {
    if (path == nullptr) {
        return nullptr;
    }
    const char* stdioMode = nullptr;
    switch (mode) {
    case kVfsFileAccessRead:
        stdioMode = "rb";
        break;
    case kVfsFileAccessWrite:
        stdioMode = "wb";
        break;
    case kVfsFileAccessReadWrite:
        stdioMode = "w+b";
        break;
    case kVfsFileAccessWrite | kVfsFileAccessUpdateExisting:
    case kVfsFileAccessReadWrite | kVfsFileAccessUpdateExisting:
        stdioMode = "r+b";
        break;
    default:
        return nullptr;
    }
    std::FILE* file = std::fopen(path, stdioMode);
    if (file == nullptr) {
        return nullptr;
    }
    CoreGameplaySession* activeSession = g_activeSession.load(std::memory_order_acquire);
    const bool content = activeSession != nullptr && activeSession->isContentPath(path);
    auto* stream = new (std::nothrow) VfsFileHandle{file, path, content};
    if (stream == nullptr) {
        std::fclose(file);
        return nullptr;
    }
    if (activeSession != nullptr) {
        activeSession->recordVfsOpen(content);
    }
    return stream;
}

int vfsClose(VfsFileHandle* stream) {
    if (stream == nullptr) {
        return -1;
    }
    const int result = std::fclose(stream->file);
    delete stream;
    return result == 0 ? 0 : -1;
}

int64_t vfsTell(VfsFileHandle* stream) {
    if (stream == nullptr) {
        return -1;
    }
    const off_t position = ftello(stream->file);
    return position < 0 ? -1 : static_cast<int64_t>(position);
}

int64_t vfsSeek(VfsFileHandle* stream, int64_t offset, int position) {
    if (stream == nullptr) {
        return -1;
    }
    int origin = 0;
    switch (position) {
    case kVfsSeekStart:
        origin = SEEK_SET;
        break;
    case kVfsSeekCurrent:
        origin = SEEK_CUR;
        break;
    case kVfsSeekEnd:
        origin = SEEK_END;
        break;
    default:
        return -1;
    }
    if (fseeko(stream->file, static_cast<off_t>(offset), origin) != 0) {
        return -1;
    }
    return vfsTell(stream);
}

int64_t vfsSize(VfsFileHandle* stream) {
    const int64_t position = vfsTell(stream);
    if (position < 0 || vfsSeek(stream, 0, kVfsSeekEnd) < 0) {
        return -1;
    }
    const int64_t size = vfsTell(stream);
    return vfsSeek(stream, position, kVfsSeekStart) < 0 ? -1 : size;
}

int64_t vfsRead(VfsFileHandle* stream, void* buffer, uint64_t length) {
    if (stream == nullptr || buffer == nullptr
            || length > std::numeric_limits<size_t>::max()) {
        return -1;
    }
    const size_t read = std::fread(buffer, 1, static_cast<size_t>(length), stream->file);
    if (read == 0 && std::ferror(stream->file) != 0) {
        return -1;
    }
    CoreGameplaySession* activeSession = g_activeSession.load(std::memory_order_acquire);
    if (activeSession != nullptr) {
        activeSession->recordVfsRead(stream->content, read);
    }
    return static_cast<int64_t>(read);
}

int64_t vfsWrite(VfsFileHandle* stream, const void* buffer, uint64_t length) {
    if (stream == nullptr || buffer == nullptr
            || length > std::numeric_limits<size_t>::max()) {
        return -1;
    }
    const size_t written = std::fwrite(buffer, 1, static_cast<size_t>(length), stream->file);
    return written == 0 && std::ferror(stream->file) != 0
            ? -1
            : static_cast<int64_t>(written);
}

int vfsFlush(VfsFileHandle* stream) {
    return stream != nullptr && std::fflush(stream->file) == 0 ? 0 : -1;
}

int vfsRemove(const char* path) {
    return path != nullptr && std::remove(path) == 0 ? 0 : -1;
}

int vfsRename(const char* oldPath, const char* newPath) {
    return oldPath != nullptr && newPath != nullptr
            && std::rename(oldPath, newPath) == 0 ? 0 : -1;
}

int64_t vfsTruncate(VfsFileHandle* stream, int64_t length) {
    if (stream == nullptr || length < 0
            || static_cast<uint64_t>(length)
                    > static_cast<uint64_t>(std::numeric_limits<off_t>::max())) {
        return -1;
    }
    return ftruncate(fileno(stream->file), static_cast<off_t>(length)) == 0 ? 0 : -1;
}

VfsInterface g_vfsInterface{
        vfsGetPath,
        vfsOpen,
        vfsClose,
        vfsSize,
        vfsTell,
        vfsSeek,
        vfsRead,
        vfsWrite,
        vfsFlush,
        vfsRemove,
        vfsRename,
        vfsTruncate,
};

bool frontendSetSensorState(unsigned port, int action, unsigned rate) {
    CoreGameplaySession* activeSession = g_activeSession.load(std::memory_order_acquire);
    return activeSession != nullptr && activeSession->setSensorState(port, action, rate);
}

float frontendGetSensorInput(unsigned port, unsigned id) {
    CoreGameplaySession* activeSession = g_activeSession.load(std::memory_order_acquire);
    return activeSession == nullptr ? 0.0F : activeSession->readSensor(port, id);
}

Microphone* openMicrophone(const MicrophoneParameters* parameters) {
    CoreGameplaySession* session = g_activeSession.load(std::memory_order_acquire);
    if (session == nullptr) {
        return nullptr;
    }
    const unsigned rate = parameters != nullptr && parameters->rate != 0
            ? parameters->rate : 48000U;
    auto* microphone = new (std::nothrow) Microphone{session, rate, false};
    if (microphone != nullptr) {
        session->recordMicrophoneOpen(rate);
    }
    return microphone;
}

void closeMicrophone(Microphone* microphone) {
    if (microphone != nullptr && microphone->owner != nullptr) {
        microphone->owner->recordMicrophoneClose();
    }
    delete microphone;
}

bool getMicrophoneParameters(
        const Microphone* microphone,
        MicrophoneParameters* parameters) {
    if (microphone == nullptr || parameters == nullptr) {
        return false;
    }
    parameters->rate = microphone->rate;
    return true;
}

bool setMicrophoneState(Microphone* microphone, bool active) {
    if (microphone == nullptr) {
        return false;
    }
    microphone->active.store(active, std::memory_order_release);
    microphone->owner->recordMicrophoneState(active);
    return true;
}

bool getMicrophoneState(const Microphone* microphone) {
    return microphone != nullptr
            && microphone->active.load(std::memory_order_acquire);
}

int frontendReadMicrophone(Microphone* microphone, int16_t* samples, size_t count) {
    if (microphone == nullptr
            || !microphone->active.load(std::memory_order_acquire)
            || samples == nullptr
            || count > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return -1;
    }
    return microphone->owner->readMicrophone(samples, count);
}

}  // namespace

std::unique_ptr<CoreGameplaySession> CoreGameplaySession::open(
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
        std::string& error) {
    if (window == nullptr || requestedWidth == 0 || requestedHeight == 0
            || !loadableCorePath(libraryPath) || !absolutePath(contentPath)
            || !absolutePath(systemDirectory) || !absolutePath(saveDirectory)
            || !supportedScreenLayout(screenLayout) || !supportedSwapScreen(swapScreen)
            || !supportedPerformanceProfile(performanceProfile)) {
        error = "The Vulkan surface, dimensions, approved core and absolute content directories are required";
        return nullptr;
    }

    auto session = std::unique_ptr<CoreGameplaySession>(new CoreGameplaySession());
    session->ownerThread_ = std::this_thread::get_id();
    if (!claimCoreSession(session.get())) {
        error = "Another Nintendo 3DS core session is active";
        return nullptr;
    }
    session->ownsCoreSession_ = true;
    g_activeSession.store(session.get(), std::memory_order_release);
    session->libraryPath_ = libraryPath;
    session->contentPath_ = contentPath;
    session->systemDirectory_ = systemDirectory;
    session->saveDirectory_ = saveDirectory;
    session->screenLayout_ = screenLayout;
    session->swapScreen_ = swapScreen;
    session->performanceProfile_ = performanceProfile;
    session->resolutionFactor_ = resolutionFactorForProfile(performanceProfile);
    session->report_.performanceProfile = session->performanceProfile_;

    session->library_ = openCoreLibrary(
            session->libraryPath_, session->libraryProcessResident_, error);
    if (session->library_ == nullptr || !session->loadCoreApi(error)) {
        if (error.empty()) {
            error = "Unable to load the Nintendo 3DS core library";
        }
        return nullptr;
    }

    SystemInfo systemInfo{};
    session->core_.getSystemInfo(&systemInfo);
    if (session->core_.apiVersion() != kLibretroApiVersion
            || systemInfo.libraryName == nullptr
            || std::strcmp(systemInfo.libraryName, "Azahar") != 0
            || !systemInfo.needFullPath) {
        error = "The native library is not the expected Nintendo 3DS core";
        return nullptr;
    }
    // Azahar and some Vulkan drivers retain auxiliary worker state briefly after
    // retro_deinit. Keep one dlopen reference for the Android process lifetime so a
    // late driver callback can never jump into an unmapped core. Reopened Surface
    // sessions reuse this exact handle, so lifecycle churn does not grow refcounts.
    if (!session->libraryProcessResident_) {
        if (!retainCoreLibraryForProcess(session->library_, session->libraryPath_, error)) {
            return nullptr;
        }
        session->libraryProcessResident_ = true;
    }

    session->core_.setEnvironment(environmentCallback);
    session->core_.setVideoRefresh(videoCallback);
    session->core_.setAudioSample(audioSampleCallback);
    session->core_.setAudioSampleBatch(audioBatchCallback);
    session->core_.setInputPoll(inputPollCallback);
    session->core_.setInputState(inputStateCallback);
    session->core_.init();
    session->initialized_ = true;

    SystemAvInfo avInfo{};
    session->core_.getSystemAvInfo(&avInfo);
    if (avInfo.geometry.baseWidth == 0 || avInfo.geometry.baseHeight == 0
            || avInfo.geometry.maxWidth == 0 || avInfo.geometry.maxHeight == 0
            || avInfo.timing.fps <= 0.0 || avInfo.timing.sampleRate <= 0.0) {
        error = "The Nintendo 3DS core returned invalid AV metadata";
        return nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(session->reportMutex_);
        session->report_.audioSampleRate = static_cast<uint32_t>(avInfo.timing.sampleRate + 0.5);
        session->report_.nominalFramesPerSecond = avInfo.timing.fps;
    }

    GameInfo gameInfo{};
    gameInfo.path = session->contentPath_.c_str();
    if (!session->core_.loadGame(&gameInfo)) {
        error = "The Nintendo 3DS content was rejected before context creation";
        return nullptr;
    }
    session->contentLoaded_ = true;
    const CoreGameplayReport negotiationReport = session->report();
    if (!negotiationReport.preferredVulkan
            || !negotiationReport.hardwareRenderNegotiated
            || !negotiationReport.negotiationInterfaceReceived
            || session->hardwareCallback_.contextReset == nullptr
            || session->hardwareCallback_.contextDestroy == nullptr
            || session->negotiation_ == nullptr) {
        error = "Azahar did not complete the required Vulkan negotiation";
        return nullptr;
    }

    session->renderHost_ = VulkanRenderHost::create(
            window,
            requestedWidth,
            requestedHeight,
            session->negotiation_,
            error);
    if (session->renderHost_ == nullptr) {
        return nullptr;
    }
    session->contextReset_ = true;
    session->hardwareCallback_.contextReset();
    {
        std::lock_guard<std::mutex> lock(session->reportMutex_);
        session->report_.contextReset = true;
    }
    if (!session->report().hardwareInterfaceProvided) {
        error = "Azahar did not request the negotiated Vulkan render interface";
        return nullptr;
    }
    return session;
}

bool writeAll(int descriptor, const uint8_t* data, size_t size) {
    size_t written = 0;
    while (written < size) {
        const ssize_t result = write(descriptor, data + written, size - written);
        if (result < 0 && errno == EINTR) {
            continue;
        }
        if (result <= 0) {
            return false;
        }
        written += static_cast<size_t>(result);
    }
    return true;
}

bool readAll(int descriptor, uint8_t* data, size_t size) {
    size_t readBytes = 0;
    while (readBytes < size) {
        const ssize_t result = read(descriptor, data + readBytes, size - readBytes);
        if (result < 0 && errno == EINTR) {
            continue;
        }
        if (result <= 0) {
            return false;
        }
        readBytes += static_cast<size_t>(result);
    }
    return true;
}

CoreGameplaySession::~CoreGameplaySession() {
    teardown();
}

bool CoreGameplaySession::loadCoreApi(std::string& error) {
#define LOAD_GAMEPLAY_SYMBOL(field, symbol)                                    \
    if (!loadSymbol(library_, symbol, core_.field)) {                          \
        error = "The Nintendo 3DS core is missing " symbol;                  \
        return false;                                                         \
    }
    LOAD_GAMEPLAY_SYMBOL(apiVersion, "retro_api_version")
    LOAD_GAMEPLAY_SYMBOL(getSystemInfo, "retro_get_system_info")
    LOAD_GAMEPLAY_SYMBOL(getSystemAvInfo, "retro_get_system_av_info")
    LOAD_GAMEPLAY_SYMBOL(setEnvironment, "retro_set_environment")
    LOAD_GAMEPLAY_SYMBOL(setVideoRefresh, "retro_set_video_refresh")
    LOAD_GAMEPLAY_SYMBOL(setAudioSample, "retro_set_audio_sample")
    LOAD_GAMEPLAY_SYMBOL(setAudioSampleBatch, "retro_set_audio_sample_batch")
    LOAD_GAMEPLAY_SYMBOL(setInputPoll, "retro_set_input_poll")
    LOAD_GAMEPLAY_SYMBOL(setInputState, "retro_set_input_state")
    LOAD_GAMEPLAY_SYMBOL(init, "retro_init")
    LOAD_GAMEPLAY_SYMBOL(deinit, "retro_deinit")
    LOAD_GAMEPLAY_SYMBOL(loadGame, "retro_load_game")
    LOAD_GAMEPLAY_SYMBOL(unloadGame, "retro_unload_game")
    LOAD_GAMEPLAY_SYMBOL(run, "retro_run")
    LOAD_GAMEPLAY_SYMBOL(serializeSize, "retro_serialize_size")
    LOAD_GAMEPLAY_SYMBOL(serialize, "retro_serialize")
    LOAD_GAMEPLAY_SYMBOL(unserialize, "retro_unserialize")
#undef LOAD_GAMEPLAY_SYMBOL
    return true;
}

bool CoreGameplaySession::runFrame(std::string& error) {
    if (!isOwnerThread()) {
        error = "Nintendo 3DS gameplay must stay on the owner thread";
        return false;
    }
    if (!contextReset_ || renderHost_ == nullptr || core_.run == nullptr) {
        error = "The Nintendo 3DS gameplay session is not ready";
        return false;
    }
    framePresentedThisRun_ = false;
    frameError_.clear();
    if (!renderHost_->beginCoreFrame(error)) {
        return false;
    }
    core_.run();
    if (!framePresentedThisRun_) {
        error = frameError_.empty()
                ? "Azahar did not submit a hardware frame"
                : frameError_;
        return false;
    }
    const VulkanRenderReport& renderReport = renderHost_->report();
    {
        std::lock_guard<std::mutex> lock(reportMutex_);
        report_.deviceName = renderReport.deviceName;
        report_.apiVersion = renderReport.apiVersion;
        report_.surfaceFormat = renderReport.surfaceFormat;
        report_.swapchainImageCount = renderReport.swapchainImageCount;
        report_.presentedFrames = renderReport.presentedFrames;
    }
    return true;
}

bool CoreGameplaySession::saveState(
        const char* destinationPath,
        size_t maximumBytes,
        size_t& stateSize,
        std::string& error) {
    stateSize = 0;
    if (!isOwnerThread()) {
        error = "Nintendo 3DS state capture must stay on the owner thread";
        return false;
    }
    if (!contentLoaded_ || core_.serializeSize == nullptr || core_.serialize == nullptr) {
        error = "The Nintendo 3DS core is not ready to capture a recovery state";
        return false;
    }
    if (!absolutePath(destinationPath) || maximumBytes == 0) {
        error = "The Nintendo 3DS recovery path or size limit is invalid";
        return false;
    }

    const size_t requiredBytes = core_.serializeSize();
    if (requiredBytes == 0 || requiredBytes > maximumBytes) {
        error = requiredBytes == 0
                ? "Azahar did not provide a recoverable state"
                : "The Nintendo 3DS recovery state exceeds the safe size limit";
        return false;
    }

    std::vector<uint8_t> state;
    try {
        state.resize(requiredBytes);
    } catch (const std::bad_alloc&) {
        error = "There is not enough memory to capture the Nintendo 3DS recovery state";
        return false;
    }
    if (!core_.serialize(state.data(), state.size())) {
        error = "Azahar could not serialize the Nintendo 3DS recovery state";
        return false;
    }

    const std::string temporaryPath = std::string(destinationPath) + ".tmp";
    const int descriptor = ::open(
            temporaryPath.c_str(),
            O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW,
            S_IRUSR | S_IWUSR);
    if (descriptor < 0) {
        error = "The Nintendo 3DS recovery file could not be created";
        return false;
    }
    const bool written = writeAll(descriptor, state.data(), state.size());
    const bool synced = written && fsync(descriptor) == 0;
    const int closeResult = close(descriptor);
    if (!synced || closeResult != 0
            || rename(temporaryPath.c_str(), destinationPath) != 0) {
        unlink(temporaryPath.c_str());
        error = "The Nintendo 3DS recovery file could not be committed atomically";
        return false;
    }
    stateSize = requiredBytes;
    return true;
}

bool CoreGameplaySession::restoreState(
        const char* sourcePath,
        size_t maximumBytes,
        size_t& stateSize,
        std::string& error) {
    stateSize = 0;
    if (!isOwnerThread()) {
        error = "Nintendo 3DS state restore must stay on the owner thread";
        return false;
    }
    if (!contentLoaded_ || core_.unserialize == nullptr) {
        error = "The Nintendo 3DS core is not ready to restore a recovery state";
        return false;
    }
    if (!absolutePath(sourcePath) || maximumBytes == 0) {
        error = "The Nintendo 3DS recovery path or size limit is invalid";
        return false;
    }

    const int descriptor = ::open(sourcePath, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        error = "The Nintendo 3DS recovery file could not be opened";
        return false;
    }
    struct stat fileStat {};
    if (fstat(descriptor, &fileStat) != 0
            || !S_ISREG(fileStat.st_mode)
            || fileStat.st_size <= 0
            || static_cast<uint64_t>(fileStat.st_size) > maximumBytes
            || static_cast<uint64_t>(fileStat.st_size) > std::numeric_limits<size_t>::max()) {
        close(descriptor);
        error = "The Nintendo 3DS recovery file is invalid or exceeds the safe size limit";
        return false;
    }

    const size_t requiredBytes = static_cast<size_t>(fileStat.st_size);
    std::vector<uint8_t> state;
    try {
        state.resize(requiredBytes);
    } catch (const std::bad_alloc&) {
        close(descriptor);
        error = "There is not enough memory to restore the Nintendo 3DS recovery state";
        return false;
    }
    const bool read = readAll(descriptor, state.data(), state.size());
    const int closeResult = close(descriptor);
    if (!read || closeResult != 0) {
        error = "The Nintendo 3DS recovery file could not be read completely";
        return false;
    }
    if (!core_.unserialize(state.data(), state.size())) {
        error = "Azahar rejected the Nintendo 3DS recovery state";
        return false;
    }
    stateSize = requiredBytes;
    return true;
}

CoreGameplayReport CoreGameplaySession::report() const {
    std::lock_guard<std::mutex> lock(reportMutex_);
    CoreGameplayReport result = report_;
    result.inputPollCallbacks = inputPollCallbacks_.load(std::memory_order_relaxed);
    result.inputStateCallbacks = inputStateCallbacks_.load(std::memory_order_relaxed);
    result.joypadStateCallbacks = joypadStateCallbacks_.load(std::memory_order_relaxed);
    result.analogStateCallbacks = analogStateCallbacks_.load(std::memory_order_relaxed);
    result.rightAnalogStateCallbacks = rightAnalogStateCallbacks_.load(std::memory_order_relaxed);
    result.pointerStateCallbacks = pointerStateCallbacks_.load(std::memory_order_relaxed);
    result.lastPolledButtonMask = lastPolledButtonMask_.load(std::memory_order_relaxed);
    result.lastPolledCirclePadX = lastPolledCirclePadX_.load(std::memory_order_relaxed);
    result.lastPolledCirclePadY = lastPolledCirclePadY_.load(std::memory_order_relaxed);
    result.lastPolledCStickX = lastPolledCStickX_.load(std::memory_order_relaxed);
    result.lastPolledCStickY = lastPolledCStickY_.load(std::memory_order_relaxed);
    result.lastPolledPointerX = lastPolledPointerX_.load(std::memory_order_relaxed);
    result.lastPolledPointerY = lastPolledPointerY_.load(std::memory_order_relaxed);
    result.lastPolledPointerPressed = lastPolledPointerPressed_.load(std::memory_order_relaxed);
    result.sensorStateCallbacks = sensorStateCallbacks_.load(std::memory_order_relaxed);
    result.sensorInputCallbacks = sensorInputCallbacks_.load(std::memory_order_relaxed);
    result.sensorSamplingRateHz = sensorSamplingRateHz_.load(std::memory_order_relaxed);
    result.lastPolledAccelerometerX =
            lastPolledAccelerometerX_.load(std::memory_order_relaxed);
    result.lastPolledAccelerometerY =
            lastPolledAccelerometerY_.load(std::memory_order_relaxed);
    result.lastPolledAccelerometerZ =
            lastPolledAccelerometerZ_.load(std::memory_order_relaxed);
    result.lastPolledGyroscopeX = lastPolledGyroscopeX_.load(std::memory_order_relaxed);
    result.lastPolledGyroscopeY = lastPolledGyroscopeY_.load(std::memory_order_relaxed);
    result.lastPolledGyroscopeZ = lastPolledGyroscopeZ_.load(std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> microphoneLock(microphoneMutex_);
        result.microphoneOpenCallbacks = microphoneOpenCallbacks_;
        result.microphoneCloseCallbacks = microphoneCloseCallbacks_;
        result.microphoneStateCallbacks = microphoneStateCallbacks_;
        result.microphoneReadCallbacks = microphoneReadCallbacks_;
        result.microphoneSamplingRateHz = microphoneSamplingRateHz_;
        result.microphoneActive = microphoneActive_;
        result.microphoneCapturedSamples = microphoneCapturedSamples_;
        result.microphoneQueuedSamples = microphoneQueuedSamples_;
        result.microphoneDroppedSamples = microphoneDroppedSamples_;
        result.microphoneDeliveredSamples = microphoneDeliveredSamples_;
        result.microphoneSilentSamples = microphoneSilentSamples_;
    }
    return result;
}

void CoreGameplaySession::enqueueMicrophone(const int16_t* samples, size_t count) {
    if (!isOwnerThread() || samples == nullptr || count == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(microphoneMutex_);
    const size_t accepted = std::min(count, kMicrophoneRingCapacitySamples);
    const size_t sourceOffset = count - accepted;
    size_t dropped = sourceOffset;
    const size_t overflow = microphoneQueuedSamples_ + accepted
                    > kMicrophoneRingCapacitySamples
            ? microphoneQueuedSamples_ + accepted - kMicrophoneRingCapacitySamples
            : 0;
    if (overflow > 0) {
        microphoneReadSample_ = (microphoneReadSample_ + overflow)
                % kMicrophoneRingCapacitySamples;
        microphoneQueuedSamples_ -= overflow;
        dropped += overflow;
    }
    const size_t writeSample = (microphoneReadSample_ + microphoneQueuedSamples_)
            % kMicrophoneRingCapacitySamples;
    const size_t first = std::min(accepted, kMicrophoneRingCapacitySamples - writeSample);
    std::copy_n(samples + sourceOffset, first, microphoneRing_.data() + writeSample);
    const size_t second = accepted - first;
    if (second > 0) {
        std::copy_n(samples + sourceOffset + first, second, microphoneRing_.data());
    }
    microphoneQueuedSamples_ += accepted;
    microphoneCapturedSamples_ += count;
    microphoneDroppedSamples_ += dropped;
}

void CoreGameplaySession::recordMicrophoneOpen(unsigned rate) {
    std::lock_guard<std::mutex> lock(microphoneMutex_);
    microphoneOpenCallbacks_++;
    microphoneSamplingRateHz_ = rate;
}

void CoreGameplaySession::recordMicrophoneClose() {
    std::lock_guard<std::mutex> lock(microphoneMutex_);
    microphoneCloseCallbacks_++;
    microphoneActive_ = false;
}

void CoreGameplaySession::recordMicrophoneState(bool active) {
    std::lock_guard<std::mutex> lock(microphoneMutex_);
    microphoneStateCallbacks_++;
    microphoneActive_ = active;
}

int CoreGameplaySession::readMicrophone(int16_t* samples, size_t count) {
    if (samples == nullptr || count > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(microphoneMutex_);
    microphoneReadCallbacks_++;
    const size_t delivered = std::min(count, microphoneQueuedSamples_);
    const size_t first = std::min(
            delivered,
            kMicrophoneRingCapacitySamples - microphoneReadSample_);
    std::copy_n(microphoneRing_.data() + microphoneReadSample_, first, samples);
    const size_t second = delivered - first;
    if (second > 0) {
        std::copy_n(microphoneRing_.data(), second, samples + first);
    }
    std::fill_n(samples + delivered, count - delivered, int16_t{0});
    microphoneReadSample_ = (microphoneReadSample_ + delivered)
            % kMicrophoneRingCapacitySamples;
    microphoneQueuedSamples_ -= delivered;
    microphoneDeliveredSamples_ += delivered;
    microphoneSilentSamples_ += count - delivered;
    return static_cast<int>(count);
}

size_t CoreGameplaySession::drainAudio(int16_t* output, size_t capacityFrames) {
    if (!isOwnerThread() || output == nullptr || capacityFrames == 0) {
        return 0;
    }
    size_t drainedFrames = 0;
    size_t queuedFrames = 0;
    {
        std::lock_guard<std::mutex> lock(audioMutex_);
        drainedFrames = std::min(capacityFrames, audioQueuedFrames_);
        const size_t firstFrames = std::min(
                drainedFrames,
                kAudioRingCapacityFrames - audioReadFrame_);
        std::copy_n(
                audioRing_.data() + audioReadFrame_ * 2,
                firstFrames * 2,
                output);
        const size_t secondFrames = drainedFrames - firstFrames;
        if (secondFrames > 0) {
            std::copy_n(audioRing_.data(), secondFrames * 2, output + firstFrames * 2);
        }
        audioReadFrame_ = (audioReadFrame_ + drainedFrames) % kAudioRingCapacityFrames;
        audioQueuedFrames_ -= drainedFrames;
        queuedFrames = audioQueuedFrames_;
    }
    {
        std::lock_guard<std::mutex> lock(reportMutex_);
        report_.audioQueuedFrames = queuedFrames;
    }
    return drainedFrames;
}

void CoreGameplaySession::updateInput(
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
        float gyroscopeZ) {
    packedInputState_.store(
            packInputState(buttonMask, circlePadX, circlePadY),
            std::memory_order_release);
    packedCStickState_.store(packAxes(cStickX, cStickY), std::memory_order_release);
    packedPointerState_.store(
            packPointerState(pointerX, pointerY, pointerPressed),
            std::memory_order_release);
    accelerometerX_.store(accelerometerX, std::memory_order_release);
    accelerometerY_.store(accelerometerY, std::memory_order_release);
    accelerometerZ_.store(accelerometerZ, std::memory_order_release);
    gyroscopeX_.store(gyroscopeX, std::memory_order_release);
    gyroscopeY_.store(gyroscopeY, std::memory_order_release);
    gyroscopeZ_.store(gyroscopeZ, std::memory_order_release);
}

void CoreGameplaySession::recordInputPoll() {
    inputPollCallbacks_.fetch_add(1, std::memory_order_relaxed);
    const uint64_t state = packedInputState_.load(std::memory_order_acquire);
    lastPolledButtonMask_.store(unpackButtonMask(state), std::memory_order_relaxed);
    lastPolledCirclePadX_.store(unpackCirclePadX(state), std::memory_order_relaxed);
    lastPolledCirclePadY_.store(unpackCirclePadY(state), std::memory_order_relaxed);
    const uint64_t cStickState = packedCStickState_.load(std::memory_order_acquire);
    lastPolledCStickX_.store(unpackAxisX(cStickState), std::memory_order_relaxed);
    lastPolledCStickY_.store(unpackAxisY(cStickState), std::memory_order_relaxed);
    const uint64_t pointerState = packedPointerState_.load(std::memory_order_acquire);
    lastPolledPointerX_.store(unpackAxisX(pointerState), std::memory_order_relaxed);
    lastPolledPointerY_.store(unpackAxisY(pointerState), std::memory_order_relaxed);
    lastPolledPointerPressed_.store(
            unpackPointerPressed(pointerState),
            std::memory_order_relaxed);
}

int16_t CoreGameplaySession::readInput(
        unsigned port,
        unsigned device,
        unsigned index,
        unsigned id) {
    inputStateCallbacks_.fetch_add(1, std::memory_order_relaxed);
    if (port != 0) {
        return 0;
    }
    if (device == kDeviceJoypad && index == 0) {
        joypadStateCallbacks_.fetch_add(1, std::memory_order_relaxed);
        const uint16_t mask = unpackButtonMask(
                packedInputState_.load(std::memory_order_acquire));
        if (id == kDeviceIdJoypadMask) {
            return static_cast<int16_t>(mask);
        }
        return id < 16 ? static_cast<int16_t>((mask >> id) & 1U) : 0;
    }
    if (device == kDeviceAnalog
            && (index == kDeviceIndexAnalogLeft || index == kDeviceIndexAnalogRight)) {
        analogStateCallbacks_.fetch_add(1, std::memory_order_relaxed);
        uint64_t state = packedInputState_.load(std::memory_order_acquire);
        if (index == kDeviceIndexAnalogRight) {
            rightAnalogStateCallbacks_.fetch_add(1, std::memory_order_relaxed);
            state = packedCStickState_.load(std::memory_order_acquire);
        }
        if (id == kDeviceIdAnalogX) {
            return index == kDeviceIndexAnalogLeft
                    ? unpackCirclePadX(state)
                    : unpackAxisX(state);
        }
        if (id == kDeviceIdAnalogY) {
            return index == kDeviceIndexAnalogLeft
                    ? unpackCirclePadY(state)
                    : unpackAxisY(state);
        }
    }
    if (device == kDevicePointer && index == 0) {
        pointerStateCallbacks_.fetch_add(1, std::memory_order_relaxed);
        const uint64_t state = packedPointerState_.load(std::memory_order_acquire);
        if (id == kDeviceIdPointerX) {
            return unpackAxisX(state);
        }
        if (id == kDeviceIdPointerY) {
            return unpackAxisY(state);
        }
        if (id == kDeviceIdPointerPressed) {
            return unpackPointerPressed(state) ? 1 : 0;
        }
    }
    return 0;
}

bool CoreGameplaySession::setSensorState(unsigned port, int action, unsigned rate) {
    sensorStateCallbacks_.fetch_add(1, std::memory_order_relaxed);
    if (port != 0 || rate == 0) {
        return false;
    }
    switch (action) {
    case kSensorAccelerometerEnable:
        accelerometerEnabled_.store(true, std::memory_order_release);
        sensorSamplingRateHz_.store(rate, std::memory_order_relaxed);
        return true;
    case kSensorAccelerometerDisable:
        accelerometerEnabled_.store(false, std::memory_order_release);
        if (!gyroscopeEnabled_.load(std::memory_order_acquire)) {
            sensorSamplingRateHz_.store(0, std::memory_order_relaxed);
        }
        return true;
    case kSensorGyroscopeEnable:
        gyroscopeEnabled_.store(true, std::memory_order_release);
        sensorSamplingRateHz_.store(rate, std::memory_order_relaxed);
        return true;
    case kSensorGyroscopeDisable:
        gyroscopeEnabled_.store(false, std::memory_order_release);
        if (!accelerometerEnabled_.load(std::memory_order_acquire)) {
            sensorSamplingRateHz_.store(0, std::memory_order_relaxed);
        }
        return true;
    default:
        return false;
    }
}

float CoreGameplaySession::readSensor(unsigned port, unsigned id) {
    if (port != 0 || id > kSensorGyroscopeZ) {
        return 0.0F;
    }
    sensorInputCallbacks_.fetch_add(1, std::memory_order_relaxed);
    const bool accelerometer = id <= kSensorAccelerometerZ;
    if (accelerometer && !accelerometerEnabled_.load(std::memory_order_acquire)) {
        return id == kSensorAccelerometerZ ? -1.0F : 0.0F;
    }
    if (!accelerometer && !gyroscopeEnabled_.load(std::memory_order_acquire)) {
        return 0.0F;
    }

    float value = 0.0F;
    switch (id) {
    case kSensorAccelerometerX:
        value = accelerometerX_.load(std::memory_order_acquire);
        lastPolledAccelerometerX_.store(value, std::memory_order_relaxed);
        break;
    case kSensorAccelerometerY:
        value = accelerometerY_.load(std::memory_order_acquire);
        lastPolledAccelerometerY_.store(value, std::memory_order_relaxed);
        break;
    case kSensorAccelerometerZ:
        value = accelerometerZ_.load(std::memory_order_acquire);
        lastPolledAccelerometerZ_.store(value, std::memory_order_relaxed);
        break;
    case kSensorGyroscopeX:
        value = gyroscopeX_.load(std::memory_order_acquire);
        lastPolledGyroscopeX_.store(value, std::memory_order_relaxed);
        break;
    case kSensorGyroscopeY:
        value = gyroscopeY_.load(std::memory_order_acquire);
        lastPolledGyroscopeY_.store(value, std::memory_order_relaxed);
        break;
    case kSensorGyroscopeZ:
        value = gyroscopeZ_.load(std::memory_order_acquire);
        lastPolledGyroscopeZ_.store(value, std::memory_order_relaxed);
        break;
    default:
        break;
    }
    return value;
}

bool CoreGameplaySession::isOwnerThread() const {
    return ownerThread_ == std::this_thread::get_id();
}

bool CoreGameplaySession::isContentPath(const char* path) const {
    return path != nullptr && contentPath_ == path;
}

void CoreGameplaySession::recordVfsOpen(bool content) {
    std::lock_guard<std::mutex> lock(reportMutex_);
    report_.vfsOpenCalls++;
    report_.contentOpenedThroughVfs = report_.contentOpenedThroughVfs || content;
}

void CoreGameplaySession::recordVfsRead(bool, size_t bytes) {
    std::lock_guard<std::mutex> lock(reportMutex_);
    report_.vfsReadCalls++;
    report_.vfsBytesRead += bytes;
}

bool CoreGameplaySession::handleEnvironment(unsigned command, void* data) {
    std::lock_guard<std::mutex> lock(reportMutex_);
    report_.environmentCallbacks++;
    switch (command) {
    case kEnvironmentGetSystemDirectory:
    case kEnvironmentGetCoreAssetsDirectory:
        if (data != nullptr) {
            *static_cast<const char**>(data) = systemDirectory_.c_str();
            return true;
        }
        return false;
    case kEnvironmentGetSaveDirectory:
        if (data != nullptr) {
            *static_cast<const char**>(data) = saveDirectory_.c_str();
            return true;
        }
        return false;
    case kEnvironmentGetLogInterface:
        if (data != nullptr) {
            static_cast<LogCallback*>(data)->log = coreLog;
            return true;
        }
        return false;
    case kEnvironmentGetCoreOptionsVersion:
        if (data != nullptr) {
            *static_cast<unsigned*>(data) = 2;
            return true;
        }
        return false;
    case kEnvironmentGetLanguage:
        if (data != nullptr) {
            *static_cast<unsigned*>(data) = kLanguagePortugueseBrazil;
            return true;
        }
        return false;
    case kEnvironmentGetCanDupe:
        if (data != nullptr) {
            *static_cast<bool*>(data) = true;
            return true;
        }
        return false;
    case kEnvironmentGetVariable: {
        if (data == nullptr) {
            return false;
        }
        auto* variable = static_cast<Variable*>(data);
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_graphics_api") == 0) {
            variable->value = "Vulkan";
            report_.preferredVulkan = true;
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_input_type") == 0) {
            variable->value = "frontend";
            report_.microphoneFrontendSelected = true;
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_audio_emulation") == 0) {
            // HLE is the core's fast audio path and avoids spending scarce frame time on LLE.
            variable->value = "HLE";
            report_.performanceOptionRequests++;
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_layout_option") == 0) {
            variable->value = screenLayout_.c_str();
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_swap_screen") == 0) {
            variable->value = swapScreen_.c_str();
            return true;
        }
        if (variable->key != nullptr
                && (std::strcmp(variable->key, "citra_use_cpu_jit") == 0
                        || std::strcmp(variable->key, "citra_use_hw_shader") == 0
                        || std::strcmp(variable->key, "citra_use_shader_jit") == 0)) {
            variable->value = "enabled";
            report_.performanceOptionRequests++;
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_shaders_accurate_mul") == 0) {
            variable->value = performanceProfile_ == "performance" ? "disabled" : "enabled";
            report_.performanceOptionRequests++;
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_cpu_clock_percentage") == 0) {
            variable->value = "100";
            report_.performanceOptionRequests++;
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_use_disk_shader_cache") == 0) {
            variable->value = "enabled";
            report_.performanceOptionRequests++;
            report_.diskShaderCacheEnabled = true;
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_resolution_factor") == 0) {
            variable->value = resolutionFactor_.c_str();
            report_.performanceOptionRequests++;
            report_.resolutionFactor = static_cast<uint32_t>(resolutionFactor_[0] - '0');
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_texture_filter") == 0) {
            variable->value = "none";
            report_.performanceOptionRequests++;
            return true;
        }
        if (variable->key != nullptr
                && std::strcmp(variable->key, "citra_texture_sampling") == 0) {
            variable->value = "GameControlled";
            report_.performanceOptionRequests++;
            return true;
        }
        if (variable->key != nullptr
                && (std::strcmp(variable->key, "citra_custom_textures") == 0
                        || std::strcmp(variable->key, "citra_dump_textures") == 0)) {
            variable->value = "disabled";
            report_.performanceOptionRequests++;
            return true;
        }
        return false;
    }
    case kEnvironmentGetVariableUpdate:
        if (data != nullptr) {
            *static_cast<bool*>(data) = false;
            return true;
        }
        return false;
    case kEnvironmentGetPreferredHwRender:
        if (data != nullptr) {
            *static_cast<int*>(data) = static_cast<int>(kHwContextVulkan);
            report_.preferredVulkan = true;
            return true;
        }
        return false;
    case kEnvironmentSetPixelFormat:
        return data != nullptr
                && *static_cast<const int*>(data) == static_cast<int>(kPixelFormatXrgb8888);
    case kEnvironmentSetHwRender: {
        if (data == nullptr) {
            return false;
        }
        auto* callback = static_cast<HardwareRenderCallback*>(data);
        if (callback->contextType != static_cast<int>(kHwContextVulkan)
                || callback->versionMajor < VK_API_VERSION_1_1
                || callback->contextReset == nullptr
                || callback->contextDestroy == nullptr) {
            return false;
        }
        hardwareCallback_ = *callback;
        report_.hardwareRenderNegotiated = true;
        return true;
    }
    case kEnvironmentSetHwRenderContextNegotiationInterface: {
        if (data == nullptr) {
            return false;
        }
        const auto* negotiation = static_cast<const VulkanNegotiationInterface*>(data);
        if (negotiation->interfaceType != kVulkanNegotiationInterface
                || negotiation->interfaceVersion < 1
                || negotiation->getApplicationInfo == nullptr
                || negotiation->createDevice == nullptr) {
            return false;
        }
        negotiation_ = negotiation;
        report_.negotiationInterfaceReceived = true;
        return true;
    }
    case kEnvironmentGetHwRenderContextNegotiationInterfaceSupport:
        if (data != nullptr) {
            auto* support = static_cast<VulkanNegotiationSupport*>(data);
            support->interfaceVersion = support->interfaceType == kVulkanNegotiationInterface
                    ? kVulkanNegotiationInterfaceVersion : 0;
            return true;
        }
        return false;
    case kEnvironmentGetHwRenderInterface:
        if (data != nullptr && renderHost_ != nullptr
                && renderHost_->interface() != nullptr) {
            *static_cast<const VulkanRenderInterface**>(data) = renderHost_->interface();
            report_.hardwareInterfaceProvided = true;
            return true;
        }
        return false;
    case kEnvironmentGetVfsInterface:
        if (data != nullptr) {
            auto* info = static_cast<VfsInterfaceInfo*>(data);
            if (info->requiredInterfaceVersion <= 2) {
                info->requiredInterfaceVersion = 2;
                info->interface = &g_vfsInterface;
                return true;
            }
        }
        return false;
    case kEnvironmentGetSensorInterface:
        if (data != nullptr) {
            auto* sensor = static_cast<SensorInterface*>(data);
            sensor->setSensorState = frontendSetSensorState;
            sensor->getSensorInput = frontendGetSensorInput;
            return true;
        }
        return false;
    case kEnvironmentGetMicrophoneInterface:
        if (data != nullptr) {
            auto* microphone = static_cast<MicrophoneInterface*>(data);
            if (microphone->interfaceVersion != kMicrophoneInterfaceVersion) {
                return false;
            }
            microphone->open = openMicrophone;
            microphone->close = closeMicrophone;
            microphone->getParameters = getMicrophoneParameters;
            microphone->setState = setMicrophoneState;
            microphone->getState = getMicrophoneState;
            microphone->read = frontendReadMicrophone;
            return true;
        }
        return false;
    case kEnvironmentSetMessage:
        if (data != nullptr) {
            const auto* message = static_cast<const Message*>(data);
            __android_log_print(
                    ANDROID_LOG_WARN,
                    "EmuOrbit-N3DS-Core",
                    "%s",
                    message->message == nullptr ? "" : message->message);
        }
        return true;
    case kEnvironmentSetCoreOptions:
    case kEnvironmentSetCoreOptionsInternational:
    case kEnvironmentSetCoreOptionsV2:
    case kEnvironmentSetCoreOptionsV2International:
    case kEnvironmentSetControllerInfo:
    case kEnvironmentSetInputDescriptors:
    case kEnvironmentSetSupportNoGame:
    case kEnvironmentSetSerializationQuirks:
    case kEnvironmentSetMemoryMaps:
    case kEnvironmentSetGeometry:
        return data != nullptr;
    case kEnvironmentSetVariables:
        return data != nullptr;
    case kEnvironmentSetHwSharedContext:
    case kEnvironmentGetRumbleInterface:
        return false;
    default:
        return false;
    }
}

void CoreGameplaySession::handleVideo(
        const void* data,
        unsigned width,
        unsigned height,
        size_t) {
    {
        std::lock_guard<std::mutex> lock(reportMutex_);
        report_.videoFrames++;
        report_.lastWidth = width;
        report_.lastHeight = height;
    }
    if (data != kHardwareFrameBufferValid) {
        frameError_ = data == nullptr
                ? "Azahar reported no frame after context reset"
                : "Azahar unexpectedly used a software video frame";
        return;
    }
    if (!renderHost_->presentCoreFrame(width, height, frameError_)) {
        return;
    }
    framePresentedThisRun_ = true;
}

size_t CoreGameplaySession::handleAudioBatch(const int16_t* data, size_t frames) {
    if (frames == 0) {
        return 0;
    }
    if (data == nullptr || frames > std::numeric_limits<size_t>::max() / 2) {
        return 0;
    }

    const size_t suppliedFrames = frames;
    size_t droppedFrames = 0;
    size_t queuedFrames = 0;
    {
        std::lock_guard<std::mutex> lock(audioMutex_);
        if (frames > kAudioRingCapacityFrames) {
            droppedFrames += frames - kAudioRingCapacityFrames;
            data += (frames - kAudioRingCapacityFrames) * 2;
            frames = kAudioRingCapacityFrames;
        }
        const size_t overflowFrames = audioQueuedFrames_ + frames > kAudioRingCapacityFrames
                ? audioQueuedFrames_ + frames - kAudioRingCapacityFrames
                : 0;
        if (overflowFrames > 0) {
            audioReadFrame_ = (audioReadFrame_ + overflowFrames)
                    % kAudioRingCapacityFrames;
            audioQueuedFrames_ -= overflowFrames;
            droppedFrames += overflowFrames;
        }

        const size_t writeFrame = (audioReadFrame_ + audioQueuedFrames_)
                % kAudioRingCapacityFrames;
        const size_t firstFrames = std::min(frames, kAudioRingCapacityFrames - writeFrame);
        std::copy_n(data, firstFrames * 2, audioRing_.data() + writeFrame * 2);
        const size_t secondFrames = frames - firstFrames;
        if (secondFrames > 0) {
            std::copy_n(data + firstFrames * 2, secondFrames * 2, audioRing_.data());
        }
        audioQueuedFrames_ += frames;
        queuedFrames = audioQueuedFrames_;
    }
    {
        std::lock_guard<std::mutex> lock(reportMutex_);
        report_.audioFrames += suppliedFrames;
        report_.audioQueuedFrames = queuedFrames;
        report_.audioDroppedFrames += droppedFrames;
        report_.audioPeakQueuedFrames = std::max<uint64_t>(
                report_.audioPeakQueuedFrames,
                queuedFrames);
    }
    return suppliedFrames;
}

void CoreGameplaySession::teardown() {
    if (g_activeSession.load(std::memory_order_acquire) == this) {
        if (contextReset_ && hardwareCallback_.contextDestroy != nullptr) {
            hardwareCallback_.contextDestroy();
            contextReset_ = false;
        }
        if (contentLoaded_ && core_.unloadGame != nullptr) {
            core_.unloadGame();
            contentLoaded_ = false;
        }
        renderHost_.reset();
        if (initialized_ && core_.deinit != nullptr) {
            core_.deinit();
            initialized_ = false;
        }
        g_activeSession.store(nullptr, std::memory_order_release);
    }
    if (library_ != nullptr) {
        if (!libraryProcessResident_) {
            dlclose(library_);
        }
        library_ = nullptr;
        libraryProcessResident_ = false;
    }
    if (ownsCoreSession_) {
        releaseCoreSession(this);
        ownsCoreSession_ = false;
    }
}

bool CoreGameplaySession::environmentCallback(unsigned command, void* data) {
    CoreGameplaySession* session = g_activeSession.load(std::memory_order_acquire);
    return session != nullptr && session->handleEnvironment(command, data);
}

void CoreGameplaySession::videoCallback(
        const void* data,
        unsigned width,
        unsigned height,
        size_t pitch) {
    CoreGameplaySession* session = g_activeSession.load(std::memory_order_acquire);
    if (session != nullptr) {
        session->handleVideo(data, width, height, pitch);
    }
}

void CoreGameplaySession::audioSampleCallback(int16_t left, int16_t right) {
    CoreGameplaySession* session = g_activeSession.load(std::memory_order_acquire);
    if (session != nullptr) {
        const int16_t samples[] = {left, right};
        session->handleAudioBatch(samples, 1);
    }
}

size_t CoreGameplaySession::audioBatchCallback(const int16_t* data, size_t frames) {
    CoreGameplaySession* session = g_activeSession.load(std::memory_order_acquire);
    return session == nullptr ? 0 : session->handleAudioBatch(data, frames);
}

void CoreGameplaySession::inputPollCallback() {
    CoreGameplaySession* session = g_activeSession.load(std::memory_order_acquire);
    if (session != nullptr) {
        session->recordInputPoll();
    }
}

int16_t CoreGameplaySession::inputStateCallback(
        unsigned port,
        unsigned device,
        unsigned index,
        unsigned id) {
    CoreGameplaySession* session = g_activeSession.load(std::memory_order_acquire);
    return session == nullptr ? 0 : session->readInput(port, device, index, id);
}

}  // namespace emuorbit::n3ds
