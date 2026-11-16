# Real local basemap delivery — 2026-11-16

Implementation: `97ce86ef102a41761084641abb707c017ca2a047`, branch
`codex/ble-diagnostics`, Android tag `android-v0.7.16` (version code 24),
ESP32 0.3.7. Protocol/interface details: [NAV4 local map](nav4-local-map.md).

Android 0.7.16 was published successfully by
[release run 43837374223](https://github.com/gargamelzhao0-del/motonav/actions/runs/43837374223).
The public APK was downloaded and its 105,767,709-byte size and SHA256 verified:
`81911fb51ec031282af83b5a497937fc92d970d7bf986f981b0190dca906a8bc`.
The stable signer remains
`af9e2f4cb0997fe77ae884e9d7797a7c6b933c1bfdebc3993386ae418aeacde1`.
The public latest update manifest points to version code 24.

## Verified

- Android pure tests, including 94 actual BleLink callback checks with boundary
  doubles; MTU negotiation, legacy fallback, interleaved WAIT/map chunks and ACK
  accounting. Basemap decoder/coordinates/local focus: 26 checks.
- Java-to-C++ basemap receiver: 1,467 checks; malformed/CRC-corrupt chunks,
  ordering, atomic publication, duplicates, expiry and STOP. Previous NAV3/NAV4,
  diagnostics, drivers, ownership and architecture checks also pass.
- Public OSM tile 14/8186/5448 downloaded with identifying User-Agent and cached:
  2,657 decoded features reduced to 192 primitives (43 minor-road segments,
  53 major-road segments, 77 building triangles, 8 water triangles, 11 park
  triangles). This is test geography, not the user's location. Attribution and
  fixture provenance are in `moto_esp32/tests/fixtures/REAL_MAP_SOURCE.md`.
- All 12 LVGL host tests pass, including real map WAIT/LIVE/GPS-loss/STOP
  previews, cached/uncached equality and RGB332 reference equality.
- [CI 43836479919](https://github.com/gargamelzhao0-del/motonav/actions/runs/43836479919):
  Android compile/lint and firmware build/export-without-tests/benchmark/preview
  jobs both succeeded for the exact implementation commit.
- COM21 identity: ESP32-S3, USB 303A:1001, serial `C4:9E:7E:0C:51:C8`.
  Only application address 0x10000 flashed, with esptool hash verification.
  Bootloader, partition table and pairing NVS preserved.
- Test-only real-geometry animation, 192 primitives, 466×466, RGB332, 40 MHz QSPI,
  64-row DMA buffers: **minimum 55 FPS, average 57 FPS, 36 samples over 40,545 ms**.
  Geometry preparation average 1,239 us / maximum 1,366 us. This test used
  simulated camera motion and did not include live phone GPS or concurrent BLE
  map loading. The user missed the visual observation; visual acceptance is pending.
- Restored production 0.3.7 without device tests, with real navigation metrics and
  diagnostic bridge enabled. Old 0.7.15 phone reconnected successfully using
  NAV4P; over four minutes of waiting guidance retained its route with zero
  protocol rejects and minimum BLE host stack reserve 4,748 bytes.

## Artifacts

- Production `motonav.bin`: SHA256
  `a7973d2704e1fec69c26493986deb3d0bada1082fb309e6824d898fee421c803`.
- Test-only benchmark image: SHA256
  `5d14b752a0e2e1c2f1dad96d6578247bc5f798dad6c2a6a0d7b24d446992b5f5`.
- Exported production source ZIP (tests removed): SHA256
  `20895881bb28ff54bae5c8f0fd229a722297c810d3e116af164536ec47dab40b`.
- Active worktree capture directories:
  `moto_esp32/dist/COM21-0.3.7-map-bench-20261116-170647` and
  `moto_esp32/dist/COM21-0.3.7-diagnostics-20261116-170934`.

Firmware source and protocol contract were synchronized to the user's original
`motonav/moto_esp32` directory only after each destination matched the previously
delivered baseline; no independent Android checkout edits were overwritten.

## Remaining device acceptance

Install stable Android 0.7.16, accept the added OSM map-data purpose, reconnect and
start a real route. Confirm NAV4S, local=1, matching map/route revisions and a
complete map primitive count. Verify actual local OSM/AMap alignment and appearance.
Outdoor live-position following, GPS-loss recovery with real phone movement and
FPS under simultaneous BLE updates remain unverified. Indoor GPS is not promoted
to a trusted fix by the map layer. Do not report this as full simulator feature parity.
