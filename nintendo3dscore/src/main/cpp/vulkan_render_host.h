// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <android/native_window.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "libretro_vulkan_abi.h"

namespace emuorbit::n3ds {

struct VulkanRenderReport {
    std::string deviceName;
    uint32_t apiVersion = 0;
    uint32_t surfaceFormat = 0;
    uint32_t swapchainImageCount = 0;
    uint64_t presentedFrames = 0;
};

/**
 * Owns one Android Surface, Vulkan context and swapchain on a single thread.
 *
 * The host retains its own ANativeWindow reference. The negotiation pointer is
 * core-owned and must outlive this host. Passing null selects the diagnostic
 * default-device path used only by device tests.
 */
class VulkanRenderHost final {
public:
    static std::unique_ptr<VulkanRenderHost> create(
            ANativeWindow* window,
            uint32_t requestedWidth,
            uint32_t requestedHeight,
            const VulkanNegotiationInterface* negotiation,
            std::string& error);

    ~VulkanRenderHost();

    VulkanRenderHost(const VulkanRenderHost&) = delete;
    VulkanRenderHost& operator=(const VulkanRenderHost&) = delete;

    const VulkanRenderInterface* interface() const;
    const VulkanRenderReport& report() const;
    bool beginCoreFrame(std::string& error);
    bool presentCoreFrame(
            uint32_t sourceWidth,
            uint32_t sourceHeight,
            std::string& error);
    bool presentDiagnosticFrame(uint32_t rgba, std::string& error);
    bool isOwnerThread() const;

private:
    VulkanRenderHost() = default;

    bool initialize(
            uint32_t requestedWidth,
            uint32_t requestedHeight,
            std::string& error);
    bool createInstance(std::string& error);
    bool createSurface(std::string& error);
    bool selectPhysicalDevice(std::string& error);
    bool createDevice(std::string& error);
    bool createSwapchain(
            uint32_t requestedWidth,
            uint32_t requestedHeight,
            std::string& error);
    bool createFrameResources(std::string& error);
    void destroy();

    template <typename Function>
    Function loadInstance(const char* name, std::string& error) const;

    template <typename Function>
    Function loadDevice(const char* name, std::string& error) const;

    static VulkanRenderHost* fromHandle(void* handle);
    static void setImage(
            void* handle,
            const VulkanImage* image,
            uint32_t semaphoreCount,
            const VkSemaphore* semaphores,
            uint32_t sourceQueueFamily);
    static uint32_t getSyncIndex(void* handle);
    static uint32_t getSyncIndexMask(void* handle);
    static void setCommandBuffers(
            void* handle,
            uint32_t commandBufferCount,
            const VkCommandBuffer* commandBuffers);
    static void waitSyncIndex(void* handle);
    static void lockQueue(void* handle);
    static void unlockQueue(void* handle);
    static void setSignalSemaphore(void* handle, VkSemaphore semaphore);

    std::thread::id ownerThread_;
    ANativeWindow* window_ = nullptr;
    const VulkanNegotiationInterface* negotiation_ = nullptr;
    bool negotiatedDeviceCreated_ = false;
    void* loader_ = nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddress_ = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddress_ = nullptr;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice gpu_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkQueue presentationQueue_ = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex_ = 0;
    uint32_t presentationQueueFamilyIndex_ = 0;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkExtent2D extent_{};
    std::vector<VkImage> swapchainImages_;
    std::vector<bool> swapchainImageInitialized_;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkSemaphore imageAvailable_ = VK_NULL_HANDLE;
    VkSemaphore renderFinished_ = VK_NULL_HANDLE;
    VkFence frameFence_ = VK_NULL_HANDLE;
    uint32_t syncIndex_ = 0;
    VulkanRenderInterface interface_{};
    VulkanRenderReport report_;
    std::mutex queueMutex_;
    std::mutex frameMutex_;
    const VulkanImage* coreImage_ = nullptr;
    std::vector<VkSemaphore> coreWaitSemaphores_;
    std::vector<VkCommandBuffer> coreCommandBuffers_;
    uint32_t coreSourceQueueFamily_ = VK_QUEUE_FAMILY_IGNORED;
    VkSemaphore coreSignalSemaphore_ = VK_NULL_HANDLE;
    bool frameAcquired_ = false;
};

}  // namespace emuorbit::n3ds
