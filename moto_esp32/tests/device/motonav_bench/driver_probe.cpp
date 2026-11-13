#include "motonav/testing/driver_probe.h"
#include "motonav/drivers/display.h"
#include "motonav/drivers/platform.h"
#include "esp_log.h"
#include "src/core/lv_global.h"
#include "src/draw/lv_draw_private.h"

namespace motonav::testing {
namespace {
struct FrameProfile {
    uint64_t start = 0, flush_start = 0, wait_start = 0;
    uint64_t frames = 0, render_us = 0, flush_us = 0, wait_us = 0;
} frames;
uint64_t task_us[32]{}, task_calls[32]{};
int32_t (*software_dispatch)(lv_draw_unit_t *, lv_layer_t *);
int32_t profiled_dispatch(lv_draw_unit_t *unit, lv_layer_t *layer) {
    auto *task = lv_draw_get_available_task(layer, nullptr, 1);
    const unsigned type = task ? static_cast<unsigned>(task->type) : 0;
    const uint64_t begin = drivers::monotonic_us();
    auto result = software_dispatch(unit, layer);
    if (task && type < 32) { task_us[type] += drivers::monotonic_us()-begin; ++task_calls[type]; }
    return result;
}
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
    if (reset) { frames = {}; for (unsigned i=0;i<32;++i) task_us[i]=task_calls[i]=0; return; }
    auto n = frames.frames ? frames.frames : 1;
    ESP_LOGI("driver_profile", "frames=%llu render_us=%llu flush_us=%llu lv_wait_us=%llu bytes=%llu transfers=%llu windows=%llu command_us=%llu submit_us=%llu dma_us=%llu wait_us=%llu",
        frames.frames, frames.render_us/n, frames.flush_us/n, frames.wait_us/n,
        data.bytes/n, data.transfers, data.window_commands, data.command_us/n,
        data.submit_us/n, data.transfer_us/n, data.wait_us/n);
    for (unsigned i=0;i<32;++i) if (task_calls[i])
        ESP_LOGI("draw_profile", "type=%u calls=%llu total_us=%llu frame_us=%llu", i, task_calls[i], task_us[i], task_us[i]/n);
}
}
BenchmarkServices driver_benchmark_services(lv_display_t *display) {
    static_assert(LV_USE_OS == LV_OS_NONE, "Task timing requires synchronous LVGL rendering");
    for (auto *unit=LV_GLOBAL_DEFAULT()->draw_info.unit_head; unit; unit=unit->next) {
        if (!std::strcmp(unit->name, "SW")) { software_dispatch=unit->dispatch_cb; unit->dispatch_cb=profiled_dispatch; break; }
    }
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
