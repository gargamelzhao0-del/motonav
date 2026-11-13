#pragma once
#include "motonav/core/display_model.h"
#include "lvgl.h"

namespace motonav {
struct SnapshotReader {
    ScreenSnapshot (*read)(void *) = nullptr;
    void *context = nullptr;
    ScreenSnapshot operator()() const { return read ? read(context) : ScreenSnapshot{}; }
};
enum class UiGesture { Tap, Hold };
// Optional modal feature. Called under the LVGL lock. Returning true suspends
// navigation rendering for this tick; the extension owns its own screen/objects.
// This interface has no dependency on any concrete extension or test module.
struct UiExtension {
    void *context = nullptr;
    bool (*refresh)(void *, const ScreenSnapshot &) = nullptr;
    void (*gesture)(void *, UiGesture, const ScreenSnapshot &) = nullptr;
    const char *idle_hint = nullptr;
};
// One UI per boot. Name and callback contexts must outlive the UI.
// All functions run on the LVGL thread or while the display lock is held.
void create_navigation_ui(lv_display_t *display, const char *device_name,
                          SnapshotReader read, UiExtension extension = {});
} // namespace motonav
