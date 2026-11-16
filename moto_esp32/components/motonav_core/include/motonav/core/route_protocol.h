#pragma once
#include "motonav/core/navigation_protocol.h"
#include "motonav/core/route_scene.h"
#include "motonav/core/basemap_scene.h"
#include "motonav/core/navigation_details.h"

namespace motonav {
// Live snapshots are atomic. Negotiated route plans survive guidance expiry but
// never refresh navigation timestamps or provide a vehicle pose.
class RouteReceiver {
public:
    static constexpr size_t MaxBytes=304;
    bool feed(uint8_t byte,uint64_t now,char (&reply)[32]) {
        if(!used_ && byte!='M')return reject(reply);
        if(used_==1 && byte!='4') { used_=0; return reject(reply); }
        bytes_[used_++]=byte;
        if(used_==4) {
            expected_=u16(2);
            if(expected_!=17 && (expected_<26 || expected_>MaxBytes)) { used_=0; return reject(reply); }
        }
        if(used_<4 || used_<expected_)return false;
        bool ok=parse(now,reply); used_=0;
        return ok?true:reject(reply);
    }
    void disconnect() {
        navigation_.disconnect(); scene_={}; plan_={}; used_=0; sequence_=0; checksum_=0;
        route_revision_=0; route_crc_=0; plan_crc_=0; preview_enabled_=false;
        basemap_enabled_=updates_enabled_=false; map_={}; staging_={}; staged_=0;
        details_enabled_=false;details_={};navigation_sequence_=0;details_at_=0;
    }
    const NavigationReceiver &navigation() const { return navigation_; }
    ReceiverStats stats() const { return stats_; }
    const BasemapScene &basemap() const { return map_; }
    NavigationDetails details(uint64_t now) const {
        if(!navigation_.connected(now)||navigation_.source_expired(now)||navigation_.guidance(now).mode!=Live
                ||details_.revision!=scene_.revision||now<details_at_||now-details_at_>3000)return {};
        return details_;
    }
    RouteScene scene(uint64_t now) const {
        if(!navigation_.handshaken())return {};
        if(navigation_.guidance(now).mode==Live && scene_.count>=2) {
            auto result=scene_;result.local_view=basemap_enabled_;
            if(result.revision==plan_.revision){result.total_m=plan_.total_m;result.total_s=plan_.total_s;}
            return result;
        }
        if(plan_.count>=2)return plan_;
        // Older senders can retain their last real route as a static overview.
        auto result=scene_; result.overview=true; return result;
    }
private:
    uint8_t bytes_[MaxBytes]{};
    size_t used_=0,expected_=0;
    uint32_t sequence_=0,session_=0,checksum_=0;
    uint32_t route_revision_=0,route_crc_=0;
    uint32_t plan_crc_=0;
    bool preview_enabled_=false;
    bool basemap_enabled_=false;
    bool updates_enabled_=false;
    bool details_enabled_=false;
    NavigationDetails details_;
    uint32_t navigation_sequence_=0;
    uint64_t details_at_=0;
    BasemapScene map_,staging_;
    unsigned staged_=0;
    NavigationReceiver navigation_;
    RouteScene scene_,plan_;
    ReceiverStats stats_;
    uint16_t u16(size_t p) const { return bytes_[p]|static_cast<uint16_t>(bytes_[p+1])<<8; }
    uint32_t u32(size_t p) const { return u16(p)|static_cast<uint32_t>(u16(p+2))<<16; }
    int i16(size_t p) const { unsigned n=u16(p); return n<32768?static_cast<int>(n):static_cast<int>(n)-65536; }
    int i32(size_t p) const { uint32_t n=u32(p); return static_cast<int>(n<=INT_MAX?static_cast<int64_t>(n):static_cast<int64_t>(n)-4294967296LL); }
    bool reject(char (&reply)[32]) { ++stats_.rejected; std::snprintf(reply,sizeof(reply),"E,NAV4\n"); return true; }
    bool core(const char *body,uint64_t now) {
        char wire[192],reply[32]; auto crc=NavigationReceiver::crc32(body,std::strlen(body));
        int size=std::snprintf(wire,sizeof(wire),"%s,%08lX\n",body,static_cast<unsigned long>(crc));
        if(size<0 || static_cast<size_t>(size)>=sizeof(wire))return false;
        bool complete=false;
        for(int i=0;i<size;i++)complete=navigation_.feed(wire[i],now,reply);
        return complete && reply[0]=='A';
    }
    bool parse(uint64_t now,char (&reply)[32]) {
        uint32_t crc=u32(expected_-4),seq=u32(5),session=u32(9);
        if(NavigationReceiver::crc32(reinterpret_cast<const char *>(bytes_),expected_-4)!=crc
                ||!seq||seq>INT_MAX||!session||session>INT_MAX)return false;
        const unsigned type=bytes_[4];
        const bool hello=type==1||type==3||type==5||type==8||type==11;
        const char *ack=hello?(type==11?"NAV4S3":type==8?"NAV4S2":type==5?"NAV4S":type==3?"NAV4P":"NAV4"):"OK";
        if((hello && expected_!=17)||(!hello && type!=2 && type!=4 && type!=6 && type!=7 && type!=9 && type!=10))return false;
        if(navigation_.handshaken() && seq==sequence_ && session==session_ && crc==checksum_) {
            ++stats_.duplicates; std::snprintf(reply,sizeof(reply),"A,%lu,%s\n",static_cast<unsigned long>(seq),ack); return true;
        }
        char body[160];
        if(hello) {
            if(navigation_.handshaken()||seq!=1)return false;
            std::snprintf(body,sizeof(body),"H,3,%lu,%lu",static_cast<unsigned long>(seq),static_cast<unsigned long>(session));
            if(!core(body,now))return false;
            scene_={}; plan_={}; preview_enabled_=type==3;basemap_enabled_=type==5||type==8||type==11;updates_enabled_=type==8||type==11;
            details_enabled_=type==11;details_={};navigation_sequence_=0;details_at_=0;
            map_={};staging_={};staged_=0;
        } else if(type==10) {
            if(!details_enabled_||!navigation_.handshaken()||session!=session_||seq<=sequence_||expected_<31)return false;
            unsigned current=bytes_[25],next=bytes_[26];
            int eta=i16(21),limit=i16(23);uint32_t revision=u32(13);
            if(!revision||revision!=scene_.revision||u32(17)!=navigation_sequence_
                    ||current>NavigationDetails::MaxNameBytes||next>NavigationDetails::MaxNameBytes
                    ||expected_!=31+current+next||eta<-1||eta>=1440||limit<-1||limit==0||limit>300
                    ||!valid_road_name(bytes_+27,current)||!valid_road_name(bytes_+27+current,next))return false;
            NavigationDetails value;value.revision=revision;value.eta_minute=eta;value.speed_limit=limit;
            std::memcpy(value.current_road,bytes_+27,current);std::memcpy(value.next_road,bytes_+27+current,next);
            // Optional HUD arriving near the source's expiry is acknowledged but
            // discarded; it must not disconnect an otherwise healthy radio link.
            if(navigation_.guidance(now).mode==Live&&navigation_.connected(now)&&!navigation_.source_expired(now)){
                details_=value;details_at_=now;
            }else details_={};
        } else if(type==6) {
            if(!basemap_enabled_||!navigation_.handshaken()||session!=session_||seq<=sequence_||expected_<45)return false;
            unsigned count=bytes_[14],reason=bytes_[13];uint32_t revision=u32(15);
            int x=i16(19),y=i16(21),heading=i16(23),total_m=i32(25),total_s=i32(29);
            if(count<2||count>64||expected_!=37+4*count||reason>7||!revision||revision>INT_MAX
                ||revision<plan_.revision||std::abs(x)>32000||std::abs(y)>32000||heading<-1||heading>3599
                ||total_m<-1||total_m>50000000||total_s<-1||total_s>31536000)return false;
            auto geometry_crc=NavigationReceiver::crc32(reinterpret_cast<const char *>(bytes_+33),4*count);
            if(revision==plan_.revision&&geometry_crc!=plan_crc_)return false;
            RouteScene next;next.revision=revision;next.count=count;next.overview=true;next.local_view=true;
            next.waiting_reason=reason;next.x=x;next.y=y;next.heading=heading;next.total_m=total_m;next.total_s=total_s;
            for(unsigned i=0;i<count;i++) {
                int px=i16(33+4*i),py=i16(35+4*i);
                if(std::abs(px)>32000||std::abs(py)>32000)return false;
                next.points[i]={static_cast<int16_t>(px),static_cast<int16_t>(py)};
            }
            if(revision!=plan_.revision){map_={};staging_={};staged_=0;}
            plan_=next;plan_crc_=geometry_crc;
        } else if(type==7||type==9) {
            const bool update=type==9;const unsigned header=update?27:22;
            if(!basemap_enabled_||(update&&!updates_enabled_)||!navigation_.handshaken()||session!=session_||seq<=sequence_||expected_<header+4)return false;
            uint32_t revision=u32(13),generation=update?u32(17):1;
            unsigned total=u16(update?21:17),offset=u16(update?23:19),count=bytes_[update?25:21],flags=update?bytes_[26]:1;
            if(!revision||revision!=plan_.revision||!generation||generation>INT_MAX||flags>1||total>(update?BasemapScene::Capacity:192)||count>19||expected_!=header+4+14*count
                ||offset+count>total||(!count&&total)||(map_.revision==revision&&generation<=map_.generation))return false;
            if(offset&&(staging_.revision!=revision||staging_.generation!=generation||staging_.complete!=(flags==1)||staging_.count!=total||offset!=staged_))return false;
            // Validate the entire chunk before touching staging state.
            for(unsigned i=0;i<count;i++) {
                size_t p=header+14*i;unsigned kind=bytes_[p];
                if(kind>5||bytes_[p+1])return false;
                for(unsigned j=0;j<6;j++)if(std::abs(i16(p+2+2*j))>2048)return false;
                if(kind<3&&(i16(p+10)||i16(p+12)))return false;
            }
            if(!offset){staging_={};staging_.revision=revision;staging_.generation=generation;staging_.complete=flags==1;staging_.count=total;staged_=0;}
            for(unsigned i=0;i<count;i++) {
                size_t p=header+14*i;auto &item=staging_.items[offset+i];item.kind=bytes_[p];
                item.a={static_cast<int16_t>(i16(p+2)),static_cast<int16_t>(i16(p+4))};
                item.b={static_cast<int16_t>(i16(p+6)),static_cast<int16_t>(i16(p+8))};
                item.c={static_cast<int16_t>(i16(p+10)),static_cast<int16_t>(i16(p+12))};
            }
            staged_+=count;
            if(staged_==total){map_=staging_;}
        } else if(type==4) {
            if(!preview_enabled_ || !navigation_.handshaken() || session!=session_ || seq<=sequence_ || expected_<31)return false;
            unsigned reason=bytes_[13],count=bytes_[14]; uint32_t revision=u32(15);
            if(reason>7 || count<2 || count>64 || expected_!=23+4*count || !revision || revision>INT_MAX)return false;
            uint32_t geometry_crc=NavigationReceiver::crc32(reinterpret_cast<const char *>(bytes_+19),4*count);
            if(revision<plan_.revision || (revision==plan_.revision && geometry_crc!=plan_crc_))return false;
            RouteScene next; next.revision=revision; next.count=count; next.overview=true; next.waiting_reason=reason;
            for(unsigned i=0;i<count;i++) {
                int px=i16(19+i*4),py=i16(21+i*4);
                if(std::abs(px)>1024 || std::abs(py)>1024)return false;
                next.points[i]={static_cast<int16_t>(px),static_cast<int16_t>(py)};
            }
            plan_=next; plan_crc_=geometry_crc;
        } else {
            if(!navigation_.handshaken()||session!=session_||seq<=sequence_||expected_<48)return false;
            unsigned count=bytes_[37]; uint32_t revision=u32(33);
            if(expected_!=48+4*count || count>64 || count==1 || revision>INT_MAX)return false;
            int x=i16(38),y=i16(40),heading=i16(42);
            RouteScene next;
            uint32_t geometry_crc=NavigationReceiver::crc32(reinterpret_cast<const char *>(bytes_+44),4*count);
            if(!count) { if(revision || x || y || heading!=-1)return false; }
            else {
                if(bytes_[13]!=Live||!revision||std::abs(x)>32000||std::abs(y)>32000||heading<-1||heading>3599)return false;
                if(revision<route_revision_ || (revision==route_revision_ && geometry_crc!=route_crc_))return false;
                next.revision=revision; next.count=count; next.x=x; next.y=y; next.heading=heading;
                for(unsigned i=0;i<count;i++) {
                    int px=i16(44+i*4),py=i16(46+i*4);
                    if(std::abs(px)>32000||std::abs(py)>32000)return false;
                    next.points[i]={static_cast<int16_t>(px),static_cast<int16_t>(py)};
                }
            }
            std::snprintf(body,sizeof(body),"N,3,%lu,%lu,%u,%u,%d,%d,%d,%d,%u,%u",
                static_cast<unsigned long>(seq),static_cast<unsigned long>(session),bytes_[13],bytes_[14],
                i32(15),i32(19),i32(23),i16(27),u16(29),u16(31));
            if(!core(body,now))return false;
            navigation_sequence_=seq;
            if(bytes_[13]!=Live||revision!=details_.revision)details_={};
            if(bytes_[13]==Stopped || bytes_[13]==Arrived) { scene_={}; plan_={}; plan_crc_=0;map_={};staging_={};staged_=0; }
            else if(bytes_[13]==Live)scene_=next;
            if(count) { route_revision_=revision; route_crc_=geometry_crc; }
        }
        sequence_=seq; session_=session; checksum_=crc; ++stats_.accepted;
        std::snprintf(reply,sizeof(reply),"A,%lu,%s\n",static_cast<unsigned long>(seq),ack); return true;
    }
};
}
