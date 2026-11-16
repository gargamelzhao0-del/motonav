# Simulator style 0.3.9 / Android 0.7.18 validation

The user rejected 0.7.17 / 0.3.8 appearance as substantially different from the
repository simulator. The reference is `sim/index.html`, not the older firmware
benchmark. A complete 274-primitive transfer in the preceding run did not mean
that the visual design was accepted.

This revision implements its perspective formula, Anton/Barlow typography,
Chinese labels, dark gradients, compact turn card, local route highlights and
road-name/ETA fields. Geometry remains actual provider data; the simulator's
procedural city is not a replacement for missing real map data.

Local checks: Android pure Java and boundary-double suites passed, firmware
host driver/protocol/raster suites passed, and 12 LVGL render/lifecycle tests
passed. New Java-to-C++ fixtures cover Chinese UTF-8, malformed payloads,
reference binding, capability fallback and independent data expiry. Shared
production HUD rendering is exercised in the removable device benchmark.

The first device candidate exposed a main-task stack overflow during UI startup.
COM21 was restored to the verified 0.3.8 application immediately. A runtime
2.8 KiB ring-geometry initializer was replaced by a compile-time constant table;
the main task now reserves 6144 bytes instead of the previous 3584. This first
candidate is not a delivery artifact.

Hardware retest, final FPS/memory figures, signed APK and exact delivery hashes
are pending. No outdoor-navigation or visual acceptance is claimed here.
