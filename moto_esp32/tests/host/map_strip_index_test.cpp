#include "motonav/graphics/map_strip_index.h"
#include <cassert>
#include <iostream>
using namespace motonav;
int main(){
    MapStripIndex bins;
    int tops[384],bottoms[384];
    for(size_t i=0;i<384;i++){
        tops[i]=int(i*37%600)-70;bottoms[i]=tops[i]+int(i%150);
        bins.add(i,tops[i],bottoms[i]);
    }
    // Arbitrary LVGL strip origins/heights, including rows crossing a bin edge.
    for(int top=0;top<466;top++)for(int rows:{1,16,32,64}){
        int bottom=std::min(465,top+rows-1);
        for(size_t i=0;i<384;i++)if(bottoms[i]>=top&&tops[i]<=bottom)
            assert(bins.word(i/32,top,bottom)&(uint32_t(1)<<(i%32)));
    }
    assert(!bins.word(12,0,465));assert(!bins.word(0,466,470));
    bins.reset();for(size_t w=0;w<12;w++)assert(!bins.word(w,0,465));
    bins.add(384,0,465);bins.add(0,-30,-1);bins.add(1,470,480);bins.add(2,30,20);
    for(size_t w=0;w<12;w++)assert(!bins.word(w,0,465));
    bins.add(383,465,465);assert(bins.word(11,465,465)==0x80000000u);
    std::cout<<"PASS: bounded strip masks retain every intersecting feature\n";
}
