#include "motonav/drivers/platform.h"
#include "nvs_flash.h"
#include "esp_mac.h"
#include "esp_timer.h"
#include "esp_app_desc.h"
#include "esp_heap_caps.h"
#include <cstdio>

namespace motonav::drivers {
esp_err_t platform_init() { return nvs_flash_init(); }
esp_err_t device_name(char *out, size_t capacity) {
    if (!out || capacity < 13) return ESP_ERR_INVALID_ARG;
    uint8_t mac[6];
    esp_err_t result = esp_read_mac(mac, ESP_MAC_BT);
    if (result == ESP_OK) std::snprintf(out, capacity, "MotoNav-%02X%02X", mac[4], mac[5]);
    return result;
}
const char *firmware_version() { return esp_app_get_description()->version; }
uint64_t monotonic_us() { return static_cast<uint64_t>(esp_timer_get_time()); }
MemoryStats memory_stats() {
    return {heap_caps_get_free_size(MALLOC_CAP_8BIT),
            heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)};
}
} // namespace motonav::drivers
