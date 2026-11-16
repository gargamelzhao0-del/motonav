#include "motonav/diagnostics/protocol.h"
#include <cstdio>
#include <inttypes.h>

namespace motonav::diagnostics {
size_t format_json(const Snapshot &s, uint64_t device_ms, uint32_t dropped, uint32_t rejected, char *out, size_t capacity) {
    if (!out || !capacity) return 0;
    const char *reasons[] = {"none","gps_weak","map_fix_stale","map_fix_stale_sdk_live",
        "instruction_missing","instruction_stale","awaiting_new_position"};
    const char *wire_reasons[] = {"none","source_waiting","transport_fix_age","transport_instruction_age",
        "transport_both_ages","invalid_input"};
    const char *phases[] = {"idle","locating","planning","ready","starting","navigating"};
    if (s.reason > 6 || s.wire_reason > 5 || s.phase > 5) return 0;
    int n = std::snprintf(out, capacity,
        "{\"schema\":1,\"session\":%" PRIu32 ",\"seq\":%" PRIu32 ",\"device_ms\":%" PRIu64
        ",\"phone_ms\":%" PRIu32 ",\"nav_tx_ms\":%" PRIu32 ",\"nav_seq\":%" PRIu32 ",\"ack_seq\":%" PRIu32
        ",\"ack_latency_ms\":%" PRIu32 ",\"frame_fix_age_ms\":%" PRIu32 ",\"frame_instruction_age_ms\":%" PRIu32
        ",\"trusted_fix_age_ms\":%" PRIu32 ",\"map_fix_age_ms\":%" PRIu32 ",\"sdk_fix_age_ms\":%" PRIu32
        ",\"instruction_callback_age_ms\":%" PRIu32 ",\"accuracy_dm\":%" PRIu32 ",\"phone_dropped\":%" PRIu32
        ",\"markers\":%" PRIu32 ",\"app_version\":%" PRIu32 ",\"flags\":%u,\"phase\":\"%s\",\"reason\":\"%s\""
        ",\"phone_mode_at_tx\":%u,\"wire_mode\":%u,\"observed_mode\":%u,\"protocol\":%u,\"source_points\":%u,\"wire_points\":%u"
        ",\"wire_reason\":\"%s\",\"receiver_dropped\":%" PRIu32 ",\"receiver_rejected\":%" PRIu32 "}",
        s.session,s.sequence,device_ms,s.phone_ms,s.nav_tx_ms,s.nav_sequence,s.ack_sequence,s.ack_latency_ms,
        s.frame_fix_age_ms,s.frame_instruction_age_ms,s.trusted_fix_age_ms,s.map_fix_age_ms,s.sdk_fix_age_ms,
        s.instruction_callback_age_ms,s.accuracy_dm,s.dropped,s.markers,s.app_version,unsigned(s.flags),phases[s.phase],reasons[s.reason],
        unsigned(s.phone_mode),unsigned(s.wire_mode),unsigned(s.observed_mode),unsigned(s.protocol),unsigned(s.source_points),
        unsigned(s.wire_points),wire_reasons[s.wire_reason],dropped,rejected);
    if (n < 0 || size_t(n) >= capacity) { out[0] = 0; return 0; }
    return size_t(n);
}
} // namespace motonav::diagnostics
