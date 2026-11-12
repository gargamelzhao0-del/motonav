#include "../../components/motonav_core/include/motonav/core/navigation_protocol.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <string>

static std::string wire(const std::string &body) {
    char crc[12];
    std::snprintf(crc, sizeof(crc), ",%08X\n", motonav::NavigationReceiver::crc32(body.data(), body.size()));
    return body + crc;
}

static std::string send(motonav::NavigationReceiver &receiver, const std::string &frame, uint64_t time) {
    char reply[32]{};
    bool received = false;
    for (char byte : frame) received = receiver.feed(byte, time, reply) || received;
    assert(received);
    return reply;
}

int main(int argc, char **argv) {
    assert(argc == 2);
    std::ifstream fixture(argv[1]);
    assert(fixture);
    std::string hello, live, waiting, arrived;
    std::getline(fixture, hello); std::getline(fixture, live); std::getline(fixture, waiting); std::getline(fixture, arrived);
    motonav::NavigationReceiver receiver;
    assert(send(receiver, live + "\n", 100) == "E,NAV3\n");
    assert(send(receiver, hello + "\n", 100) == "A,1,NAV3\n");
    assert(receiver.guidance(100).mode == motonav::Waiting);
    assert(send(receiver, live + "\n", 200) == "A,2,OK\n");
    assert(receiver.guidance(200).mode == motonav::Live);
    assert(receiver.guidance(200).turn_m == 250);
    assert(receiver.guidance(200).speed_kmh == 50);
    assert(send(receiver, live + "\n", 4000) == "A,2,OK\n");
    assert(receiver.guidance(4201).mode == motonav::Waiting); // duplicate did not renew the timeout
    assert(send(receiver, waiting + "\n", 5000) == "A,3,OK\n");
    assert(receiver.guidance(5000).turn_m == -1);
    assert(send(receiver, live + "\n", 5001) == "E,NAV3\n"); // older sequence
    assert(send(receiver, arrived + "\n", 6000) == "A,4,OK\n");
    receiver.disconnect();
    assert(receiver.guidance(7000).mode == motonav::Arrived);
    assert(send(receiver, live + "\n", 7100) == "E,NAV3\n");
    assert(send(receiver, hello + "\n", 7200) == "A,1,NAV3\n");
    assert(receiver.guidance(7200).mode == motonav::Waiting); // reconnect wipes old guidance
    assert(send(receiver, wire("N,3,2,999,2,3,1,2,3,4,0,0"), 7300) == "E,NAV3\n");
    assert(send(receiver, wire("N,3,2,123,2,3,1,2,3,4,15000,14000"), 8000) == "A,2,OK\n");
    assert(receiver.guidance(8001).mode == motonav::Waiting); // link is healthy but source is expired
    assert(send(receiver, wire("N,3,3,123,2,3,-1,2,3,4,0,0"), 8100) == "E,NAV3\n");
    assert(send(receiver, wire("N,3,3,123,1,3,1,2,3,4,0,0"), 8200) == "E,NAV3\n");
    assert(send(receiver, wire("N,3,3,123,2,3,1,2,3,4,15001,0"), 8300) == "E,NAV3\n");
    assert(send(receiver, wire("N,3,99999999999999999999999,123,1,0,-1,-1,-1,-1,0,0"), 8400) == "E,NAV3\n");
    std::string corrupt = live + "\n"; corrupt[2] = '1';
    assert(send(receiver, corrupt, 8500) == "E,NAV3\n");
    assert(send(receiver, std::string(200, 'N') + "\n", 8600) == "E,NAV3\n");
    assert(send(receiver, wire("N,3,3,123,2,3,1,2,3,4,0,0"), 8700) == "A,3,OK\n");
    receiver.disconnect();
    assert(receiver.guidance(8701).mode == motonav::Waiting);
    std::cout << "PASS: Java-to-firmware frames, CRC, session isolation, stale data, reconnect and malformed input\n";
}
