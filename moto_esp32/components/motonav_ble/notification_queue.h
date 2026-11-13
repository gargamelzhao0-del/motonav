#pragma once
#include "motonav/drivers/ble_transport.h"
#include <algorithm>
#include <cstring>

namespace motonav::drivers {
// BLE-host-owned bounded byte ring. Preflight guarantees atomic batch enqueue.
class NotificationQueue {
public:
    static constexpr size_t Capacity = 2048;
    enum class Result { Ok, Invalid, Full };
    Result check(const BleNotification *messages, size_t count) const {
        if (!messages || !count || count > BleNotificationBatchLimit) return Result::Invalid;
        size_t bytes = 0;
        for (size_t i = 0; i < count; ++i) {
            if (!messages[i].data || !messages[i].length || messages[i].length > BleNotificationMaxBytes)
                return Result::Invalid;
            if (messages[i].length > Capacity - bytes) return Result::Full;
            bytes += messages[i].length;
        }
        return count <= BleNotificationBatchLimit - count_ && bytes <= Capacity - used_
            ? Result::Ok : Result::Full;
    }
    Result enqueue(const BleNotification *messages, size_t count) {
        const auto result = check(messages, count);
        if (result != Result::Ok) return result;
        for (size_t i = 0; i < count; ++i) {
            const auto &message = messages[i];
            const size_t first = std::min(message.length, Capacity - tail_);
            std::memcpy(bytes_ + tail_, message.data, first);
            std::memcpy(bytes_, message.data + first, message.length - first);
            tail_ = (tail_ + message.length) % Capacity;
            sizes_[(head_packet_ + count_) % BleNotificationBatchLimit] = message.length;
            used_ += message.length;
            ++count_;
        }
        return Result::Ok;
    }
    size_t front(uint8_t *out, size_t capacity) const {
        if (!count_) return 0;
        const size_t bytes = sizes_[head_packet_];
        if (!out || capacity < bytes) return 0;
        const size_t first = std::min(bytes, Capacity - head_);
        std::memcpy(out, bytes_ + head_, first);
        std::memcpy(out + first, bytes_, bytes - first);
        return bytes;
    }
    void pop() {
        if (!count_) return;
        const auto bytes = sizes_[head_packet_];
        head_ = (head_ + bytes) % Capacity;
        used_ -= bytes;
        head_packet_ = (head_packet_ + 1) % BleNotificationBatchLimit;
        --count_;
    }
    bool empty() const { return count_ == 0; }
    void clear() { head_ = tail_ = head_packet_ = count_ = used_ = 0; }
private:
    uint8_t bytes_[Capacity]{};
    uint16_t sizes_[BleNotificationBatchLimit]{};
    size_t head_ = 0, tail_ = 0, head_packet_ = 0, count_ = 0, used_ = 0;
};
} // namespace motonav::drivers
