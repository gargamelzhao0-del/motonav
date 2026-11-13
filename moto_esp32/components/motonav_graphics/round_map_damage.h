#pragma once
#include <array>

namespace motonav {
struct MapDamage { int left, top, right, bottom; }; // Exclusive right/bottom.
// Cover every physical pixel of the 466-pixel round panel, with 3 pixels of
// radial overscan. Only corners behind the circular bezel are left untouched.
// Even bounds match CO5300's two-pixel addressing requirement.
constexpr auto round_map_damage() {
    std::array<MapDamage, 8> bands{};
    for (int i=0; i<8; ++i) {
        int top=i*64, bottom=top+64 < 466 ? top+64 : 466;
        int dy=top*2 > 465 ? top*2-465 : (bottom-1)*2 < 465 ? 465-(bottom-1)*2 : 0;
        int left=0;
        while ((left*2-465)*(left*2-465)+dy*dy > 471*471) ++left;
        left &= ~1;
        bands[i]={left,top,466-left,bottom};
    }
    return bands;
}
} // namespace motonav
