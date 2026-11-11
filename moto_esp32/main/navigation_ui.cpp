#include "navigation_ui.h"
#include "esp_lv_adapter.h"
#include "esp_timer.h"
#include "esp_log.h"
#include <climits>

namespace motonav {
namespace {
constexpr uint32_t Lime = 0xc4fa64, White = 0xf2f3e9, Muted = 0x89978c, Amber = 0xffc265;
lv_display_t *display;
SnapshotReader snapshot;
const char *name;
lv_obj_t *top, *title, *number, *speed, *remaining, *footer, *arrow, *test_marker;
lv_point_precise_t arrow_points[6];
int last_icon = -1;
uint32_t last_title_color = 0;
bool testing = false, full_frame = false;
uint64_t test_started = 0, last_sample = 0;
uint32_t minimum_fps = UINT32_MAX, samples = 0;
uint64_t total_fps = 0;

uint64_t milliseconds() { return static_cast<uint64_t>(esp_timer_get_time() / 1000); }
void text(lv_obj_t *object, const char *value) {
    if (std::strcmp(lv_label_get_text(object), value)) lv_label_set_text(object, value);
}
lv_obj_t *label(int y, int width, const lv_font_t *font, uint32_t color) {
    lv_obj_t *object = lv_label_create(lv_screen_active());
    lv_obj_set_width(object, width);
    lv_obj_set_style_text_align(object, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_font(object, font, 0);
    lv_obj_set_style_text_color(object, lv_color_hex(color), 0);
    lv_obj_align(object, LV_ALIGN_TOP_MID, 0, y);
    lv_obj_remove_flag(object, LV_OBJ_FLAG_CLICKABLE);
    return object;
}
void draw_arrow(int icon) {
    if (icon == last_icon) return;
    last_icon = icon;
    ArrowShape shape = arrow_shape(icon);
    lv_obj_set_flag(arrow, LV_OBJ_FLAG_HIDDEN, shape.count == 0);
    if (!shape.count) return;
    for (unsigned i = 0; i < shape.count; ++i) {
        arrow_points[i].x = shape.points[i].x; arrow_points[i].y = shape.points[i].y;
    }
    lv_line_set_points(arrow, arrow_points, shape.count);
}
void reset_benchmark() {
    test_started = last_sample = milliseconds();
    minimum_fps = UINT32_MAX; samples = 0; total_fps = 0;
    ESP_ERROR_CHECK(esp_lv_adapter_fps_stats_reset(display));
    text(number, "-- FPS"); text(speed, "WARMING UP");
    text(remaining, "Target: at least 40 FPS");
}
void set_test(bool enabled) {
    testing = enabled;
    lv_obj_set_flag(test_marker, LV_OBJ_FLAG_HIDDEN, !enabled);
    if (enabled) reset_benchmark();
    last_icon = -1;
}
void touch(lv_event_t *event) {
    ScreenSnapshot state = snapshot();
    if (lv_event_get_code(event) == LV_EVENT_LONG_PRESSED && may_test_display(state)) set_test(!testing);
    else if (lv_event_get_code(event) == LV_EVENT_SHORT_CLICKED && testing) {
        full_frame = !full_frame; reset_benchmark();
    }
}
void refresh(lv_timer_t *) {
    ScreenSnapshot state = snapshot();
    ScreenMode mode = screen_mode(state);
    uint64_t now = milliseconds();
    if (testing && !may_test_display(state)) set_test(false);
    if (testing) {
        text(top, "DISPLAY TEST / NOT NAVIGATION");
        text(title, full_frame ? "FULL FRAME" : "PARTIAL HUD");
        text(footer, "Tap: workload  |  Hold: back");
        draw_arrow(9);
        // A 240 x 12 moving marker exercises partial invalidation; no simulated GPS is produced.
        unsigned phase = static_cast<unsigned>((now - test_started) % 2000);
        int y = 145 + static_cast<int>((phase < 1000 ? phase : 2000 - phase) * 110 / 1000);
        lv_obj_set_y(test_marker, y);
        if (full_frame) lv_obj_invalidate(lv_screen_active());
        if (now - last_sample >= 1100) {
            last_sample = now;
            uint32_t fps = 0;
            ESP_ERROR_CHECK(esp_lv_adapter_get_fps(display, &fps));
            char value[80];
            std::snprintf(value, sizeof(value), "%lu FPS", static_cast<unsigned long>(fps)); text(number, value);
            if (now - test_started >= 2500) {
                if (fps < minimum_fps) minimum_fps = fps;
                ++samples; total_fps += fps;
                std::snprintf(value, sizeof(value), "MIN %lu  /  AVG %lu", static_cast<unsigned long>(minimum_fps),
                              static_cast<unsigned long>(total_fps / samples)); text(speed, value);
                std::snprintf(value, sizeof(value), "%lus measured / target 40", static_cast<unsigned long>((now - test_started - 2500) / 1000));
                text(remaining, value);
                if (samples % 5 == 0) ESP_LOGI("display_test", "workload=%s fps=%lu min=%lu avg=%lu samples=%lu",
                    full_frame ? "full" : "partial", static_cast<unsigned long>(fps), static_cast<unsigned long>(minimum_fps),
                    static_cast<unsigned long>(total_fps / samples), static_cast<unsigned long>(samples));
            }
        }
        return;
    }
    text(top, state.linked ? (state.encrypted ? "PHONE CONNECTED" : "PHONE CONNECTING") : name);
    text(footer, mode == ScreenMode::Live ? "MOTONAV" : "Hold screen: display test");
    uint32_t title_color = mode == ScreenMode::Stale || mode == ScreenMode::Waiting ? Amber : Lime;
    if (title_color != last_title_color) {
        lv_obj_set_style_text_color(title, lv_color_hex(title_color), 0);
        last_title_color = title_color;
    }
    char value[80];
    if (mode == ScreenMode::Pairing) {
        text(title, "PAIR WITH PHONE"); draw_arrow(0);
        std::snprintf(value, sizeof(value), "%06lu", static_cast<unsigned long>(state.passkey)); text(number, value);
        text(speed, "Enter PIN on phone"); text(remaining, name); text(footer, "");
    } else if (mode == ScreenMode::Live) {
        const Guidance &g = state.guidance;
        text(title, maneuver(g.icon)); draw_arrow(g.icon);
        format_distance(value, g.turn_m); text(number, value);
        if (g.speed_kmh < 0) text(speed, "-- km/h");
        else { std::snprintf(value, sizeof(value), "%d km/h", g.speed_kmh); text(speed, value); }
        char distance[24]; format_distance(distance, g.remaining_m);
        unsigned minutes = static_cast<unsigned>((g.remaining_s + 59) / 60);
        if (minutes < 60) std::snprintf(value, sizeof(value), "%s left  /  %u min", distance, minutes);
        else std::snprintf(value, sizeof(value), "%s left  /  %uh %um", distance, minutes / 60, minutes % 60);
        text(remaining, value);
    } else {
        draw_arrow(0); text(number, "--"); text(speed, "");
        switch (mode) {
            case ScreenMode::Disconnected: text(title, "CONNECT PHONE"); text(remaining, "In app: Connect screen"); break;
            case ScreenMode::Securing: text(title, "SECURING LINK"); text(remaining, "Confirm Bluetooth pairing"); break;
            case ScreenMode::Handshake: text(title, "CONNECTING"); text(remaining, "Waiting for MotoNav protocol"); break;
            case ScreenMode::Ready: text(title, "READY FOR ROUTE"); text(remaining, "Start navigation on phone"); break;
            case ScreenMode::Waiting: text(title, "WAITING FOR GUIDANCE"); text(remaining, "Check phone location and route"); break;
            case ScreenMode::Stale: text(title, "GUIDANCE EXPIRED"); text(remaining, state.stream_fresh ? "Waiting for a fresh location" : "Waiting for phone updates"); break;
            case ScreenMode::Arrived: text(title, "ARRIVED"); text(number, LV_SYMBOL_OK); text(remaining, "Destination reached"); break;
            case ScreenMode::Stopped: text(title, "NAVIGATION ENDED"); text(remaining, "Start a new route on phone"); break;
            default: break;
        }
    }
}
} // namespace

void create_navigation_ui(lv_display_t *screen_display, const char *device_name, SnapshotReader read) {
    display = screen_display; name = device_name; snapshot = read;
    ESP_ERROR_CHECK(esp_lv_adapter_fps_stats_enable(display, true));
    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x090f0c), 0);
    lv_obj_remove_flag(screen, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(screen, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(screen, touch, LV_EVENT_LONG_PRESSED, nullptr);
    lv_obj_add_event_cb(screen, touch, LV_EVENT_SHORT_CLICKED, nullptr);
    lv_obj_t *rim = lv_obj_create(screen);
    lv_obj_remove_style_all(rim);
    lv_obj_set_size(rim, 438, 438); lv_obj_center(rim);
    lv_obj_set_style_radius(rim, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_border_width(rim, 2, 0);
    lv_obj_set_style_border_color(rim, lv_color_hex(0x25362d), 0);
    lv_obj_remove_flag(rim, static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE));
    top = label(52, 310, &lv_font_montserrat_16, Muted);
    title = label(88, 350, &lv_font_montserrat_28, Lime);
    arrow = lv_line_create(screen); lv_obj_set_pos(arrow, 153, 130);
    lv_obj_set_style_line_width(arrow, 14, 0);
    lv_obj_set_style_line_color(arrow, lv_color_hex(Lime), 0);
    lv_obj_set_style_line_rounded(arrow, true, 0);
    test_marker = lv_obj_create(screen); lv_obj_remove_style_all(test_marker);
    lv_obj_set_size(test_marker, 240, 12); lv_obj_set_x(test_marker, 113);
    lv_obj_set_style_bg_color(test_marker, lv_color_hex(Amber), 0);
    lv_obj_set_style_bg_opa(test_marker, LV_OPA_COVER, 0);
    lv_obj_remove_flag(test_marker, static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE));
    lv_obj_add_flag(test_marker, LV_OBJ_FLAG_HIDDEN);
    number = label(266, 330, &lv_font_montserrat_48, White);
    speed = label(330, 340, &lv_font_montserrat_28, White);
    remaining = label(374, 330, &lv_font_montserrat_20, Muted);
    footer = label(405, 285, &lv_font_montserrat_16, Muted);
#if CONFIG_MOTONAV_BOOT_DISPLAY_TEST
    set_test(true);
#endif
    refresh(nullptr);
    lv_timer_create(refresh, 16, nullptr);
}
} // namespace motonav
