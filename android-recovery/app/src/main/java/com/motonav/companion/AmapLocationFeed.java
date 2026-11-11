package com.motonav.companion;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.LocationSource;

/** Owns one continuous foreground request independently of the map's blue dot. */
final class AmapLocationFeed implements LocationSource {
    private final Context context;
    private final AMapLocationListener receiver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AMapLocationClient client;
    private OnLocationChangedListener mapListener;
    private Location shown;
    private long lastCallbackMs = -1;

    AmapLocationFeed(Context context, AMapLocationListener receiver) {
        this.context = context.getApplicationContext();
        this.receiver = receiver;
    }

    boolean maintain() {
        long now = SystemClock.elapsedRealtime();
        if (!MapStartupPolicy.locationNeedsStart(now, lastCallbackMs)) return client != null;
        stopClient();
        lastCallbackMs = now;
        try {
            AMapLocationClient next = new AMapLocationClient(context);
            client = next;
            AMapLocationClientOption options = new AMapLocationClientOption();
            options.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            options.setOnceLocation(false);
            options.setInterval(1000);
            options.setLocationCacheEnable(false);
            options.setNeedAddress(false);
            options.setMockEnable(false);
            next.setLocationOption(options);
            next.setLocationListener(location -> main.post(() -> {
                if (client != next) return;
                lastCallbackMs = SystemClock.elapsedRealtime();
                receiver.onLocationChanged(location);
            }));
            next.startLocation();
            return true;
        } catch (Exception | LinkageError e) {
            stopClient();
            return false;
        }
    }

    void stop() {
        shown = null;
        lastCallbackMs = -1;
        stopClient();
    }

    private void stopClient() {
        AMapLocationClient previous = client;
        client = null;
        if (previous == null) return;
        try { previous.stopLocation(); } catch (RuntimeException | LinkageError ignored) {}
        try { previous.onDestroy(); } catch (RuntimeException | LinkageError ignored) {}
    }

    void show(Location location) {
        if (shown == location) return;
        shown = location;
        if (location != null && mapListener != null) mapListener.onLocationChanged(location);
    }

    @Override public void activate(OnLocationChangedListener listener) {
        mapListener = listener;
        if (shown != null && listener != null) listener.onLocationChanged(shown);
    }

    @Override public void deactivate() {
        mapListener = null;
    }
}
