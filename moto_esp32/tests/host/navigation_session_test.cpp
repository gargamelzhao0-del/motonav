#include "motonav/core/navigation_session.h"
#include <cassert>
#include <iostream>
#include <string>
#include <vector>

using namespace motonav;
static std::string frame(const std::string &body) {
    char suffix[12];
    std::snprintf(suffix, sizeof(suffix), ",%08X\n", NavigationReceiver::crc32(body.data(), body.size()));
    return body + suffix;
}
static std::vector<std::string> send(NavigationSession &session, const std::string &bytes, uint64_t now) {
    std::vector<std::string> replies;
    for (char byte : bytes) {
        char reply[32]{};
        if (session.feed(byte, now, reply)) replies.emplace_back(reply);
    }
    return replies;
}
int main() {
    const auto hello = frame("H,3,1,123");
    const auto live = frame("N,3,2,123,2,3,250,12000,1000,50,0,0");
    NavigationSession session;
    assert(!session.can_receive());
    assert(send(session, hello, 0).empty());
    session.connected();
    session.subscription(true);
    assert(send(session, hello, 1).empty()); // CCC alone must not admit navigation.
    session.pairing(123);
    auto state = session.snapshot(1);
    assert(screen_mode(state) == ScreenMode::Pairing && state.passkey == 123);
    session.security(true);
    assert(session.can_receive());
    assert(!session.snapshot(2).pairing && session.snapshot(2).passkey == 0);

    // Transport fragments may split anywhere; more than one frame may arrive
    // in a write. No BLE/RTOS/UI implementation participates in this test.
    for (size_t split = 0; split <= hello.size(); ++split) {
        session.subscription(false); session.subscription(true);
        auto first = send(session, hello.substr(0, split), 10);
        auto rest = send(session, hello.substr(split) + live, 10);
        first.insert(first.end(), rest.begin(), rest.end());
        assert((first == std::vector<std::string>{"A,1,NAV3\n", "A,2,OK\n"}));
        assert(screen_mode(session.snapshot(10)) == ScreenMode::Live);
    }
    assert(send(session, live, 4000).front() == "A,2,OK\n");
    assert(screen_mode(session.snapshot(4011)) == ScreenMode::Stale); // Duplicate does not refresh.

    session.security(false);
    assert(!session.snapshot(4012).handshaken && !session.can_receive());
    assert(session.snapshot(4012).guidance.mode != Live);
    session.security(true);
    assert(send(session, live, 4013).front() == "E,NAV3\n"); // Fresh handshake required.
    assert(send(session, hello, 4014).front() == "A,1,NAV3\n");
    assert(send(session, frame("N,3,2,123,2,3,250,12000,1000,50,14900,0"), 4015).front() == "A,2,OK\n");
    assert(screen_mode(session.snapshot(4116)) == ScreenMode::Stale); // Source age still enforced.

    // A partial line cannot cross an unsubscribe or physical reconnect.
    session.subscription(false); session.subscription(true);
    send(session, hello.substr(0, 5), 5000);
    session.subscription(false); session.subscription(true);
    assert(send(session, hello.substr(5), 5001).front() == "E,NAV3\n");
    assert(send(session, hello, 5002).front() == "A,1,NAV3\n");
    send(session, live.substr(0, 9), 5003);
    session.disconnected();
    assert(screen_mode(session.snapshot(5004)) == ScreenMode::Disconnected);
    session.connected(); session.subscription(true); session.security(true);
    assert(send(session, live.substr(9), 5005).front() == "E,NAV3\n");
    assert(send(session, hello + live, 5006).size() == 2);
    assert(screen_mode(session.snapshot(5006)) == ScreenMode::Live);
    session.pairing(765432);
    assert(!session.can_receive() && !session.snapshot(5007).handshaken);
    assert(screen_mode(session.snapshot(5007)) == ScreenMode::Pairing);
    std::cout << "PASS: session fragments, pairing/security, unsubscribe/reconnect and freshness\n";
}
