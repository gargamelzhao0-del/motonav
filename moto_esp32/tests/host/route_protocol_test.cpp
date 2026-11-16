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
    check(argc==3,"Java fixture paths");std::ifstream input(argv[1]);std::string line;
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
    check(receiver.navigation().guidance(104001).mode!=motonav::Live,"duplicate does not renew stream freshness");
    check(receiver.scene(104001).count==4 && receiver.scene(104001).overview,"expired guidance retains route without pose");
    check(feed(receiver,frames[2],104001)=="A,3,OK\n","new snapshot recovers");
    check(receiver.scene(104001).count==4,"atomic map recovery");
    check(feed(receiver,frames[3],104002)=="A,4,OK\n","waiting status");
    check(receiver.scene(104002).count==4 && receiver.scene(104002).overview,"waiting retains static route");
    check(feed(receiver,frames[4],104003)=="A,5,OK\n","end status");
    check(receiver.navigation().guidance(104003).mode==motonav::Stopped,"stop preserved");
    check(receiver.scene(104003).count==0,"stop removes route");
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
    check(expired.navigation().source_expired(4) && expired.scene(4).overview,"source lifetime independently expires without removing map");
    receiver.disconnect();check(receiver.scene(120000).count==0,"disconnect removes route");
    motonav::NavigationSession session;session.connected();session.protocol(4);session.security(true);session.subscription(true);
    char reply[32];for(auto &frame: {frames[0],frames[1]})for(uint8_t byte:frame)session.feed(byte,100000,reply);
    check(motonav::screen_mode(session.snapshot(100000))==motonav::ScreenMode::Live,"production session exposes NAV4 live guidance");
    check(session.snapshot(100000).route.count==4,"production UI snapshot contains same real-encoded map");
    session.security(false);check(session.snapshot(100000).route.count==0,"loss of encrypted authorization hides map");
    frames.clear();std::ifstream plans(argv[2]);
    while(std::getline(plans,line)) {
        if(line.empty())continue;
        std::vector<uint8_t> bytes;for(size_t i=0;i<line.size();i+=2)bytes.push_back(std::stoul(line.substr(i,2),nullptr,16));
        frames.push_back(bytes);
    }
    check(frames.size()==11,"complete route-plan Java fixture set");
    motonav::RouteReceiver preview;
    check(feed(preview,frames[0],100000)=="A,1,NAV4P\n","explicit preview negotiation");
    check(feed(preview,frames[1],100000)=="A,2,OK\n","plan without GPS accepted");
    check(!preview.navigation().has_guidance() && preview.scene(100000).count==4 && preview.scene(100000).overview,"plan never invents navigation or pose");
    feed(preview,frames[2],100000);feed(preview,frames[3],100000);
    check(!preview.scene(100000).overview && preview.navigation().guidance(100000).mode==motonav::Live,"live guidance selects live camera");
    feed(preview,frames[4],103000);
    check(preview.navigation().guidance(104001).mode!=motonav::Live && preview.scene(104001).overview,"plan ACK does not renew live stream");
    check(feed(preview,frames[4],104001)=="A,5,OK\n","plan duplicate ACK remains idempotent");
    feed(preview,frames[5],104002);feed(preview,frames[6],104003);
    check(preview.scene(104003).waiting_reason==1 && preview.scene(104003).revision==1,"weak GPS returns to complete plan, independent of live window revision");
    feed(preview,frames[7],104004);check(preview.scene(104004).count==0,"stop clears plan and live window");
    feed(preview,frames[8],104005);feed(preview,frames[9],104006);
    check(preview.scene(104006).count==2 && preview.scene(104006).overview,"new journey receives new plan");
    feed(preview,frames[10],104007);check(preview.scene(104007).count==0,"arrival clears plan");
    for(size_t i=4;i<frames[1].size();i++) {
        motonav::RouteReceiver invalid;feed(invalid,frames[0],1);
        auto bytes=frames[1];bytes[i]^=1;
        check(feed(invalid,bytes,2)=="E,NAV4\n" && invalid.scene(2).count==0,"corrupt plan cannot publish partial geometry");
    }
    for(size_t field:{size_t(9),size_t(13),size_t(14),size_t(18),size_t(20)}) {
        motonav::RouteReceiver invalid;feed(invalid,frames[0],1);
        auto bytes=frames[1];bytes[field]=field==18?255:127;crc(bytes);
        check(feed(invalid,bytes,2)=="E,NAV4\n","plan session, reason, count, revision and bounds validated");
    }
    motonav::RouteReceiver collision;feed(collision,frames[0],1);feed(collision,frames[1],1);
    auto changed=frames[4];changed[19]^=1;crc(changed);
    check(feed(collision,changed,2)=="E,NAV4\n","same plan revision cannot change geometry");
    auto legacy=frames[0];legacy[4]=1;crc(legacy);
    motonav::RouteReceiver unnegotiated;feed(unnegotiated,legacy,1);
    check(feed(unnegotiated,frames[1],2)=="E,NAV4\n","legacy session cannot accept unnegotiated plan frames");
    preview.disconnect();check(preview.scene(104008).count==0,"disconnect removes plan");
    std::cout<<"NAV4 receiver: "<<checks<<" cross-language, corruption, expiry and session checks passed\n";
}
