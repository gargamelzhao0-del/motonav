#include "motonav/core/navigation_session.h"
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
#include <cstdlib>
static unsigned checks;
void check(bool value,const char *text) { ++checks; if(!value) { std::cerr<<text<<'\n'; std::exit(1); } }
std::string feed(motonav::RouteReceiver &receiver,const std::vector<uint8_t> &frame,uint64_t now) {
    char reply[32]{}; unsigned responses=0;
    for(uint8_t byte:frame)if(receiver.feed(byte,now,reply))responses++;
    check(responses==1,"one complete binary frame gets one ACK"); return reply;
}
void crc(std::vector<uint8_t> &bytes) {
    auto checksum=motonav::NavigationReceiver::crc32(reinterpret_cast<const char *>(bytes.data()),bytes.size()-4);
    for(int i=0;i<4;i++)bytes[bytes.size()-4+i]=checksum>>(i*8);
}
int main(int argc,char **argv) {
    check(argc==2,"Java fixture path");std::ifstream input(argv[1]);std::string line;
    std::vector<std::vector<uint8_t>> frames;
    while(std::getline(input,line)) {
        if(line.empty())continue;
        std::vector<uint8_t> bytes;for(size_t i=0;i<line.size();i+=2)bytes.push_back(std::stoul(line.substr(i,2),nullptr,16));
        frames.push_back(bytes);
    }
    check(frames.size()==6,"complete Java fixture set"); motonav::RouteReceiver receiver;
    check(feed(receiver,frames[0],100000)=="A,1,NAV4\n","hello negotiated NAV4");
    check(feed(receiver,frames[1],100000)=="A,2,OK\n","actual Java snapshot accepted");
    check(receiver.navigation().guidance(100000).speed_kmh==36,"speed units shared");
    auto scene=receiver.scene(100000);check(scene.count==4 && scene.points[0].y<0 && scene.points[3].x>0,"map axes survived binary transport");
    check(feed(receiver,frames[1],103000)=="A,2,OK\n","duplicate ACK");
    check(receiver.scene(104001).count==0,"duplicate does not renew stream freshness");
    check(feed(receiver,frames[2],104001)=="A,3,OK\n","new snapshot recovers");
    check(receiver.scene(104001).count==4,"atomic map recovery");
    check(feed(receiver,frames[3],104002)=="A,4,OK\n","waiting status");
    check(receiver.scene(104002).count==0,"waiting hides old map");
    check(feed(receiver,frames[4],104003)=="A,5,OK\n","end status");
    check(receiver.navigation().guidance(104003).mode==motonav::Stopped,"stop preserved");
    check(feed(receiver,frames[5],120000)=="A,6,OK\n","max-size frame works");
    check(receiver.scene(120000).count==64,"all 64 points received");
    for(size_t i=4;i<frames[1].size();i++) {
        motonav::RouteReceiver corrupt;feed(corrupt,frames[0],1);
        auto bytes=frames[1];bytes[i]^=1;
        check(feed(corrupt,bytes,2)=="E,NAV4\n","every corrupted payload byte rejected");
        check(corrupt.scene(2).count==0,"bad CRC cannot publish map");
    }
    for(size_t field: {size_t(9),size_t(37),size_t(43)}) {
        motonav::RouteReceiver invalid;feed(invalid,frames[0],1);
        auto bytes=frames[1];bytes[field]=255;crc(bytes);
        check(feed(invalid,bytes,2)=="E,NAV4\n","wrong session/count/heading rejected even with valid CRC");
    }
    motonav::RouteReceiver expired;feed(expired,frames[0],1);
    auto old=frames[1];old[29]=0x97;old[30]=0x3a;crc(old); // 14999ms age
    check(feed(expired,old,2)=="A,2,OK\n","near-expiry measurement accepted with remaining lifetime");
    check(expired.scene(4).count==0,"source lifetime independently expires");
    receiver.disconnect();check(receiver.scene(120000).count==0,"disconnect removes route");
    motonav::NavigationSession session;session.connected();session.protocol(4);session.security(true);session.subscription(true);
    char reply[32];for(auto &frame: {frames[0],frames[1]})for(uint8_t byte:frame)session.feed(byte,100000,reply);
    check(motonav::screen_mode(session.snapshot(100000))==motonav::ScreenMode::Live,"production session exposes NAV4 live guidance");
    check(session.snapshot(100000).route.count==4,"production UI snapshot contains same real-encoded map");
    session.security(false);check(session.snapshot(100000).route.count==0,"loss of encrypted authorization hides map");
    std::cout<<"NAV4 receiver: "<<checks<<" cross-language, corruption, expiry and session checks passed\n";
}
