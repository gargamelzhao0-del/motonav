#include "map_fixture.h"
#include "map_fixture_data.h"
#include "motonav/graphics/route_trace.h"
#include <cmath>

namespace motonav {
void render_map_benchmark_labels(MapAnnotations &view,const MapCamera &camera,MapAnnotationOptions options) {
    update_map_annotations(view,RealMapLabels,camera,RealMapRoute,sizeof(RealMapRoute)/sizeof(RealMapRoute[0]),true,options);
}
MapCamera render_map_benchmark(lv_obj_t *view, uint64_t elapsed_ms) {
    // Traverse the decoded road itself at the HUD's test speed (36 km/h).
    // Independent sine-wave camera motion made the fixed vehicle marker drift
    // away from the displayed route, despite otherwise correct projection.
    static_assert(FirstSegment<StartSegment&&StartSegment<EndSegment);
    static_assert(EndSegment<sizeof(RealMapRoute)/sizeof(RealMapRoute[0]));
    float lengths[EndSegment-FirstSegment]{},total=0,start=0;
    for(unsigned i=FirstSegment;i<EndSegment;i++){
        float dx=RealMapRoute[i+1].x-RealMapRoute[i].x,dy=RealMapRoute[i+1].y-RealMapRoute[i].y;
        lengths[i-FirstSegment]=std::sqrt(dx*dx+dy*dy);total+=lengths[i-FirstSegment];
        if(i<StartSegment)start=total;
    }
    float distance=std::fmod(start+float(elapsed_ms%1000000)*.01f,2*total);
    const bool reverse=distance>total;if(reverse)distance=2*total-distance;
    unsigned segment=0;
    while(segment<EndSegment-FirstSegment-1&&distance>lengths[segment])distance-=lengths[segment++];
    const auto a=RealMapRoute[segment+FirstSegment],b=RealMapRoute[segment+FirstSegment+1];
    const float fraction=lengths[segment]>0?distance/lengths[segment]:0;
    const float dx=b.x-a.x,dy=b.y-a.y;
    MapCamera camera{a.x+dx*fraction,a.y+dy*fraction,std::atan2(reverse?-dx:dx,reverse?-dy:dy),
        .88f,.610865238f,298.0f/466};
    static const RouteTrace trace=[] {RouteTrace value;value.prepare(RealMapRoute,sizeof(RealMapRoute)/sizeof(RealMapRoute[0]));return value;}();
    auto progress=trace.locate(camera.x,camera.y);
    render_route_map(view,camera,{RealMapRoute,sizeof(RealMapRoute)/sizeof(RealMapRoute[0]),nullptr,0,true,
        RealMapFeatures,sizeof(RealMapFeatures)/sizeof(RealMapFeatures[0]),true,0,progress.valid?progress.index:-1,1});
    return camera;
}
} // namespace motonav
