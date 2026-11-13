package com.motonav.companion;

import android.os.Bundle;
import com.amap.api.location.AMapLocation;

/** Preserve explicit origins and distinguish highly trusted stationary network rechecks from caches. */
final class AmapLocationSample {
    private AmapLocationSample() { }

    static int originalSource(AMapLocation location) {
        Bundle extras = location.getExtras();
        return extras == null ? 0 : extras.getInt(AMapLocation.LBS_REAL_LOCATION_TYPE, 0);
    }
    static int source(AMapLocation location) {
        return MapFixTracker.resolveSource(location.getLocationType(), originalSource(location),
                location.getTrustedLevel() == AMapLocation.TRUSTED_LEVEL_HIGH);
    }
}
