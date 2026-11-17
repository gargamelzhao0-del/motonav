#pragma once
#include <array>
#include <cstdint>

namespace motonav {
// Semantic map colors shared by geometry and the scanline shade cache.
// These alter presentation only; all features still come from received data.
struct NavigationMapStyle {
    static constexpr uint32_t Ground=0x262e34,Water=0x244b65,Park=0x304a42,Building=0x3b4852;
    static constexpr uint32_t RoadEdge=0x33444f,MajorRoad=0xb7c1c8,SecondaryRoad=0x8d9ea8,MinorRoad=0x687d88;
    static constexpr uint32_t Route=0xb79aff,RouteEdge=0x51416c,Passed=0x61727c,PassedEdge=0x303d46;
    static constexpr uint32_t PlayerEdge=0x101a22,Player=0xf2f3e9,PlayerSide=0xc8d5df,Compass=0x6e8390;
    static constexpr std::array<uint32_t,18> Palette{Ground,Water,Park,Building,RoadEdge,MajorRoad,
        SecondaryRoad,MinorRoad,0x10191e,0x66757c,Route,RouteEdge,PlayerEdge,Player,PlayerSide,Compass,Passed,PassedEdge};
};
}
