"""Fail when a production component imports application/test/hardware internals.

These dependency checks complement compilation; they keep future additions from
reintroducing the driver -> application or production -> test dependencies.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
allowed = {
    'motonav_core': {'core'},
    'motonav_ui': {'core', 'ui', 'graphics'},
    'motonav_graphics': {'graphics'},
    'motonav_display': {'drivers'},
    'motonav_touch': {'drivers'},
    'motonav_ble': {'drivers'},
    'motonav_platform': {'drivers'},
}
errors = []
for owner, namespaces in allowed.items():
    for path in (root / 'components' / owner).rglob('*'):
        if path.suffix not in {'.h', '.cpp'}:
            continue
        for header in re.findall(r'^\s*#\s*include\s*[<"]([^">]+)[">]', path.read_text(encoding='utf-8'), re.M):
            if header.startswith('motonav/') and header.split('/')[1] not in namespaces:
                errors.append(f'{path.relative_to(root)} imports {header}')
            if any(part in header.split('/') for part in ('tests', 'testing', 'main', 'app')):
                errors.append(f'{path.relative_to(root)} imports application/test internals: {header}')
            if owner in {'motonav_core', 'motonav_ui'} and (
                    header.startswith(('esp_', 'bsp/', 'freertos/', 'host/', 'nimble/')) or
                    (owner == 'motonav_core' and ('lvgl' in header or header.startswith('lv_')))):
                errors.append(f'{path.relative_to(root)} imports hardware/graphics runtime: {header}')
for path in (root / 'main').rglob('*'):
    if path.suffix in {'.h', '.cpp'}:
        headers = re.findall(r'^\s*#\s*include\s*[<"]([^">]+)[">]', path.read_text(encoding='utf-8'), re.M)
        for header in headers:
            if header.startswith(('bsp/', 'host/', 'nimble/', 'esp_lcd', 'driver/', 'nvs_', 'esp_lv_adapter')):
                errors.append(f'{path.relative_to(root)} bypasses driver interface: {header}')
if errors:
    raise SystemExit('\n'.join(errors))
print('PASS: production dependency boundaries and application driver interfaces')
