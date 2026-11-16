#include "motonav/core/navigation_session.h"
#include <fstream>
#include <iostream>
#include <vector>
#include <string>
#include <cstdlib>
using Bytes=std::vector<uint8_t>;
void check(bool ok,const char *message){if(!ok){std::cerr<<message<<'\n';std::exit(1);}}
std::string feed(motonav::RouteReceiver &r,const Bytes &bytes,uint64_t now){char reply[32]{};for(auto b:bytes)r.feed(b,now,reply);return reply;}
void crc(Bytes &b){auto c=motonav::NavigationReceiver::crc32(reinterpret_cast<const char *>(b.data()),b.size()-4);for(unsigned i=0;i<4;i++)b[b.size()-4+i]=c>>(8*i);}
int main(int argc,char **argv){
    check(argc==2,"fixture path");std::ifstream input(argv[1]);std::string row;std::vector<Bytes> frames;
    while(std::getline(input,row)){Bytes b;for(size_t i=0;i<row.size();i+=2)b.push_back(std::stoul(row.substr(i,2),nullptr,16));if(!b.empty())frames.push_back(b);}
    check(frames.size()==6,"complete Java fixture");motonav::RouteReceiver r;
    check(feed(r,frames[0],100000)=="A,1,NAV4S3\n","explicit capability");feed(r,frames[1],100000);
    check(feed(r,frames[2],100000)=="A,3,OK\n","UTF-8 metadata accepted");
    auto d=r.details(100000);check(std::string(d.current_road)=="光华东路"&&std::string(d.next_road)=="东三环北路"&&d.eta_minute==686&&d.speed_limit==-1,"actual Java fields reach firmware");
    check(feed(r,frames[2],102500)=="A,3,OK\n"&&r.details(103001).revision==0,"duplicate cannot renew metadata freshness");
    check(!r.navigation().connected(104001),"metadata never renews navigation stream");
    feed(r,frames[3],100001);check(!r.details(100001).revision,"WAIT clears road names and ETA");
    feed(r,frames[4],100002);check(!r.details(100002).revision,"recovery cannot resurrect old metadata");
    feed(r,frames[5],100002);check(r.details(100002).revision==1&&!r.details(100002).current_road[0],"empty values explicitly clear fields");
    r.disconnect();check(!r.details(100003).revision,"disconnect clears all details");
    for(size_t i=4;i<frames[2].size();i++){
        motonav::RouteReceiver bad;feed(bad,frames[0],100000);feed(bad,frames[1],100000);auto f=frames[2];f[i]^=1;
        check(feed(bad,f,100000)=="E,NAV4\n"&&!bad.details(100000).revision,"CRC rollback is atomic");
    }
    for(auto change:std::vector<std::pair<size_t,uint8_t>>{{13,2},{17,1},{22,0x7f},{23,0},{24,0x7f},{25,64},{26,64},{27,0},{27,0xc0},{27,0xff},{28,0x41}}){
        motonav::RouteReceiver bad;feed(bad,frames[0],100000);feed(bad,frames[1],100000);auto f=frames[2];f[change.first]=change.second;crc(f);
        check(feed(bad,f,100000)=="E,NAV4\n"&&!bad.details(100000).revision,"valid CRC cannot bypass field/reference/UTF-8 validation");
    }
    motonav::RouteReceiver legacy;auto hello=frames[0];hello[4]=8;crc(hello);feed(legacy,hello,100000);feed(legacy,frames[1],100000);
    check(feed(legacy,frames[2],100000)=="E,NAV4\n","metadata requires negotiated capability");
    motonav::RouteReceiver expired;feed(expired,frames[0],100000);feed(expired,frames[1],100000);
    check(feed(expired,frames[2],104001)=="A,3,OK\n"&&!expired.details(104001).revision,"late optional metadata is discarded without disconnecting or reviving guidance");
    motonav::NavigationSession session;session.connected();session.protocol(4);session.security(true);session.subscription(true);char reply[32];
    for(unsigned i=0;i<3;i++)for(auto b:frames[i])session.feed(b,100000,reply);
    check(std::string(session.snapshot(100000).details.current_road)=="光华东路","UI boundary exposes validated details");
    std::cout<<"PASS: Java-to-C++ HUD, freshness, unknowns, corruption, UTF-8 and capability checks\n";
}
