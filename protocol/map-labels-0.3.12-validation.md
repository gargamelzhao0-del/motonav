# Map annotations: Android 0.7.20 / firmware 0.3.12

On 2026-11-17 the user reported that the device still lacked the simulator's
map information, especially landmarks and place names. Inspection found that
`VectorTile` discarded name/POI layers and retained only geometric kinds.
NAV4S4 transported shapes and live-instruction HUD text, without an independent
map annotation set. Consequently those names could not appear on the ESP.

This change retains real provider names and categories, decodes point geometry,
selects a bounded spatially distributed annotation set, transports it through
the optional NAV4S5 capability, and renders an independent map label layer.
The layer works during WAIT/GPS weakness and does not alter the validity of
navigation, current positions or 0.3.11's historical-position display.

The phone can send 24 labels per map generation. Up to eight fit on the round
screen after collision/edge checks; the renderer keeps names horizontal while
their real anchors follow the projected map. Category icons identify fuel,
parking, food, medical, transit and parks. A verified unnamed service uses a
category description; an unknown feature is not assigned a fabricated name.
The original 383-primitive London geometry remains unchanged in the regression
fixture, now with names from the same real OSM tile. Position and navigation
in that fixture are explicitly synthetic test inputs.

The normal image excludes all test fixtures and benchmarks. Source components:
`LocalMapLabels` selects provider-neutral data; `RouteProtocol`/`BleLink`
encode/schedule it; `MapLabelReceiver` validates and atomically publishes it;
`MapAnnotations` projects/declutters it. No display, touch or BLE driver
internals are accessed by these components.

Validation covers real cached MVT decoding, UTF-8/category handling, bounded
selection, Java-to-C++ frames, corruption, invalid lengths/kinds/coordinates,
atomic replacement, empty-set clearing, session resets, old-capability
fallback, navigation priority and STOP cancellation. LVGL previews cover
Chinese labels, icons, movement, collision avoidance, clearing and exact
cached/uncached and RGB332-reference pixel equivalence.

Local final test results, CI, release hashes, device installation, hardware FPS
and user acceptance will be recorded after verification. No claim of complete
map coverage, improved GPS accuracy or a sustained 40 FPS is made.
