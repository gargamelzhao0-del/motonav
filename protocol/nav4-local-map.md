# Local navigation scene (Android 0.7.17 / firmware 0.3.8)

The default view is a fixed local metre camera, never a fit of the complete journey.
Phone and route coordinates remain AMap GCJ-02. Public OSM Shortbread geometry is
converted using AMap's public GPS CoordinateConverter; iterative inverse conversion
selects the WGS84 tile region. The phone caches viewed tiles for at least seven days
in private storage with a 128 MiB bound and an identifying User-Agent. Downloads,
decoding, simplification and triangulation run on one background worker. Failed
requests retry after 30 seconds; navigation remains independent of map availability.

Keep the NAV4 service UUID and CRC32 framing. HELLO type 8 (17 bytes) negotiates
`A,1,NAV4S2`. On `E,NAV4`, Android tries type 5 / NAV4S, type 3 / NAV4P, then type 1 / NAV4. Previous
NAV3/NAV4/NAV4P senders remain supported. Android negotiates ATT MTU 247 before
service discovery, falling back to 20-byte payloads if unsupported.

All integers are little endian. The common header is magic `M4`, total length u16,
type u8, sequence u32, session u32 (13 bytes). Final four bytes are IEEE CRC32.

| Type | Body after header | Total bytes |
| --- | --- | --- |
| 6 local focus | reason u8, point count u8, origin revision u32, camera x/y/heading i16, planned metres/seconds i32, route points x/y i16 | 37 + 4 × count |
| 7 basemap chunk | origin revision u32, total primitives u16, offset u16, count u8, primitives | 26 + 14 × count |
| 9 progressive basemap chunk | origin revision u32, generation u32, total primitives u16, offset u16, count u8, complete u8, primitives | 31 + 14 × count |

Route count 2–64; local route/camera coordinates ±32000 metres. Heading -1 means
unknown, otherwise tenths of a degree. Planned totals may be -1 (unknown). Reasons
reuse NAV4P values. Type 6 is a retained camera/plan, **never a trusted fix**.

A primitive is kind u8, reserved zero u8, and three x/y i16 points in ±2048 metres.
Kinds 0/1/2 are minor/major/highway lines, with the third point zero. Kinds 3/4/5 are
building/water/park triangles. Capacity 192 primitives in NAV4S, 384 in NAV4S2;
at most 19 per chunk. Type 9 requires NAV4S2 negotiation, a positive generation
no greater than INT_MAX and complete equal to zero or one.
Offsets must be contiguous. A complete map publishes atomically, tagged with its
local origin revision. CRC failures, wrong revisions and invalid geometry cannot
publish partial data. Exact retransmissions are idempotent. New origin, STOP,
arrival, disconnect and security loss clear the map as appropriate.

NAV4S2 can replace the same origin with a strictly newer generation. Each scene
still publishes atomically: the previously displayed scene remains visible while
the replacement is staged. A partial scene has complete=0 because more provider
tiles are pending, not because its radio transfer is incomplete. The phone sends
up to 192 primitives from the first useful tile, then a final scene up to 384.
Only one latest replacement is queued while the first scene finishes uploading.
Delayed callbacks cannot supersede newer pending or delivered generations.

Selection retains whole simplified street paths and complete triangulated
footprints, distributes detail across spatial cells, then fills the remaining
budget with nearby objects. Movement over 90 metres or rotation over 45 degrees
rebuilds the visible selection from cached tiles. Origin revision and scene
generation are separate; refreshing detail does not reset the camera or guidance.

NAV type 2 remains the only source of live guidance and trusted vehicle position.
Focus/map packets never extend its expiry. Each map ACK returns to the scheduler;
WAIT/STOP and one-second navigation heartbeats take priority over the next chunk.
Map ACKs are excluded from the diagnostic navigation ACK counter.

The ESP draws provider-neutral primitives locally through the graphics component.
The UI reads map geometry once per origin/generation through a separate snapshot interface;
map arrays are not copied on the UI stack each frame. Legacy overview rendering
exists only for old senders. New local navigation includes heading-up camera
interpolation, limited tilt while moving, turn card, speed, planned/remaining time
and distance, compass and visible OSM attribution. GPS loss hides the vehicle and
shows unknown speed with a retained local view. No fabricated streets, speed limit,
weather, traffic, POIs or Chinese road-name glyphs are introduced.

Geometry is intentionally bounded/generalized. Courtyard/island holes are omitted
per polygon; other polygons in the same MVT multipolygon remain usable. Missing OSM
coverage or an unreachable public tile service is reported as unavailable, not
replaced with the simulator's procedural roads. Hardware FPS and coordinate alignment
must be recorded separately from offline decoder/renderer test results.

The default panel transport is full-color RGB565 with 64-row internal DMA buffers
and the verified 40 MHz QSPI clock. Protocol state and transactional scratch use
external PSRAM through the platform allocation interface; the renderer's hot
geometry and DMA buffers remain in internal RAM. Allocation failure aborts
initialization. Device benchmarks remain removable test-only code.
