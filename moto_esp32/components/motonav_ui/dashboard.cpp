#include "motonav/ui/dashboard.h"
#include "label_cache.h"
#include "motonav/core/display_model.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#include "src/core/lv_obj_event_private.h"
#include "src/misc/lv_area_private.h"

namespace motonav {
namespace { constexpr uint32_t Lime = 0xc4fa64, White = 0xf2f3e9, Muted = 0x89978c; }
void set_label_text(lv_obj_t *object, const char *value) {
    if (std::strcmp(lv_label_get_text(object), value)) lv_label_set_text(object, value);
}
static lv_obj_t *label(lv_obj_t *screen, int y, int width, const lv_font_t *font, uint32_t color) {
    lv_obj_t *object = lv_label_create(screen);
    lv_obj_set_width(object, width);
    lv_obj_set_style_text_align(object, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_font(object, font, 0);
    lv_obj_set_style_text_color(object, lv_color_hex(color), 0);
    lv_obj_align(object, LV_ALIGN_TOP_MID, 0, y);
    lv_obj_remove_flag(object, LV_OBJ_FLAG_CLICKABLE);
    return object;
}
static void create_rim(lv_obj_t *screen) {
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
        lv_obj_add_event_cb(rim, [](lv_event_t *event) {
            auto *object = lv_event_get_target_obj(event);
            auto *cover = static_cast<lv_cover_check_info_t *>(lv_event_get_param(event));
            lv_area_t bounds; lv_obj_get_coords(object, &bounds);
            if (cover->res != LV_COVER_RES_MASKED && !lv_image_get_rotation(object) &&
                lv_image_get_scale_x(object) == LV_SCALE_NONE && lv_image_get_scale_y(object) == LV_SCALE_NONE &&
                lv_obj_get_style_opa(object, LV_PART_MAIN) == LV_OPA_COVER &&
                lv_obj_get_style_image_opa(object, LV_PART_MAIN) == LV_OPA_COVER &&
                lv_area_is_in(cover->area, &bounds, 0)) cover->res = LV_COVER_RES_COVER;
        }, LV_EVENT_COVER_CHECK, nullptr);
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
        // Cache in display byte order once; repeated partial/full refreshes
        // can copy this image without swapping every pixel on every frame.
        for (uint32_t y=0; y<buffer->header.h; ++y)
            lv_draw_sw_rgb565_swap(buffer->data+y*buffer->header.stride, buffer->header.w);
        buffer->header.cf = LV_COLOR_FORMAT_RGB565_SWAPPED;
        lv_canvas_set_draw_buf(rim, buffer);
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
void dashboard_arrow(Dashboard &view, int icon) {
    if (icon == view.last_icon) return;
    view.last_icon = icon;
    ArrowShape shape = arrow_shape(icon);
    lv_obj_set_flag(view.arrow, LV_OBJ_FLAG_HIDDEN, shape.count == 0);
    if (!shape.count) return;
    for (unsigned i = 0; i < shape.count; ++i) {
        view.arrow_points[i].x = shape.points[i].x*view.arrow_scale; view.arrow_points[i].y = shape.points[i].y*view.arrow_scale;
    }
    lv_line_set_points(view.arrow, view.arrow_points, shape.count);
}

void create_dashboard(lv_obj_t *screen, Dashboard &view) {
    initialize_label_cache();
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x090f0c), 0);
    lv_obj_remove_flag(screen, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(screen, LV_OBJ_FLAG_CLICKABLE);
    create_rim(screen);
    view.top = label(screen, 52, 310, &lv_font_montserrat_16, Muted);
    view.title = label(screen, 88, 350, &lv_font_montserrat_28, Lime);
    view.arrow = lv_line_create(screen); lv_obj_set_pos(view.arrow, 153, 130);
    lv_obj_set_style_line_width(view.arrow, 14, 0);
    lv_obj_set_style_line_color(view.arrow, lv_color_hex(Lime), 0);
    lv_obj_set_style_line_rounded(view.arrow, true, 0);
    view.number = label(screen, 266, 330, &lv_font_montserrat_48, White);
    view.speed = label(screen, 330, 340, &lv_font_montserrat_28, White);
    view.remaining = label(screen, 374, 330, &lv_font_montserrat_20, Muted);
    view.footer = label(screen, 405, 285, &lv_font_montserrat_16, Muted);
}
} // namespace motonav
