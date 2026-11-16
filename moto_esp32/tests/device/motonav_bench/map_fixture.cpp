#include "map_fixture.h"
#include "real_map_fixture.h"
#include <cmath>

namespace motonav {
MapCamera render_map_benchmark(lv_obj_t *view, uint64_t elapsed_ms) {
    float t=static_cast<float>(elapsed_ms%120000)/1000.0f;
    MapCamera camera{RealMapCamera.x+60*std::sin(t*.3f),RealMapCamera.y+85*std::sin(t*.2f),
        RealMapHeading+.5f*std::sin(t*.16f),.88f,.872664626f,306.0f/466};
    render_route_map(view,camera,{RealMapRoute,sizeof(RealMapRoute)/sizeof(RealMapRoute[0]),nullptr,0,true,
        RealMapFeatures,sizeof(RealMapFeatures)/sizeof(RealMapFeatures[0]),true});
    return camera;
}
} // namespace motonav
