#pragma once

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <climits>

namespace motonav {

enum Mode { Waiting = 1, Live = 2, Arrived = 3, Stopped = 4 };

struct Guidance {
    Mode mode = Waiting;
    int icon = 0;
    int turn_m = -1;
    int remaining_m = -1;
    int remaining_s = -1;
    int speed_kmh = -1;
};

struct ReceiverStats {
    uint32_t accepted = 0, duplicates = 0, rejected = 0;
};

// No allocation or graphics dependencies. Used by both the firmware and host tests.
class NavigationReceiver {
public:
    static constexpr unsigned MaxAgeMs = 15000;
    static constexpr unsigned LinkTimeoutMs = 4000;

    static uint32_t crc32(const char *text, size_t length) {
        uint32_t crc = 0xffffffffu;
        for (size_t i = 0; i < length; ++i) {
            crc ^= static_cast<uint8_t>(text[i]);
            for (int bit = 0; bit < 8; ++bit)
                crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
        }
        return ~crc;
    }

    // Each complete line produces a bounded acknowledgement. Duplicates do not renew freshness.
    bool feed(char byte, uint64_t now_ms, char (&reply)[32]) {
        if (byte != '\n') {
            if (byte < 32 || byte > 126 || used_ >= sizeof(buffer_) - 1) dropping_ = true;
            if (!dropping_) buffer_[used_++] = byte;
            return false;
        }
        buffer_[used_] = '\0';
        bool ok = !dropping_ && parse(now_ms, reply);
        if (!ok) { ++stats_.rejected; std::snprintf(reply, sizeof(reply), "E,NAV3\n"); }
        used_ = 0;
        dropping_ = false;
        return true;
    }

    void disconnect() {
        handshaken_ = false;
        used_ = 0; dropping_ = false; sequence_ = 0;
        if (guidance_.mode == Live) guidance_ = Guidance{};
    }

    bool connected(uint64_t now_ms) const {
        return handshaken_ && now_ms >= received_at_ && now_ms - received_at_ <= LinkTimeoutMs;
    }

    bool handshaken() const { return handshaken_; }
    bool has_guidance() const { return guidance_received_; }
    bool source_expired(uint64_t now_ms) const {
        return guidance_.mode == Live && (now_ms < received_at_ || now_ms - received_at_ > validity_ms_);
    }
    ReceiverStats stats() const { return stats_; }

    Guidance guidance(uint64_t now_ms) const {
        if (guidance_.mode == Live && (!connected(now_ms) || now_ms - received_at_ > validity_ms_)) return Guidance{};
        return guidance_;
    }

private:
    char buffer_[95]{};
    size_t used_ = 0;
    bool dropping_ = false, handshaken_ = false, guidance_received_ = false;
    int sequence_ = 0, session_ = 0;
    uint32_t last_crc_ = 0;
    uint64_t received_at_ = 0;
    unsigned validity_ms_ = 0;
    Guidance guidance_{};
    ReceiverStats stats_{};

    static bool number(const char *text, int min, int max, int &value) {
        if (!text || !*text) return false;
        const char *digits = *text == '-' ? text + 1 : text;
        if (!*digits) return false;
        for (const char *p = digits; *p; ++p) if (*p < '0' || *p > '9') return false;
        errno = 0;
        char *end = nullptr;
        long parsed = std::strtol(text, &end, 10);
        if (errno || *end || parsed < min || parsed > max) return false;
        value = static_cast<int>(parsed);
        return true;
    }

    bool parse(uint64_t now_ms, char (&reply)[32]) {
        char *checksum = std::strrchr(buffer_, ',');
        if (!checksum || std::strlen(checksum + 1) != 8) return false;
        uint32_t supplied = 0;
        for (const char *p = checksum + 1; *p; ++p) {
            unsigned digit;
            if (*p >= '0' && *p <= '9') digit = static_cast<unsigned>(*p - '0');
            else if (*p >= 'A' && *p <= 'F') digit = static_cast<unsigned>(*p - 'A' + 10);
            else return false;
            supplied = (supplied << 4) | digit;
        }
        if (crc32(buffer_, static_cast<size_t>(checksum - buffer_)) != supplied) return false;
        *checksum = '\0';
        char *fields[12] = {buffer_};
        size_t count = 1;
        for (char *p = buffer_; *p; ++p) {
            if (*p == ',') {
                if (count == 12) return false;
                *p = '\0'; fields[count++] = p + 1;
            }
        }
        bool hello = std::strcmp(fields[0], "H") == 0;
        if ((hello && count != 4) || (!hello && (std::strcmp(fields[0], "N") != 0 || count != 12))) return false;
        int seq, session;
        if (std::strcmp(fields[1], "3") || !number(fields[2], 1, INT_MAX, seq) || !number(fields[3], 1, INT_MAX, session)) return false;
        if (handshaken_ && seq == sequence_ && session == session_ && supplied == last_crc_) {
            ++stats_.duplicates;
            std::snprintf(reply, sizeof(reply), "A,%d,%s\n", seq, hello ? "NAV3" : "OK");
            return true;
        }
        if (hello) {
            if (handshaken_ || seq != 1) return false;
            session_ = session;
            guidance_ = Guidance{};
            guidance_received_ = false;
            handshaken_ = true;
        } else {
            if (!handshaken_ || session != session_ || seq <= sequence_) return false;
            int mode, icon, turn, distance, seconds, speed, fix_age, instruction_age;
            if (!number(fields[4], Waiting, Stopped, mode) || !number(fields[5], 0, 255, icon)
                    || !number(fields[6], -1, 50000000, turn) || !number(fields[7], -1, 50000000, distance)
                    || !number(fields[8], -1, 31536000, seconds) || !number(fields[9], -1, 400, speed)
                    || !number(fields[10], 0, MaxAgeMs, fix_age) || !number(fields[11], 0, MaxAgeMs, instruction_age)) return false;
            if (mode == Live) {
                if (turn < 0 || distance < 0 || seconds < 0) return false;
                guidance_ = {Live, icon, turn, distance, seconds, speed};
                validity_ms_ = MaxAgeMs - static_cast<unsigned>(fix_age > instruction_age ? fix_age : instruction_age);
            } else {
                if (icon != 0 || turn != -1 || distance != -1 || seconds != -1 || speed != -1 || fix_age != 0 || instruction_age != 0) return false;
                guidance_ = Guidance{}; guidance_.mode = static_cast<Mode>(mode);
            }
            guidance_received_ = true;
        }
        ++stats_.accepted;
        sequence_ = seq; last_crc_ = supplied; received_at_ = now_ms;
        std::snprintf(reply, sizeof(reply), "A,%d,%s\n", seq, hello ? "NAV3" : "OK");
        return true;
    }
};

} // namespace motonav
