#pragma once
#include "motonav/ui/navigation_ui.h"

namespace motonav::testing {
struct BenchmarkServices {
    uint64_t (*now_us)();
    void (*reset_fps)(lv_display_t *);
    uint32_t (*read_fps)(lv_display_t *);
    void (*profile)(bool reset) = nullptr;
};
// Optional singleton feature; attach before creating navigation UI, while locked.
// Timing/FPS services are injected. This module never controls panel/touch/BLE.
UiExtension make_display_benchmark(lv_display_t *display, SnapshotReader reader,
                                   BenchmarkServices services);
} // namespace motonav::testing
