#include "motonav/drivers/touch.h"
#include "bsp/esp-bsp.h"
#include "bsp/touch.h"
#include "esp_check.h"

namespace motonav::drivers {
esp_err_t touch_init(lv_display_t *display, lv_indev_t **out) {
    static bool attempted = false;
    if (out) *out = nullptr;
    if (!display) return ESP_ERR_INVALID_ARG;
    if (attempted) return ESP_ERR_INVALID_STATE;
    attempted = true;
    bsp_display_cfg_t hardware{};
    hardware.touch_flags.mirror_x = hardware.touch_flags.mirror_y = 1;
    esp_lcd_touch_handle_t touch = nullptr;
    ESP_RETURN_ON_ERROR(bsp_touch_new(&hardware, &touch), "touch", "CST9217 init");
    esp_lv_adapter_touch_config_t config = ESP_LV_ADAPTER_TOUCH_DEFAULT_CONFIG(display, touch);
    lv_indev_t *input = esp_lv_adapter_register_touch(&config);
    if (!input) return ESP_ERR_NO_MEM;
    if (out) *out = input;
    return ESP_OK;
}
} // namespace motonav::drivers
