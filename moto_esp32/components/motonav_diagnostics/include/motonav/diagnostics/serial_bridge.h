#pragma once
#include "motonav/diagnostics/protocol.h"
#include "motonav/drivers/ble_transport.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include <atomic>

namespace motonav::diagnostics {
// No navigation or UI dependency. The BLE task validates/copies only; a low
// priority worker formats and writes serial output. Queue admission never waits.
class SerialBridge {
public:
    esp_err_t init();
    drivers::BleDiagnosticCallbacks callbacks();
private:
    struct Item { Snapshot snapshot; uint32_t epoch; uint64_t received_ms; };
    drivers::BleReceiveResult receive(const uint8_t *data, size_t length);
    void reset();
    void run();
    QueueHandle_t queue_ = nullptr;
    Assembler assembler_;
    std::atomic<uint32_t> epoch_{0}, dropped_{0}, rejected_{0};
    uint32_t last_session_ = 0, last_sequence_ = 0;
    uint64_t last_accepted_ms_ = 0;
    bool have_accepted_ = false;
};
} // namespace motonav::diagnostics
