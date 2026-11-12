#include "../main/map_raster.h"
#include <cassert>
#include <iostream>
#include <limits>
#include <random>
#include <vector>

using namespace motonav;
int main() {
    constexpr int w=320,h=300,stride=324,guard=32;
    constexpr uint16_t sentinel=0x1234;
    std::vector<uint16_t> storage(guard+stride*h+guard,sentinel);
    MapRaster raster(storage.data()+guard,stride*h,w,h,stride);
    assert(raster.valid());
    raster.clear(0);
    assert(storage[guard]==0 && storage[guard+(h-1)*stride+w-1]==0);
    assert(MapRaster::rgb(0xff0000)==0xf800 && MapRaster::rgb(0x00ff00)==0x07e0 && MapRaster::rgb(0x0000ff)==0x001f);
    ProjectedPoint square[]{{10,10},{30,10},{30,30},{10,30}};
    raster.polygon(square,4,0xffff);
    assert(storage[guard+20*stride+20]==0xffff);
    assert(storage[guard+30*stride+30]==0);
    std::mt19937 random(932);
    for (unsigned i=0;i<2000;++i) {
        auto point=[&] { return ProjectedPoint{static_cast<float>(static_cast<int>(random()%1400)-700),
                                              static_cast<float>(static_cast<int>(random()%1400)-700)}; };
        auto a=point(),b=point(),c=point();
        raster.line(a,b,1+static_cast<int>(i%16),static_cast<uint16_t>(i));
        ProjectedPoint triangle[]{a,b,c}; raster.polygon(triangle,3,static_cast<uint16_t>(i));
    }
    auto before=storage;
    raster.line({std::numeric_limits<float>::quiet_NaN(),0},{0,0},5,0);
    raster.line({0,0},{0,0},-1,0);
    MapRaster invalid(storage.data()+guard,3,w,h,stride);
    assert(!invalid.valid()); invalid.clear(0);
    assert(storage==before);
    for(int i=0;i<guard;++i) assert(storage[i]==sentinel && storage[guard+stride*h+i]==sentinel);
    for(int y=0;y<h;++y) for(int x=w;x<stride;++x) assert(storage[guard+y*stride+x]==sentinel);
    std::cout << "PASS: RGB565 raster geometry, stride, guards and 2000 clipped shapes\n";
}
