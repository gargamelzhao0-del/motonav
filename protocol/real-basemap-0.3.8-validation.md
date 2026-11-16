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

The latter measurement uses 36 samples over 40,548 ms, with no phone connected.
It does not meet a guaranteed 40 FPS floor and is not a live navigation result.
Further raster changes and final production/release verification are recorded
below when completed. Hardware tests remain removable and disabled in production.

Remaining: actual new-app first-map latency, geographic alignment, outdoor GPS
following/recovery and sustained FPS during simultaneous BLE map replacement.
OSM coverage, polygon holes and the bounded detail budget remain limitations.
