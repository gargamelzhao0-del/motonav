#pragma once
#include "motonav/core/display_model.h"

namespace motonav {
// Protocol state is independent of BLE, RTOS and LVGL. The owner serializes
// access and supplies monotonic milliseconds. Notifications never call UI code.
class NavigationSession {
public:
    void connected() { receiver_.disconnect(); link_ = {}; link_.linked = true; }
    void disconnected() { receiver_.disconnect(); link_ = {}; }
    void subscription(bool enabled) {
        link_.subscribed = enabled;
        if (!enabled) receiver_.disconnect();
    }
    void pairing(uint32_t pin) {
        link_.pairing = true; link_.passkey = pin; link_.encrypted = false;
        receiver_.disconnect();
    }
    void security(bool secure) {
        link_.pairing = false; link_.passkey = 0; link_.encrypted = secure;
        if (!secure) receiver_.disconnect();
    }
    bool can_receive() const { return link_.linked && link_.encrypted && link_.subscribed && !link_.pairing; }
    bool feed(char byte, uint64_t now_ms, char (&reply)[32]) {
        return can_receive() && receiver_.feed(byte, now_ms, reply);
    }
    ScreenSnapshot snapshot(uint64_t now_ms) const {
        auto result = link_;
        result.guidance = receiver_.guidance(now_ms);
        result.handshaken = receiver_.handshaken();
        result.has_guidance = receiver_.has_guidance();
        result.stream_fresh = receiver_.connected(now_ms);
        result.source_expired = receiver_.source_expired(now_ms);
        result.packets = receiver_.stats();
        return result;
    }
private:
    NavigationReceiver receiver_;
    ScreenSnapshot link_;
};
} // namespace motonav
