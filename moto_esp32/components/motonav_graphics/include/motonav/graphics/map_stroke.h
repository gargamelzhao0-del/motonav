#pragma once
#include "motonav/graphics/map_raster.h"

namespace motonav {
// A road surface, not a fixed-width stroke on the projected centerline.
// Width is specified in the simulator's unpitched pixels. No allocation.
struct MapStroke {
    MapRaster::PreparedPolygon body;
    MapRaster::PreparedEllipse start,end;
    bool prepare(const MapProjection &projection,MapPoint a,MapPoint b,float width) {
        ProjectedPoint screenA,screenB;
        if(!projection.segment(a,b,screenA,screenB,20)){*this={};return false;}
        return prepare_projected(projection,a,b,screenA,screenB,width);
    }
    // Share the clipped centerline between the road fill and its outer edge.
    bool prepare_projected(const MapProjection &projection,MapPoint a,MapPoint b,
                           ProjectedPoint screenA,ProjectedPoint screenB,float width) {
        *this={};
        if(!projection.valid()||!std::isfinite(width)||width<=0||width>16)return false;
        const auto normal=projection.plane_normal(a,b,width*.5f);
        if(normal.x==0&&normal.y==0)return false;
        ProjectedPoint corners[]{projection.offset(screenA,normal.x,normal.y),projection.offset(screenB,normal.x,normal.y),
                                projection.offset(screenB,-normal.x,-normal.y),projection.offset(screenA,-normal.x,-normal.y)};
        body=MapRaster::PreparedPolygon(corners,4);
        auto cap=[&](ProjectedPoint center) {
            ProjectedPoint side,forward;projection.disk_axes(center,width*.5f,side,forward);
            return MapRaster::PreparedEllipse(center,side,forward);
        };
        start=cap(screenA);end=cap(screenB);return true;
    }
    bool intersects_y(int top,int bottom)const{return body.intersects_y(top,bottom)||start.intersects_y(top,bottom)||end.intersects_y(top,bottom);}
    void paint(MapRaster &raster,uint16_t color,bool antialias=false)const{
        raster.polygon(body,color,antialias);raster.ellipse(start,color,antialias);raster.ellipse(end,color,antialias);
    }
};
}
