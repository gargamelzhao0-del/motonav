#pragma once
#include <cstddef>
#include <cstdint>
#include "esp_err.h"

namespace motonav::drivers {
enum class BleEventType { Connected, Disconnected, Subscription, PairingPin, Security, Protocol };
struct BleEvent { BleEventType type; uint32_t value = 0; };
enum class BleReceiveResult { Accepted, Rejected, NoMemory };
struct BleCallbacks {
    void *context = nullptr;
    void (*event)(void *, const BleEvent &) = nullptr;
    BleReceiveResult (*receive)(void *, const uint8_t *, size_t) = nullptr;
};
// Single secure NUS link. Name/revision are copied. NVS must be initialized.
// Callbacks run on the BLE host task, must be short, and must not call LVGL.
// Receive bytes are borrowed for the callback only; they are NOT complete frames.
esp_err_t ble_start(const char *name, const char *revision, const BleCallbacks &callbacks);
// Call only inside a BLE callback (typically receive); never from ISR/UI tasks.
// Copies data into a NimBLE-owned packet. Returns an ESP error on backpressure.
esp_err_t ble_notify(const uint8_t *data, size_t length);
} // namespace motonav::drivers
