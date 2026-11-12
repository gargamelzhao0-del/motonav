#pragma once
#include "motonav/graphics/map_projection.h"
#include <algorithm>
#include <cstring>

namespace motonav {
// Opaque, bounded RGB565 scanlines for the map layer. No temporary draw tasks,
// per-pixel blending, or allocation. UI text remains rendered by LVGL.
class MapRaster {
public:
    MapRaster(uint16_t *pixels, size_t count, int width, int height, size_t stride, int origin_x=0, int origin_y=0)
        : pixels_(pixels), width_(width), height_(height), origin_x_(origin_x), origin_y_(origin_y), stride_(stride),
          valid_(pixels && width > 0 && width <= 466 && height > 0 && height <= 466 &&
                 origin_x >= 0 && origin_x <= 466-width && origin_y >= 0 && origin_y <= 466-height &&
                 stride >= static_cast<size_t>(width) && stride <= 4096 &&
                 count >= (static_cast<size_t>(height) - 1) * stride + width) {}
    bool valid() const { return valid_; }
    static constexpr uint16_t rgb(uint32_t color) {
        return static_cast<uint16_t>(((color >> 8) & 0xf800) | ((color >> 5) & 0x07e0) | ((color >> 3) & 0x001f));
    }
    void clear(uint16_t color) {
        if (!valid_) return;
        span(origin_y_, origin_x_, origin_x_ + width_ - 1, color);
        // Reuse one cache-hot row with the platform's optimized memcpy.
        for (int y = 1; y < height_; ++y)
            std::memcpy(pixels_ + static_cast<size_t>(y) * stride_, pixels_, width_ * sizeof(uint16_t));
    }
    void polygon(const ProjectedPoint *points, size_t count, uint16_t color) {
        if (!valid_ || !points || count < 3 || count > 4) return;
        float min_y = 1024, max_y = -1024;
        for (size_t i=0; i<count; ++i) {
            if (!bounded(points[i])) return;
            min_y = std::min(min_y, points[i].y); max_y = std::max(max_y, points[i].y);
        }
        int first = std::max(origin_y_, ceil_int(min_y - 0.5f));
        int last = std::min(origin_y_ + height_ - 1, floor_int(max_y - 0.5f));
        struct Edge { ProjectedPoint a; float slope; int first, last; } edges[4]{};
        size_t edge_count = 0;
        for (size_t i=0; i<count; ++i) {
            auto a=points[i], b=points[(i+1)%count];
            if (a.y == b.y) continue;
            float slope = (b.x-a.x)/(b.y-a.y);
            if (!std::isfinite(slope)) return;
            edges[edge_count++] = {a, slope, ceil_int(std::min(a.y,b.y)-0.5f), ceil_int(std::max(a.y,b.y)-0.5f)-1};
        }
        // Active edges only change at vertices. Resolve those intervals once,
        // instead of four floating-point edge tests for every map scanline.
        for (int y=first; y<=last;) {
            const Edge *active[4]; size_t active_count=0;
            int end=last;
            for (size_t i=0;i<edge_count;++i) {
                const auto &edge=edges[i];
                if (y < edge.first) end=std::min(end,edge.first-1);
                else if (y <= edge.last) { active[active_count++]=&edge; end=std::min(end,edge.last); }
            }
            if (active_count < 2) { y=end+1; continue; }
            for (;y<=end;++y) {
                float scan=y+0.5f, left=1024, right=-1024;
                for(size_t i=0;i<active_count;++i) {
                    const auto &edge=*active[i];
                    float x=edge.a.x+(scan-edge.a.y)*edge.slope;
                    left=std::min(left,x); right=std::max(right,x);
                }
                if (left <= right) span(y, ceil_int(left-0.5f), floor_int(right-0.5f), color);
            }
        }
    }
    void line(ProjectedPoint a, ProjectedPoint b, int width, uint16_t color) {
        if (!valid_ || !bounded(a) || !bounded(b) || width < 1 || width > 16) return;
        float dx=b.x-a.x, dy=b.y-a.y, length=std::sqrt(dx*dx+dy*dy), radius=width*0.5f;
        if (length > 0.01f) {
            float nx=-dy*radius/length, ny=dx*radius/length;
            ProjectedPoint corners[]{{a.x+nx,a.y+ny},{b.x+nx,b.y+ny},{b.x-nx,b.y-ny},{a.x-nx,a.y-ny}};
            polygon(corners,4,color);
        }
        disk(a,radius,color); disk(b,radius,color);
    }
private:
    // All inputs have already been checked within the small screen bounds.
    // Avoid repeated libm ceil/floor calls for every opaque scanline.
    static int floor_int(float value) { int n=static_cast<int>(value); return n-(n>value); }
    static int ceil_int(float value) { int n=static_cast<int>(value); return n+(n<value); }
    static bool bounded(ProjectedPoint p) {
        return std::isfinite(p.x) && std::isfinite(p.y) && std::abs(p.x)<=1024 && std::abs(p.y)<=1024;
    }
    void span(int y, int first, int last, uint16_t color) {
        if (y < origin_y_ || y >= origin_y_ + height_) return;
        first=std::max(origin_x_,first); last=std::min(origin_x_+width_-1,last);
        if (first>last) return;
        auto *row=pixels_+static_cast<size_t>(y-origin_y_)*stride_;
        first-=origin_x_; last-=origin_x_;
        std::fill(row+first,row+last+1,color);
    }
    void disk(ProjectedPoint p, float radius, uint16_t color) {
        int first=std::max(origin_y_,ceil_int(p.y-radius-0.5f));
        int last=std::min(origin_y_+height_-1,floor_int(p.y+radius-0.5f));
        for (int y=first;y<=last;++y) {
            float dy=y+0.5f-p.y;
            float half=std::sqrt(std::max(0.0f,radius*radius-dy*dy));
            span(y,ceil_int(p.x-half-0.5f),floor_int(p.x+half-0.5f),color);
        }
    }
    uint16_t *pixels_;
    int width_, height_, origin_x_, origin_y_;
    size_t stride_;
    bool valid_;
};
} // namespace motonav
