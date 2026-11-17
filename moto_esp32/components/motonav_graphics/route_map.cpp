#include "motonav/graphics/route_map.h"
#include "motonav/graphics/map_raster.h"
#include "motonav/graphics/map_stroke.h"
#include "motonav/graphics/map_strip_index.h"
#include "motonav/graphics/building_contours.h"
#include "round_map_damage.h"
#include "src/draw/lv_image_decoder_private.h"
#include "src/draw/lv_draw_private.h"
#include "src/misc/lv_area_private.h"
#include "src/core/lv_obj_event_private.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#include <cstdlib>
#include <new>
#include <array>
#include <initializer_list>
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
using Segment = MapRaster::PreparedLine;
using Style = NavigationMapStyle;
static_assert(sizeof(Segment) == 20, "Keep the prepared geometry cache bounded");
struct MapScene {
    lv_image_dsc_t image{};
    Segment roads[64]{}, route[64]{};
    struct StrokePair { MapStroke body,edge; } route_surfaces[64]{};
    bool route_passed[64]{};
    float player_y=326;
    struct Feature {
        int16_t top=0,bottom=0;
        uint16_t shape=0,edge=0;
        uint8_t kind=0;
    } features[384]{};
    // Compact typed pools keep active cache lines contiguous. A triangle no
    // longer reserves/copies space for two complete road surfaces.
    MapRaster::PreparedPolygon areas[384]{};
    MapStroke surfaces[384]{},edges[384]{};
    uint16_t area_count=0,surface_count=0,edge_count=0;
    uint16_t paint_order[384]{};
    MapStripIndex strips;
    BuildingContours building_contours;
    uint64_t feature_version=0;
    size_t input_feature_count=0;
    Segment building_edges[256]{};
    size_t building_edge_count=0;
    size_t feature_count=0;
    bool compass=false;
    size_t road_count = 0, route_count = 0;
    bool show_position=true;
    uint8_t position_style=0;
    lv_draw_buf_t strip{};
    uint16_t *pixels = nullptr; // Decoder fallback only; direct draw needs no scratch copy.
    uint64_t (*clock_us)() = nullptr;
    MapPaintStats stats;
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
    ProjectedPoint outline[]{{x,y-17},{x-13,y+14},{x+13,y+14}};
    ProjectedPoint left[]{{x,y-14},{x-10.5f,y+11},{x,y+5}};
    ProjectedPoint right[]{{x,y-14},{x+10.5f,y+11},{x,y+5}};
    raster.polygon(MapRaster::PreparedPolygon(outline,3),panel_color(Style::PlayerEdge),true);
    raster.polygon(MapRaster::PreparedPolygon(left,3),panel_color(Style::Player),true);
    raster.polygon(MapRaster::PreparedPolygon(right,3),panel_color(Style::PlayerSide),true);
}
void position_dot(MapRaster &raster,float x,float y,uint8_t style) {
    const auto color=panel_color(style==1?0xffc265:style==2?0x77caff:0xaab0b4);
    static const auto offsets=[] {
        std::array<ProjectedPoint,17> result{};constexpr float step=6.283185307f/16;
        for(unsigned i=0;i<result.size();i++)result[i]={18*std::sin(i*step),18*std::cos(i*step)};
        return result;
    }();
    for(unsigned i=0;i<16;i++)raster.line({x+offsets[i].x,y+offsets[i].y},
        {x+offsets[i+1].x,y+offsets[i+1].y},1,color);
    raster.line({x,y},{x,y},16,panel_color(0x12171c));
    raster.line({x,y},{x,y},10,color);
}
void line(MapRaster &raster, const Segment &segment, int width, uint32_t color, int top, int bottom) {
    if (!segment.intersects_y(top, bottom, width)) return;
    raster.line(segment, width, panel_color(color));
}
void paint(MapRaster &raster, MapScene &scene, int top, int bottom) {
    const uint64_t begin = scene.clock_us ? scene.clock_us() : 0;
    raster.navigation_style(scene.compass);
    raster.clear(panel_color(Style::Ground));
    bool contours_painted=false;
    auto contours=[&](){
        if(contours_painted)return;
        for(size_t i=0;i<scene.building_edge_count;i++)line(raster,scene.building_edges[i],1,Style::BuildingEdge,top,bottom);
        contours_painted=true;
    };
    // Resolve painter order and bounds once per camera update. Each strip visits
    // the compact list once, instead of scanning all PSRAM geometry six times.
    for(size_t block=0;block<(scene.feature_count+31)/32;block++) {
      uint32_t mask=scene.strips.word(block,top,bottom);
      while(mask) {
        size_t i=block*32+static_cast<unsigned>(__builtin_ctz(mask));mask&=mask-1;
        const auto &f=scene.features[scene.paint_order[i]];
        if(f.bottom<top||f.top>bottom+0.5f)continue;
        const unsigned kind=f.kind;
        if(kind>=3)raster.polygon(scene.areas[f.shape],panel_color(kind==4?Style::Water:kind==5?Style::Park:Style::Building));
        else {
            contours(); // Above every building fill, below every road surface.
            if(kind==2)scene.edges[f.edge].paint(raster,panel_color(Style::RoadEdge));
            scene.surfaces[f.shape].paint(raster,panel_color(kind==2?Style::MajorRoad:kind==1?Style::SecondaryRoad:Style::MinorRoad));
        }
      }
    }
    contours();
    for (size_t i=0; i<scene.road_count; ++i) {
        line(raster, scene.roads[i], 8, 0x10191e, top, bottom);
        line(raster, scene.roads[i], 4, 0x66757c, top, bottom);
    }
    for (int pass=0; pass<2; ++pass)
        for (size_t i=0; i<scene.route_count; ++i) {
            const bool past=scene.route_passed[i];
            const uint32_t color=past?(pass?Style::Passed:Style::PassedEdge):(pass?Style::Route:Style::RouteEdge);
            if(scene.compass){const auto &stroke=pass?scene.route_surfaces[i].body:scene.route_surfaces[i].edge;
                if(stroke.intersects_y(top,bottom))stroke.paint(raster,panel_color(color),true);}
            else line(raster, scene.route[i], past?(pass?4:8):(pass?7:12),color,top,bottom);
        }
    if(scene.show_position&&scene.player_y+20>=top&&scene.player_y-20<=bottom+1){
        if(scene.position_style)position_dot(raster,scene.image.header.w/2,scene.player_y,scene.position_style);
        else player(raster, scene.image.header.w/2, scene.player_y);
    }
    if(scene.compass) {
        static const auto ticks=[] {
            std::array<Segment,72> result{};
            for(unsigned i=0;i<result.size();i++) {
                float angle=i*0.0872664626f,r=i%6?216:211;
                result[i]=Segment({233+std::sin(angle)*r,233-std::cos(angle)*r},
                                  {233+std::sin(angle)*220,233-std::cos(angle)*220});
            }return result;
        }();
        for(unsigned i=0;i<ticks.size();i+=3)line(raster,ticks[i],i%6?1:2,Style::Compass,top,bottom);
    }
    if (scene.clock_us) {
        scene.stats.elapsed_us += scene.clock_us() - begin;
        ++scene.stats.calls;
    }
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
    // Projected surfaces are bounded but larger than centerlines. Keep their
    // cache in PSRAM; internal RAM is reserved for the driver and BLE runtime.
    void *memory = heap_caps_malloc(sizeof(MapScene), MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
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
    if (!view || geometry.route_count > 64 || geometry.road_count > 64 ||geometry.feature_count>384||geometry.position_style>3||
        !std::isfinite(geometry.route_progress)||geometry.route_progress < -1||
        (geometry.route_progress>=0&&(geometry.route_count<2||geometry.route_progress>geometry.route_count-1))||
        (geometry.feature_count&&!geometry.features)||
        (geometry.route_count && !geometry.route) || (geometry.road_count && !geometry.roads)) return false;
    for(size_t i=0;i<geometry.feature_count;i++) {
        const auto &f=geometry.features[i];if(f.kind>5)return false;
        for(auto p:{f.a,f.b,f.c})if(std::abs(int(p.x))>2048||std::abs(int(p.y))>2048)return false;
    }
    auto *scene = scene_for(lv_image_get_src(view));
    if (!scene) return false;
    MapProjection projection(scene->image.header.w, scene->image.header.h, camera);
    if (!projection.valid()) return false;
    if(!geometry.feature_version||scene->feature_version!=geometry.feature_version||scene->input_feature_count!=geometry.feature_count){
        scene->building_contours.prepare(geometry.features,geometry.feature_count);
        scene->feature_version=geometry.feature_version;scene->input_feature_count=geometry.feature_count;
    }
    scene->road_count = scene->route_count = 0;
    scene->show_position=geometry.show_position;
    scene->position_style=geometry.position_style;
    scene->player_y=scene->image.header.h*camera.anchor_y;
    scene->area_count=scene->surface_count=scene->edge_count=0;
    scene->feature_count=0;scene->compass=geometry.compass;
    ProjectedPoint a, b;
    scene->building_edge_count=0;
    for(size_t i=0;i<scene->building_contours.count()&&scene->building_edge_count<256;i++){
        const auto edge=scene->building_contours.edge(i);
        if(!projection.segment(edge.a,edge.b,a,b,2))continue;
        float dx=b.x-a.x,dy=b.y-a.y;
        if(dx*dx+dy*dy<36||std::max(a.y,b.y)<140||std::min(a.y,b.y)>345)continue;
        scene->building_edges[scene->building_edge_count++]=Segment(a,b);
    }
    for(size_t i=0;i<geometry.feature_count;i++) {
        const auto &f=geometry.features[i];MapScene::Feature next;next.kind=f.kind;
        if(f.kind<3) {
            if(!projection.segment(f.a,f.b,a,b,20))continue;
            next.shape=scene->surface_count;
            if(!scene->surfaces[next.shape].prepare_projected(projection,f.a,f.b,a,b,f.kind==2?6.5f:f.kind==1?5.0f:2.8f))continue;
            scene->surface_count++;
            if(f.kind==2){next.edge=scene->edge_count++;scene->edges[next.edge].prepare_projected(projection,f.a,f.b,a,b,10);}
            next.top=static_cast<int16_t>(std::floor(std::min(a.y,b.y)-24));next.bottom=static_cast<int16_t>(std::ceil(std::max(a.y,b.y)+24));
        } else {
            ProjectedPoint points[]{projection.project(f.a),projection.project(f.b),projection.project(f.c)};
            float left=points[0].x,right=left,top=points[0].y,bottom=top;
            for(const auto &p:points){left=std::min(left,p.x);right=std::max(right,p.x);top=std::min(top,p.y);bottom=std::max(bottom,p.y);}
            if(right<0||left>scene->image.header.w||bottom<0||top>scene->image.header.h)continue;
            next.shape=scene->area_count++;scene->areas[next.shape]=MapRaster::PreparedPolygon(points,3);
            next.top=static_cast<int16_t>(std::floor(std::max(-8192.0f,top)));next.bottom=static_cast<int16_t>(std::ceil(std::min(8192.0f,bottom)));
        }
        scene->features[scene->feature_count++]=next;
    }
    size_t ordered=0;
    for(unsigned kind:{4u,5u,3u,0u,1u,2u})for(size_t i=0;i<scene->feature_count;i++)
        if(scene->features[i].kind==kind)scene->paint_order[ordered++]=static_cast<uint16_t>(i);
    scene->strips.reset();
    for(size_t i=0;i<scene->feature_count;i++){
        const auto &f=scene->features[scene->paint_order[i]];scene->strips.add(i,f.top,f.bottom);
    }
    for (size_t i=0; i<geometry.road_count; ++i) {
        if (projection.segment(geometry.roads[i].a, geometry.roads[i].b, a, b))
            scene->roads[scene->road_count++] = Segment(a, b);
    }
    auto route_part=[&](MapPoint start,MapPoint end,bool passed) {
        if(start.x==end.x&&start.y==end.y)return;
        if (projection.segment(start,end,a,b,geometry.compass?20:8)) {
            const size_t index=scene->route_count++;
            scene->route[index] = Segment(a, b);
            scene->route_passed[index]=passed;
            if(geometry.compass) {
                scene->route_surfaces[index].body.prepare_projected(projection,start,end,a,b,passed?4.5f:8);
                scene->route_surfaces[index].edge.prepare_projected(projection,start,end,a,b,passed?8:13);
            }
        }
    };
    for(size_t i=1;i<geometry.route_count;i++) {
        const auto start=geometry.route[i-1],end=geometry.route[i];
        const float fraction=geometry.route_progress-float(i-1);
        if(fraction>0&&fraction<1) {
            MapPoint split{static_cast<int16_t>(std::lround(start.x+(end.x-start.x)*fraction)),
                           static_cast<int16_t>(std::lround(start.y+(end.y-start.y)*fraction))};
            route_part(start,split,true);route_part(split,end,false);
        }else route_part(start,end,fraction>=1);
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

void configure_route_map_profiler(lv_obj_t *view, uint64_t (*clock_us)()) {
    auto *scene = view ? scene_for(lv_image_get_src(view)) : nullptr;
    if (scene) { scene->clock_us = clock_us; scene->stats = {}; }
}
MapPaintStats route_map_paint_stats(lv_obj_t *view) {
    auto *scene = view ? scene_for(lv_image_get_src(view)) : nullptr;
    return scene ? scene->stats : MapPaintStats{};
}
} // namespace motonav
