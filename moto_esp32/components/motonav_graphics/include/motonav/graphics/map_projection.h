#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <algorithm>

namespace motonav {
struct MapPoint { int16_t x, y; }; // Local coordinates, not latitude/longitude.
struct MapSegment { MapPoint a, b; };
struct MapCamera {
    float x, y, heading, scale;
    float perspective=0; // Ground-plane camera pitch in radians, 0..61 degrees.
    float anchor_y=0.7f; // Vehicle anchor as a fraction of display height.
};
struct ProjectedPoint { float x, y; };

class MapProjection {
public:
    MapProjection(int width, int height, const MapCamera &camera)
        : width_(width), height_(height), camera_(camera),
          valid_(width > 0 && width <= 466 && height > 0 && height <= 466 &&
                 std::isfinite(camera.x) && std::abs(camera.x) <= 32768 &&
                 std::isfinite(camera.y) && std::abs(camera.y) <= 32768 &&
                 std::isfinite(camera.heading) && std::abs(camera.heading) <= 360 &&
                 std::isfinite(camera.scale) && camera.scale >= 0.1f && camera.scale <= 4.0f &&
                 std::isfinite(camera.perspective) && camera.perspective>=0 && camera.perspective<=1.065f &&
                 std::isfinite(camera.anchor_y) && camera.anchor_y>=0.4f && camera.anchor_y<=0.8f) {
        if (valid_) {
            sine_=std::sin(camera.heading);cosine_=std::cos(camera.heading);
            pitch_sine_=std::sin(camera.perspective);pitch_cosine_=std::cos(camera.perspective);
        }
    }
    bool valid() const { return valid_; }
    ProjectedPoint project(MapPoint p) const {
        float x = p.x - camera_.x, y = p.y - camera_.y;
        float forward=x*sine_+y*cosine_;
        // Same ground-plane projection as sim/index.html (F=700). Clamp the
        // near plane so bounded off-screen geometry cannot cross the camera.
        float depth=std::max(80.0f/700,1+forward*camera_.scale*(pitch_sine_/700));
        const float scale=camera_.scale/depth;
        return {width_ * 0.5f + (x * cosine_ - y * sine_) * scale,
                height_ * camera_.anchor_y - forward * pitch_cosine_ * scale};
    }
    // Liang-Barsky clips BEFORE drawing. Far-off geometry never creates
    // oversized LVGL draw tasks; padding preserves thick strokes at an edge.
    bool segment(MapPoint start, MapPoint end, ProjectedPoint &a, ProjectedPoint &b) const {
        if (!valid_) return false;
        a = project(start); b = project(end);
        // Most local streets lie wholly inside or outside the view. Avoid the
        // software divisions in Liang-Barsky unless a segment crosses an edge.
        auto region=[this](ProjectedPoint p) {
            return (p.x < -8 ? 1u : p.x > width_+7 ? 2u : 0u) |
                   (p.y < -8 ? 4u : p.y > height_+7 ? 8u : 0u);
        };
        unsigned ac=region(a),bc=region(b);
        if(ac&bc)return false;
        if(!(ac|bc))return true;
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
    float sine_ = 0, cosine_ = 1, pitch_sine_=0, pitch_cosine_=1;
};
} // namespace motonav
