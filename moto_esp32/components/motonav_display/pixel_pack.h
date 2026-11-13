#pragma once
#include <cstddef>
#include <cstdint>

namespace motonav::drivers {
// Private, allocation-free transport conversion. The caller owns the source
// RGB565_SWAPPED buffer, aligned to four bytes, with count divisible by four.
// RGB332 output aliases its beginning; no bytes beyond count are modified.
// Returns the exact output byte count, or zero for invalid arguments.
class PixelPack {
public:
    static size_t rgb332(uint8_t *pixels, size_t count);
};
}
