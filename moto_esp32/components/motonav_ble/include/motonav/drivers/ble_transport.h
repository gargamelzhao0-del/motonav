#pragma once
#include <cstddef>
#include <cstdint>
#include "esp_err.h"

namespace motonav::drivers {
enum class BleEventType { Connected, Disconnected, Subscription, PairingPin, Security, Protocol };
struct BleEvent { BleEventType type; uint32_t value = 0; };
// Accepted consumes the entire write. Every other result consumes ZERO bytes:
// the caller may retry the same ATT fragment without resetting its stream.
enum class BleReceiveResult { Accepted, Rejected, NoMemory };
constexpr size_t BleNotificationBatchLimit = 32;
constexpr size_t BleNotificationMaxBytes = 512;
struct BleNotification { const uint8_t *data; size_t length; };
struct BleCallbacks {
    void *context = nullptr;
    void (*event)(void *, const BleEvent &) = nullptr;
    BleReceiveResult (*receive)(void *, const uint8_t *, size_t) = nullptr;
};
// Optional independent encrypted write channel (DIAG1). Does not select or
// mutate NAV3/NAV4, and cannot enqueue navigation ACKs. At most 20 bytes/write.
// Same BLE-host callback rules; reset discards partial data on link/security loss.
struct BleDiagnosticCallbacks {
    void *context = nullptr;
    BleReceiveResult (*receive)(void *, const uint8_t *, size_t) = nullptr;
    void (*reset)(void *) = nullptr;
};
// Single secure NUS link. Name/revision are copied. NVS must be initialized.
// Callbacks run on the BLE host task, must be short, and must not call LVGL.
// Receive bytes are borrowed for the callback only; they are NOT complete frames.
esp_err_t ble_start(const char *name, const char *revision, const BleCallbacks &callbacks,
                    const BleDiagnosticCallbacks &diagnostics = {});
// Call only inside a BLE callback (typically receive); never from ISR/UI tasks.
// ESP_OK means copied into the bounded outgoing queue, not delivered over air.
// Temporary NimBLE backpressure is retried; persistent failure closes the link.
// Disconnect, active-channel unsubscribe and security loss discard queued notifications.
esp_err_t ble_notify(const uint8_t *data, size_t length);
// All-or-nothing enqueue, up to BleNotificationBatchLimit packets. Each packet
// must fit the negotiated MTU minus 3, and BleNotificationMaxBytes. An error
// queues nothing. Data is borrowed only until return. Same thread as ble_notify.
esp_err_t ble_notify_batch(const BleNotification *messages, size_t count);
} // namespace motonav::drivers
