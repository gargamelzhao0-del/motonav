#include "navigation_service.h"
#include "motonav/drivers/platform.h"

namespace motonav::app {
void NavigationService::ReleaseStorage::operator()(Storage *memory) const {
    if(memory){memory->~Storage();drivers::free_external(memory);}
}
esp_err_t NavigationService::init() {
    if (mutex_) return ESP_ERR_INVALID_STATE;
    auto *memory=drivers::allocate_external(sizeof(Storage));
    if(!memory)return ESP_ERR_NO_MEM;
    storage_.reset(new(memory) Storage{});
    mutex_ = xSemaphoreCreateMutex();
    if(!mutex_)storage_.reset();
    return mutex_ ? ESP_OK : ESP_ERR_NO_MEM;
}
ScreenSnapshot NavigationService::snapshot() {
    xSemaphoreTake(mutex_, portMAX_DELAY);
    auto result = storage_->session.snapshot(drivers::monotonic_ms());
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
bool NavigationService::read_map(uint32_t revision,BasemapScene &out) {
    xSemaphoreTake(mutex_,portMAX_DELAY);
    bool result=storage_->session.read_map(revision,out);
    xSemaphoreGive(mutex_);return result;
}
void NavigationService::event(const drivers::BleEvent &input) {
    xSemaphoreTake(mutex_, portMAX_DELAY);
    switch (input.type) {
        case drivers::BleEventType::Connected: storage_->session.connected(); break;
        case drivers::BleEventType::Disconnected: storage_->session.disconnected(); break;
        case drivers::BleEventType::Subscription: storage_->session.subscription(input.value != 0); break;
        case drivers::BleEventType::PairingPin: storage_->session.pairing(input.value); break;
        case drivers::BleEventType::Protocol: storage_->session.protocol(input.value); break;
        case drivers::BleEventType::Security: storage_->session.security(input.value != 0); break;
    }
    xSemaphoreGive(mutex_);
}
drivers::BleReceiveResult NavigationService::receive(const uint8_t *bytes, size_t length) {
    if (!bytes || !length || length > 512) return drivers::BleReceiveResult::Rejected;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    storage_->candidate = storage_->session;
    xSemaphoreGive(mutex_);
    if (!storage_->candidate.can_receive()) return drivers::BleReceiveResult::Rejected;

    // Events and receives are serialized by the BLE host. Snapshot readers
    // can keep reading the committed state while this bounded batch is parsed.
    const uint64_t now = drivers::monotonic_ms();
    size_t count = 0;
    for (size_t i = 0; i < length; ++i) {
        char reply[32];
        if (!storage_->candidate.feed(static_cast<char>(bytes[i]), now, reply)) continue;
        if (count == drivers::BleNotificationBatchLimit) return drivers::BleReceiveResult::NoMemory;
        const size_t size = std::strlen(reply);
        std::memcpy(replies_[count], reply, size);
        messages_[count] = {reinterpret_cast<const uint8_t *>(replies_[count]), size};
        ++count;
    }
    if (count) {
        // This operation copies the whole batch or queues nothing. The worker
        // cannot send until this BLE callback returns and state is committed.
        const esp_err_t result = drivers::ble_notify_batch(messages_, count);
        if (result == ESP_ERR_NO_MEM) return drivers::BleReceiveResult::NoMemory;
        if (result != ESP_OK) return drivers::BleReceiveResult::Rejected;
    }
    xSemaphoreTake(mutex_, portMAX_DELAY);
    storage_->session = storage_->candidate;
    xSemaphoreGive(mutex_);
    return drivers::BleReceiveResult::Accepted;
}
} // namespace motonav::app
