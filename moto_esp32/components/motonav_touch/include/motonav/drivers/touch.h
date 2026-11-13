#pragma once
#include "esp_err.h"
#include "lvgl.h"

namespace motonav::drivers {
// Call once after display_init and before display_start. CST9217 coordinates
// are calibrated here; application gestures are standard LVGL input events.
esp_err_t touch_init(lv_display_t *display, lv_indev_t **out = nullptr);
} // namespace motonav::drivers
