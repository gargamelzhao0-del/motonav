#pragma once
#include "motonav/core/display_model.h"
#include "motonav/core/navigation_presentation.h"
#include "lvgl.h"
namespace motonav {
struct NavigationHud {
    lv_obj_t *root=nullptr,*distance=nullptr,*maneuver=nullptr,*speed=nullptr,*time=nullptr,*remaining=nullptr,*status=nullptr,*arrow=nullptr;
    lv_obj_t *compass[4]{};
    lv_obj_t *unit=nullptr,*progress=nullptr;
    lv_obj_t *current_road=nullptr,*road_accent=nullptr,*speed_limit=nullptr;
    lv_obj_t *card=nullptr,*icon_background=nullptr;
    lv_point_precise_t points[ArrowShape::Capacity]{};
    int icon=-1;
    int live=-1;
    ManeuverEmphasis emphasis=ManeuverEmphasis::Normal;
    char road_name[64]{};
};
void create_navigation_hud(lv_obj_t *parent,NavigationHud &hud);
void update_navigation_hud(NavigationHud &hud,const ScreenSnapshot &state,float heading,
                           ManeuverEmphasis emphasis=ManeuverEmphasis::Normal);
}
