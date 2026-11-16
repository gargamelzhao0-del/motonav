#include "motonav/core/navigation_session.h"
#include <fstream>
#include <vector>
#include <string>
#include <iostream>
#include <cstdlib>
static int checks;
static void check(bool ok,const char *message){++checks;if(!ok){std::cerr<<message<<'\n';std::exit(1);}}
static std::string feed(motonav::RouteReceiver &r,const std::vector<uint8_t>&v,uint64_t now=100000){char ack[32]{};unsigned replies=0;for(auto b:v)if(r.feed(b,now,ack))replies++;check(replies==1,"one reply");return ack;}
static void crc(std::vector<uint8_t>&v){auto c=motonav::NavigationReceiver::crc32(reinterpret_cast<const char *>(v.data()),v.size()-4);for(int i=0;i<4;i++)v[v.size()-4+i]=c>>(8*i);}
int main(int argc,char **argv){
    check(argc==3,"fixture paths");std::ifstream input(argv[1]);std::string row;std::vector<std::vector<uint8_t>> frames;
    while(std::getline(input,row)){std::vector<uint8_t> v;for(size_t i=0;i<row.size();i+=2)v.push_back(std::stoul(row.substr(i,2),nullptr,16));frames.push_back(v);}
    check(frames.size()==8,"Java scene fixture");motonav::RouteReceiver r;
    check(feed(r,frames[0])=="A,1,NAV4S\n","scene capability");feed(r,frames[1]);feed(r,frames[2]);
    check(r.scene(100000).local_view&&r.scene(100000).overview&&r.scene(100000).total_m==5000,"waiting has local plan without live position");
    check(feed(r,frames[4])[0]=='E',"out-of-order chunk rejected");
    auto malformed=frames[3];malformed[22]=6;crc(malformed);check(feed(r,malformed)[0]=='E',"unknown primitive rejected");
    malformed=frames[3];malformed[24]=255;malformed[25]=127;crc(malformed);check(feed(r,malformed)[0]=='E',"coordinate bound");
    check(feed(r,frames[3])[0]=='A'&&r.basemap().revision==0,"partial map remains invisible");
    check(feed(r,frames[3])[0]=='A'&&r.basemap().revision==0,"duplicate does not append twice");
    check(feed(r,frames[4])[0]=='A'&&r.basemap().count==23,"complete map atomically visible");
    check(r.navigation().guidance(100000).mode==motonav::Waiting,"map never promotes GPS to live");
    feed(r,frames[5]);check(!r.scene(100000).overview&&r.scene(100000).local_view,"live local map");
    check(r.navigation().guidance(104001).mode!=motonav::Live&&r.scene(104001).overview,"map does not extend guidance lifetime");
    feed(r,frames[6],104002);check(r.basemap().count==23&&r.scene(104002).local_view,"GPS loss retains map");
    feed(r,frames[7],104003);check(r.basemap().count==0&&r.scene(104003).count==0,"stop clears map");
    for(size_t i=4;i<frames[3].size();i++){motonav::RouteReceiver test;feed(test,frames[0]);feed(test,frames[1]);feed(test,frames[2]);auto v=frames[3];v[i]^=1;check(feed(test,v)[0]=='E'&&test.basemap().count==0,"CRC error cannot publish partial geometry");}
    frames.clear();std::ifstream updates(argv[2]);
    while(std::getline(updates,row)){std::vector<uint8_t> v;for(size_t i=0;i<row.size();i+=2)v.push_back(std::stoul(row.substr(i,2),nullptr,16));frames.push_back(v);}
    check(frames.size()==10,"progressive Java fixture");r.disconnect();
    check(feed(r,frames[0])=="A,1,NAV4S2\n","progressive capability");feed(r,frames[1]);feed(r,frames[2]);feed(r,frames[3]);
    check(r.basemap().count==0,"partial radio frame cannot publish early tile");
    feed(r,frames[4]);check(r.basemap().generation==1&&!r.basemap().complete&&r.basemap().count==23,"first complete tile scene visible before all tiles");
    malformed=frames[5];malformed[26]=2;crc(malformed);check(feed(r,malformed)[0]=='E'&&r.basemap().generation==1,"invalid completion flag cannot replace visible map");
    malformed=frames[5];malformed[17]=1;crc(malformed);check(feed(r,malformed)[0]=='E',"old map generation cannot replace current map");
    feed(r,frames[5]);check(r.basemap().generation==1,"old scene retained during replacement");
    feed(r,frames[6]);check(r.basemap().generation==2&&r.basemap().complete,"final scene replaces first tile atomically");
    check(feed(r,frames[6])[0]=='A'&&r.basemap().generation==2,"final update duplicate is idempotent");
    feed(r,frames[7]);check(r.navigation().guidance(104001).mode!=motonav::Live,"progressive map cannot extend fix age");
    feed(r,frames[8],104002);check(r.basemap().complete,"GPS loss preserves completed map");
    feed(r,frames[9],104003);check(r.basemap().generation==0,"stop clears progressive scene");
    std::cout<<"Basemap receiver: "<<checks<<" checks passed\n";
}
