#include "motonav/core/navigation_session.h"
#include <fstream>
#include <iostream>
#include <vector>
#include <string>
#include <cstdlib>
using Bytes=std::vector<uint8_t>;
void check(bool ok,const char *message){if(!ok){std::cerr<<message<<'\n';std::exit(1);}}
std::string feed(motonav::RouteReceiver &r,const Bytes &b,uint64_t now){char reply[32]{};for(auto c:b)r.feed(c,now,reply);return reply;}
void crc(Bytes &b){auto c=motonav::NavigationReceiver::crc32(reinterpret_cast<const char *>(b.data()),b.size()-4);for(unsigned i=0;i<4;i++)b[b.size()-4+i]=c>>(8*i);}
Bytes with_sequence(Bytes b,uint32_t sequence){for(unsigned i=0;i<4;i++)b[5+i]=sequence>>(8*i);crc(b);return b;}
int main(int argc,char **argv){
    check(argc==2,"fixture path");std::ifstream input(argv[1]);std::string row;std::vector<Bytes> frames;
    while(std::getline(input,row)){Bytes b;for(size_t i=0;i<row.size();i+=2)b.push_back(std::stoul(row.substr(i,2),nullptr,16));if(!b.empty())frames.push_back(b);}
    check(frames.size()==6,"complete Java fixture");motonav::RouteReceiver r;
    {
        motonav::MapPositionHistory history;
        check(!history.last(100000,1).quality,"history starts empty");
        check(history.remember({1,12,34,-1,550,1,3500},100000),"validated observation retained");
        check(history.last(111500,1).age_ms==15000,"history preserves measurement age after current fix expires");
        check(!history.last(99999,1).quality&&!history.last(100000,2).quality,"clock rollback and unrelated origin cannot expose old coordinates");
        check(history.last(156499,1).quality&&!history.last(156500,1).quality,"retention ends at sixty seconds of source age");
        check(!history.remember({1,99,99,-1,550,2,0},110000)&&history.last(110000,1).x==12,"invalid observation cannot overwrite or renew history");
        history.clear();check(!history.last(110000,1).quality,"explicit clearing removes history");
        check(history.remember({1,12,34,-1,8,2,3000},UINT64_MAX-500),"large monotonic clock accepted");
        check(history.last(UINT64_MAX,1).age_ms==3500,"age arithmetic cannot wrap at the clock limit");
    }
    check(feed(r,frames[0],100000)=="A,1,NAV4S4\n","explicit independent position capability");feed(r,frames[1],100000);
    check(feed(r,frames[2],100000)=="A,3,OK\n","approximate point accepted while guidance waits");
    auto s=r.scene(100000);check(s.position_quality==1&&s.position_accuracy_m==550&&s.x==12&&s.y==34&&s.overview,"measured point and quality share an atomic coordinate frame");
    check(r.navigation().guidance(100000).mode==motonav::Waiting,"position never promotes a navigation instruction");
    feed(r,frames[2],102000);check(!r.scene(104001).position_quality,"position/duplicate cannot refresh navigation heartbeat");
    auto heartbeat=frames[1];heartbeat[5]=4;crc(heartbeat);feed(r,heartbeat,111000);
    check(r.scene(111499).position_quality==1&&!r.scene(111500).position_quality,"duplicate cannot renew source TTL; independent age expires exactly");
    check(r.scene(111500).last_position.quality==1&&r.scene(111500).last_position.age_ms==15000,"fresh marker expires while separate historical observation remains");
    motonav::RouteReceiver full;for(unsigned i=0;i<4;i++)feed(full,frames[i],i==3?101000:100000);
    check(full.scene(101000).position_quality==2&&full.scene(101000).position_accuracy_m==8,"precise point does not require turn guidance");
    auto unknown=frames[4];unknown[19]=250;unknown[20]=0;crc(unknown);
    feed(full,unknown,101001);check(!full.scene(101001).position_quality,"unknown explicitly clears current marker");
    check(full.scene(101001).x==250&&full.scene(101001).last_position.x==12,"unknown focus cannot move the historical point to an unmeasured camera center");
    feed(full,frames[5],101002);check(!full.scene(101002).count,"STOP clears independent position and route");
    for(size_t i=4;i<frames[2].size();i++){
        motonav::RouteReceiver bad;feed(bad,frames[0],100000);feed(bad,frames[1],100000);auto f=frames[2];f[i]^=1;
        check(feed(bad,f,100000)=="E,NAV4\n"&&!bad.scene(100000).position_quality,"corruption has no partial position side effects");
    }
    for(auto mutation:std::vector<std::pair<size_t,uint8_t>>{{33,3},{33,2},{34,0},{35,255},{36,255},{37,255},{14,65}}){
        motonav::RouteReceiver bad;feed(bad,frames[0],100000);feed(bad,frames[1],100000);auto f=frames[2];f[mutation.first]=mutation.second;
        if(mutation.first==34)f[35]=0;
        if(mutation.first==36)f[37]=255;
        crc(f);
        check(feed(bad,f,100000)=="E,NAV4\n"&&!bad.scene(100000).position_quality,"valid CRC cannot bypass quality/age/accuracy bounds");
    }
    motonav::RouteReceiver old;auto hello=frames[0];hello[4]=11;crc(hello);feed(old,hello,100000);feed(old,frames[1],100000);
    check(feed(old,frames[2],100000)=="E,NAV4\n","position requires negotiated support");
    full.disconnect();check(!full.scene(101003).position_quality,"disconnect clears position");
    {
        motonav::RouteReceiver intermittent;feed(intermittent,frames[0],100000);uint32_t sequence=1;
        unsigned current=0,historical=0;
        for(uint64_t now=100000;now<=220000;now+=1000){
            feed(intermittent,with_sequence(frames[1],++sequence),now);
            unsigned age=3500+(now-100000)%25000;
            Bytes focus=age<15000?frames[2]:frames[4];
            if(age<15000){focus[36]=age;focus[37]=age>>8;}
            feed(intermittent,with_sequence(focus,++sequence),now);
            auto scene=intermittent.scene(now);
            check(scene.position_quality||motonav::has_last_position(scene),"25-second indoor fixes never leave an invisible marker gap");
            check(intermittent.navigation().guidance(now).mode==motonav::Waiting,"history cannot become turn guidance");
            if(scene.position_quality)++current;else ++historical;
        }
        check(current&&historical,"replay exercises both fresh and historical intervals");
        for(uint64_t now=221000;now<=270000;now+=1000){
            feed(intermittent,with_sequence(frames[1],++sequence),now);
            feed(intermittent,with_sequence(frames[4],++sequence),now);
        }
        check(!motonav::has_last_position(intermittent.scene(270000)),"repeated unavailable focus cannot indefinitely retain a position");
    }
    {
        motonav::RouteReceiver changed;for(unsigned i=0;i<3;i++)feed(changed,frames[i],100000);
        auto focus=frames[4];focus[15]=2;crc(focus);feed(changed,focus,101000);
        check(!motonav::has_last_position(changed.scene(101000)),"new origin discards unrelated historical coordinates");
    }
    for(unsigned reset=0;reset<5;reset++){
        motonav::NavigationSession session;session.connected();session.protocol(4);session.security(true);session.subscription(true);char reply[32]{};
        for(unsigned i=0;i<3;i++)for(auto b:frames[i])session.feed(b,100000,reply);
        check(motonav::has_last_position(session.snapshot(100000).route),"session owns the accepted history");
        if(reset==0)session.security(false);
        if(reset==1)session.subscription(false);
        if(reset==2)session.disconnected();
        if(reset==3)session.pairing(123456);
        if(reset==4)session.protocol(3);
        check(!motonav::has_last_position(session.snapshot(100001).route),"security/subscription/session transitions clear historical location");
    }
    std::cout<<"PASS: Java-to-C++ measured map position, WAIT isolation, expiry, corruption and compatibility\n";
}
