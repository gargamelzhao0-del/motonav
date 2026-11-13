#pragma once
#include "motonav/core/display_model.h"
#include "motonav/core/route_protocol.h"

namespace motonav {
// Protocol state is independent of BLE, RTOS and LVGL. The owner serializes
// access and supplies monotonic milliseconds. Notifications never call UI code.
class NavigationSession {
public:
    void connected() { reset_receivers(); protocol_=3; link_ = {}; link_.linked = true; }
    void disconnected() { reset_receivers(); link_ = {}; }
    void protocol(unsigned version) { reset_receivers(); protocol_=version==4?4:3; }
    void subscription(bool enabled) {
        link_.subscribed = enabled;
        if (!enabled) reset_receivers();
    }
    void pairing(uint32_t pin) {
        link_.pairing = true; link_.passkey = pin; link_.encrypted = false;
        reset_receivers();
    }
    void security(bool secure) {
        link_.pairing = false; link_.passkey = 0; link_.encrypted = secure;
        if (!secure) reset_receivers();
    }
    bool can_receive() const { return link_.linked && link_.encrypted && link_.subscribed && !link_.pairing; }
    bool feed(char byte, uint64_t now_ms, char (&reply)[32]) {
        return can_receive() && (protocol_==4?route_receiver_.feed(static_cast<uint8_t>(byte),now_ms,reply):receiver_.feed(byte,now_ms,reply));
    }
    ScreenSnapshot snapshot(uint64_t now_ms) const {
        auto result = link_;
        result.protocol = protocol_;
        const auto &receiver=protocol_==4?route_receiver_.navigation():receiver_;
        result.guidance = receiver.guidance(now_ms);
        result.handshaken = receiver.handshaken();
        result.has_guidance = receiver.has_guidance();
        result.stream_fresh = receiver.connected(now_ms);
        result.source_expired = receiver.source_expired(now_ms);
        result.packets = protocol_==4?route_receiver_.stats():receiver.stats();
        if(protocol_==4)result.route=route_receiver_.scene(now_ms);
        return result;
    }
private:
    void reset_receivers() { receiver_.disconnect(); route_receiver_.disconnect(); }
    unsigned protocol_=3;
    RouteReceiver route_receiver_;
    NavigationReceiver receiver_;
    ScreenSnapshot link_;
};
} // namespace motonav
