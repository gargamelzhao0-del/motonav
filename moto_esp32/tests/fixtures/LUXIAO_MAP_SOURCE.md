# Luxiao station public map fixture

`nav4-luxiao.frames` contains a fixed public-place extract around 陆肖地铁站,
Chengdu. It is not a recorded phone position or journey. Geometry and labels
come from OpenStreetMap Shortbread; the route follows the source 吉龙路 line.
Speed (36 km/h), turn instruction, ETA, road HUD text and camera motion are
synthetic test inputs. Never send this fixture as real phone navigation.

Two viewed-region tiles, retrieved 2026-11-17:

| Tile URL | SHA256 |
| --- | --- |
| https://vector.openstreetmap.org/shortbread_v1/14/12929/6731.mvt | `4d87cdd3f3d8cb4e8fae349d594fd51d732ccb764a87b2561947e788b31913b1` |
| https://vector.openstreetmap.org/shortbread_v1/14/12929/6730.mvt | `b932492b846b7989e9548553e670cafbb5ace2fba5e4b13d1b0070ac52e9560d` |

The test origin is WGS84 30.54175, 104.09586944444445. Projection uses identity
conversion for the fixed WGS84 test; it does not exercise the phone's AMap
CoordinateConverter. The local test pose is (6, 31) m, bearing 89.2 degrees.
The Java 0.7.25 LocalMapBuilder produces 320 primitives and 12 labels with
NAV4S6 enabled. The original 0.7.24 pipeline produces 199 primitives and the
same 12 labels for this pose/view. Both counts are below the 384/24 limits.

Map data © OpenStreetMap contributors, under the Open Database License:
https://www.openstreetmap.org/copyright and
https://opendatacommons.org/licenses/odbl/1-0/.
The derived fixture geometry is provided under the same database license.
CI uses checked-in frames offline. No bulk/offline map download is required.

Regenerate the removable benchmark header from the checked-in frames:

```powershell
python tests/generate_real_map_fixture.py --scene luxiao
./tools/build.ps1 -MapBench -LuxiaoScene -BenchSeconds 43 -BuildDirectory build-bench
```

The benchmark traverses the fixed route at 36 km/h, at scale 0.88 and pitch
35 degrees. It does not load new tiles during movement. Production builds
exclude this fixture and all on-device test code.
