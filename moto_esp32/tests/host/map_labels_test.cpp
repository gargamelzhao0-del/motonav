#include "motonav/core/navigation_session.h"
#include <fstream>
#include <vector>
#include <string>
#include <iostream>
#include <cstdlib>
static int checks;
static void check(bool ok,const char *message){++checks;if(!ok){std::cerr<<message<<'\n';std::exit(1);}}
using Frame=std::vector<uint8_t>;
static std::string feed(motonav::RouteReceiver &r,const Frame &v,uint64_t now=100000){
    char ack[32]{};unsigned replies=0;for(auto b:v)if(r.feed(b,now,ack))replies++;
    check(replies==1,"one bounded reply");return ack;
}
static void crc(Frame &v){auto c=motonav::NavigationReceiver::crc32(reinterpret_cast<const char *>(v.data()),v.size()-4);for(int i=0;i<4;i++)v[v.size()-4+i]=c>>(8*i);}
int main(int argc,char **argv){
    check(argc==2,"fixture path");std::ifstream input(argv[1]);std::string row;std::vector<Frame> f;
    while(std::getline(input,row)){Frame v;for(size_t i=0;i<row.size();i+=2)v.push_back(std::stoul(row.substr(i,2),nullptr,16));f.push_back(v);}
    check(f.size()==9,"Java S5 fixture");motonav::RouteReceiver r;
    check(feed(r,f[0])=="A,1,NAV4S5\n","Explicit label capability");
    for(int i=1;i<4;i++)check(feed(r,f[i])[0]=='A',"setup accepted");
    check(feed(r,f[5])[0]=='E',"out of order cannot publish labels");
    for(size_t i=4;i<f[4].size();i++){auto bad=f[4];bad[i]^=1;check(feed(r,bad)[0]=='E'&&r.basemap().labels.count==0,"CRC protects labels");}
    for(auto mutation:std::vector<std::pair<size_t,uint8_t>>{{17,2},{21,25},{23,5},{24,9},{26,127},{29,64},{30,0xff}}){
        auto bad=f[4];bad[mutation.first]=mutation.second;crc(bad);
        check(feed(r,bad)[0]=='E'&&r.basemap().labels.count==0,"invalid chunk cannot mutate published labels");
    }
    check(feed(r,f[4])[0]=='A'&&r.basemap().labels.count==0,"partial labels remain invisible");
    check(feed(r,f[4])[0]=='A'&&r.basemap().labels.count==0,"exact retry is idempotent");
    check(feed(r,f[5])[0]=='A'&&r.basemap().labels.count==5,"complete labels published atomically");
    check(std::strcmp(r.basemap().labels.items[0].name,"中山路")==0&&r.basemap().labels.items[4].kind==6,"UTF-8 and typed POI survive Java/C++");
    check(r.navigation().guidance(100000).mode==motonav::Waiting,"labels never promote guidance");
    check(!r.navigation().connected(104001),"labels do not renew navigation heartbeat");
    check(feed(r,f[6])[0]=='A'&&r.basemap().labels.count==5&&r.basemap().labels.generation==1,"same-origin geometry replacement retains old labels");
    auto old=f[4];old[5]=8;crc(old);check(feed(r,old)[0]=='E'&&r.basemap().labels.count==5,"old generation cannot replace names");
    check(feed(r,f[7])[0]=='A'&&r.basemap().labels.count==0&&r.basemap().labels.generation==2,"explicit empty publication clears old labels");
    feed(r,f[8]);check(r.basemap().labels.generation==0,"STOP clears annotation state");
    for(int reset=0;reset<5;reset++){
        motonav::NavigationSession s;s.connected();s.protocol(4);s.security(true);s.subscription(true);
        char reply[32]{};for(int i=0;i<6;i++)for(auto b:f[i])s.feed(b,100000,reply);
        check(s.snapshot(100000).basemap_label_count==5,"snapshot exposes label generation/count");
        switch(reset){case 0:s.disconnected();break;case 1:s.security(false);break;case 2:s.subscription(false);break;case 3:s.pairing(123456);break;default:s.protocol(3);}
        check(s.snapshot(100000).basemap_label_count==0,"session reset clears names");
    }
    std::cout<<"PASS: "<<checks<<" label receiver checks\n";
}
