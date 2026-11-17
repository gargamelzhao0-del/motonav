#include "motonav/ui/map_annotations.h"
#include "motonav/ui/dashboard.h"
#include "motonav/ui/fonts.h"
#include <algorithm>
#include <cmath>

namespace motonav {
namespace {
struct Box { int left,top,right,bottom; };
bool overlap(Box a,Box b){return a.left<=b.right&&a.right>=b.left&&a.top<=b.bottom&&a.bottom>=b.top;}
bool circle(Box a){
    for(int x:{a.left,a.right})for(int y:{a.top,a.bottom})
        if((x-233)*(x-233)+(y-233)*(y-233)>211*211)return false;
    return a.top>=140&&a.bottom<=337;
}
bool crosses(Box r,ProjectedPoint a,ProjectedPoint b) {
    if(std::max(a.x,b.x)<r.left||std::min(a.x,b.x)>r.right||
       std::max(a.y,b.y)<r.top||std::min(a.y,b.y)>r.bottom)return false;
    float lo=0,hi=1,dx=b.x-a.x,dy=b.y-a.y;
    const float p[]{-dx,dx,-dy,dy},q[]{a.x-r.left,r.right-a.x,a.y-r.top,r.bottom-a.y};
    for(unsigned i=0;i<4;i++){
        if(p[i]==0){if(q[i]<0)return false;continue;}
        float t=q[i]/p[i];
        if(p[i]<0){if(t>hi)return false;lo=std::max(lo,t);}
        else {if(t<lo)return false;hi=std::min(hi,t);}
    }
    return true;
}
uint32_t color(unsigned kind){
    switch(kind){case 0:return 0xc4cdd1;case 1:return 0xe6e2d4;case 3:return 0xefb956;
        case 4:return 0x8ec7f3;case 5:return 0xf0b899;case 6:return 0xf4a6ab;case 7:return 0xabbcf7;
        case 8:return 0x98caa4;default:return 0xd4c5f3;}
}
const char *icon(unsigned kind){
    switch(kind){case 3:return "油";case 4:return "P";case 5:return "餐";case 6:return "+";
        case 7:return "站";case 8:return "园";default:return "·";}
}
}
void create_map_annotations(lv_obj_t *parent,MapAnnotations &view){
    view={};
    view.root=lv_obj_create(parent);lv_obj_remove_style_all(view.root);lv_obj_set_size(view.root,466,466);
    lv_obj_remove_flag(view.root,static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE));
    for(unsigned i=0;i<MapAnnotations::Capacity;i++){
        for(auto **slot:{&view.text[i],&view.icon[i]}){
            *slot=lv_label_create(view.root);lv_obj_remove_style_all(*slot);
            lv_obj_set_style_text_font(*slot,&motonav_ui_18,0);
            lv_obj_set_style_text_align(*slot,LV_TEXT_ALIGN_CENTER,0);
            lv_label_set_long_mode(*slot,LV_LABEL_LONG_DOT);
            lv_obj_set_style_bg_color(*slot,lv_color_hex(0x182125),0);
            lv_obj_set_style_bg_opa(*slot,LV_OPA_COVER,0);
            lv_obj_set_style_radius(*slot,3,0);
            lv_obj_add_flag(*slot,LV_OBJ_FLAG_HIDDEN);
        }
        lv_obj_set_style_pad_hor(view.text[i],3,0);lv_obj_set_height(view.text[i],25);
        lv_obj_set_size(view.icon[i],22,25);
    }
}
void update_map_annotations(MapAnnotations &view,const MapLabels &labels,const MapCamera &camera,
                            const MapPoint *route,size_t count,bool position){
    if(!view.root)return;
    if(view.revision!=labels.revision||view.generation!=labels.generation){
        for(unsigned i=0;i<labels.count&&i<MapLabels::Capacity;i++){
            lv_point_t measured{};
            lv_text_get_size(&measured,labels.items[i].name,&motonav_ui_18,0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
            view.widths[i]=std::clamp<int>(measured.x+6,24,150);
        }
        view.generation=labels.generation;
        view.revision=labels.revision;
    }
    MapProjection projection(466,466,camera);
    Box occupied[MapAnnotations::Capacity*2+2];unsigned boxes=0;
    // Reserve the measured-position marker and the current-road HUD.
    if(position){int y=std::lround(466*camera.anchor_y);occupied[boxes++]={203,y-30,263,y+30};}
    occupied[boxes++]={262,296,413,332};
    ProjectedPoint line[64];count=std::min<size_t>(count,64);
    for(size_t i=0;i<count;i++)line[i]=projection.project(route[i]);
    auto available=[&](Box box){
        if(!circle(box))return false;
        for(unsigned j=0;j<boxes;j++)if(overlap(box,occupied[j]))return false;
        Box margin{box.left-6,box.top-6,box.right+6,box.bottom+6};
        for(size_t j=1;j<count;j++)if(crosses(margin,line[j-1],line[j]))return false;
        return true;
    };
    unsigned used=0;
    for(unsigned i=0;i<labels.count&&i<MapLabels::Capacity&&used<MapAnnotations::Capacity;i++){
        const auto &label=labels.items[i];
        auto p=projection.project({label.point.x,label.point.y});
        if(p.x<15||p.x>451||p.y<137||p.y>337)continue;
        int x=std::lround(p.x),y=std::lround(p.y);bool poi=label.kind>=2;
        int width=view.widths[i],height=25;
        Box marker{x-11,y-12,x+10,y+12};
        if(poi&&!available(marker))continue;
        int gap=poi?16:10;
        const Box choices[]{
            {x+gap,y-12,x+gap+width-1,y+height-13},
            {x-gap-width,y-12,x-gap-1,y+height-13},
            {x-width/2,y-gap-height,x+(width+1)/2-1,y-gap-1},
            {x-width/2,y+gap,x+(width+1)/2-1,y+gap+height-1}};
        bool placed=false;Box selected{};
        for(auto box:choices)if(available(box)){selected=box;placed=true;break;}
        if(!placed)continue;
        auto *text=view.text[used];set_label_text(text,label.name);
        lv_obj_set_pos(text,selected.left,selected.top);lv_obj_set_size(text,width,height);
        const auto ink=lv_color_hex(color(label.kind));
        if(!lv_color_eq(lv_obj_get_style_text_color(text,LV_PART_MAIN),ink))lv_obj_set_style_text_color(text,ink,0);
        lv_obj_remove_flag(text,LV_OBJ_FLAG_HIDDEN);
        auto *symbol=view.icon[used];lv_obj_set_flag(symbol,LV_OBJ_FLAG_HIDDEN,!poi);
        if(poi){
            set_label_text(symbol,icon(label.kind));lv_obj_set_pos(symbol,marker.left,marker.top);
            if(!lv_color_eq(lv_obj_get_style_text_color(symbol,LV_PART_MAIN),ink))lv_obj_set_style_text_color(symbol,ink,0);
            occupied[boxes++]={marker.left-3,marker.top-3,marker.right+3,marker.bottom+3};
        }
        occupied[boxes++]={selected.left-4,selected.top-3,selected.right+4,selected.bottom+3};
        used++;
    }
    for(unsigned i=used;i<MapAnnotations::Capacity;i++){
        lv_obj_add_flag(view.text[i],LV_OBJ_FLAG_HIDDEN);lv_obj_add_flag(view.icon[i],LV_OBJ_FLAG_HIDDEN);
    }
    view.visible=used;
}
}
