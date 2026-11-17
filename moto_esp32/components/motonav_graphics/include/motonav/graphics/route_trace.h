#pragma once
#include "motonav/graphics/map_projection.h"
#include <limits>

namespace motonav {
// Visual progress within a local polyline, never a source of navigation or GPS
// validity. Reject distant and ambiguous matches instead of choosing a road.
class RouteTrace {
public:
    struct Cursor { bool valid=false; float meters=0, index=-1; };
    bool prepare(const MapPoint *points,size_t count) {
        count_=0;
        if(!points||count<2||count>64)return false;
        length_[0]=0;
        for(size_t i=0;i<count;i++) {
            points_[i]=points[i];
            if(i){float dx=float(points[i].x)-points[i-1].x,dy=float(points[i].y)-points[i-1].y;
                length_[i]=length_[i-1]+std::sqrt(dx*dx+dy*dy);}
        }
        if(length_[count-1]<.001f)return false;
        count_=count;return true;
    }
    Cursor locate(float x,float y)const {
        if(!count_||!std::isfinite(x)||!std::isfinite(y))return {};
        float best=std::numeric_limits<float>::max();Cursor cursor;
        for(size_t i=1;i<count_;i++) {
            float t,d;if(!project(i,x,y,t,d))continue;
            if(d<best){best=d;cursor={true,length_[i-1]+t*(length_[i]-length_[i-1]),float(i-1)+t};}
        }
        if(!cursor.valid||best>12*12)return {};
        // Crossings and close parallel legs can have different route progress.
        // Adjacent projections around one vertex are the same local section.
        for(size_t i=1;i<count_;i++) {
            float t,d;if(!project(i,x,y,t,d))continue;
            float meters=length_[i-1]+t*(length_[i]-length_[i-1]);
            if(d<=best+16&&std::abs(meters-cursor.meters)>25)return {};
        }
        return cursor;
    }
    ProjectedPoint position(float meters)const {
        if(!count_||!std::isfinite(meters))return {};
        meters=std::clamp(meters,0.0f,length_[count_-1]);
        for(size_t i=1;i<count_;i++) {
            float length=length_[i]-length_[i-1];
            if(length<.001f||meters>length_[i])continue;
            float t=(meters-length_[i-1])/length;
            return {points_[i-1].x+(points_[i].x-points_[i-1].x)*t,
                    points_[i-1].y+(points_[i].y-points_[i-1].y)*t};
        }
        return {float(points_[count_-1].x),float(points_[count_-1].y)};
    }
private:
    bool project(size_t i,float x,float y,float &t,float &distance)const {
        float dx=float(points_[i].x)-points_[i-1].x,dy=float(points_[i].y)-points_[i-1].y;
        float squared=dx*dx+dy*dy;if(squared<.001f)return false;
        t=std::clamp(((x-points_[i-1].x)*dx+(y-points_[i-1].y)*dy)/squared,0.0f,1.0f);
        float ex=x-(points_[i-1].x+t*dx),ey=y-(points_[i-1].y+t*dy);
        distance=ex*ex+ey*ey;return true;
    }
    MapPoint points_[64]{};
    float length_[64]{};
    size_t count_=0;
};
}
