#include "motonav/ui/navigation_hud.h"
#include "motonav/ui/dashboard.h"
#include "motonav/ui/fonts.h"
#include "map_caption.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace motonav {
namespace {
const char *chinese_maneuver(int icon) {
    switch(icon) {
        case 2:return "左转";case 3:return "右转";
        case 4:return "向左前方";case 5:return "向右前方";
        case 6:return "向左后方";case 7:return "向右后方";
        case 8:case 19:return "掉头";case 9:return "直行";
        case 10:return "途经点";case 11:case 17:return "进入环岛";
        case 12:case 18:return "驶出环岛";case 13:return "服务区";
        case 14:return "收费站";case 15:return "目的地";
        case 16:return "隧道";case 20:return "沿路行驶";
        case 21:case 25:return "环岛左转";case 22:case 26:return "环岛右转";
        case 23:case 27:return "环岛直行";case 24:case 28:return "环岛掉头";
        case 65:return "靠左行驶";case 66:return "靠右行驶";
        default:return "查看手机";
    }
}
lv_obj_t *text(lv_obj_t *parent,int x,int y,int width,const lv_font_t *font,uint32_t color) {
    auto *label=lv_label_create(parent);lv_obj_set_pos(label,x,y);lv_obj_set_width(label,width);
    lv_obj_set_style_text_font(label,font,0);lv_obj_set_style_text_color(label,lv_color_hex(color),0);
    lv_obj_set_style_text_align(label,LV_TEXT_ALIGN_CENTER,0);lv_label_set_long_mode(label,LV_LABEL_LONG_DOT);
    lv_obj_set_style_bg_opa(label,LV_OPA_TRANSP,0);return label;
}
}
void create_navigation_hud(lv_obj_t *parent,NavigationHud &hud) {
    hud.root=lv_obj_create(parent);lv_obj_remove_style_all(hud.root);lv_obj_set_size(hud.root,466,466);
    lv_obj_remove_flag(hud.root,static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE));
    auto *card=hud.card=lv_obj_create(hud.root);lv_obj_remove_style_all(card);lv_obj_set_pos(card,126,44);lv_obj_set_size(card,214,64);
    lv_obj_set_style_bg_color(card,lv_color_hex(0x060909),0);lv_obj_set_style_bg_opa(card,LV_OPA_COVER,0);
    auto *icon=hud.icon_background=lv_obj_create(hud.root);lv_obj_remove_style_all(icon);lv_obj_set_pos(icon,126,44);lv_obj_set_size(icon,58,64);
    lv_obj_set_style_bg_color(icon,lv_color_hex(0x2b2716),0);lv_obj_set_style_bg_opa(icon,LV_OPA_COVER,0);
    hud.distance=text(hud.root,196,43,137,&motonav_distance_36,0xffffff);
    lv_obj_set_style_text_align(hud.distance,LV_TEXT_ALIGN_LEFT,0);
    hud.unit=text(hud.root,278,67,45,&motonav_small_14,0x9aa2a5);
    lv_obj_set_style_text_align(hud.unit,LV_TEXT_ALIGN_LEFT,0);
    hud.maneuver=text(hud.root,196,84,137,&motonav_ui_16,0xd6d9d6);
    lv_obj_set_style_text_align(hud.maneuver,LV_TEXT_ALIGN_LEFT,0);
    hud.progress=lv_obj_create(hud.root);lv_obj_remove_style_all(hud.progress);lv_obj_set_pos(hud.progress,184,105);lv_obj_set_size(hud.progress,0,3);
    lv_obj_set_style_bg_color(hud.progress,lv_color_hex(0xf0c850),0);lv_obj_set_style_bg_opa(hud.progress,LV_OPA_COVER,0);
    hud.arrow=lv_line_create(hud.root);lv_obj_set_pos(hud.arrow,129,49);
    lv_obj_set_style_line_color(hud.arrow,lv_color_hex(0xf0c850),0);lv_obj_set_style_line_width(hud.arrow,5,0);
    lv_obj_set_style_line_rounded(hud.arrow,true,0);
    hud.speed=text(hud.root,178,345,110,&motonav_speed_66,0xffffff);
    auto *unit=text(hud.root,196,409,74,&motonav_small_14,0x9aa2a5);lv_label_set_text(unit,"KM/H");
    hud.time=text(hud.root,65,354,103,&lv_font_montserrat_20,0xffffff);
    hud.remaining=text(hud.root,65,384,103,&motonav_ui_16,0x9aa2a5);
    lv_obj_set_style_text_align(hud.time,LV_TEXT_ALIGN_RIGHT,0);lv_obj_set_style_text_align(hud.remaining,LV_TEXT_ALIGN_RIGHT,0);
    auto *limit=lv_obj_create(hud.root);lv_obj_remove_style_all(limit);lv_obj_set_pos(limit,297,361);lv_obj_set_size(limit,42,42);
    lv_obj_set_style_bg_color(limit,lv_color_hex(0xf5f5f3),0);lv_obj_set_style_bg_opa(limit,LV_OPA_COVER,0);
    lv_obj_set_style_border_color(limit,lv_color_hex(0xe5484d),0);lv_obj_set_style_border_width(limit,5,0);lv_obj_set_style_radius(limit,LV_RADIUS_CIRCLE,0);
    hud.speed_limit=text(hud.root,300,368,36,&lv_font_montserrat_20,0x111111);lv_label_set_text(hud.speed_limit,"--");
    auto *limit_name=text(hud.root,294,405,48,&motonav_ui_16,0x9aa2a5);lv_label_set_text(limit_name,"限速");
    hud.status=text(hud.root,113,112,240,&motonav_ui_16,0xe1bc66);
    hud.current_road=create_map_caption(hud.root);
    hud.road_accent=lv_obj_create(hud.root);lv_obj_remove_style_all(hud.road_accent);
    lv_obj_set_pos(hud.road_accent,405,303);lv_obj_set_size(hud.road_accent,2,20);
    lv_obj_set_style_bg_color(hud.road_accent,lv_color_hex(0xf0c850),0);lv_obj_set_style_bg_opa(hud.road_accent,LV_OPA_COVER,0);
    auto *credit=text(hud.root,143,437,180,&motonav_small_14,0x9aa2a5);lv_label_set_text(credit,"© OpenStreetMap");
    const char *letters[]{"N","E","S","W"};
    for(unsigned i=0;i<4;i++) {
        hud.compass[i]=text(hud.root,0,0,20,&motonav_small_14,i?0x899194:0xffffff);lv_label_set_text(hud.compass[i],letters[i]);
        if(!i){lv_obj_set_style_bg_color(hud.compass[i],lv_color_hex(0x0d0f10),0);lv_obj_set_style_bg_opa(hud.compass[i],LV_OPA_COVER,0);lv_obj_set_style_radius(hud.compass[i],LV_RADIUS_CIRCLE,0);}
    }
    lv_obj_add_flag(hud.root,LV_OBJ_FLAG_HIDDEN);
}
void update_navigation_hud(NavigationHud &hud,const ScreenSnapshot &state,float heading,ManeuverEmphasis emphasis) {
    const bool live=screen_mode(state)==ScreenMode::Live&&!state.route.overview;
    if(!live)emphasis=ManeuverEmphasis::Normal;
    const bool approach=emphasis!=ManeuverEmphasis::Normal,immediate=emphasis==ManeuverEmphasis::Immediate;
    if(hud.emphasis!=emphasis) {
        hud.emphasis=emphasis;hud.icon=-1;
        lv_obj_set_pos(hud.card,approach?110:126,approach?40:44);lv_obj_set_size(hud.card,approach?246:214,approach?80:64);
        lv_obj_set_pos(hud.icon_background,approach?110:126,approach?40:44);lv_obj_set_size(hud.icon_background,approach?70:58,approach?80:64);
        lv_obj_set_pos(hud.arrow,approach?114:129,approach?45:49);
        lv_obj_set_pos(hud.distance,approach?192:196,approach?40:43);lv_obj_set_width(hud.distance,approach?157:137);
        lv_obj_set_pos(hud.maneuver,approach?192:196,approach?93:84);lv_obj_set_width(hud.maneuver,approach?157:137);
        lv_obj_set_y(hud.unit,approach?64:67);lv_obj_set_y(hud.status,approach?124:112);
        lv_obj_set_pos(hud.progress,approach?180:184,approach?117:105);
        const uint32_t color=immediate?0xff944d:0xf0c850;
        lv_obj_set_style_line_color(hud.arrow,lv_color_hex(color),0);
        lv_obj_set_style_bg_color(hud.progress,lv_color_hex(color),0);
        lv_obj_set_style_text_color(hud.distance,lv_color_hex(immediate?0xffbd78:0xffffff),0);
        lv_obj_set_flag(hud.time,LV_OBJ_FLAG_HIDDEN,immediate);lv_obj_set_flag(hud.remaining,LV_OBJ_FLAG_HIDDEN,immediate);
    }
    char value[128];int icon=live?state.guidance.icon:0;
    const bool details=live&&state.details.revision==state.route.revision&&state.details.revision;
    if(hud.live!=live){hud.live=live;lv_obj_set_style_text_font(hud.distance,live?&motonav_distance_36:&motonav_ui_16,0);}
    if(hud.icon!=icon) {
        hud.icon=icon;auto shape=arrow_shape(icon);lv_obj_set_flag(hud.arrow,LV_OBJ_FLAG_HIDDEN,!shape.count);
        const float scale=approach?.42f:.33f;
        for(unsigned i=0;i<shape.count;i++)hud.points[i]={static_cast<lv_value_precise_t>(shape.points[i].x*scale),static_cast<lv_value_precise_t>(shape.points[i].y*scale)};
        if(shape.count)lv_line_set_points(hud.arrow,hud.points,shape.count);
    }
    if(live) {
        format_distance(value,state.guidance.turn_m);char *separator=std::strchr(value,' ');
        if(separator){*separator=0;set_label_text(hud.unit,separator+1);}else set_label_text(hud.unit,"");
        if(std::strcmp(lv_label_get_text(hud.distance),value)){
            set_label_text(hud.distance,value);
            lv_point_t measured{};lv_text_get_size(&measured,value,&motonav_distance_36,0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
            lv_obj_set_x(hud.unit,std::min<int>(305,200+measured.x));
        }
        if(details&&state.details.next_road[0])std::snprintf(value,sizeof(value),"%s · %s",chinese_maneuver(icon),state.details.next_road);
        else std::snprintf(value,sizeof(value),"%s",chinese_maneuver(icon));
        set_label_text(hud.maneuver,value);
        if(state.guidance.speed_kmh<0)set_label_text(hud.speed,"--");
        else {std::snprintf(value,sizeof(value),"%d",state.guidance.speed_kmh);set_label_text(hud.speed,value);}
    } else {
        set_label_text(hud.distance,state.route.position_quality?"等待指引":"等待定位");set_label_text(hud.unit,"");set_label_text(hud.speed,"--");
        set_label_text(hud.maneuver,state.route.waiting_reason==7?"正在重算路线":state.route.waiting_reason==1?"GPS 信号弱":state.route.position_quality?"等待导航指令":"无实时位置");
    }
    const char *road=details?state.details.current_road:"";
    if(std::strcmp(hud.road_name,road)){
        if(*road){
            lv_point_t measured{};lv_text_get_size(&measured,road,&motonav_ui_16,0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
            const int width=std::clamp<int>(measured.x+4,16,139);
            if(set_map_caption(hud.current_road,road,width,0xf2f3e9)){
                lv_obj_set_pos(hud.current_road,401-width,301);std::snprintf(hud.road_name,sizeof(hud.road_name),"%s",road);
            }
        }else hud.road_name[0]=0;
    }
    lv_obj_set_flag(hud.current_road,LV_OBJ_FLAG_HIDDEN,immediate||!details||!state.details.current_road[0]||std::strcmp(hud.road_name,road));
    lv_obj_set_flag(hud.road_accent,LV_OBJ_FLAG_HIDDEN,immediate||!details||!state.details.current_road[0]);
    if(details&&state.details.speed_limit>0)std::snprintf(value,sizeof(value),"%d",state.details.speed_limit);
    else std::snprintf(value,sizeof(value),"--");
    set_label_text(hud.speed_limit,value);
    lv_obj_set_width(hud.progress,live&&state.guidance.turn_m>=0&&state.guidance.turn_m<400?(approach?176:156)*(400-state.guidance.turn_m)/400:0);
    int meters=live?state.guidance.remaining_m:state.route.total_m,seconds=live?state.guidance.remaining_s:state.route.total_s;
    format_distance(value,meters);set_label_text(hud.remaining,value);
    if(details&&state.details.eta_minute>=0){std::snprintf(value,sizeof(value),"%02d:%02d",state.details.eta_minute/60,state.details.eta_minute%60);set_label_text(hud.time,value);}
    else if(seconds<0)set_label_text(hud.time,"-- min");
    else {
        unsigned minutes=(seconds+59)/60;
        if(minutes<60)std::snprintf(value,sizeof(value),"%u min",minutes);
        else std::snprintf(value,sizeof(value),"%uh %um",minutes/60,minutes%60);
        set_label_text(hud.time,value);
    }
    const char *status=state.basemap_revision!=state.route.revision?"正在加载底图":!state.basemap_count?"此处暂无地图细节":!state.basemap_complete?"正在补充地图细节":live?"":"底图已就绪 · 等待位置";
    if(!live&&state.route.position_quality){
        std::snprintf(value,sizeof(value),"%s · 精度约 %u m",state.route.position_quality==1?"大致位置":"已定位",state.route.position_accuracy_m);
        status=value;
    }else if(!live&&has_last_position(state.route)){
        std::snprintf(value,sizeof(value),"上次位置 · 约 %lu 秒前",static_cast<unsigned long>((state.route.last_position.age_ms+999)/1000));
        status=value;
    }
    set_label_text(hud.status,status);
    for(unsigned i=0;i<4;i++) {
        float angle=i*1.570796327f-heading;
        float radius=222;
        lv_obj_set_pos(hud.compass[i],static_cast<int>(223+std::sin(angle)*radius),static_cast<int>(223-std::cos(angle)*radius));
    }
}
}
