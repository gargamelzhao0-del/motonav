#include "map_fixture.h"
#include <cmath>

namespace motonav {
void render_map_benchmark(lv_obj_t *view, uint64_t elapsed_ms) {
    static constexpr MapPoint route[]{{0,-600},{0,-120},{240,-120},{240,240},{-120,240},{-120,480},{360,480}};
    static const auto roads = [] {
        struct Roads { MapSegment segments[22]; } result{};
        for (int i=0; i<11; ++i) {
            int16_t coordinate = static_cast<int16_t>(i*120-600);
            result.segments[i] = {{-720,coordinate},{720,coordinate}};
            result.segments[i+11] = {{coordinate,-720},{coordinate,720}};
        }
        return result;
    }();
    float t = static_cast<float>(elapsed_ms % 120000) / 1000.0f;
    MapCamera camera{60*std::sin(t*0.3f),85*std::sin(t*0.2f),0.5f*std::sin(t*0.16f),0.7f};
    render_route_map(view, camera, {route,sizeof(route)/sizeof(route[0]),roads.segments,22});
}
} // namespace motonav
