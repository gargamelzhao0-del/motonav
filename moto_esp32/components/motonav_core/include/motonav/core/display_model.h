#pragma once
#include "motonav/core/navigation_protocol.h"
#include "motonav/core/route_scene.h"

namespace motonav {

struct ScreenSnapshot {
    RouteScene route{};
    Guidance guidance{};
    ReceiverStats packets{};
    bool linked = false, encrypted = false, subscribed = false;
    bool handshaken = false, stream_fresh = false, has_guidance = false, source_expired = false;
    bool pairing = false;
    uint32_t passkey = 0;
};

enum class ScreenMode { Disconnected, Pairing, Securing, Handshake, Ready, Waiting, Stale, Live, Arrived, Stopped };

inline ScreenMode screen_mode(const ScreenSnapshot &s) {
    if (s.pairing) return ScreenMode::Pairing;
    if (s.guidance.mode == Arrived) return ScreenMode::Arrived;
    if (s.guidance.mode == Stopped) return ScreenMode::Stopped;
    if (!s.linked) return ScreenMode::Disconnected;
    if (!s.encrypted) return ScreenMode::Securing;
    if (!s.subscribed || !s.handshaken) return ScreenMode::Handshake;
    if (!s.has_guidance) return ScreenMode::Ready;
    if (!s.stream_fresh || s.source_expired) return ScreenMode::Stale;
    return s.guidance.mode == Live ? ScreenMode::Live : ScreenMode::Waiting;
}

inline const char *maneuver(int icon) {
    switch (icon) {
        case 2: return "TURN LEFT"; case 3: return "TURN RIGHT";
        case 4: return "BEAR LEFT"; case 5: return "BEAR RIGHT";
        case 6: return "SHARP LEFT"; case 7: return "SHARP RIGHT";
        case 8: case 19: return "U-TURN"; case 9: return "STRAIGHT";
        case 10: return "WAYPOINT"; case 11: case 17: return "ENTER ROUNDABOUT";
        case 12: case 18: return "EXIT ROUNDABOUT"; case 13: return "SERVICE AREA";
        case 14: return "TOLL GATE"; case 15: return "DESTINATION";
        case 16: return "TUNNEL"; case 20: return "FOLLOW ROAD";
        case 21: case 25: return "ROUNDABOUT LEFT";
        case 22: case 26: return "ROUNDABOUT RIGHT";
        case 23: case 27: return "ROUNDABOUT AHEAD";
        case 24: case 28: return "ROUNDABOUT U-TURN";
        case 65: return "KEEP LEFT"; case 66: return "KEEP RIGHT";
        default: return "CHECK PHONE MAP";
    }
}

struct Point { int x, y; };
struct ArrowShape { Point points[6]{}; unsigned count = 0; };

inline ArrowShape arrow_shape(int icon) {
    ArrowShape result;
    if (icon == 9) return {{{80,125},{80,20},{45,55},{80,20},{115,55}},5};
    bool right = icon == 3 || icon == 5 || icon == 7 || icon == 66;
    if (icon == 2 || icon == 3) result = {{{80,125},{80,55},{20,55},{50,25},{20,55},{50,85}},6};
    else if (icon == 4 || icon == 5 || icon == 65 || icon == 66)
        result = {{{80,125},{80,90},{30,40},{30,78},{30,40},{68,40}},6};
    else if (icon == 6 || icon == 7)
        result = {{{80,125},{80,35},{25,90},{25,52},{25,90},{63,90}},6};
    if (right) for (unsigned i = 0; i < result.count; ++i) result.points[i].x = 160 - result.points[i].x;
    return result;
}

template<size_t N> inline void format_distance(char (&text)[N], int meters) {
    if (meters < 0) std::snprintf(text, N, "--");
    else if (meters < 1000) std::snprintf(text, N, "%d m", meters);
    else if (meters < 10000) std::snprintf(text, N, "%d.%d km", meters / 1000, (meters % 1000) / 100);
    else std::snprintf(text, N, "%d km", meters / 1000);
}

} // namespace motonav
