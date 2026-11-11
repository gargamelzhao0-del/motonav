"""Static wiring guards, NOT Android/SDK lifecycle tests."""
from pathlib import Path
source = Path('app/src/main/java/com/motonav/companion/AmapActivity.java').read_text()
assert 'MyLocationStyle.LOCATION_TYPE_FOLLOW' in source
assert 'MyLocationStyle.LOCATION_TYPE_LOCATE' not in source
negative = source.split('.setNegativeButton(', 1)[1].split('.setPositiveButton(', 1)[0]
assert 'saved.consent(false)' in negative
assert 'dispose()' in negative
clear = source.split('private void clearKey(){', 1)[1].split('private void locationPermission', 1)[0]
assert clear.index('dispose()') < clear.index('saved.clear()')
assert clear.index('keys.clear()') < clear.index('saved.clear()')
assert 'TextureMapView' in source
assert 'LOGO_POSITION_BOTTOM_CENTER' in source
assert 'setLogoBottomMargin' in source
assert 'setLogoSize' not in source
dial = Path('app/src/main/java/com/motonav/companion/MapDial.java').read_text()
assert 'setClipToOutline(true)' in dial
assert 'toggleShape()' in dial
assert 'requestDisallowInterceptTouchEvent' in dial
print('PASS: 13 static activity/visual wiring guards (not runtime validation)')
