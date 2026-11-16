#pragma once
#include <cstdint>
namespace motonav {
struct RoutePoint { int16_t x=0,y=0; };
struct RouteScene {
    uint32_t revision=0;
    uint8_t count=0;
    int16_t x=0,y=0,heading=-1;
    RoutePoint points[64]{};
    // Overview coordinates have no vehicle pose and are uniformly normalized.
    bool overview=false;
    uint8_t waiting_reason=0;
};
}
