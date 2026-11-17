# Camera coverage and perspective road surfaces — 0.3.13 / 0.7.21

This is the first implementation after `visual-parity-gta5-2026-11-17.md`:
camera-dependent real-map selection and projected road/route surfaces. It does
not claim full simulator parity or completion of the remaining annotation,
route-progress, icon and animation work.

## Implementation and boundaries

- Android `MapViewport` mirrors the device camera's speed, heading, pitch and
  anchor. Inverse screen coverage includes a 120 m margin and is bounded to
  +/-1900 m. `Basemap.visibleTiles` requests at most nine tiles at z14 or lower.
  WAIT uses its actual framing preset and does not invent driving speed.
- `MapDetailSelector` separates water, green areas, roads and buildings, ranks
  projected coverage and distributes objects across screen cells. Geometry
  remains limited to 384 primitives, with 192 for progressive previews. An
  object is retained or omitted as a whole; roads can split at existing vertices.
- `MapPolygon` uses bounded even-odd tessellation for polygon holes. Courtyard
  interiors stay empty; objects beyond the existing complexity budget can
  still be omitted. This is not a promise of complete provider coverage.
- The loader finishes an in-flight request for the same route origin, keeps
  the last received display scene while replacements arrive, and limits camera
  refresh requests to at least 1.5 s apart. Location validity is unchanged.
- ESP `MapStroke` projects ground widths 2.8/5/6.5 px for roads (highway outer
  edge 10), and 8/13 px for route fill/edge. Bodies are exact homographies;
  round caps use local projected ellipses. The old overview path is preserved.
- Prepared polygons and strokes occupy compact typed pools in a bounded PSRAM
  scene. Draw strips reuse this geometry. There is no new full-frame canvas,
  DMA buffer or per-frame allocation. Public driver and navigation interfaces,
  NAV4S5 and the GATT service table are unchanged.
- Palette lookup, label collision broad-phase checks and unchanged text colors
  avoid repeated work. `CONFIG_LV_OBJ_STYLE_CACHE=y` adds eight bytes per LVGL
  object; an existing sdkconfig must enable it explicitly.

Names still have the prior 24 received / 8 visible limits. Icons retain the
existing glyph design. Explicit SDK route progress, continuous route highlight
phase, bridge/tunnel ordering, area HUD and independent camera smoothing remain
subsequent work. No nearest-point guess is used to infer progress through a
self-intersecting route.

## Reproducible source data and host checks

The cached public London tile is unchanged, SHA-256
`ab9c6c7141397dcdf316eb140a576716515c8f60618ab6c8b1d59bb5549ee85f`.
The LIVE 36 km/h fixture now contains 384 primitives: 44 minor roads, 101 major
roads, 160 building triangles, 47 water triangles and 32 park triangles, plus
24 labels. These counts are primitives, not buildings or named places. ODbL
attribution and reproduction instructions are in `tests/fixtures/REAL_MAP_SOURCE.md`.
The route and movement remain explicit test inputs, not the user's location.

`MapGeometryTests` passed 389 checks including courtyard area, winding order,
camera coverage across 180 speed/heading/latitude combinations, bounded tile
counts, context budgets and deterministic selection. The complete Android
host suite passed. Source CI for Android commit `68a28d8` passed verification,
APK build/lint and the then-current firmware production checks.

Firmware host checks cover projected widths, clipping, buffer guards and exact
whole-frame/strip equivalence. Twelve LVGL checks passed with the style cache,
including cached/uncached text, direct/decoder map paths and RGB332 reference
pixels. Enabling the style cache did not change the fixed real-map LIVE pixels.

## COM21 renderer comparison on the original 383-primitive fixture

RGB565, 40 MHz QSPI, double 64-row DMA buffers, unchanged synthetic movement.
Each timed run is about 40.5 s after warm-up. Instrumented dispatch changes
timing, so the table records it and is not a strict uninstrumented speedup claim.

| Build | Dispatch profiling | Min / mean FPS | Prepare us | Render us |
| --- | --- | --- | ---: | ---: |
| 0.3.12 delivery | off | 18 / 23 | 2794 | 37766 |
| Initial perspective implementation | on | 16 / 20 | 7015 | 38449 |
| Compact pools and analytical caps | off | 17 / 22 | 5523 | 36168 |
| Shared projection / label broad phase | on | 17 / 22 | 4982 | 36530 |
| With LVGL style cache | on | 18 / 23 | 4959 | 34879 |

Last measured preparation: geometry 4301 us, annotations 390 us, HUD 267 us.
DMA averaged 19705 us, overlapping rendering; it must not be added to render
time. Map rasterization averaged about 11 ms/frame. Test free internal heap
was about 25 KB versus roughly 7 KB in the previous labelled test. No panic,
assertion or watchdog was observed in these captures. The 40 FPS target is
still unmet. The new data extract is a different workload and is recorded
separately when validated; it must not replace the identical-input comparison.

Local detailed evidence: `moto_esp32/dist/map-integration/0.3.13/`.
Final release hashes, normal-image boot verification, new-extract measurements
and visual feedback are appended after delivery checks finish.
