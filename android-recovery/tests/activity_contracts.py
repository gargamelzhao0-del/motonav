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
feed = Path('app/src/main/java/com/motonav/companion/AmapLocationFeed.java').read_text()
assert 'setLocationSource(locationFeed)' in source
assert 'AMapLocationMode.Hight_Accuracy' in feed and 'setOnceLocation(false)' in feed
assert 'setLocationCacheEnable(false)' in feed
assert 'if (client != next) return;' in feed
pause = source.split('@Override protected void onPause()', 1)[1].split('@Override protected void onDestroy()', 1)[0]
assert 'stopLocation();' in pause
receive = source.split('private void handleLocation(', 1)[1].split('private void renderLocation(', 1)[0]
assert receive.index('systemLocationEnabled()') < receive.index('fixes.offer(')
assert 'location.getErrorCode()' in receive
assert 'hasPrecisePermission(), systemLocationEnabled()' in source
print('PASS: static map, location lifecycle, and privacy guards (not runtime validation)')

assert 'journey.pause(' not in pause and 'journey.close(' not in pause
assert 'navigationService.detach(navigationView)' in pause
service = (Path('app/src/main/java/com/motonav/companion/NavigationService.java')).read_text()
assert 'startForeground(' in service and 'START_NOT_STICKY' in service
assert 'new AmapLocationFeed(this, this::receiveLocation)' in service
assert 'if (!navigationAllowed())' in service
assert 'setElapsedRealtimeNanos(time)' in service
assert 'feed.stop(); fixes.clear();' in service
assert 'new JourneyController(this, this)' in service
assert 'navigationService.revoke()' in source
print('PASS: service ownership, background location and explicit-revocation wiring')
