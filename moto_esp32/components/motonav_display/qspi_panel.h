#pragma once
#include <cstddef>
#include "esp_lcd_types.h"
#include "esp_err.h"

namespace motonav::drivers {
// Board-specific factory, invoked on the display core. Uses public ESP-IDF
// SPI/LCD interfaces; the command and pixels share a CS-held DMA sequence.
esp_err_t create_qspi_panel(size_t max_bytes, esp_lcd_panel_handle_t *panel, esp_lcd_panel_io_handle_t *io);
}
