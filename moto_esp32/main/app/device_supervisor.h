#pragma once
namespace motonav::app {
// Call from the application task every 100 ms, outside LVGL callbacks/locks.
void supervise_display();
}
