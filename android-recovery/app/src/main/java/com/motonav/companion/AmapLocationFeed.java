package com.motonav.companion;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.LocationSource;
import java.util.LinkedHashSet;

/** Map and navigation subscribe to one request; leaving either screen must not stop the other. */
final class AmapLocationFeed implements LocationSource {
    private static Shared shared;
    private final Shared request;
    private final AMapLocationListener receiver;
    private OnLocationChangedListener mapListener;
    private Location shown;

    AmapLocationFeed(Context context, AMapLocationListener receiver) {
        if (shared == null) shared = new Shared(context.getApplicationContext());
        request = shared;
        this.receiver = receiver;
    }

    boolean maintain() { return maintain(false); }
    boolean maintain(boolean manual) {
        boolean added = request.subscribers.add(this);
        boolean running = request.maintain(manual);
        // Keep the original measurement time when attaching to a running request.
        if (added && request.latest != null) receiver.onLocationChanged(request.latest.clone());
        return running;
    }

    void stop() {
        shown = null;
        if (request.subscribers.remove(this) && request.subscribers.isEmpty()) request.stop();
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

    @Override public void deactivate() { mapListener = null; }

    private static final class Shared {
        private final Context context;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final LinkedHashSet<AmapLocationFeed> subscribers = new LinkedHashSet<>();
        private final LocationRecovery recovery = new LocationRecovery();
        private AMapLocationClient client;
        private AMapLocationListener listener;
        private AMapLocation latest;
        private long lastCallbackMs = -1;
        private int generation;

        Shared(Context context) { this.context = context; }

        boolean maintain(boolean manual) {
            long now = SystemClock.elapsedRealtime();
            LocationRecovery.Action action = recovery.next(now, client != null, manual);
            if (action == LocationRecovery.Action.NONE) return client != null;
            Diagnostics.record("location_start", "owner", "shared", "generation", ++generation,
                    "reason", manual ? "manual_retry" : client == null ? "initial_or_retry"
                            : action == LocationRecovery.Action.RESTART ? "measurement_stalled" : "recreate_after_stall",
                    "callback_gap_ms", lastCallbackMs < 0 ? -1 : now - lastCallbackMs,
                    "observation_age_ms", recovery.progressAgeMillis(now));
            recovery.attempted(now);
            latest = null;
            try {
                if (action == LocationRecovery.Action.CREATE) {
                    stopClient();
                    client = new AMapLocationClient(context);
                } else client.stopLocation();
                final AMapLocationClient next = client;
                final int attempt = generation;
                AMapLocationClientOption options = new AMapLocationClientOption();
                options.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
                options.setOnceLocation(false);
                options.setInterval(1000);
                options.setDeviceModeDistanceFilter(0);
                options.setLocationCacheEnable(false);
                options.setCacheCallBack(false);
                options.setWifiScan(true);
                options.setNeedAddress(false);
                options.setMockEnable(false);
                next.setLocationOption(options);
                // Queued callbacks from the previous listener cannot update a newer attempt.
                if (listener != null) next.unRegisterLocationListener(listener);
                listener = location -> {
                    AMapLocation copy = location == null ? null : location.clone();
                    if (Looper.myLooper() == main.getLooper()) receive(next, attempt, copy);
                    else main.post(() -> receive(next, attempt, copy));
                };
                next.setLocationListener(listener);
                next.startLocation();
                return true;
            } catch (Exception | LinkageError e) {
                Diagnostics.record("location_exception", "owner", "shared", "error_class", e.getClass().getSimpleName());
                stopClient();
                return false;
            }
        }

        private void receive(AMapLocationClient next, int attempt, AMapLocation location) {
            if (client != next) return;
            if (attempt != generation || subscribers.isEmpty()) return;
            long now = SystemClock.elapsedRealtime();
            Diagnostics.location("shared", location, lastCallbackMs < 0 ? -1 : now - lastCallbackMs, generation);
            lastCallbackMs = now;
            if (location != null && location.getErrorCode() == 0
                    && MapStartupPolicy.validFix(location.getLatitude(), location.getLongitude(), location.getAccuracy(), location.isFromMockProvider())
                    && MapFixTracker.isLiveSource(AmapLocationSample.source(location))) {
                long time = MapStartupPolicy.fixTime(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(),
                        location.getElapsedRealtimeNanos(), location.getTime());
                if (time > 0) {
                    recovery.measured(now, time);
                    if (latest == null || time > MapStartupPolicy.fixTime(SystemClock.elapsedRealtimeNanos(),
                            System.currentTimeMillis(), latest.getElapsedRealtimeNanos(), latest.getTime()))
                        latest = location.clone();
                }
            }
            for (AmapLocationFeed subscriber : subscribers.toArray(new AmapLocationFeed[0])) {
                if (!subscribers.contains(subscriber)) continue;
                subscriber.receiver.onLocationChanged(location == null ? null : location.clone());
            }
        }

        private void stopClient() {
            AMapLocationClient previous = client;
            client = null;
            if (previous == null) return;
            Diagnostics.record("location_stop", "owner", "shared", "generation", generation);
            try { if (listener != null) previous.unRegisterLocationListener(listener); } catch (RuntimeException | LinkageError ignored) { }
            listener = null;
            try { previous.stopLocation(); } catch (RuntimeException | LinkageError ignored) { }
            try { previous.onDestroy(); } catch (RuntimeException | LinkageError ignored) { }
        }

        private void stop() {
            ++generation;
            stopClient();
            latest = null;
            lastCallbackMs = -1;
            recovery.clear();
        }
    }
}
