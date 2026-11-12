#include "route_map.h"
#include "map_raster.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#if MOTONAV_AUTORUN_MAP_BENCH
#include "esp_timer.h"
#include "esp_log.h"
#endif

namespace motonav {
namespace {
void line(MapRaster &raster, ProjectedPoint a, ProjectedPoint b, int width, uint32_t color) {
    raster.line(a,b,width,MapRaster::rgb(color));
}
void player(MapRaster &raster, float x, float y) {
    ProjectedPoint outline[]{{x,y-17},{x-12,y+11},{x+12,y+11}};
    ProjectedPoint inner[]{{x,y-12},{x-8,y+7},{x+8,y+7}};
    raster.polygon(outline,3,MapRaster::rgb(0x12171c));
    raster.polygon(inner,3,MapRaster::rgb(0xf2f3e9));
}
} // namespace

lv_obj_t *create_route_map(lv_obj_t *parent, int width, int height) {
    if (width <= 0 || width > 466 || height <= 0 || height > 466) return nullptr;
    auto *buffer = lv_draw_buf_create(width, height, LV_COLOR_FORMAT_RGB565, LV_STRIDE_AUTO);
    if (!buffer) return nullptr;
    auto *canvas = lv_canvas_create(parent);
    lv_obj_remove_style_all(canvas);
    lv_canvas_set_draw_buf(canvas, buffer);
    lv_obj_remove_flag(canvas, static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE));
    lv_obj_add_event_cb(canvas, [](lv_event_t *event) {
        auto *owned = static_cast<lv_draw_buf_t *>(lv_event_get_user_data(event));
        lv_image_cache_drop(owned);
        lv_image_set_src(lv_event_get_target_obj(event), nullptr);
        lv_draw_buf_destroy(owned);
    }, LV_EVENT_DELETE, buffer);
    return canvas;
}

bool render_route_map(lv_obj_t *canvas, const MapCamera &camera, const MapGeometry &geometry) {
    if (!canvas || geometry.route_count > 512 || geometry.road_count > 64 ||
        (geometry.route_count && !geometry.route) || (geometry.road_count && !geometry.roads)) return false;
    auto *buffer = lv_canvas_get_draw_buf(canvas);
    if (!buffer) return false;
    MapProjection projection(buffer->header.w, buffer->header.h, camera);
    if (!projection.valid()) return false;
    if (buffer->header.cf != LV_COLOR_FORMAT_RGB565 || buffer->header.stride % 2) return false;
    MapRaster raster(reinterpret_cast<uint16_t *>(buffer->data),buffer->data_size/2,
                     buffer->header.w,buffer->header.h,buffer->header.stride/2);
    if (!raster.valid()) return false;
#if MOTONAV_AUTORUN_MAP_BENCH
    int64_t t0 = esp_timer_get_time();
#endif
    raster.clear(MapRaster::rgb(0x20292e));
#if MOTONAV_AUTORUN_MAP_BENCH
    int64_t t1 = esp_timer_get_time();
#endif
    ProjectedPoint a, b;
    for (size_t i = 0; i < geometry.road_count; ++i) {
        const auto &road = geometry.roads[i];
        if (!projection.segment(road.a, road.b, a, b)) continue;
        line(raster, a, b, 8, 0x10191e);
        line(raster, a, b, 4, 0x66757c);
    }
#if MOTONAV_AUTORUN_MAP_BENCH
    int64_t t2 = esp_timer_get_time();
#endif
    // Two passes keep the route interior continuous across joined segments.
    for (int pass = 0; pass < 2; ++pass) {
        for (size_t i = 1; i < geometry.route_count; ++i) {
            if (!projection.segment(geometry.route[i - 1], geometry.route[i], a, b)) continue;
            line(raster, a, b, pass ? 7 : 12, pass ? 0xb66dff : 0x2a1440);
        }
    }
    player(raster, buffer->header.w / 2, buffer->header.h * 7 / 10);
    lv_obj_invalidate(canvas);
#if MOTONAV_AUTORUN_MAP_BENCH
    static int64_t clear_us = 0, roads_us = 0, route_us = 0;
    static unsigned frames = 0;
    clear_us += t1 - t0; roads_us += t2 - t1; route_us += esp_timer_get_time() - t2;
    if (++frames == 120) {
        ESP_LOGI("map_layers", "clear_us=%lld roads_us=%lld route_us=%lld",
            static_cast<long long>(clear_us / frames), static_cast<long long>(roads_us / frames),
            static_cast<long long>(route_us / frames));
        frames = 0; clear_us = roads_us = route_us = 0;
    }
#endif
    return true;
}

void render_map_benchmark(lv_obj_t *canvas, uint64_t elapsed_ms) {
    static constexpr MapPoint route[]{{0,-600},{0,-120},{240,-120},{240,240},{-120,240},{-120,480},{360,480}};
    static const auto roads = [] {
        struct Roads { MapSegment segments[22]; } result{};
        for (int i = 0; i < 11; ++i) {
            int16_t coordinate = static_cast<int16_t>(i * 120 - 600);
            result.segments[i] = {{-720,coordinate},{720,coordinate}};
            result.segments[i + 11] = {{coordinate,-720},{coordinate,720}};
        }
        return result;
    }();
    // Bound time so long bench sessions preserve subpixel motion precision.
    float t = static_cast<float>(elapsed_ms % 120000) / 1000.0f;
    MapCamera camera{60 * std::sin(t * 0.3f), 85 * std::sin(t * 0.2f),
                     0.5f * std::sin(t * 0.16f), 0.7f};
    render_route_map(canvas, camera, {route, sizeof(route) / sizeof(route[0]), roads.segments, 22});
}
} // namespace motonav
