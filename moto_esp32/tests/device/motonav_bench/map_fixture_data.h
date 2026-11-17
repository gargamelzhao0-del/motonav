#pragma once
#if MOTONAV_BENCH_LUXIAO
#include "luxiao_map_fixture.h"
static constexpr unsigned FirstSegment=0, EndSegment=8, StartSegment=6;
#else
#include "real_map_fixture.h"
static constexpr unsigned FirstSegment=2, EndSegment=23, StartSegment=9;
#endif
