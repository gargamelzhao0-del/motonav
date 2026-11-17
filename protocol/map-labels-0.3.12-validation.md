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

Visual inspection exposed a missing Traditional Chinese glyph in the real
Whitehall name. The pinned OFL Noto font now supplies supported CJK basic
ideographs and accented Latin through three additional bounded fallback
chunks in flash. Original GB2312 glyphs and metrics remain unchanged. The
renderer tests assert non-placeholder glyphs for traditional place-name
characters and accented Latin, and all 12 UI checks pass with this extension.

## Verified delivery

Source: `6443b4a6bbdbc14c7e8eb0f60fa19e558eb97108`, on
`codex/ble-diagnostics`; Android tag `android-v0.7.20` points to this commit.

- The full Android host suite and firmware host suite passed, including 22
  map-label selection/encoding checks and 221 cross-language receiver checks.
- All 12 LVGL checks passed after the font extension, including exact cached
  versus uncached rendering and RGB332-reference comparisons.
- Both jobs of [source CI](https://github.com/gargamelzhao0-del/motonav/actions/runs/44008732314)
  passed: Android verification and the exported production firmware build.
- The [Android publish workflow](https://github.com/gargamelzhao0-del/motonav/actions/runs/44008738868)
  passed, including APK signature verification and stable-channel signer checks.
  The downloaded APK matches the published size, SHA-256 and version metadata.
- The final local production image passed `tools/verify_production.py` and
  excludes the benchmark and simulated navigation fixtures.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| MotoNav-0.7.20.apk, version code 28 | 105779997 | `0e61bdac0e105d180986f3c07e8beecc3e808cc6c0ccce56a9b336dd99f9461f` |
| Firmware motonav.bin | 4774176 | `d477d7a9f605d9171a41dfed44bf420b37cb233820749877dd208d33ae43fc64` |
| Firmware source-6443b4a.zip | 3479671 | `2fd03a9de5154f1138443ffbe6844938c2800dcd00a9818bb5486e0228fd1d49` |

APK signer SHA-256:
`af9e2f4cb0997fe77ae884e9d7797a7c6b933c1bfdebc3993386ae418aeacde1`.
Install the [signed Android update](https://github.com/gargamelzhao0-del/motonav/releases/download/android-v0.7.20/MotoNav-0.7.20.apk)
over the existing stable app. Firmware alone cannot create names that an old
phone app does not transmit. This update does not change the GATT service table.

## COM21 observations

The device identity was checked before each application-only write: USB
303A:1001, serial/MAC C4:9E:7E:0C:51:C8, BLE MotoNav-51CA. The bootloader,
partition table and NVS were preserved.

The labelled London map benchmark used RGB565, 64-row double buffering and
40 MHz QSPI. Its timed measurement reported minimum **18 FPS**, average
**23 FPS**, across 36 samples over 40534 ms. The continued animation reached
a lower minimum of 15 FPS and an average around 21 FPS; the feature does not
meet the earlier 40 FPS target. The render profiler reported 37766 us per
frame, with 19808 us DMA time that overlaps rendering and must not be added
to it. Map preparation averaged 2794 us. Text caching recorded 25147 hits and
33 misses, with 668800 bytes of external-memory cache storage. Further frame
time work should measure map rasterization and overlay drawing together.

The user confirmed: "名称和图标可见，显示正常". This confirms the labelled test
scene visually; it is not acceptance of the user's actual phone map.
No panic, assertion, watchdog or stack-overflow message was observed in the
captured benchmark. Its main-task stack minimum was 1648 bytes; internal free
heap was approximately 6.6–7.6 KB, so connected operation still needs monitoring.

The normal image was then flashed and verified at 0x10000 on 2026-11-17
11:25 (Asia/Shanghai). Boot reports firmware 0.3.12, `device_tests=0` and
Bluetooth diagnostics enabled. At idle it reports 26659 bytes free internal
heap and a 3024-byte main-task stack minimum. The passive COM21 logger remains
active. The phone subsequently connected with diagnostic `app_version=28`
(0.7.20), and the normal firmware received a complete real map with 291
primitives plus 21 labels at map generation 2. Navigation and diagnostic
rejection counters remained zero at that check. Connected map rendering
reported 5319 bytes free internal heap; this small remaining margin is a
constraint for further features. The user confirmed that place names now
appear on the real map, while requesting more detail and a further pixel-level
comparison with the repository simulator and GTA5 driving navigation. Thus the
annotation data path is accepted, but overall visual parity is not accepted.

Local evidence is under `moto_esp32/dist/map-integration/0.3.12/`,
`dist/COM21-maplabels-0.3.12-bench-20261117-111926/` and
`dist/COM21-0.3.12-normal-20261117-112506/`. Release artifacts, flash log and
`validation.json` are under `moto_esp32/dist/release-0.3.12/`.

Firmware/protocol changes and verified release artifacts were synchronized
back to the user's `motonav` workspace after checking the existing files
against the previous delivery. Android source changes remain in the isolated
`motonav-ble-diagnostics` checkout, avoiding the other developer's Android
working files; the signed APK is also available in the user's `motonav/apk/`.

Provider coverage still determines which places have names. The bounded
selection and screen collision rules intentionally omit some labels. This
delivery does not claim complete map coverage, full simulator parity,
improved GPS accuracy or a sustained 40 FPS.
