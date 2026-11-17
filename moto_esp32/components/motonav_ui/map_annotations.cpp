#include "motonav/ui/map_annotations.h"
#include "motonav/ui/fonts.h"
#include "map_caption.h"
#include "map_symbols.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

namespace motonav {
namespace {
struct Box { int left,top,right,bottom; };
bool overlap(Box a,Box b){return a.left<=b.right&&a.right>=b.left&&a.top<=b.bottom&&a.bottom>=b.top;}
bool inside(Box a,bool retained,int top){
    const int radius=retained?211:205;
    for(int x:{a.left,a.right})for(int y:{a.top,a.bottom})
        if((x-233)*(x-233)+(y-233)*(y-233)>radius*radius)return false;
    return a.top>=top&&a.bottom<=337;
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
uint64_t identity(const MapLabel &label){
    uint64_t key=14695981039346656037ULL;
    auto add=[&](uint8_t value){key=(key^value)*1099511628211ULL;};
    add(label.kind);for(unsigned shift:{0u,8u}){add(uint16_t(label.point.x)>>shift);add(uint16_t(label.point.y)>>shift);}
    for(const auto *p=reinterpret_cast<const uint8_t *>(label.name);*p;p++)add(*p);
    return key?key:1;
}
bool generic(const MapLabel &label){
    const char *names[]{"","","地标","加油站","停车场","餐饮","医疗","公交站","公园"};
    return label.kind>=2&&label.kind<9&&(!std::strcmp(label.name,names[label.kind])
        ||(label.kind==8&&(!std::strcmp(label.name,"园区")||!std::strcmp(label.name,"自然保护区")))
        ||(label.kind==6&&(!std::strcmp(label.name,"医院")||!std::strcmp(label.name,"诊所")||!std::strcmp(label.name,"药房")))
        ||(label.kind==7&&!std::strcmp(label.name,"车站"))
        ||(label.kind==3&&!std::strcmp(label.name,"充电站")));
}
unsigned slot(uint64_t *keys,unsigned capacity,uint64_t key,uint32_t used){
    for(unsigned i=0;i<capacity;i++)if(keys[i]==key&&!(used&(1u<<i)))return i;
    for(unsigned i=0;i<capacity;i++)if(!(used&(1u<<i))){keys[i]=key;return i;}
    return capacity;
}
}
void create_map_annotations(lv_obj_t *parent,MapAnnotations &view){
    view={};view.root=lv_obj_create(parent);lv_obj_remove_style_all(view.root);lv_obj_set_size(view.root,466,466);
    lv_obj_remove_flag(view.root,static_cast<lv_obj_flag_t>(LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE));
    for(auto &text:view.text)text=create_map_caption(view.root);
    for(auto &icon:view.icon){icon=lv_image_create(view.root);lv_obj_add_flag(icon,LV_OBJ_FLAG_HIDDEN);lv_obj_remove_flag(icon,LV_OBJ_FLAG_CLICKABLE);}
}
void update_map_annotations(MapAnnotations &view,const MapLabels &labels,const MapCamera &camera,
                            const MapPoint *route,size_t count,bool position,MapAnnotationOptions options){
    if(!view.root)return;
    view.layout_pending=false;
    const unsigned total=std::min<unsigned>(labels.count,MapLabels::Capacity);
    if(view.revision!=labels.revision||view.generation!=labels.generation){
        std::array<MapAnnotations::Retained,MapLabels::Capacity> previous;
        std::copy_n(view.retained,MapLabels::Capacity,previous.begin());
        for(unsigned i=0;i<MapLabels::Capacity;i++){
            view.retained[i]={};view.widths[i]=0;if(i>=total)continue;
            const auto key=identity(labels.items[i]);view.retained[i].key=key;
            if(view.revision==labels.revision)for(const auto &old:previous)if(old.key==key){view.retained[i]=old;break;}
            lv_point_t size{};lv_text_get_size(&size,labels.items[i].name,&motonav_ui_16,0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
            view.widths[i]=std::clamp<int>(size.x+4,16,144);
        }
        view.generation=labels.generation;view.revision=labels.revision;
    }
    MapProjection projection(466,466,camera);
    if(!projection.valid()||(count&&!route))return;
    Box occupied[MapAnnotations::Capacity+MapAnnotations::IconCapacity+2];unsigned boxes=0;
    if(position){int y=std::lround(466*camera.anchor_y);occupied[boxes++]={208,y-26,258,y+26};}
    if(options.current_road)occupied[boxes++]={262,296,413,332};
    ProjectedPoint line[64],points[MapLabels::Capacity];count=std::min<size_t>(count,64);
    for(size_t i=0;i<count;i++)line[i]=projection.project(route[i]);
    for(unsigned i=0;i<total;i++)points[i]=projection.project({labels.items[i].point.x,labels.items[i].point.y});
    auto available=[&](Box box,bool retained){
        if(!inside(box,retained,options.top))return false;
        for(unsigned j=0;j<boxes;j++)if(overlap(box,occupied[j]))return false;
        Box margin{box.left-6,box.top-6,box.right+6,box.bottom+6};
        for(size_t j=1;j<count;j++)if(crosses(margin,line[j-1],line[j]))return false;
        return true;
    };
    uint32_t text_used=0,icon_used=0;view.visible=view.visible_icons=0;
    const uint32_t now=lv_tick_get();
    bool icons[MapLabels::Capacity]{};
    int sizes[MapLabels::Capacity]{};
    // Place named landmarks first. Generic symbols use space left after captions.
    auto place_icons=[&](bool unnamed){
      for(unsigned pass=0;pass<2;pass++)for(unsigned i=0;i<total&&view.visible_icons<std::min(options.icons,MapAnnotations::IconCapacity);i++){
        auto &retained=view.retained[i];const auto &label=labels.items[i];
        if(label.kind<2||generic(label)!=unnamed||retained.icon!=(pass==0))continue;
        const auto p=points[i];if(p.x<0||p.x>466||p.y<120||p.y>345)continue;
        int x=std::lround(p.x),y=std::lround(p.y);
        int size=y<195?12:y<265?15:18;
        // Keep the selected icon size through a four-pixel boundary band.
        if(retained.icon_size==12&&y<199)size=12;
        else if(retained.icon_size==15&&y>=191&&y<269)size=15;
        else if(retained.icon_size==18&&y>=261)size=18;
        sizes[i]=size;
        Box box{x-size/2,y-size/2,x-size/2+size-1,y-size/2+size-1};
        if(!available(box,retained.icon))continue;
        unsigned index=slot(view.icon_keys,MapAnnotations::IconCapacity,retained.key,icon_used);
        auto *object=view.icon[index];const auto *symbol=map_symbol(label.kind,size);
        if(lv_image_get_src(object)!=symbol)lv_image_set_src(object,symbol);
        lv_obj_set_pos(object,box.left,box.top);lv_obj_remove_flag(object,LV_OBJ_FLAG_HIDDEN);
        occupied[boxes++]={box.left-3,box.top-3,box.right+3,box.bottom+3};
        icon_used|=1u<<index;icons[i]=true;view.visible_icons++;retained.icon_size=size;
      }
    };
    place_icons(false);
    bool texts[MapLabels::Capacity]{};unsigned road_names=0,place_names=0,poi_names=0;
    for(unsigned pass=0;pass<2;pass++)for(unsigned i=0;i<total&&view.visible<std::min(options.names,MapAnnotations::Capacity);i++){
        auto &retained=view.retained[i];const auto &label=labels.items[i];
        if(retained.text!=(pass==0))continue;
        bool poi=label.kind>=2;if(poi&&generic(label))continue;
        if((label.kind==0&&road_names>=3)||(label.kind==1&&place_names>=3)||(poi&&poi_names>=4))continue;
        auto p=points[i];if(p.x<0||p.x>466||p.y<120||p.y>345)continue;
        int x=std::lround(p.x),y=std::lround(p.y),width=view.widths[i],height=24,gap=icons[i]?sizes[i]/2+5:7;
        const int far=30;
        const Box choices[]{{x+gap,y-height/2,x+gap+width-1,y+height/2-1},
            {x-gap-width,y-height/2,x-gap-1,y+height/2-1},
            {x-width/2,y-gap-height,x+(width+1)/2-1,y-gap-1},
            {x-width/2,y+gap,x+(width+1)/2-1,y+gap+height-1},
            {x+far,y-height/2,x+far+width-1,y+height/2-1},
            {x-far-width,y-height/2,x-far-1,y+height/2-1},
            {x-width/2,y-far-height,x+(width+1)/2-1,y-far-1},
            {x-width/2,y+far,x+(width+1)/2-1,y+far+height-1}};
        int choice=-1;
        if(retained.has_side&&available(choices[retained.side],retained.text)){
            choice=retained.side;retained.blocked=false;
        }else {
            if(retained.has_side){
                if(!retained.blocked){retained.blocked=true;retained.blocked_at=now;}
                if(uint32_t(now-retained.blocked_at)<350){view.layout_pending=true;continue;}
            }
            for(int j=0;j<8;j++)if(available(choices[j],retained.text)){choice=j;break;}
        }
        if(choice<0)continue;
        unsigned index=slot(view.text_keys,MapAnnotations::Capacity,retained.key,text_used);
        auto *object=view.text[index];uint32_t color=label.kind==0?0xbac4c8:label.kind==1?0xdcd8c9:0xd6dcd9;
        if(!set_map_caption(object,label.name,width,color))continue;
        const auto box=choices[choice];lv_obj_set_pos(object,box.left,box.top);lv_obj_remove_flag(object,LV_OBJ_FLAG_HIDDEN);
        occupied[boxes++]={box.left-5,box.top-3,box.right+5,box.bottom+3};
        text_used|=1u<<index;texts[i]=true;retained.side=choice;
        retained.has_side=true;retained.blocked=false;view.visible++;
        if(label.kind==0)road_names++;else if(label.kind==1)place_names++;else poi_names++;
    }
    place_icons(true);
    for(unsigned i=0;i<total;i++){view.retained[i].text=texts[i];view.retained[i].icon=icons[i];}
    for(unsigned i=0;i<MapAnnotations::Capacity;i++)if(!(text_used&(1u<<i)))lv_obj_add_flag(view.text[i],LV_OBJ_FLAG_HIDDEN);
    for(unsigned i=0;i<MapAnnotations::IconCapacity;i++)if(!(icon_used&(1u<<i)))lv_obj_add_flag(view.icon[i],LV_OBJ_FLAG_HIDDEN);
}
}
