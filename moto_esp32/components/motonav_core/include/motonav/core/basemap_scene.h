#pragma once
#include "motonav/core/route_scene.h"
#include <cstddef>

namespace motonav {
// Local metres in the same origin/revision as RouteScene. Roads are lines;
// buildings, water and parks are triangles, tessellated by the phone.
struct MapPrimitive { uint8_t kind=0; RoutePoint a{},b{},c{}; };
struct BasemapScene {
    static constexpr size_t Capacity=192;
    uint32_t revision=0;
    uint16_t count=0;
    MapPrimitive items[Capacity]{};
};
} // namespace motonav
