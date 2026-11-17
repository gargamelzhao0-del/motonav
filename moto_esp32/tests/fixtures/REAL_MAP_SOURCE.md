The `nav4-real-map.frames` visual test uses a bounded extract of real OpenStreetMap
streets, buildings, water and parks around central London. The route is a selected
street segment and all guidance/position samples are **test inputs**, not a recorded
journey. This fixture must never be sent as real phone navigation.

Source: https://vector.openstreetmap.org/shortbread_v1/14/8186/5448.mvt
Retrieved 2026-11-16. Original tile SHA256:
`ab9c6c7141397dcdf316eb140a576716515c8f60618ab6c8b1d59bb5549ee85f`.

Map data © OpenStreetMap contributors, available under the Open Database License:
https://www.openstreetmap.org/copyright and https://opendatacommons.org/licenses/odbl/1-0/.
The derived map geometry in this fixture is provided under the same database license.
`BasemapTests` can regenerate the extract from a locally cached copy of this tile;
ordinary CI is offline and uses the checked-in extract. No bulk map download.

The 0.3.8 extract is centered at extent coordinates (800, 3000), uses whole-object
selection and contains 383 primitives: 109 minor-road segments, 83 major-road
segments, 161 building triangles and 30 park triangles. Run
`python tests/generate_real_map_fixture.py` after regenerating the Java frames to
update the removable device benchmark geometry. All motion remains synthetic.

The 0.3.12 extract adds up to 24 real names/POIs from the same tile. Selection
uses the test camera's forward view and spatial cells; the 383 geometry
primitives are unchanged. The data remains ODbL and test-only.

The 0.3.13 extract uses the camera-aware selector with the fixture's LIVE
36 km/h heading/position: 384 primitives (44 minor-road segments, 101 major-road
segments, 160 building triangles, 47 water triangles, 32 park triangles), and
24 labels. The source tile and synthetic route are unchanged. Building holes
use even-odd tessellation, and context has independent budgets. The animated
device benchmark keeps its established scale 0.88/pitch 50 degrees for workload
continuity; its camera moves through this fixed extract, without fetching new
tiles. The old/new extract benchmarks are different workloads and their FPS
must not be presented as a pure renderer speedup comparison.
