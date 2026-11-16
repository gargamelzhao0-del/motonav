# DIAG1: phone diagnostics over BLE and USB

Implemented by Android 0.7.14 and ESP32 firmware 0.3.4. The phone explicitly starts a timed diagnostic session and sends one fixed-field snapshot per second. The paired circle forwards accepted snapshots to its USB serial log as `phone_diag: {JSON}`. No internet endpoint or phone USB debugging is required. This is a diagnostic summary, not a replacement for the full local Android journal.

## Transport and ownership

- Service: `91d10001-6b5d-4f1a-b817-52e0baf8a430`.
- RX: `91d10002-6b5d-4f1a-b817-52e0baf8a430`, acknowledged writes, authenticated and encrypted. No TX or CCCD.
- NAV3/NAV4 and their ACKs are unchanged. A DIAG1 write does not select the navigation channel, update navigation state or acknowledge a navigation packet.
- Android serializes all GATT writes on the existing handler, with at most one ATT operation in flight. Navigation has priority between diagnostic fragments. Diagnostic storage is bounded to one active frame and one latest pending frame; pending snapshots older than two seconds are discarded.
- Disconnect clears both frames. Stop/expiry prevents additional diagnostic fragments; an already issued ATT operation can complete. A diagnostic ATT rejection drops that frame; a missing write callback requires reconnecting because the GATT operation cannot safely be overlapped.
- ESP32 validates/reassembles on the BLE host task and enqueues at most four snapshots without waiting. A separate priority-1 worker formats JSON and writes serial output. No diagnostic callback takes a display lock or calls LVGL. The receiver accepts at most one complete snapshot per 500 ms and rejects repeated/non-increasing sequence numbers within a session.
- Disconnect/security loss changes the receiver epoch, discarding queued old snapshots. A line already being printed may finish. ATT success for the final fragment means admission to the device queue, not proof that a PC saved the line.
- Android stop/expiry does not replay old diagnostics later. The local phone journal is retained for export. If the circle lacks the optional service, the App reports that condition and navigation remains usable.

## Fragment (4-byte header + up to 16 payload bytes)

| Offset | Size | Value |
| --- | --- | --- |
| 0 | 1 | `0xd1` |
| 1 | 1 | Low eight bits of the diagnostic sequence |
| 2 | 1 | Frame offset: 0, 16, 32, 48, 64 or 80 |
| 3 | 1 | Payload length: 16, except the final fragment is 8 |
| 4 | 1–16 | Payload |

Works with MTU 23. An offset-zero fragment starts a new frame and replaces any partial old frame. Offsets must be consecutive, hints must match, and the complete frame must arrive within three seconds. CRC and the full sequence are checked after assembly. There is no per-frame heap allocation on ESP32.

## Snapshot (88 bytes, little-endian)

| Offset | Type | Field |
| --- | --- | --- |
| 0 | 2 bytes | ASCII `D1` |
| 2 | u16 | Length, 88 |
| 4 | u32 | Diagnostic session, nonzero |
| 8 | u32 | Diagnostic sequence, nonzero |
| 12 | u32 | Phone monotonic milliseconds, low 32 bits |
| 16 | u32 | Last navigation encoding time, phone monotonic milliseconds, low 32 bits |
| 20 | u32 | Last navigation sequence; zero before the first snapshot |
| 24 | u32 | Last acknowledged navigation/HELLO sequence |
| 28 | u32 | Last navigation ACK latency in milliseconds; unknown for HELLO |
| 32 | u32 | Source fix age when the last navigation packet was encoded |
| 36 | u32 | Source instruction age at that encoding |
| 40 | u32 | Current trusted fix age |
| 44 | u32 | Current map fix age |
| 48 | u32 | Current navigation SDK fix age |
| 52 | u32 | Current instruction callback age |
| 56 | u32 | Current trusted fix accuracy in decimetres |
| 60 | u32 | Phone diagnostic discard count |
| 64 | u32 | User problem-marker counter |
| 68 | u32 | Android versionCode |
| 72 | u16 | Flags, defined below |
| 74 | u8 | Journey phase: idle=0, locating=1, planning=2, ready=3, starting=4, navigating=5 |
| 75 | u8 | Current waiting reason, defined below |
| 76 | u8 | Phone source mode at last navigation encoding; zero if no packet |
| 77 | u8 | Actual encoded navigation wire mode; zero if no packet |
| 78 | u8 | Selected protocol: 3 or 4 |
| 79 | u8 | Source route point count (0–64) |
| 80 | u8 | Encoded route point count (0–64); zero for NAV3 or non-LIVE |
| 81 | u8 | Wire waiting reason, defined below |
| 82 | u8 | Current observed phone mode |
| 83 | u8 | Reserved, zero |
| 84 | u32 | CRC-32/ISO-HDLC over bytes 0–83 |

`4294967295` is unknown/unavailable for ages, accuracy and ACK latency. It is not an actual age. Ages are milliseconds; accuracy is decimetres. Phone and device monotonic clocks have different origins and cannot be subtracted. Phone timestamps wrap after approximately 49.7 days. `device_ms` in JSON is the receiver's 64-bit arrival time, not the worker's print time.

Modes: WAITING=1, LIVE=2, ARRIVED=3, STOPPED=4. `phone_mode_at_tx`, `wire_mode` and frame ages refer to the same last encoded navigation packet; `observed_mode`, current ages and current reason refer to the newer diagnostic sample. A captured wire mode proves what was encoded, not successful BLE delivery; compare `nav_seq` with `ack_seq` and device navigation logs. HELLO ACK 1 may exist while `nav_seq` is zero.

Flags: bit 0 GPS weak; bit 1 navigation active; bit 2 precise permission; bit 3 host `navigationAllowed` (permission/location admission policy, not just the system location switch); bit 4 navigation operation pending. Other bits must be zero.

Current reason: 0 none, 1 gps_weak, 2 map_fix_stale, 3 map_fix_stale_sdk_live, 4 instruction_missing, 5 instruction_stale, 6 awaiting_new_position.

Wire reason: 0 none, 1 source_waiting, 2 transport_fix_age, 3 transport_instruction_age, 4 transport_both_ages, 5 invalid_input. The existing encoder reserves a three-second transport budget within its 15-second freshness window. Tests reproduce phone LIVE / wire WAITING at source ages 12001–15000 ms; this is a diagnostic clue, not confirmation of the reported phone's root cause. This feature does not weaken freshness or location checks.

No coordinates, destination names, API keys, arbitrary message strings or phone/device identifiers are included. The random diagnostic session is temporary. JSON uses fixed keys/enums, and adds receiver discard/rejection counters.

## Use and removal

1. Install the stable-signed Android 0.7.14 update and flash circle firmware 0.3.4.
2. Keep the circle on USB and connect it in the App over BLE. Open Settings → Diagnostics → **开始蓝牙实时诊断**. Default duration is one hour; four hours is optional. No URL is needed.
3. On the PC, run from `moto_esp32`:

   ```powershell
   python tools/diagnostic_monitor.py --port COM21 --seconds 3600 --output dist/ble-diagnostic-session
   ```

4. Reproduce real navigation. **标记：刚刚出现问题** increments the marker counter. The PC saves raw `serial.log`, decoded `phone.jsonl` and latest `status.json` in a new output directory (an existing directory is rejected to avoid mixing sessions). The monitor opens only the requested port and does not intentionally reset the circle. Close another serial monitor first.
5. Stop in the App. The full local Android journal remains available separately.

Firmware component `components/motonav_diagnostics` owns the decoder, bounded queue and serial worker. `main/app/navigation_app.cpp` only assembles it through `BleDiagnosticCallbacks`. `tools/build.ps1 -NoBluetoothDiagnostics` (or CMake `-DMOTONAV_BLE_DIAGNOSTICS=OFF`) excludes it; with the switch off the component directory may be removed from a production export. `tests/` remains independently removable.

Validation includes actual Android GATT callback arbitration, Java-to-C++ fixtures from actual NAV encoders, malformed frames, CRC, ordering, timeout, receiver overflow, allocation failure, replay and disconnect epochs. These host checks and a firmware build do not replace real phone/BLE/USB acceptance or measure concurrent map FPS.
