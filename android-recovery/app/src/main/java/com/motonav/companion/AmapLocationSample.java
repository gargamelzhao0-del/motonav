package com.motonav.companion;

import android.os.Bundle;
import com.amap.api.location.AMapLocation;

/** Resolve a stationary network recheck only when the SDK supplies its original source and freshness. */
final class AmapLocationSample {
    private AmapLocationSample() { }

    static int source(AMapLocation location) {
        Bundle extras = location.getExtras();
        int original = extras == null ? 0 : extras.getInt(AMapLocation.LBS_REAL_LOCATION_TYPE, 0);
        return MapFixTracker.resolveSource(location.getLocationType(), original,
                location.getTrustedLevel() == AMapLocation.TRUSTED_LEVEL_HIGH);
    }
}
