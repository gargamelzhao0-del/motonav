#pragma once
#include <cstddef>
#include <cstdint>

namespace motonav {
// Fill a caller-owned RGB565 span. Arbitrary pixel counts and 16-bit alignment
// are supported. No padding writes, byte-order conversion, DMA or allocation.
inline void fill_rgb565(uint16_t *pixels, size_t count, uint16_t color) {
    if (!pixels || !count) return;
    if (reinterpret_cast<uintptr_t>(pixels) & 2U) { *pixels++ = color; --count; }
    uint32_t pair = static_cast<uint32_t>(color) | (static_cast<uint32_t>(color) << 16);
    auto *out = static_cast<uint8_t *>(__builtin_assume_aligned(pixels, 4));
    // Explicit builtins bypass ESP-IDF's -fno-builtin-memcpy without violating
    // C++ aliasing. Unroll stores to reduce loop overhead on the S3.
    while (count >= 16) {
        __builtin_memcpy(out, &pair, 4); __builtin_memcpy(out+4, &pair, 4);
        __builtin_memcpy(out+8, &pair, 4); __builtin_memcpy(out+12, &pair, 4);
        __builtin_memcpy(out+16, &pair, 4); __builtin_memcpy(out+20, &pair, 4);
        __builtin_memcpy(out+24, &pair, 4); __builtin_memcpy(out+28, &pair, 4);
        out += 32; count -= 16;
    }
    while (count >= 2) { __builtin_memcpy(out, &pair, 4); out += 4; count -= 2; }
    if (count) __builtin_memcpy(out, &color, 2);
}
}
