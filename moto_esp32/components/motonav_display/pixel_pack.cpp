#include "pixel_pack.h"
#include <cstring>
#ifdef ESP_PLATFORM
#include "esp_attr.h"
#define MOTONAV_PACK_IRAM IRAM_ATTR
#else
#define MOTONAV_PACK_IRAM
#endif

namespace motonav::drivers {
#if MOTONAV_PACK_S3
extern "C" void motonav_rgb332_s3(uint8_t *output, const uint8_t *source,
                                  size_t blocks, const uint32_t *masks);
// Internal RAM avoids flash-cache misses while the SPI DMA consumes the other strip.
alignas(16) static DRAM_ATTR const uint32_t rgb332_masks[]{
    0xe0e0e0e0, 0xe0e0e0e0, 0xe0e0e0e0, 0xe0e0e0e0,
    0x07070707, 0x07070707, 0x07070707, 0x07070707,
    0x18181818, 0x18181818, 0x18181818, 0x18181818,
};
#endif
namespace {
inline uint16_t rgb332_pair_value(uint32_t value) {
    value = (value & 0x00e000e0U) | ((value & 0x00070007U) << 2) | ((value & 0x18001800U) >> 11);
    return static_cast<uint16_t>((value & 0xff) | ((value >> 8) & 0xff00));
}
__attribute__((noinline)) uint16_t MOTONAV_PACK_IRAM rgb332_block8(const uint8_t *source,uint8_t *output) {
    uint32_t a,b,c,d;
    __builtin_memcpy(&a,source,4); __builtin_memcpy(&b,source+4,4);
    __builtin_memcpy(&c,source+8,4); __builtin_memcpy(&d,source+12,4);
    const uint32_t first=rgb332_pair_value(a)|(uint32_t(rgb332_pair_value(b))<<16);
    const uint32_t last=rgb332_pair_value(c)|(uint32_t(rgb332_pair_value(d))<<16);
    __builtin_memcpy(output,&first,4); __builtin_memcpy(output+4,&last,4);
    return static_cast<uint16_t>(last>>16);
}
}

size_t MOTONAV_PACK_IRAM PixelPack::rgb332(uint8_t *pixels, size_t count) {
    if (!pixels || !count || count % 4 || (reinterpret_cast<uintptr_t>(pixels) & 3U)) return 0;
    auto *source = static_cast<const uint8_t *>(__builtin_assume_aligned(pixels, 4));
    auto *output = static_cast<uint8_t *>(__builtin_assume_aligned(pixels, 4));
#if MOTONAV_PACK_S3
    if (!(reinterpret_cast<uintptr_t>(pixels) & 15U) && count >= 16) {
        const size_t bulk = count & ~size_t{15};
        motonav_rgb332_s3(output, source, bulk / 16, rgb332_masks);
        source += bulk * 2;
        output += bulk;
        // Scalar tail never reads or writes outside the caller's exact rectangle.
        for (size_t left = count - bulk; left; left -= 4, source += 8, output += 4) {
            uint32_t a, b;
            __builtin_memcpy(&a, source, 4);
            __builtin_memcpy(&b, source + 4, 4);
            const uint32_t packed = rgb332_pair_value(a) | (uint32_t(rgb332_pair_value(b)) << 16);
            __builtin_memcpy(output, &packed, 4);
        }
        return count;
    }
#endif
    const auto *end = source + (count & ~size_t{7}) * 2;
    uint32_t previous;
    __builtin_memcpy(&previous, source, 4);
    uint32_t repeated = rgb332_pair_value(previous) * 0x00010001U;
    for (; source < end; source += 16, output += 8) {
        uint32_t a,b,c,d;
        __builtin_memcpy(&a,source,4); __builtin_memcpy(&b,source+4,4);
        __builtin_memcpy(&c,source+8,4); __builtin_memcpy(&d,source+12,4);
        if ((a^previous)|(b^previous)|(c^previous)|(d^previous)) {
            repeated = rgb332_block8(source,output) * 0x00010001U;
            previous = d;
        } else {
            __builtin_memcpy(output,&repeated,4);
            __builtin_memcpy(output+4,&repeated,4);
        }
    }
    if (count & 4) {
        uint32_t a,b;
        __builtin_memcpy(&a,source,4); __builtin_memcpy(&b,source+4,4);
        const uint32_t packed=rgb332_pair_value(a)|(uint32_t(rgb332_pair_value(b))<<16);
        __builtin_memcpy(output,&packed,4);
    }
    return count;
}
}
