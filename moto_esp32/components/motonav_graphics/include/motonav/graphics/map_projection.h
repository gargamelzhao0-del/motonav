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
            pitch_ratio_=pitch_sine_/(700*pitch_cosine_);
        }
    }
    bool valid() const { return valid_; }
    ProjectedPoint project(MapPoint p) const {
        return project(p.x,p.y);
    }
    ProjectedPoint project(float px,float py) const {
        float x = px - camera_.x, y = py - camera_.y;
        float forward=x*sine_+y*cosine_;
        // Same ground-plane projection as sim/index.html (F=700). Clamp the
        // near plane so bounded off-screen geometry cannot cross the camera.
        float depth=std::max(80.0f/700,1+forward*camera_.scale*(pitch_sine_/700));
        const float scale=camera_.scale/depth;
        return {width_ * 0.5f + (x * cosine_ - y * sine_) * scale,
                height_ * camera_.anchor_y - forward * pitch_cosine_ * scale};
    }
    bool unproject(ProjectedPoint p,float &x,float &y) const {
        const float d=height_*camera_.anchor_y-p.y;
        const float denominator=700*pitch_cosine_-d*pitch_sine_;
        if(!valid_||!std::isfinite(p.x)||!std::isfinite(p.y)||denominator<=0.001f)return false;
        const float forward=d*700/denominator/camera_.scale;
        const float lateral=(p.x-width_*0.5f)*(1+forward*camera_.scale*pitch_sine_/700)/camera_.scale;
        x=camera_.x+lateral*cosine_+forward*sine_;
        y=camera_.y-lateral*sine_+forward*cosine_;
        return std::isfinite(x)&&std::isfinite(y);
    }
    float scale() const { return camera_.scale; }
    // Offset in the unpitched ground image. This is an exact homography of
    // the road surface and avoids unprojecting/reprojecting four endpoints.
    ProjectedPoint offset(ProjectedPoint center,float side,float forward) const {
        float sc=ground_scale(center);
        float factor=1/(1+forward*pitch_sine_*sc/700);
        return {width_*.5f+((center.x-width_*.5f)+side*sc)*factor,
                height_*camera_.anchor_y-((height_*camera_.anchor_y-center.y)+forward*pitch_cosine_*sc)*factor};
    }
    ProjectedPoint plane_normal(MapPoint a,MapPoint b,float radius)const {
        float dx=b.x-a.x,dy=b.y-a.y,length=std::sqrt(dx*dx+dy*dy);
        if(length<.001f)return {};
        float factor=radius/length;
        return {-(dx*sine_+dy*cosine_)*factor,(dx*cosine_-dy*sine_)*factor};
    }
    void disk_axes(ProjectedPoint center,float radius,ProjectedPoint &side,ProjectedPoint &forward)const {
        float sc=ground_scale(center);
        side={radius*sc,0};
        forward={-(center.x-width_*.5f)*radius*pitch_sine_*sc/700,-radius*pitch_cosine_*sc*sc};
    }
    // Liang-Barsky clips BEFORE drawing. Far-off geometry never creates
    // oversized LVGL draw tasks; padding preserves thick strokes at an edge.
    bool segment(MapPoint start, MapPoint end, ProjectedPoint &a, ProjectedPoint &b,float padding=8) const {
        if (!valid_||!std::isfinite(padding)||padding<0||padding>32) return false;
        float ax=start.x,ay=start.y,bx=end.x,by=end.y;
        auto depth=[this](float x,float y){return 1+((x-camera_.x)*sine_+(y-camera_.y)*cosine_)*camera_.scale*pitch_sine_/700;};
        float da=depth(ax,ay),db=depth(bx,by);constexpr float near=80.0f/700;
        if(da<near&&db<near)return false;
        if(da<near){float t=(near-da)/(db-da);ax+=(bx-ax)*t;ay+=(by-ay)*t;}
        else if(db<near){float t=(near-db)/(da-db);bx+=(ax-bx)*t;by+=(ay-by)*t;}
        a = project(ax,ay); b = project(bx,by);
        // Most local streets lie wholly inside or outside the view. Avoid the
        // software divisions in Liang-Barsky unless a segment crosses an edge.
        auto region=[this,padding](ProjectedPoint p) {
            return (p.x < -padding ? 1u : p.x > width_+padding-1 ? 2u : 0u) |
                   (p.y < -padding ? 4u : p.y > height_+padding-1 ? 8u : 0u);
        };
        unsigned ac=region(a),bc=region(b);
        if(ac&bc)return false;
        if(!(ac|bc))return true;
        float dx = b.x - a.x, dy = b.y - a.y, lo = 0, hi = 1;
        const float p[]{-dx, dx, -dy, dy};
        const float q[]{a.x + padding, width_ + padding-1 - a.x, a.y + padding, height_ + padding-1 - a.y};
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
    float ground_scale(ProjectedPoint p)const{return 1-(height_*camera_.anchor_y-p.y)*pitch_ratio_;}
    int width_, height_;
    MapCamera camera_;
    bool valid_;
    float sine_ = 0, cosine_ = 1, pitch_sine_=0, pitch_cosine_=1;
    float pitch_ratio_=0;
};
} // namespace motonav
