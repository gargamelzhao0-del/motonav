#pragma once
#include "motonav/core/navigation_session.h"
#include "motonav/drivers/ble_transport.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include <memory>

namespace motonav::app {
// Sole owner of protocol state. BLE callbacks and UI snapshot reads are
// serialized; neither LVGL nor notifications run while this mutex is held.
class NavigationService {
public:
    esp_err_t init();
    ScreenSnapshot snapshot();
    bool read_map(uint32_t revision,BasemapScene &out);
    drivers::BleCallbacks callbacks();
private:
    void event(const drivers::BleEvent &event);
    drivers::BleReceiveResult receive(const uint8_t *bytes, size_t length);
    struct Storage { NavigationSession session,candidate; };
    struct ReleaseStorage { void operator()(Storage *memory) const; };
    std::unique_ptr<Storage,ReleaseStorage> storage_;
    SemaphoreHandle_t mutex_ = nullptr;
    // Only the serialized BLE receive callback uses this scratch space. Keep
    // the worst-case reply batch off the small NimBLE task stack.
    char replies_[drivers::BleNotificationBatchLimit][32]{};
    drivers::BleNotification messages_[drivers::BleNotificationBatchLimit]{};
};
} // namespace motonav::app
