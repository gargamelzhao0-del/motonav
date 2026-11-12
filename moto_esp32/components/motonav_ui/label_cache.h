#pragma once
namespace motonav {
// Optional renderer for unchanged, opaque, centered, single-line labels.
// LVGL owns layout/backgrounds and handles all unsupported styles normally.
void initialize_label_cache();
}
