#pragma once
#include <cstdint>

namespace motonav {

// Historical display context only. This never supplies navigation or speed.
struct MapPositionObservation {
    uint32_t revision=0;
    int16_t x=0,y=0,heading=-1;
    uint16_t accuracy_m=0;
    uint8_t quality=0;
    uint32_t age_ms=0;
};

class MapPositionHistory {
public:
    static constexpr uint32_t MaxAgeMs=60000;
    void clear(){position_={};received_at_=0;}
    bool remember(const MapPositionObservation &position,uint64_t now){
        if(!position.revision||position.quality<1||position.quality>2||!position.accuracy_m
                ||position.accuracy_m>10000||(position.quality==2&&position.accuracy_m>100)
                ||position.age_ms>=15000||position.x<-32000||position.x>32000
                ||position.y<-32000||position.y>32000||position.heading<-1||position.heading>3599)return false;
        position_=position;received_at_=now;return true;
    }
    MapPositionObservation last(uint64_t now,uint32_t revision) const {
        if(!position_.quality||position_.revision!=revision||now<received_at_||now-received_at_>=MaxAgeMs)return {};
        auto result=position_;
        result.age_ms+=static_cast<uint32_t>(now-received_at_);
        return result.age_ms<MaxAgeMs?result:MapPositionObservation{};
    }
private:
    MapPositionObservation position_{};
    uint64_t received_at_=0;
};
}
