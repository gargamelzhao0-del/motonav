#pragma once
#include "map_projection.h"
#include "lvgl.h"

namespace motonav {
struct MapGeometry {
    const MapPoint *route;
    size_t route_count;
    const MapSegment *roads;
    size_t road_count;
};
// Image owns a bounded scene; fallback decoding lazily allocates a strip buffer.
lv_obj_t *create_route_map(lv_obj_t *parent, int width, int height);
// Copies/project geometry now; LVGL rasterizes strips synchronously during refresh.
bool render_route_map(lv_obj_t *canvas, const MapCamera &camera, const MapGeometry &geometry);
// Explicit bench fixture: never connected to NavigationReceiver or real guidance.
void render_map_benchmark(lv_obj_t *canvas, uint64_t elapsed_ms);
} // namespace motonav
