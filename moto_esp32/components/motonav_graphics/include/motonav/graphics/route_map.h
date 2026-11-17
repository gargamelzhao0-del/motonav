#pragma once
#include "motonav/graphics/map_projection.h"
#include "lvgl.h"

namespace motonav {
struct MapFeature { uint8_t kind;MapPoint a,b,c; };
struct MapGeometry {
    const MapPoint *route;
    size_t route_count;
    const MapSegment *roads;
    size_t road_count;
    bool show_position=true;
    const MapFeature *features=nullptr;
    size_t feature_count=0;
    bool compass=false;
    uint8_t position_style=0; // 0 heading arrow, 1 approximate, 2 precise, 3 historical dot.
    // Optional visual split: segment index + fraction, -1 means unknown.
    // The caller owns position validity; this does not change guidance/progress.
    float route_progress=-1;
};
// All functions below require the LVGL thread or the caller's display lock.
// Image owns a bounded scene; fallback decoding lazily allocates a strip buffer.
// One LVGL runtime per boot; deleting the image releases its scene and scratch.
lv_obj_t *create_route_map(lv_obj_t *parent, int width, int height);
// Copies/project geometry now; LVGL rasterizes strips synchronously during refresh.
// At most 64 route points, 64 legacy road segments and 384 real map primitives.
// Invalid arguments return false
// without changing the previous scene. Input pointers are borrowed until return.
bool render_route_map(lv_obj_t *canvas, const MapCamera &camera, const MapGeometry &geometry);
struct MapPaintStats { uint64_t calls = 0, elapsed_us = 0; };
// Optional caller-supplied clock; nullptr disables profiling. Call under the LVGL lock.
void configure_route_map_profiler(lv_obj_t *view, uint64_t (*clock_us)());
MapPaintStats route_map_paint_stats(lv_obj_t *view);
} // namespace motonav
