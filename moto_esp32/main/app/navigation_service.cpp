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
        case drivers::BleEventType::Security: session_.security(input.value != 0); break;
    }
    xSemaphoreGive(mutex_);
}
drivers::BleReceiveResult NavigationService::receive(const uint8_t *bytes, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        char reply[32];
        xSemaphoreTake(mutex_, portMAX_DELAY);
        bool allowed = session_.can_receive();
        bool complete = session_.feed(static_cast<char>(bytes[i]), drivers::monotonic_ms(), reply);
        xSemaphoreGive(mutex_);
        if (!allowed) return drivers::BleReceiveResult::Rejected;
        if (!complete) continue;
        esp_err_t result = drivers::ble_notify(reinterpret_cast<const uint8_t *>(reply), std::strlen(reply));
        if (result == ESP_ERR_NO_MEM) return drivers::BleReceiveResult::NoMemory;
        if (result != ESP_OK) return drivers::BleReceiveResult::Rejected;
    }
    return drivers::BleReceiveResult::Accepted;
}
} // namespace motonav::app
