package com.motonav.companion;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounded diagnostic schema. Coordinates, keys, addresses and arbitrary messages are not fields. */
public final class DiagnosticRecord {
    public static final Set<String> EVENTS = new HashSet<>(Arrays.asList(
        "session_start", "session_stop", "user_mark", "environment", "lifecycle", "location_start",
        "location_stop", "location_callback", "location_decision", "location_state", "location_retry",
        "location_exception", "route_action", "route_result", "search_result", "navigation_state", "ble_state", "java_crash"));
    public static final Set<String> TEXT = new HashSet<>(Arrays.asList(
        "owner", "reason", "state", "error_class", "manufacturer", "model", "version", "phase", "action"));
    public static final Set<String> NUMBERS = new HashSet<>(Arrays.asList(
        "api", "version_code", "error", "source", "accuracy_m", "measurement_age_ms", "elapsed_age_ms",
        "wall_age_ms", "callback_gap_ms", "observation_age_ms", "navigation_age_ms", "generation",
        "satellites", "gps_status", "trusted_level", "count", "route_age_ms", "moved_m", "icon",
        "fix_age_ms", "dropped", "queue", "line"));
    public static final Set<String> FLAGS = new HashSet<>(Arrays.asList(
        "fine", "coarse", "system_location", "foreground", "consent", "map_ready", "requested", "accepted",
        "eligible", "mock", "network", "wifi", "cellular", "vpn", "battery_saver", "idle",
        "background_exempt", "navigating", "gps_weak", "destination", "selected", "route_valid"));
    private DiagnosticRecord() {}

    public static String line(String session, long sequence, long elapsed, String event, Object... fields) {
        if (!session.matches("[0-9a-f]{32}") || sequence <= 0 || elapsed < 0 || !EVENTS.contains(event)
                || fields.length % 2 != 0 || fields.length > 60) throw new IllegalArgumentException("diagnostic_schema");
        StringBuilder out = new StringBuilder("{\"schema\":1,\"session\":\"").append(session)
            .append("\",\"seq\":").append(sequence).append(",\"at_ms\":").append(elapsed)
            .append(",\"event\":\"").append(event).append('"');
        Set<String> used = new HashSet<>();
        for (int i = 0; i < fields.length; i += 2) {
            if (!(fields[i] instanceof String)) throw new IllegalArgumentException("diagnostic_field");
            String key = (String) fields[i]; Object value = fields[i + 1]; String encoded;
            if (!used.add(key)) throw new IllegalArgumentException("duplicate_field");
            if (TEXT.contains(key) && value instanceof String) {
                String safe = ((String) value).replaceAll("[^A-Za-z0-9_.$ -]", "?");
                encoded = "\"" + safe.substring(0, Math.min(80, safe.length())) + "\"";
            } else if (NUMBERS.contains(key) && value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number) || Math.abs(number) > 1e15) number = -1;
                encoded = Double.toString(number);
            } else if (FLAGS.contains(key) && value instanceof Boolean) encoded = value.toString();
            else throw new IllegalArgumentException("diagnostic_field");
            out.append(",\"").append(key).append("\":").append(encoded);
        }
        return out.append('}').toString();
    }

    public static long age(long now, long value) {
        return value > 0 && now >= value ? (now - value) / 1_000_000L : -1;
    }
}
