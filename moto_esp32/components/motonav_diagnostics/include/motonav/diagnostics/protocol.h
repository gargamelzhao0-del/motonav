#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace motonav::diagnostics {
constexpr size_t FrameBytes = 88;
constexpr size_t FragmentPayload = 16;
constexpr uint32_t Unknown = UINT32_MAX;
struct Snapshot {
    uint32_t session, sequence, phone_ms, nav_tx_ms, nav_sequence, ack_sequence, ack_latency_ms;
    uint32_t frame_fix_age_ms, frame_instruction_age_ms, trusted_fix_age_ms, map_fix_age_ms;
    uint32_t sdk_fix_age_ms, instruction_callback_age_ms, accuracy_dm, dropped, markers, app_version;
    uint16_t flags;
    uint8_t phase, reason, phone_mode, wire_mode, protocol, source_points, wire_points, wire_reason, observed_mode;
};
inline uint32_t read32(const uint8_t *p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
inline uint32_t crc32(const uint8_t *p, size_t size) {
    uint32_t crc = UINT32_MAX;
    for (size_t i = 0; i < size; ++i) {
        crc ^= p[i];
        for (unsigned bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}
inline bool decode(const uint8_t *p, size_t size, Snapshot &out) {
    if (!p || size != FrameBytes || p[0] != 'D' || p[1] != '1' || p[2] != FrameBytes || p[3] || p[83] ||
        crc32(p, FrameBytes - 4) != read32(p + 84)) return false;
    Snapshot s{};
    s.session = read32(p+4); s.sequence = read32(p+8); s.phone_ms = read32(p+12);
    s.nav_tx_ms = read32(p+16); s.nav_sequence = read32(p+20); s.ack_sequence = read32(p+24);
    s.ack_latency_ms = read32(p+28); s.frame_fix_age_ms = read32(p+32); s.frame_instruction_age_ms = read32(p+36);
    s.trusted_fix_age_ms = read32(p+40); s.map_fix_age_ms = read32(p+44); s.sdk_fix_age_ms = read32(p+48);
    s.instruction_callback_age_ms = read32(p+52); s.accuracy_dm = read32(p+56); s.dropped = read32(p+60);
    s.markers = read32(p+64); s.app_version = read32(p+68); s.flags = p[72] | uint16_t(p[73]) << 8;
    s.phase = p[74]; s.reason = p[75]; s.phone_mode = p[76]; s.wire_mode = p[77]; s.protocol = p[78];
    s.source_points = p[79]; s.wire_points = p[80]; s.wire_reason = p[81]; s.observed_mode = p[82];
    if (!s.session || !s.sequence || s.flags > 31 || s.phase > 5 || s.reason > 6 || s.phone_mode > 4 ||
        s.wire_mode > 4 || s.observed_mode < 1 || s.observed_mode > 4 || s.wire_reason > 5 ||
        (s.protocol != 3 && s.protocol != 4) || s.source_points > 64 || s.wire_points > 64 ||
        (s.nav_sequence == 0 && (s.phone_mode || s.wire_mode || s.source_points || s.wire_points)) ||
        (s.nav_sequence != 0 && (!s.phone_mode || !s.wire_mode)) ||
        (s.wire_mode != 2 && s.wire_points) || (s.protocol == 3 && s.wire_points)) return false;
    out = s;
    return true;
}

// DIAG1 fragments have their own sequence hint and offset. Navigation writes
// can preempt them; a fresh offset-zero fragment safely abandons an old frame.
class Assembler {
public:
    enum class Result { Incomplete, Complete, Invalid };
    void reset() { used_ = 0; started_ = 0; }
    Result feed(const uint8_t *p, size_t size, uint64_t now, Snapshot &out) {
        if (!p || size < 5 || size > 20 || p[0] != 0xd1 || p[3] == 0 || p[3] > FragmentPayload ||
            size != size_t(p[3]) + 4 || p[2] % FragmentPayload || size_t(p[2]) + p[3] > FrameBytes ||
            p[3] != (FrameBytes - p[2] < FragmentPayload ? FrameBytes - p[2] : FragmentPayload)) return invalid();
        if (p[2] == 0) { used_ = 0; hint_ = p[1]; started_ = now; }
        if (p[1] != hint_ || p[2] != used_ || now < started_ || now - started_ > 3000) return invalid();
        std::memcpy(bytes_ + used_, p + 4, p[3]); used_ += p[3];
        if (used_ != FrameBytes) return Result::Incomplete;
        reset();
        if (!decode(bytes_, sizeof(bytes_), out) || uint8_t(out.sequence) != hint_) return Result::Invalid;
        return Result::Complete;
    }
private:
    Result invalid() { reset(); return Result::Invalid; }
    uint8_t bytes_[FrameBytes]{};
    uint8_t hint_ = 0;
    size_t used_ = 0;
    uint64_t started_ = 0;
};
// Returns zero on insufficient capacity; the output contains only fixed fields.
size_t format_json(const Snapshot &sample, uint64_t device_ms, uint32_t receiver_dropped,
                   uint32_t receiver_rejected, char *out, size_t capacity);
} // namespace motonav::diagnostics
