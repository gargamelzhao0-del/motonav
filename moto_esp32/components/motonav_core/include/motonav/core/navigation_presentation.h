#pragma once
#include "motonav/core/display_model.h"
#include <algorithm>

namespace motonav {
enum class ManeuverEmphasis { Normal, Approach, Immediate };
// Presentation policy consumes existing trusted guidance; it creates no new
// instructions. Hysteresis avoids changing the layout at every metre jitter.
class NavigationPresentation {
public:
    ManeuverEmphasis update(const ScreenSnapshot &state) {
        const auto &g=state.guidance;
        if(screen_mode(state)!=ScreenMode::Live||state.route.overview||g.turn_m<0||!turn(g.icon)) {
            stage_=ManeuverEmphasis::Normal;revision_=0;return stage_;
        }
        if(revision_!=state.route.revision||icon_!=g.icon)stage_=ManeuverEmphasis::Normal;
        revision_=state.route.revision;icon_=g.icon;
        const int speed=std::clamp(g.speed_kmh,0,300);
        const int approach=std::clamp(speed*3,150,280),immediate=std::clamp(speed,50,100);
        if(g.turn_m<=immediate)stage_=ManeuverEmphasis::Immediate;
        else if(stage_==ManeuverEmphasis::Immediate&&g.turn_m<=immediate+20){}
        else if(g.turn_m<=approach)stage_=ManeuverEmphasis::Approach;
        else if(stage_!=ManeuverEmphasis::Normal&&g.turn_m<=approach+25)stage_=ManeuverEmphasis::Approach;
        else stage_=ManeuverEmphasis::Normal;
        return stage_;
    }
private:
    static bool turn(int icon) {
        return (icon>=2&&icon<=8)||icon==11||icon==12||(icon>=17&&icon<=19)||
               (icon>=21&&icon<=28)||icon==65||icon==66;
    }
    ManeuverEmphasis stage_=ManeuverEmphasis::Normal;
    uint32_t revision_=0;
    int icon_=0;
};
}
