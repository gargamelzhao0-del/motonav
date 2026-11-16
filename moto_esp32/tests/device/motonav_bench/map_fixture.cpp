#include "map_fixture.h"
#include "real_map_fixture.h"
#include <cmath>

namespace motonav {
void render_map_benchmark(lv_obj_t *view, uint64_t elapsed_ms) {
    float t=static_cast<float>(elapsed_ms%120000)/1000.0f;
    MapCamera camera{60*std::sin(t*.3f),85*std::sin(t*.2f),.5f*std::sin(t*.16f),.7f,.25f};
    render_route_map(view,camera,{RealMapRoute,sizeof(RealMapRoute)/sizeof(RealMapRoute[0]),nullptr,0,true,
        RealMapFeatures,sizeof(RealMapFeatures)/sizeof(RealMapFeatures[0]),true});
}
} // namespace motonav
