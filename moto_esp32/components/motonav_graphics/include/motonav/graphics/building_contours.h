#pragma once
#include "motonav/graphics/map_feature.h"
#include <algorithm>

namespace motonav {
// Recover the supplied footprint boundaries by cancelling oriented shared
// triangle edges. Winding normalization keeps duplicate/reversed triangles
// from erasing the outside, and retains courtyard boundaries. No new geometry.
class BuildingContours {
public:
    static constexpr size_t Capacity=384*3;
    bool prepare(const MapFeature *features,size_t count) {
        if(count>384||(count&&!features))return false;
        for(size_t i=0;i<count;i++)if(features[i].kind==3)
            for(auto p:{features[i].a,features[i].b,features[i].c})
                if(std::abs(int(p.x))>2048||std::abs(int(p.y))>2048)return false;
        size_t used=0;
        auto point=[](MapPoint p){return (uint32_t(int(p.x)+2048)<<13)|uint32_t(int(p.y)+2048);};
        auto edge=[&](MapPoint a,MapPoint b){
            uint32_t x=point(a),y=point(b);if(x==y)return;
            // 13 bits per coordinate; one spare low bit records orientation.
            keys_[used++]=((uint64_t(std::min(x,y))<<26)|std::max(x,y))<<1|(x<y?1u:0u);
        };
        for(size_t i=0;i<count;i++)if(features[i].kind==3) {
            auto a=features[i].a,b=features[i].b,c=features[i].c;
            int32_t area=(int32_t(b.x)-a.x)*(int32_t(c.y)-a.y)-(int32_t(b.y)-a.y)*(int32_t(c.x)-a.x);
            if(!area)continue;
            if(area<0)std::swap(b,c);
            edge(a,b);edge(b,c);edge(c,a);
        }
        std::sort(keys_,keys_+used);count_=0;
        for(size_t i=0;i<used;) {
            const auto key=keys_[i]>>1;unsigned orientations=0;
            do{orientations|=(keys_[i]&1)?1u:2u;i++;}while(i<used&&(keys_[i]>>1)==key);
            if(orientations!=3)keys_[count_++]=key;
        }
        return true;
    }
    size_t count()const{return count_;}
    MapSegment edge(size_t index)const {
        if(index>=count_)return {};
        auto point=[](uint32_t value){return MapPoint{static_cast<int16_t>(int(value>>13)-2048),
            static_cast<int16_t>(int(value&8191)-2048)};};
        return {point(static_cast<uint32_t>(keys_[index]>>26)),point(static_cast<uint32_t>(keys_[index]&0x3ffffff))};
    }
private:
    uint64_t keys_[Capacity]{};
    size_t count_=0;
};
}
