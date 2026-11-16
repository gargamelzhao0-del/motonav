#pragma once
#include "motonav/graphics/map_projection.h"
#include "motonav/graphics/pixel_fill.h"
#include <algorithm>
#include <array>
#include <cstring>

namespace motonav {
// Opaque, bounded RGB565 scanlines for the map layer. No temporary draw tasks,
// per-pixel blending, or allocation. UI text remains rendered by LVGL.
class MapRaster {
public:
    // A projected line shared by both stroke widths and every display strip.
    // Only four extra bytes per segment; no per-frame allocation or normal
    // quantization. Keep the original arithmetic order for identical pixels.
    class PreparedLine {
        friend class MapRaster;
    public:
        PreparedLine() = default;
        PreparedLine(ProjectedPoint a, ProjectedPoint b) : a_(a), b_(b) {
            if (!bounded(a) || !bounded(b)) return;
            const float dx = b.x - a.x, dy = b.y - a.y;
            const float length=std::sqrt(dx*dx + dy*dy);
            inverse_length_=length>0.01f?1.0f/length:0;
        }
        bool intersects_y(int top, int bottom, int padding) const {
            return inverse_length_ >= 0 && std::max(a_.y, b_.y) + padding >= top &&
                   std::min(a_.y, b_.y) - padding <= bottom;
        }
    private:
        ProjectedPoint a_{}, b_{};
        float inverse_length_ = -1;
    };
    MapRaster(uint16_t *pixels, size_t count, int width, int height, size_t stride, int origin_x=0, int origin_y=0)
        : pixels_(pixels), width_(width), height_(height), origin_x_(origin_x), origin_y_(origin_y), stride_(stride),
          valid_(pixels && width > 0 && width <= 466 && height > 0 && height <= 466 &&
                 origin_x >= 0 && origin_x <= 466-width && origin_y >= 0 && origin_y <= 466-height &&
                 stride >= static_cast<size_t>(width) && stride <= 4096 &&
                 count >= (static_cast<size_t>(height) - 1) * stride + width) {}
    bool valid() const { return valid_; }
    // Shade constant-color scanline spans instead of every pixel. No frame canvas.
    void navigation_style(bool enabled) { navigation_style_=enabled; }
    static constexpr uint16_t rgb(uint32_t color) {
        return static_cast<uint16_t>(((color >> 8) & 0xf800) | ((color >> 5) & 0x07e0) | ((color >> 3) & 0x001f));
    }
    void clear(uint16_t color) {
        if (!valid_) return;
        if(navigation_style_) {
            for(int y=origin_y_;y<origin_y_+height_;++y)span(y,origin_x_,origin_x_+width_-1,color);
            return;
        }
        if (stride_ == static_cast<size_t>(width_)) {
            fill_rgb565(pixels_, static_cast<size_t>(width_) * height_, color);
        } else {
            for (int y = 0; y < height_; ++y)
                fill_rgb565(pixels_ + static_cast<size_t>(y) * stride_, width_, color);
        }
    }
    void polygon(const ProjectedPoint *points, size_t count, uint16_t color) {
        if (!valid_ || !points || count < 3 || count > 4) return;
        float min_y = 8192, max_y = -8192;
        for (size_t i=0; i<count; ++i) {
            if (!bounded(points[i])) return;
            min_y = std::min(min_y, points[i].y); max_y = std::max(max_y, points[i].y);
        }
        int first = std::max(origin_y_, ceil_int(min_y - 0.5f));
        int last = std::min(origin_y_ + height_ - 1, floor_int(max_y - 0.5f));
        if(first>last)return;
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
                float scan=y+0.5f, left=8192, right=-8192;
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
        if (!valid_ || width < 1 || width > 16) return;
        line(PreparedLine(a, b), width, color);
    }
    void line(const PreparedLine &segment, int width, uint16_t color) {
        if (!valid_ || segment.inverse_length_ < 0 || width < 1 || width > 16) return;
        const auto a = segment.a_, b = segment.b_;
        float dx=b.x-a.x, dy=b.y-a.y, radius=width*0.5f;
        if (segment.inverse_length_ > 0) {
            const float factor=radius*segment.inverse_length_;
            float nx=-dy*factor, ny=dx*factor;
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
        return std::isfinite(p.x) && std::isfinite(p.y) && std::abs(p.x)<=8192 && std::abs(p.y)<=8192;
    }
    void span(int y, int first, int last, uint16_t color) {
        if (y < origin_y_ || y >= origin_y_ + height_) return;
        first=std::max(origin_x_,first); last=std::min(origin_x_+width_-1,last);
        if (first>last) return;
        auto *row=pixels_+static_cast<size_t>(y-origin_y_)*stride_;
        if(navigation_style_) {
            unsigned light=256;
            if(y<160)light=96+static_cast<unsigned>(y);
            if(y<70)light=light*(32+static_cast<unsigned>(y)*224/70)/256;
            if(y>=330)light=y<391?256-static_cast<unsigned>(y-330)*159/61:97-static_cast<unsigned>(y-391)*66/75;
            const auto &edge=ring_edges()[y];
            if(first>=edge[0]&&last<466-edge[0]) {
                shaded_span(row,first,last,y,color,light);return;
            }
            const int cuts[]{0,edge[2],edge[1],edge[0],466-edge[0],466-edge[1],466-edge[2],466};
            constexpr unsigned factors[]{58,90,134,256,134,90,58};
            for(unsigned i=0;i<7;i++) {
                int a=std::max(first,cuts[i]),b=std::min(last,cuts[i+1]-1);
                if(a<=b)shaded_span(row,a,b,y,color,light*factors[i]/256);
            }
            return;
        }
        first-=origin_x_; last-=origin_x_;
        fill_rgb565(row+first, static_cast<size_t>(last-first+1), color);
    }
    void shaded_span(uint16_t *row,int first,int last,int y,uint16_t color,unsigned light) {
        if(light==256){fill_rgb565(row+first-origin_x_,last-first+1,color);return;}
        unsigned a=(y&1)?224:32,b=(y&1)?96:160;
        uint16_t even=dim(color,light,a),odd=dim(color,light,b);
        if(first&1)std::swap(even,odd);
        fill_rgb565_pair(row+first-origin_x_,last-first+1,even,odd);
    }
    static uint16_t dim(uint16_t swapped,unsigned light,unsigned threshold) {
        unsigned color=(swapped>>8)|(swapped<<8),r=(((color>>11)&31)*light+threshold)/256,
                 g=(((color>>5)&63)*light+threshold)/256,b=((color&31)*light+threshold)/256;
        uint16_t result=static_cast<uint16_t>((r<<11)|(g<<5)|b);
        return static_cast<uint16_t>((result>>8)|(result<<8));
    }
    static const std::array<std::array<int16_t,3>,466> &ring_edges() {
        // Compile-time integer geometry avoids a 2.8 KiB temporary on the
        // startup task's stack, and keeps this immutable table in flash.
        static constexpr auto rows=[] {
            std::array<std::array<int16_t,3>,466> result{};
            constexpr int radii[]{212,218,226};
            for(int y=0;y<466;y++)for(int i=0;i<3;i++) {
                const int dy=2*y-465,n=std::max(0,4*radii[i]*radii[i]-dy*dy);
                int low=0,high=452;
                while(low<high){const int mid=(low+high+1)/2;if(mid*mid<=n)low=mid;else high=mid-1;}
                result[y][i]=static_cast<int16_t>((466-low)/2);
            }
            return result;
        }();
        return rows;
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
    bool navigation_style_=false;
};
} // namespace motonav
