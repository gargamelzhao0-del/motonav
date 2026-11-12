#include "navigation_ui.h"
#include <array>
#include <filesystem>
#include <fstream>
#include <iostream>

static motonav::ScreenSnapshot snapshot;
static uint64_t clock_ms;
static std::array<uint16_t, 466 * 466> pixels;
static std::array<uint16_t, 466 * 50> buffer;
int64_t esp_timer_get_time() { return static_cast<int64_t>(clock_ms * 1000); }
static void flush(lv_display_t *display, const lv_area_t *area, uint8_t *data) {
    auto input = reinterpret_cast<uint16_t *>(data);
    for (int y = area->y1; y <= area->y2; ++y)
        for (int x = area->x1; x <= area->x2; ++x) pixels[y * 466 + x] = *input++;
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
#if MOTONAV_AUTORUN_BENCH
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
#endif
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    std::filesystem::path directory(argv[1]); std::filesystem::create_directories(directory);
    lv_init();
    auto display = lv_display_create(466, 466);
    lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565);
    lv_display_set_buffers(display, buffer.data(), nullptr, sizeof(buffer), LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(display, flush);
    motonav::create_navigation_ui(display, "MotoNav-1234", [] { return snapshot; });
#if MOTONAV_AUTORUN_BENCH
    save(display, directory / "automatic-partial.ppm");
    require_label("PARTIAL HUD");
    clock_ms += 43000; lv_tick_inc(43000);
    save(display, directory / "automatic-full.ppm");
    require_label("FULL FRAME");
    clock_ms += 43000; lv_tick_inc(43000);
    save(display, directory / "automatic-finished.ppm");
    require_label("CONNECT PHONE");
    std::cout << "PASS: automatic test switches workloads and restores normal display\n";
#endif
    save(display, directory / "idle.ppm");
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
    snapshot = {};
    lv_obj_send_event(lv_screen_active(), LV_EVENT_LONG_PRESSED, nullptr);
    save(display, directory / "display-test.ppm");
    snapshot.linked = snapshot.encrypted = snapshot.subscribed = snapshot.handshaken = true;
    snapshot.has_guidance = snapshot.stream_fresh = true;
    snapshot.guidance = {motonav::Live, 3, 250, 12000, 1000, 50};
    save(display, directory / "resumed-navigation.ppm");
    if (pixels != navigation_pixels) {
        std::cerr << "Live guidance did not completely replace the display test\n";
        return 1;
    }
    std::cout << "PASS: eight LVGL layout previews and live navigation interrupts the test; no hardware/FPS claim\n";
}
