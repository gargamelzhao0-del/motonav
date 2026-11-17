#include "motonav/ui/navigation_ui.h"
#include "motonav/ui/dashboard.h"
#include "motonav/ui/navigation_hud.h"
#include "motonav/ui/map_annotations.h"
#include "motonav/graphics/route_map.h"
#include "motonav/graphics/map_camera_motion.h"
#include "motonav/graphics/map_camera_profile.h"
#include "motonav/core/navigation_presentation.h"
#include <initializer_list>

namespace motonav {
namespace {
constexpr uint32_t Lime = 0xc4fa64, Amber = 0xffc265;
SnapshotReader snapshot;
UiExtension extension;
Dashboard view;
NavigationHud hud;
MapAnnotations annotations;
BasemapScene basemap;
MapFeature features[BasemapScene::Capacity]{};
bool local_layout=false;
const char *name;
uint32_t last_title_color = 0;
lv_obj_t *map_view=nullptr;
bool map_layout=false;
bool map_live=false;
int map_marker=-1;
uint32_t map_revision=0;
MapCamera camera{};
MapCameraMotion camera_motion;
RouteTrace route_trace;
NavigationPresentation presentation;
ManeuverEmphasis map_emphasis=ManeuverEmphasis::Normal;
bool protected_road=false;
bool map_failed=false;
void layout(bool enabled) {
    if(enabled==map_layout)return;
    map_layout=enabled; view.last_icon=-1; view.arrow_scale=enabled?0.35f:1;
    lv_obj_set_pos(view.arrow,enabled?105:153,enabled?95:130);
    lv_obj_set_style_line_width(view.arrow,enabled?5:14,0);
    lv_obj_set_y(view.top,enabled?40:52); lv_obj_set_width(view.top,enabled?230:310);
    lv_obj_set_y(view.title,enabled?68:88); lv_obj_set_width(view.title,enabled?266:350);
    lv_obj_set_style_text_font(view.title,enabled?&lv_font_montserrat_20:&lv_font_montserrat_28,0);
    lv_label_set_long_mode(view.title,enabled?LV_LABEL_LONG_DOT:LV_LABEL_LONG_WRAP);
    lv_obj_set_y(view.number,enabled?99:266); lv_obj_set_width(view.number,enabled?210:330);
    lv_obj_set_style_text_font(view.number,enabled?&lv_font_montserrat_28:&lv_font_montserrat_48,0);
    lv_obj_align(view.number,LV_ALIGN_TOP_MID,enabled?30:0,enabled?99:266);
    lv_obj_set_y(view.speed,enabled?361:330); lv_obj_set_width(view.speed,enabled?250:340);
    lv_obj_set_y(view.remaining,enabled?400:374); lv_obj_set_width(view.remaining,enabled?270:330);
    lv_obj_set_style_text_font(view.remaining,enabled?&lv_font_montserrat_16:&lv_font_montserrat_20,0);
    lv_obj_set_flag(view.footer,LV_OBJ_FLAG_HIDDEN,enabled);
    for(auto *label:{view.top,view.title,view.number,view.speed,view.remaining}) {
        lv_obj_set_style_bg_color(label,lv_color_hex(0x090f0c),0);
        lv_obj_set_style_bg_opa(label,enabled?LV_OPA_COVER:LV_OPA_TRANSP,0);
        lv_obj_set_style_radius(label,enabled?6:0,0);
    }
}
void route_map(const ScreenSnapshot &state,ManeuverEmphasis emphasis) {
    const auto mode=screen_mode(state);
    bool visible=state.linked && state.encrypted && state.handshaken && !state.pairing &&
        mode!=ScreenMode::Stopped && mode!=ScreenMode::Arrived && state.route.count>=2;
    if(!visible) {
        if(map_view) { lv_obj_delete(map_view); map_view=nullptr;annotations={}; }
        map_revision=0;basemap={};camera_motion={};route_trace={};
        map_failed=false; map_live=false;map_marker=-1; layout(false); return;
    }
    if(!map_view && !map_failed) {
        map_view=create_route_map(lv_obj_get_parent(view.top),466,466);
        if(map_view) { lv_obj_set_pos(map_view,0,0); lv_obj_move_to_index(map_view,1);create_map_annotations(map_view,annotations); }
        else map_failed=true;
    }
    if(!map_view) { layout(false); return; }
    layout(true);
    const auto &r=state.route;
    const bool live=mode==ScreenMode::Live && !r.overview;
    const bool historical=!live&&!r.position_quality&&has_last_position(r);
    const int marker=live?3:historical?4:r.position_quality;
    const bool marker_changed=map_marker!=marker;map_marker=marker;
    const bool changed_mode=live!=map_live;
    map_live=live;
    bool base_changed=false;
    if(basemap.revision&&basemap.revision!=r.revision){basemap={};base_changed=true;}
    if(state.basemap_revision==r.revision&&(basemap.revision!=r.revision||basemap.generation!=state.basemap_generation||basemap.labels.generation!=state.basemap_labels_generation)&&snapshot.read_map&&snapshot.read_map(snapshot.context,r.revision,basemap)) {
        for(unsigned i=0;i<basemap.count;i++) {const auto &p=basemap.items[i];features[i]={p.kind,{p.a.x,p.a.y},{p.b.x,p.b.y},{p.c.x,p.c.y}};}
        base_changed=true;
    }
    if(!live&&!r.local_view) {
        lv_obj_add_flag(annotations.root,LV_OBJ_FLAG_HIDDEN);
        if(map_revision==r.revision && !changed_mode)return;
        map_revision=r.revision;
        // Fit both normalized plans and legacy metre windows without implying a
        // current position. Uniform scaling preserves the route's proportions.
        int left=r.points[0].x,right=left,bottom=r.points[0].y,top=bottom;
        for(unsigned i=1;i<r.count;i++) {
            left=std::min(left,int(r.points[i].x));right=std::max(right,int(r.points[i].x));
            bottom=std::min(bottom,int(r.points[i].y));top=std::max(top,int(r.points[i].y));
        }
        const float extent=std::max(1,std::max(right-left,top-bottom));
        const float factor=2000/extent;
        const float width=std::max(1.0f,(right-left)*factor),height=std::max(1.0f,(top-bottom)*factor);
        const float scale=std::min(4.0f,std::min(310/width,230/height));
        MapPoint points[64];
        for(unsigned i=0;i<r.count;i++)points[i]={
            static_cast<int16_t>(std::lround((r.points[i].x-(left+right)*0.5f)*factor)),
            static_cast<int16_t>(std::lround((r.points[i].y-(bottom+top)*0.5f)*factor))};
        camera={0,(248-466*0.7f)/scale,0,scale};
        if(!render_route_map(map_view,camera,{points,r.count,nullptr,0,false})) {
            lv_obj_delete(map_view);map_view=nullptr;annotations={};map_failed=true;layout(false);
        }
        return;
    }
    lv_obj_remove_flag(annotations.root,LV_OBJ_FLAG_HIDDEN);
    const int position_x=historical?r.last_position.x:r.x,position_y=historical?r.last_position.y:r.y;
    const int bearing=historical?r.last_position.heading:r.heading;
    float heading=bearing<0?camera.heading:static_cast<float>(bearing)*0.001745329252f;
    const bool road=live&&emphasis!=ManeuverEmphasis::Immediate&&state.details.revision==r.revision&&state.details.current_road[0];
    bool changed=base_changed||marker_changed||emphasis!=map_emphasis||road!=protected_road;
    map_emphasis=emphasis;protected_road=road;
    uint32_t now=lv_tick_get();
    MapPoint points[64];
    for(unsigned i=0;i<r.count;i++)points[i]={r.points[i].x,r.points[i].y};
    const bool reset=map_revision!=r.revision||changed_mode||marker_changed;
    if(reset){route_trace.prepare(points,r.count);changed=true;}
    map_revision=r.revision;
    // Bearing becomes unstable near standstill. Hold the already displayed
    // heading without manufacturing a new position or extending its validity.
    if(live&&!reset&&state.guidance.speed_kmh>=0&&state.guidance.speed_kmh<4)heading=camera.heading;
    MapCamera target=map_camera_profile(r.local_view,live,state.guidance.speed_kmh);
    target.x=position_x;target.y=position_y;target.heading=heading;
    MapCamera next=camera_motion.update(target,now,reset,route_trace,live&&r.local_view);
    const auto progress=live&&r.local_view?route_trace.locate(next.x,next.y):RouteTrace::Cursor{};
    changed=changed||std::abs(next.x-camera.x)>0.001f||std::abs(next.y-camera.y)>0.001f||std::abs(next.heading-camera.heading)>0.0001f||std::abs(next.perspective-camera.perspective)>0.0001f||std::abs(next.scale-camera.scale)>0.0001f||std::abs(next.anchor_y-camera.anchor_y)>0.0001f;
    camera=next;
    if(changed) {
        if(!render_route_map(map_view,camera,{points,r.count,nullptr,0,marker!=0,features,basemap.count,r.local_view,
                static_cast<uint8_t>(live?0:historical?3:r.position_quality),progress.valid?progress.index:-1,
                (uint64_t(r.revision)<<32)|basemap.generation})) {
            lv_obj_delete(map_view); map_view=nullptr;annotations={}; map_failed=true; layout(false);
        }
    }
    if(map_view&&(changed||annotations.layout_pending)) {
        const bool approach=emphasis!=ManeuverEmphasis::Normal,immediate=emphasis==ManeuverEmphasis::Immediate;
        update_map_annotations(annotations,basemap.labels,camera,points,r.count,marker!=0,
            {immediate?2u:approach?5u:8u,immediate?4u:approach?6u:12u,approach?155:140,road});
    }
}
void touch(lv_event_t *event) {
    if (extension.gesture) extension.gesture(extension.context,
        lv_event_get_code(event) == LV_EVENT_LONG_PRESSED ? UiGesture::Hold : UiGesture::Tap, snapshot());
}
void refresh(lv_timer_t *) {
    ScreenSnapshot state = snapshot();
    if (extension.refresh && extension.refresh(extension.context, state)) return;
    ScreenMode mode = screen_mode(state);
    const auto emphasis=presentation.update(state);
    route_map(state,emphasis);
    bool local=map_layout&&state.route.local_view;
    if(local!=local_layout) {
        local_layout=local;
        lv_obj_set_flag(hud.root,LV_OBJ_FLAG_HIDDEN,!local);
        for(auto *object:{view.top,view.title,view.number,view.speed,view.remaining,view.footer,view.arrow})lv_obj_set_flag(object,LV_OBJ_FLAG_HIDDEN,local);
        view.last_icon=-1;
    }
    if(local){update_navigation_hud(hud,state,camera.heading,emphasis);update_map_attribution(hud,basemap.labels.sources);return;}
    set_label_text(view.top, state.linked ? (state.encrypted ? "PHONE CONNECTED" : "PHONE CONNECTING") : name);
    const bool overview=map_layout && mode!=ScreenMode::Live;
    lv_obj_set_flag(view.number,LV_OBJ_FLAG_HIDDEN,overview);
    lv_obj_set_flag(view.speed,LV_OBJ_FLAG_HIDDEN,overview);
    if(overview)set_label_text(view.top,"ROUTE OVERVIEW");
    set_label_text(view.footer, mode == ScreenMode::Live ? "MOTONAV" : (extension.idle_hint ? extension.idle_hint : "MOTONAV"));
    uint32_t title_color = overview || mode == ScreenMode::Stale || mode == ScreenMode::Waiting ? Amber : Lime;
    if (title_color != last_title_color) {
        lv_obj_set_style_text_color(view.title, lv_color_hex(title_color), 0);
        last_title_color = title_color;
    }
    char value[80];
    if (mode == ScreenMode::Pairing) {
        set_label_text(view.title, "PAIR WITH PHONE"); dashboard_arrow(view, 0);
        std::snprintf(value, sizeof(value), "%06lu", static_cast<unsigned long>(state.passkey)); set_label_text(view.number, value);
        set_label_text(view.speed, "Enter PIN on phone"); set_label_text(view.remaining, name); set_label_text(view.footer, "");
    } else if(overview) {
        dashboard_arrow(view,0);
        const char *status=mode==ScreenMode::Stale?"GUIDANCE EXPIRED":"WAITING FOR GUIDANCE";
        if(mode!=ScreenMode::Stale) {
            switch(state.route.waiting_reason) {
                case 1: status="GPS SIGNAL WEAK";break;
                case 2: case 3: case 6: status="WAITING FOR GPS";break;
                case 7: status="RECALCULATING";break;
                default: break;
            }
        }
        set_label_text(view.title,status);
        set_label_text(view.remaining,"Planned route / no live position");
    } else if (mode == ScreenMode::Live) {
        const Guidance &g = state.guidance;
        set_label_text(view.title, maneuver(g.icon)); dashboard_arrow(view, g.icon);
        format_distance(value, g.turn_m); set_label_text(view.number, value);
        if (g.speed_kmh < 0) set_label_text(view.speed, "-- km/h");
        else { std::snprintf(value, sizeof(value), "%d km/h", g.speed_kmh); set_label_text(view.speed, value); }
        char distance[24]; format_distance(distance, g.remaining_m);
        unsigned minutes = static_cast<unsigned>((g.remaining_s + 59) / 60);
        if (minutes < 60) std::snprintf(value, sizeof(value), "%s left  /  %u min", distance, minutes);
        else std::snprintf(value, sizeof(value), "%s left  /  %uh %um", distance, minutes / 60, minutes % 60);
        set_label_text(view.remaining, value);
    } else {
        dashboard_arrow(view, 0); set_label_text(view.number, "--"); set_label_text(view.speed, "");
        switch (mode) {
            case ScreenMode::Disconnected: set_label_text(view.title, "CONNECT PHONE"); set_label_text(view.remaining, "In app: Connect screen"); break;
            case ScreenMode::Securing: set_label_text(view.title, "SECURING LINK"); set_label_text(view.remaining, "Confirm Bluetooth pairing"); break;
            case ScreenMode::Handshake: set_label_text(view.title, "CONNECTING"); set_label_text(view.remaining, "Waiting for MotoNav protocol"); break;
            case ScreenMode::Ready: set_label_text(view.title, "READY FOR ROUTE"); set_label_text(view.remaining, "Start navigation on phone"); break;
            case ScreenMode::Waiting: set_label_text(view.title, "WAITING FOR GUIDANCE"); set_label_text(view.remaining, "Check phone location and route"); break;
            case ScreenMode::Stale: set_label_text(view.title, "GUIDANCE EXPIRED"); set_label_text(view.remaining, state.stream_fresh ? "Waiting for a fresh location" : "Waiting for phone updates"); break;
            case ScreenMode::Arrived: set_label_text(view.title, "ARRIVED"); set_label_text(view.number, LV_SYMBOL_OK); set_label_text(view.remaining, "Destination reached"); break;
            case ScreenMode::Stopped: set_label_text(view.title, "NAVIGATION ENDED"); set_label_text(view.remaining, "Start a new route on phone"); break;
            default: break;
        }
    }
}
} // namespace

void create_navigation_ui(lv_display_t *display, const char *device_name,
                          SnapshotReader read, UiExtension feature) {
    name = device_name; snapshot = read; extension = feature;
    lv_obj_t *screen = lv_display_get_screen_active(display);
    create_dashboard(screen, view);
    create_navigation_hud(screen,hud);
    if (extension.gesture) {
        lv_obj_add_event_cb(screen, touch, LV_EVENT_LONG_PRESSED, nullptr);
        lv_obj_add_event_cb(screen, touch, LV_EVENT_SHORT_CLICKED, nullptr);
    }
    refresh(nullptr);
    lv_timer_create(refresh, 16, nullptr);
}
} // namespace motonav
