# Simulator style 0.3.9 / Android 0.7.18 validation

The user rejected 0.7.17 / 0.3.8 appearance as substantially different from the
repository simulator. The reference is `sim/index.html`, not the older firmware
benchmark. A complete 274-primitive transfer in the preceding run did not mean
that the visual design was accepted.

This revision implements its perspective formula, Anton/Barlow typography,
Chinese labels, dark gradients, compact turn card, local route highlights and
road-name/ETA fields. Geometry remains actual provider data; the simulator's
procedural city is not a replacement for missing real map data.

Local checks: Android pure Java and boundary-double suites passed, firmware
host driver/protocol/raster suites passed, and 12 LVGL render/lifecycle tests
passed. New Java-to-C++ fixtures cover Chinese UTF-8, malformed payloads,
reference binding, capability fallback and independent data expiry. Shared
production HUD rendering is exercised in the removable device benchmark.

The first device candidate exposed a main-task stack overflow during UI startup.
COM21 was restored to the verified 0.3.8 application immediately. A runtime
2.8 KiB ring-geometry initializer was replaced by a compile-time constant table;
the main task now reserves 6144 bytes instead of the previous 3584. This first
candidate is not a delivery artifact.

## Delivery and checks

- Firmware source: `26a1178106d8bc02ccbcb655bb8f88fbed70963f` on
  `codex/ble-diagnostics`; firmware version 0.3.9.
- Android source/tag: `89458e2315edf13a568f22a2292e2b125dd9fd4d`,
  `android-v0.7.18`, version code 26. The later firmware-only optimization
  does not change the published Android source/tag.
- [Final source CI](https://github.com/gargamelzhao0-del/motonav/actions/runs/43858545172):
  both `verify` and `firmware` passed. Local final LVGL run: 12 passed, zero
  failed. Host protocol/driver/raster checks and the production-image
  verification passed.
- [Signed release workflow](https://github.com/gargamelzhao0-del/motonav/actions/runs/43857718705)
  passed. The latest stable release points to 0.7.18.
- [MotoNav 0.7.18 APK](https://github.com/gargamelzhao0-del/motonav/releases/download/android-v0.7.18/MotoNav-0.7.18.apk):
  105771805 bytes, SHA256
  `7d7dc225831abe78825c8da92aeb5ad4697aa1651cd2b14a076f9e98d2b38a37`.
  Stable signer SHA256 remains
  `af9e2f4cb0997fe77ae884e9d7797a7c6b933c1bfdebc3993386ae418aeacde1`.
- Normal firmware: 2432272 bytes, SHA256
  `77109076d3a7b9b61299c96aeb6ec8f94f2af8e9f81649f10bd3271da9e72371`.
  ELF SHA256:
  `9bf52edb2cbcd56172df066753e2fed68fc873699b5869e7edefce233068f5a0`.
- Source export without device tests: `source-26a1178.zip`, SHA256
  `76d0ba4a859966dd45ba8c0ee204dbcc07ae90cf23dba2c00474d975c986dbc4`.

COM21 was flashed with only the normal application at `0x10000` on
2026-11-16 at 19:38 Shanghai time; esptool verified its hash. Bootloader,
partition table and NVS were preserved. Startup reports firmware 0.3.9,
`device_tests=0`, `ble_diagnostics=1`, `DIAG1_RX=31` and successful advertising
as MotoNav-51CA. The first normal idle capture reports 27823 bytes of internal
heap, main stack minimum remaining 3168 bytes and BLE stack minimum remaining
5548 bytes, with no panic, allocation failure or watchdog event. These are
idle startup observations, not a connected navigation endurance result.

Builds use RGB565, 64 buffer rows, QSPI 40 MHz and a 6144-byte main task stack.
BLE and its diagnostic worker are initialized before the navigation UI so
the LVGL allocations cannot consume the contiguous memory needed by the BLE
host task. Normal delivery excludes the test component and simulated route.
Artifacts and private captures are under the ignored `moto_esp32/dist/` tree.

## Physical display result and remaining performance gap

The user confirmed that the new Chinese labels, turn card, speed and perspective
map display normally and are closer to the repository simulator, while asking
for the position marker to follow the route. The removable test animation now
walks the actual decoded route segments with their tangent heading, replacing
independent sine-wave camera movement. Its speed matches the marked test HUD.
A host check samples 120 seconds and verifies that the camera stays within
0.01 m of a route segment. The user then confirmed: "车标跟随路线正常".
This acceptance covers the marked display test, not live GPS navigation.

The final timed hardware benchmark used 383 real-map primitives, the complete
new HUD, BLE advertising, and the route-following test camera:

| Measurement | Result |
| --- | --- |
| Timed samples / duration | 36 / 40589 ms |
| Minimum / average FPS | 24 / 26 |
| Average LVGL render time | 34067 us/frame |
| Average DMA time | 19568 us/frame |
| Map drawing / text-mask drawing | 14299 / 2540 us/frame |
| Map preparation | 2069 us average, 3473 us maximum |
| Text cache | 25022 hits, 15 misses, 401280 bytes |
| Main / BLE stack minimum remaining | 2296 / 5540 bytes |

Capture: `dist/COM21-0.3.9-final-bench-20261116-193234/serial.log`.
Benchmark image SHA256:
`df6c42dc1a98203e7e8dc542a595148ba09ae0e246d7459908bb66e44ace8bb3`.
It is separate from the normal image and remains visibly marked DISPLAY TEST.

Transparent text masks and the precomputed shade lookup improved this new HUD
from minimum/average 19/21 FPS to 24/26 FPS. The 40 FPS requirement is still
unmet. The previous 0.3.8 simplified RGB565 HUD measured 36/38 FPS; adding this
more faithful layout and shading has a real rendering cost. Render and DMA
partly overlap, so their durations must not simply be added. Further work must
reduce the measured map/compositing cost while preserving the accepted layout.
No 80 MHz display clock or 60 FPS result is claimed.

## Real navigation acceptance still required

The normal application continues to use fresh phone/SDK navigation coordinates;
the test route follower does not supply production locations. Chinese road
names and ETA require a fresh NAV4S3 metadata frame bound to the accepted NAV
sequence/revision. Missing speed limits remain unknown, and weak GPS does not
create fabricated movement or speed. Actual provider geometry can differ from
the simulator's procedural city.

The user has been asked to install 0.7.18, connect MotoNav-51CA, enable Bluetooth
diagnostic forwarding and start real-map navigation. Real connected map/metadata
acceptance and outdoor GPS guidance have not yet been observed in this record.
