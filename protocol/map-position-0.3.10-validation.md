# Independent map position 0.3.10 / Android 0.7.19

The user initially confirmed that 0.7.18 / 0.3.9 showed the real map and
navigation information, then clarified that the vehicle/location marker was
missing and the display remained slow. This is not full navigation acceptance.

The COM21 capture showed a secure NAV4 connection, 273 committed map primitives,
no rejected navigation frames, and WAIT state. Bluetooth-forwarded phone logs
then reported `gps_weak`, map fixes approximately 0.4 seconds old, no trusted
navigation fix, and recent instruction callbacks. The previous UI hid the
position marker whenever turn guidance was unavailable, despite fresh phone
map observations. The same capture had no panic or watchdog fault.

NAV4S4 now carries independently aged measured positions inside atomic local
focus frames. Approximate and precise points have distinct markers; they never
create navigation speed or turn instructions. Position expiry, unknown data,
disconnect, STOP and old-capability fallback are covered by tests. No mocked
position is introduced into the production path.

Local validation: Android pure Java and SDK boundary-double suites passed;
118 Bluetooth callback checks passed; complete firmware host checks passed;
12 LVGL lifecycle/render tests passed, including visible WAIT markers and
pixel-identical marker removal. The live navigation reference image is
byte-identical before/after the painter-order and strip-culling optimization.

## Source and delivery

Source commit: `76bdb4025f0bb86dca3536c222563b9a9474d5b7` on
`codex/ble-diagnostics`; Android tag `android-v0.7.19`, version code 27.
[Source CI](https://github.com/gargamelzhao0-del/motonav/actions/runs/43865450495)
passed both Android verification and firmware jobs.
[Signed release](https://github.com/gargamelzhao0-del/motonav/actions/runs/43865461529)
passed and published 0.7.19 on 2026-11-16 at 20:21:06 Shanghai time.

The downloaded [0.7.19 APK](https://github.com/gargamelzhao0-del/motonav/releases/download/android-v0.7.19/MotoNav-0.7.19.apk)
is 105771805 bytes, SHA256
`20bdde2810ce37faf289a36aff8ef51de27735132f67308daea57415437ed379`.
Its release signer matches the stable checked-in certificate SHA256
`af9e2f4cb0997fe77ae884e9d7797a7c6b933c1bfdebc3993386ae418aeacde1`.

Normal firmware is 2434832 bytes, SHA256
`8d1393baa44c9b380da1727176516c7336f3afad3b7a178c0d535abd716b7835`;
ELF SHA256
`49faebb446f0ad17294f26f90a7bf8c9057909a702c674ff88fb86c92fba3586`.
The source export without tests, `source-76bdb40.zip`, has SHA256
`3ae2640b545601ba89092dc18095ae1711589f3dd61aa760e200cf190f7fd333`.
Ignored delivery files are in `moto_esp32/dist/release-0.3.10/`.

COM21 received only this normal app at `0x10000`, with esptool hash verification,
on 2026-11-16 at 20:27 Shanghai time. Bootloader, partition table and NVS were
preserved. Boot reports `0.3.10`, `device_tests=0`, `ble_diagnostics=1`,
`DIAG1_RX=31`, RGB565, 64 rows and successful BLE advertising. Initial idle
internal heap is 27823 bytes and BLE stack minimum remaining 5532 bytes. The
observed normal boot has no panic, stack overflow or watchdog failure.

## Frame rate remains below the requirement

All runs use the same 383-primitive real-map animation and complete HUD, QSPI
40 MHz, CPU 240 MHz, and BLE advertising. These are marked DISPLAY TEST runs,
not real GPS movement or a connected-radio endurance test.

| Variant | Minimum / average FPS | Average render time | Measured interval |
| --- | --- | --- | --- |
| 64 rows, detailed draw instrumentation | 25 / 25 | 34388 us | 40594 ms |
| 64 rows, draw instrumentation off | 25 / 26 | 33793 us | 40536 ms |
| 32 rows, draw instrumentation off | 22 / 22 | 39320 us | 40545 ms |

Captures respectively:
`dist/COM21-0.3.10-bench-20261116-201111/`,
`dist/COM21-0.3.10-no-draw-profile-20261116-201836/`, and
`dist/COM21-0.3.10-rows32-20261116-202322/`.

The sorted/cullable geometry list reduced the measured map-draw work, but
**did not establish a meaningful overall FPS improvement** over 0.3.9's
24/26 FPS. Removing dispatch instrumentation did not resolve the gap.
More internal memory from 32-row buffers did not compensate
for extra strip processing; that configuration is not delivered.

Normal delivery retains 64 rows. The matching non-profiled benchmark image has
SHA256 `8cb91a06a925cf1cbeb4e457c860a8b4c6ee2a33a2603d4d60bcf20e2372d2b0`.
`-DrawTaskProfile` now explicitly enables intrusive per-task profiling for
diagnosis; normal builds exclude all benchmark code. The 40 FPS goal and the
reported choppiness remain open until real navigation is checked and the
remaining full-frame rendering cost is reduced. No higher display clock or
reduced-color default is introduced.

## Phone acceptance pending

The user was asked to install 0.7.19, connect the normal 0.3.10 firmware, enable
Bluetooth forwarding and verify the location dot and position-following motion.
At delivery the phone had not yet reconnected to the fresh normal capture.
Local tests confirm the new logic and rendering; they do not establish real
phone visual acceptance, outdoor position accuracy or live road guidance.
