// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright 2026 EmuOrbit contributors

#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl32.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <unistd.h>
#include <vector>

#define VK_NO_PROTOTYPES
#include <libretro.h>
#include <libretro_vulkan.h>

struct retro_vfs_file_handle {
    std::FILE* file;
    std::string path;
    bool is_content;
};

struct retro_microphone {
    unsigned rate;
    bool active;
};

namespace {

std::string g_system_directory;
std::string g_save_directory;
std::string g_content_path;
std::string g_renderer = "Software";
std::vector<uint32_t> g_software_framebuffer;
size_t g_video_frames = 0;
size_t g_audio_frames = 0;
bool g_hardware_frame_seen = false;
bool g_hardware_context_destroyed = false;
bool g_swap_failed = false;
retro_hw_render_callback g_hw_render{};
bool g_hw_render_negotiated = false;
const retro_hw_render_context_negotiation_interface_vulkan* g_vulkan_negotiation = nullptr;
std::atomic_size_t g_vfs_open_calls{0};
std::atomic_size_t g_vfs_read_calls{0};
std::atomic_size_t g_vfs_bytes_read{0};
std::atomic_size_t g_vfs_content_bytes_read{0};
std::atomic_size_t g_input_poll_calls{0};
std::atomic_size_t g_input_state_calls{0};
std::atomic_size_t g_sensor_state_calls{0};
std::atomic_size_t g_sensor_input_calls{0};
std::atomic_size_t g_microphone_open_calls{0};
std::atomic_size_t g_microphone_read_calls{0};
bool g_vfs_requested = false;
std::atomic_bool g_vfs_content_opened{false};
bool g_sensor_requested = false;
bool g_microphone_requested = false;

class EglContext {
public:
    bool create(unsigned width, unsigned height) {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY) {
            return fail("eglGetDisplay");
        }

        EGLint major = 0;
        EGLint minor = 0;
        if (eglInitialize(display_, &major, &minor) != EGL_TRUE) {
            return fail("eglInitialize");
        }
        if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
            return fail("eglBindAPI");
        }

        const EGLint config_attributes[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, g_hw_render.depth ? 24 : 0,
            EGL_STENCIL_SIZE, g_hw_render.stencil ? 8 : 0,
            EGL_NONE,
        };
        EGLConfig config = nullptr;
        EGLint config_count = 0;
        if (eglChooseConfig(display_, config_attributes, &config, 1, &config_count) != EGL_TRUE ||
            config_count != 1) {
            return fail("eglChooseConfig");
        }

        const EGLint context_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE,
        };
        context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, context_attributes);
        if (context_ == EGL_NO_CONTEXT) {
            return fail("eglCreateContext");
        }

        const EGLint surface_attributes[] = {
            EGL_WIDTH, static_cast<EGLint>(width),
            EGL_HEIGHT, static_cast<EGLint>(height),
            EGL_NONE,
        };
        surface_ = eglCreatePbufferSurface(display_, config, surface_attributes);
        if (surface_ == EGL_NO_SURFACE) {
            return fail("eglCreatePbufferSurface");
        }
        if (eglMakeCurrent(display_, surface_, surface_, context_) != EGL_TRUE) {
            return fail("eglMakeCurrent");
        }

        GLint gl_major = 0;
        GLint gl_minor = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &gl_major);
        glGetIntegerv(GL_MINOR_VERSION, &gl_minor);
        const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
        std::printf("PROBE_EGL_VERSION=%d.%d\n", major, minor);
        std::printf("PROBE_GLES_VERSION=%s\n", version == nullptr ? "" : version);
        if (gl_major < 3 || (gl_major == 3 && gl_minor < 2)) {
            std::fprintf(stderr, "PROBE_ERROR=OpenGL ES 3.2 is required, received %d.%d\n",
                         gl_major, gl_minor);
            return false;
        }
        return true;
    }

    void destroy() {
        if (display_ == EGL_NO_DISPLAY) {
            return;
        }
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface_ != EGL_NO_SURFACE) {
            eglDestroySurface(display_, surface_);
            surface_ = EGL_NO_SURFACE;
        }
        if (context_ != EGL_NO_CONTEXT) {
            eglDestroyContext(display_, context_);
            context_ = EGL_NO_CONTEXT;
        }
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
    }

    bool swap_buffers() const {
        return display_ != EGL_NO_DISPLAY && surface_ != EGL_NO_SURFACE &&
               eglSwapBuffers(display_, surface_) == EGL_TRUE;
    }

    ~EglContext() {
        destroy();
    }

private:
    bool fail(const char* operation) const {
        std::fprintf(stderr, "PROBE_ERROR=%s failed with EGL error 0x%04x\n", operation,
                     eglGetError());
        return false;
    }

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
};

EglContext g_egl_context;

bool uses_opengles() {
    return g_renderer == "OpenGL";
}

bool uses_vulkan() {
    return g_renderer == "Vulkan";
}

bool uses_hardware_renderer() {
    return uses_opengles() || uses_vulkan();
}

uintptr_t current_framebuffer() {
    return 0;
}

retro_proc_address_t get_gl_proc_address(const char* symbol) {
    auto address = eglGetProcAddress(symbol);
    if (address != nullptr) {
        return reinterpret_cast<retro_proc_address_t>(address);
    }
    return reinterpret_cast<retro_proc_address_t>(dlsym(RTLD_DEFAULT, symbol));
}

class VulkanContext {
public:
    bool create() {
        if (g_vulkan_negotiation == nullptr ||
            g_vulkan_negotiation->interface_type !=
                RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN ||
            g_vulkan_negotiation->interface_version < 1 ||
            g_vulkan_negotiation->create_device == nullptr) {
            return fail("Vulkan context negotiation interface is unavailable");
        }

        loader_ = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (loader_ == nullptr) {
            return fail(dlerror());
        }
        get_instance_proc_addr_ = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            dlsym(loader_, "vkGetInstanceProcAddr"));
        if (get_instance_proc_addr_ == nullptr) {
            return fail("vkGetInstanceProcAddr is unavailable");
        }

        const auto create_instance = load_instance<PFN_vkCreateInstance>(
            VK_NULL_HANDLE, "vkCreateInstance");
        if (create_instance == nullptr) {
            return false;
        }

        VkApplicationInfo fallback_application{};
        fallback_application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        fallback_application.pApplicationName = "EmuOrbit N3DS probe";
        fallback_application.applicationVersion = 1;
        fallback_application.pEngineName = "libretro probe";
        fallback_application.engineVersion = 1;
        fallback_application.apiVersion = VK_API_VERSION_1_1;
        const VkApplicationInfo* application = &fallback_application;
        if (g_vulkan_negotiation->get_application_info != nullptr) {
            application = g_vulkan_negotiation->get_application_info();
        }
        if (application == nullptr) {
            return fail("Vulkan application info is unavailable");
        }

        VkInstanceCreateInfo instance_info{};
        instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instance_info.pApplicationInfo = application;
        if (create_instance(&instance_info, nullptr, &instance_) != VK_SUCCESS ||
            instance_ == VK_NULL_HANDLE) {
            return fail("vkCreateInstance failed");
        }

        const auto enumerate_devices = load_instance<PFN_vkEnumeratePhysicalDevices>(
            instance_, "vkEnumeratePhysicalDevices");
        const auto get_properties = load_instance<PFN_vkGetPhysicalDeviceProperties>(
            instance_, "vkGetPhysicalDeviceProperties");
        get_device_proc_addr_ = load_instance<PFN_vkGetDeviceProcAddr>(
            instance_, "vkGetDeviceProcAddr");
        if (enumerate_devices == nullptr || get_properties == nullptr ||
            get_device_proc_addr_ == nullptr) {
            return false;
        }

        uint32_t device_count = 0;
        if (enumerate_devices(instance_, &device_count, nullptr) != VK_SUCCESS ||
            device_count == 0) {
            return fail("no Vulkan physical device is available");
        }
        std::vector<VkPhysicalDevice> devices(device_count);
        if (enumerate_devices(instance_, &device_count, devices.data()) != VK_SUCCESS ||
            device_count == 0) {
            return fail("Vulkan physical-device enumeration failed");
        }
        gpu_ = devices.front();

        retro_vulkan_context negotiated{};
        if (!g_vulkan_negotiation->create_device(
                &negotiated, instance_, gpu_, VK_NULL_HANDLE, get_instance_proc_addr_,
                nullptr, 0, nullptr, 0, nullptr) ||
            negotiated.gpu == VK_NULL_HANDLE || negotiated.device == VK_NULL_HANDLE ||
            negotiated.queue == VK_NULL_HANDLE) {
            return fail("core Vulkan device negotiation failed");
        }
        gpu_ = negotiated.gpu;
        device_ = negotiated.device;
        queue_ = negotiated.queue;
        queue_family_index_ = negotiated.queue_family_index;

        VkPhysicalDeviceProperties properties{};
        get_properties(gpu_, &properties);
        std::printf("PROBE_VULKAN_DEVICE=%s\n", properties.deviceName);
        std::printf("PROBE_VULKAN_API=%u.%u.%u\n",
                    VK_VERSION_MAJOR(properties.apiVersion),
                    VK_VERSION_MINOR(properties.apiVersion),
                    VK_VERSION_PATCH(properties.apiVersion));

        interface_.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
        interface_.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
        interface_.handle = this;
        interface_.instance = instance_;
        interface_.gpu = gpu_;
        interface_.device = device_;
        interface_.get_device_proc_addr = get_device_proc_addr_;
        interface_.get_instance_proc_addr = get_instance_proc_addr_;
        interface_.queue = queue_;
        interface_.queue_index = queue_family_index_;
        interface_.set_image = set_image;
        interface_.get_sync_index = get_sync_index;
        interface_.get_sync_index_mask = get_sync_index_mask;
        interface_.set_command_buffers = set_command_buffers;
        interface_.wait_sync_index = wait_sync_index;
        interface_.lock_queue = lock_queue;
        interface_.unlock_queue = unlock_queue;
        interface_.set_signal_semaphore = set_signal_semaphore;
        ready_ = true;
        return true;
    }

    void destroy() {
        ready_ = false;
        interface_.handle = nullptr;
        if (device_ != VK_NULL_HANDLE) {
            const auto wait_idle = reinterpret_cast<PFN_vkDeviceWaitIdle>(
                get_device_proc_addr_ == nullptr
                    ? nullptr
                    : get_device_proc_addr_(device_, "vkDeviceWaitIdle"));
            if (wait_idle != nullptr) {
                wait_idle(device_);
            }
            if (g_vulkan_negotiation != nullptr &&
                g_vulkan_negotiation->destroy_device != nullptr) {
                g_vulkan_negotiation->destroy_device();
            }
            const auto destroy_device = load_instance<PFN_vkDestroyDevice>(
                instance_, "vkDestroyDevice");
            if (destroy_device != nullptr) {
                destroy_device(device_, nullptr);
            }
            device_ = VK_NULL_HANDLE;
        }
        if (instance_ != VK_NULL_HANDLE) {
            const auto destroy_instance = load_instance<PFN_vkDestroyInstance>(
                instance_, "vkDestroyInstance");
            if (destroy_instance != nullptr) {
                destroy_instance(instance_, nullptr);
            }
            instance_ = VK_NULL_HANDLE;
        }
        if (loader_ != nullptr) {
            dlclose(loader_);
            loader_ = nullptr;
        }
        get_instance_proc_addr_ = nullptr;
        get_device_proc_addr_ = nullptr;
        gpu_ = VK_NULL_HANDLE;
        queue_ = VK_NULL_HANDLE;
        interface_ = {};
    }

    const retro_hw_render_interface_vulkan* interface() const {
        return ready_ ? &interface_ : nullptr;
    }

    size_t image_updates() const {
        return image_updates_.load();
    }

    bool valid_image_seen() const {
        return valid_image_seen_.load();
    }

    bool unsupported_submission_seen() const {
        return unsupported_submission_seen_.load();
    }

    ~VulkanContext() {
        destroy();
    }

private:
    template <typename Function>
    Function load_instance(VkInstance instance, const char* name) const {
        if (get_instance_proc_addr_ == nullptr) {
            return nullptr;
        }
        auto function = reinterpret_cast<Function>(get_instance_proc_addr_(instance, name));
        if (function == nullptr) {
            std::fprintf(stderr, "PROBE_ERROR=%s is unavailable\n", name);
        }
        return function;
    }

    bool fail(const char* reason) const {
        std::fprintf(stderr, "PROBE_ERROR=%s\n", reason == nullptr ? "Vulkan failure" : reason);
        return false;
    }

    static VulkanContext* from_handle(void* handle) {
        return static_cast<VulkanContext*>(handle);
    }

    static void set_image(void* handle, const retro_vulkan_image* image,
                          uint32_t semaphore_count, const VkSemaphore*, uint32_t) {
        auto* context = from_handle(handle);
        if (context == nullptr) {
            return;
        }
        ++context->image_updates_;
        const bool valid = image != nullptr && image->image_view != VK_NULL_HANDLE &&
                           image->create_info.image != VK_NULL_HANDLE &&
                           image->create_info.viewType == VK_IMAGE_VIEW_TYPE_2D;
        context->valid_image_seen_.store(context->valid_image_seen_.load() || valid);
        if (semaphore_count != 0) {
            context->unsupported_submission_seen_.store(true);
        }
    }

    static uint32_t get_sync_index(void*) {
        return 0;
    }

    static uint32_t get_sync_index_mask(void*) {
        return 1;
    }

    static void set_command_buffers(void* handle, uint32_t command_count,
                                    const VkCommandBuffer*) {
        if (command_count != 0) {
            auto* context = from_handle(handle);
            if (context != nullptr) {
                context->unsupported_submission_seen_.store(true);
            }
        }
    }

    static void wait_sync_index(void*) {}

    static void lock_queue(void* handle) {
        auto* context = from_handle(handle);
        if (context != nullptr) {
            context->queue_mutex_.lock();
        }
    }

    static void unlock_queue(void* handle) {
        auto* context = from_handle(handle);
        if (context != nullptr) {
            context->queue_mutex_.unlock();
        }
    }

    static void set_signal_semaphore(void* handle, VkSemaphore semaphore) {
        if (semaphore != VK_NULL_HANDLE) {
            auto* context = from_handle(handle);
            if (context != nullptr) {
                context->unsupported_submission_seen_.store(true);
            }
        }
    }

    void* loader_ = nullptr;
    PFN_vkGetInstanceProcAddr get_instance_proc_addr_ = nullptr;
    PFN_vkGetDeviceProcAddr get_device_proc_addr_ = nullptr;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice gpu_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queue_family_index_ = 0;
    retro_hw_render_interface_vulkan interface_{};
    std::mutex queue_mutex_;
    std::atomic_size_t image_updates_{0};
    std::atomic_bool valid_image_seen_{false};
    std::atomic_bool unsupported_submission_seen_{false};
    bool ready_ = false;
};

VulkanContext g_vulkan_context;

const char* vfs_get_path(retro_vfs_file_handle* stream) {
    return stream == nullptr ? nullptr : stream->path.c_str();
}

retro_vfs_file_handle* vfs_open(const char* path, unsigned mode, unsigned hints) {
    static_cast<void>(hints);
    if (path == nullptr) {
        return nullptr;
    }

    const char* stdio_mode = nullptr;
    switch (mode) {
    case RETRO_VFS_FILE_ACCESS_READ:
        stdio_mode = "rb";
        break;
    case RETRO_VFS_FILE_ACCESS_WRITE:
        stdio_mode = "wb";
        break;
    case RETRO_VFS_FILE_ACCESS_READ_WRITE:
        stdio_mode = "w+b";
        break;
    case RETRO_VFS_FILE_ACCESS_WRITE | RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING:
    case RETRO_VFS_FILE_ACCESS_READ_WRITE | RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING:
        stdio_mode = "r+b";
        break;
    default:
        return nullptr;
    }

    std::FILE* file = std::fopen(path, stdio_mode);
    if (file == nullptr) {
        return nullptr;
    }
    const bool is_content = !g_content_path.empty() && g_content_path == path;
    auto* stream = new (std::nothrow) retro_vfs_file_handle{file, path, is_content};
    if (stream == nullptr) {
        std::fclose(file);
        return nullptr;
    }
    ++g_vfs_open_calls;
    if (is_content) {
        g_vfs_content_opened.store(true);
    }
    return stream;
}

int vfs_close(retro_vfs_file_handle* stream) {
    if (stream == nullptr) {
        return -1;
    }
    const int result = std::fclose(stream->file);
    delete stream;
    return result == 0 ? 0 : -1;
}

int64_t vfs_tell(retro_vfs_file_handle* stream) {
    if (stream == nullptr) {
        return -1;
    }
    const off_t position = ftello(stream->file);
    return position < 0 ? -1 : static_cast<int64_t>(position);
}

int64_t vfs_seek(retro_vfs_file_handle* stream, int64_t offset, int seek_position) {
    if (stream == nullptr) {
        return -1;
    }
    int origin = 0;
    switch (seek_position) {
    case RETRO_VFS_SEEK_POSITION_START:
        origin = SEEK_SET;
        break;
    case RETRO_VFS_SEEK_POSITION_CURRENT:
        origin = SEEK_CUR;
        break;
    case RETRO_VFS_SEEK_POSITION_END:
        origin = SEEK_END;
        break;
    default:
        return -1;
    }
    if (fseeko(stream->file, static_cast<off_t>(offset), origin) != 0) {
        return -1;
    }
    return vfs_tell(stream);
}

int64_t vfs_size(retro_vfs_file_handle* stream) {
    const int64_t position = vfs_tell(stream);
    if (position < 0 || vfs_seek(stream, 0, RETRO_VFS_SEEK_POSITION_END) < 0) {
        return -1;
    }
    const int64_t size = vfs_tell(stream);
    if (vfs_seek(stream, position, RETRO_VFS_SEEK_POSITION_START) < 0) {
        return -1;
    }
    return size;
}

int64_t vfs_read(retro_vfs_file_handle* stream, void* buffer, uint64_t length) {
    if (stream == nullptr || buffer == nullptr ||
        length > std::numeric_limits<size_t>::max()) {
        return -1;
    }
    const size_t read = std::fread(buffer, 1, static_cast<size_t>(length), stream->file);
    if (read == 0 && std::ferror(stream->file) != 0) {
        return -1;
    }
    ++g_vfs_read_calls;
    g_vfs_bytes_read += read;
    if (stream->is_content) {
        g_vfs_content_bytes_read += read;
    }
    return static_cast<int64_t>(read);
}

int64_t vfs_write(retro_vfs_file_handle* stream, const void* buffer, uint64_t length) {
    if (stream == nullptr || buffer == nullptr ||
        length > std::numeric_limits<size_t>::max()) {
        return -1;
    }
    const size_t written = std::fwrite(buffer, 1, static_cast<size_t>(length), stream->file);
    if (written == 0 && std::ferror(stream->file) != 0) {
        return -1;
    }
    return static_cast<int64_t>(written);
}

int vfs_flush(retro_vfs_file_handle* stream) {
    return stream != nullptr && std::fflush(stream->file) == 0 ? 0 : -1;
}

int vfs_remove(const char* path) {
    return path != nullptr && std::remove(path) == 0 ? 0 : -1;
}

int vfs_rename(const char* old_path, const char* new_path) {
    return old_path != nullptr && new_path != nullptr &&
                   std::rename(old_path, new_path) == 0
               ? 0
               : -1;
}

int64_t vfs_truncate(retro_vfs_file_handle* stream, int64_t length) {
    if (stream == nullptr || length < 0 ||
        static_cast<uint64_t>(length) >
            static_cast<uint64_t>(std::numeric_limits<off_t>::max())) {
        return -1;
    }
    return ftruncate(fileno(stream->file), static_cast<off_t>(length)) == 0 ? 0 : -1;
}

retro_vfs_interface g_vfs_interface{
    vfs_get_path,
    vfs_open,
    vfs_close,
    vfs_size,
    vfs_tell,
    vfs_seek,
    vfs_read,
    vfs_write,
    vfs_flush,
    vfs_remove,
    vfs_rename,
    vfs_truncate,
    nullptr,
    nullptr,
    nullptr,
    nullptr,
    nullptr,
    nullptr,
    nullptr,
};

bool set_sensor_state(unsigned port, retro_sensor_action action, unsigned rate) {
    ++g_sensor_state_calls;
    if (port != 0 || rate == 0) {
        return false;
    }
    switch (action) {
    case RETRO_SENSOR_ACCELEROMETER_ENABLE:
    case RETRO_SENSOR_ACCELEROMETER_DISABLE:
    case RETRO_SENSOR_GYROSCOPE_ENABLE:
    case RETRO_SENSOR_GYROSCOPE_DISABLE:
        return true;
    default:
        return false;
    }
}

float get_sensor_input(unsigned port, unsigned id) {
    ++g_sensor_input_calls;
    if (port != 0 || id > RETRO_SENSOR_GYROSCOPE_Z) {
        return 0.0F;
    }
    return id == RETRO_SENSOR_ACCELEROMETER_Z ? -1.0F : 0.0F;
}

retro_microphone_t* open_microphone(const retro_microphone_params_t* params) {
    const unsigned rate = params != nullptr && params->rate != 0 ? params->rate : 48000;
    auto* microphone = new (std::nothrow) retro_microphone{rate, false};
    if (microphone != nullptr) {
        ++g_microphone_open_calls;
    }
    return microphone;
}

void close_microphone(retro_microphone_t* microphone) {
    delete microphone;
}

bool get_microphone_params(const retro_microphone_t* microphone,
                           retro_microphone_params_t* params) {
    if (microphone == nullptr || params == nullptr) {
        return false;
    }
    params->rate = microphone->rate;
    return true;
}

bool set_microphone_state(retro_microphone_t* microphone, bool active) {
    if (microphone == nullptr) {
        return false;
    }
    microphone->active = active;
    return true;
}

bool get_microphone_state(const retro_microphone_t* microphone) {
    return microphone != nullptr && microphone->active;
}

int read_microphone(retro_microphone_t* microphone, int16_t* samples, size_t sample_count) {
    if (microphone == nullptr || !microphone->active || samples == nullptr ||
        sample_count > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return -1;
    }
    std::fill_n(samples, sample_count, int16_t{0});
    ++g_microphone_read_calls;
    return static_cast<int>(sample_count);
}

void core_log(enum retro_log_level level, const char* format, ...) {
    std::fprintf(stderr, "CORE_LOG[%d] ", static_cast<int>(level));
    va_list arguments;
    va_start(arguments, format);
    std::vfprintf(stderr, format, arguments);
    va_end(arguments);
}

bool environment_callback(unsigned command, void* data) {
    switch (command) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
        if (data == nullptr) {
            return false;
        }
        *static_cast<const char**>(data) = g_system_directory.c_str();
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        if (data == nullptr) {
            return false;
        }
        *static_cast<const char**>(data) = g_save_directory.c_str();
        return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        if (data == nullptr) {
            return false;
        }
        static_cast<retro_log_callback*>(data)->log = core_log;
        return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        if (data == nullptr) {
            return false;
        }
        *static_cast<unsigned*>(data) = 2;
        return true;
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        if (data == nullptr) {
            return false;
        }
        *static_cast<unsigned*>(data) = RETRO_LANGUAGE_PORTUGUESE_BRAZIL;
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        if (data == nullptr) {
            return false;
        }
        *static_cast<bool*>(data) = true;
        return true;
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
        if (data == nullptr) {
            return false;
        }
        *static_cast<retro_hw_context_type*>(data) =
            uses_opengles() ? RETRO_HW_CONTEXT_OPENGLES3
                            : (uses_vulkan() ? RETRO_HW_CONTEXT_VULKAN
                                             : RETRO_HW_CONTEXT_NONE);
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        if (data == nullptr) {
            return false;
        }
        auto* variable = static_cast<retro_variable*>(data);
        if (variable->key != nullptr &&
            std::strcmp(variable->key, "citra_graphics_api") == 0) {
            variable->value = g_renderer.c_str();
            return true;
        }
        return false;
    }
    case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER: {
        if (data == nullptr) {
            return false;
        }
        auto* framebuffer = static_cast<retro_framebuffer*>(data);
        if (framebuffer->width == 0 || framebuffer->height == 0 ||
            framebuffer->width >
                std::numeric_limits<size_t>::max() / framebuffer->height) {
            return false;
        }
        const size_t pixels = static_cast<size_t>(framebuffer->width) *
                              framebuffer->height;
        g_software_framebuffer.resize(pixels);
        framebuffer->data = g_software_framebuffer.data();
        framebuffer->pitch = static_cast<size_t>(framebuffer->width) * sizeof(uint32_t);
        framebuffer->format = RETRO_PIXEL_FORMAT_XRGB8888;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_MESSAGE:
        if (data != nullptr) {
            const auto* message = static_cast<const retro_message*>(data);
            std::fprintf(stderr, "CORE_MESSAGE=%s\n",
                         message->msg == nullptr ? "" : message->msg);
        }
        return true;
    case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
        return uses_opengles();
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        if (!uses_hardware_renderer() || data == nullptr) {
            return false;
        }
        auto* callback = static_cast<retro_hw_render_callback*>(data);
        const bool valid_opengles =
            uses_opengles() && callback->context_type == RETRO_HW_CONTEXT_OPENGLES3 &&
            callback->version_major == 3 && callback->version_minor == 2;
        const bool valid_vulkan =
            uses_vulkan() && callback->context_type == RETRO_HW_CONTEXT_VULKAN &&
            callback->version_major >= VK_API_VERSION_1_1;
        if ((!valid_opengles && !valid_vulkan) ||
            callback->context_reset == nullptr || callback->context_destroy == nullptr) {
            std::fprintf(stderr, "PROBE_ERROR=unsupported hardware render request\n");
            return false;
        }
        if (uses_opengles()) {
            callback->get_current_framebuffer = current_framebuffer;
            callback->get_proc_address = get_gl_proc_address;
        }
        g_hw_render = *callback;
        g_hw_render_negotiated = true;
        if (uses_opengles()) {
            std::printf("PROBE_HW_CONTEXT=OpenGLES/%u.%u\n", callback->version_major,
                        callback->version_minor);
        } else {
            std::printf("PROBE_HW_CONTEXT=Vulkan/%u.%u.%u\n",
                        VK_VERSION_MAJOR(callback->version_major),
                        VK_VERSION_MINOR(callback->version_major),
                        VK_VERSION_PATCH(callback->version_major));
        }
        return true;
    }
    case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
        if (!uses_vulkan() || data == nullptr) {
            return false;
        }
        const auto* negotiation =
            static_cast<const retro_hw_render_context_negotiation_interface_vulkan*>(data);
        if (negotiation->interface_type !=
                RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN ||
            negotiation->interface_version < 1 || negotiation->get_application_info == nullptr ||
            negotiation->create_device == nullptr) {
            std::fprintf(stderr, "PROBE_ERROR=unsupported Vulkan negotiation interface\n");
            return false;
        }
        g_vulkan_negotiation = negotiation;
        std::printf("PROBE_VULKAN_NEGOTIATION_VERSION=%u\n",
                    negotiation->interface_version);
        return true;
    }
    case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
        if (data == nullptr) {
            return false;
        }
        auto* support =
            static_cast<retro_hw_render_context_negotiation_interface*>(data);
        support->interface_version =
            support->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN
                ? RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN_VERSION
                : 0;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
        if (!uses_vulkan() || data == nullptr || g_vulkan_context.interface() == nullptr) {
            return false;
        }
        *static_cast<const retro_hw_render_interface_vulkan**>(data) =
            g_vulkan_context.interface();
        return true;
    case RETRO_ENVIRONMENT_GET_VFS_INTERFACE: {
        if (data == nullptr) {
            return false;
        }
        auto* info = static_cast<retro_vfs_interface_info*>(data);
        if (info->required_interface_version > 2) {
            return false;
        }
        info->required_interface_version = 2;
        info->iface = &g_vfs_interface;
        g_vfs_requested = true;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE: {
        if (data == nullptr) {
            return false;
        }
        auto* sensor = static_cast<retro_sensor_interface*>(data);
        sensor->set_sensor_state = set_sensor_state;
        sensor->get_sensor_input = get_sensor_input;
        g_sensor_requested = true;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_MICROPHONE_INTERFACE: {
        if (data == nullptr) {
            return false;
        }
        auto* microphone = static_cast<retro_microphone_interface*>(data);
        if (microphone->interface_version != RETRO_MICROPHONE_INTERFACE_VERSION) {
            return false;
        }
        microphone->open_mic = open_microphone;
        microphone->close_mic = close_microphone;
        microphone->get_params = get_microphone_params;
        microphone->set_mic_state = set_microphone_state;
        microphone->get_mic_state = get_microphone_state;
        microphone->read_mic = read_microphone;
        g_microphone_requested = true;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        if (data == nullptr) {
            return false;
        }
        return *static_cast<const retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
        return false;
    default:
        std::fprintf(stderr, "ENV_UNSUPPORTED=%u\n", command);
        return false;
    }
}

void video_callback(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (data == nullptr || width == 0 || height == 0) {
        return;
    }
    ++g_video_frames;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        g_hardware_frame_seen = true;
        if (uses_opengles() && !g_egl_context.swap_buffers()) {
            g_swap_failed = true;
            std::fprintf(stderr, "PROBE_ERROR=eglSwapBuffers failed\n");
        } else if (uses_vulkan() && !g_vulkan_context.valid_image_seen()) {
            g_swap_failed = true;
            std::fprintf(stderr, "PROBE_ERROR=Vulkan frame has no negotiated image\n");
        }
    }
    if (g_video_frames == 1) {
        std::printf("PROBE_FIRST_VIDEO=%ux%u/%zu/%s\n", width, height, pitch,
                    data == RETRO_HW_FRAME_BUFFER_VALID ? "HW" : "SW");
    }
}

void audio_sample_callback(int16_t, int16_t) {}

size_t audio_batch_callback(const int16_t*, size_t frames) {
    g_audio_frames += frames;
    return frames;
}

void input_poll_callback() {
    ++g_input_poll_calls;
}

int16_t input_state_callback(unsigned, unsigned, unsigned, unsigned) {
    ++g_input_state_calls;
    return 0;
}

template <typename Function>
Function load_required_symbol(void* library, const char* name) {
    dlerror();
    void* symbol = dlsym(library, name);
    const char* error = dlerror();
    if (error != nullptr || symbol == nullptr) {
        std::fprintf(stderr, "PROBE_ERROR=missing symbol %s: %s\n", name,
                     error == nullptr ? "unknown error" : error);
        return nullptr;
    }
    return reinterpret_cast<Function>(symbol);
}

struct CoreApi {
    decltype(&retro_api_version) api_version{};
    decltype(&retro_get_system_info) get_system_info{};
    decltype(&retro_get_system_av_info) get_system_av_info{};
    decltype(&retro_set_environment) set_environment{};
    decltype(&retro_set_video_refresh) set_video_refresh{};
    decltype(&retro_set_audio_sample) set_audio_sample{};
    decltype(&retro_set_audio_sample_batch) set_audio_sample_batch{};
    decltype(&retro_set_input_poll) set_input_poll{};
    decltype(&retro_set_input_state) set_input_state{};
    decltype(&retro_init) init{};
    decltype(&retro_deinit) deinit{};
    decltype(&retro_load_game) load_game{};
    decltype(&retro_unload_game) unload_game{};
    decltype(&retro_run) run{};
    decltype(&retro_serialize_size) serialize_size{};
    decltype(&retro_serialize) serialize{};
    decltype(&retro_unserialize) unserialize{};

    bool load(void* library) {
#define LOAD_REQUIRED(field, symbol)                                                     \
    field = load_required_symbol<decltype(field)>(library, symbol);                     \
    if (field == nullptr) {                                                              \
        return false;                                                                    \
    }
        LOAD_REQUIRED(api_version, "retro_api_version")
        LOAD_REQUIRED(get_system_info, "retro_get_system_info")
        LOAD_REQUIRED(get_system_av_info, "retro_get_system_av_info")
        LOAD_REQUIRED(set_environment, "retro_set_environment")
        LOAD_REQUIRED(set_video_refresh, "retro_set_video_refresh")
        LOAD_REQUIRED(set_audio_sample, "retro_set_audio_sample")
        LOAD_REQUIRED(set_audio_sample_batch, "retro_set_audio_sample_batch")
        LOAD_REQUIRED(set_input_poll, "retro_set_input_poll")
        LOAD_REQUIRED(set_input_state, "retro_set_input_state")
        LOAD_REQUIRED(init, "retro_init")
        LOAD_REQUIRED(deinit, "retro_deinit")
        LOAD_REQUIRED(load_game, "retro_load_game")
        LOAD_REQUIRED(unload_game, "retro_unload_game")
        LOAD_REQUIRED(run, "retro_run")
        LOAD_REQUIRED(serialize_size, "retro_serialize_size")
        LOAD_REQUIRED(serialize, "retro_serialize")
        LOAD_REQUIRED(unserialize, "retro_unserialize")
#undef LOAD_REQUIRED
        return true;
    }
};

bool is_nonempty(const char* value) {
    return value != nullptr && value[0] != '\0';
}

}  // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    std::setvbuf(stderr, nullptr, _IONBF, 0);

    if (argc != 4 && argc != 6 && argc != 7 && argc != 8) {
        std::fprintf(stderr,
                     "usage: %s <core.so> <system-dir> <save-dir> "
                     "[content-path frame-count [software|opengles|vulkan "
                     "[maximum-state-bytes]]]\n",
                     argv[0]);
        return 64;
    }

    g_system_directory = argv[2];
    g_save_directory = argv[3];
    if (argc >= 7) {
        if (std::strcmp(argv[6], "software") == 0) {
            g_renderer = "Software";
        } else if (std::strcmp(argv[6], "opengles") == 0) {
            g_renderer = "OpenGL";
        } else if (std::strcmp(argv[6], "vulkan") == 0) {
            g_renderer = "Vulkan";
        } else {
            std::fprintf(stderr,
                         "PROBE_ERROR=renderer must be software, opengles or vulkan\n");
            return 64;
        }
    }
    size_t maximum_state_bytes = 0;
    const bool state_measurement_requested = argc == 8;
    if (state_measurement_requested) {
        char* state_limit_end = nullptr;
        const unsigned long long parsed_state_limit =
                std::strtoull(argv[7], &state_limit_end, 10);
        if (state_limit_end == argv[7] || *state_limit_end != '\0' ||
            parsed_state_limit > std::numeric_limits<size_t>::max()) {
            std::fprintf(stderr, "PROBE_ERROR=maximum state bytes must fit size_t\n");
            return 64;
        }
        maximum_state_bytes = static_cast<size_t>(parsed_state_limit);
    }
    std::printf("PROBE_RENDERER=%s\n", g_renderer.c_str());

    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
        std::fprintf(stderr, "PROBE_ERROR=dlopen failed: %s\n", dlerror());
        return 65;
    }

    CoreApi core;
    if (!core.load(library)) {
        dlclose(library);
        return 66;
    }

    const unsigned api_version = core.api_version();
    std::printf("PROBE_API_VERSION=%u\n", api_version);
    if (api_version != RETRO_API_VERSION) {
        std::fprintf(stderr, "PROBE_ERROR=unsupported libretro API\n");
        dlclose(library);
        return 67;
    }

    retro_system_info system_info{};
    core.get_system_info(&system_info);
    std::printf("PROBE_LIBRARY_NAME=%s\n",
                is_nonempty(system_info.library_name) ? system_info.library_name : "");
    std::printf("PROBE_LIBRARY_VERSION=%s\n",
                is_nonempty(system_info.library_version) ? system_info.library_version : "");
    std::printf("PROBE_NEEDS_FULLPATH=%d\n", system_info.need_fullpath ? 1 : 0);
    std::printf("PROBE_EXTENSIONS=%s\n",
                is_nonempty(system_info.valid_extensions) ? system_info.valid_extensions : "");
    if (!is_nonempty(system_info.library_name) ||
        std::strcmp(system_info.library_name, "Azahar") != 0 ||
        !system_info.need_fullpath) {
        std::fprintf(stderr, "PROBE_ERROR=unexpected Azahar system metadata\n");
        dlclose(library);
        return 68;
    }

    core.set_environment(environment_callback);
    core.set_video_refresh(video_callback);
    core.set_audio_sample(audio_sample_callback);
    core.set_audio_sample_batch(audio_batch_callback);
    core.set_input_poll(input_poll_callback);
    core.set_input_state(input_state_callback);

    core.init();
    retro_system_av_info av_info{};
    core.get_system_av_info(&av_info);
    std::printf("PROBE_GEOMETRY=%ux%u\n", av_info.geometry.base_width,
                av_info.geometry.base_height);
    std::printf("PROBE_TIMING=%.3f/%.3f\n", av_info.timing.fps,
                av_info.timing.sample_rate);

    const bool valid_av = av_info.geometry.base_width > 0 &&
                          av_info.geometry.base_height > 0 && av_info.timing.fps > 0.0 &&
                          av_info.timing.sample_rate > 0.0;

    bool content_passed = true;
    bool state_passed = true;
    if (argc >= 6) {
        char* frame_count_end = nullptr;
        const long requested_frames = std::strtol(argv[5], &frame_count_end, 10);
        if (frame_count_end == argv[5] || *frame_count_end != '\0' || requested_frames < 1 ||
            requested_frames > 600) {
            std::fprintf(stderr, "PROBE_ERROR=frame count must be between 1 and 600\n");
            core.deinit();
            dlclose(library);
            return 70;
        }

        retro_game_info game_info{};
        g_content_path = argv[4];
        game_info.path = argv[4];
        const bool loaded = core.load_game(&game_info);
        std::printf("PROBE_CONTENT_LOADED=%d\n", loaded ? 1 : 0);
        bool context_ready = true;
        if (loaded && uses_hardware_renderer()) {
            context_ready = g_hw_render_negotiated;
            if (context_ready && uses_opengles()) {
                context_ready = g_egl_context.create(av_info.geometry.max_width,
                                                     av_info.geometry.max_height);
            } else if (context_ready && uses_vulkan()) {
                context_ready = g_vulkan_context.create();
            }
            if (context_ready) {
                g_hw_render.context_reset();
                std::printf("PROBE_CONTEXT_RESET=1\n");
            }
        }
        if (loaded && context_ready) {
            for (long frame = 0; frame < requested_frames; ++frame) {
                core.run();
                if ((frame + 1) % 60 == 0) {
                    std::printf("PROBE_FRAME_CHECKPOINT=%ld\n", frame + 1);
                }
            }
            if (state_measurement_requested) {
                const auto prepare_started = std::chrono::steady_clock::now();
                const size_t state_size = core.serialize_size();
                const auto prepare_micros = std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now() - prepare_started).count();
                std::printf("PROBE_STATE_SIZE=%zu\n", state_size);
                std::printf("PROBE_STATE_PREPARE_MICROS=%lld\n",
                            static_cast<long long>(prepare_micros));
                std::printf("PROBE_STATE_MAXIMUM_BYTES=%zu\n", maximum_state_bytes);
                const bool within_limit = maximum_state_bytes == 0 ||
                                          state_size <= maximum_state_bytes;
                std::printf("PROBE_STATE_WITHIN_LIMIT=%d\n", within_limit ? 1 : 0);
                state_passed = state_size > 0 && within_limit;

                if (state_passed && maximum_state_bytes > 0) {
                    try {
                        std::vector<uint8_t> state(state_size);
                        const auto serialize_started = std::chrono::steady_clock::now();
                        const bool serialized = core.serialize(state.data(), state.size());
                        const auto serialize_micros =
                                std::chrono::duration_cast<std::chrono::microseconds>(
                                        std::chrono::steady_clock::now() - serialize_started).count();
                        const auto unserialize_started = std::chrono::steady_clock::now();
                        const bool unserialized = serialized &&
                                core.unserialize(state.data(), state.size());
                        const auto unserialize_micros =
                                std::chrono::duration_cast<std::chrono::microseconds>(
                                        std::chrono::steady_clock::now() - unserialize_started).count();
                        std::printf("PROBE_STATE_SERIALIZATION_ATTEMPTED=1\n");
                        std::printf("PROBE_STATE_SERIALIZE_OK=%d\n", serialized ? 1 : 0);
                        std::printf("PROBE_STATE_SERIALIZE_MICROS=%lld\n",
                                    static_cast<long long>(serialize_micros));
                        std::printf("PROBE_STATE_UNSERIALIZE_OK=%d\n", unserialized ? 1 : 0);
                        std::printf("PROBE_STATE_UNSERIALIZE_MICROS=%lld\n",
                                    static_cast<long long>(unserialize_micros));
                        state_passed = serialized && unserialized;
                        if (state_passed) {
                            core.run();
                            std::printf("PROBE_STATE_POST_RESTORE_FRAME=1\n");
                        }
                    } catch (const std::bad_alloc&) {
                        std::fprintf(stderr, "PROBE_ERROR=state allocation failed\n");
                        state_passed = false;
                    }
                } else {
                    std::printf("PROBE_STATE_SERIALIZATION_ATTEMPTED=0\n");
                }
            }
            if (uses_hardware_renderer() && g_hw_render.context_destroy != nullptr) {
                g_hw_render.context_destroy();
                g_hardware_context_destroyed = true;
                std::printf("PROBE_CONTEXT_DESTROY=1\n");
            }
            core.unload_game();
        }
        g_egl_context.destroy();
        g_vulkan_context.destroy();
        std::printf("PROBE_VIDEO_FRAMES=%zu\n", g_video_frames);
        std::printf("PROBE_AUDIO_FRAMES=%zu\n", g_audio_frames);
        if (uses_vulkan()) {
            std::printf("PROBE_VULKAN_IMAGE_UPDATES=%zu\n",
                        g_vulkan_context.image_updates());
        }
        content_passed = loaded && context_ready && g_video_frames > 0 &&
                         state_passed &&
                         (!uses_hardware_renderer() ||
                          (g_hardware_frame_seen && g_hardware_context_destroyed &&
                           !g_swap_failed &&
                           (!uses_vulkan() ||
                            (g_vulkan_context.valid_image_seen() &&
                             !g_vulkan_context.unsupported_submission_seen()))));
    }

    core.deinit();
    if (argc >= 6) {
        std::printf("PROBE_VFS_REQUESTED=%d\n", g_vfs_requested ? 1 : 0);
        std::printf("PROBE_VFS_OPENS=%zu\n", g_vfs_open_calls.load());
        std::printf("PROBE_VFS_READS=%zu\n", g_vfs_read_calls.load());
        std::printf("PROBE_VFS_BYTES_READ=%zu\n", g_vfs_bytes_read.load());
        std::printf("PROBE_VFS_CONTENT_OPENED=%d\n",
                    g_vfs_content_opened.load() ? 1 : 0);
        std::printf("PROBE_VFS_CONTENT_BYTES_READ=%zu\n",
                    g_vfs_content_bytes_read.load());
        std::printf("PROBE_INPUT_POLLS=%zu\n", g_input_poll_calls.load());
        std::printf("PROBE_INPUT_STATE_CALLS=%zu\n", g_input_state_calls.load());
        std::printf("PROBE_SENSOR_REQUESTED=%d\n", g_sensor_requested ? 1 : 0);
        std::printf("PROBE_SENSOR_STATE_CALLS=%zu\n", g_sensor_state_calls.load());
        std::printf("PROBE_SENSOR_INPUT_CALLS=%zu\n", g_sensor_input_calls.load());
        std::printf("PROBE_MICROPHONE_REQUESTED=%d\n", g_microphone_requested ? 1 : 0);
        std::printf("PROBE_MICROPHONE_OPENS=%zu\n", g_microphone_open_calls.load());
        std::printf("PROBE_MICROPHONE_READS=%zu\n", g_microphone_read_calls.load());
        content_passed = content_passed && g_vfs_requested && g_vfs_content_opened.load() &&
                         g_vfs_content_bytes_read.load() > 0 &&
                         g_input_poll_calls.load() > 0 && g_input_state_calls.load() > 0 &&
                         g_sensor_requested && g_sensor_state_calls.load() >= 4;
    }
    dlclose(library);

    if (!valid_av) {
        std::fprintf(stderr, "PROBE_ERROR=invalid AV metadata\n");
        return 69;
    }
    if (!content_passed) {
        std::fprintf(stderr, "PROBE_ERROR=content lifecycle did not produce video\n");
        return 71;
    }

    std::printf("PROBE_RESULT=PASS\n");
    return EXIT_SUCCESS;
}
