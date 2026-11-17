#pragma once
#include "motonav/core/navigation_details.h"
#include "motonav/core/route_scene.h"
#include <cstring>
#include <cstdlib>

namespace motonav {
struct MapLabel {
    uint8_t kind=0; // road, place, landmark, fuel, parking, food, medical, transit, park
    RoutePoint point{};
    char name[64]{};
};
struct MapLabels {
    static constexpr unsigned Capacity=24;
    uint32_t revision=0;
    uint32_t generation=0;
    uint8_t count=0;
    MapLabel items[Capacity]{};
    uint8_t sources=1; // 1 OpenStreetMap, 2 AMap, 3 both. Negotiated by NAV4S7.
};

// The transport validates frame CRC/session/sequence before calling this receiver.
// Entire chunks validate before mutation; publication requires the complete set.
class MapLabelReceiver {
public:
    void clear(){staging_={};received_=0;}
    bool accept(const uint8_t *bytes,size_t size,uint32_t revision,uint32_t generation,MapLabels &published) {
        if(size<28)return false;
        const bool attributed=bytes[4]==18;
        if(size<(attributed?29u:28u)||!revision||!generation||(published.revision==revision&&generation<=published.generation))return false;
        const unsigned sources=attributed?bytes[24]:1,header=attributed?25:24;
        if(sources<1||sources>3)return false;
        auto u16=[&](size_t p)->unsigned{return bytes[p]|unsigned(bytes[p+1])<<8;};
        auto i16=[&](size_t p)->int{unsigned n=u16(p);return n<32768?int(n):int(n)-65536;};
        unsigned total=bytes[21],offset=bytes[22],count=bytes[23];
        if(total>MapLabels::Capacity||count>(attributed?3u:4u)||offset+count>total||(!count&&total))return false;
        if(offset&&(staging_.revision!=revision||staging_.generation!=generation||staging_.count!=total||staging_.sources!=sources||received_!=offset))return false;
        size_t at=header;
        for(unsigned i=0;i<count;i++) {
            if(at+6>size-4)return false;
            unsigned length=bytes[at+5];
            if(bytes[at]>8||std::abs(i16(at+1))>2048||std::abs(i16(at+3))>2048
                    ||!length||length>63||at+6+length>size-4||!valid_road_name(bytes+at+6,length))return false;
            at+=6+length;
        }
        if(at!=size-4)return false;
        if(!offset){staging_={};staging_.revision=revision;staging_.generation=generation;staging_.count=total;staging_.sources=sources;received_=0;}
        at=header;
        for(unsigned i=0;i<count;i++) {
            auto &label=staging_.items[offset+i];unsigned length=bytes[at+5];
            label.kind=bytes[at];label.point={static_cast<int16_t>(i16(at+1)),static_cast<int16_t>(i16(at+3))};
            std::memcpy(label.name,bytes+at+6,length);at+=6+length;
        }
        received_+=count;
        if(received_==total)published=staging_;
        return true;
    }
private:
    MapLabels staging_;
    unsigned received_=0;
};
}
