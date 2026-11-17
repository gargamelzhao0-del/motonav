#pragma once
#include "lvgl.h"
namespace motonav {
// UI-owned transparent caption. Glyph rasterization/one-pixel halo are cached
// until text, width or color changes; camera movement only moves the image.
lv_obj_t *create_map_caption(lv_obj_t *parent);
bool set_map_caption(lv_obj_t *object,const char *text,int width,uint32_t color);
const char *map_caption_text(lv_obj_t *object);
}
