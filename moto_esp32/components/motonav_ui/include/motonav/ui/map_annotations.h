#pragma once
#include "motonav/core/map_labels.h"
#include "motonav/graphics/map_projection.h"
#include "lvgl.h"

namespace motonav {
// Fixed UI pool; no driver, provider or navigation-liveness dependency.
struct MapAnnotations {
    static constexpr unsigned Capacity=8;
    static constexpr unsigned IconCapacity=12;
    lv_obj_t *root=nullptr;
    lv_obj_t *text[Capacity]{},*icon[IconCapacity]{};
    unsigned visible=0,visible_icons=0;
    bool layout_pending=false;
    uint32_t revision=0;
    uint32_t generation=0;
    int widths[MapLabels::Capacity]{};
    struct Retained {
        uint64_t key=0;
        uint32_t blocked_at=0;
        uint8_t side=0,icon_size=0;
        bool text=false,icon=false,has_side=false,blocked=false;
    } retained[MapLabels::Capacity]{};
    uint64_t text_keys[Capacity]{},icon_keys[IconCapacity]{};
};
struct MapAnnotationOptions {
    unsigned names=6,icons=12;
    int top=140;
    bool current_road=true;
};
void create_map_annotations(lv_obj_t *parent,MapAnnotations &view);
void update_map_annotations(MapAnnotations &view,const MapLabels &labels,const MapCamera &camera,
                            const MapPoint *route,size_t count,bool position,
                            MapAnnotationOptions options={});
}
