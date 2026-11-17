"""Regenerate the test-only device geometry from the checked-in Java wire fixture."""
from pathlib import Path
import struct
import json
import argparse
import zlib

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--scene', choices=('westminster', 'luxiao'), default='westminster')
args = parser.parse_args()
stem = 'luxiao' if args.scene == 'luxiao' else 'real-map'
header = 'luxiao_map_fixture.h' if args.scene == 'luxiao' else 'real_map_fixture.h'
source_note = 'LUXIAO_MAP_SOURCE.md' if args.scene == 'luxiao' else 'REAL_MAP_SOURCE.md'
features, route = [], []
labels = []
pose=(0,0,0)
for row in (root / f'fixtures/nav4-{stem}.frames').read_text(encoding='utf-8').splitlines():
    frame = bytes.fromhex(row)
    assert zlib.crc32(frame[:-4]) == struct.unpack_from('<I', frame, len(frame)-4)[0]
    if frame[4] == 6:
        route = [struct.unpack_from('<hh', frame, 33 + 4*i) for i in range(frame[14])]
        pose=struct.unpack_from('<hhh',frame,19)
    elif frame[4] == 9:
        for i in range(frame[25]):
            kind, reserved, *points = struct.unpack_from('<BB6h', frame, 27 + 14*i)
            assert reserved == 0
            features.append((kind, points))
    elif frame[4] == 14:
        at = 24
        for i in range(frame[23]):
            kind, x, y, length = struct.unpack_from('<BhhB', frame, at)
            name = frame[at+6:at+6+length].decode('utf-8')
            labels.append((kind, x, y, name))
            at += 6 + length
assert 300 <= len(features) <= 384 and len(route) >= 2
lines = [f'// Map data (c) OpenStreetMap contributors, ODbL. See tests/fixtures/{source_note}.',
         f'// Generated from nav4-{stem}.frames; test-only camera motion.', '#pragma once',
         'static constexpr motonav::MapFeature RealMapFeatures[]{']
for kind, p in features:
    lines.append('    {%d,{%d,%d},{%d,%d},{%d,%d}},' % (kind, *p))
lines.extend(['};', 'static constexpr motonav::MapPoint RealMapRoute[]{'])
lines.extend('    {%d,%d},' % p for p in route)
lines.extend(['};', f'static constexpr motonav::MapPoint RealMapCamera{{{pose[0]},{pose[1]}}};',
              f'static constexpr float RealMapHeading={pose[2]*0.001745329252:.9f}f;', ''])
assert len(labels) <= 24
lines.append(f'static constexpr motonav::MapLabels RealMapLabels{{1,1,{len(labels)},{{')
for kind, x, y, name in labels:
    lines.append('    {%d,{%d,%d},%s},' % (kind, x, y, json.dumps(name, ensure_ascii=False)))
lines.append('}};')
(root / 'device/motonav_bench' / header).write_text('\n'.join(lines), encoding='utf-8')
print(f'Generated {len(features)} real primitives and {len(route)} test route points')
