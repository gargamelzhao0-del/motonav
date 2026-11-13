#pragma once
#include "qspi_test_sdk.h"
inline esp_err_t esp_lcd_panel_io_tx_param(esp_lcd_panel_io_handle_t io, int command,
                                          const void *data, size_t bytes) {
    return io->tx_param(io, command, data, bytes);
}
