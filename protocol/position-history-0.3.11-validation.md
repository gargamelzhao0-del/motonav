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

Final firmware hashes, CI, device installation and live acceptance are pending.
The existing full-color 40 FPS performance gap remains a separate open item.
