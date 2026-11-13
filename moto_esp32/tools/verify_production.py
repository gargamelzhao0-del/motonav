"""Verify that a completed ESP-IDF build excludes optional device test code."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('build_directory', type=Path)
args = parser.parse_args()
build = args.build_directory.resolve()
assert (build / 'motonav.bin').is_file(), 'Firmware image is missing'
description = json.loads((build / 'project_description.json').read_text(encoding='utf-8'))
assert not any('motonav_bench' in name for name in description['build_components']), 'Test component linked'
entries = json.loads((build / 'compile_commands.json').read_text(encoding='utf-8'))
for entry in entries:
    source = entry['file'].replace('\\', '/')
    assert '/tests/device/' not in source, f'Test source compiled: {source}'
    assert not source.endswith(('/map_fixture.cpp', '/display_benchmark.cpp')), f'Simulation compiled: {source}'
    if source.endswith('/main/app/navigation_app.cpp'):
        assert 'MOTONAV_DEVICE_TESTS=0' in entry['command'], 'Application enables test integration'
cache = (build / 'CMakeCache.txt').read_text(encoding='utf-8')
for key in ('MOTONAV_DEVICE_TESTS', 'MOTONAV_AUTORUN_BENCH', 'MOTONAV_AUTORUN_MAP_BENCH'):
    assert f'{key}:BOOL=OFF' in cache, f'{key} must explicitly be OFF'
print(f'PASS: production image excludes device tests and simulated map: {build / "motonav.bin"}')
