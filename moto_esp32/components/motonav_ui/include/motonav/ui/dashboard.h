#pragma once
#include "lvgl.h"

namespace motonav {
// Shared presentation widgets only: no timers, transport, hardware or test state.
// Parent owns LVGL objects; the caller keeps this struct alive while displayed.
struct Dashboard {
    lv_obj_t *top = nullptr, *title = nullptr, *number = nullptr, *speed = nullptr;
    lv_obj_t *remaining = nullptr, *footer = nullptr, *arrow = nullptr;
    lv_point_precise_t arrow_points[6]{};
    int last_icon = -1;
    float arrow_scale = 1;
};
void create_dashboard(lv_obj_t *screen, Dashboard &view);
void dashboard_arrow(Dashboard &view, int icon);
void set_label_text(lv_obj_t *object, const char *value);
struct LabelCacheStats { uint64_t hits = 0, misses = 0; size_t bytes = 0; };
// Read under the LVGL lock. Cache storage is bounded and shared by all screens.
LabelCacheStats label_cache_stats();
} // namespace motonav
