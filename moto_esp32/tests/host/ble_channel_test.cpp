#include "../../components/motonav_ble/channel_state.h"
#include <cassert>
#include <cstdio>

int main() {
    motonav::drivers::BleChannelState channels;
    assert(!channels.select(3) && !channels.select(4));
    // Reproduce Android's bonded CCCD restore: old NAV3 appears first.
    channels.subscription(3, true);
    channels.subscription(4, true);
    assert(channels.active() == 0);
    assert(channels.select(4) && channels.active() == 4);
    assert(channels.select(4)); // A repeated CCCD enable must not reset a session.
    assert(!channels.select(3)); // Cannot mix protocol bytes in one receiver.
    assert(!channels.subscription(3, false) && channels.active() == 4);
    assert(channels.subscription(4, false) && channels.active() == 0);
    assert(!channels.select(4));
    channels.subscription(3, true);
    assert(channels.select(3) && channels.active() == 3);
    channels.subscription(4, true);
    assert(!channels.select(4)); // New subscription does not steal an active stream.
    channels.reset();
    assert(!channels.select(3) && !channels.select(4));
    channels.subscription(4, true);
    channels.subscription(3, true);
    assert(channels.select(3)); // Also works with the reverse restore order.
    channels.reset();
    channels.subscription(0, true);
    channels.subscription(5, true);
    assert(!channels.select(0) && !channels.select(5));
    std::puts("PASS: bonded CCCD restore, NAV4 selection, channel isolation and reconnect");
}
