#pragma once
#include "motonav/core/route_scene.h"
#include "motonav/core/map_labels.h"
#include <cstddef>

namespace motonav {
// Local metres in the same origin/revision as RouteScene. Roads are lines;
// buildings, water, parks, land use and sites are triangles tessellated by the
// phone. S6 adds land use=6, sites=7 and waterway centerlines=8.
struct MapPrimitive { uint8_t kind=0; RoutePoint a{},b{},c{}; };
struct BasemapScene {
    static constexpr size_t Capacity=384;
    uint32_t revision=0;
    uint32_t generation=0;
    bool complete=true;
    uint16_t count=0;
    MapPrimitive items[Capacity]{};
    MapLabels labels{};
};
} // namespace motonav
