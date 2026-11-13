#include "pixel_pack.h"
#include <array>
#include <cassert>
#include <cstring>
#include <iostream>
#include <vector>
using motonav::drivers::PixelPack;

static uint8_t reference(uint16_t rgb) {
    return static_cast<uint8_t>((rgb / 2048 / 4) * 32 + ((rgb / 32) % 64 / 8) * 4 + (rgb % 32 / 8));
}
static void check(size_t count, unsigned offset, unsigned run, unsigned seed) {
    std::vector<uint32_t> storage((count * 2 + 64) / 4, 0xa5a5a5a5);
    auto *raw = reinterpret_cast<uint8_t *>(storage.data());
    auto *pixels = raw + 16 + offset;
    for (size_t i=0; i<count; ++i) {
        const uint16_t rgb = static_cast<uint16_t>((i / run) * 71 + seed);
        pixels[2*i] = rgb >> 8; pixels[2*i+1] = rgb & 255;
    }
    const auto original = storage;
    const auto *before = reinterpret_cast<const uint8_t *>(original.data());
    assert(PixelPack::rgb332(pixels, count)==count);
    for (size_t i=0; i<count; ++i)
        assert(pixels[i]==reference(static_cast<uint16_t>((i/run)*71+seed)));
    assert(std::memcmp(raw, before, 16+offset)==0);
    assert(std::memcmp(pixels+count, before+16+offset+count, storage.size()*4-16-offset-count)==0);
}
int main() {
    check(65536, 0, 1, 0); // Odd multiplier visits every RGB565 color exactly once.
    for (unsigned offset : {0U,4U,8U,12U})
        for (size_t count=4; count<=1024; count+=4) check(count, offset, 1, count*313);
    for (unsigned run : {1U,2U,3U,8U,17U,466U,65536U})
        for (size_t count : {29824U,29820U,65536U}) check(count, 0, run, 0x63a5);
    alignas(16) std::array<uint8_t,64> pixels{};
    const auto original=pixels;
    assert(!PixelPack::rgb332(nullptr, 4));
    for (size_t count : {0U,1U,2U,3U,5U}) assert(!PixelPack::rgb332(pixels.data(), count));
    for (unsigned offset : {1U,2U,3U}) assert(!PixelPack::rgb332(pixels.data()+offset, 4));
    assert(pixels==original);
    std::cout << "PASS: RGB332 exhaustive colors, alignment, exact in-place bounds, tails and full strips\n";
}
