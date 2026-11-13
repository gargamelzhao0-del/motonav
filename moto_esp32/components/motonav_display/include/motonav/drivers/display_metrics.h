#pragma once
#include <cstdint>

namespace motonav::drivers {
// Optional diagnostics, disabled by default. Times are accumulated durations,
// not mutually exclusive: rendering and DMA intentionally overlap.
struct DisplayMetrics {
    uint64_t bytes = 0, transfers = 0, window_commands = 0;
    uint64_t command_us = 0, submit_us = 0, transfer_us = 0, wait_us = 0;
};
} // namespace motonav::drivers
