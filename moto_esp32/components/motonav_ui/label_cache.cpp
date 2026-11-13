#include "label_cache.h"
#include "motonav/ui/dashboard.h"
#include "lvgl.h"
#include "src/draw/lv_draw_private.h"
#include "src/draw/sw/lv_draw_sw.h"
#include "src/misc/lv_area_private.h"
#include <algorithm>
#include <cstring>

namespace motonav {
namespace {
constexpr uint8_t UnitId = 241;
constexpr size_t Slots = 8, MaxWidth = 466, MaxHeight = 64, TextBytes = 256;
struct Entry {
    lv_draw_label_dsc_t key{};
    char text[TextBytes]{};
    uint16_t background = 0;
    int width = 0, height = 0, inset = 0;
    uint64_t used = 0;
};
Entry entries[Slots];
uint8_t *storage;
lv_draw_buf_t *arena;
size_t slot_bytes;
uint64_t generation;
LabelCacheStats stats;
bool initialized;
struct Request { int width, height, inset; uint16_t background; };

bool eligible(lv_draw_task_t *task, Request &request) {
    auto *d = lv_draw_task_get_label_dsc(task);
    if (!d || !d->base.obj || d->base.part != LV_PART_MAIN ||
        !lv_obj_check_type(d->base.obj, &lv_label_class) ||
        task->target_layer->color_format != LV_COLOR_FORMAT_RGB565_SWAPPED || task->target_layer->recolor.alpha ||
        !d->text || std::strlen(d->text) >= TextBytes || std::strchr(d->text, '\n') || std::strchr(d->text, '\r') ||
        d->align != LV_TEXT_ALIGN_CENTER || d->opa != LV_OPA_COVER || d->rotation || d->ofs_x || d->ofs_y ||
        d->outline_stroke_width || d->decor || d->base.drop_shadow_opa || d->hint ||
        d->sel_start != LV_DRAW_LABEL_NO_TXT_SEL || d->sel_end != LV_DRAW_LABEL_NO_TXT_SEL) return false;
    // These bundled fonts have bounded ASCII bearings. Other fonts/scripts
    // retain LVGL's full renderer rather than assuming the same glyph bounds.
    if (d->font != &lv_font_montserrat_16 && d->font != &lv_font_montserrat_20 &&
        d->font != &lv_font_montserrat_28 && d->font != &lv_font_montserrat_48) return false;
    for (const auto *text = reinterpret_cast<const unsigned char *>(d->text); *text; ++text)
        if (*text < 32 || *text > 126) return false;
#if LV_DRAW_TRANSFORM_USE_MATRIX
    if (!lv_matrix_is_identity(&task->matrix)) return false;
#endif
    auto *obj = d->base.obj;
    if (lv_obj_get_style_bg_opa(obj, LV_PART_MAIN) != LV_OPA_COVER ||
        lv_obj_get_style_bg_grad_dir(obj, LV_PART_MAIN) != LV_GRAD_DIR_NONE || lv_obj_get_style_bg_grad(obj, LV_PART_MAIN) ||
        lv_obj_get_style_bg_image_src(obj, LV_PART_MAIN) ||
        lv_obj_get_style_pad_left(obj, LV_PART_MAIN) || lv_obj_get_style_pad_right(obj, LV_PART_MAIN) ||
        lv_obj_get_style_pad_top(obj, LV_PART_MAIN) || lv_obj_get_style_pad_bottom(obj, LV_PART_MAIN) ||
        lv_obj_get_style_border_width(obj, LV_PART_MAIN) || lv_obj_get_style_blend_mode(obj, LV_PART_MAIN) != LV_BLEND_MODE_NORMAL)
        return false;
    request.width = lv_area_get_width(&task->area);
    request.height = lv_area_get_height(&task->area);
    if (request.width < 1 || request.width > static_cast<int>(MaxWidth) || request.height < 1 ||
        request.height > static_cast<int>(MaxHeight) || d->text_size.y > request.height) return false;
    request.inset = std::min<int>(lv_obj_get_style_radius(obj, LV_PART_MAIN), std::min(request.width, request.height)/2);
    // Only the solid center is cached. LVGL still blends the rounded corners.
    // Four extra pixels allow normal glyph bearings beyond their advance width.
    if (request.inset < 0 || d->text_size.x + 2*(request.inset+4) > request.width) return false;
    request.background = lv_color_to_u16(lv_obj_get_style_bg_color_filtered(obj, LV_PART_MAIN));
    request.background = static_cast<uint16_t>((request.background << 8) | (request.background >> 8));
    return true;
}
int32_t evaluate(lv_draw_unit_t *, lv_draw_task_t *task) {
    Request request{};
    if (!eligible(task, request)) return 0;
    // One bounded arena, allocated only when a supported label is first drawn.
    // Large allocations use the platform's normal external-memory policy.
    if (!storage) {
        arena = lv_draw_buf_create(MaxWidth, MaxHeight*Slots, LV_COLOR_FORMAT_RGB565_SWAPPED, LV_STRIDE_AUTO);
        if (arena) { storage = arena->data; slot_bytes = arena->header.stride * MaxHeight; }
    }
    if (!storage) return 0;
    task->preference_score = 0;
    task->preferred_draw_unit_id = UnitId;
    return 1;
}
int32_t dispatch(lv_draw_unit_t *, lv_layer_t *layer) {
    auto *task = lv_draw_get_available_task(layer, nullptr, UnitId);
    if (!task || !lv_draw_layer_alloc_buf(layer)) return LV_DRAW_UNIT_IDLE;
    Request request{};
    auto *d = lv_draw_task_get_label_dsc(task);
    if (!eligible(task, request)) return LV_DRAW_UNIT_IDLE;
    task->state = LV_DRAW_TASK_STATE_IN_PROGRESS;
    auto key = *d;
    key.base = {}; key.text = nullptr; key.hint = nullptr;
    size_t index = Slots, oldest = 0;
    for (size_t i=0; i<Slots; ++i) {
        const auto &entry = entries[i];
        if (entry.used < entries[oldest].used) oldest = i;
        if (entry.used && entry.width == request.width && entry.height == request.height &&
            entry.inset == request.inset && entry.background == request.background &&
            !std::memcmp(&entry.key, &key, sizeof(key)) && !std::strcmp(entry.text, d->text)) { index = i; break; }
    }
    if (index == Slots) {
        ++stats.misses;
        index = oldest;
        auto *pixels = reinterpret_cast<uint16_t *>(storage + index*slot_bytes);
        const uint32_t stride = lv_draw_buf_width_to_stride(request.width, LV_COLOR_FORMAT_RGB565_SWAPPED);
        std::fill_n(pixels, stride/2*request.height, request.background);
        lv_draw_buf_t buffer{};
        if (lv_draw_buf_init(&buffer, request.width, request.height, LV_COLOR_FORMAT_RGB565_SWAPPED,
                             stride, pixels, slot_bytes) != LV_RESULT_OK) {
            lv_draw_sw_label(task, d, &task->area);
            task->state = LV_DRAW_TASK_STATE_FINISHED;
            lv_draw_dispatch_request();
            return 1;
        }
        lv_layer_t cached_layer;
        lv_layer_init(&cached_layer);
        cached_layer.draw_buf = &buffer;
        cached_layer.color_format = LV_COLOR_FORMAT_RGB565_SWAPPED;
        cached_layer.buf_area = cached_layer._clip_area = cached_layer.phy_clip_area = task->area;
        auto cached_task = *task;
        cached_task.target_layer = &cached_layer; cached_task.clip_area = task->area;
        lv_draw_sw_label(&cached_task, d, &task->area);
        entries[index].key = key;
        std::strcpy(entries[index].text, d->text);
        entries[index].width = request.width; entries[index].height = request.height;
        entries[index].inset = request.inset; entries[index].background = request.background;
    } else { ++stats.hits; }
    entries[index].used = ++generation;
    lv_area_t area = task->area, clip;
    area.x1 += request.inset; area.x2 -= request.inset;
    if (lv_area_intersect(&clip, &area, &task->clip_area) && lv_area_intersect(&clip, &clip, &layer->buf_area)) {
        const uint32_t stride = lv_draw_buf_width_to_stride(request.width, LV_COLOR_FORMAT_RGB565_SWAPPED);
        auto *source = storage + index*slot_bytes + (clip.y1-task->area.y1)*stride + (clip.x1-task->area.x1)*2;
        auto *target = static_cast<uint8_t *>(lv_draw_layer_go_to_xy(layer,
            clip.x1-layer->buf_area.x1, clip.y1-layer->buf_area.y1));
        for (int y=clip.y1; y<=clip.y2; ++y) {
            std::memcpy(target, source, lv_area_get_width(&clip)*2);
            source += stride; target += layer->draw_buf->header.stride;
        }
    }
    task->state = LV_DRAW_TASK_STATE_FINISHED;
    lv_draw_dispatch_request();
    return 1;
}
}
LabelCacheStats label_cache_stats() {
    auto result = stats; result.bytes = arena ? arena->data_size : 0;
    return result;
}
void initialize_label_cache() {
#ifndef MOTONAV_DISABLE_LABEL_CACHE
    if (initialized) return;
    auto *unit = static_cast<lv_draw_unit_t *>(lv_draw_create_unit(sizeof(lv_draw_unit_t)));
    if (!unit) return;
    unit->name = "MotoNav labels"; unit->evaluate_cb = evaluate; unit->dispatch_cb = dispatch;
    unit->delete_cb = [](lv_draw_unit_t *) -> int32_t {
        if (arena) lv_draw_buf_destroy(arena);
        arena = nullptr; storage = nullptr;
        for (auto &entry : entries) entry = {};
        stats = {}; generation = 0; initialized = false;
        return 0;
    };
    initialized = true;
#endif
}
}
