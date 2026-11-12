#pragma once
#include "lvgl.h"
#include <cstdlib>
#include "esp_timer.h"
#define ESP_ERROR_CHECK(value) do { if ((value) != 0) std::abort(); } while (0)
// Host previews verify layout only. They deliberately do not synthesize device FPS.
inline int esp_lv_adapter_fps_stats_enable(lv_display_t *, bool) { return 0; }
void advance_host_clock(uint32_t milliseconds);
inline int64_t fps_reset_us;
inline int esp_lv_adapter_fps_stats_reset(lv_display_t *) {
    // Cross a millisecond boundary while switching workloads, as on hardware.
    advance_host_clock(2);
    fps_reset_us = esp_timer_get_time();
    return 0;
}
inline int esp_lv_adapter_get_fps(lv_display_t *, uint32_t *value) {
    if (esp_timer_get_time() - fps_reset_us < 1000000) std::abort();
    *value = 0;
    return 0;
}
