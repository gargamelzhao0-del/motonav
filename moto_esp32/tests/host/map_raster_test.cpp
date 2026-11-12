#include "motonav/graphics/map_raster.h"
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
    // Splitting the same scene into independently rendered strips must not
    // introduce seams or rounding differences at global pixel boundaries.
    auto paint=[](MapRaster &target) {
        target.clear(0x2020);
        for (int i=0;i<30;++i) {
            ProjectedPoint a{-7.25f+i*17.3f,-10.75f}, b{465.5f-i*9.7f,480.25f};
            target.line(a,b,1+i%16,static_cast<uint16_t>(i*123));
            ProjectedPoint triangle[]{{50.5f+i,2.5f+i*12.7f},{300.25f,285.5f},{110.5f,460.25f}};
            target.polygon(triangle,3,static_cast<uint16_t>(i*71));
        }
    };
    std::vector<uint16_t> whole(466*466), strips(466*466);
    MapRaster full(whole.data(),whole.size(),466,466,466); paint(full);
    for (int y=0;y<466;y+=16) {
        MapRaster strip(strips.data()+y*466,strips.size()-y*466,466,std::min(16,466-y),466,0,y);
        assert(strip.valid()); paint(strip);
    }
    assert(whole==strips);
    std::vector<uint16_t> clipped(173*101);
    MapRaster tile(clipped.data(),clipped.size(),173,101,173,19,41); paint(tile);
    for (int y=0;y<101;++y) for(int x=0;x<173;++x) assert(clipped[y*173+x]==whole[(y+41)*466+x+19]);
    assert(!MapRaster(clipped.data(),clipped.size(),173,101,173,-1,41).valid());
    assert(!MapRaster(clipped.data(),clipped.size(),173,101,173,300,41).valid());
    std::cout << "PASS: RGB565 raster geometry, stride, guards and 2000 clipped shapes\n";
    std::cout << "PASS: full-screen and strip rendering are pixel-identical, including clipped tiles\n";
}
