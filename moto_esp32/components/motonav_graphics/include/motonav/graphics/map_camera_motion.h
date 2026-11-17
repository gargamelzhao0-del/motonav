#pragma once
#include "motonav/graphics/route_trace.h"

namespace motonav {
// Display-only interpolation toward received observations. No extrapolation,
// inferred speed, liveness extension, allocation or dependency on LVGL ticks.
class MapCameraMotion {
public:
    const MapCamera &value()const{return value_;}
    MapCamera update(const MapCamera &target,uint32_t now,bool reset,
                     const RouteTrace &route,bool follow_route) {
        if(reset||!initialized_) {
            value_=target;value_.heading=std::remainder(target.heading,TwoPi);
            target_=from_=value_;last_at_=position_at_=now;
            initialized_=true;on_route_=false;return value_;
        }
        const float dt=std::min<uint32_t>(now-last_at_,200)*.001f;last_at_=now;
        const float fraction=std::min(uint32_t(now-position_at_)/400.0f,1.0f);
        value_.x=from_.x+(target_.x-from_.x)*fraction;
        value_.y=from_.y+(target_.y-from_.y)*fraction;
        if(on_route_&&follow_route&&fraction<1) {
            auto p=route.position(start_m_+(end_m_-start_m_)*fraction);
            value_.x=p.x+start_offset_.x+(end_offset_.x-start_offset_.x)*fraction;
            value_.y=p.y+start_offset_.y+(end_offset_.y-start_offset_.y)*fraction;
        }
        if(target.x!=target_.x||target.y!=target_.y) {
            from_=value_;position_at_=now;on_route_=false;
            const float dx=target.x-from_.x,dy=target.y-from_.y;
            if(dx*dx+dy*dy>200*200) {
                value_.x=from_.x=target.x;value_.y=from_.y=target.y;
            } else if(follow_route) {
                auto a=route.locate(from_.x,from_.y),b=route.locate(target.x,target.y);
                if(a.valid&&b.valid&&std::abs(b.meters-a.meters)<=200) {
                    auto pa=route.position(a.meters),pb=route.position(b.meters);
                    start_m_=a.meters;end_m_=b.meters;
                    start_offset_={from_.x-pa.x,from_.y-pa.y};
                    end_offset_={target.x-pb.x,target.y-pb.y};on_route_=true;
                }
            }
        }
        target_=target;
        float turn=std::remainder(target.heading-value_.heading,TwoPi);
        float step=turn*(1-std::exp(-dt/.22f));
        const float limit=dt*1.570796327f; // At most 90 degrees/second.
        value_.heading=std::remainder(value_.heading+std::clamp(step,-limit,limit),TwoPi);
        if(std::abs(turn)<.0001f)value_.heading=std::remainder(target.heading,TwoPi);
        value_.scale=approach(value_.scale,target.scale,dt,.8f);
        value_.perspective=approach(value_.perspective,target.perspective,dt,.6f);
        value_.anchor_y=approach(value_.anchor_y,target.anchor_y,dt,.6f);
        return value_;
    }
private:
    static float approach(float current,float target,float dt,float seconds) {
        if(std::abs(current-target)<.0001f)return target;
        return current+(target-current)*(1-std::exp(-dt/seconds));
    }
    static constexpr float TwoPi=6.283185307f;
    MapCamera value_{},target_{},from_{};
    ProjectedPoint start_offset_{},end_offset_{};
    float start_m_=0,end_m_=0;
    uint32_t last_at_=0,position_at_=0;
    bool initialized_=false,on_route_=false;
};
}
