#include "motonav/core/route_protocol.h"
#include <fstream>
#include <vector>
#include <string>
#include <iostream>
#include <cstdlib>
static unsigned checks;
static void check(bool ok,const char *why){++checks;if(!ok){std::cerr<<why<<'\n';std::exit(1);}}
static std::string feed(motonav::RouteReceiver &r,const std::vector<uint8_t>&frame,uint64_t now=100000){
    char reply[32]{};unsigned count=0;for(auto byte:frame)if(r.feed(byte,now,reply))++count;
    check(count==1,"one reply per frame");return reply;
}
static void crc(std::vector<uint8_t>&frame){auto value=motonav::NavigationReceiver::crc32(reinterpret_cast<const char *>(frame.data()),frame.size()-4);for(unsigned i=0;i<4;i++)frame[frame.size()-4+i]=value>>(8*i);}
int main(int argc,char **argv){
    check(argc==2,"fixture argument");std::ifstream input(argv[1]);std::string row;std::vector<std::vector<uint8_t>> frames;
    while(std::getline(input,row)){std::vector<uint8_t> frame;for(size_t i=0;i<row.size();i+=2)frame.push_back(std::stoul(row.substr(i,2),nullptr,16));frames.push_back(frame);}
    check(frames.size()==8,"Java S6 fixture");motonav::RouteReceiver receiver;
    check(feed(receiver,frames[0])=="A,1,NAV4S6\n","Explicit S6 negotiation");feed(receiver,frames[1]);feed(receiver,frames[2]);
    auto invalid=frames[3];invalid[27]=9;crc(invalid);
    check(feed(receiver,invalid)[0]=='E'&&receiver.basemap().count==0,"Unknown category cannot publish a partial scene");
    unsigned water=0;for(unsigned i=0;i<frames[3][25];i++)if(frames[3][27+14*i]==8)water=27+14*i;
    check(water>0,"Java sent a waterway centerline");invalid=frames[3];invalid[water+10]=1;crc(invalid);
    check(feed(receiver,invalid)[0]=='E'&&receiver.basemap().count==0,"Waterway tail validation is atomic");
    check(feed(receiver,frames[3])[0]=='A',"Accept corrected scene after a rejected chunk");
    bool kinds[9]{};for(unsigned i=0;i<receiver.basemap().count;i++)kinds[receiver.basemap().items[i].kind]=true;
    check(kinds[0]&&kinds[6]&&kinds[7]&&kinds[8],"C++ retains roads, land use, sites and water lines");
    check(feed(receiver,frames[4])[0]=='A'&&receiver.basemap().labels.count==1,"S6 retains independent annotations");
    feed(receiver,frames[5]);check(receiver.navigation().guidance(100000).mode==motonav::Live,"Live guidance unaffected");
    feed(receiver,frames[6],104002);check(receiver.basemap().count>0,"Waiting retains real scene geometry");
    feed(receiver,frames[7],104003);check(receiver.basemap().count==0,"STOP clears new categories too");
    receiver.disconnect();auto old=frames[0];old[4]=15;crc(old);
    check(feed(receiver,old)=="A,1,NAV4S5\n","Reconnect may negotiate older capabilities");feed(receiver,frames[1]);feed(receiver,frames[2]);
    check(feed(receiver,frames[3])[0]=='E'&&receiver.basemap().count==0,"S6 capability never leaks into an S5 session");
    std::cout<<"PASS: "<<checks<<" Java-to-C++ surface, capability, atomicity and lifecycle checks\n";
}
