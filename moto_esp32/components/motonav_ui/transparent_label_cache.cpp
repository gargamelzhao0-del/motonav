#include "label_cache.h"
#include "motonav/ui/dashboard.h"
#include "motonav/ui/fonts.h"
#include "src/draw/lv_draw_private.h"
#include "src/draw/sw/lv_draw_sw.h"
#include "src/draw/sw/blend/lv_draw_sw_blend.h"
#include "src/draw/sw/blend/lv_draw_sw_blend_private.h"
#include "src/misc/lv_area_private.h"
#include <cstring>

namespace motonav {
namespace {
constexpr uint8_t Unit=242;
constexpr size_t Slots=40,Width=256,Height=64,TextBytes=192;
struct Entry {
    lv_draw_label_dsc_t key{};
    char text[TextBytes]{};
    int width=0,height=0;
    uint64_t used=0;
};
// One large allocation follows LVGL's external-memory policy. The cache does
// not consume a second set of internal DMA buffers or allocate every frame.
struct Storage { Entry entries[Slots]; uint8_t pixels[Slots][Width*Height]; };
Storage *storage=nullptr;
struct HotEntry { lv_obj_t *object=nullptr;uint64_t used=0; } hot[Slots];
uint64_t generation=0;
LabelCacheStats stats;
bool initialized=false;
bool eligible(lv_draw_task_t *task) {
    auto *d=lv_draw_task_get_label_dsc(task);
    if(!d||!d->base.obj||d->base.part!=LV_PART_MAIN||!lv_obj_check_type(d->base.obj,&lv_label_class)
            ||task->target_layer->color_format!=LV_COLOR_FORMAT_RGB565_SWAPPED||task->target_layer->recolor.alpha
            ||!d->text||std::strlen(d->text)>=TextBytes||std::strchr(d->text,'\n')||std::strchr(d->text,'\r')
            ||d->opa!=LV_OPA_COVER||d->rotation||d->ofs_x||d->ofs_y||d->outline_stroke_width||d->decor
            ||d->base.drop_shadow_opa||d->hint||d->sel_start!=LV_DRAW_LABEL_NO_TXT_SEL||d->sel_end!=LV_DRAW_LABEL_NO_TXT_SEL)return false;
    if(d->font!=&motonav_ui_18&&d->font!=&motonav_speed_66&&d->font!=&motonav_distance_36&&d->font!=&motonav_small_14
            &&d->font!=&lv_font_montserrat_16&&d->font!=&lv_font_montserrat_20
            &&d->font!=&lv_font_montserrat_28&&d->font!=&lv_font_montserrat_48)return false;
    // Solid legacy labels keep their faster RGB565 copy cache.
    if((d->font==&lv_font_montserrat_16||d->font==&lv_font_montserrat_20||d->font==&lv_font_montserrat_28||d->font==&lv_font_montserrat_48)
            &&lv_obj_get_style_bg_opa(d->base.obj,LV_PART_MAIN)==LV_OPA_COVER)return false;
#if LV_DRAW_TRANSFORM_USE_MATRIX
    if(!lv_matrix_is_identity(&task->matrix))return false;
#endif
    int width=lv_area_get_width(&task->area),height=lv_area_get_height(&task->area);
    return width>0&&width+8<=int(Width)&&height>0&&height<=int(Height)&&d->text_size.y<=height;
}
int32_t evaluate(lv_draw_unit_t *,lv_draw_task_t *task) {
    if(!eligible(task))return 0;
    if(!storage)storage=static_cast<Storage *>(lv_malloc_zeroed(sizeof(Storage)));
    if(!storage)return 0;
    task->preference_score=0;task->preferred_draw_unit_id=Unit;return 1;
}
int32_t dispatch(lv_draw_unit_t *,lv_layer_t *layer) {
    auto *task=lv_draw_get_available_task(layer,nullptr,Unit);
    if(!task||!lv_draw_layer_alloc_buf(layer)||!eligible(task))return LV_DRAW_UNIT_IDLE;
    task->state=LV_DRAW_TASK_STATE_IN_PROGRESS;
    auto *d=lv_draw_task_get_label_dsc(task);auto key=*d;key.base={};key.text=nullptr;key.hint=nullptr;
    const int width=lv_area_get_width(&task->area)+8,height=lv_area_get_height(&task->area);
    lv_area_t area=task->area;area.x1-=4;area.x2+=4;
    size_t index=Slots,oldest=0;bool hit=false;
    for(size_t i=0;i<Slots;i++){
        if(hot[i].used<hot[oldest].used)oldest=i;
        if(hot[i].used&&hot[i].object==d->base.obj){index=i;break;}
    }
    if(index<Slots){const auto &entry=storage->entries[index];hit=entry.width==width&&entry.height==height
        &&!std::memcmp(&entry.key,&key,sizeof(key))&&!std::strcmp(entry.text,d->text);}
    const uint32_t stride=lv_draw_buf_width_to_stride(width,LV_COLOR_FORMAT_A8);
    if(!hit){
        if(index==Slots)index=oldest;
        ++stats.misses;auto *mask=storage->pixels[index];std::memset(mask,0,stride*height);
        lv_draw_buf_t buffer{};
        if(lv_draw_buf_init(&buffer,width,height,LV_COLOR_FORMAT_A8,stride,mask,Width*Height)!=LV_RESULT_OK){
            lv_draw_sw_label(task,d,&task->area);
            task->state=LV_DRAW_TASK_STATE_FINISHED;lv_draw_dispatch_request();return 1;
        }else{
            lv_layer_t cached;lv_layer_init(&cached);cached.draw_buf=&buffer;cached.color_format=LV_COLOR_FORMAT_A8;
            cached.buf_area=cached._clip_area=cached.phy_clip_area=area;
            auto draw=*task;draw.target_layer=&cached;draw.clip_area=area;
            lv_draw_sw_label(&draw,d,&task->area);
            auto &entry=storage->entries[index];entry.key=key;std::strcpy(entry.text,d->text);entry.width=width;entry.height=height;
        }
    }else ++stats.hits;
    hot[index]={d->base.obj,++generation};
    lv_draw_sw_blend_dsc_t blend{};blend.blend_area=&area;blend.color=d->color;blend.opa=d->opa;
    blend.mask_buf=storage->pixels[index];blend.mask_area=&area;blend.mask_stride=stride;blend.mask_res=LV_DRAW_SW_MASK_RES_CHANGED;
    lv_draw_sw_blend(task,&blend);
    task->state=LV_DRAW_TASK_STATE_FINISHED;lv_draw_dispatch_request();return 1;
}
}
LabelCacheStats transparent_label_cache_stats(){auto result=stats;result.bytes=storage?sizeof(Storage):0;return result;}
void initialize_transparent_label_cache(){
#ifndef MOTONAV_DISABLE_LABEL_CACHE
    if(initialized)return;
    auto *unit=static_cast<lv_draw_unit_t *>(lv_draw_create_unit(sizeof(lv_draw_unit_t)));if(!unit)return;
    unit->name="MotoNav text masks";unit->evaluate_cb=evaluate;unit->dispatch_cb=dispatch;
    unit->delete_cb=[](lv_draw_unit_t *)->int32_t{lv_free(storage);storage=nullptr;for(auto &entry:hot)entry={};stats={};generation=0;initialized=false;return 0;};
    initialized=true;
#endif
}
}
