#include "motonav/testing/display_benchmark.h"
#include "motonav/ui/dashboard.h"
#include "map_fixture.h"
#include "esp_log.h"
#include <climits>
#include <initializer_list>
#ifndef MOTONAV_BENCH_SECONDS
#define MOTONAV_BENCH_SECONDS 43
#endif

namespace motonav::testing {
namespace {
constexpr uint32_t Amber = 0xffc265;
lv_display_t *display;
lv_obj_t *normal_screen, *test_screen, *test_marker;
Dashboard view;
SnapshotReader snapshot;
BenchmarkServices services;
bool exit_requested = false, pending_autorun = false;
bool may_test_display(const ScreenSnapshot &state) {
    return !state.pairing && screen_mode(state) != ScreenMode::Live;
}
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
    lv_obj_set_flag(view.speed, LV_OBJ_FLAG_HIDDEN, map);
    lv_obj_set_flag(view.remaining, LV_OBJ_FLAG_HIDDEN, map);
    lv_obj_set_flag(view.arrow, LV_OBJ_FLAG_HIDDEN, map);
    lv_obj_set_flag(test_marker, LV_OBJ_FLAG_HIDDEN, !testing || map);
    lv_obj_set_y(view.title, map ? 78 : 88);
    lv_obj_set_style_text_font(view.title, map ? &lv_font_montserrat_20 : &lv_font_montserrat_28, 0);
    lv_obj_set_y(view.number, map ? 390 : 266);
    lv_obj_set_style_text_font(view.number, map ? &lv_font_montserrat_20 : &lv_font_montserrat_48, 0);
    lv_obj_set_y(view.footer, map ? 422 : 405);
    lv_obj_set_width(view.footer, map ? 180 : 285);
    for (auto *overlay : {view.top, view.title, view.number, view.footer}) {
        lv_obj_set_style_bg_color(overlay, lv_color_hex(0x090f0c), 0);
        lv_obj_set_style_bg_opa(overlay, map ? LV_OPA_COVER : LV_OPA_TRANSP, 0);
        lv_obj_set_style_radius(overlay, map ? 6 : 0, 0);
    }
    view.last_icon = -1;
}

uint64_t milliseconds() { return services.now_us() / 1000; }
void reset_benchmark() {
    benchmark_layout();
    test_started = last_sample = milliseconds();
    minimum_fps = UINT32_MAX; samples = 0; total_fps = 0;
    map_render_us = map_render_max_us = map_frames = 0;
    services.reset_fps(display);
    if (services.profile) services.profile(true);
    set_label_text(view.number, "-- FPS"); set_label_text(view.speed, "WARMING UP");
    set_label_text(view.remaining, "Target: at least 40 FPS");
    ESP_LOGI("display_test", "started workload=%s automatic=%d map_buffer=%s", workload_name(), automatic_test,
             workload == Workload::Map ? (map_canvas ? "ready" : "allocation_failed") : "unused");
}
void report_benchmark() {
    if (!samples) return;
    if (services.profile) services.profile(false);
    ESP_LOGI("display_test", "completed workload=%s min=%lu avg=%lu samples=%lu measured_ms=%llu",
        workload_name(), static_cast<unsigned long>(minimum_fps),
        static_cast<unsigned long>(total_fps / samples), static_cast<unsigned long>(samples),
        static_cast<unsigned long long>(milliseconds() - test_started - 2500));
    if (map_frames) ESP_LOGI("map_profile", "frames=%llu prepare_avg_us=%llu prepare_max_us=%llu",
        static_cast<unsigned long long>(map_frames), static_cast<unsigned long long>(map_render_us / map_frames),
        static_cast<unsigned long long>(map_render_max_us));
}
void touch(lv_event_t *event);
void set_test(bool enabled) {
    if (enabled && !testing) {
        test_screen = lv_obj_create(nullptr);
        view = {};
        create_dashboard(test_screen, view);
        lv_obj_t *screen = test_screen;
        test_marker = lv_obj_create(screen); lv_obj_remove_style_all(test_marker);
        lv_obj_set_size(test_marker, 240, 12); lv_obj_set_x(test_marker, 113);
        lv_obj_set_style_bg_color(test_marker, lv_color_hex(Amber), 0);
        lv_obj_set_style_bg_opa(test_marker, LV_OPA_COVER, 0);
        lv_obj_remove_flag(test_marker, static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE));
        lv_obj_add_flag(test_marker, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_event_cb(screen, touch, LV_EVENT_LONG_PRESSED, nullptr);
        lv_obj_add_event_cb(screen, touch, LV_EVENT_SHORT_CLICKED, nullptr);
        lv_screen_load(test_screen);
    }
    if (!enabled && testing) {
        report_benchmark();
        ESP_LOGI("display_test", "stopped automatic=%d", automatic_test);
        lv_screen_load(normal_screen);
        lv_obj_delete(test_screen);
        test_screen = map_canvas = test_marker = nullptr;
        view = {};
    }
    testing = enabled;
    exit_requested = false;
    if (!enabled) automatic_test = false;
    if (enabled) reset_benchmark();
}
void gesture(void *, UiGesture input, const ScreenSnapshot &state) {
    if (automatic_test) {
        ESP_LOGI("display_test", "touch ignored during automatic workload");
        return;
    }
    if (input == UiGesture::Hold && may_test_display(state)) {
        if (testing) exit_requested = true; // Never delete an event target in its callback.
        else set_test(true);
    } else if (input == UiGesture::Tap && testing && !exit_requested) {
        report_benchmark();
        workload = static_cast<Workload>((static_cast<unsigned>(workload) + 1) % 3);
        reset_benchmark();
    }
}
void touch(lv_event_t *event) {
    gesture(nullptr, lv_event_get_code(event) == LV_EVENT_LONG_PRESSED ? UiGesture::Hold : UiGesture::Tap, snapshot());
}
bool refresh(void *, const ScreenSnapshot &state) {
    if (pending_autorun) {
        pending_autorun = false;
        if (may_test_display(state)) { automatic_test = true; set_test(true); }
    }
    if (testing && exit_requested) set_test(false);
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
        set_label_text(view.top, "DISPLAY TEST / NOT NAVIGATION");
        bool map = workload == Workload::Map;
        set_label_text(view.title, map ? "ROUTE MAP / SIMULATED" : workload == Workload::Full ? "FULL FRAME" : "PARTIAL HUD");
        set_label_text(view.footer, automatic_test ? "AUTO TEST" : map ? "Tap next / Hold exit" : "Tap: workload  |  Hold: back");
        if (map) {
            if (!map_canvas) { set_label_text(view.title, "MAP MEMORY ERROR"); return true; }
            uint64_t begin = static_cast<uint64_t>(services.now_us());
            render_map_benchmark(map_canvas, now - test_started);
            uint64_t duration = static_cast<uint64_t>(services.now_us()) - begin;
            if (now - test_started >= 2500) {
                map_render_us += duration; ++map_frames;
                if (duration > map_render_max_us) map_render_max_us = duration;
            }
        } else {
            dashboard_arrow(view, 9);
            // A 240 x 12 moving marker exercises partial invalidation; no simulated GPS is produced.
            unsigned phase = static_cast<unsigned>((now - test_started) % 2000);
            int y = 145 + static_cast<int>((phase < 1000 ? phase : 2000 - phase) * 110 / 1000);
            lv_obj_set_y(test_marker, y);
            if (workload == Workload::Full) lv_obj_invalidate(lv_screen_active());
        }
        if (now - last_sample >= 1100) {
            last_sample = now;
            uint32_t fps = 0;
            fps = services.read_fps(display);
            char value[80];
            std::snprintf(value, sizeof(value), "%lu FPS", static_cast<unsigned long>(fps)); set_label_text(view.number, value);
            if (now - test_started >= 2500) {
                if (fps < minimum_fps) minimum_fps = fps;
                ++samples; total_fps += fps;
                if (map) {
                    std::snprintf(value, sizeof(value), "%lu FPS / MIN %lu", static_cast<unsigned long>(fps),
                                  static_cast<unsigned long>(minimum_fps)); set_label_text(view.number, value);
                }
                std::snprintf(value, sizeof(value), "MIN %lu  /  AVG %lu", static_cast<unsigned long>(minimum_fps),
                              static_cast<unsigned long>(total_fps / samples)); set_label_text(view.speed, value);
                std::snprintf(value, sizeof(value), "%lus measured / target 40", static_cast<unsigned long>((now - test_started - 2500) / 1000));
                set_label_text(view.remaining, value);
                if (samples % 5 == 0) ESP_LOGI("display_test", "workload=%s fps=%lu min=%lu avg=%lu samples=%lu",
                    workload_name(), static_cast<unsigned long>(fps), static_cast<unsigned long>(minimum_fps),
                    static_cast<unsigned long>(total_fps / samples), static_cast<unsigned long>(samples));
            }
        }
        return true;
    }
    return false;
}
} // namespace

UiExtension make_display_benchmark(lv_display_t *target, SnapshotReader reader, BenchmarkServices host) {
    display = target; snapshot = reader; services = host;
    normal_screen = lv_display_get_screen_active(display);
#if MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
    pending_autorun = true;
#endif
#if MOTONAV_AUTORUN_MAP_BENCH
    workload = Workload::Map;
#endif
    return {nullptr, refresh, gesture, "Hold screen: display test"};
}
} // namespace motonav::testing
