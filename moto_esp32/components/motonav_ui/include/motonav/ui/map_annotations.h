#pragma once
#include "motonav/core/map_labels.h"
#include "motonav/graphics/map_projection.h"
#include "lvgl.h"

namespace motonav {
// Fixed UI pool; no driver, provider or navigation-liveness dependency.
struct MapAnnotations {
    static constexpr unsigned Capacity=8;
    lv_obj_t *root=nullptr;
    lv_obj_t *text[Capacity]{},*icon[Capacity]{};
    unsigned visible=0;
    uint32_t revision=0;
    uint32_t generation=0;
    int widths[MapLabels::Capacity]{};
};
void create_map_annotations(lv_obj_t *parent,MapAnnotations &view);
void update_map_annotations(MapAnnotations &view,const MapLabels &labels,const MapCamera &camera,
                            const MapPoint *route,size_t count,bool position);
}
