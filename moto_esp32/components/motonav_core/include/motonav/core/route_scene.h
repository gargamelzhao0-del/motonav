#pragma once
#include <cstdint>
#include "motonav/core/map_position_history.h"
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
    bool local_view=false;
    int32_t total_m=-1,total_s=-1;
    // Independent measured position: 0 unavailable, 1 approximate, 2 precise.
    // It never supplies turn instructions or speed.
    uint8_t position_quality=0;
    uint16_t position_accuracy_m=0;
    MapPositionObservation last_position{};
};
inline bool has_last_position(const RouteScene &scene){
    return scene.last_position.quality&&scene.last_position.revision==scene.revision
        &&scene.last_position.age_ms<MapPositionHistory::MaxAgeMs;
}
}
