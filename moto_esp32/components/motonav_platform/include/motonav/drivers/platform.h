#pragma once
#include <cstddef>
#include <cstdint>
#include "esp_err.h"

namespace motonav::drivers {
// Initializes NVS for persisted BLE bonds. Never auto-erases an existing NVS.
esp_err_t platform_init();
// System restart, without erasing NVS. Called by application fault policy only.
[[noreturn]] void restart_device();
esp_err_t device_name(char *out, size_t capacity);
const char *firmware_version();
uint64_t monotonic_us();
inline uint64_t monotonic_ms() { return monotonic_us() / 1000; }
struct MemoryStats { size_t free_heap, free_internal; };
MemoryStats memory_stats();
} // namespace motonav::drivers
