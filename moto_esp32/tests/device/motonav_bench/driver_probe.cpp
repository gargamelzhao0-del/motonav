#include "motonav/testing/driver_probe.h"
#include "motonav/drivers/display.h"
#include "motonav/drivers/platform.h"
#include "esp_log.h"

namespace motonav::testing {
namespace {
struct FrameProfile {
    uint64_t start = 0, flush_start = 0, wait_start = 0;
    uint64_t frames = 0, render_us = 0, flush_us = 0, wait_us = 0;
} frames;
void frame_event(lv_event_t *event) {
    uint64_t now = drivers::monotonic_us();
    switch (lv_event_get_code(event)) {
        case LV_EVENT_RENDER_START: frames.start = now; break;
        case LV_EVENT_FLUSH_START: frames.flush_start = now; break;
        case LV_EVENT_FLUSH_FINISH: frames.flush_us += now - frames.flush_start; break;
        case LV_EVENT_FLUSH_WAIT_START: frames.wait_start = now; break;
        case LV_EVENT_FLUSH_WAIT_FINISH: frames.wait_us += now - frames.wait_start; break;
        case LV_EVENT_RENDER_READY: frames.render_us += now - frames.start; ++frames.frames; break;
        default: break;
    }
}
void profile(bool reset) {
    if (!reset) {
        // Exercise a real panel control command between active workloads.
        // The driver must drain DMA and break RAMWRC before changing brightness.
        ESP_ERROR_CHECK(drivers::display_set_brightness(70));
        ESP_ERROR_CHECK(drivers::display_set_brightness(101) == ESP_ERR_INVALID_ARG ? ESP_OK : ESP_FAIL);
        ESP_LOGI("driver_check", "brightness 70 accepted; 101 rejected");
    }
    drivers::DisplayMetrics data;
    ESP_ERROR_CHECK(drivers::display_read_metrics(&data, reset));
    if (reset) { frames = {}; return; }
    auto n = frames.frames ? frames.frames : 1;
    ESP_LOGI("driver_profile", "frames=%llu render_us=%llu flush_us=%llu lv_wait_us=%llu bytes=%llu transfers=%llu windows=%llu command_us=%llu submit_us=%llu dma_us=%llu wait_us=%llu",
        frames.frames, frames.render_us/n, frames.flush_us/n, frames.wait_us/n,
        data.bytes/n, data.transfers, data.window_commands, data.command_us/n,
        data.submit_us/n, data.transfer_us/n, data.wait_us/n);
}
}
BenchmarkServices driver_benchmark_services(lv_display_t *display) {
    ESP_ERROR_CHECK(drivers::display_enable_fps(display, true));
    ESP_ERROR_CHECK(drivers::display_enable_metrics(true));
    lv_display_add_event_cb(display, frame_event, LV_EVENT_ALL, nullptr);
    return {drivers::monotonic_us,
        [](lv_display_t *target) { ESP_ERROR_CHECK(drivers::display_reset_fps(target)); },
        [](lv_display_t *target) {
            uint32_t fps = 0;
            ESP_ERROR_CHECK(drivers::display_read_fps(target, &fps));
            return fps;
        }, profile};
}
} // namespace motonav::testing
