#include "device_supervisor.h"
#include "motonav/drivers/display_status.h"
#include "motonav/drivers/platform.h"
#include "esp_log.h"

namespace motonav::app {
void supervise_display() {
    const auto status = drivers::display_status();
    if (status.state != drivers::DisplayState::Faulted) return;
    ESP_LOGE("motonav", "Display fault=%u error=%d; restarting device",
             static_cast<unsigned>(status.fault), status.error);
    drivers::restart_device();
}
}
