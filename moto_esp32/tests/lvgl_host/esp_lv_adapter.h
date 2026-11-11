#pragma once
#include "lvgl.h"
#include <cstdlib>
#define ESP_ERROR_CHECK(value) do { if ((value) != 0) std::abort(); } while (0)
// Host previews verify layout only. They deliberately do not synthesize device FPS.
inline int esp_lv_adapter_fps_stats_enable(lv_display_t *, bool) { return 0; }
inline int esp_lv_adapter_fps_stats_reset(lv_display_t *) { return 0; }
inline int esp_lv_adapter_get_fps(lv_display_t *, uint32_t *value) { *value = 0; return 0; }
