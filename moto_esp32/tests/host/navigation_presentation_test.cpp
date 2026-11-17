#include "motonav/core/navigation_presentation.h"
#include <cassert>
#include <iostream>
using namespace motonav;
int main(){
    ScreenSnapshot state;state.linked=state.encrypted=state.subscribed=state.handshaken=true;
    state.has_guidance=state.stream_fresh=true;state.route.revision=1;
    state.guidance={Live,3,320,4000,600,36};
    NavigationPresentation policy;
    auto distance=[&](int meters){state.guidance.turn_m=meters;return policy.update(state);};
    assert(distance(320)==ManeuverEmphasis::Normal);
    assert(distance(150)==ManeuverEmphasis::Approach);
    assert(distance(165)==ManeuverEmphasis::Approach);
    assert(distance(176)==ManeuverEmphasis::Normal);
    assert(distance(50)==ManeuverEmphasis::Immediate);
    assert(distance(65)==ManeuverEmphasis::Immediate);
    assert(distance(71)==ManeuverEmphasis::Approach);
    state.guidance.speed_kmh=100;assert(distance(250)==ManeuverEmphasis::Approach);
    assert(distance(90)==ManeuverEmphasis::Immediate);
    state.source_expired=true;assert(distance(20)==ManeuverEmphasis::Normal);
    state.source_expired=false;state.guidance.mode=Waiting;assert(distance(20)==ManeuverEmphasis::Normal);
    state.guidance.mode=Live;state.guidance.icon=9;assert(distance(20)==ManeuverEmphasis::Normal);
    state.guidance.icon=47;assert(distance(20)==ManeuverEmphasis::Normal);
    state.guidance.icon=8;assert(distance(20)==ManeuverEmphasis::Immediate);
    state.route.overview=true;assert(distance(20)==ManeuverEmphasis::Normal);
    state.route.overview=false;state.guidance.speed_kmh=36;assert(distance(50)==ManeuverEmphasis::Immediate);
    state.guidance.icon=2;assert(distance(65)==ManeuverEmphasis::Approach);
    assert(distance(50)==ManeuverEmphasis::Immediate);state.route.revision++;
    assert(distance(65)==ManeuverEmphasis::Approach);
    assert(distance(-1)==ManeuverEmphasis::Normal);
    std::cout<<"PASS: maneuver emphasis, speed adaptation, hysteresis and stale/unknown guards\n";
}
