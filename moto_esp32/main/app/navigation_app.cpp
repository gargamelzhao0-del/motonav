#include "navigation_app.h"
#include "navigation_service.h"
#include "motonav/drivers/display.h"
#include "motonav/drivers/touch.h"
#include "motonav/drivers/platform.h"
#include "motonav/ui/navigation_ui.h"
#include "freertos/task.h"
#include "esp_log.h"
#if MOTONAV_DEVICE_TESTS
#include "motonav/testing/display_benchmark.h"
#include "motonav/testing/driver_probe.h"
#include "motonav/testing/pixel_pack_check.h"
#endif

namespace motonav::app {
namespace {
NavigationService navigation;
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
    }, &navigation};
    UiExtension extension;
#if MOTONAV_DEVICE_TESTS
    extension = testing::make_display_benchmark(display, reader, testing::driver_benchmark_services(display));
#endif
    create_navigation_ui(display, name, reader, extension);
    drivers::display_unlock();
    ESP_ERROR_CHECK(drivers::display_start());
    ESP_ERROR_CHECK(drivers::ble_start(name, drivers::firmware_version(), navigation.callbacks()));
    ESP_LOGI("motonav", "Firmware %s / NAV3 / %s / device_tests=%d",
             drivers::firmware_version(), name, MOTONAV_DEVICE_TESTS);

    while (true) {
        vTaskDelay(pdMS_TO_TICKS(5000));
        auto state = navigation.snapshot();
        auto memory = drivers::memory_stats();
        ESP_LOGI("motonav", "state=%d link=%d secure=%d rx=%lu dup=%lu rejected=%lu heap=%lu internal=%lu",
            static_cast<int>(screen_mode(state)), state.linked, state.encrypted,
            static_cast<unsigned long>(state.packets.accepted), static_cast<unsigned long>(state.packets.duplicates),
            static_cast<unsigned long>(state.packets.rejected), static_cast<unsigned long>(memory.free_heap),
            static_cast<unsigned long>(memory.free_internal));
    }
}
} // namespace motonav::app
