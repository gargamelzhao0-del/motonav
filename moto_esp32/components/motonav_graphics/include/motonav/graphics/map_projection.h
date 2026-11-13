#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace motonav {
struct MapPoint { int16_t x, y; }; // Local coordinates, not latitude/longitude.
struct MapSegment { MapPoint a, b; };
struct MapCamera { float x, y, heading, scale; }; // Heading radians, scale pixels/local unit.
struct ProjectedPoint { float x, y; };

class MapProjection {
public:
    MapProjection(int width, int height, const MapCamera &camera)
        : width_(width), height_(height), camera_(camera),
          valid_(width > 0 && width <= 466 && height > 0 && height <= 466 &&
                 std::isfinite(camera.x) && std::abs(camera.x) <= 32768 &&
                 std::isfinite(camera.y) && std::abs(camera.y) <= 32768 &&
                 std::isfinite(camera.heading) && std::abs(camera.heading) <= 360 &&
                 std::isfinite(camera.scale) && camera.scale >= 0.1f && camera.scale <= 4.0f) {
        if (valid_) { sine_ = std::sin(camera.heading); cosine_ = std::cos(camera.heading); }
    }
    bool valid() const { return valid_; }
    ProjectedPoint project(MapPoint p) const {
        float x = p.x - camera_.x, y = p.y - camera_.y;
        return {width_ * 0.5f + (x * cosine_ - y * sine_) * camera_.scale,
                height_ * 0.7f - (x * sine_ + y * cosine_) * camera_.scale};
    }
    // Liang-Barsky clips BEFORE drawing. Far-off geometry never creates
    // oversized LVGL draw tasks; padding preserves thick strokes at an edge.
    bool segment(MapPoint start, MapPoint end, ProjectedPoint &a, ProjectedPoint &b) const {
        if (!valid_) return false;
        a = project(start); b = project(end);
        float dx = b.x - a.x, dy = b.y - a.y, lo = 0, hi = 1;
        const float p[]{-dx, dx, -dy, dy};
        const float q[]{a.x + 8, width_ + 7 - a.x, a.y + 8, height_ + 7 - a.y};
        for (unsigned i = 0; i < 4; ++i) {
            if (p[i] == 0) { if (q[i] < 0) return false; continue; }
            float t = q[i] / p[i];
            if (p[i] < 0) { if (t > hi) return false; if (t > lo) lo = t; }
            else { if (t < lo) return false; if (t < hi) hi = t; }
        }
        b = {a.x + hi * dx, a.y + hi * dy};
        a = {a.x + lo * dx, a.y + lo * dy};
        return true;
    }
private:
    int width_, height_;
    MapCamera camera_;
    bool valid_;
    float sine_ = 0, cosine_ = 1;
};
} // namespace motonav
