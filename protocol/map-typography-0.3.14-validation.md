# Map typography and independent POI layout — firmware 0.3.14

This iteration addresses the reported coarse text/icons and crowded map. Android
0.7.21 remains compatible; no phone update, GATT change or map packet change is
required. Geometry, route progression, position validity and map coverage are
unchanged. This is not a claim of full simulator parity or 40 FPS performance.

## Implementation

- Generate the same supported CJK/Latin character repertoire from pinned Noto
  Sans SC at 16 px, weight 400, replacing 18 px weight 500. Anton speed and
  Barlow turn-distance fonts retain their previous sizes.
- Map names use a cached anti-aliased glyph mask with a one-pixel dark halo and
  transparent background. The current-road HUD uses the same private component.
  No filled label plate is drawn. The original string is retained for identity;
  overlong display text is shortened at a UTF-8 boundary with an ellipsis.
- POIs use original vector-derived building, fuel, parking, food, medical,
  transit and park symbols. The checked-in generator supersamples them to
  12/15/18 px images; depth selects the size. Images live in Flash and remain
  upright. They are not copied game assets.
- Independent budgets admit at most six names (three roads, one place and two
  POIs) and twelve icons from the existing 24 received labels. Names can be
  omitted while their icons remain. Redundant generic category names are omitted.
- Both layers avoid the route, position marker, HUD and previously admitted
  labels/icons. Identity uses name, kind and local coordinates. Retained items
  try their previous placement first and have a small boundary hysteresis;
  a new route coordinate system clears that preference.
- Caption allocations are bounded (about 19 KiB each, eight annotation slots
  plus one current-road slot) and use the existing large-allocation PSRAM path.
  Text rasterization runs only when content, width or color changes. Camera
  movement changes positions; it adds no per-frame allocation or framebuffer.
  The RGB565, 40 MHz QSPI and double 64-row DMA configuration is unchanged.

## Local host verification

The full firmware host suite and all twelve real-LVGL checks passed. Checks
include route/HUD collision bounds, icon-only placement, transparent caption
backgrounds, unchanged-camera pixel stability, harmless label-list reordering,
movement, replacement/clearing, direct/decoder rendering, cached/uncached text
and RGB332 reference equivalence. Existing protocol, driver ownership and
diagnostic bridge tests also passed.

Before/after host previews use identical 384-primitive public London geometry,
camera and synthetic guidance. This is neither the user's location nor a
photograph of COM21. Map data: © OpenStreetMap contributors, ODbL; see
`moto_esp32/tests/fixtures/REAL_MAP_SOURCE.md`.

![Identical-input host preview, 0.3.13 left and 0.3.14 right](assets/map-typography-0.3.14/before-after.png)

## COM21 measurement

Source commit: `4c3477e9133c56532f3ebbe5149f184f4c6da2f7`. COM21 identity was
checked as USB 303A:1001, serial C4:9E:7E:0C:51:C8, BLE MotoNav-51CA. Only the
application at 0x10000 was flashed, with esptool hash verification; bootloader,
partition table and NVS were preserved. The old passive logger had ended
naturally before the port was opened.

Same 384-primitive / 24-label fixture, RGB565, 40 MHz QSPI, double 64-row DMA,
style cache enabled, draw-task profiling disabled:

| Measurement | 0.3.13 | 0.3.14 |
| --- | ---: | ---: |
| Timed min / mean FPS | 18 / 23 | 21 / 23 |
| Timed interval after warm-up | 40557 ms | 40528 ms |
| Prepare mean | 5024 us | 5147 us |
| Geometry / annotations / HUD | 4362 / 402 / 259 us | 4402 / 470 / 274 us |
| Render mean | 35062 us | 34757 us |
| Overlapping DMA mean | 19703 us | 19617 us |

The continued 0.3.14 hold reached 125 samples at about 145 s uptime: minimum
19, mean 23 FPS. The complete capture lasts 150 s and contains no panic,
assertion or watchdog. Lowest sampled free internal heap was 24459 bytes.
This is a typography/layout change with broadly similar average rendering
cost, not evidence of a sustained FPS gain. DMA overlaps rendering and must
not be added to render time. The complex-map 40 FPS goal remains unmet.

The normal image passed `verify_production.py`, excluding the test component
and simulated map. The delivery also contains a source export without `tests/`.

| Normal artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| motonav.bin | 4151440 | `a9fbbe5b28a6d788d4bccc7b3c4d470b7902f6d0f9640cb3a16d0c608f7ac357` |
| source-4c3477e.zip | 4764910 | `7bea88f53709ba1e459b6938b155bc0a3075fdab71bae5a740d395e5af132279` |

Detailed local logs and previews are under
`moto_esp32/dist/map-integration/0.3.14/`. Artifacts and manifest are under
`moto_esp32/dist/release-0.3.14/`.

## Normal-device delivery

The normal image was written and hash-verified on COM21 at 0x10000 around
14:24 Asia/Shanghai on 2026-11-17, preserving pairing/NVS. The 30 s boot capture
reports firmware 0.3.14, `device_tests=0`, `ble_diagnostics=1`, free internal
heap 26579 bytes and main-task stack minimum 3416 bytes. It contains no
panic/assert/watchdog. Bluetooth was disconnected during this boot capture;
this is not a phone-navigation acceptance run. A passive one-hour diagnostic
logger was started at 14:25 without resetting the device.

Physical legibility and real-phone map feedback remain pending; host previews
and boot logs do not establish either. Android 0.7.21 does not need reinstalling.
Firmware/protocol synchronization checks the original checkout against baseline
`2c4a317`, backs up changed files, and verifies each copied file. Five superseded
18 px generated font files are removed only after their original bytes match
that baseline. Original Android source files are not changed.

[Source CI](https://github.com/gargamelzhao0-del/motonav/actions/runs/44030382337)
passed both jobs on `4c3477e`: Android checks/build/lint, firmware dependency
boundaries and host tests, a clean production build after removing `tests/`,
optional device-benchmark compilation, and all LVGL checks. This CI does not
publish a new phone release. The delivery includes the CI result and measured
normal-image SHA-256 in its validation records.
