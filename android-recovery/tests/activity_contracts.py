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
assert 'map.onLowMemory()' not in source
assert 'public void overview(){dial.setRound(false);placeLogo();}' in source
assert 'x*x+y*y>r*r' in dial
assert 'MyLocationStyle.ERROR_CODE' in source
assert 'if(error!=0)' in source
assert 'if(userRetry)' in source and 'centerOnFix(currentFix)' in source
assert 'else if(locationProblem!=null)' in source
receive = source.split('private void handleLocation(',1)[1].split('private void locationPermission(',1)[0]
assert receive.index('if(!allowed)') < receive.index('currentFix=new')
assert receive.index('if(!systemLocationEnabled())') < receive.index('currentFix=new')
assert 'MapStartupPolicy.usableSnapshot(allowed,systemLocationEnabled()' in source
print('PASS: 23 static activity/visual wiring guards (not runtime validation)')
