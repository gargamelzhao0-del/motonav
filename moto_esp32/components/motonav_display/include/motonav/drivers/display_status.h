#pragma once
#include <cstdint>
#include "esp_err.h"

namespace motonav::drivers {
enum class DisplayState : uint32_t { NotInitialized, Ready, Running, Faulted };
enum class DisplayFault : uint32_t { None, DmaTimeout, InvalidBuffer, ControlTransfer, ColorTransfer };
struct DisplayStatus {
    DisplayState state = DisplayState::NotInitialized;
    DisplayFault fault = DisplayFault::None;
    esp_err_t error = ESP_OK;
};
// Short critical-section snapshot; never takes the LVGL lock. Callable from
// application tasks even if LVGL is stalled. No heap allocation or blocking IO.
// A runtime fault is latched until reboot. Buffers remain owned; the application
// must arrange restart rather than destroying/reinitializing the display.
DisplayStatus display_status();
} // namespace motonav::drivers
