#include "motonav/ui/navigation_ui.h"
#include "motonav/ui/dashboard.h"
#if MOTONAV_DEVICE_TESTS
#include "motonav/testing/display_benchmark.h"
#endif
#include <array>
#include <filesystem>
#include <fstream>
#include <iostream>
#if MOTONAV_PREVIEW_RGB332
#include "pixel_pack.h"
#endif

static motonav::ScreenSnapshot snapshot;
static uint64_t clock_ms;
static std::array<uint16_t, 466 * 466> pixels;
static std::array<uint16_t, 466 * 64> buffer;
static unsigned rim_draws;
static uint64_t host_time_us() { return clock_ms * 1000; }
void advance_host_clock(uint32_t milliseconds) { clock_ms += milliseconds; lv_tick_inc(milliseconds); }
static void flush(lv_display_t *display, const lv_area_t *area, uint8_t *data) {
    auto input = reinterpret_cast<uint16_t *>(data);
#if MOTONAV_PREVIEW_RGB332
    const size_t count = lv_area_get_size(area);
    std::array<uint8_t,466*64> expected;
    if (count > expected.size()) std::abort();
    for (size_t i=0; i<count; ++i) {
        const uint16_t rgb = static_cast<uint16_t>((input[i] << 8) | (input[i] >> 8));
        expected[i] = (rgb/2048/4)*32 + ((rgb/32)%64/8)*4 + (rgb%32/8);
    }
#if MOTONAV_PREVIEW_RGB332 == 1
    if (motonav::drivers::PixelPack::rgb332(data,count) != count ||
        std::memcmp(data,expected.data(),count)) std::abort();
#endif
    size_t index = 0;
    for (int y=area->y1; y<=area->y2; ++y) for (int x=area->x1; x<=area->x2; ++x,++index) {
#if MOTONAV_PREVIEW_RGB332 == 1
        const uint8_t value = data[index];
#else
        const uint8_t value = expected[index];
#endif
        const unsigned r=(value>>5)*255/7, g=((value>>2)&7)*255/7, b=(value&3)*255/3;
        pixels[y*466+x] = ((r>>3)<<11) | ((g>>2)<<5) | (b>>3);
    }
#else
    for (int y = area->y1; y <= area->y2; ++y)
        for (int x = area->x1; x <= area->x2; ++x) {
            uint16_t pixel = *input++;
            pixels[y * 466 + x] = static_cast<uint16_t>((pixel << 8) | (pixel >> 8));
        }
#endif
    lv_display_flush_ready(display);
}
static void save(lv_display_t *display, const std::filesystem::path &path) {
    for (int i = 0; i < 5; ++i) { clock_ms += 20; lv_tick_inc(20); lv_timer_handler(); }
    lv_refr_now(display);
    std::ofstream file(path, std::ios::binary);
    file << "P6\n466 466\n255\n";
    for (uint16_t pixel : pixels) {
        file.put(static_cast<char>(((pixel >> 11) & 31) * 255 / 31));
        file.put(static_cast<char>(((pixel >> 5) & 63) * 255 / 63));
        file.put(static_cast<char>((pixel & 31) * 255 / 31));
    }
    if (!file) std::abort();
}
static bool contains_label(lv_obj_t *object, const char *text) {
    if (lv_obj_check_type(object, &lv_label_class) && std::strcmp(lv_label_get_text(object), text) == 0) return true;
    for (uint32_t i = 0; i < lv_obj_get_child_count(object); ++i)
        if (contains_label(lv_obj_get_child(object, i), text)) return true;
    return false;
}
static void require_label(const char *text) {
    if (!contains_label(lv_screen_active(), text)) {
        std::cerr << "Missing automatic test state: " << text << '\n';
        std::exit(1);
    }
}
#if MOTONAV_DEVICE_TESTS
static void exercise_label_cache(lv_display_t *display, const std::filesystem::path &directory) {
    auto *previous = lv_screen_active();
    auto *screen = lv_obj_create(nullptr);
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x17334d), 0);
    auto *label = lv_label_create(screen);
    lv_obj_set_size(label, 350, LV_SIZE_CONTENT); lv_obj_set_pos(label, 58, 76);
    lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_20, 0);
    lv_obj_set_style_bg_color(label, lv_color_hex(0x090f0c), 0);
    lv_obj_set_style_bg_opa(label, LV_OPA_COVER, 0);
    lv_obj_set_style_radius(label, 6, 0);
    lv_label_set_text(label, "CACHE / TEST 40");
    lv_screen_load(screen);
    save(display, directory / "cache-initial.ppm");
    auto before = motonav::label_cache_stats();
    lv_obj_invalidate(label); save(display, directory / "cache-hit.ppm");
#ifndef MOTONAV_DISABLE_LABEL_CACHE
    auto hit = motonav::label_cache_stats();
    if (hit.hits <= before.hits || hit.misses != before.misses) std::abort();
#endif
    lv_label_set_text(label, "CACHE / TEST 41"); save(display, directory / "cache-text.ppm");
    lv_obj_set_style_text_color(label, lv_color_hex(0xec8137), 0); save(display, directory / "cache-color.ppm");
    lv_obj_set_style_bg_color(label, lv_color_hex(0x2a4867), 0); save(display, directory / "cache-background.ppm");
    lv_obj_set_style_text_font(label, &lv_font_montserrat_28, 0); save(display, directory / "cache-font.ppm");
    lv_obj_set_style_radius(label, 0, 0); save(display, directory / "cache-radius.ppm");
    lv_obj_set_pos(label, 42, 123); save(display, directory / "cache-move.ppm");
    lv_obj_set_width(label, 150); save(display, directory / "cache-wrap.ppm");
    lv_obj_set_width(label, 350); lv_label_set_text(label, "ONE\nTWO"); save(display, directory / "cache-multiline.ppm");
    lv_label_set_text(label, "CACHE / TEST 41");
    lv_obj_set_style_bg_opa(label, LV_OPA_50, 0); save(display, directory / "cache-translucent.ppm");
    lv_obj_set_style_bg_opa(label, LV_OPA_COVER, 0);
    lv_obj_set_style_opa(label, LV_OPA_50, 0); save(display, directory / "cache-opacity.ppm");
    lv_obj_set_style_opa(label, LV_OPA_COVER, 0);
    lv_obj_set_style_recolor(label, lv_color_hex(0x36adf0), 0);
    lv_obj_set_style_recolor_opa(label, LV_OPA_50, 0); save(display, directory / "cache-recolor.ppm");
    lv_obj_set_style_recolor_opa(label, LV_OPA_TRANSP, 0);
    // More distinct strings than cache slots exercises replacement safely.
    for (int i=0;i<12;++i) { lv_label_set_text_fmt(label, "REPLACEMENT %d", i); lv_obj_invalidate(label); lv_refr_now(display); }
    lv_label_set_text(label, "CACHE / TEST 41"); save(display, directory / "cache-eviction.ppm");
    unsigned index = 0;
    for (auto *font : {&lv_font_montserrat_16, &lv_font_montserrat_20, &lv_font_montserrat_28, &lv_font_montserrat_48}) {
        lv_obj_set_style_text_font(label, font, 0);
        for (const char *text : {"W/y!?()_", "fjAVT[]", "gqp.,:;"}) {
            lv_label_set_text(label, text);
            save(display, directory / ("cache-bearing-"+std::to_string(index++)+".ppm"));
        }
    }
    lv_screen_load(previous); lv_obj_delete(screen);
}
#endif
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    std::filesystem::path directory(argv[1]); std::filesystem::create_directories(directory);
    lv_init();
    auto display = lv_display_create(466, 466);
    lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565_SWAPPED);
    lv_display_set_buffers(display, buffer.data(), nullptr, sizeof(buffer), LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(display, flush);
#if MOTONAV_PREVIEW_RGB332
    lv_display_add_event_cb(display, [](lv_event_t *event) {
        auto *area = static_cast<lv_area_t *>(lv_event_get_param(event));
        area->x1 &= ~1; area->y1 &= ~1; area->x2 |= 1; area->y2 |= 1;
    }, LV_EVENT_INVALIDATE_AREA, nullptr);
#endif
    motonav::SnapshotReader reader{[](void *) { return snapshot; }, nullptr};
    motonav::UiExtension extension;
#if MOTONAV_DEVICE_TESTS
    static uint64_t fps_reset_us;
    extension = motonav::testing::make_display_benchmark(display, reader, {
        host_time_us,
        [](lv_display_t *) {
            // Simulate a workload switch crossing the millisecond boundary.
            advance_host_clock(2); fps_reset_us = host_time_us();
        },
        [](lv_display_t *) -> uint32_t {
            if (host_time_us() - fps_reset_us < 1000000) std::abort();
            return 0; // Host previews cannot measure device FPS.
        }});
#endif
    motonav::create_navigation_ui(display, "MotoNav-1234", reader, extension);

#if MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
    // Accidental touches must not silently truncate a timed hardware run.
    lv_obj_send_event(lv_screen_active(), LV_EVENT_SHORT_CLICKED, nullptr);
    lv_obj_send_event(lv_screen_active(), LV_EVENT_LONG_PRESSED, nullptr);
#endif
#if MOTONAV_AUTORUN_BENCH
    save(display, directory / "automatic-partial.ppm");
    require_label("PARTIAL HUD");
    clock_ms += 43000; lv_tick_inc(43000);
    save(display, directory / "automatic-full.ppm");
    require_label("FULL FRAME");
    clock_ms += 43000; lv_tick_inc(43000);
    save(display, directory / "automatic-map.ppm");
    require_label("ROUTE MAP / SIMULATED");
    clock_ms += 43000; lv_tick_inc(43000);
    save(display, directory / "automatic-finished.ppm");
    require_label("CONNECT PHONE");
    std::cout << "PASS: automatic test switches workloads and restores normal display\n";
#endif
#if MOTONAV_AUTORUN_MAP_BENCH
    save(display, directory / "automatic-map.ppm");
    require_label("ROUTE MAP / SIMULATED");
    clock_ms += 43000; lv_tick_inc(43000);
    save(display, directory / "automatic-finished.ppm");
#if MOTONAV_HOLD_MAP_AFTER_BENCH
    require_label("ROUTE MAP / SIMULATED");
    const auto held_pixels = pixels;
    clock_ms += 500; lv_tick_inc(500);
    save(display, directory / "automatic-held-map.ppm");
    require_label("ROUTE MAP / SIMULATED");
    if (pixels == held_pixels) { std::cerr << "Held map stopped animating\n"; return 1; }
    lv_obj_send_event(lv_screen_active(), LV_EVENT_LONG_PRESSED, nullptr);
    save(display, directory / "automatic-held-exit.ppm");
#endif
    require_label("CONNECT PHONE");
    std::cout << "PASS: map-only automatic test restores normal display\n";
#endif
    save(display, directory / "idle.ppm");
    auto *navigation_screen = lv_screen_active();
    auto normal_children = lv_obj_get_child_count(lv_screen_active());
    lv_obj_add_event_cb(lv_obj_get_child(lv_screen_active(),0), [](lv_event_t *) { ++rim_draws; }, LV_EVENT_DRAW_MAIN, nullptr);
    snapshot.linked = snapshot.encrypted = snapshot.subscribed = snapshot.handshaken = true;
    snapshot.has_guidance = snapshot.stream_fresh = true;
    snapshot.guidance = {motonav::Live, 3, 250, 12000, 1000, 50};
    save(display, directory / "navigation.ppm");
    auto navigation_pixels = pixels;
    snapshot.guidance = {motonav::Live, 47, 50000000, 50000000, 31536000, -1};
    save(display, directory / "unknown-maneuver.ppm");
    snapshot.guidance = {};
    save(display, directory / "waiting.ppm");
    snapshot.guidance = {}; snapshot.source_expired = true;
    save(display, directory / "stale.ppm");
    snapshot.pairing = true; snapshot.passkey = 123;
    save(display, directory / "pairing.ppm");
#if !MOTONAV_DEVICE_TESTS
    snapshot = {};
    save(display, directory / "production-idle.ppm");
    auto idle_pixels = pixels;
    lv_obj_send_event(lv_screen_active(), LV_EVENT_LONG_PRESSED, nullptr);
    lv_obj_send_event(lv_screen_active(), LV_EVENT_SHORT_CLICKED, nullptr);
    save(display, directory / "production-touch.ppm");
    require_label("CONNECT PHONE");
    require_label("MOTONAV");
    if (pixels != idle_pixels || lv_screen_active() != navigation_screen ||
        lv_obj_get_child_count(lv_screen_active()) != normal_children) return 1;
    std::cout << "PASS: production UI links/runs without the benchmark or graphics components\n";
    lv_deinit();
    if (motonav::label_cache_stats().bytes) std::abort();
    return 0;
#else
    snapshot = {};
    lv_obj_send_event(lv_screen_active(), LV_EVENT_LONG_PRESSED, nullptr);
    // Automatic builds retain their last workload. Cycle until the map is active.
    for (int i=0;i<3 && !contains_label(lv_screen_active(), "ROUTE MAP / SIMULATED");++i) {
        lv_obj_send_event(lv_screen_active(), LV_EVENT_SHORT_CLICKED, nullptr);
        save(display, directory / "display-test.ppm");
    }
    save(display, directory / "display-test.ppm");
    require_label("ROUTE MAP / SIMULATED");
    auto map_pixels = pixels;
    if (lv_screen_active() == navigation_screen) {
        std::cerr << "Benchmark is mutating the production screen\n"; return 1;
    }
    lv_obj_add_event_cb(lv_obj_get_child(lv_screen_active(), 0), [](lv_event_t *) { ++rim_draws; }, LV_EVENT_DRAW_MAIN, nullptr);
    constexpr uint16_t normal_background = ((0x09 >> 3) << 11) | ((0x0f >> 2) << 5) | (0x0c >> 3);
    for (size_t edge : {size_t(233),size_t(233*466),size_t(233*466+465),size_t(465*466+233)}) {
        if (pixels[edge] == normal_background) {
            std::cerr << "Map does not cover the full round-screen edge\n"; return 1;
        }
    }
    clock_ms += 500; lv_tick_inc(500);
    rim_draws=0;
    save(display, directory / "map-moving.ppm");
    if (rim_draws) { std::cerr << "Hidden instrument background was redrawn under full-screen map\n"; return 1; }
    if (pixels == map_pixels) { std::cerr << "Map did not animate\n"; return 1; }
    snapshot.linked = snapshot.encrypted = snapshot.subscribed = snapshot.handshaken = true;
    snapshot.has_guidance = snapshot.stream_fresh = true;
    snapshot.guidance = {motonav::Live, 3, 250, 12000, 1000, 50};
    save(display, directory / "resumed-navigation.ppm");
    if (pixels != navigation_pixels) {
        std::cerr << "Live guidance did not completely replace the display test\n";
        return 1;
    }
    if (lv_screen_active() != navigation_screen || lv_obj_get_child_count(lv_screen_active()) != normal_children) return 1;
    snapshot = {};
    lv_obj_send_event(lv_screen_active(), LV_EVENT_LONG_PRESSED, nullptr);
    save(display, directory / "map-before-pairing.ppm");
    require_label("ROUTE MAP / SIMULATED");
    snapshot.pairing = true; snapshot.passkey = 123;
    save(display, directory / "pairing-interrupts-map.ppm");
    require_label("PAIR WITH PHONE");
    if (lv_screen_active() != navigation_screen || lv_obj_get_child_count(lv_screen_active()) != normal_children) return 1;
    snapshot = {};
    lv_obj_send_event(lv_screen_active(), LV_EVENT_LONG_PRESSED, nullptr);
    save(display, directory / "map-before-exit.ppm");
    lv_obj_send_event(lv_screen_active(), LV_EVENT_LONG_PRESSED, nullptr);
    save(display, directory / "manual-exit.ppm");
    require_label("CONNECT PHONE");
    if (lv_screen_active() != navigation_screen || lv_obj_get_child_count(lv_screen_active()) != normal_children) return 1;
    std::cout << "PASS: animated map, pairing/live preemption, manual exit and canvas cleanup; no hardware/FPS claim\n";
    exercise_label_cache(display, directory);
#endif
    lv_deinit();
    if (motonav::label_cache_stats().bytes) std::abort();
}
