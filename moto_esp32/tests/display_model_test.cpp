#include "../main/display_model.h"
#include "../main/strip_stream.h"
#include <cassert>
#include <iostream>
#include <random>
#include <string>

using namespace motonav;
static std::string frame(const std::string &body) {
    char checksum[12];
    std::snprintf(checksum, sizeof(checksum), ",%08X\n", NavigationReceiver::crc32(body.data(), body.size()));
    return body + checksum;
}
static std::string send(NavigationReceiver &r, const std::string &wire, uint64_t time) {
    char reply[32]{};
    for (char byte : wire) r.feed(byte, time, reply);
    return reply;
}
int main() {
    StripStream stream;
    assert(stream.begin(0, 0, 466, 50) == StripStream::Start); stream.submitted(50);
    assert(stream.begin(0, 50, 466, 100) == StripStream::Continue); stream.submitted(100);
    assert(stream.begin(20, 100, 40, 120) == StripStream::Start);
    assert(stream.begin(0, 100, 466, 150) == StripStream::Start); stream.submitted(150);
    assert(stream.begin(0, 200, 466, 250) == StripStream::Start); // Gap is not a continuation.
    assert(stream.begin(0, 250, 466, 300) == StripStream::Start); // Previous write failed.
    stream.submitted(300);
    assert(stream.begin(0, 0, 466, 50) == StripStream::Start); // New frame resets the window.
    stream.submitted(466);
    assert(stream.begin(0, 0, 466, 50) == StripStream::Start);
    assert(stream.begin(72, 100, 392, 172) == StripStream::Start); stream.submitted(172);
    assert(stream.begin(72, 172, 392, 244) == StripStream::Continue); stream.submitted(244);
    assert(stream.begin(74, 244, 392, 300) == StripStream::Start); // Changed columns.
    assert(stream.begin(72, 100, 392, 172) == StripStream::Start); // Failed submission.
    stream.submitted(173); // Incorrect completion cannot authorize continuation.
    assert(stream.begin(72, 172, 392, 244) == StripStream::Start); stream.submitted(244);
    assert(stream.begin(-2, 244, 392, 300) == StripStream::Partial); stream.submitted(300);
    assert(stream.begin(72, 300, 392, 400) == StripStream::Start); stream.submitted(400);
    assert(stream.begin(72, 100, 392, 172) == StripStream::Start); // New map frame.

    ScreenSnapshot s;
    assert(screen_mode(s) == ScreenMode::Disconnected);
    s.linked = true; assert(screen_mode(s) == ScreenMode::Securing);
    s.pairing = true; s.passkey = 123;
    assert(screen_mode(s) == ScreenMode::Pairing && !may_test_display(s));
    s.pairing = false; s.encrypted = true;
    assert(screen_mode(s) == ScreenMode::Handshake);
    s.subscribed = s.handshaken = true;
    assert(screen_mode(s) == ScreenMode::Ready); // No GPS fault before the phone starts a route.
    s.has_guidance = s.stream_fresh = true;
    assert(screen_mode(s) == ScreenMode::Waiting);
    s.guidance = {Live, 3, 250, 12000, 1000, 50};
    assert(screen_mode(s) == ScreenMode::Live && !may_test_display(s));
    s.source_expired = true; assert(screen_mode(s) == ScreenMode::Stale);
    s.source_expired = false; s.stream_fresh = false; assert(screen_mode(s) == ScreenMode::Stale);
    s.linked = false; assert(screen_mode(s) == ScreenMode::Disconnected);
    s.guidance.mode = Arrived; assert(screen_mode(s) == ScreenMode::Arrived);
    s.pairing = true; assert(screen_mode(s) == ScreenMode::Pairing);
    s.pairing = false; s.guidance.mode = Stopped; assert(screen_mode(s) == ScreenMode::Stopped);

    char text[24];
    format_distance(text, -1); assert(std::string(text) == "--");
    format_distance(text, 999); assert(std::string(text) == "999 m");
    format_distance(text, 1000); assert(std::string(text) == "1.0 km");
    format_distance(text, 9999); assert(std::string(text) == "9.9 km");
    format_distance(text, 50000000); assert(std::string(text) == "50000 km");
    for (int icon = 0; icon <= 255; ++icon) {
        ArrowShape shape = arrow_shape(icon);
        assert(shape.count <= 6);
        for (unsigned i = 0; i < shape.count; ++i) {
            assert(shape.points[i].x >= 0 && shape.points[i].x <= 160);
            assert(shape.points[i].y >= 0 && shape.points[i].y <= 140);
        }
    }
    assert(arrow_shape(2).points[2].x < 80 && arrow_shape(3).points[2].x > 80);
    assert(arrow_shape(4).points[2].x < 80 && arrow_shape(5).points[2].x > 80);
    assert(arrow_shape(6).points[2].x < 80 && arrow_shape(7).points[2].x > 80);
    assert(arrow_shape(65).points[2].x < 80 && arrow_shape(66).points[2].x > 80);
    assert(arrow_shape(9).points[1].y < arrow_shape(9).points[0].y);
    assert(arrow_shape(47).count == 0 && std::string(maneuver(47)) == "CHECK PHONE MAP");

    NavigationReceiver r;
    assert(send(r, frame("H,3,1,123"), 100) == "A,1,NAV3\n");
    assert(r.handshaken() && !r.has_guidance());
    std::string nav = frame("N,3,2,123,2,3,250,12000,1000,50,14900,0");
    assert(send(r, nav, 200) == "A,2,OK\n");
    assert(r.has_guidance() && r.guidance(300).mode == Live);
    assert(r.source_expired(301) && r.guidance(301).mode == Waiting);
    assert(send(r, nav, 302) == "A,2,OK\n" && r.source_expired(302));
    assert(r.stats().accepted == 2 && r.stats().duplicates == 1);
    auto live = frame("N,3,3,123,2,2,20,500,60,-1,0,0");
    char reply[32]{};
    for (size_t i = 0; i < live.size() - 1; ++i) assert(!r.feed(live[i], 500, reply));
    assert(r.guidance(500).mode == Waiting); // A partial packet cannot update displayed guidance.
    assert(r.feed('\n', 500, reply) && r.guidance(500).icon == 2);
    std::mt19937 random(3405);
    for (int trial = 0; trial < 10000; ++trial) {
        std::string garbage(1 + random() % 200, '?');
        for (char &c : garbage) c = static_cast<char>(32 + random() % 95);
        assert(send(r, garbage + "\n", 501) == "E,NAV3\n");
        assert(r.guidance(501).icon == 2);
    }
    assert(r.stats().rejected == 10000 && r.stats().accepted == 3);
    assert(r.guidance(4501).mode == Waiting && !r.connected(4501));
    r.disconnect(); assert(!r.handshaken() && r.guidance(4502).mode == Waiting);
    assert(send(r, frame("H,3,1,456"), 5000) == "A,1,NAV3\n" && !r.has_guidance());
    assert(send(r, live, 5001) == "E,NAV3\n"); // Previous-session frames stay rejected.
    std::cout << "PASS: display states, stale guidance, arrow direction, formatting, counters and 10000 malformed frames\n";
}
