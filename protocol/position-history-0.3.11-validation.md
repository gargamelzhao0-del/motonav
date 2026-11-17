# Position continuity 0.3.11 / Android 0.7.19

On 2026-11-17 the user reported that the location dot appeared and disappeared
periodically after installing 0.7.19 with firmware 0.3.10. Passive capture on
COM21 reproduced the problem without resetting the running device.

The capture `dist/COM21-position-intermittent-20261117-100512/` showed secure
NAV4, a complete 287-primitive map and no rejected navigation frames. Position
quality alternated between approximate (42 m) and unavailable. New measured
positions arrived about every 25 seconds; the current-position limit was 15
seconds. Phone diagnostics reported app version code 27, `gps_weak`, and an
unavailable map fix during the gaps. The missing marker was the presentation
of intermittent measurements, not evidence of missing map packets.

The repair keeps current-position validation unchanged and adds separate
historical display context. A gray point, labeled with the approximate
measurement age, bridges short gaps. The original measured coordinate and
revision are retained; unknown camera focus values cannot move the point.
History ends at 60 seconds of source age or on route-origin/session changes,
STOP, arrival, loss of security or subscription. It cannot become speed or turn
guidance. No new phone APK, wire capability or GATT service is needed.

Full local firmware host checks and 12 LVGL render/lifecycle checks passed.
Coverage includes a two-minute replay of 25-second fix intervals, exact source
age limits, clock rollback/overflow boundaries, repeated unknown frames,
coordinate-revision changes, session resets, the visible gray point, age-label
updates without moving the map, recovery, and removal. The point-ring offsets
are cached and off-strip marker drawing is skipped; no FPS gain is claimed.

## Delivered firmware

The normal 0.3.11 image was built from
`396477c6e83c50b9fff21d42d1ae630057e1dfa4`. Production verification passed with
device tests excluded. The image retains RGB565, 64-row display buffers and
40 MHz QSPI. Android 0.7.19 remains compatible and does not need reinstalling.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `motonav.bin` | 2435952 | `3daa366cb5384a5cf6be6f917b2a0ebf7959c90a18b6315fa9b3f414be6988bd` |
| `motonav.elf` | 15501860 | `53144b5eaf62a051aabfd9b82faef116c8097d2e446acd16a5b1105442406d5b` |
| `source-396477c.zip` | 1336831 | `3ed7c7bf654987b1d3d5388deb3a1ca5ea0b813a5cd8290216a182556e1add90` |

The normal application was flashed on COM21 at `0x10000` on 2026-11-17 around
10:21 Asia/Shanghai. Esptool verified the data hash; the boot log identifies
0.3.11. Bootloader, partition table and NVS were preserved. Source and release
artifacts were copied to the user workspace `motonav/` after checking for
concurrent edits, then verified byte-for-byte. The delivery is under
`moto_esp32/dist/release-0.3.11/`.

## Live acceptance

The passive capture `dist/COM21-0.3.11-normal-20261117-102114/` records repeated
current-position expiry and recovery while retaining separate historical
coordinates. Selected device timestamps (milliseconds since boot):

| Device time | Current quality | History quality | History age (ms) |
| ---: | ---: | ---: | ---: |
| 7101 | 1 | 1 | 3704 |
| 26228 | 0 | 1 | 15785 |
| 36337 | 1 | 1 | 3220 |
| 52086 | 0 | 1 | 15960 |
| 61087 | 1 | 1 | 3822 |
| 219841 | 0 | 1 | 16077 |
| 228835 | 0 | 1 | 25071 |
| 229959 | 1 | 1 | 4357 |

The current measurement still expires normally. Historical age advances
during unavailable frames and a new measured position restores current
quality. The user confirmed: "保持可见，正常颜色与灰色切换".

In the inspected capture the complete map contained 287 primitives, navigation
frame rejections remained zero, and no panic, stack overflow or watchdog fault
was recorded. The diagnostic stream had two rejected writes/ATT `0x11`
responses and subsequently continued forwarding; this is not a claim of zero
diagnostic loss. Phone version code 27 continued reporting `gps_weak` and the
navigation state remained WAITING. This acceptance covers marker continuity,
not outdoor guidance readiness or improved GPS accuracy.

CI for the source commit is tracked by
[run 44002143335](https://github.com/gargamelzhao0-del/motonav/actions/runs/44002143335).
Both `firmware` and `verify` completed successfully. This includes firmware
host checks, the production source export without device tests, the optional
benchmark build, LVGL checks, Android compilation/lint and update packaging
checks. No new Android release was published for this firmware-only change.

The existing full-color 40 FPS performance gap remains a separate open item;
this change has no new on-device frame-rate measurement.
