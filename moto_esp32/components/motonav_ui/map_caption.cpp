#include "map_caption.h"
#include "motonav/ui/fonts.h"
#include "src/draw/lv_draw_private.h"
#include "src/draw/sw/lv_draw_sw.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#include <algorithm>
#include <cstring>
#include <new>

namespace motonav {
namespace {
constexpr int Width=156,Height=24;
struct Caption {
    lv_image_dsc_t image{};
    char key[64]{};
    int width=0;
    uint32_t color=0;
    // One bounded external-memory allocation, released with the image.
    alignas(4) uint32_t pixels[Width*Height]{};
    alignas(4) uint8_t mask[Width*Height]{};
};
void shorten(char *text,int maximum) {
    lv_point_t size{};lv_text_get_size(&size,text,&motonav_ui_16,0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
    if(size.x<=maximum)return;
    size_t end=std::strlen(text);
    do {
        if(!end){text[0]=0;return;}
        --end;while(end&&(static_cast<unsigned char>(text[end])&0xc0)==0x80)--end;
        std::strcpy(text+end,"…");
        lv_text_get_size(&size,text,&motonav_ui_16,0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
    }while(size.x>maximum);
}
}
lv_obj_t *create_map_caption(lv_obj_t *parent) {
    auto *object=lv_image_create(parent);
    lv_obj_add_flag(object,LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(object,LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(object,[](lv_event_t *event){
        auto *owned=static_cast<Caption *>(lv_obj_get_user_data(lv_event_get_target_obj(event)));
        if(owned){lv_image_cache_drop(&owned->image);owned->~Caption();lv_free(owned);}
    },LV_EVENT_DELETE,nullptr);
    return object;
}
bool set_map_caption(lv_obj_t *object,const char *text,int width,uint32_t color) {
    if(!object||!text||std::strlen(text)>=64||width<8||width>Width)return false;
    auto *owned=static_cast<Caption *>(lv_obj_get_user_data(object));
    if(!owned){
        void *memory=lv_malloc(sizeof(Caption));if(!memory)return false;
        owned=new(memory) Caption{};lv_obj_set_user_data(object,owned);
    }
#ifndef MOTONAV_DISABLE_LABEL_CACHE
    if(owned->width==width&&owned->color==color&&!std::strcmp(owned->key,text))return true;
#endif
    lv_image_cache_drop(&owned->image);
    std::memset(owned->mask,0,sizeof(owned->mask));
    lv_draw_buf_t buffer{};
    if(lv_draw_buf_init(&buffer,Width,Height,LV_COLOR_FORMAT_A8,Width,owned->mask,sizeof(owned->mask))!=LV_RESULT_OK)return false;
    lv_layer_t layer;lv_layer_init(&layer);layer.draw_buf=&buffer;layer.color_format=LV_COLOR_FORMAT_A8;
    layer.buf_area=layer._clip_area=layer.phy_clip_area={0,0,Width-1,Height-1};
    char display[68];std::strcpy(display,text);shorten(display,width-4);
    lv_draw_label_dsc_t label;lv_draw_label_dsc_init(&label);
    label.font=&motonav_ui_16;label.text=display;label.color=lv_color_white();label.opa=LV_OPA_COVER;
    lv_draw_task_t task{};task.target_layer=&layer;task.clip_area=layer.buf_area;task.opa=LV_OPA_COVER;
#if LV_DRAW_TRANSFORM_USE_MATRIX
    lv_matrix_identity(&task.matrix);
#endif
    lv_area_t area{2,1,width-3,Height-2};lv_draw_sw_label(&task,&label,&area);
    const unsigned red=(color>>16)&255,green=(color>>8)&255,blue=color&255;
    for(int y=0;y<Height;y++)for(int x=0;x<width;x++){
        unsigned ink=owned->mask[y*Width+x],halo=ink;
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)
            if(x+dx>=0&&x+dx<width&&y+dy>=0&&y+dy<Height)
                halo=std::max<unsigned>(halo,owned->mask[(y+dy)*Width+x+dx]);
        const unsigned border=(halo*210/255)*(255-ink)/255,alpha=ink+border;
        owned->pixels[y*width+x]=alpha?(alpha<<24)|(((red*ink+10*border)/alpha)<<16)|
            (((green*ink+16*border)/alpha)<<8)|((blue*ink+18*border)/alpha):0;
    }
    owned->width=width;owned->color=color;std::strcpy(owned->key,text);
    auto &image=owned->image;image.header.magic=LV_IMAGE_HEADER_MAGIC;
    image.header.flags=LV_IMAGE_FLAGS_USER2;
    image.header.cf=LV_COLOR_FORMAT_ARGB8888;image.header.w=width;image.header.h=Height;image.header.stride=width*4;
    image.data_size=width*Height*4;image.data=reinterpret_cast<const uint8_t *>(owned->pixels);
    lv_image_set_src(object,&image);lv_obj_invalidate(object);return true;
}
const char *map_caption_text(lv_obj_t *object){
    if(!object||!lv_obj_check_type(object,&lv_image_class))return nullptr;
    const auto *source=lv_image_get_src(object);
    if(!source||lv_image_src_get_type(source)!=LV_IMAGE_SRC_VARIABLE)return nullptr;
    const auto *image=static_cast<const lv_image_dsc_t *>(source);
    if(image->header.flags!=LV_IMAGE_FLAGS_USER2)return nullptr;
    const auto *owned=static_cast<const Caption *>(lv_obj_get_user_data(object));
    return owned?owned->key:nullptr;
}
}
