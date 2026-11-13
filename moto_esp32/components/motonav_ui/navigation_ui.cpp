#include "motonav/ui/navigation_ui.h"
#include "motonav/ui/dashboard.h"
#include "motonav/graphics/route_map.h"
#include <initializer_list>

namespace motonav {
namespace {
constexpr uint32_t Lime = 0xc4fa64, Amber = 0xffc265;
SnapshotReader snapshot;
UiExtension extension;
Dashboard view;
const char *name;
uint32_t last_title_color = 0;
lv_obj_t *map_view=nullptr;
bool map_layout=false;
uint32_t map_revision=0,transition_at=0;
MapCamera camera{},from_camera{},target_camera{};
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
void route_map(const ScreenSnapshot &state) {
    bool visible=screen_mode(state)==ScreenMode::Live && state.route.count>=2;
    if(!visible) {
        if(map_view) { lv_obj_delete(map_view); map_view=nullptr; }
        map_revision=0; map_failed=false; layout(false); return;
    }
    if(!map_view && !map_failed) {
        map_view=create_route_map(lv_obj_get_parent(view.top),466,466);
        if(map_view) { lv_obj_set_pos(map_view,0,0); lv_obj_move_to_index(map_view,1); }
        else map_failed=true;
    }
    if(!map_view) { layout(false); return; }
    layout(true);
    const auto &r=state.route;
    float heading=r.heading<0?0:static_cast<float>(r.heading)*0.001745329252f;
    bool changed=false;
    uint32_t now=lv_tick_get();
    if(map_revision!=r.revision) {
        map_revision=r.revision; camera={static_cast<float>(r.x),static_cast<float>(r.y),heading,1};
        from_camera=target_camera=camera; transition_at=now; changed=true;
    } else if(target_camera.x!=r.x || target_camera.y!=r.y || std::abs(target_camera.heading-heading)>0.0001f) {
        from_camera=camera; target_camera={static_cast<float>(r.x),static_cast<float>(r.y),heading,1}; transition_at=now;
    }
    float fraction=std::min(1.0f,static_cast<float>(now-transition_at)/900);
    float turn=std::remainder(target_camera.heading-from_camera.heading,6.283185307f);
    MapCamera next{from_camera.x+(target_camera.x-from_camera.x)*fraction,
        from_camera.y+(target_camera.y-from_camera.y)*fraction,from_camera.heading+turn*fraction,1};
    next.heading=std::remainder(next.heading,6.283185307f);
    changed=changed||std::abs(next.x-camera.x)>0.001f||std::abs(next.y-camera.y)>0.001f||std::abs(next.heading-camera.heading)>0.0001f;
    camera=next;
    if(changed) {
        MapPoint points[64];
        for(unsigned i=0;i<r.count;i++)points[i]={r.points[i].x,r.points[i].y};
        if(!render_route_map(map_view,camera,{points,r.count,nullptr,0})) {
            lv_obj_delete(map_view); map_view=nullptr; map_failed=true; layout(false);
        }
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
    route_map(state);
    set_label_text(view.top, state.linked ? (state.encrypted ? "PHONE CONNECTED" : "PHONE CONNECTING") : name);
    set_label_text(view.footer, mode == ScreenMode::Live ? "MOTONAV" : (extension.idle_hint ? extension.idle_hint : "MOTONAV"));
    uint32_t title_color = mode == ScreenMode::Stale || mode == ScreenMode::Waiting ? Amber : Lime;
    if (title_color != last_title_color) {
        lv_obj_set_style_text_color(view.title, lv_color_hex(title_color), 0);
        last_title_color = title_color;
    }
    char value[80];
    if (mode == ScreenMode::Pairing) {
        set_label_text(view.title, "PAIR WITH PHONE"); dashboard_arrow(view, 0);
        std::snprintf(value, sizeof(value), "%06lu", static_cast<unsigned long>(state.passkey)); set_label_text(view.number, value);
        set_label_text(view.speed, "Enter PIN on phone"); set_label_text(view.remaining, name); set_label_text(view.footer, "");
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
    if (extension.gesture) {
        lv_obj_add_event_cb(screen, touch, LV_EVENT_LONG_PRESSED, nullptr);
        lv_obj_add_event_cb(screen, touch, LV_EVENT_SHORT_CLICKED, nullptr);
    }
    refresh(nullptr);
    lv_timer_create(refresh, 16, nullptr);
}
} // namespace motonav
