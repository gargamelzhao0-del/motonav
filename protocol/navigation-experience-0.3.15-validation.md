# Navigation presentation and map refinement — firmware 0.3.15

Date: 2026-11-17. Board: Waveshare ESP32-S3-Touch-AMOLED-1.75C, COM21,
USB 303A:1001 / C4:9E:7E:0C:51:C8, BLE MotoNav-51CA. Android 0.7.21
remains compatible; the NAV4S5 format, GATT table and position validity rules
are unchanged. The production image contains no simulated route or map.

## Implemented behavior

- `MapCameraMotion` separates a finite 400 ms position transition from heading,
  zoom and pitch/anchor easing. Changes to heading or zoom cannot restart a
  position transition. Heading uses shortest-angle wrapping and a 90 degree/s
  limit. Known speed below 4 km/h holds the established view heading.
- `RouteTrace` caches at most 64 local points. A visual match must be within
  12 m and unambiguous: another close projection more than 25 m along the route
  away rejects the match. Valid endpoints interpolate along the polyline while
  preserving observed offsets. There is no dead reckoning or GPS freshness
  extension. Large corrections and route-coordinate revisions reset the view.
- Passed geometry is thinner and gray, future geometry remains purple. This is
  local display matching, **not authoritative SDK journey progress**. Waiting,
  approximate, historical and ambiguous positions do not get a passed split.
- A pure `NavigationPresentation` policy selects normal/approach/immediate
  layouts from trusted instructions. Approach is 150–280 m depending on speed;
  immediate is 50–100 m. Exit hysteresis is 25/20 m respectively. Unknown,
  straight, waiting and expired instructions do not activate this policy.
- Approach enlarges the arrow/card and limits annotations to 3 names/6 icons;
  immediate uses amber emphasis, 2 names/4 icons and hides secondary trip/current
  road text. Speed remains visible. Normal budgets remain 6 names/12 icons.
- Retained names wait 350 ms before changing sides after an obstruction, while
  still hiding an obstructed label immediately. Pending layout work can finish
  even with a stationary camera. Icon size thresholds have 4 px hysteresis.
- U-turn and roundabout symbols are bounded to eight points. Generic entry/exit
  shows a roundabout without inventing a numbered exit or its direction.
- `MapStripIndex` uses 720 bytes per map scene to select candidate features in
  painter order for each DMA strip. It does not drop intersecting map features.

## Visual refinement after device feedback

The user confirmed the first 0.3.15 test's route, marker, text and maneuver
states worked, but described the overall map as too plain. The final revision
therefore changes presentation beyond those behavioral fixes:

- Maximum pitch 50 to 35 degrees, a less distant high-speed zoom range and an
  anchor around y=298 rather than y=306 at maximum pitch.
- Centralized ground/building/road/water/park colors, clearer building contrast
  and gentler upper/rim shading. Bottom shading still supports the speed HUD.
- A continuous purple route replaces the white dashed highlight segments.
  Route and vehicle edges use fractional scanline coverage; only edge pixels
  blend, with no supersampled frame buffer. This is edge coverage smoothing,
  not full-scene multisample antialiasing.
- Reduced rim ticks and corrected test-banner/FPS placement. The held fixture
  cycles 320/120/40 m after timing, keeping the normal annotation budget during
  the measured interval. The heavy test starts after LVGL takes over, keeping
  its initial rendering off the small main-task stack.

The refinements do not add geographic data. Existing 384-primitive/24-label
limits and provider coverage remain; buildings, lanes and POIs are never
invented to fill gaps. The narrower view was checked against the installed
phone's older 50-degree viewport plus its 120 m coverage margin.

## Validation

- 23 host C++ programs and dependency checks pass. Added coverage includes
  independent motion channels, route bends, close parallel legs/crossings,
  clock wrapping, invalid/expired guidance, camera coverage and strip masks.
- Analytic fractional-rectangle tests verify edge color blending. Antialiased
  triangles/ellipses match across full frames, 16-row strips and clipped tiles.
- All 12 LVGL checks pass, including production without test integration,
  maneuver/expiry scenarios, label relocation, maximum route split capacity,
  cached/uncached pixels, direct/decoder pixels and RGB332 reference pixels.
- Normal and optional benchmark ESP-IDF builds pass. `verify_production.py`
  confirms the normal image excludes test components and simulated maps.

## COM21 measurements

RGB565, 40 MHz QSPI, double 64-row DMA buffers, same 384-primitive/24-label
geographic fixture, draw-task profiling disabled. Each timed interval follows
a 2.5 s warm-up. The final perspective/style differs, so this is not an isolated
microbenchmark of the strip index.

| Measurement | 0.3.14 | First 0.3.15 | Final refined 0.3.15 |
| --- | ---: | ---: | ---: |
| Minimum / mean sampled FPS | 21 / 23 | 21 / 24 | 19 / 23 |
| Timed interval (ms) | 40528 | 40533 | 40544 |
| Prepare mean (us) | 5147 | 5488 | 5017 |
| Geometry / annotations / HUD (us) | 4402 / 470 / 274 | 4660 / 533 / 294 | 4139 / 570 / 307 |
| Render mean (us) | 34757 | 33509 | 35164 |
| Overlapping DMA mean (us) | 19617 | 19616 | 19629 |

The final style trades the first iteration's small FPS gain for clearer map
presentation and edge smoothing. **The complex-map 40 FPS target remains unmet.**
DMA overlaps rendering and must not be added to render time. The subsequent
held demo changes annotation budgets, so its samples are not the fixed-layout
timed benchmark. Main-task minimum stack improved from 536 to 3456 bytes after
deferring the optional fixture start; that was a test-startup change.

Logs and native previews are retained in `moto_esp32/dist/`; preview images are
native LVGL renders of a fixed London OSM fixture, not photographs, phone GPS
captures or invented map geometry. Map data: © OpenStreetMap contributors,
ODbL; fixture provenance remains in `tests/generate_real_map_fixture.py`.

Final refined visual acceptance and normal-image boot/phone verification are
recorded separately after the held test. A build or screenshot does not prove
live phone-navigation acceptance or full simulator parity.
