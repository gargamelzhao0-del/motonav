#include "notification_queue.h"
#include <cassert>
#include <deque>
#include <iostream>
#include <string>
#include <vector>
using namespace motonav::drivers;
int main() {
    NotificationQueue queue;
    using Result = NotificationQueue::Result;
    uint8_t output[512];
    const std::string long_packet(512, 'A');
    BleNotification four[4];
    for (auto &p : four) p = {reinterpret_cast<const uint8_t *>(long_packet.data()), long_packet.size()};
    assert(queue.enqueue(four, 4) == Result::Ok);
    assert(queue.enqueue(four, 1) == Result::Full);
    assert(queue.front(output, 511) == 0 && queue.front(output, 512) == 512);
    queue.pop();
    assert(queue.enqueue(four, 2) == Result::Full); // Must not enqueue its first packet.
    for (unsigned i = 0; i < 3; ++i) { assert(queue.front(output, 512) == 512); queue.pop(); }
    assert(queue.empty());
    uint32_t random = 123;
    std::deque<std::string> reference;
    for (unsigned i = 0; i < 10000; ++i) {
        random = random * 1664525u + 1013904223u;
        std::string data(1 + (random % 512), static_cast<char>(random >> 24));
        const BleNotification packet{reinterpret_cast<const uint8_t *>(data.data()), data.size()};
        if (queue.enqueue(&packet, 1) == Result::Ok) reference.push_back(data);
        if (!reference.empty() && ((random >> 16) & 1)) {
            const size_t length = queue.front(output, sizeof(output));
            assert(length == reference.front().size());
            assert(std::string(reinterpret_cast<char *>(output), length) == reference.front());
            queue.pop(); reference.pop_front();
        }
    }
    while (!reference.empty()) {
        const auto length = queue.front(output, sizeof(output));
        assert(std::string(reinterpret_cast<char *>(output), length) == reference.front());
        queue.pop(); reference.pop_front();
    }
    const uint8_t a = 'a';
    BleNotification slots[32];
    for (auto &p : slots) p = {&a, 1};
    queue.clear();
    assert(queue.enqueue(slots, 32) == Result::Ok && queue.enqueue(slots, 1) == Result::Full);
    queue.clear();
    assert(queue.empty() && queue.front(output, sizeof(output)) == 0);
    const BleNotification invalid[]{ {&a, 1}, {nullptr, 1} };
    assert(queue.enqueue(invalid, 2) == Result::Invalid && queue.empty());
    assert(queue.enqueue(nullptr, 1) == Result::Invalid);
    std::cout << "PASS: bounded notification queue, atomic batches, wraparound, byte/packet pressure and reset\n";
}
