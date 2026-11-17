#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace motonav {
// Conservative screen-space bins, indexed by painter order. A 720-byte mask
// avoids scanning every PSRAM feature for each independently flushed strip.
class MapStripIndex {
public:
    static constexpr size_t Words=12,Capacity=Words*32;
    void reset(){for(auto &band:bands_)for(auto &word:band)word=0;}
    void add(size_t item,int top,int bottom) {
        if(item>=Capacity||top>bottom||bottom<0||top>=466)return;
        for(int band=std::max(0,top)/32;band<=std::min(465,bottom)/32;band++)
            bands_[band][item/32]|=uint32_t(1)<<(item%32);
    }
    uint32_t word(size_t block,int top,int bottom)const {
        if(block>=Words||top>bottom||bottom<0||top>=466)return 0;
        uint32_t result=0;
        for(int band=std::max(0,top)/32;band<=std::min(465,bottom)/32;band++)result|=bands_[band][block];
        return result;
    }
private:
    uint32_t bands_[15][Words]{};
};
}
