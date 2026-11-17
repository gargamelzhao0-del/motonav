#include "motonav/core/route_protocol.h"
#include <fstream>
#include <vector>
#include <string>
#include <iostream>
#include <cstdlib>
static unsigned checks;
static void check(bool ok,const char *why){++checks;if(!ok){std::cerr<<why<<'\n';std::exit(1);}}
static std::string feed(motonav::RouteReceiver &r,const std::vector<uint8_t>&frame){
    char reply[32]{};unsigned count=0;for(auto byte:frame)if(r.feed(byte,100000,reply))++count;
    check(count==1,"one reply per frame");return reply;
}
static void crc(std::vector<uint8_t>&f){auto v=motonav::NavigationReceiver::crc32(reinterpret_cast<const char *>(f.data()),f.size()-4);for(unsigned i=0;i<4;i++)f[f.size()-4+i]=v>>(8*i);}
int main(int argc,char **argv){
    check(argc==2,"fixture argument");std::ifstream in(argv[1]);std::string row;std::vector<std::vector<uint8_t>> frames;
    while(std::getline(in,row)){std::vector<uint8_t> f;for(size_t i=0;i<row.size();i+=2)f.push_back(std::stoul(row.substr(i,2),nullptr,16));frames.push_back(f);}
    check(frames.size()==7,"Java S7 fixture");motonav::RouteReceiver receiver;
    check(feed(receiver,frames[0])=="A,1,NAV4S7\n","Explicit label source negotiation");
    for(unsigned i=1;i<4;i++)feed(receiver,frames[i]);
    auto bad=frames[4];bad[24]=4;crc(bad);
    check(feed(receiver,bad)[0]=='E'&&receiver.basemap().labels.count==0,"Invalid source rejects without publishing");
    check(feed(receiver,frames[4])[0]=='A'&&receiver.basemap().labels.count==0,"Names and sources remain atomic until complete");
    bad=frames[5];bad[24]=1;crc(bad);
    check(feed(receiver,bad)[0]=='E'&&receiver.basemap().labels.count==0,"Mixed-source chunks cannot change source mid-generation");
    check(feed(receiver,frames[5])[0]=='A'&&receiver.basemap().labels.count==5&&receiver.basemap().labels.sources==3,"Corrected final chunk publishes all five names with their source");
    feed(receiver,frames[6]);check(receiver.basemap().labels.count==0,"STOP clears supplementary names");
    receiver.disconnect();auto old=frames[0];old[4]=17;crc(old);check(feed(receiver,old)=="A,1,NAV4S6\n","Legacy capability is independent after reconnect");
    for(unsigned i=1;i<4;i++)feed(receiver,frames[i]);
    check(feed(receiver,frames[4])[0]=='E',"S7 packet cannot leak into an S6 session");
    motonav::MapLabelReceiver labels;motonav::MapLabels published;
    check(!labels.accept(nullptr,0,1,1,published),"Short label packet is rejected before reading header");
    std::cout<<"PASS: "<<checks<<" Java-to-C++ attributed names, atomicity and legacy checks\n";
}
