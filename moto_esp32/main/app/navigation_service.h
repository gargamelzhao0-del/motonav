#pragma once
#include "motonav/core/navigation_session.h"
#include "motonav/drivers/ble_transport.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

namespace motonav::app {
// Sole owner of protocol state. BLE callbacks and UI snapshot reads are
// serialized; neither LVGL nor notifications run while this mutex is held.
class NavigationService {
public:
    esp_err_t init();
    ScreenSnapshot snapshot();
    drivers::BleCallbacks callbacks();
private:
    void event(const drivers::BleEvent &event);
    drivers::BleReceiveResult receive(const uint8_t *bytes, size_t length);
    NavigationSession session_;
    SemaphoreHandle_t mutex_ = nullptr;
};
} // namespace motonav::app
