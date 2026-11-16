#include "navigation_app.h"
#include "navigation_service.h"
#include "device_supervisor.h"
#include "motonav/drivers/display.h"
#include "motonav/drivers/touch.h"
#include "motonav/drivers/platform.h"
#include "motonav/ui/navigation_ui.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "sdkconfig.h"
static_assert(CONFIG_ESP_MAIN_TASK_STACK_SIZE >= 6144,
              "Update existing sdkconfig: CONFIG_ESP_MAIN_TASK_STACK_SIZE must be at least 6144");
#if MOTONAV_BLE_DIAGNOSTICS
#include "motonav/diagnostics/serial_bridge.h"
#endif
#if MOTONAV_DEVICE_TESTS
#include "motonav/testing/display_benchmark.h"
#include "motonav/testing/driver_probe.h"
#include "motonav/testing/pixel_pack_check.h"
#endif

namespace motonav::app {
namespace {
NavigationService navigation;
#if MOTONAV_BLE_DIAGNOSTICS
diagnostics::SerialBridge diagnostic_bridge;
#endif
char name[20];
}
void run() {
    ESP_ERROR_CHECK(drivers::platform_init());
    ESP_ERROR_CHECK(navigation.init());
    ESP_ERROR_CHECK(drivers::device_name(name, sizeof(name)));

    lv_display_t *display = nullptr;
    drivers::DisplayConfig display_config;
    display_config.buffer_rows = MOTONAV_DISPLAY_ROWS;
    display_config.direct_qspi = MOTONAV_DIRECT_QSPI;
    display_config.color = MOTONAV_COLOR_RGB332 ? drivers::DisplayColor::Rgb332 : drivers::DisplayColor::Rgb565;
#if MOTONAV_DEVICE_TESTS
    if (display_config.color == drivers::DisplayColor::Rgb332 && !testing::check_pixel_pack()) {
        ESP_LOGE("motonav", "RGB332 self-check failed; use RGB565 for this boot");
        display_config.color = drivers::DisplayColor::Rgb565;
    }
#endif
    ESP_ERROR_CHECK(drivers::display_init(&display, display_config));
    ESP_ERROR_CHECK(drivers::touch_init(display));
    ESP_ERROR_CHECK(drivers::display_lock());
    SnapshotReader reader{[](void *context) {
        return static_cast<NavigationService *>(context)->snapshot();
    }, &navigation,[](void *context,uint32_t revision,BasemapScene &out) {
        return static_cast<NavigationService *>(context)->read_map(revision,out);
    }};
    UiExtension extension;
#if MOTONAV_DEVICE_TESTS
    extension = testing::make_display_benchmark(display, reader, testing::driver_benchmark_services(display));
#endif
    create_navigation_ui(display, name, reader, extension);
    drivers::display_unlock();
    ESP_ERROR_CHECK(drivers::display_start());
    drivers::BleDiagnosticCallbacks diagnostics;
#if MOTONAV_BLE_DIAGNOSTICS
    ESP_ERROR_CHECK(diagnostic_bridge.init());
    diagnostics = diagnostic_bridge.callbacks();
#endif
    ESP_ERROR_CHECK(drivers::ble_start(name, drivers::firmware_version(), navigation.callbacks(), diagnostics));
    ESP_LOGI("motonav", "Firmware %s / NAV3+NAV4 / %s / device_tests=%d / ble_diagnostics=%d",
             drivers::firmware_version(), name, MOTONAV_DEVICE_TESTS, MOTONAV_BLE_DIAGNOSTICS);

#if MOTONAV_NAV_METRICS
    ESP_ERROR_CHECK(drivers::display_enable_fps(display, true));
    bool measuring = false;
    uint64_t started = 0;
    RouteScene previous;
#endif
    uint64_t next_sample = drivers::monotonic_ms() + (MOTONAV_NAV_METRICS ? 1100 : 5000);
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(100));
        supervise_display();
        const uint64_t sample_time = drivers::monotonic_ms();
        if (sample_time < next_sample) continue;
        next_sample = sample_time + (MOTONAV_NAV_METRICS ? 1100 : 5000);
        auto state = navigation.snapshot();
        auto memory = drivers::memory_stats();
        auto ble = drivers::ble_runtime_status();
        ESP_LOGI("motonav", "main_stack_min=%lu", static_cast<unsigned long>(uxTaskGetStackHighWaterMark(nullptr)));
        ESP_LOGI("motonav", "state=%d link=%d secure=%d protocol=%u route_points=%u route_revision=%lu rx=%lu dup=%lu rejected=%lu heap=%lu internal=%lu diag_rx=%u diag_writes=%lu diag_rejected=%lu ble_stack_min=%lu map_items=%u map_revision=%lu local=%d map_generation=%lu map_complete=%d",
            static_cast<int>(screen_mode(state)), state.linked, state.encrypted, state.protocol,
            static_cast<unsigned>(state.route.count), static_cast<unsigned long>(state.route.revision),
            static_cast<unsigned long>(state.packets.accepted), static_cast<unsigned long>(state.packets.duplicates),
            static_cast<unsigned long>(state.packets.rejected), static_cast<unsigned long>(memory.free_heap),
            static_cast<unsigned long>(memory.free_internal), ble.diagnostic_rx_handle,
            static_cast<unsigned long>(ble.diagnostic_writes), static_cast<unsigned long>(ble.diagnostic_rejected),
            static_cast<unsigned long>(ble.host_stack_min_bytes),static_cast<unsigned>(state.basemap_count),
            static_cast<unsigned long>(state.basemap_revision),state.route.local_view,
            static_cast<unsigned long>(state.basemap_generation),state.basemap_complete);
#if MOTONAV_NAV_METRICS
        const bool live = screen_mode(state) == ScreenMode::Live && state.route.count >= 2;
        const uint64_t now = drivers::monotonic_ms();
        if(live && !measuring) { ESP_ERROR_CHECK(drivers::display_reset_fps(display)); started = now; }
        if(live && measuring && now-started >= 2500) {
            uint32_t fps = 0; ESP_ERROR_CHECK(drivers::display_read_fps(display, &fps));
            bool motion = previous.revision == state.route.revision &&
                (previous.x != state.route.x || previous.y != state.route.y || previous.heading != state.route.heading);
            ESP_LOGI("navigation_perf", "last_render_fps=%lu motion=%d route_points=%u elapsed_ms=%llu",
                static_cast<unsigned long>(fps), motion, static_cast<unsigned>(state.route.count),
                static_cast<unsigned long long>(now-started-2500));
        }
        measuring = live; previous = state.route;
#endif
    }
}
} // namespace motonav::app
