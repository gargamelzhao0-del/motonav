#include "motonav/graphics/map_camera_motion.h"
#include "motonav/graphics/map_camera_profile.h"
#include <cassert>
#include <iostream>
#include <limits>
using namespace motonav;
static bool close(float a,float b,float epsilon=.001f){return std::abs(a-b)<epsilon;}
int main(){
    const MapPoint corner[]{{0,0},{0,100},{100,100}};
    RouteTrace route;assert(route.prepare(corner,3));
    auto cursor=route.locate(0,60);assert(cursor.valid&&close(cursor.meters,60)&&close(cursor.index,.6f));
    assert(!route.locate(30,50).valid);
    assert(!route.locate(std::numeric_limits<float>::quiet_NaN(),0).valid);
    const MapPoint crossing[]{{-50,-50},{50,50},{-50,50},{50,-50}};
    RouteTrace ambiguous;assert(ambiguous.prepare(crossing,4));assert(!ambiguous.locate(0,0).valid);
    const MapPoint parallel[]{{0,0},{0,100},{4,100},{4,0}};
    assert(ambiguous.prepare(parallel,4));assert(!ambiguous.locate(2,30).valid);
    const MapPoint duplicate[]{{0,0},{0,0},{0,100}};
    assert(ambiguous.prepare(duplicate,3));assert(ambiguous.locate(0,20).valid);
    const MapPoint empty[]{{0,0},{0,0}};assert(!ambiguous.prepare(empty,2));
    assert(!ambiguous.locate(0,0).valid);
    MapCameraMotion motion;
    MapCamera start{0,60,0,1,0,.7f},target{40,100,1.570796327f,.6f,.8f,.65f};
    motion.update(start,1000,true,route,true);
    motion.update(target,1000,false,route,true);
    target.heading=1.4f;motion.update(target,1100,false,route,true);
    auto mid=motion.update(target,1200,false,route,true);
    assert(close(mid.x,0)&&close(mid.y,100)); // Traverse the bend, not the chord.
    auto end=motion.update(target,1400,false,route,true);
    assert(close(end.x,40)&&close(end.y,100)); // Heading changes cannot restart position.
    assert(end.scale<1&&end.scale>.6f&&end.perspective>0&&end.perspective<.8f);
    auto held=motion.update(target,2400,false,route,true);
    assert(close(held.x,40)&&close(held.y,100)); // No dead reckoning after input stops.
    motion.update(start,0,true,route,false);motion.update(target,0,false,route,false);
    auto raw=motion.update(target,200,false,route,false);
    assert(close(raw.x,20)&&close(raw.y,80)); // Weak observations are never snapped.
    MapCameraMotion fine,coarse;
    MapCamera zero{0,0,0,1};target=zero;target.heading=.2f;target.scale=.6f;
    fine.update(zero,0,true,route,false);coarse.update(zero,0,true,route,false);
    for(unsigned t=20;t<=1000;t+=20)fine.update(target,t,false,route,false);
    for(unsigned t=50;t<=1000;t+=50)coarse.update(target,t,false,route,false);
    assert(close(fine.value().heading,coarse.value().heading));
    assert(close(fine.value().scale,coarse.value().scale));
    start.heading=3.13f;target=start;target.heading=-3.13f;
    motion.update(start,0,true,route,false);
    auto wrapped=motion.update(target,100,false,route,false);
    assert(std::abs(std::remainder(wrapped.heading-start.heading,6.283185307f))<.03f);
    start={0,0,0,1};target=start;target.y=40;
    motion.update(start,UINT32_MAX-100,true,route,true);
    motion.update(target,UINT32_MAX-100,false,route,true);
    auto rolled=motion.update(target,299,false,route,true);assert(close(rolled.y,40));
    target={500,500,1,.8f};auto reset=motion.update(target,500,true,route,false);
    assert(close(reset.x,500)&&close(reset.scale,.8f));
    // The installed 0.7.21 phone selects coverage using the older 50-degree
    // camera plus a 120 m margin. Every visible point of the new round view
    // must remain inside that envelope; changing firmware must not expose gaps.
    for(bool live:{false,true})for(int speed=0;speed<=300;speed+=5)for(int angle=0;angle<360;angle+=15){
        auto current=map_camera_profile(true,live,speed);current.heading=angle*.01745329252f;
        const float old_zoom=live?.85f+std::clamp((speed-12)/68.0f,0.0f,1.0f)*1.6f:1.13f;
        const float old_pitch=.872664626f*(live?std::clamp((speed-6)/16.0f,0.0f,1.0f):1.0f);
        MapCamera old{0,0,current.heading,1/old_zoom,old_pitch,(270+36*old_pitch/.872664626f)/466};
        MapProjection previous(466,466,old),next(466,466,current);
        float left=0,right=0,bottom=0,top=0;
        for(float sx:{-24.0f,490.0f})for(float sy:{64.0f,490.0f}){
            float x,y;assert(previous.unproject({sx,sy},x,y));
            left=std::min(left,x);right=std::max(right,x);bottom=std::min(bottom,y);top=std::max(top,y);
        }
        for(int a=0;a<360;a+=5){
            float x,y,radians=a*.01745329252f;
            assert(next.unproject({233+233*std::cos(radians),233+233*std::sin(radians)},x,y));
            assert(x>=left-120&&x<=right+120&&y>=bottom-120&&y<=top+120);
        }
    }
    std::cout<<"PASS: independent motion channels, route bends, ambiguity, expiry hold and clock wrap\n";
    std::cout<<"PASS: refined camera stays within the installed phone's map coverage envelope\n";
}
