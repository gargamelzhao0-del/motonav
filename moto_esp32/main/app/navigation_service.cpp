#include "navigation_service.h"
#include "motonav/drivers/platform.h"

namespace motonav::app {
esp_err_t NavigationService::init() {
    if (mutex_) return ESP_ERR_INVALID_STATE;
    mutex_ = xSemaphoreCreateMutex();
    return mutex_ ? ESP_OK : ESP_ERR_NO_MEM;
}
ScreenSnapshot NavigationService::snapshot() {
    xSemaphoreTake(mutex_, portMAX_DELAY);
    auto result = session_.snapshot(drivers::monotonic_ms());
    xSemaphoreGive(mutex_);
    return result;
}
drivers::BleCallbacks NavigationService::callbacks() {
    return {this,
        [](void *context, const drivers::BleEvent &input) {
            static_cast<NavigationService *>(context)->event(input);
        },
        [](void *context, const uint8_t *data, size_t size) {
            return static_cast<NavigationService *>(context)->receive(data, size);
        }};
}
void NavigationService::event(const drivers::BleEvent &input) {
    xSemaphoreTake(mutex_, portMAX_DELAY);
    switch (input.type) {
        case drivers::BleEventType::Connected: session_.connected(); break;
        case drivers::BleEventType::Disconnected: session_.disconnected(); break;
        case drivers::BleEventType::Subscription: session_.subscription(input.value != 0); break;
        case drivers::BleEventType::PairingPin: session_.pairing(input.value); break;
        case drivers::BleEventType::Protocol: session_.protocol(input.value); break;
        case drivers::BleEventType::Security: session_.security(input.value != 0); break;
    }
    xSemaphoreGive(mutex_);
}
drivers::BleReceiveResult NavigationService::receive(const uint8_t *bytes, size_t length) {
    if (!bytes || !length || length > 512) return drivers::BleReceiveResult::Rejected;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    candidate_ = session_;
    xSemaphoreGive(mutex_);
    if (!candidate_.can_receive()) return drivers::BleReceiveResult::Rejected;

    // Events and receives are serialized by the BLE host. Snapshot readers
    // can keep reading the committed state while this bounded batch is parsed.
    const uint64_t now = drivers::monotonic_ms();
    drivers::BleNotification messages[drivers::BleNotificationBatchLimit];
    size_t count = 0;
    for (size_t i = 0; i < length; ++i) {
        char reply[32];
        if (!candidate_.feed(static_cast<char>(bytes[i]), now, reply)) continue;
        if (count == drivers::BleNotificationBatchLimit) return drivers::BleReceiveResult::NoMemory;
        const size_t size = std::strlen(reply);
        std::memcpy(replies_[count], reply, size);
        messages[count] = {reinterpret_cast<const uint8_t *>(replies_[count]), size};
        ++count;
    }
    if (count) {
        // This operation copies the whole batch or queues nothing. The worker
        // cannot send until this BLE callback returns and state is committed.
        const esp_err_t result = drivers::ble_notify_batch(messages, count);
        if (result == ESP_ERR_NO_MEM) return drivers::BleReceiveResult::NoMemory;
        if (result != ESP_OK) return drivers::BleReceiveResult::Rejected;
    }
    xSemaphoreTake(mutex_, portMAX_DELAY);
    session_ = candidate_;
    xSemaphoreGive(mutex_);
    return drivers::BleReceiveResult::Accepted;
}
} // namespace motonav::app
