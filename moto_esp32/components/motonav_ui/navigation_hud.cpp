#include "motonav/ui/navigation_hud.h"
#include "motonav/ui/dashboard.h"
#include <cmath>

namespace motonav {
namespace {
lv_obj_t *text(lv_obj_t *parent,int x,int y,int width,const lv_font_t *font,uint32_t color) {
    auto *label=lv_label_create(parent);lv_obj_set_pos(label,x,y);lv_obj_set_width(label,width);
    lv_obj_set_style_text_font(label,font,0);lv_obj_set_style_text_color(label,lv_color_hex(color),0);
    lv_obj_set_style_text_align(label,LV_TEXT_ALIGN_CENTER,0);lv_label_set_long_mode(label,LV_LABEL_LONG_DOT);
    lv_obj_set_style_bg_color(label,lv_color_hex(0x182125),0);lv_obj_set_style_bg_opa(label,LV_OPA_COVER,0);
    lv_obj_set_style_radius(label,5,0);return label;
}
}
void create_navigation_hud(lv_obj_t *parent,NavigationHud &hud) {
    hud.root=lv_obj_create(parent);lv_obj_remove_style_all(hud.root);lv_obj_set_size(hud.root,466,466);
    lv_obj_remove_flag(hud.root,static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE));
    auto *card=lv_obj_create(hud.root);lv_obj_remove_style_all(card);lv_obj_set_pos(card,112,44);lv_obj_set_size(card,242,70);
    lv_obj_set_style_bg_color(card,lv_color_hex(0x182125),0);lv_obj_set_style_bg_opa(card,LV_OPA_COVER,0);lv_obj_set_style_radius(card,10,0);
    hud.distance=text(hud.root,179,49,166,&lv_font_montserrat_28,0xf0f1ed);
    hud.maneuver=text(hud.root,179,84,166,&lv_font_montserrat_16,0xe1bc66);
    hud.arrow=lv_line_create(hud.root);lv_obj_set_pos(hud.arrow,119,50);
    lv_obj_set_style_line_color(hud.arrow,lv_color_hex(0xf0c850),0);lv_obj_set_style_line_width(hud.arrow,5,0);
    lv_obj_set_style_line_rounded(hud.arrow,true,0);
    hud.speed=text(hud.root,181,346,104,&lv_font_montserrat_48,0xf3f4ee);
    auto *unit=text(hud.root,196,399,74,&lv_font_montserrat_16,0xd8dddc);lv_label_set_text(unit,"km/h");
    hud.time=text(hud.root,65,363,112,&lv_font_montserrat_20,0xf0f1ed);
    hud.remaining=text(hud.root,65,392,112,&lv_font_montserrat_16,0xc1cdce);
    hud.status=text(hud.root,290,369,112,&lv_font_montserrat_16,0xe1bc66);
    lv_label_set_long_mode(hud.status,LV_LABEL_LONG_WRAP);
    auto *credit=text(hud.root,121,422,224,&lv_font_montserrat_16,0xb5c0c4);lv_label_set_text(credit,"(c) OpenStreetMap");
    const char *letters[]{"N","E","S","W"};
    for(unsigned i=0;i<4;i++) {hud.compass[i]=text(hud.root,0,0,20,&lv_font_montserrat_16,0xe1bc66);lv_label_set_text(hud.compass[i],letters[i]);}
    lv_obj_add_flag(hud.root,LV_OBJ_FLAG_HIDDEN);
}
void update_navigation_hud(NavigationHud &hud,const ScreenSnapshot &state,float heading) {
    const bool live=screen_mode(state)==ScreenMode::Live&&!state.route.overview;
    char value[48];int icon=live?state.guidance.icon:0;
    if(hud.icon!=icon) {
        hud.icon=icon;auto shape=arrow_shape(icon);lv_obj_set_flag(hud.arrow,LV_OBJ_FLAG_HIDDEN,!shape.count);
        for(unsigned i=0;i<shape.count;i++)hud.points[i]={static_cast<lv_value_precise_t>(shape.points[i].x*.33f),static_cast<lv_value_precise_t>(shape.points[i].y*.33f)};
        if(shape.count)lv_line_set_points(hud.arrow,hud.points,shape.count);
    }
    if(live) {
        format_distance(value,state.guidance.turn_m);set_label_text(hud.distance,value);
        set_label_text(hud.maneuver,maneuver(icon));
        if(state.guidance.speed_kmh<0)set_label_text(hud.speed,"--");
        else {std::snprintf(value,sizeof(value),"%d",state.guidance.speed_kmh);set_label_text(hud.speed,value);}
    } else {
        set_label_text(hud.distance,"WAIT GPS");set_label_text(hud.speed,"--");
        set_label_text(hud.maneuver,state.route.waiting_reason==7?"RECALCULATING":state.route.waiting_reason==1?"GPS SIGNAL WEAK":"NO LIVE POSITION");
    }
    int meters=live?state.guidance.remaining_m:state.route.total_m,seconds=live?state.guidance.remaining_s:state.route.total_s;
    format_distance(value,meters);set_label_text(hud.remaining,value);
    if(seconds<0)set_label_text(hud.time,"-- min");
    else {
        unsigned minutes=(seconds+59)/60;
        if(minutes<60)std::snprintf(value,sizeof(value),"%u min",minutes);
        else std::snprintf(value,sizeof(value),"%uh %um",minutes/60,minutes%60);
        set_label_text(hud.time,value);
    }
    set_label_text(hud.status,state.basemap_revision!=state.route.revision?"LOADING\nMAP":!state.basemap_count?"NO MAP\nDETAIL":!state.basemap_complete?"MORE\nDETAIL":live?"LIVE\nNAV":"MAP READY\nNO GPS");
    for(unsigned i=0;i<4;i++) {
        float angle=i*1.570796327f-heading;
        float radius=std::cos(angle)<-.95f?224:205;
        lv_obj_set_pos(hud.compass[i],static_cast<int>(223+std::sin(angle)*radius),static_cast<int>(223-std::cos(angle)*radius));
    }
}
}
