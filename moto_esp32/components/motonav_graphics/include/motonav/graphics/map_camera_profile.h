#pragma once
#include "motonav/graphics/map_projection.h"
namespace motonav {
// Local ground view only. Waiting uses an explicit fixed framing preset, not a
// fabricated speed. The caller supplies the observed focus and valid bearing.
inline MapCamera map_camera_profile(bool local,bool live,int speed_kmh) {
    if(!local)return {0,0,0,1};
    const float speed=float(std::max(0,speed_kmh));
    const float zoom=live?1.02f+std::clamp((speed-10)/70,0.0f,1.0f)*.65f:1.13f;
    const float tilt=.610865238f*(live?std::clamp((speed-6)/16,0.0f,1.0f):1.0f);
    return {0,0,0,1/zoom,tilt,(286+12*tilt/.610865238f)/466};
}
}
