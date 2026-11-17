#pragma once
#include "motonav/graphics/route_map.h"
#include "motonav/ui/map_annotations.h"
namespace motonav {
MapCamera render_map_benchmark(lv_obj_t *canvas, uint64_t elapsed_ms);
void render_map_benchmark_labels(MapAnnotations &view,const MapCamera &camera,MapAnnotationOptions options={});
}
