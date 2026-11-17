#include "motonav/graphics/map_stroke.h"
#include <cassert>
#include <iostream>
#include <vector>
using namespace motonav;
int main(){
    constexpr int width=466,guard=32;
    std::vector<uint16_t> memory(width*width+guard*2,0xdead),whole(width*width),strips(width*width);
    MapCamera camera{0,0,0,1/1.9794117647f,.872664626f,306.0f/466};
    MapProjection projection(width,width,camera);
    MapStroke horizontal,vertical;
    assert(horizontal.prepare(projection,{-500,713},{500,713},5));
    assert(vertical.prepare(projection,{0,0},{0,1200},5));
    MapRaster map(memory.data()+guard,width*width,width,width,width);map.clear(0);horizontal.paint(map,0xffff);
    int horizontalPixels=0;for(int y=130;y<150;y++)horizontalPixels+=memory[guard+y*width+233]!=0;
    assert(horizontalPixels>=1&&horizontalPixels<=2); // about 1.65 projected pixels, not fixed 5
    map.clear(0);vertical.paint(map,0xffff);
    int verticalPixels=0;for(int x=220;x<246;x++)verticalPixels+=memory[guard+140*width+x]!=0;
    assert(verticalPixels>=3&&verticalPixels<=4); // about 3.59 projected pixels
    for(int i=0;i<guard;i++)assert(memory[i]==0xdead&&memory[guard+width*width+i]==0xdead);
    std::vector<MapStroke> roads;
    for(int i=0;i<50;i++){MapStroke road;if(road.prepare(projection,{static_cast<int16_t>(i*17-400),-500},{static_cast<int16_t>(500-i*13),1500},2.8f+i%10))roads.push_back(road);}
    auto draw=[&](MapRaster &target){target.navigation_style(true);target.clear(0x1234);for(const auto &road:roads)road.paint(target,0xffff);};
    MapRaster full(whole.data(),whole.size(),width,width,width);draw(full);
    for(int y=0;y<width;y+=16){MapRaster strip(strips.data()+y*width,strips.size()-y*width,width,std::min(16,width-y),width,0,y);draw(strip);}
    assert(whole==strips);
    for(int angle=0;angle<360;angle+=15){
        camera.heading=angle*.01745329252f;MapProjection rotated(width,width,camera);
        for(MapPoint p: {MapPoint{0,0},MapPoint{120,650},MapPoint{-200,200}}){float x,y;assert(rotated.unproject(rotated.project(p),x,y));assert(std::abs(x-p.x)<.01f&&std::abs(y-p.y)<.01f);}
        MapStroke crossing;crossing.prepare(rotated,{-32000,-32000},{32000,32000},13);crossing.paint(map,0xffff);
    }
    for(int i=0;i<guard;i++)assert(memory[i]==0xdead&&memory[guard+width*width+i]==0xdead);
    std::cout<<"PASS: perspective road widths, inverse projection, near-plane guards and exact strip equivalence\n";
}
