#include "motonav/diagnostics/serial_bridge.h"
#include "motonav/drivers/platform.h"
#include "freertos/task.h"
#include "esp_log.h"

namespace motonav::diagnostics {
esp_err_t SerialBridge::init() {
    if (queue_) return ESP_ERR_INVALID_STATE;
    queue_ = xQueueCreate(4, sizeof(Item));
    if (!queue_) return ESP_ERR_NO_MEM;
    if (xTaskCreate([](void *p) { static_cast<SerialBridge *>(p)->run(); }, "phone_diag", 4096, this, 1, nullptr) != pdPASS) {
        vQueueDelete(queue_); queue_ = nullptr; return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}
drivers::BleDiagnosticCallbacks SerialBridge::callbacks() {
    return {this, [](void *p, const uint8_t *data, size_t size) {
        return static_cast<SerialBridge *>(p)->receive(data, size);
    }, [](void *p) { static_cast<SerialBridge *>(p)->reset(); }};
}
void SerialBridge::reset() {
    assembler_.reset(); ++epoch_;
    last_session_ = 0; last_sequence_ = 0; have_accepted_ = false;
}
drivers::BleReceiveResult SerialBridge::receive(const uint8_t *data, size_t length) {
    if (!queue_) return drivers::BleReceiveResult::Rejected;
    const uint64_t now = drivers::monotonic_ms();
    Item item{};
    // Keep the same zero-consumption-on-error contract as the BLE interface:
    // an ATT retry of the final fragment must work after temporary queue pressure.
    Assembler candidate = assembler_;
    auto result = candidate.feed(data, length, now, item.snapshot);
    if (result == Assembler::Result::Invalid) { ++rejected_; return drivers::BleReceiveResult::Rejected; }
    if (result == Assembler::Result::Incomplete) { assembler_ = candidate; return drivers::BleReceiveResult::Accepted; }
    if (last_session_ == item.snapshot.session && item.snapshot.sequence <= last_sequence_) {
        ++rejected_; return drivers::BleReceiveResult::Rejected;
    }
    // Receiver-side bound also protects against a malfunctioning authenticated client.
    if (have_accepted_ && now - last_accepted_ms_ < 500) { ++dropped_; return drivers::BleReceiveResult::NoMemory; }
    item.epoch = epoch_.load(); item.received_ms = now;
    if (xQueueSend(queue_, &item, 0) != pdTRUE) { ++dropped_; return drivers::BleReceiveResult::NoMemory; }
    assembler_ = candidate;
    last_session_ = item.snapshot.session; last_sequence_ = item.snapshot.sequence;
    last_accepted_ms_ = now; have_accepted_ = true;
    return drivers::BleReceiveResult::Accepted;
}
void SerialBridge::run() {
    Item item{};
    char line[1200];
    while (true) {
        if (xQueueReceive(queue_, &item, portMAX_DELAY) != pdTRUE || item.epoch != epoch_.load()) continue;
        if (format_json(item.snapshot, item.received_ms, dropped_.load(), rejected_.load(), line, sizeof(line)))
            ESP_LOGI("phone_diag", "%s", line);
    }
}
} // namespace motonav::diagnostics
