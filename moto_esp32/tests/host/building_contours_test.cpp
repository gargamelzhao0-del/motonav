#include "motonav/graphics/building_contours.h"
#include <cassert>
#include <iostream>
using namespace motonav;
int main(){
    BuildingContours contours;
    MapFeature square[]{{3,{0,0},{100,0},{100,100}},{3,{0,0},{0,100},{100,100}}};
    assert(contours.prepare(square,2)&&contours.count()==4);
    for(size_t i=0;i<contours.count();i++){auto e=contours.edge(i);assert(e.a.x==e.b.x||e.a.y==e.b.y);}
    MapFeature duplicates[]{square[0],square[1],square[0]};
    assert(contours.prepare(duplicates,3)&&contours.count()==4);
    const MapPoint a{0,0},b{120,0},c{120,120},d{0,120};
    const MapPoint e{40,40},f{80,40},g{80,80},h{40,80};
    MapFeature courtyard[]{{3,a,b,f},{3,a,f,e},{3,b,c,g},{3,b,g,f},
        {3,c,d,h},{3,c,h,g},{3,d,a,e},{3,d,e,h}};
    assert(contours.prepare(courtyard,8)&&contours.count()==8);
    unsigned inner=0;for(size_t i=0;i<contours.count();i++){
        auto edge=contours.edge(i);if(edge.a.x>=40&&edge.a.x<=80&&edge.a.y>=40&&edge.a.y<=80)inner++;
    }assert(inner==4);
    MapFeature mixed[]{{4,{0,0},{10,0},{10,10}},{3,{0,0},{10,10},{20,20}}};
    assert(contours.prepare(mixed,2)&&!contours.count());
    MapFeature maximum[384];
    for(int i=0;i<384;i++){
        int16_t x=static_cast<int16_t>(i%24*70-1000),y=static_cast<int16_t>(i/24*100-750);
        maximum[i]={3,{x,y},{static_cast<int16_t>(x+20),y},{x,static_cast<int16_t>(y+20)}};
    }
    assert(contours.prepare(maximum,384)&&contours.count()==BuildingContours::Capacity);
    assert(!contours.prepare(maximum,385)&&contours.count()==BuildingContours::Capacity);
    maximum[0].a.x=3000;assert(!contours.prepare(maximum,384));
    assert(contours.prepare(nullptr,0)&&!contours.count());
    std::cout<<"PASS: real footprint boundaries, shared diagonals, duplicates, winding, courtyards and limits\n";
}
