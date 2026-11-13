#pragma once
#include "motonav/testing/display_benchmark.h"

namespace motonav::testing {
// Hardware-only diagnostic adapter. Kept entirely out of production builds.
BenchmarkServices driver_benchmark_services(lv_display_t *display);
}
