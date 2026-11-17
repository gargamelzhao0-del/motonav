# Local navigation scene (base NAV4S2 and negotiated extensions through NAV4S6)

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
weather, traffic or POIs are introduced. Version 0.3.9 adds licensed Chinese fonts
and real SDK road names through the optional details message below.

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

## NAV4S3 navigation HUD (Android 0.7.18 / ESP32 0.3.9)

HELLO type 11 (17 bytes) negotiates `NAV4S3`, including NAV4S2 capabilities.
Older receivers reject it normally; the phone retries HELLO types 8, 5, 3, 1
in order, resetting the probe sequence to 1. Existing GATT UUIDs are unchanged.

Type 10 contains the common 13-byte header, then:

| Offset | Field |
| --- | --- |
| 13 | local origin revision u32 |
| 17 | sequence of the latest accepted NAV type 2, u32 |
| 21 | phone-local ETA minute of day i16: -1 unknown, otherwise 0–1439 |
| 23 | current road speed limit i16: -1 unknown, otherwise 1–300 km/h |
| 25 | current road UTF-8 byte length u8, 0–63 |
| 26 | next road UTF-8 byte length u8, 0–63 |
| 27 | current road bytes followed by next road bytes, without NUL terminators |
| end − 4 | CRC32 |

Length is `31 + current_length + next_length`, maximum 157 bytes. Whole-frame
validation precedes atomic publication. Names must be valid UTF-8, without control
characters or bidi overrides. The sender removes formatting/control characters
and truncates at complete Unicode characters. Missing glyphs remain placeholders.

The phone captures these fields with the trusted instruction and sends them after
the navigation ACK, before the next map chunk. Unsent details expire after two
seconds and are cleared by a new WAIT/STOP frame. Details never renew navigation
or fix timestamps. The receiver requires the referenced navigation sequence and
origin, expires details after three seconds, and hides them on WAIT, source expiry,
STOP, arrival, disconnect or security loss. Exact retries do not extend expiry.
Well-formed optional details that arrive after guidance expiry are acknowledged
and discarded, so an expiring fix cannot unnecessarily disconnect BLE.

Road names come from `NaviInfo.getCurrentRoadName()` and `getNextRoadName()`;
ETA is phone-local wall time plus the accepted remaining route duration.
[SDK reference](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/NaviInfo.html).
Android 0.7.18 sends speed limit -1: it does not substitute a nearby speed camera's
limit for the current road. The reserved validated field permits a later verified
road-limit source without fabricating today's value.

The HUD and benchmark share the same UI implementation. Only removable test code
supplies synthetic navigation fields; production never imports test fixtures.
Anton/Barlow/Noto fonts use pinned OFL sources documented alongside generated C
assets. The navigation view uses the simulator's 700-pixel perspective distance,
speed-dependent scale/tilt, lower vehicle anchor, condensed speed numerals and
bounded route highlights. Scanline shading uses ordered RGB565 dithering and
compile-time ring geometry, without a full-frame canvas or per-frame allocation.

## NAV4S4: measured position independent of guidance (0.7.19 / 0.3.10)

HELLO type 13 (17 bytes) receives `A,1,NAV4S4`. It includes S3 capabilities;
rejection falls back to types 11, 8, 5, 3 and 1. The GATT table is unchanged.

FOCUS2 type 12 retains FOCUS type 6 fields at offsets 13 through 32. It adds
quality u8 at 33 (0 unavailable, 1 approximate, 2 precise), accuracy u16 in
metres at 34, and original measurement age u16 in milliseconds at 36. Route
points start at 38; CRC is last. Length is `42 + 4 * count` (maximum 298).
Unknown quality requires zero accuracy and age. Other qualities require
accuracy 1–10000 and age below 15000; precise quality also requires accuracy
at most 100. The point is the existing x/y in this same atomic coordinate frame.

The phone selects a fresh measured fix separately from instruction readiness.
Its map observation may be approximate even when GPS guidance is unavailable.
The default route focus at the planned origin has quality zero. No location is
invented or snapped to a route to mask measurement uncertainty.

The sender reserves the same three-second delivery budget as NAV, replacing
an observation that would expire in transit with unavailable quality. It sends
bounded position updates between priority NAV/STOP frames; identical points
cannot continuously consume the map-transfer queue. The receiver validates
the entire frame before publishing it and expires position against the original
measurement age. Duplicate ACKs, focus frames and map chunks do not refresh
navigation liveness or permit turn guidance/speed. Marker display additionally
requires a live authenticated link; STOP, arrival and disconnect clear it.

WAIT with an actual position shows a yellow approximate or blue precise dot
and an accuracy label. Valid live guidance retains the white directional
vehicle marker. Old FOCUS frames have no measured-position semantics.

### Firmware 0.3.11: separate historical display context

The wire format and current-position lifetime are unchanged. Quality zero and
source expiry still clear the current position. A separate bounded
`MapPositionHistory` retains the last validated observation, its original age
and its coordinate revision for at most 60 seconds of measurement age.
Unknown focus frames and navigation heartbeats cannot renew this history.
Origin changes, STOP/arrival and session/security/subscription resets clear it.

During a temporary observation gap, the UI may show that historical coordinate
as a gray point with an explicit approximate age. It centers on the retained
coordinate, not on any unmeasured camera position in an unknown focus frame.
The gray style is a graphics choice, not a new wire quality value. History never
makes navigation live, supplies speed, or creates turn instructions. Older apps
without independently measured positions do not produce a historical marker.

## NAV4S5: independent map annotations (0.7.20 / 0.3.12)

HELLO type 15 negotiates `A,1,NAV4S5`; Android falls back through types 13, 11,
8, 5, 3 and 1. GATT UUIDs and the 304-byte frame limit are unchanged.

Type 14 has revision u32 at 13, map generation u32 at 17, total u8 at 21,
offset u8 at 22 and count u8 at 23. Entries start at 24; each is kind u8,
x/y i16, UTF-8 byte length u8 and that many name bytes. CRC32 is last.
Length is 28 + sum(6 + name length). Each chunk carries at most four entries;
a set has at most 24. Each name has 1–63 bytes, validated with the same UTF-8
and control-character rules as HUD names. Coordinates are local metres in
±2048, sharing the route origin. Kinds are road=0, place=1, landmark=2,
fuel=3, parking=4, food=5, medical=6, transit=7, park=8.

Only negotiated, authenticated, in-sequence packets matching the currently
published geometry revision/generation are accepted. All entries in a chunk
validate before staging. Offsets must be contiguous; publication is atomic
after the complete set arrives. An empty set explicitly clears old names.
Exact retransmissions are acknowledged without appending. A new geometry
generation retains old names from the same origin until its annotation set
arrives; new origins, STOP, arrival and session/security/subscription resets
clear both. Labels never supply or renew guidance, position or speed.

Android finishes each map's geometry and annotations before the next queued
generation, yielding between chunks to priority navigation/position updates.
Label ACKs are excluded from navigation diagnostics. Older receivers receive
geometry only. STOP cancels queued geometry and annotations.

The phone reads names, points and categories from the existing Shortbread
tiles, including `street_labels`, `place_labels`, `pois`, `public_transport`
and water labels. Names prefer Chinese when supplied, then local name,
English name or road reference. A known unnamed service may display its
recorded category, such as parking, without inventing a business name.
Selection is bounded, removes duplicates and spreads detail across spatial
cells. Source: [Shortbread schema](https://shortbread-tiles.org/schema/1.0/).
No additional map service, bulk download or POI query is introduced.

The independent UI annotation layer projects the same local coordinates as
the map, uses a fixed eight-label pool and avoids the route, position marker,
HUD, other labels and the circular border. Widths are measured on generation
change; text masks are reused during movement. It never substitutes simulator
POIs. Missing provider coverage remains missing, and unsupported font glyphs
remain placeholders. See [validation](map-labels-0.3.12-validation.md).


## NAV4S6 real area and waterway categories (Android 0.7.25 / ESP32 0.3.16)

HELLO type 17 (17 bytes) negotiates `A,1,NAV4S6`, including all NAV4S5
capabilities. On `E,NAV4`, the sender resets the probe sequence and tries
15, 13, 11, 8, 5, 3, 1 in order. No GATT UUID or service-table change is needed.

Only a session that negotiated NAV4S6 may carry these additional geometry kinds:

| Kind | Representation | Source meaning |
| --- | --- | --- |
| 6 | Triangle | Residential, commercial, industrial and other mapped land use |
| 7 | Triangle | School, hospital, parking, construction and other mapped sites |
| 8 | Line, third point (0, 0) | River, canal, stream or ditch centerline |

The original kinds 0–5, chunk layouts, coordinate bounds, 304-byte maximum
frame, 384-primitive scene and 24-label limits remain unchanged. Unknown kind 9
or a nonzero line tail rejects the entire chunk before modifying staging data.
Disconnect clears the new capability. The App rebuilds selection on a capability
change and refuses to send a cached scene with new kinds to a legacy receiver.

The renderer orders land use, water area, green area, sites, waterway lines,
buildings and roads before the independent route/player overlays. Waterway
width is a cartographic stroke, not a claimed surveyed riverbank width. Newly
supported green categories reuse kind 5. Subway geometry is not relabeled as a
driveable road. Whole-object selection keeps a bounded background budget and
reuses spare capacity without splitting polygon footprints.

Source counts cover the full downloaded tile region and can overlap; selected
geometry covers the current local view. They are not a retention percentage.
[Luxiao validation](map-surfaces-0.3.16-validation.md) records actual source
omissions, fixed-scene comparisons and hardware performance separately.


## NAV4S7 attributed names (Android 0.7.26 / ESP32 0.3.17)

HELLO type `19` (17 bytes, same CRC/session/sequence framing) replies
`A,<seq>,NAV4S7`. It includes S6 surfaces and all prior capabilities. The App
falls back through 17, 15, 13, 11, 8, 5, 3, 1 on an explicit legacy rejection.
GATT UUIDs and MTU negotiation are unchanged.

Name packet type `18` uses the S5 type 14 fields through byte 23: route revision
at 13, scene generation at 17, total/offset/count at 21/22/23. Byte 24 is a
source mask: 1 OpenStreetMap, 2 AMap, 3 both. Entries begin at byte 25 and retain
the kind/int16 x/int16 y/uint8 UTF-8 length/name representation. Maximum three
entries per chunk, 24 per scene; total length is `29 + sum(6 + nameBytes)`
including CRC, never over 304 bytes. No empty names or invented positions.

All chunks must have the same revision, geometry generation, total and source.
Names and source publish atomically only after the final chunk. Invalid chunks
do not mutate the accepted sequence or published labels. Type 18 requires S7;
type 14 remains accepted as OSM source 1. STOP and disconnect clear capability
and labels. The App does not send AMap-sourced names to S6 or older firmware.

OSM geometry always retains its credit. The additional source bit controls the
AMap credit only when a complete attributed label set has been received.
Independent tile and nearby-search workers publish through one scene composer;
all scene replacements preserve monotonically increasing generations.

See [validation and limits](map-place-names-0.3.17-validation.md).
