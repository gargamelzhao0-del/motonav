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

Hardware measurements and signed delivery artifacts will be recorded after
the final device run. Outdoor movement and GPS guidance are not yet accepted.
