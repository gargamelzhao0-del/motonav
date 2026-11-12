#include "../main/map_projection.h"
#include <cassert>
#include <iostream>
#include <limits>
#include <random>

using namespace motonav;
int main() {
    MapProjection map(320, 300, {0,0,0,1});
    assert(map.valid());
    auto center = map.project({0,0});
    assert(center.x == 160 && center.y == 210);
    assert(map.project({0,100}).y < center.y);
    ProjectedPoint a, b;
    assert(map.segment({-32768,0},{32767,0},a,b));
    assert(std::abs(a.x + 8) < 0.01f && std::abs(b.x - 327) < 0.01f);
    assert(!map.segment({0,32767},{100,32767},a,b));
    assert(map.segment({0,0},{0,0},a,b));
    assert(!MapProjection(0,300,{0,0,0,1}).valid());
    assert(!MapProjection(320,467,{0,0,0,1}).valid());
    for (float invalid : {std::numeric_limits<float>::infinity(),std::numeric_limits<float>::quiet_NaN()}) {
        MapProjection bad(320,300,{invalid,0,0,1});
        assert(!bad.valid() && !bad.segment({0,0},{1,1},a,b));
        assert(!MapProjection(320,300,{0,0,invalid,1}).valid());
        assert(!MapProjection(320,300,{0,0,0,invalid}).valid());
    }
    std::mt19937 random(466);
    for (unsigned i=0;i<10000;++i) {
        MapProjection rotated(320,300,{static_cast<float>(i%400-200.0),0,
            static_cast<float>(i%628)/100,0.1f+static_cast<float>(i%390)/100});
        MapPoint p{static_cast<int16_t>(random()),static_cast<int16_t>(random())};
        MapPoint q{static_cast<int16_t>(random()),static_cast<int16_t>(random())};
        if (rotated.segment(p,q,a,b)) {
            for (auto v : {a,b}) {
                assert(std::isfinite(v.x) && std::isfinite(v.y));
                assert(v.x >= -8.1f && v.x <= 327.1f && v.y >= -8.1f && v.y <= 307.1f);
            }
        }
    }
    std::cout << "PASS: projection bounds, invalid cameras and 10000 clipped segments\n";
}
