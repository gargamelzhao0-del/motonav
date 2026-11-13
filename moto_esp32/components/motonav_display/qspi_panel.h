#pragma once
#include <cstddef>
#include "esp_lcd_types.h"
#include "esp_err.h"
#include "motonav/drivers/display_color.h"

namespace motonav::drivers {
// Board-specific factory, invoked on the display core. Uses public ESP-IDF
// SPI/LCD interfaces; the command and pixels share a CS-held DMA sequence.
esp_err_t create_qspi_panel(size_t max_bytes, esp_lcd_panel_handle_t *panel, esp_lcd_panel_io_handle_t *io);
// Initialization only; call before submitting any LVGL pixels.
esp_err_t qspi_panel_set_color(esp_lcd_panel_io_handle_t io, DisplayColor color);
}
