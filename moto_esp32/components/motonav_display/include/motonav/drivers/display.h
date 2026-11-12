#pragma once
#include "esp_err.h"
#include "lvgl.h"

namespace motonav::drivers {
// One board/display per boot. Initialize display, attach touch, then start.
// Initialization failure is fatal for this boot; partial init is not retryable.
esp_err_t display_init(lv_display_t **out, uint8_t brightness_percent = 70);
esp_err_t display_start();
// Application tasks must lock around LVGL calls; callbacks already hold it.
// Recursive mutex. -1 waits indefinitely; nonnegative values are milliseconds.
esp_err_t display_lock(int32_t timeout_ms = -1);
void display_unlock();
// Serializes with rendering and waits for actual DMA completion (0..100).
esp_err_t display_set_brightness(uint8_t percent);
// Counts completed display frames, not UI timer ticks. Disabled by default.
esp_err_t display_enable_fps(lv_display_t *display, bool enable);
esp_err_t display_reset_fps(lv_display_t *display);
esp_err_t display_read_fps(lv_display_t *display, uint32_t *fps);
} // namespace motonav::drivers
