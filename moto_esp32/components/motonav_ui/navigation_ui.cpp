#include "motonav/ui/navigation_ui.h"
#include "motonav/ui/dashboard.h"

namespace motonav {
namespace {
constexpr uint32_t Lime = 0xc4fa64, Amber = 0xffc265;
SnapshotReader snapshot;
UiExtension extension;
Dashboard view;
const char *name;
uint32_t last_title_color = 0;
void touch(lv_event_t *event) {
    if (extension.gesture) extension.gesture(extension.context,
        lv_event_get_code(event) == LV_EVENT_LONG_PRESSED ? UiGesture::Hold : UiGesture::Tap, snapshot());
}
void refresh(lv_timer_t *) {
    ScreenSnapshot state = snapshot();
    if (extension.refresh && extension.refresh(extension.context, state)) return;
    ScreenMode mode = screen_mode(state);
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
