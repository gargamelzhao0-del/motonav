#pragma once

namespace motonav::drivers {
// Bonded phones may restore both CCCDs in handle order. A restored subscription
// is not a protocol choice: the first authenticated RX write selects the channel.
class BleChannelState {
public:
    void reset() { enabled_ = active_ = 0; }
    unsigned active() const { return active_; }
    bool subscribed(unsigned channel) const { return (enabled_ & bit(channel)) != 0; }
    bool subscription(unsigned channel, bool enabled) {
        if (enabled) enabled_ |= bit(channel);
        else enabled_ &= ~bit(channel);
        if (!enabled && active_ != 0 && active_ == channel) { active_ = 0; return true; }
        return false;
    }
    bool select(unsigned channel) {
        if (!subscribed(channel) || (active_ != 0 && active_ != channel)) return false;
        active_ = channel;
        return true;
    }
private:
    static unsigned bit(unsigned channel) { return channel == 3 ? 1 : channel == 4 ? 2 : 0; }
    unsigned enabled_ = 0, active_ = 0;
};
}
