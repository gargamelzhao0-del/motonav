#pragma once
#include "motonav/graphics/map_projection.h"
#include "motonav/graphics/pixel_fill.h"
#include "motonav/graphics/map_style.h"
#include <algorithm>
#include <array>
#include <cstring>

namespace motonav {
// Opaque, bounded RGB565 scanlines for the map layer. No temporary draw tasks,
// allocation, or full-frame AA. Optional edge coverage blends two pixels per
// polygon scanline. UI text remains rendered by LVGL.
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
    // Edges are prepared once per camera update, reused across DMA strips.
    class PreparedPolygon {
        friend class MapRaster;
    public:
        PreparedPolygon()=default;
        PreparedPolygon(const ProjectedPoint *points,size_t count) {
            if(!points||count<3||count>4)return;
            for(size_t i=0;i<count;i++)if(!bounded(points[i]))return;
            for(size_t i=0;i<count;i++) {
                auto a=points[i],b=points[(i+1)%count];if(a.y==b.y)continue;
                const float slope=(b.x-a.x)/(b.y-a.y);
                if(!std::isfinite(slope)){count_=0;return;}
                int first=ceil_int(std::min(a.y,b.y)-.5f),last=ceil_int(std::max(a.y,b.y)-.5f)-1;
                edges_[count_++]={a.x+(.5f-a.y)*slope,slope,static_cast<int16_t>(first),static_cast<int16_t>(last)};
                top_=std::min<int>(top_,first);bottom_=std::max<int>(bottom_,last);
            }
        }
        bool intersects_y(int top,int bottom)const{return count_>=2&&bottom_>=top&&top_<=bottom;}
    private:
        struct Edge { float intercept,slope;int16_t first,last; } edges_[4]{};
        int16_t top_=8192,bottom_=-8192;
        uint8_t count_=0;
    };
    class PreparedEllipse {
        friend class MapRaster;
    public:
        PreparedEllipse()=default;
        PreparedEllipse(ProjectedPoint center,ProjectedPoint xAxis,ProjectedPoint yAxis):center_(center) {
            if(!bounded(center)||!bounded(xAxis)||!bounded(yAxis))return;
            float yy=xAxis.y*xAxis.y+yAxis.y*yAxis.y,det=xAxis.x*yAxis.y-xAxis.y*yAxis.x;
            if(yy<.000001f||std::abs(det)<.000001f)return;
            inverse_y_=1/yy;shear_=(xAxis.x*xAxis.y+yAxis.x*yAxis.y)/yy;half_squared_=det*det/yy;
            float radius=std::sqrt(yy);
            first_=static_cast<int16_t>(ceil_int(center.y-radius-.5f));last_=static_cast<int16_t>(floor_int(center.y+radius-.5f));
        }
        bool intersects_y(int top,int bottom)const{return inverse_y_>0&&last_>=top&&first_<=bottom;}
    private:
        ProjectedPoint center_{};
        float inverse_y_=0,shear_=0,half_squared_=0;
        int16_t first_=0,last_=-1;
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
        const auto paint=prepare_color(color);
        if(navigation_style_) {
            for(int y=origin_y_;y<origin_y_+height_;++y)span(y,origin_x_,origin_x_+width_-1,paint);
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
        const auto paint=prepare_color(color);
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
                if (left <= right) span(y, ceil_int(left-0.5f), floor_int(right-0.5f), paint);
            }
        }
    }
    void polygon(const PreparedPolygon &polygon,uint16_t color,bool antialias=false) {
        if(!valid_||!polygon.intersects_y(origin_y_,origin_y_+height_-1))return;
        const auto paint=prepare_color(color);
        int first=std::max<int>(origin_y_,polygon.top_),last=std::min<int>(origin_y_+height_-1,polygon.bottom_);
        for(int y=first;y<=last;) {
            const PreparedPolygon::Edge *active[4];unsigned count=0;int end=last;
            for(unsigned i=0;i<polygon.count_;i++) {
                const auto &edge=polygon.edges_[i];
                if(y<edge.first)end=std::min<int>(end,edge.first-1);
                else if(y<=edge.last){active[count++]=&edge;end=std::min<int>(end,edge.last);}
            }
            if(count<2){y=end+1;continue;}
            for(;y<=end;y++) {
                float left=8192,right=-8192;
                for(unsigned i=0;i<count;i++){float x=active[i]->intercept+y*active[i]->slope;left=std::min(left,x);right=std::max(right,x);}
                if(antialias)covered_span(y,left,right,paint);
                else span(y,ceil_int(left-.5f),floor_int(right-.5f),paint);
            }
        }
    }
    void ellipse(const PreparedEllipse &ellipse,uint16_t color,bool antialias=false) {
        if(!valid_||!ellipse.intersects_y(origin_y_,origin_y_+height_-1))return;
        const auto paint=prepare_color(color);
        int first=std::max<int>(origin_y_,ellipse.first_),last=std::min<int>(origin_y_+height_-1,ellipse.last_);
        for(int y=first;y<=last;y++) {
            float dy=y+.5f-ellipse.center_.y;
            float half=std::sqrt(std::max(0.0f,ellipse.half_squared_*(1-dy*dy*ellipse.inverse_y_)));
            float center=ellipse.center_.x+ellipse.shear_*dy;
            if(antialias)covered_span(y,center-half,center+half,paint);
            else span(y,ceil_int(center-half-.5f),floor_int(center+half-.5f),paint);
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
    struct SpanColor { uint16_t value; const uint32_t *shades; };
    SpanColor prepare_color(uint16_t color) const {
        if(!navigation_style_)return {color,nullptr};
        if(cached_shades_&&cached_color_==color)return {color,cached_shades_};
        constexpr auto palette=NavigationMapStyle::Palette;
        static constexpr auto table=[] {
            std::array<std::array<uint32_t,514>,18> result{};
            constexpr auto rgb=NavigationMapStyle::Palette;
            for(unsigned c=0;c<18;c++)for(unsigned light=0;light<=256;light++)for(unsigned row=0;row<2;row++){
                const unsigned packed=((rgb[c]>>8)&0xf800)|((rgb[c]>>5)&0x7e0)|((rgb[c]>>3)&31);
                uint32_t pair=0;
                for(unsigned column=0;column<2;column++){
                    const unsigned threshold=row?(column?96:224):(column?160:32);
                    const unsigned r=(((packed>>11)&31)*light+threshold)/256,g=(((packed>>5)&63)*light+threshold)/256,b=((packed&31)*light+threshold)/256;
                    const uint16_t value=(r<<11)|(g<<5)|b;
                    const uint16_t swapped=static_cast<uint16_t>((uint32_t(value)<<8)|(value>>8));
                    pair|=uint32_t(swapped)<<(16*column);
                }
                result[c][light*2+row]=pair;
            }
            return result;
        }();
        for(unsigned i=0;i<18;i++){
            const auto rgb565=rgb(palette[i]);
            if(color==static_cast<uint16_t>((rgb565>>8)|(rgb565<<8))){cached_color_=color;cached_shades_=table[i].data();return {color,cached_shades_};}
        }
        return {color,nullptr};
    }
    void span(int y, int first, int last, SpanColor color) {
        if (y < origin_y_ || y >= origin_y_ + height_) return;
        first=std::max(origin_x_,first); last=std::min(origin_x_+width_-1,last);
        if (first>last) return;
        auto *row=pixels_+static_cast<size_t>(y-origin_y_)*stride_;
        if(navigation_style_) {
            const unsigned light=row_light(y);
            const auto &edge=ring_edges()[y];
            if(first>=edge[0]&&last<466-edge[0]) {
                shaded_span(row,first,last,y,color,light);return;
            }
            const int cuts[]{0,edge[2],edge[1],edge[0],466-edge[0],466-edge[1],466-edge[2],466};
            constexpr unsigned factors[]{180,204,230,256,230,204,180};
            for(unsigned i=0;i<7;i++) {
                int a=std::max(first,cuts[i]),b=std::min(last,cuts[i+1]-1);
                if(a<=b)shaded_span(row,a,b,y,color,light*factors[i]/256);
            }
            return;
        }
        first-=origin_x_; last-=origin_x_;
        fill_rgb565(row+first, static_cast<size_t>(last-first+1), color.value);
    }
    static unsigned row_light(int y) {
        if(y<140)return 166+static_cast<unsigned>(y)*90/140;
        if(y<340)return 256;
        if(y<415)return 256-static_cast<unsigned>(y-340)*150/75;
        return 106-static_cast<unsigned>(y-415)*42/51;
    }
    // Coverage is evaluated only for the two edge pixels of a scanline. The
    // interior keeps the opaque span fast path, with no full-frame AA buffer.
    void covered_span(int y,float left,float right,SpanColor color) {
        if(right<=left)return;
        int first=floor_int(left),last=floor_int(right);
        if(first==last){blend_edge(y,first,color,right-left);return;}
        span(y,first+1,last-1,color);
        blend_edge(y,first,color,1-(left-first));
        blend_edge(y,last,color,right-last);
    }
    void blend_edge(int y,int x,SpanColor color,float coverage) {
        if(y<origin_y_||y>=origin_y_+height_||x<origin_x_||x>=origin_x_+width_)return;
        unsigned alpha=static_cast<unsigned>(std::clamp(coverage*256+.5f,0.0f,256.0f));
        if(!alpha)return;
        uint16_t source=color.value;
        if(navigation_style_) {
            unsigned light=row_light(y),factor=256;
            const auto &edge=ring_edges()[y];
            if(x<edge[2]||x>=466-edge[2])factor=180;
            else if(x<edge[1]||x>=466-edge[1])factor=204;
            else if(x<edge[0]||x>=466-edge[0])factor=230;
            light=light*factor/256;
            if(color.shades)source=static_cast<uint16_t>(color.shades[light*2+(y&1)]>>(16*(x&1)));
            else source=dim(source,light,(y&1)?((x&1)?96:224):((x&1)?160:32));
        }
        auto &pixel=pixels_[static_cast<size_t>(y-origin_y_)*stride_+x-origin_x_];
        unsigned s=(source>>8)|(source<<8),d=(pixel>>8)|(pixel<<8),inverse=256-alpha;
        unsigned r=(((s>>11)&31)*alpha+((d>>11)&31)*inverse+128)>>8;
        unsigned g=(((s>>5)&63)*alpha+((d>>5)&63)*inverse+128)>>8;
        unsigned b=((s&31)*alpha+(d&31)*inverse+128)>>8;
        uint16_t packed=static_cast<uint16_t>((r<<11)|(g<<5)|b);
        pixel=static_cast<uint16_t>((packed>>8)|(packed<<8));
    }
    void shaded_span(uint16_t *row,int first,int last,int y,SpanColor color,unsigned light) {
        if(light==256){fill_rgb565(row+first-origin_x_,last-first+1,color.value);return;}
        unsigned a=(y&1)?224:32,b=(y&1)?96:160;
        const uint32_t cached=color.shades?color.shades[light*2+(y&1)]:0;
        uint16_t even=color.shades?static_cast<uint16_t>(cached):dim(color.value,light,a),odd=color.shades?static_cast<uint16_t>(cached>>16):dim(color.value,light,b);
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
        const auto paint=prepare_color(color);
        int first=std::max(origin_y_,ceil_int(p.y-radius-0.5f));
        int last=std::min(origin_y_+height_-1,floor_int(p.y+radius-0.5f));
        for (int y=first;y<=last;++y) {
            float dy=y+0.5f-p.y;
            float half=std::sqrt(std::max(0.0f,radius*radius-dy*dy));
            span(y,ceil_int(p.x-half-0.5f),floor_int(p.x+half-0.5f),paint);
        }
    }
    uint16_t *pixels_;
    int width_, height_, origin_x_, origin_y_;
    size_t stride_;
    bool valid_;
    bool navigation_style_=false;
    mutable uint16_t cached_color_=0;
    mutable const uint32_t *cached_shades_=nullptr;
};
} // namespace motonav
