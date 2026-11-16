#include "motonav/core/route_protocol.h"
#include <fstream>
#include <iostream>
#include <vector>
#include <string>
#include <cstdlib>
using Bytes=std::vector<uint8_t>;
void check(bool ok,const char *message){if(!ok){std::cerr<<message<<'\n';std::exit(1);}}
std::string feed(motonav::RouteReceiver &r,const Bytes &b,uint64_t now){char reply[32]{};for(auto c:b)r.feed(c,now,reply);return reply;}
void crc(Bytes &b){auto c=motonav::NavigationReceiver::crc32(reinterpret_cast<const char *>(b.data()),b.size()-4);for(unsigned i=0;i<4;i++)b[b.size()-4+i]=c>>(8*i);}
int main(int argc,char **argv){
    check(argc==2,"fixture path");std::ifstream input(argv[1]);std::string row;std::vector<Bytes> frames;
    while(std::getline(input,row)){Bytes b;for(size_t i=0;i<row.size();i+=2)b.push_back(std::stoul(row.substr(i,2),nullptr,16));if(!b.empty())frames.push_back(b);}
    check(frames.size()==6,"complete Java fixture");motonav::RouteReceiver r;
    check(feed(r,frames[0],100000)=="A,1,NAV4S4\n","explicit independent position capability");feed(r,frames[1],100000);
    check(feed(r,frames[2],100000)=="A,3,OK\n","approximate point accepted while guidance waits");
    auto s=r.scene(100000);check(s.position_quality==1&&s.position_accuracy_m==550&&s.x==12&&s.y==34&&s.overview,"measured point and quality share an atomic coordinate frame");
    check(r.navigation().guidance(100000).mode==motonav::Waiting,"position never promotes a navigation instruction");
    feed(r,frames[2],102000);check(!r.scene(104001).position_quality,"position/duplicate cannot refresh navigation heartbeat");
    auto heartbeat=frames[1];heartbeat[5]=4;crc(heartbeat);feed(r,heartbeat,111000);
    check(r.scene(111499).position_quality==1&&!r.scene(111500).position_quality,"duplicate cannot renew source TTL; independent age expires exactly");
    motonav::RouteReceiver full;for(unsigned i=0;i<4;i++)feed(full,frames[i],i==3?101000:100000);
    check(full.scene(101000).position_quality==2&&full.scene(101000).position_accuracy_m==8,"precise point does not require turn guidance");
    feed(full,frames[4],101001);check(!full.scene(101001).position_quality,"unknown explicitly clears marker");
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
    std::cout<<"PASS: Java-to-C++ measured map position, WAIT isolation, expiry, corruption and compatibility\n";
}
