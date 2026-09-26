// SPDX-License-Identifier: GPL-3.0-or-later
// Queue, swapchain and libretro render-interface patterns adapted from
// RetroArch gfx/drivers/vulkan.c at
// 81478f2aa2abb942cfacb2109cbc25a4bd3b46ca.
// Copyright (C) 2016-2017 Hans-Kristian Arntzen
// Copyright (C) 2011-2017 Daniel De Matteis
#include "vulkan_render_host.h"

#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <iterator>
#include <limits>
#include <utility>

namespace emuorbit::n3ds {
namespace {

constexpr uint64_t kInfiniteTimeout = std::numeric_limits<uint64_t>::max();

bool fail(std::string& error, const char* message) {
    error = message;
    return false;
}

bool vkSucceeded(VkResult result) {
    return result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR;
}

VkCompositeAlphaFlagBitsKHR chooseCompositeAlpha(VkCompositeAlphaFlagsKHR supported) {
    constexpr std::array<VkCompositeAlphaFlagBitsKHR, 4> candidates = {
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
    };
    for (VkCompositeAlphaFlagBitsKHR candidate : candidates) {
        if ((supported & candidate) != 0) {
            return candidate;
        }
    }
    return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
}

VkSurfaceFormatKHR chooseSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) {
    if (formats.size() == 1 && formats.front().format == VK_FORMAT_UNDEFINED) {
        return {VK_FORMAT_R8G8B8A8_UNORM, formats.front().colorSpace};
    }
    constexpr std::array<VkFormat, 4> preferred = {
            VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_R8G8B8A8_SRGB,
            VK_FORMAT_B8G8R8A8_SRGB,
    };
    for (VkFormat candidate : preferred) {
        const auto match = std::find_if(
                formats.begin(), formats.end(), [candidate](const auto& format) {
                    return format.format == candidate;
                });
        if (match != formats.end()) {
            return *match;
        }
    }
    return formats.front();
}

VkExtent2D chooseExtent(
        const VkSurfaceCapabilitiesKHR& capabilities,
        uint32_t requestedWidth,
        uint32_t requestedHeight) {
    if (capabilities.currentExtent.width != std::numeric_limits<uint32_t>::max()) {
        return capabilities.currentExtent;
    }
    return {
            std::clamp(
                    requestedWidth,
                    capabilities.minImageExtent.width,
                    capabilities.maxImageExtent.width),
            std::clamp(
                    requestedHeight,
                    capabilities.minImageExtent.height,
                    capabilities.maxImageExtent.height),
    };
}

VkSurfaceTransformFlagBitsKHR choosePreTransform(
        const VkSurfaceCapabilitiesKHR& capabilities) {
    // Android may report ROTATE_90/270 after an Activity orientation change. The compositor
    // applies the current display transform when the swapchain uses IDENTITY; declaring the
    // current transform instead means the application promises it already rotated every frame.
    // Our blit keeps core pixels upright, so prefer IDENTITY and let SurfaceFlinger rotate once.
    if ((capabilities.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0) {
        return VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    }
    return capabilities.currentTransform;
}

}  // namespace

std::unique_ptr<VulkanRenderHost> VulkanRenderHost::create(
        ANativeWindow* window,
        uint32_t requestedWidth,
        uint32_t requestedHeight,
        const VulkanNegotiationInterface* negotiation,
        std::string& error) {
    if (window == nullptr || requestedWidth == 0 || requestedHeight == 0) {
        error = "A valid Android surface and non-zero extent are required";
        return nullptr;
    }
    if (negotiation != nullptr
            && (negotiation->interfaceType != kVulkanNegotiationInterface
                || negotiation->interfaceVersion < 1
                || negotiation->getApplicationInfo == nullptr
                || negotiation->createDevice == nullptr)) {
        error = "The core Vulkan negotiation interface is unsupported";
        return nullptr;
    }

    auto host = std::unique_ptr<VulkanRenderHost>(new VulkanRenderHost());
    host->ownerThread_ = std::this_thread::get_id();
    host->window_ = window;
    ANativeWindow_acquire(window);
    host->negotiation_ = negotiation;
    if (!host->initialize(requestedWidth, requestedHeight, error)) {
        return nullptr;
    }
    return host;
}

VulkanRenderHost::~VulkanRenderHost() {
    destroy();
}

bool VulkanRenderHost::initialize(
        uint32_t requestedWidth,
        uint32_t requestedHeight,
        std::string& error) {
    loader_ = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (loader_ == nullptr) {
        return fail(error, "The Android Vulkan loader is unavailable");
    }
    getInstanceProcAddress_ = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            dlsym(loader_, "vkGetInstanceProcAddr"));
    if (getInstanceProcAddress_ == nullptr) {
        return fail(error, "vkGetInstanceProcAddr is unavailable");
    }
    if (!createInstance(error)
            || !createSurface(error)
            || !selectPhysicalDevice(error)
            || !createDevice(error)
            || !createSwapchain(requestedWidth, requestedHeight, error)
            || !createFrameResources(error)) {
        return false;
    }

    interface_.interfaceType = kVulkanRenderInterface;
    interface_.interfaceVersion = kVulkanRenderInterfaceVersion;
    interface_.handle = this;
    interface_.instance = instance_;
    interface_.gpu = gpu_;
    interface_.device = device_;
    interface_.getDeviceProcAddress = getDeviceProcAddress_;
    interface_.getInstanceProcAddress = getInstanceProcAddress_;
    interface_.queue = queue_;
    interface_.queueIndex = queueFamilyIndex_;
    interface_.setImage = setImage;
    interface_.getSyncIndex = getSyncIndex;
    interface_.getSyncIndexMask = getSyncIndexMask;
    interface_.setCommandBuffers = setCommandBuffers;
    interface_.waitSyncIndex = waitSyncIndex;
    interface_.lockQueue = lockQueue;
    interface_.unlockQueue = unlockQueue;
    interface_.setSignalSemaphore = setSignalSemaphore;

    VkPhysicalDeviceProperties properties{};
    const auto getProperties = loadInstance<PFN_vkGetPhysicalDeviceProperties>(
            "vkGetPhysicalDeviceProperties", error);
    if (getProperties == nullptr) {
        return false;
    }
    getProperties(gpu_, &properties);
    report_.deviceName = properties.deviceName;
    report_.apiVersion = properties.apiVersion;
    report_.swapchainImageCount = static_cast<uint32_t>(swapchainImages_.size());
    return true;
}

bool VulkanRenderHost::createInstance(std::string& error) {
    const auto createInstanceFunction = loadInstance<PFN_vkCreateInstance>(
            "vkCreateInstance", error);
    if (createInstanceFunction == nullptr) {
        return false;
    }

    VkApplicationInfo fallback{};
    fallback.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    fallback.pApplicationName = "EmuOrbit Nintendo 3DS";
    fallback.applicationVersion = 1;
    fallback.pEngineName = "EmuOrbit";
    fallback.engineVersion = 1;
    fallback.apiVersion = VK_API_VERSION_1_1;
    const VkApplicationInfo* applicationInfo = &fallback;
    if (negotiation_ != nullptr) {
        applicationInfo = negotiation_->getApplicationInfo();
        if (applicationInfo == nullptr) {
            return fail(error, "The core returned no Vulkan application info");
        }
        if (negotiation_->interfaceVersion >= 2
                && negotiation_->createInstance != nullptr) {
            return fail(error, "Vulkan negotiation v2 instance wrapping is not enabled yet");
        }
    }

    const char* extensions[] = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = applicationInfo;
    createInfo.enabledExtensionCount = std::size(extensions);
    createInfo.ppEnabledExtensionNames = extensions;
    if (createInstanceFunction(&createInfo, nullptr, &instance_) != VK_SUCCESS
            || instance_ == VK_NULL_HANDLE) {
        return fail(error, "Vulkan instance creation failed");
    }
    getDeviceProcAddress_ = loadInstance<PFN_vkGetDeviceProcAddr>(
            "vkGetDeviceProcAddr", error);
    return getDeviceProcAddress_ != nullptr;
}

bool VulkanRenderHost::createSurface(std::string& error) {
    const auto createAndroidSurface = loadInstance<PFN_vkCreateAndroidSurfaceKHR>(
            "vkCreateAndroidSurfaceKHR", error);
    if (createAndroidSurface == nullptr) {
        return false;
    }
    VkAndroidSurfaceCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    createInfo.window = window_;
    if (createAndroidSurface(instance_, &createInfo, nullptr, &surface_) != VK_SUCCESS
            || surface_ == VK_NULL_HANDLE) {
        return fail(error, "Android Vulkan surface creation failed");
    }
    return true;
}

bool VulkanRenderHost::selectPhysicalDevice(std::string& error) {
    const auto enumerateDevices = loadInstance<PFN_vkEnumeratePhysicalDevices>(
            "vkEnumeratePhysicalDevices", error);
    const auto getQueueFamilies = loadInstance<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
            "vkGetPhysicalDeviceQueueFamilyProperties", error);
    const auto getSurfaceSupport = loadInstance<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
            "vkGetPhysicalDeviceSurfaceSupportKHR", error);
    if (enumerateDevices == nullptr || getQueueFamilies == nullptr
            || getSurfaceSupport == nullptr) {
        return false;
    }

    uint32_t deviceCount = 0;
    if (enumerateDevices(instance_, &deviceCount, nullptr) != VK_SUCCESS
            || deviceCount == 0) {
        return fail(error, "No Vulkan physical device is available");
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    if (enumerateDevices(instance_, &deviceCount, devices.data()) != VK_SUCCESS) {
        return fail(error, "Vulkan physical-device enumeration failed");
    }
    for (VkPhysicalDevice device : devices) {
        uint32_t familyCount = 0;
        getQueueFamilies(device, &familyCount, nullptr);
        if (familyCount == 0) {
            continue;
        }
        std::vector<VkQueueFamilyProperties> families(familyCount);
        getQueueFamilies(device, &familyCount, families.data());
        for (uint32_t index = 0; index < familyCount; ++index) {
            VkBool32 presentationSupported = VK_FALSE;
            if ((families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0
                    && getSurfaceSupport(device, index, surface_, &presentationSupported)
                            == VK_SUCCESS
                    && presentationSupported == VK_TRUE) {
                gpu_ = device;
                queueFamilyIndex_ = index;
                presentationQueueFamilyIndex_ = index;
                return true;
            }
        }
    }
    return fail(error, "No Vulkan queue can render and present to the Android surface");
}

bool VulkanRenderHost::createDevice(std::string& error) {
    if (negotiation_ != nullptr) {
        VulkanContext context{};
        const char* requiredExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        if (!negotiation_->createDevice(
                    &context,
                    instance_,
                    gpu_,
                    surface_,
                    getInstanceProcAddress_,
                    requiredExtensions,
                    std::size(requiredExtensions),
                    nullptr,
                    0,
                    nullptr)
                || context.gpu == VK_NULL_HANDLE
                || context.device == VK_NULL_HANDLE
                || context.queue == VK_NULL_HANDLE
                || context.presentationQueue == VK_NULL_HANDLE) {
            return fail(error, "The core rejected Vulkan device negotiation");
        }
        gpu_ = context.gpu;
        device_ = context.device;
        queue_ = context.queue;
        queueFamilyIndex_ = context.queueFamilyIndex;
        presentationQueue_ = context.presentationQueue;
        presentationQueueFamilyIndex_ = context.presentationQueueFamilyIndex;
        negotiatedDeviceCreated_ = true;
    } else {
        const float priority = 1.0F;
        VkDeviceQueueCreateInfo queueInfo{};
        queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = queueFamilyIndex_;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        const char* extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        VkDeviceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        createInfo.queueCreateInfoCount = 1;
        createInfo.pQueueCreateInfos = &queueInfo;
        createInfo.enabledExtensionCount = std::size(extensions);
        createInfo.ppEnabledExtensionNames = extensions;
        const auto createDeviceFunction = loadInstance<PFN_vkCreateDevice>(
                "vkCreateDevice", error);
        if (createDeviceFunction == nullptr
                || createDeviceFunction(gpu_, &createInfo, nullptr, &device_) != VK_SUCCESS
                || device_ == VK_NULL_HANDLE) {
            return fail(error, "Vulkan logical-device creation failed");
        }
        const auto getDeviceQueue = loadDevice<PFN_vkGetDeviceQueue>(
                "vkGetDeviceQueue", error);
        if (getDeviceQueue == nullptr) {
            return false;
        }
        getDeviceQueue(device_, queueFamilyIndex_, 0, &queue_);
        presentationQueue_ = queue_;
    }

    if (queueFamilyIndex_ != presentationQueueFamilyIndex_) {
        return fail(error, "Separate Vulkan graphics and presentation queues are not supported");
    }
    const auto getSurfaceSupport = loadInstance<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
            "vkGetPhysicalDeviceSurfaceSupportKHR", error);
    VkBool32 supported = VK_FALSE;
    if (getSurfaceSupport == nullptr
            || getSurfaceSupport(
                       gpu_, presentationQueueFamilyIndex_, surface_, &supported)
                    != VK_SUCCESS
            || supported != VK_TRUE) {
        return fail(error, "The negotiated Vulkan queue cannot present to the surface");
    }
    return true;
}

bool VulkanRenderHost::createSwapchain(
        uint32_t requestedWidth,
        uint32_t requestedHeight,
        std::string& error) {
    const auto getCapabilities = loadInstance<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
            "vkGetPhysicalDeviceSurfaceCapabilitiesKHR", error);
    const auto getFormats = loadInstance<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
            "vkGetPhysicalDeviceSurfaceFormatsKHR", error);
    const auto createSwapchainFunction = loadDevice<PFN_vkCreateSwapchainKHR>(
            "vkCreateSwapchainKHR", error);
    const auto getSwapchainImages = loadDevice<PFN_vkGetSwapchainImagesKHR>(
            "vkGetSwapchainImagesKHR", error);
    if (getCapabilities == nullptr || getFormats == nullptr
            || createSwapchainFunction == nullptr || getSwapchainImages == nullptr) {
        return false;
    }

    VkSurfaceCapabilitiesKHR capabilities{};
    if (getCapabilities(gpu_, surface_, &capabilities) != VK_SUCCESS) {
        return fail(error, "Vulkan surface capabilities are unavailable");
    }
    if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
        return fail(error, "The Android surface cannot receive Vulkan transfer frames");
    }
    uint32_t formatCount = 0;
    if (getFormats(gpu_, surface_, &formatCount, nullptr) != VK_SUCCESS
            || formatCount == 0) {
        return fail(error, "The Android surface exposes no Vulkan format");
    }
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    if (getFormats(gpu_, surface_, &formatCount, formats.data()) != VK_SUCCESS) {
        return fail(error, "Vulkan surface-format enumeration failed");
    }
    const VkSurfaceFormatKHR format = chooseSurfaceFormat(formats);
    extent_ = chooseExtent(capabilities, requestedWidth, requestedHeight);

    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0) {
        imageCount = std::min(imageCount, capabilities.maxImageCount);
    }
    imageCount = std::min(imageCount, 31U);
    if (imageCount < capabilities.minImageCount) {
        return fail(error, "The Vulkan swapchain requires more than 31 images");
    }

    VkSwapchainCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    createInfo.surface = surface_;
    createInfo.minImageCount = imageCount;
    createInfo.imageFormat = format.format;
    createInfo.imageColorSpace = format.colorSpace;
    createInfo.imageExtent = extent_;
    createInfo.imageArrayLayers = 1;
    createInfo.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    createInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    createInfo.preTransform = choosePreTransform(capabilities);
    createInfo.compositeAlpha = chooseCompositeAlpha(capabilities.supportedCompositeAlpha);
    createInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    createInfo.clipped = VK_TRUE;
    if (createSwapchainFunction(device_, &createInfo, nullptr, &swapchain_) != VK_SUCCESS
            || swapchain_ == VK_NULL_HANDLE) {
        return fail(error, "Vulkan swapchain creation failed");
    }

    uint32_t observedCount = 0;
    if (getSwapchainImages(device_, swapchain_, &observedCount, nullptr) != VK_SUCCESS
            || observedCount == 0 || observedCount > 31) {
        return fail(error, "Vulkan swapchain returned an invalid image count");
    }
    swapchainImages_.resize(observedCount);
    if (getSwapchainImages(
                device_, swapchain_, &observedCount, swapchainImages_.data()) != VK_SUCCESS) {
        return fail(error, "Vulkan swapchain-image enumeration failed");
    }
    swapchainImageInitialized_.assign(observedCount, false);
    report_.surfaceFormat = static_cast<uint32_t>(format.format);
    return true;
}

bool VulkanRenderHost::createFrameResources(std::string& error) {
    const auto createCommandPool = loadDevice<PFN_vkCreateCommandPool>(
            "vkCreateCommandPool", error);
    const auto allocateCommandBuffers = loadDevice<PFN_vkAllocateCommandBuffers>(
            "vkAllocateCommandBuffers", error);
    const auto createSemaphore = loadDevice<PFN_vkCreateSemaphore>(
            "vkCreateSemaphore", error);
    const auto createFence = loadDevice<PFN_vkCreateFence>("vkCreateFence", error);
    if (createCommandPool == nullptr || allocateCommandBuffers == nullptr
            || createSemaphore == nullptr || createFence == nullptr) {
        return false;
    }

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamilyIndex_;
    if (createCommandPool(device_, &poolInfo, nullptr, &commandPool_) != VK_SUCCESS) {
        return fail(error, "Vulkan command-pool creation failed");
    }
    VkCommandBufferAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocation.commandPool = commandPool_;
    allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocation.commandBufferCount = 1;
    if (allocateCommandBuffers(device_, &allocation, &commandBuffer_) != VK_SUCCESS) {
        return fail(error, "Vulkan command-buffer allocation failed");
    }
    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    if (createSemaphore(device_, &semaphoreInfo, nullptr, &imageAvailable_) != VK_SUCCESS
            || createSemaphore(device_, &semaphoreInfo, nullptr, &renderFinished_)
                    != VK_SUCCESS) {
        return fail(error, "Vulkan semaphore creation failed");
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (createFence(device_, &fenceInfo, nullptr, &frameFence_) != VK_SUCCESS) {
        return fail(error, "Vulkan fence creation failed");
    }
    return true;
}

bool VulkanRenderHost::presentDiagnosticFrame(uint32_t rgba, std::string& error) {
    if (!isOwnerThread()) {
        return fail(error, "Vulkan surface operations must stay on the owner thread");
    }
    const auto waitForFences = loadDevice<PFN_vkWaitForFences>("vkWaitForFences", error);
    const auto resetFences = loadDevice<PFN_vkResetFences>("vkResetFences", error);
    const auto acquireNextImage = loadDevice<PFN_vkAcquireNextImageKHR>(
            "vkAcquireNextImageKHR", error);
    const auto resetCommandPool = loadDevice<PFN_vkResetCommandPool>(
            "vkResetCommandPool", error);
    const auto beginCommandBuffer = loadDevice<PFN_vkBeginCommandBuffer>(
            "vkBeginCommandBuffer", error);
    const auto commandBarrier = loadDevice<PFN_vkCmdPipelineBarrier>(
            "vkCmdPipelineBarrier", error);
    const auto clearColorImage = loadDevice<PFN_vkCmdClearColorImage>(
            "vkCmdClearColorImage", error);
    const auto endCommandBuffer = loadDevice<PFN_vkEndCommandBuffer>(
            "vkEndCommandBuffer", error);
    const auto queueSubmit = loadDevice<PFN_vkQueueSubmit>("vkQueueSubmit", error);
    const auto queuePresent = loadDevice<PFN_vkQueuePresentKHR>("vkQueuePresentKHR", error);
    if (waitForFences == nullptr || resetFences == nullptr || acquireNextImage == nullptr
            || resetCommandPool == nullptr || beginCommandBuffer == nullptr
            || commandBarrier == nullptr || clearColorImage == nullptr
            || endCommandBuffer == nullptr || queueSubmit == nullptr
            || queuePresent == nullptr) {
        return false;
    }
    if (waitForFences(device_, 1, &frameFence_, VK_TRUE, kInfiniteTimeout) != VK_SUCCESS) {
        return fail(error, "Vulkan frame-fence synchronization failed");
    }
    const VkResult acquired = acquireNextImage(
            device_, swapchain_, kInfiniteTimeout, imageAvailable_, VK_NULL_HANDLE, &syncIndex_);
    if (!vkSucceeded(acquired) || syncIndex_ >= swapchainImages_.size()) {
        return fail(error, "Vulkan swapchain image acquisition failed");
    }
    if (resetFences(device_, 1, &frameFence_) != VK_SUCCESS) {
        return fail(error, "Vulkan frame-fence reset failed");
    }
    if (resetCommandPool(device_, commandPool_, 0) != VK_SUCCESS) {
        return fail(error, "Vulkan command-pool reset failed");
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (beginCommandBuffer(commandBuffer_, &beginInfo) != VK_SUCCESS) {
        return fail(error, "Vulkan command-buffer recording failed");
    }

    VkImageMemoryBarrier toTransfer{};
    toTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransfer.srcAccessMask = 0;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransfer.oldLayout = swapchainImageInitialized_[syncIndex_]
            ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
            : VK_IMAGE_LAYOUT_UNDEFINED;
    toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.image = swapchainImages_[syncIndex_];
    toTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    toTransfer.subresourceRange.levelCount = 1;
    toTransfer.subresourceRange.layerCount = 1;
    commandBarrier(
            commandBuffer_,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &toTransfer);

    VkClearColorValue color{};
    color.float32[0] = static_cast<float>((rgba >> 24U) & 0xffU) / 255.0F;
    color.float32[1] = static_cast<float>((rgba >> 16U) & 0xffU) / 255.0F;
    color.float32[2] = static_cast<float>((rgba >> 8U) & 0xffU) / 255.0F;
    color.float32[3] = static_cast<float>(rgba & 0xffU) / 255.0F;
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;
    clearColorImage(
            commandBuffer_,
            swapchainImages_[syncIndex_],
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            &color,
            1,
            &range);

    VkImageMemoryBarrier toPresent = toTransfer;
    toPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toPresent.dstAccessMask = 0;
    toPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    commandBarrier(
            commandBuffer_,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &toPresent);
    if (endCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        return fail(error, "Vulkan command-buffer finalization failed");
    }

    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &imageAvailable_;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commandBuffer_;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &renderFinished_;
    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderFinished_;
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain_;
    present.pImageIndices = &syncIndex_;
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        if (queueSubmit(queue_, 1, &submit, frameFence_) != VK_SUCCESS) {
            return fail(error, "Vulkan diagnostic frame submission failed");
        }
        if (!vkSucceeded(queuePresent(presentationQueue_, &present))) {
            return fail(error, "Vulkan diagnostic frame presentation failed");
        }
    }
    swapchainImageInitialized_[syncIndex_] = true;
    report_.presentedFrames++;
    return true;
}

bool VulkanRenderHost::beginCoreFrame(std::string& error) {
    if (!isOwnerThread()) {
        return fail(error, "Vulkan surface operations must stay on the owner thread");
    }
    if (frameAcquired_) {
        return fail(error, "A Vulkan core frame is already active");
    }
    const auto waitForFences = loadDevice<PFN_vkWaitForFences>("vkWaitForFences", error);
    const auto acquireNextImage = loadDevice<PFN_vkAcquireNextImageKHR>(
            "vkAcquireNextImageKHR", error);
    if (waitForFences == nullptr || acquireNextImage == nullptr) {
        return false;
    }
    if (waitForFences(device_, 1, &frameFence_, VK_TRUE, kInfiniteTimeout) != VK_SUCCESS) {
        return fail(error, "Vulkan frame-fence synchronization failed");
    }
    const VkResult acquired = acquireNextImage(
            device_, swapchain_, kInfiniteTimeout, imageAvailable_, VK_NULL_HANDLE, &syncIndex_);
    if (!vkSucceeded(acquired) || syncIndex_ >= swapchainImages_.size()) {
        return fail(error, "Vulkan swapchain image acquisition failed");
    }
    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        coreImage_ = nullptr;
        coreWaitSemaphores_.clear();
        coreCommandBuffers_.clear();
        coreSourceQueueFamily_ = VK_QUEUE_FAMILY_IGNORED;
        coreSignalSemaphore_ = VK_NULL_HANDLE;
    }
    frameAcquired_ = true;
    return true;
}

bool VulkanRenderHost::presentCoreFrame(
        uint32_t sourceWidth,
        uint32_t sourceHeight,
        std::string& error) {
    if (!isOwnerThread()) {
        return fail(error, "Vulkan surface operations must stay on the owner thread");
    }
    if (!frameAcquired_) {
        return fail(error, "No Vulkan swapchain image was acquired for the core frame");
    }
    if (sourceWidth == 0 || sourceHeight == 0) {
        return fail(error, "The core returned an empty Vulkan frame");
    }

    const VulkanImage* source = nullptr;
    std::vector<VkSemaphore> coreWaits;
    std::vector<VkCommandBuffer> coreCommands;
    uint32_t sourceQueueFamily = VK_QUEUE_FAMILY_IGNORED;
    VkSemaphore coreSignal = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        source = coreImage_;
        coreWaits = coreWaitSemaphores_;
        coreCommands = coreCommandBuffers_;
        sourceQueueFamily = coreSourceQueueFamily_;
        coreSignal = coreSignalSemaphore_;
        coreWaitSemaphores_.clear();
        coreCommandBuffers_.clear();
        coreSignalSemaphore_ = VK_NULL_HANDLE;
    }
    if (source == nullptr || source->imageView == VK_NULL_HANDLE
            || source->createInfo.image == VK_NULL_HANDLE
            || source->createInfo.viewType != VK_IMAGE_VIEW_TYPE_2D) {
        return fail(error, "The core did not publish a valid Vulkan image");
    }
    if (sourceQueueFamily != VK_QUEUE_FAMILY_IGNORED
            && sourceQueueFamily != queueFamilyIndex_) {
        return fail(error, "Cross-family Vulkan core images are not supported yet");
    }

    const auto resetFences = loadDevice<PFN_vkResetFences>("vkResetFences", error);
    const auto resetCommandPool = loadDevice<PFN_vkResetCommandPool>(
            "vkResetCommandPool", error);
    const auto beginCommandBuffer = loadDevice<PFN_vkBeginCommandBuffer>(
            "vkBeginCommandBuffer", error);
    const auto commandBarrier = loadDevice<PFN_vkCmdPipelineBarrier>(
            "vkCmdPipelineBarrier", error);
    const auto clearColorImage = loadDevice<PFN_vkCmdClearColorImage>(
            "vkCmdClearColorImage", error);
    const auto blitImage = loadDevice<PFN_vkCmdBlitImage>("vkCmdBlitImage", error);
    const auto endCommandBuffer = loadDevice<PFN_vkEndCommandBuffer>(
            "vkEndCommandBuffer", error);
    const auto queueSubmit = loadDevice<PFN_vkQueueSubmit>("vkQueueSubmit", error);
    const auto queuePresent = loadDevice<PFN_vkQueuePresentKHR>("vkQueuePresentKHR", error);
    if (resetFences == nullptr || resetCommandPool == nullptr
            || beginCommandBuffer == nullptr || commandBarrier == nullptr
            || clearColorImage == nullptr || blitImage == nullptr
            || endCommandBuffer == nullptr || queueSubmit == nullptr
            || queuePresent == nullptr) {
        return false;
    }
    if (resetFences(device_, 1, &frameFence_) != VK_SUCCESS
            || resetCommandPool(device_, commandPool_, 0) != VK_SUCCESS) {
        return fail(error, "Vulkan core-frame resources could not be reset");
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (beginCommandBuffer(commandBuffer_, &beginInfo) != VK_SUCCESS) {
        return fail(error, "Vulkan core-frame recording failed");
    }

    VkImageMemoryBarrier destinationToTransfer{};
    destinationToTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    destinationToTransfer.srcAccessMask = 0;
    destinationToTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    destinationToTransfer.oldLayout = swapchainImageInitialized_[syncIndex_]
            ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
            : VK_IMAGE_LAYOUT_UNDEFINED;
    destinationToTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    destinationToTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    destinationToTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    destinationToTransfer.image = swapchainImages_[syncIndex_];
    destinationToTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    destinationToTransfer.subresourceRange.levelCount = 1;
    destinationToTransfer.subresourceRange.layerCount = 1;
    commandBarrier(
            commandBuffer_,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &destinationToTransfer);

    // Match EmuOrbit's #080B18 navy so any aspect-ratio remainder reads as an
    // intentional controls backdrop instead of a black bar or stretched game pixels.
    VkClearColorValue backdrop{};
    backdrop.float32[0] = 8.0F / 255.0F;
    backdrop.float32[1] = 11.0F / 255.0F;
    backdrop.float32[2] = 24.0F / 255.0F;
    backdrop.float32[3] = 1.0F;
    clearColorImage(
            commandBuffer_,
            swapchainImages_[syncIndex_],
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            &backdrop,
            1,
            &destinationToTransfer.subresourceRange);

    const bool sourceUsesGeneralLayout = source->imageLayout == VK_IMAGE_LAYOUT_GENERAL;
    VkImageMemoryBarrier sourceToTransfer{};
    sourceToTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    sourceToTransfer.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
            | VK_ACCESS_SHADER_READ_BIT;
    sourceToTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    sourceToTransfer.oldLayout = source->imageLayout;
    sourceToTransfer.newLayout = sourceUsesGeneralLayout
            ? VK_IMAGE_LAYOUT_GENERAL
            : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    sourceToTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceToTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceToTransfer.image = source->createInfo.image;
    sourceToTransfer.subresourceRange = source->createInfo.subresourceRange;
    commandBarrier(
            commandBuffer_,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &sourceToTransfer);

    uint32_t destinationWidth = extent_.width;
    uint32_t destinationHeight = extent_.height;
    int32_t destinationX = 0;
    int32_t destinationY = 0;
    const uint64_t fitWidth = static_cast<uint64_t>(extent_.height) * sourceWidth
            / sourceHeight;
    if (fitWidth <= extent_.width) {
        destinationWidth = static_cast<uint32_t>(fitWidth);
        destinationX = static_cast<int32_t>((extent_.width - destinationWidth) / 2U);
    } else {
        destinationHeight = static_cast<uint32_t>(
                static_cast<uint64_t>(extent_.width) * sourceHeight / sourceWidth);
        // Center the complete dual-screen frame in both orientations. The remaining space is
        // balanced above and below in landscape, with no crop, stretch or duplicated pixels.
        destinationY = static_cast<int32_t>((extent_.height - destinationHeight) / 2U);
    }

    VkImageBlit frameRegion{};
    frameRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    frameRegion.srcSubresource.mipLevel = source->createInfo.subresourceRange.baseMipLevel;
    frameRegion.srcSubresource.baseArrayLayer = source->createInfo.subresourceRange.baseArrayLayer;
    frameRegion.srcSubresource.layerCount = 1;
    frameRegion.srcOffsets[1] = {
            static_cast<int32_t>(sourceWidth),
            static_cast<int32_t>(sourceHeight),
            1,
    };
    frameRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    frameRegion.dstSubresource.layerCount = 1;
    frameRegion.dstOffsets[0] = {destinationX, destinationY, 0};
    frameRegion.dstOffsets[1] = {
            destinationX + static_cast<int32_t>(destinationWidth),
            destinationY + static_cast<int32_t>(destinationHeight),
            1,
    };

    blitImage(
            commandBuffer_,
            source->createInfo.image,
            sourceToTransfer.newLayout,
            swapchainImages_[syncIndex_],
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &frameRegion,
            VK_FILTER_LINEAR);

    VkImageMemoryBarrier sourceRestore = sourceToTransfer;
    sourceRestore.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    sourceRestore.dstAccessMask = VK_ACCESS_SHADER_READ_BIT
            | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    sourceRestore.oldLayout = sourceToTransfer.newLayout;
    sourceRestore.newLayout = source->imageLayout;
    VkImageMemoryBarrier destinationToPresent = destinationToTransfer;
    destinationToPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    destinationToPresent.dstAccessMask = 0;
    destinationToPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    destinationToPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    const std::array<VkImageMemoryBarrier, 2> finalBarriers = {
            sourceRestore,
            destinationToPresent,
    };
    commandBarrier(
            commandBuffer_,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            static_cast<uint32_t>(finalBarriers.size()),
            finalBarriers.data());
    if (endCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        return fail(error, "Vulkan core-frame finalization failed");
    }

    std::vector<VkSemaphore> waitSemaphores;
    std::vector<VkPipelineStageFlags> waitStages;
    if (coreCommands.empty()) {
        waitSemaphores = std::move(coreWaits);
        waitStages.assign(waitSemaphores.size(), VK_PIPELINE_STAGE_TRANSFER_BIT);
    }
    waitSemaphores.push_back(imageAvailable_);
    waitStages.push_back(VK_PIPELINE_STAGE_TRANSFER_BIT);
    coreCommands.push_back(commandBuffer_);
    std::array<VkSemaphore, 2> signalSemaphores = {renderFinished_, coreSignal};
    const uint32_t signalCount = coreSignal != VK_NULL_HANDLE
            && coreSignal != renderFinished_ ? 2U : 1U;

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = static_cast<uint32_t>(waitSemaphores.size());
    submit.pWaitSemaphores = waitSemaphores.data();
    submit.pWaitDstStageMask = waitStages.data();
    submit.commandBufferCount = static_cast<uint32_t>(coreCommands.size());
    submit.pCommandBuffers = coreCommands.data();
    submit.signalSemaphoreCount = signalCount;
    submit.pSignalSemaphores = signalSemaphores.data();
    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderFinished_;
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain_;
    present.pImageIndices = &syncIndex_;
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        if (queueSubmit(queue_, 1, &submit, frameFence_) != VK_SUCCESS) {
            return fail(error, "Vulkan core-frame submission failed");
        }
        if (!vkSucceeded(queuePresent(presentationQueue_, &present))) {
            return fail(error, "Vulkan core-frame presentation failed");
        }
    }
    swapchainImageInitialized_[syncIndex_] = true;
    frameAcquired_ = false;
    report_.presentedFrames++;
    return true;
}

const VulkanRenderInterface* VulkanRenderHost::interface() const {
    return interface_.handle == this ? &interface_ : nullptr;
}

const VulkanRenderReport& VulkanRenderHost::report() const {
    return report_;
}

bool VulkanRenderHost::isOwnerThread() const {
    return ownerThread_ == std::this_thread::get_id();
}

void VulkanRenderHost::destroy() {
    interface_.handle = nullptr;
    std::string ignored;
    if (device_ != VK_NULL_HANDLE) {
        const auto deviceWaitIdle = loadDevice<PFN_vkDeviceWaitIdle>(
                "vkDeviceWaitIdle", ignored);
        if (deviceWaitIdle != nullptr) {
            deviceWaitIdle(device_);
        }
        const auto destroyFence = loadDevice<PFN_vkDestroyFence>("vkDestroyFence", ignored);
        const auto destroySemaphore = loadDevice<PFN_vkDestroySemaphore>(
                "vkDestroySemaphore", ignored);
        const auto destroyCommandPool = loadDevice<PFN_vkDestroyCommandPool>(
                "vkDestroyCommandPool", ignored);
        const auto destroySwapchain = loadDevice<PFN_vkDestroySwapchainKHR>(
                "vkDestroySwapchainKHR", ignored);
        if (destroyFence != nullptr && frameFence_ != VK_NULL_HANDLE) {
            destroyFence(device_, frameFence_, nullptr);
        }
        if (destroySemaphore != nullptr && renderFinished_ != VK_NULL_HANDLE) {
            destroySemaphore(device_, renderFinished_, nullptr);
        }
        if (destroySemaphore != nullptr && imageAvailable_ != VK_NULL_HANDLE) {
            destroySemaphore(device_, imageAvailable_, nullptr);
        }
        if (destroyCommandPool != nullptr && commandPool_ != VK_NULL_HANDLE) {
            destroyCommandPool(device_, commandPool_, nullptr);
        }
        if (destroySwapchain != nullptr && swapchain_ != VK_NULL_HANDLE) {
            destroySwapchain(device_, swapchain_, nullptr);
        }
        if (negotiatedDeviceCreated_ && negotiation_ != nullptr
                && negotiation_->destroyDevice != nullptr) {
            negotiation_->destroyDevice();
        }
        const auto destroyDeviceFunction = loadInstance<PFN_vkDestroyDevice>(
                "vkDestroyDevice", ignored);
        if (destroyDeviceFunction != nullptr) {
            destroyDeviceFunction(device_, nullptr);
        }
    }
    frameFence_ = VK_NULL_HANDLE;
    renderFinished_ = VK_NULL_HANDLE;
    imageAvailable_ = VK_NULL_HANDLE;
    commandBuffer_ = VK_NULL_HANDLE;
    commandPool_ = VK_NULL_HANDLE;
    swapchain_ = VK_NULL_HANDLE;
    swapchainImages_.clear();
    swapchainImageInitialized_.clear();
    device_ = VK_NULL_HANDLE;
    queue_ = VK_NULL_HANDLE;
    presentationQueue_ = VK_NULL_HANDLE;

    if (instance_ != VK_NULL_HANDLE) {
        const auto destroySurface = loadInstance<PFN_vkDestroySurfaceKHR>(
                "vkDestroySurfaceKHR", ignored);
        if (destroySurface != nullptr && surface_ != VK_NULL_HANDLE) {
            destroySurface(instance_, surface_, nullptr);
        }
        const auto destroyInstanceFunction = loadInstance<PFN_vkDestroyInstance>(
                "vkDestroyInstance", ignored);
        if (destroyInstanceFunction != nullptr) {
            destroyInstanceFunction(instance_, nullptr);
        }
    }
    surface_ = VK_NULL_HANDLE;
    instance_ = VK_NULL_HANDLE;
    if (loader_ != nullptr) {
        dlclose(loader_);
        loader_ = nullptr;
    }
    getInstanceProcAddress_ = nullptr;
    getDeviceProcAddress_ = nullptr;
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

template <typename Function>
Function VulkanRenderHost::loadInstance(const char* name, std::string& error) const {
    if (getInstanceProcAddress_ == nullptr) {
        error = "The Vulkan instance resolver is unavailable";
        return nullptr;
    }
    Function function = reinterpret_cast<Function>(getInstanceProcAddress_(instance_, name));
    if (function == nullptr) {
        error = std::string(name) + " is unavailable";
    }
    return function;
}

template <typename Function>
Function VulkanRenderHost::loadDevice(const char* name, std::string& error) const {
    if (getDeviceProcAddress_ == nullptr || device_ == VK_NULL_HANDLE) {
        error = "The Vulkan device resolver is unavailable";
        return nullptr;
    }
    Function function = reinterpret_cast<Function>(getDeviceProcAddress_(device_, name));
    if (function == nullptr) {
        error = std::string(name) + " is unavailable";
    }
    return function;
}

VulkanRenderHost* VulkanRenderHost::fromHandle(void* handle) {
    return static_cast<VulkanRenderHost*>(handle);
}

void VulkanRenderHost::setImage(
        void* handle,
        const VulkanImage* image,
        uint32_t semaphoreCount,
        const VkSemaphore* semaphores,
        uint32_t sourceQueueFamily) {
    VulkanRenderHost* host = fromHandle(handle);
    if (host == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(host->frameMutex_);
    host->coreImage_ = image;
    host->coreSourceQueueFamily_ = sourceQueueFamily;
    if (semaphoreCount == 0 || semaphores == nullptr) {
        host->coreWaitSemaphores_.clear();
    } else {
        host->coreWaitSemaphores_.assign(semaphores, semaphores + semaphoreCount);
    }
}

uint32_t VulkanRenderHost::getSyncIndex(void* handle) {
    VulkanRenderHost* host = fromHandle(handle);
    return host == nullptr ? 0 : host->syncIndex_;
}

uint32_t VulkanRenderHost::getSyncIndexMask(void* handle) {
    VulkanRenderHost* host = fromHandle(handle);
    if (host == nullptr || host->swapchainImages_.empty()) {
        return 0;
    }
    const uint32_t count = static_cast<uint32_t>(host->swapchainImages_.size());
    return count >= 32 ? std::numeric_limits<uint32_t>::max() : (1U << count) - 1U;
}

void VulkanRenderHost::setCommandBuffers(
        void* handle,
        uint32_t commandBufferCount,
        const VkCommandBuffer* commandBuffers) {
    VulkanRenderHost* host = fromHandle(handle);
    if (host == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(host->frameMutex_);
    if (commandBufferCount == 0 || commandBuffers == nullptr) {
        host->coreCommandBuffers_.clear();
    } else {
        host->coreCommandBuffers_.assign(
                commandBuffers, commandBuffers + commandBufferCount);
    }
}

void VulkanRenderHost::waitSyncIndex(void* handle) {
    VulkanRenderHost* host = fromHandle(handle);
    if (host == nullptr || host->frameFence_ == VK_NULL_HANDLE) {
        return;
    }
    std::string ignored;
    const auto waitForFences = host->loadDevice<PFN_vkWaitForFences>(
            "vkWaitForFences", ignored);
    if (waitForFences != nullptr) {
        waitForFences(host->device_, 1, &host->frameFence_, VK_TRUE, kInfiniteTimeout);
    }
}

void VulkanRenderHost::lockQueue(void* handle) {
    VulkanRenderHost* host = fromHandle(handle);
    if (host != nullptr) {
        host->queueMutex_.lock();
    }
}

void VulkanRenderHost::unlockQueue(void* handle) {
    VulkanRenderHost* host = fromHandle(handle);
    if (host != nullptr) {
        host->queueMutex_.unlock();
    }
}

void VulkanRenderHost::setSignalSemaphore(void* handle, VkSemaphore semaphore) {
    VulkanRenderHost* host = fromHandle(handle);
    if (host != nullptr) {
        std::lock_guard<std::mutex> lock(host->frameMutex_);
        host->coreSignalSemaphore_ = semaphore;
    }
}

}  // namespace emuorbit::n3ds
