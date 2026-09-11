// SPDX-License-Identifier: MIT
// Copyright (C) 2010-2020 The RetroArch team
// Minimal ABI declarations derived from libretro_vulkan.h at RetroArch
// 81478f2aa2abb942cfacb2109cbc25a4bd3b46ca. The full notice remains recorded
// in nintendo3dscore/compliance/THIRD_PARTY_NOTICES.txt.
#pragma once

#include <climits>
#include <cstdint>

#define VK_USE_PLATFORM_ANDROID_KHR
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

namespace emuorbit::n3ds {

constexpr unsigned kVulkanRenderInterfaceVersion = 5;
constexpr unsigned kVulkanNegotiationInterfaceVersion = 2;

enum VulkanRenderInterfaceType : int {
    kVulkanRenderInterface = 0,
    kVulkanRenderInterfaceDummy = INT_MAX,
};

enum VulkanNegotiationInterfaceType : int {
    kVulkanNegotiationInterface = 0,
    kVulkanNegotiationInterfaceDummy = INT_MAX,
};

struct VulkanImage {
    VkImageView imageView;
    VkImageLayout imageLayout;
    VkImageViewCreateInfo createInfo;
};

struct VulkanContext {
    VkPhysicalDevice gpu;
    VkDevice device;
    VkQueue queue;
    uint32_t queueFamilyIndex;
    VkQueue presentationQueue;
    uint32_t presentationQueueFamilyIndex;
};

using VulkanGetApplicationInfo = const VkApplicationInfo* (*)();
using VulkanCreateDevice = bool (*)(
        VulkanContext* context,
        VkInstance instance,
        VkPhysicalDevice gpu,
        VkSurfaceKHR surface,
        PFN_vkGetInstanceProcAddr getInstanceProcAddress,
        const char** requiredDeviceExtensions,
        unsigned requiredDeviceExtensionCount,
        const char** requiredDeviceLayers,
        unsigned requiredDeviceLayerCount,
        const VkPhysicalDeviceFeatures* requiredFeatures);
using VulkanDestroyDevice = void (*)();
using VulkanCreateInstanceWrapper = VkInstance (*)(
        void* opaque,
        const VkInstanceCreateInfo* createInfo);
using VulkanCreateInstance = VkInstance (*)(
        PFN_vkGetInstanceProcAddr getInstanceProcAddress,
        const VkApplicationInfo* applicationInfo,
        VulkanCreateInstanceWrapper createInstanceWrapper,
        void* opaque);
using VulkanCreateDeviceWrapper = VkDevice (*)(
        VkPhysicalDevice gpu,
        void* opaque,
        const VkDeviceCreateInfo* createInfo);
using VulkanCreateDevice2 = bool (*)(
        VulkanContext* context,
        VkInstance instance,
        VkPhysicalDevice gpu,
        VkSurfaceKHR surface,
        PFN_vkGetInstanceProcAddr getInstanceProcAddress,
        VulkanCreateDeviceWrapper createDeviceWrapper,
        void* opaque);

struct VulkanNegotiationInterface {
    VulkanNegotiationInterfaceType interfaceType;
    unsigned interfaceVersion;
    VulkanGetApplicationInfo getApplicationInfo;
    VulkanCreateDevice createDevice;
    VulkanDestroyDevice destroyDevice;
    VulkanCreateInstance createInstance;
    VulkanCreateDevice2 createDevice2;
};

using VulkanSetImage = void (*)(
        void* handle,
        const VulkanImage* image,
        uint32_t semaphoreCount,
        const VkSemaphore* semaphores,
        uint32_t sourceQueueFamily);
using VulkanGetSyncIndex = uint32_t (*)(void* handle);
using VulkanGetSyncIndexMask = uint32_t (*)(void* handle);
using VulkanSetCommandBuffers = void (*)(
        void* handle,
        uint32_t commandBufferCount,
        const VkCommandBuffer* commandBuffers);
using VulkanWaitSyncIndex = void (*)(void* handle);
using VulkanQueueLock = void (*)(void* handle);
using VulkanSetSignalSemaphore = void (*)(void* handle, VkSemaphore semaphore);

struct VulkanRenderInterface {
    VulkanRenderInterfaceType interfaceType;
    unsigned interfaceVersion;
    void* handle;
    VkInstance instance;
    VkPhysicalDevice gpu;
    VkDevice device;
    PFN_vkGetDeviceProcAddr getDeviceProcAddress;
    PFN_vkGetInstanceProcAddr getInstanceProcAddress;
    VkQueue queue;
    unsigned queueIndex;
    VulkanSetImage setImage;
    VulkanGetSyncIndex getSyncIndex;
    VulkanGetSyncIndexMask getSyncIndexMask;
    VulkanSetCommandBuffers setCommandBuffers;
    VulkanWaitSyncIndex waitSyncIndex;
    VulkanQueueLock lockQueue;
    VulkanQueueLock unlockQueue;
    VulkanSetSignalSemaphore setSignalSemaphore;
};

}  // namespace emuorbit::n3ds
