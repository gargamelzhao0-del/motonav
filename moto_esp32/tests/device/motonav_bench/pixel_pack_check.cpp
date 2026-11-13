#include "motonav/testing/pixel_pack_check.h"
#include "pixel_pack.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include <cstdint>
#include <cstring>
#include <initializer_list>

namespace motonav::testing {
namespace {
constexpr size_t Capacity = 1024;
constexpr size_t Allocation = Capacity * 2 + 64;
uint16_t value_at(unsigned i, unsigned run, unsigned seed) {
    return static_cast<uint16_t>((i / run) * 71U + seed);
}
uint8_t expected(uint16_t rgb) {
    const unsigned red = (rgb / 2048) / 4;
    const unsigned green = ((rgb / 32) % 64) / 8;
    const unsigned blue = (rgb % 32) / 8;
    return static_cast<uint8_t>(red * 32 + green * 4 + blue);
}
bool one(uint8_t *storage, size_t count, size_t offset, unsigned run, unsigned seed) {
    std::memset(storage, 0xa5, Allocation);
    auto *pixels = storage + 16 + offset;
    for (size_t i = 0; i < count; ++i) {
        const uint16_t rgb = value_at(i, run, seed);
        pixels[2*i] = rgb >> 8;
        pixels[2*i+1] = rgb & 255;
    }
    if (drivers::PixelPack::rgb332(pixels, count) != count) return false;
    for (size_t i = 0; i < count; ++i) {
        if (pixels[i] != expected(value_at(i, run, seed))) {
            ESP_LOGE("pixel_check", "mismatch count=%u offset=%u index=%u got=%u want=%u",
                unsigned(count), unsigned(offset), unsigned(i), pixels[i], expected(value_at(i, run, seed)));
            return false;
        }
    }
    // The uncompressed suffix must not be overwritten by vector stores.
    for (size_t i = count; i < count*2; ++i) {
        const uint16_t rgb = value_at(i/2, run, seed);
        const uint8_t want = i&1 ? rgb&255 : rgb>>8;
        if (pixels[i] != want) return false;
    }
    for (size_t i = 0; i < 16 + offset; ++i) if (storage[i] != 0xa5) return false;
    for (size_t i = 16 + offset + count*2; i < Allocation; ++i) if (storage[i] != 0xa5) return false;
    return true;
}
}
bool check_pixel_pack() {
    auto *storage = static_cast<uint8_t *>(heap_caps_aligned_alloc(16, Allocation, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
    if (!storage) return false;
    bool ok = true;
    unsigned cases = 0;
    // Multiplication by odd 71 permutes all 65536 source colors.
    for (unsigned base = 0; base < 65536 && ok; base += Capacity) {
        ok = one(storage, Capacity, 0, 1, base*71U);
        ++cases;
    }
    for (unsigned offset : {0U, 4U, 8U, 12U})
        for (unsigned count = 4; count <= Capacity && ok; count += 4) {
            ok = one(storage, count, offset, 1, count*313U);
            ++cases;
        }
    for (unsigned run : {2U, 3U, 8U, 17U, 466U, 65536U}) {
        if (ok) ok = one(storage, Capacity, 0, run, 0x63a5U);
        ++cases;
    }
    heap_caps_free(storage);
    ESP_LOGI("pixel_check", "%s: RGB332 hardware kernel, all 65536 colors, short tails, offsets, guards; cases=%u",
        ok ? "PASS" : "FAIL", cases);
    return ok;
}
}
