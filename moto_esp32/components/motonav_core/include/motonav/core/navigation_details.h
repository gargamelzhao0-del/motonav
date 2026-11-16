#pragma once
#include <cstddef>
#include <cstdint>

namespace motonav {
struct NavigationDetails {
    static constexpr unsigned MaxNameBytes=63;
    uint32_t revision=0;
    int eta_minute=-1, speed_limit=-1;
    char current_road[MaxNameBytes+1]{}, next_road[MaxNameBytes+1]{};
};

// Reject malformed/overlong UTF-8, control characters and bidirectional overrides.
// Unsupported printable glyphs remain LVGL's visible missing-glyph placeholder.
inline bool valid_road_name(const uint8_t *text,size_t size) {
    for(size_t i=0;i<size;) {
        uint32_t cp=text[i++];unsigned tail=0;uint32_t minimum=0;
        if(cp>=0xc2&&cp<=0xdf){cp&=31;tail=1;minimum=0x80;}
        else if(cp>=0xe0&&cp<=0xef){cp&=15;tail=2;minimum=0x800;}
        else if(cp>=0xf0&&cp<=0xf4){cp&=7;tail=3;minimum=0x10000;}
        else if(cp>=0x80)return false;
        if(i+tail>size)return false;
        while(tail--){uint8_t next=text[i++];if((next&0xc0)!=0x80)return false;cp=(cp<<6)|(next&63);}
        if(cp<minimum||cp>0x10ffff||(cp>=0xd800&&cp<=0xdfff)||cp<32||(cp>=0x7f&&cp<=0x9f)
                ||(cp>=0x200b&&cp<=0x200f)||(cp>=0x202a&&cp<=0x202e)||(cp>=0x2060&&cp<=0x206f)||cp==0xfeff)return false;
    }
    return true;
}
}
