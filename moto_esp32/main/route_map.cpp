#include "route_map.h"
#include "map_raster.h"
#include "round_map_damage.h"
#include "src/draw/lv_image_decoder_private.h"
#include "src/draw/lv_draw_private.h"
#include "src/misc/lv_area_private.h"
#include "src/core/lv_obj_event_private.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#include <cstdlib>
#include <new>
#ifdef ESP_PLATFORM
#include "esp_heap_caps.h"
#endif

namespace motonav {
namespace {
// LVGL's synchronous software renderer consumes each decoded strip before the
// next one is generated. No full-frame PSRAM canvas or worker task is needed.
static_assert(LV_DRAW_SW_DRAW_UNIT_CNT == 1, "Map strip decoder requires one software draw unit");
constexpr int StripRows = 16;
constexpr size_t StripPixels = 466 * StripRows;
struct Segment { ProjectedPoint a, b; };
struct MapScene {
    lv_image_dsc_t image{};
    Segment roads[64]{}, route[511]{};
    size_t road_count = 0, route_count = 0;
    lv_draw_buf_t strip{};
    uint16_t *pixels = nullptr; // Decoder fallback only; direct draw needs no scratch copy.
};
lv_image_decoder_t *map_decoder;
constexpr uint8_t MapDrawUnit = 240; // Application unit, separate from LVGL's built-in units.
constexpr uint16_t panel_color(uint32_t color) {
    const auto rgb565 = MapRaster::rgb(color);
    return static_cast<uint16_t>((rgb565 << 8) | (rgb565 >> 8));
}

MapScene *scene_for(const void *source) {
    if (!source || lv_image_src_get_type(source) != LV_IMAGE_SRC_VARIABLE) return nullptr;
    auto *image = static_cast<const lv_image_dsc_t *>(source);
    if (image->header.flags != LV_IMAGE_FLAGS_USER1 || image->data_size != sizeof(MapScene)) return nullptr;
    auto *scene = reinterpret_cast<MapScene *>(const_cast<uint8_t *>(image->data));
    return scene && &scene->image == image ? scene : nullptr;
}
lv_result_t info(lv_image_decoder_t *, lv_image_decoder_dsc_t *dsc, lv_image_header_t *header) {
    auto *scene = scene_for(dsc->src);
    if (!scene) return LV_RESULT_INVALID;
    *header = scene->image.header;
    return LV_RESULT_OK;
}
lv_result_t open(lv_image_decoder_t *, lv_image_decoder_dsc_t *dsc) {
    dsc->user_data = scene_for(dsc->src);
    dsc->decoded = nullptr; // Generate only the area LVGL is currently flushing.
    dsc->args.no_cache = true;
    return dsc->user_data ? LV_RESULT_OK : LV_RESULT_INVALID;
}
void player(MapRaster &raster, float x, float y) {
    ProjectedPoint outline[]{{x,y-17},{x-12,y+11},{x+12,y+11}};
    ProjectedPoint inner[]{{x,y-12},{x-8,y+7},{x+8,y+7}};
    raster.polygon(outline,3,panel_color(0x12171c));
    raster.polygon(inner,3,panel_color(0xf2f3e9));
}
void line(MapRaster &raster, const Segment &segment, int width, uint32_t color, int top, int bottom) {
    if (std::max(segment.a.y, segment.b.y) + width < top ||
        std::min(segment.a.y, segment.b.y) - width > bottom) return;
    raster.line(segment.a, segment.b, width, panel_color(color));
}
void paint(MapRaster &raster, const MapScene &scene, int top, int bottom) {
    raster.clear(panel_color(0x20292e));
    for (size_t i=0; i<scene.road_count; ++i) {
        line(raster, scene.roads[i], 8, 0x10191e, top, bottom);
        line(raster, scene.roads[i], 4, 0x66757c, top, bottom);
    }
    for (int pass=0; pass<2; ++pass)
        for (size_t i=0; i<scene.route_count; ++i)
            line(raster, scene.route[i], pass ? 7 : 12, pass ? 0xb66dff : 0x2a1440, top, bottom);
    player(raster, scene.image.header.w/2, scene.image.header.h*7/10);
}
int32_t evaluate(lv_draw_unit_t *, lv_draw_task_t *task) {
    auto *dsc = lv_draw_task_get_image_dsc(task);
    if (!dsc || !scene_for(dsc->src) || task->target_layer->color_format != LV_COLOR_FORMAT_RGB565_SWAPPED ||
        task->area.x1 != 0 || task->area.y1 != 0 ||
        lv_area_get_width(&task->area) != dsc->header.w || lv_area_get_height(&task->area) != dsc->header.h ||
        dsc->rotation || dsc->scale_x != LV_SCALE_NONE || dsc->scale_y != LV_SCALE_NONE ||
        dsc->skew_x || dsc->skew_y || dsc->clip_radius || dsc->tile || dsc->colorkey || dsc->bitmap_mask_src ||
        dsc->opa != LV_OPA_COVER || dsc->recolor_opa || dsc->blend_mode != LV_BLEND_MODE_NORMAL) return 0;
#if LV_DRAW_TRANSFORM_USE_MATRIX
    if (!lv_matrix_is_identity(&task->matrix)) return 0;
#endif
    task->preference_score = 0;
    task->preferred_draw_unit_id = MapDrawUnit;
    return 1;
}
int32_t dispatch(lv_draw_unit_t *, lv_layer_t *layer) {
    auto *task = lv_draw_get_available_task(layer, nullptr, MapDrawUnit);
    if (!task) return LV_DRAW_UNIT_IDLE;
    if (!lv_draw_layer_alloc_buf(layer)) return LV_DRAW_UNIT_IDLE;
    auto *scene = scene_for(lv_draw_task_get_image_dsc(task)->src);
    task->state = LV_DRAW_TASK_STATE_IN_PROGRESS;
    lv_area_t clip;
    if (lv_area_intersect(&clip, &task->area, &task->clip_area) &&
        lv_area_intersect(&clip, &clip, &layer->buf_area)) {
        auto *pixels = static_cast<uint16_t *>(lv_draw_layer_go_to_xy(layer,
            clip.x1-layer->buf_area.x1, clip.y1-layer->buf_area.y1));
        size_t offset = reinterpret_cast<uint8_t *>(pixels)-layer->draw_buf->data;
        MapRaster raster(pixels, (layer->draw_buf->data_size-offset)/2,
                         lv_area_get_width(&clip), lv_area_get_height(&clip),
                         layer->draw_buf->header.stride/2, clip.x1, clip.y1);
        paint(raster, *scene, clip.y1, clip.y2);
    }
    task->state = LV_DRAW_TASK_STATE_FINISHED;
    lv_draw_dispatch_request();
    return 1;
}
lv_result_t area(lv_image_decoder_t *, lv_image_decoder_dsc_t *dsc,
                 const lv_area_t *full, lv_area_t *decoded) {
    auto *scene = static_cast<MapScene *>(dsc->user_data);
    if (!scene || full->x1 < 0 || full->y1 < 0 || full->x2 >= scene->image.header.w ||
        full->y2 >= scene->image.header.h || full->x2 < full->x1 || full->y2 < full->y1) return LV_RESULT_INVALID;
    int y = decoded->y1 == LV_COORD_MIN ? full->y1 : decoded->y2 + 1;
    if (y > full->y2) return LV_RESULT_INVALID;
    *decoded = {full->x1, y, full->x2, std::min<int32_t>(y + StripRows - 1, full->y2)};
    int width = lv_area_get_width(decoded), height = lv_area_get_height(decoded);
    uint32_t stride = lv_draw_buf_width_to_stride(width, LV_COLOR_FORMAT_RGB565_SWAPPED);
    if (stride % 2 || static_cast<size_t>(stride) * height > StripPixels*2) return LV_RESULT_INVALID;
    if (!scene->pixels) {
#ifdef ESP_PLATFORM
        scene->pixels = static_cast<uint16_t *>(heap_caps_malloc(StripPixels*2, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
#else
        scene->pixels = static_cast<uint16_t *>(std::malloc(StripPixels*2));
#endif
        if (!scene->pixels) return LV_RESULT_INVALID;
    }
    if (lv_draw_buf_init(&scene->strip, width, height, LV_COLOR_FORMAT_RGB565_SWAPPED, stride,
                         scene->pixels, StripPixels*2) != LV_RESULT_OK) return LV_RESULT_INVALID;
    MapRaster raster(scene->pixels, StripPixels, width, height, stride/2, decoded->x1, decoded->y1);
    paint(raster, *scene, decoded->y1, decoded->y2);
    dsc->decoded = &scene->strip;
    return LV_RESULT_OK;
}
} // namespace

lv_obj_t *create_route_map(lv_obj_t *parent, int width, int height) {
    if (width <= 0 || width > 466 || height <= 0 || height > 466) return nullptr;
    if (!map_decoder) {
        map_decoder = lv_image_decoder_create();
        if (!map_decoder) return nullptr;
        lv_image_decoder_set_info_cb(map_decoder, info);
        lv_image_decoder_set_open_cb(map_decoder, open);
        lv_image_decoder_set_get_area_cb(map_decoder, area);
        map_decoder->name = "MotoNav strips";
#ifndef MOTONAV_MAP_DECODER_ONLY
        // Obey LVGL task ordering, but rasterize opaque map pixels directly
        // into its current DMA strip. Transforms and other formats use the
        // decoder fallback; labels remain with LVGL's software renderer.
        auto *unit = static_cast<lv_draw_unit_t *>(lv_draw_create_unit(sizeof(lv_draw_unit_t)));
        if (unit) {
            unit->name = "MotoNav map";
            unit->evaluate_cb = evaluate;
            unit->dispatch_cb = dispatch;
        }
#endif
    }
#ifdef ESP_PLATFORM
    void *memory = heap_caps_malloc(sizeof(MapScene), MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
#else
    void *memory = std::malloc(sizeof(MapScene));
#endif
    if (!memory) return nullptr;
    auto *scene = new(memory) MapScene{};
    scene->image.header.magic = LV_IMAGE_HEADER_MAGIC;
    scene->image.header.cf = LV_COLOR_FORMAT_RGB565_SWAPPED;
    scene->image.header.flags = LV_IMAGE_FLAGS_USER1;
    scene->image.header.w = width; scene->image.header.h = height;
    scene->image.header.stride = width * 2;
    scene->image.data_size = sizeof(MapScene);
    scene->image.data = reinterpret_cast<const uint8_t *>(scene);
    auto *view = lv_image_create(parent);
    lv_obj_remove_style_all(view);
    lv_image_set_src(view, &scene->image);
    lv_obj_set_size(view, width, height);
    lv_obj_add_event_cb(view, [](lv_event_t *event) {
        auto *object = lv_event_get_target_obj(event);
        auto *cover = static_cast<lv_cover_check_info_t *>(lv_event_get_param(event));
        auto *owned = scene_for(lv_image_get_src(object));
        lv_area_t bounds; lv_obj_get_coords(object, &bounds);
        if (cover->res == LV_COVER_RES_MASKED || !owned ||
            lv_image_get_rotation(object) || lv_image_get_scale_x(object) != LV_SCALE_NONE ||
            lv_image_get_scale_y(object) != LV_SCALE_NONE ||
            lv_obj_get_width(object) != owned->image.header.w || lv_obj_get_height(object) != owned->image.header.h ||
            lv_obj_get_style_image_opa(object, LV_PART_MAIN) != LV_OPA_COVER ||
            lv_obj_get_style_opa(object, LV_PART_MAIN) != LV_OPA_COVER ||
            !lv_area_is_in(cover->area, &bounds, 0)) return;
        // LVGL 9.5's image base class marks a transparent background NOT_COVER,
        // even for an opaque RGB565 image. Its public setter only strengthens
        // that result. This pinned-version callback declares the actual image
        // coverage without drawing a redundant solid rectangle underneath.
        cover->res = LV_COVER_RES_COVER;
    }, LV_EVENT_COVER_CHECK, nullptr);
    lv_obj_remove_flag(view, static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE));
    lv_obj_add_event_cb(view, [](lv_event_t *event) {
        auto *owned = static_cast<MapScene *>(lv_event_get_user_data(event));
        lv_image_cache_drop(&owned->image);
        lv_image_set_src(lv_event_get_target_obj(event), nullptr);
        std::free(owned->pixels);
        owned->~MapScene();
        std::free(owned);
    }, LV_EVENT_DELETE, scene);
    return view;
}

bool render_route_map(lv_obj_t *view, const MapCamera &camera, const MapGeometry &geometry) {
    if (!view || geometry.route_count > 512 || geometry.road_count > 64 ||
        (geometry.route_count && !geometry.route) || (geometry.road_count && !geometry.roads)) return false;
    auto *scene = scene_for(lv_image_get_src(view));
    if (!scene) return false;
    MapProjection projection(scene->image.header.w, scene->image.header.h, camera);
    if (!projection.valid()) return false;
    scene->road_count = scene->route_count = 0;
    for (size_t i=0; i<geometry.road_count; ++i) {
        auto &segment = scene->roads[scene->road_count];
        if (projection.segment(geometry.roads[i].a, geometry.roads[i].b, segment.a, segment.b)) ++scene->road_count;
    }
    for (size_t i=1; i<geometry.route_count; ++i) {
        auto &segment = scene->route[scene->route_count];
        if (projection.segment(geometry.route[i-1], geometry.route[i], segment.a, segment.b)) ++scene->route_count;
    }
    if (scene->image.header.w == 466 && scene->image.header.h == 466 &&
        lv_obj_get_x(view) == 0 && lv_obj_get_y(view) == 0 &&
        lv_image_get_rotation(view) == 0 && lv_image_get_scale_x(view) == LV_SCALE_NONE &&
        lv_image_get_scale_y(view) == LV_SCALE_NONE) {
        static constexpr auto bands = round_map_damage();
        lv_area_t bounds; lv_obj_get_coords(view, &bounds);
        for (const auto &band : bands) {
            lv_area_t dirty{bounds.x1+band.left, bounds.y1+band.top,
                            bounds.x1+band.right-1, bounds.y1+band.bottom-1};
            lv_obj_invalidate_area(view, &dirty);
        }
    } else {
        lv_obj_invalidate(view);
    }
    return true;
}

void render_map_benchmark(lv_obj_t *view, uint64_t elapsed_ms) {
    static constexpr MapPoint route[]{{0,-600},{0,-120},{240,-120},{240,240},{-120,240},{-120,480},{360,480}};
    static const auto roads = [] {
        struct Roads { MapSegment segments[22]; } result{};
        for (int i=0; i<11; ++i) {
            int16_t coordinate = static_cast<int16_t>(i*120-600);
            result.segments[i] = {{-720,coordinate},{720,coordinate}};
            result.segments[i+11] = {{coordinate,-720},{coordinate,720}};
        }
        return result;
    }();
    float t = static_cast<float>(elapsed_ms % 120000) / 1000.0f;
    MapCamera camera{60*std::sin(t*0.3f),85*std::sin(t*0.2f),0.5f*std::sin(t*0.16f),0.7f};
    render_route_map(view, camera, {route,sizeof(route)/sizeof(route[0]),roads.segments,22});
}
} // namespace motonav
