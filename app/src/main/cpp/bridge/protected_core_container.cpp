#include "protected_core_container.h"

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/dlext.h>
#if defined(EMUORBIT_SANITIZER_DIAGNOSTIC)
#include <android/log.h>
#endif
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

#include "protected_ds_symbols.h"

namespace emuorbit {
namespace {

constexpr size_t kMagicSize = 16;
constexpr size_t kNonceSize = 12;
constexpr size_t kHeaderSize = kMagicSize + kNonceSize + sizeof(uint64_t);
constexpr size_t kTagSize = 32;
constexpr size_t kKeySize = 32;
constexpr size_t kIoBufferSize = 64 * 1024;
constexpr uint64_t kMaximumPayloadSize = 128ULL * 1024ULL * 1024ULL;
constexpr size_t kMaterialSize = EMUORBIT_DS_MATERIAL_SIZE;
constexpr size_t kMaterialLogicalSize = EMUORBIT_DS_MATERIAL_LOGICAL_SIZE;
constexpr size_t kMaterialBankCount = 4;
constexpr size_t kMaterialBankSize = kMaterialSize / kMaterialBankCount;
constexpr size_t kEncryptionPartAOffset = 0;
constexpr size_t kEncryptionPartBOffset = kEncryptionPartAOffset + kKeySize;
constexpr size_t kEncryptionPartCOffset = kEncryptionPartBOffset + kKeySize;
constexpr size_t kAuthenticationPartAOffset = kEncryptionPartCOffset + kKeySize;
constexpr size_t kAuthenticationPartBOffset = kAuthenticationPartAOffset + kKeySize;
constexpr size_t kAuthenticationPartCOffset = kAuthenticationPartBOffset + kKeySize;
constexpr size_t kMagicOffset = kAuthenticationPartCOffset + kKeySize;

static volatile unsigned char kMaterialBank0[] = {
        EMUORBIT_DS_MATERIAL_BANK_0};
static volatile unsigned char kMaterialBank1[] = {
        EMUORBIT_DS_MATERIAL_BANK_1};
static volatile unsigned char kMaterialBank2[] = {
        EMUORBIT_DS_MATERIAL_BANK_2};
static volatile unsigned char kMaterialBank3[] = {
        EMUORBIT_DS_MATERIAL_BANK_3};

static_assert(kMaterialSize == 256);
static_assert(kMaterialLogicalSize == kMagicOffset + kMagicSize);
static_assert((EMUORBIT_DS_MATERIAL_STRIDE & 1) == 1);
static_assert(sizeof(kMaterialBank0) == kMaterialBankSize);
static_assert(sizeof(kMaterialBank1) == kMaterialBankSize);
static_assert(sizeof(kMaterialBank2) == kMaterialBankSize);
static_assert(sizeof(kMaterialBank3) == kMaterialBankSize);

void secureZero(void* memory, size_t size) {
    volatile auto* bytes = static_cast<volatile unsigned char*>(memory);
    while (size-- > 0) {
        *bytes++ = 0;
    }
}

class ScopedWipe final {
public:
    ScopedWipe(void* memory, size_t size) : memory_(memory), size_(size) {}

    ~ScopedWipe() {
        wipe();
    }

    ScopedWipe(const ScopedWipe&) = delete;
    ScopedWipe& operator=(const ScopedWipe&) = delete;

    void wipe() {
        if (memory_ != nullptr) {
            secureZero(memory_, size_);
            memory_ = nullptr;
            size_ = 0;
        }
    }

private:
    void* memory_;
    size_t size_;
};

uint8_t readEncodedMaterial(size_t physicalIndex) {
    const size_t bankOffset = physicalIndex / kMaterialBankCount;
    switch (physicalIndex & (kMaterialBankCount - 1U)) {
        case 0:
            return kMaterialBank0[bankOffset];
        case 1:
            return kMaterialBank1[bankOffset];
        case 2:
            return kMaterialBank2[bankOffset];
        default:
            return kMaterialBank3[bankOffset];
    }
}

uint8_t decodeMaterialByte(size_t logicalIndex) {
    const size_t physicalIndex = (
            EMUORBIT_DS_MATERIAL_OFFSET
            + logicalIndex * EMUORBIT_DS_MATERIAL_STRIDE) & 0xffU;
    const uint32_t mask = (
            EMUORBIT_DS_MATERIAL_MASK_A
            + logicalIndex * EMUORBIT_DS_MATERIAL_MASK_B
            + physicalIndex * EMUORBIT_DS_MATERIAL_MASK_C
            + (logicalIndex ^ physicalIndex) * EMUORBIT_DS_MATERIAL_MASK_D)
            & 0xffU;
    return readEncodedMaterial(physicalIndex) ^ static_cast<uint8_t>(mask);
}

uint32_t rotateRight(uint32_t value, unsigned count) {
    return (value >> count) | (value << (32U - count));
}

uint32_t rotateLeft(uint32_t value, unsigned count) {
    return (value << count) | (value >> (32U - count));
}

uint32_t loadLittle32(const uint8_t* data) {
    return static_cast<uint32_t>(data[0])
            | (static_cast<uint32_t>(data[1]) << 8U)
            | (static_cast<uint32_t>(data[2]) << 16U)
            | (static_cast<uint32_t>(data[3]) << 24U);
}

uint64_t loadLittle64(const uint8_t* data) {
    uint64_t value = 0;
    for (unsigned index = 0; index < 8; ++index) {
        value |= static_cast<uint64_t>(data[index]) << (index * 8U);
    }
    return value;
}

void storeLittle32(uint8_t* destination, uint32_t value) {
    for (unsigned index = 0; index < 4; ++index) {
        destination[index] = static_cast<uint8_t>(value >> (index * 8U));
    }
}

class Sha256 final {
public:
    Sha256() = default;

    ~Sha256() {
        secureZero(state_.data(), sizeof(state_));
        secureZero(block_.data(), block_.size());
        totalSize_ = 0;
        blockSize_ = 0;
    }

    void update(const uint8_t* data, size_t size) {
        totalSize_ += size;
        while (size > 0) {
            const size_t copySize = std::min(size, block_.size() - blockSize_);
            std::memcpy(block_.data() + blockSize_, data, copySize);
            blockSize_ += copySize;
            data += copySize;
            size -= copySize;
            if (blockSize_ == block_.size()) {
                transform(block_.data());
                blockSize_ = 0;
            }
        }
    }

    std::array<uint8_t, 32> finish() {
        const uint64_t bitSize = totalSize_ * 8U;
        std::array<uint8_t, 64> padding{};
        padding[0] = 0x80;
        const size_t paddingSize = blockSize_ < 56
                ? 56 - blockSize_ : 120 - blockSize_;
        update(padding.data(), paddingSize);
        std::array<uint8_t, 8> encodedSize{};
        for (unsigned index = 0; index < 8; ++index) {
            encodedSize[7 - index] = static_cast<uint8_t>(bitSize >> (index * 8U));
        }
        update(encodedSize.data(), encodedSize.size());

        std::array<uint8_t, 32> digest{};
        for (size_t word = 0; word < state_.size(); ++word) {
            digest[word * 4] = static_cast<uint8_t>(state_[word] >> 24U);
            digest[word * 4 + 1] = static_cast<uint8_t>(state_[word] >> 16U);
            digest[word * 4 + 2] = static_cast<uint8_t>(state_[word] >> 8U);
            digest[word * 4 + 3] = static_cast<uint8_t>(state_[word]);
        }
        secureZero(block_.data(), block_.size());
        return digest;
    }

private:
    void transform(const uint8_t* block) {
        static constexpr std::array<uint32_t, 64> constants = {
                0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
                0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
                0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
                0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
                0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
                0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
                0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
                0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
                0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
                0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
                0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
                0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
                0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
                0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
                0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
                0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
        };
        std::array<uint32_t, 64> schedule{};
        for (size_t index = 0; index < 16; ++index) {
            const uint8_t* value = block + index * 4;
            schedule[index] = (static_cast<uint32_t>(value[0]) << 24U)
                    | (static_cast<uint32_t>(value[1]) << 16U)
                    | (static_cast<uint32_t>(value[2]) << 8U)
                    | static_cast<uint32_t>(value[3]);
        }
        for (size_t index = 16; index < schedule.size(); ++index) {
            const uint32_t s0 = rotateRight(schedule[index - 15], 7)
                    ^ rotateRight(schedule[index - 15], 18)
                    ^ (schedule[index - 15] >> 3U);
            const uint32_t s1 = rotateRight(schedule[index - 2], 17)
                    ^ rotateRight(schedule[index - 2], 19)
                    ^ (schedule[index - 2] >> 10U);
            schedule[index] = schedule[index - 16] + s0
                    + schedule[index - 7] + s1;
        }

        uint32_t a = state_[0];
        uint32_t b = state_[1];
        uint32_t c = state_[2];
        uint32_t d = state_[3];
        uint32_t e = state_[4];
        uint32_t f = state_[5];
        uint32_t g = state_[6];
        uint32_t h = state_[7];
        for (size_t index = 0; index < schedule.size(); ++index) {
            const uint32_t sum1 = rotateRight(e, 6) ^ rotateRight(e, 11)
                    ^ rotateRight(e, 25);
            const uint32_t choose = (e & f) ^ (~e & g);
            const uint32_t temporary1 = h + sum1 + choose
                    + constants[index] + schedule[index];
            const uint32_t sum0 = rotateRight(a, 2) ^ rotateRight(a, 13)
                    ^ rotateRight(a, 22);
            const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temporary2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temporary1;
            d = c;
            c = b;
            b = a;
            a = temporary1 + temporary2;
        }
        state_[0] += a;
        state_[1] += b;
        state_[2] += c;
        state_[3] += d;
        state_[4] += e;
        state_[5] += f;
        state_[6] += g;
        state_[7] += h;
        secureZero(schedule.data(), sizeof(schedule));
    }

    std::array<uint32_t, 8> state_ = {
            0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
            0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U,
    };
    std::array<uint8_t, 64> block_{};
    uint64_t totalSize_ = 0;
    size_t blockSize_ = 0;
};

class HmacSha256 final {
public:
    explicit HmacSha256(const std::array<uint8_t, kKeySize>& key) {
        outerPad_.fill(0x5c);
        std::array<uint8_t, 64> innerPad{};
        innerPad.fill(0x36);
        for (size_t index = 0; index < key.size(); ++index) {
            innerPad[index] ^= key[index];
            outerPad_[index] ^= key[index];
        }
        inner_.update(innerPad.data(), innerPad.size());
        secureZero(innerPad.data(), innerPad.size());
    }

    ~HmacSha256() {
        secureZero(outerPad_.data(), outerPad_.size());
    }

    void update(const uint8_t* data, size_t size) {
        inner_.update(data, size);
    }

    std::array<uint8_t, kTagSize> finish() {
        auto innerDigest = inner_.finish();
        Sha256 outer;
        outer.update(outerPad_.data(), outerPad_.size());
        outer.update(innerDigest.data(), innerDigest.size());
        auto result = outer.finish();
        secureZero(innerDigest.data(), innerDigest.size());
        secureZero(outerPad_.data(), outerPad_.size());
        return result;
    }

private:
    Sha256 inner_;
    std::array<uint8_t, 64> outerPad_{};
};

void quarterRound(
        std::array<uint32_t, 16>& state,
        size_t a,
        size_t b,
        size_t c,
        size_t d) {
    state[a] += state[b];
    state[d] = rotateLeft(state[d] ^ state[a], 16);
    state[c] += state[d];
    state[b] = rotateLeft(state[b] ^ state[c], 12);
    state[a] += state[b];
    state[d] = rotateLeft(state[d] ^ state[a], 8);
    state[c] += state[d];
    state[b] = rotateLeft(state[b] ^ state[c], 7);
}

class ChaCha20 final {
public:
    ChaCha20(
            const std::array<uint8_t, kKeySize>& key,
            const uint8_t* nonce) {
        state_[0] = 0x61707865U;
        state_[1] = 0x3320646eU;
        state_[2] = 0x79622d32U;
        state_[3] = 0x6b206574U;
        for (size_t index = 0; index < 8; ++index) {
            state_[4 + index] = loadLittle32(key.data() + index * 4);
        }
        state_[12] = 1;
        state_[13] = loadLittle32(nonce);
        state_[14] = loadLittle32(nonce + 4);
        state_[15] = loadLittle32(nonce + 8);
    }

    bool transform(const uint8_t* input, uint8_t* output, size_t size) {
        while (size > 0) {
            if (streamOffset_ == stream_.size()) {
                if (state_[12] == 0) {
                    return false;
                }
                refill();
            }
            const size_t count = std::min(size, stream_.size() - streamOffset_);
            for (size_t index = 0; index < count; ++index) {
                output[index] = input[index] ^ stream_[streamOffset_ + index];
            }
            input += count;
            output += count;
            size -= count;
            streamOffset_ += count;
        }
        return true;
    }

    ~ChaCha20() {
        secureZero(state_.data(), sizeof(state_));
        secureZero(stream_.data(), stream_.size());
    }

private:
    void refill() {
        auto working = state_;
        for (unsigned round = 0; round < 10; ++round) {
            quarterRound(working, 0, 4, 8, 12);
            quarterRound(working, 1, 5, 9, 13);
            quarterRound(working, 2, 6, 10, 14);
            quarterRound(working, 3, 7, 11, 15);
            quarterRound(working, 0, 5, 10, 15);
            quarterRound(working, 1, 6, 11, 12);
            quarterRound(working, 2, 7, 8, 13);
            quarterRound(working, 3, 4, 9, 14);
        }
        for (size_t index = 0; index < working.size(); ++index) {
            storeLittle32(stream_.data() + index * 4,
                          working[index] + state_[index]);
        }
        ++state_[12];
        streamOffset_ = 0;
        secureZero(working.data(), sizeof(working));
    }

    std::array<uint32_t, 16> state_{};
    std::array<uint8_t, 64> stream_{};
    size_t streamOffset_ = 64;
};

std::array<uint8_t, kKeySize> reconstructKey(
        size_t partAOffset,
        size_t partBOffset,
        size_t partCOffset) {
    std::array<uint8_t, kKeySize> key{};
    for (size_t index = 0; index < key.size(); ++index) {
        key[index] = decodeMaterialByte(partAOffset + index)
                ^ decodeMaterialByte(partBOffset + index)
                ^ decodeMaterialByte(partCOffset + index);
    }
    return key;
}

bool constantTimeEquals(
        const uint8_t* first,
        const uint8_t* second,
        size_t size) {
    uint8_t difference = 0;
    for (size_t index = 0; index < size; ++index) {
        difference |= first[index] ^ second[index];
    }
    return difference == 0;
}

bool readExact(AAsset* asset, uint8_t* destination, size_t size) {
    while (size > 0) {
        const size_t request = std::min(
                size, static_cast<size_t>(std::numeric_limits<int>::max()));
        const int count = AAsset_read(asset, destination, request);
        if (count <= 0) {
            return false;
        }
        destination += static_cast<size_t>(count);
        size -= static_cast<size_t>(count);
    }
    return true;
}

bool hasExpectedElfHeader(const uint8_t* data, size_t size) {
    return size >= 20
            && data[0] == 0x7f
            && data[1] == 'E'
            && data[2] == 'L'
            && data[3] == 'F'
            && data[4] == 2
            && data[5] == 1
            && data[18] == 0xb7
            && data[19] == 0x00;
}

int createAnonymousModuleFile(size_t size) {
    unsigned flags = MFD_CLOEXEC | MFD_ALLOW_SEALING;
#if defined(MFD_EXEC)
    int descriptor = static_cast<int>(syscall(
            SYS_memfd_create,
            EMUORBIT_SECONDARY_LIBRARY,
            flags | MFD_EXEC));
    if (descriptor < 0) {
        descriptor = static_cast<int>(syscall(
                SYS_memfd_create,
                EMUORBIT_SECONDARY_LIBRARY,
                flags));
    }
#else
    int descriptor = static_cast<int>(syscall(
            SYS_memfd_create,
            EMUORBIT_SECONDARY_LIBRARY,
            flags));
#endif
    if (descriptor < 0
            || ftruncate(descriptor, static_cast<off_t>(size)) != 0) {
        if (descriptor >= 0) {
            close(descriptor);
        }
        return -1;
    }
    return descriptor;
}

}  // namespace

bool loadProtectedCoreContainer(
        JNIEnv* env,
        jobject assetManagerObject,
        void** libraryHandle) {
    const auto fail = [](int stage) {
#if defined(EMUORBIT_SANITIZER_DIAGNOSTIC)
        __android_log_print(
                ANDROID_LOG_ERROR,
                "EmuOrbitNative",
                "Protected loader diagnostic stage %d",
                stage);
#else
        (void) stage;
#endif
        return false;
    };
    if (env == nullptr || assetManagerObject == nullptr
            || libraryHandle == nullptr) {
        return fail(1);
    }
    *libraryHandle = nullptr;
    AAssetManager* manager = AAssetManager_fromJava(env, assetManagerObject);
    if (manager == nullptr) {
        return fail(2);
    }
    std::unique_ptr<AAsset, decltype(&AAsset_close)> asset(
            AAssetManager_open(
                    manager,
                    EMUORBIT_DS_CONTAINER_ASSET,
                    AASSET_MODE_STREAMING),
            &AAsset_close);
    if (!asset) {
        return fail(3);
    }
    const off64_t containerSize = AAsset_getLength64(asset.get());
    if (containerSize < static_cast<off64_t>(kHeaderSize + kTagSize)) {
        return fail(4);
    }

    std::array<uint8_t, kHeaderSize> header{};
    ScopedWipe headerWipe(header.data(), header.size());
    if (!readExact(asset.get(), header.data(), header.size())) {
        return fail(5);
    }
    std::array<uint8_t, kMagicSize> expectedMagic{};
    for (size_t index = 0; index < expectedMagic.size(); ++index) {
        expectedMagic[index] = decodeMaterialByte(kMagicOffset + index);
    }
    const bool validMagic = constantTimeEquals(
            header.data(), expectedMagic.data(), expectedMagic.size());
    secureZero(expectedMagic.data(), expectedMagic.size());
    if (!validMagic) {
        return fail(6);
    }
    const uint64_t payloadSize = loadLittle64(
            header.data() + kMagicSize + kNonceSize);
    if (payloadSize == 0 || payloadSize > kMaximumPayloadSize
            || static_cast<uint64_t>(containerSize)
                    != kHeaderSize + payloadSize + kTagSize) {
        return fail(7);
    }

    auto authenticationKey = reconstructKey(
            kAuthenticationPartAOffset,
            kAuthenticationPartBOffset,
            kAuthenticationPartCOffset);
    HmacSha256 authenticator(authenticationKey);
    secureZero(authenticationKey.data(), authenticationKey.size());
    authenticator.update(header.data(), header.size());
    std::vector<uint8_t> ioBuffer(kIoBufferSize);
    uint64_t remaining = payloadSize;
    while (remaining > 0) {
        const size_t count = static_cast<size_t>(
                std::min<uint64_t>(remaining, ioBuffer.size()));
        if (!readExact(asset.get(), ioBuffer.data(), count)) {
            secureZero(ioBuffer.data(), ioBuffer.size());
            return fail(8);
        }
        authenticator.update(ioBuffer.data(), count);
        remaining -= count;
    }
    std::array<uint8_t, kTagSize> storedTag{};
    if (!readExact(asset.get(), storedTag.data(), storedTag.size())) {
        secureZero(ioBuffer.data(), ioBuffer.size());
        return fail(9);
    }
    auto calculatedTag = authenticator.finish();
    const bool validTag = constantTimeEquals(
            storedTag.data(), calculatedTag.data(), storedTag.size());
    secureZero(storedTag.data(), storedTag.size());
    secureZero(calculatedTag.data(), calculatedTag.size());
    if (!validTag || AAsset_seek64(
            asset.get(), static_cast<off64_t>(kHeaderSize), SEEK_SET)
                    != static_cast<off64_t>(kHeaderSize)) {
        secureZero(ioBuffer.data(), ioBuffer.size());
        return fail(10);
    }

    const int sharedFd = createAnonymousModuleFile(
            static_cast<size_t>(payloadSize));
    if (sharedFd < 0) {
        secureZero(ioBuffer.data(), ioBuffer.size());
        return fail(11);
    }
    void* mapping = mmap(
            nullptr,
            static_cast<size_t>(payloadSize),
            PROT_READ | PROT_WRITE,
            MAP_SHARED,
            sharedFd,
            0);
    if (mapping == MAP_FAILED) {
        close(sharedFd);
        secureZero(ioBuffer.data(), ioBuffer.size());
        return fail(12);
    }
    // Keep the short-lived plaintext mapping out of ordinary process dumps.
    // This is deliberately best-effort: kernels that reject the hint must not
    // prevent emulation on otherwise supported Android devices.
    (void) madvise(
            mapping,
            static_cast<size_t>(payloadSize),
            MADV_DONTDUMP);

    auto encryptionKey = reconstructKey(
            kEncryptionPartAOffset,
            kEncryptionPartBOffset,
            kEncryptionPartCOffset);
    ChaCha20 cipher(encryptionKey, header.data() + kMagicSize);
    secureZero(encryptionKey.data(), encryptionKey.size());
    headerWipe.wipe();
    remaining = payloadSize;
    size_t offset = 0;
    bool decrypted = true;
    while (remaining > 0) {
        const size_t count = static_cast<size_t>(
                std::min<uint64_t>(remaining, ioBuffer.size()));
        if (!readExact(asset.get(), ioBuffer.data(), count)
                || !cipher.transform(
                        ioBuffer.data(),
                        static_cast<uint8_t*>(mapping) + offset,
                        count)) {
            decrypted = false;
            break;
        }
        offset += count;
        remaining -= count;
    }
    secureZero(ioBuffer.data(), ioBuffer.size());
    const bool validElf = decrypted && hasExpectedElfHeader(
            static_cast<const uint8_t*>(mapping),
            static_cast<size_t>(payloadSize));
    if (!validElf) {
        secureZero(mapping, static_cast<size_t>(payloadSize));
        munmap(mapping, static_cast<size_t>(payloadSize));
        close(sharedFd);
        return fail(decrypted ? 14 : 13);
    }
    munmap(mapping, static_cast<size_t>(payloadSize));
    constexpr int kRequiredSeals =
            F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    if (fcntl(sharedFd, F_ADD_SEALS, kRequiredSeals) != 0) {
        close(sharedFd);
        return fail(16);
    }
    const int appliedSeals = fcntl(sharedFd, F_GET_SEALS);
    if (appliedSeals < 0 || (appliedSeals & kRequiredSeals) != kRequiredSeals) {
        close(sharedFd);
        return fail(16);
    }

    android_dlextinfo loaderInfo{};
    loaderInfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    loaderInfo.library_fd = sharedFd;
    void* handle = android_dlopen_ext(
            EMUORBIT_SECONDARY_LIBRARY,
            RTLD_NOW | RTLD_LOCAL,
            &loaderInfo);
    close(sharedFd);
    if (handle == nullptr) {
#if defined(EMUORBIT_SANITIZER_DIAGNOSTIC)
        const char* loaderError = dlerror();
        __android_log_print(
                ANDROID_LOG_ERROR,
                "EmuOrbitNative",
                "Protected loader diagnostic: %s",
                loaderError == nullptr ? "unknown dynamic-loader error" : loaderError);
#endif
        return fail(15);
    }
    *libraryHandle = handle;
    return true;
}

}  // namespace emuorbit
