#pragma once

namespace motonav::drivers {
// Panel transport only. LVGL and application drawing remain RGB565_SWAPPED.
enum class DisplayColor { Rgb565, Rgb332 };
}
