#include "navigation_ui.h"
#include "route_map.h"
#include "esp_lv_adapter.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#include <climits>
#include <initializer_list>
#ifndef MOTONAV_BENCH_SECONDS
#define MOTONAV_BENCH_SECONDS 43
#endif

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
enum class Workload { Partial, Full, Map };
Workload workload = Workload::Partial;
bool testing = false, automatic_test = false;
lv_obj_t *map_canvas = nullptr;
uint64_t test_started = 0, last_sample = 0;
uint32_t minimum_fps = UINT32_MAX, samples = 0;
uint64_t total_fps = 0;
uint64_t map_render_us = 0, map_render_max_us = 0, map_frames = 0;

const char *workload_name() {
    return workload == Workload::Map ? "map466x466" : workload == Workload::Full ? "full" : "partial";
}
void benchmark_layout() {
    bool map = testing && workload == Workload::Map;
    if (map && !map_canvas) {
        map_canvas = create_route_map(lv_screen_active(), 466, 466);
        if (map_canvas) {
            lv_obj_set_pos(map_canvas, 0, 0);
            lv_obj_move_to_index(map_canvas, 1); // Above the rim, behind every HUD label.
        }
    } else if (!map && map_canvas) {
        lv_obj_delete(map_canvas); map_canvas = nullptr;
    }
    lv_obj_set_flag(speed, LV_OBJ_FLAG_HIDDEN, map);
    lv_obj_set_flag(remaining, LV_OBJ_FLAG_HIDDEN, map);
    lv_obj_set_flag(arrow, LV_OBJ_FLAG_HIDDEN, map);
    lv_obj_set_flag(test_marker, LV_OBJ_FLAG_HIDDEN, !testing || map);
    lv_obj_set_y(title, map ? 78 : 88);
    lv_obj_set_style_text_font(title, map ? &lv_font_montserrat_20 : &lv_font_montserrat_28, 0);
    lv_obj_set_y(number, map ? 390 : 266);
    lv_obj_set_style_text_font(number, map ? &lv_font_montserrat_20 : &lv_font_montserrat_48, 0);
    lv_obj_set_y(footer, map ? 422 : 405);
    lv_obj_set_width(footer, map ? 180 : 285);
    for (auto *overlay : {top, title, number, footer}) {
        lv_obj_set_style_bg_color(overlay, lv_color_hex(0x090f0c), 0);
        lv_obj_set_style_bg_opa(overlay, map ? LV_OPA_COVER : LV_OPA_TRANSP, 0);
        lv_obj_set_style_radius(overlay, map ? 6 : 0, 0);
    }
    last_icon = -1;
}

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
void create_rim(lv_obj_t *screen) {
    // The decorative ring never changes. Cache its antialiasing once instead
    // of recomputing a full circular mask for every damaged display strip.
    // Cover the entire screen so LVGL can skip filling the background again
    // beneath the cached image, including the 14-pixel margin around the ring.
    auto *buffer = lv_draw_buf_create(466, 466, LV_COLOR_FORMAT_RGB565, LV_STRIDE_AUTO);
    lv_obj_t *rim;
    if (buffer) {
        rim = lv_canvas_create(screen);
        lv_obj_remove_style_all(rim);
        lv_canvas_set_draw_buf(rim, buffer);
        lv_canvas_fill_bg(rim, lv_color_hex(0x090f0c), LV_OPA_COVER);
        lv_layer_t layer;
        lv_canvas_init_layer(rim, &layer);
        lv_draw_rect_dsc_t border;
        lv_draw_rect_dsc_init(&border);
        border.bg_opa = LV_OPA_TRANSP;
        border.radius = LV_RADIUS_CIRCLE;
        border.border_width = 2;
        border.border_color = lv_color_hex(0x25362d);
        lv_area_t area{14, 14, 451, 451};
        lv_draw_rect(&layer, &border, &area);
        lv_canvas_finish_layer(rim, &layer);
        lv_obj_add_event_cb(rim, [](lv_event_t *event) {
            auto *owned_buffer = static_cast<lv_draw_buf_t *>(lv_event_get_user_data(event));
            lv_image_cache_drop(owned_buffer);
            lv_image_set_src(lv_event_get_target_obj(event), nullptr);
            lv_draw_buf_destroy(owned_buffer);
        }, LV_EVENT_DELETE, buffer);
    } else {
        rim = lv_obj_create(screen);
        lv_obj_remove_style_all(rim);
        lv_obj_set_style_radius(rim, LV_RADIUS_CIRCLE, 0);
        lv_obj_set_style_border_width(rim, 2, 0);
        lv_obj_set_style_border_color(rim, lv_color_hex(0x25362d), 0);
    }
    const int size = buffer ? 466 : 438;
    lv_obj_set_size(rim, size, size); lv_obj_center(rim);
    lv_obj_remove_flag(rim, static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE));
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
    benchmark_layout();
    test_started = last_sample = milliseconds();
    minimum_fps = UINT32_MAX; samples = 0; total_fps = 0;
    map_render_us = map_render_max_us = map_frames = 0;
    ESP_ERROR_CHECK(esp_lv_adapter_fps_stats_reset(display));
    text(number, "-- FPS"); text(speed, "WARMING UP");
    text(remaining, "Target: at least 40 FPS");
    ESP_LOGI("display_test", "started workload=%s automatic=%d map_buffer=%s", workload_name(), automatic_test,
             workload == Workload::Map ? (map_canvas ? "ready" : "allocation_failed") : "unused");
}
void report_benchmark() {
    if (!samples) return;
    ESP_LOGI("display_test", "completed workload=%s min=%lu avg=%lu samples=%lu measured_ms=%llu",
        workload_name(), static_cast<unsigned long>(minimum_fps),
        static_cast<unsigned long>(total_fps / samples), static_cast<unsigned long>(samples),
        static_cast<unsigned long long>(milliseconds() - test_started - 2500));
    if (map_frames) ESP_LOGI("map_profile", "frames=%llu prepare_avg_us=%llu prepare_max_us=%llu",
        static_cast<unsigned long long>(map_frames), static_cast<unsigned long long>(map_render_us / map_frames),
        static_cast<unsigned long long>(map_render_max_us));
}
void set_test(bool enabled) {
    if (!enabled && testing) {
        report_benchmark();
        ESP_LOGI("display_test", "stopped automatic=%d", automatic_test);
    }
    testing = enabled;
    if (!enabled) automatic_test = false;
    if (enabled) reset_benchmark();
    else benchmark_layout();
    last_icon = -1;
}
void touch(lv_event_t *event) {
    if (automatic_test) {
        ESP_LOGI("display_test", "touch ignored during automatic workload");
        return;
    }
    ScreenSnapshot state = snapshot();
    if (lv_event_get_code(event) == LV_EVENT_LONG_PRESSED && may_test_display(state)) {
        automatic_test = false; set_test(!testing);
    }
    else if (lv_event_get_code(event) == LV_EVENT_SHORT_CLICKED && testing) {
        automatic_test = false; report_benchmark();
        workload = static_cast<Workload>((static_cast<unsigned>(workload) + 1) % 3); reset_benchmark();
    }
}
void refresh(lv_timer_t *) {
    ScreenSnapshot state = snapshot();
    ScreenMode mode = screen_mode(state);
    uint64_t now = milliseconds();
    if (testing && !may_test_display(state)) set_test(false);
    if (testing && automatic_test && now - test_started >= MOTONAV_BENCH_SECONDS * 1000ULL) {
        if (workload != Workload::Map) {
            report_benchmark();
            workload = static_cast<Workload>(static_cast<unsigned>(workload) + 1); reset_benchmark();
        } else {
            set_test(false);
            ESP_LOGI("display_test", "automatic test finished; normal navigation display restored");
        }
    }
    // A workload change can create its canvas and reset counters after the
    // timestamp above. Refresh it before unsigned elapsed-time calculations.
    now = milliseconds();
    if (testing) {
        text(top, "DISPLAY TEST / NOT NAVIGATION");
        bool map = workload == Workload::Map;
        text(title, map ? "ROUTE MAP / SIMULATED" : workload == Workload::Full ? "FULL FRAME" : "PARTIAL HUD");
        text(footer, automatic_test ? "AUTO TEST" : map ? "Tap next / Hold exit" : "Tap: workload  |  Hold: back");
        if (map) {
            if (!map_canvas) { text(title, "MAP MEMORY ERROR"); return; }
            uint64_t begin = static_cast<uint64_t>(esp_timer_get_time());
            render_map_benchmark(map_canvas, now - test_started);
            uint64_t duration = static_cast<uint64_t>(esp_timer_get_time()) - begin;
            if (now - test_started >= 2500) {
                map_render_us += duration; ++map_frames;
                if (duration > map_render_max_us) map_render_max_us = duration;
            }
        } else {
            draw_arrow(9);
            // A 240 x 12 moving marker exercises partial invalidation; no simulated GPS is produced.
            unsigned phase = static_cast<unsigned>((now - test_started) % 2000);
            int y = 145 + static_cast<int>((phase < 1000 ? phase : 2000 - phase) * 110 / 1000);
            lv_obj_set_y(test_marker, y);
            if (workload == Workload::Full) lv_obj_invalidate(lv_screen_active());
        }
        if (now - last_sample >= 1100) {
            last_sample = now;
            uint32_t fps = 0;
            ESP_ERROR_CHECK(esp_lv_adapter_get_fps(display, &fps));
            char value[80];
            std::snprintf(value, sizeof(value), "%lu FPS", static_cast<unsigned long>(fps)); text(number, value);
            if (now - test_started >= 2500) {
                if (fps < minimum_fps) minimum_fps = fps;
                ++samples; total_fps += fps;
                if (map) {
                    std::snprintf(value, sizeof(value), "%lu FPS / MIN %lu", static_cast<unsigned long>(fps),
                                  static_cast<unsigned long>(minimum_fps)); text(number, value);
                }
                std::snprintf(value, sizeof(value), "MIN %lu  /  AVG %lu", static_cast<unsigned long>(minimum_fps),
                              static_cast<unsigned long>(total_fps / samples)); text(speed, value);
                std::snprintf(value, sizeof(value), "%lus measured / target 40", static_cast<unsigned long>((now - test_started - 2500) / 1000));
                text(remaining, value);
                if (samples % 5 == 0) ESP_LOGI("display_test", "workload=%s fps=%lu min=%lu avg=%lu samples=%lu",
                    workload_name(), static_cast<unsigned long>(fps), static_cast<unsigned long>(minimum_fps),
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
    create_rim(screen);
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
#if CONFIG_MOTONAV_BOOT_DISPLAY_TEST || MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
#if MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
    automatic_test = true;
#endif
#if MOTONAV_AUTORUN_MAP_BENCH
    workload = Workload::Map;
#endif
    set_test(true);
#endif
    refresh(nullptr);
    lv_timer_create(refresh, 16, nullptr);
}
} // namespace motonav
