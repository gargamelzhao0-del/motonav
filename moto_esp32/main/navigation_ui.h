#pragma once
#include "display_model.h"
#include "lvgl.h"

namespace motonav {
using SnapshotReader = ScreenSnapshot (*)();
void create_navigation_ui(lv_display_t *display, const char *device_name, SnapshotReader read);
}
