#pragma once
#include "motonav/graphics/map_projection.h"
#include "lvgl.h"

namespace motonav {
struct MapGeometry {
    const MapPoint *route;
    size_t route_count;
    const MapSegment *roads;
    size_t road_count;
    bool show_position=true;
};
// All functions below require the LVGL thread or the caller's display lock.
// Image owns a bounded scene; fallback decoding lazily allocates a strip buffer.
// One LVGL runtime per boot; deleting the image releases its scene and scratch.
lv_obj_t *create_route_map(lv_obj_t *parent, int width, int height);
// Copies/project geometry now; LVGL rasterizes strips synchronously during refresh.
// At most 512 route points and 64 road segments. Invalid arguments return false
// without changing the previous scene. Input pointers are borrowed until return.
bool render_route_map(lv_obj_t *canvas, const MapCamera &camera, const MapGeometry &geometry);
struct MapPaintStats { uint64_t calls = 0, elapsed_us = 0; };
// Optional caller-supplied clock; nullptr disables profiling. Call under the LVGL lock.
void configure_route_map_profiler(lv_obj_t *view, uint64_t (*clock_us)());
MapPaintStats route_map_paint_stats(lv_obj_t *view);
} // namespace motonav
