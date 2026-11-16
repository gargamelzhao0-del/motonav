"""Regenerate the test-only device geometry from the checked-in Java wire fixture."""
from pathlib import Path
import struct

root = Path(__file__).resolve().parent
features, route = [], []
pose=(0,0,0)
for row in (root / 'fixtures/nav4-real-map.frames').read_text(encoding='utf-8').splitlines():
    frame = bytes.fromhex(row)
    if frame[4] == 6:
        route = [struct.unpack_from('<hh', frame, 33 + 4*i) for i in range(frame[14])]
        pose=struct.unpack_from('<hhh',frame,19)
    elif frame[4] == 9:
        for i in range(frame[25]):
            kind, reserved, *points = struct.unpack_from('<BB6h', frame, 27 + 14*i)
            assert reserved == 0
            features.append((kind, points))
assert 300 <= len(features) <= 384 and len(route) >= 2
lines = ['// Map data (c) OpenStreetMap contributors, ODbL. See tests/fixtures/REAL_MAP_SOURCE.md.',
         '// Generated from nav4-real-map.frames; test-only camera motion.', '#pragma once',
         'static constexpr motonav::MapFeature RealMapFeatures[]{']
for kind, p in features:
    lines.append('    {%d,{%d,%d},{%d,%d},{%d,%d}},' % (kind, *p))
lines.extend(['};', 'static constexpr motonav::MapPoint RealMapRoute[]{'])
lines.extend('    {%d,%d},' % p for p in route)
lines.extend(['};', f'static constexpr motonav::MapPoint RealMapCamera{{{pose[0]},{pose[1]}}};',
              f'static constexpr float RealMapHeading={pose[2]*0.001745329252:.9f}f;', ''])
(root / 'device/motonav_bench/real_map_fixture.h').write_text('\n'.join(lines), encoding='utf-8')
print(f'Generated {len(features)} real primitives and {len(route)} test route points')
