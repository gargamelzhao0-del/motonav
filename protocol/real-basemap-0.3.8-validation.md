# Real map follow-up: Android 0.7.17 / ESP32 0.3.8

This follows the user's rejection of 0.7.16's incomplete, slow-loading map.
The labeled RGB565 animation was visually accepted; actual navigation with the
new Android build still requires device observation after installation.

Implemented: whole street/footprint selection, spatially distributed detail up
to 384 primitives, first-tile publication, one newest queued replacement, NAV4S2
generation validation, retained display during transfer, viewport reselection,
full-color palette and explicit map-ready/no-GPS status. Navigation and STOP
remain higher priority than map chunks. GPS_WEAK never creates simulated motion.
No services, external log forwarding or firmware pairing storage were changed.

Offline checks: complete Android pure suite (100 actual BleLink callback checks
using boundary doubles), 229 basemap checks, 1,492 cross-language map receiver
checks, driver/architecture/session tests, and all 12 LVGL preview comparisons.
The cached public map fixture contains 383 primitives; it is synthetic navigation
over real test geography, not the user's coordinates.

COM21 test comparisons, QSPI 40 MHz, RGB565:

| Configuration | Minimum / average FPS | Interpretation |
| --- | --- | --- |
| 192 primitives, 64 rows | 39 / 40 | Full-color appearance accepted by user |
| 192 primitives, 48 rows | 36 / 37 | Smaller buffers rejected |
| 384 primitives, 64 rows | 36 / 37 | More detail increases CPU raster cost |
| 383 primitives, projection fast paths, 64 rows | 36 / 38 | Preparation 1,342 us average / 1,469 us maximum |
| Raster compiled with O3, reciprocal stroke lengths | 36 / 37 | Rejected compiler setting; returned to O2 |
| Final source, 383 primitives, 64 rows, RGB565 | **36 / 38** | 36 samples over 40,533 ms |

The final measurement had no phone connected. Preparation averaged 1,392 us
(maximum 1,505 us), raster tiles averaged 1,216 us, DMA averaged 19,571 us/frame.
BLE host stack reserve was at least 5,464 bytes; internal heap stayed above
13 KiB. It does not meet a guaranteed 40 FPS floor and is not a live navigation
result. Hardware tests remain removable and disabled in production.

## Delivery verification

Source: `6d5e22102abe9de2886acb3e3895860766e6535d`. Android 0.7.17 (code 25)
published by [release run 43847307026](https://github.com/gargamelzhao0-del/motonav/actions/runs/43847307026).
The package was downloaded and verified against the release manifest:
105,767,709 bytes, SHA256
`d5d0df158781e359d4856a959fc4036cf1a5b98bc76f6b8e31c27a752362d640`.
The stable signer remains
`af9e2f4cb0997fe77ae884e9d7797a7c6b933c1bfdebc3993386ae418aeacde1`.
The public latest update manifest was independently read back at code 25.

COM21 application address 0x10000 was flashed and hash-verified. Startup reports
firmware 0.3.8, `device_tests=0`, `ble_diagnostics=1`, RGB565. Production verification
confirms no synthetic map/device test symbols. Navigation metrics are enabled.
Idle internal heap is 32,347 bytes with BLE stack reserve 5,512 bytes. Pairing NVS,
bootloader and partition table were preserved. One-hour passive diagnostic capture:
`moto_esp32/dist/COM21-0.3.8-diagnostics-20261116-181836`.

Local artifacts in `moto_esp32/dist/release-0.3.8/`:

- Normal `motonav.bin`: 1,158,768 bytes, SHA256
  `65f064077a3bf58d8e83621ec14121dc35a85a0f59c199da048681461e57fc65`.
- Final test-only benchmark image SHA256
  `6c91279bf5c9cefbf40c8d8b4b15d3952a5f0577b6358cc1e92666eefa2ca25b`.
- Exported source without tests SHA256
  `e89d47c4f77a04647f97028f2240cd13f60b6055a837af2dd67a1f07de9c6a40`.

Firmware changes were synchronized into the user's original `motonav/moto_esp32`
only after verifying each destination against the last delivered baseline.
The original Android working tree was left under its existing owner's control;
the signed APK was copied to `motonav/apk/MotoNav-0.7.17.apk`.

Both jobs in [exact-source CI 43847207252](https://github.com/gargamelzhao0-del/motonav/actions/runs/43847207252) succeeded, including production export built without tests and all LVGL previews.

The user installed 0.7.17 and rejected the appearance as still substantially
different from the repository simulator. Actual diagnostics confirm code 25,
NAV4S2 and complete delivery: first local focus at device_ms=258027, partial map
154 primitives / generation 1 at 259139, final map 274 primitives / generation 2
at 261363. These sampled timestamps imply about 1.1 seconds to the first scene
and 3.3 seconds to final detail, not exact transfer timings. Navigation protocol
rejects remain zero; one separate diagnostic record was dropped/rejected during
startup. GPS remains weak. This demonstrates transfer completion, not visual
acceptance. Work continues against `sim/index.html` for perspective and HUD parity.

Remaining: geographic alignment, outdoor GPS
following/recovery and sustained FPS during simultaneous BLE map replacement.
OSM coverage, polygon holes and the bounded detail budget remain limitations.
