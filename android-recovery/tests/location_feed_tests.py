"""Execute the real feed against deterministic Android/AMap boundary doubles, not device tests."""
from pathlib import Path
import subprocess
import tempfile

stubs = {
    "android/content/Context.java": """
package android.content;
public class Context { public Context getApplicationContext() { return this; } }
""",
    "android/os/Looper.java": """
package android.os;
public class Looper {
    private static final Looper MAIN = new Looper();
    public static boolean background;
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return background ? null : MAIN; }
}
""",
    "android/os/Handler.java": """
package android.os;
public class Handler {
    private static final java.util.List<Runnable> queue = new java.util.ArrayList<>();
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public Looper getLooper() { return looper; }
    public boolean post(Runnable action) { queue.add(action); return true; }
    public static void flush() {
        while (!queue.isEmpty()) queue.remove(0).run();
    }
}
""",
    "android/os/SystemClock.java": """
package android.os;
public class SystemClock {
    public static long millis = 100000;
    public static long elapsedRealtime() { return millis; }
    public static long elapsedRealtimeNanos() { return millis * 1000000L; }
}
""",
    "android/os/Bundle.java": """
package android.os;
public class Bundle extends java.util.HashMap<String, Integer> {
    public int getInt(String key, int fallback) { return getOrDefault(key, fallback); }
    public void putInt(String key, int value) { put(key, value); }
}
""",
    "android/location/Location.java": """
package android.location;
public class Location implements Cloneable {
    public double latitude = 31, longitude = 121;
    public float accuracy = 10;
    public long elapsed, time;
    public boolean mock;
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public float getAccuracy() { return accuracy; }
    public boolean isFromMockProvider() { return mock; }
    public long getElapsedRealtimeNanos() { return elapsed; }
    public long getTime() { return time; }
}
""",
    "com/amap/api/location/AMapLocation.java": """
package com.amap.api.location;
public class AMapLocation extends android.location.Location {
    public static final String LBS_REAL_LOCATION_TYPE = "original";
    public static final int TRUSTED_LEVEL_HIGH = 1;
    public int error, source = 1, trust = 1;
    public android.os.Bundle extras;
    public int getErrorCode() { return error; }
    public int getLocationType() { return source; }
    public int getTrustedLevel() { return trust; }
    public android.os.Bundle getExtras() { return extras; }
    public AMapLocation clone() {
        try { return (AMapLocation) super.clone(); }
        catch (CloneNotSupportedException e) { throw new AssertionError(e); }
    }
}
""",
    "com/amap/api/location/AMapLocationListener.java": """
package com.amap.api.location;
public interface AMapLocationListener { void onLocationChanged(AMapLocation location); }
""",
    "com/amap/api/location/AMapLocationClientOption.java": """
package com.amap.api.location;
public class AMapLocationClientOption {
    public enum AMapLocationMode { Hight_Accuracy }
    public AMapLocationMode mode;
    public boolean once, cache = true, fast = true, mock = true;
    public long interval;
    public float distance = -1;
    public void setLocationMode(AMapLocationMode value) { mode = value; }
    public void setOnceLocation(boolean value) { once = value; }
    public void setInterval(long value) { interval = value; }
    public void setDeviceModeDistanceFilter(float value) { distance = value; }
    public void setLocationCacheEnable(boolean value) { cache = value; }
    public void setCacheCallBack(boolean value) { fast = value; }
    public void setWifiScan(boolean value) { }
    public void setNeedAddress(boolean value) { }
    public void setMockEnable(boolean value) { mock = value; }
}
""",
    "com/amap/api/location/AMapLocationClient.java": """
package com.amap.api.location;
public class AMapLocationClient {
    public static final java.util.List<AMapLocationClient> clients = new java.util.ArrayList<>();
    public static int constructors, failures;
    public int starts, stops, destroys;
    public AMapLocationClientOption options;
    public AMapLocationListener listener;
    public AMapLocationClient(android.content.Context context) throws Exception {
        constructors++;
        if (failures > 0) { failures--; throw new Exception("constructor failed"); }
        clients.add(this);
    }
    public static AMapLocationClient current() { return clients.get(clients.size() - 1); }
    public void setLocationOption(AMapLocationClientOption value) { options = value; }
    public void setLocationListener(AMapLocationListener value) {
        if (listener != null) throw new AssertionError("old listener was not removed");
        listener = value;
    }
    public void unRegisterLocationListener(AMapLocationListener value) {
        if (listener == value) listener = null;
    }
    public void startLocation() { starts++; }
    public void stopLocation() { stops++; }
    public void onDestroy() { destroys++; }
    public void emit(AMapLocation location) { if (listener != null) listener.onLocationChanged(location); }
}
""",
    "com/amap/api/maps/LocationSource.java": """
package com.amap.api.maps;
public interface LocationSource {
    interface OnLocationChangedListener { void onLocationChanged(android.location.Location location); }
    void activate(OnLocationChangedListener listener);
    void deactivate();
}
""",
    "com/motonav/companion/Diagnostics.java": """
package com.motonav.companion;
public class Diagnostics {
    public static void record(String event, Object... fields) { }
    public static void location(String owner, com.amap.api.location.AMapLocation value, long gap, int generation) { }
}
""",
}

test = """
package com.motonav.companion;
import android.content.Context;
import android.os.SystemClock;
import android.os.Looper;
import android.os.Handler;
import android.os.Bundle;
import com.amap.api.location.*;
import java.util.ArrayList;
import java.util.List;

public class LocationFeedTests {
    static int checks;
    static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    static AMapLocation sample(long ageMs) {
        AMapLocation value = new AMapLocation();
        value.elapsed = (SystemClock.millis - ageMs) * 1000000L;
        value.time = System.currentTimeMillis() - ageMs;
        return value;
    }
    static void step(long ms) { SystemClock.millis += ms; }
    public static void main(String[] args) {
        List<AMapLocation> mapPoints = new ArrayList<>(), navPoints = new ArrayList<>();
        AmapLocationFeed map = new AmapLocationFeed(new Context(), mapPoints::add);
        AmapLocationFeed nav = new AmapLocationFeed(new Context(), navPoints::add);
        check(map.maintain(), "initial request");
        AMapLocationClient first = AMapLocationClient.current();
        check(first.starts == 1 && AMapLocationClient.clients.size() == 1, "one initial client");
        check(!first.options.once && first.options.interval == 1000 && first.options.distance == 0
                && !first.options.cache && !first.options.fast && !first.options.mock, "continuous uncached options");
        first.emit(sample(0));
        long originalTime = mapPoints.get(0).elapsed;
        check(nav.maintain(), "navigation attaches");
        check(AMapLocationClient.clients.size() == 1 && first.starts == 1, "shared request");
        check(navPoints.size() == 1 && navPoints.get(0).elapsed == originalTime, "handoff preserves timestamp");

        map.stop();
        check(first.stops == 0 && first.destroys == 0, "map pause cannot stop navigation");
        step(1000); first.emit(sample(0));
        check(mapPoints.size() == 1 && navPoints.size() == 2, "only active owner receives location");
        map.maintain();
        check(mapPoints.size() == 2 && mapPoints.get(1).elapsed == navPoints.get(1).elapsed, "map resumes shared fix");
        nav.stop();
        check(first.stops == 0, "navigation end cannot stop visible map");
        map.deactivate();
        step(1000); first.emit(sample(0));
        check(mapPoints.size() == 3 && navPoints.size() == 2, "overlay deactivation does not unsubscribe");

        AMapLocationListener old = first.listener;
        Looper.background = true;
        old.onLocationChanged(sample(0)); // Queue a callback before recovery.
        Looper.background = false;
        for (int i = 0; i < 20; i++) {
            step(1000);
            AMapLocation error = sample(0); error.error = 4;
            first.emit(error);
            map.maintain();
        }
        check(first.starts == 2 && first.destroys == 0, "continuous errors trigger soft recovery");
        int delivered = mapPoints.size();
        Handler.flush();
        old.onLocationChanged(sample(0));
        check(mapPoints.size() == delivered, "callbacks from an older attempt are ignored");
        for (int i = 0; i < 60; i++) {
            step(1000);
            first.emit(sample(30000)); // callbacks continue, measurement remains stale
            map.maintain();
        }
        check(first.destroys == 1 && AMapLocationClient.clients.size() == 2, "stale callbacks trigger backed-off recreation");
        AMapLocationClient second = AMapLocationClient.current();
        old.onLocationChanged(sample(0));
        check(mapPoints.size() == delivered + 60, "destroyed client cannot publish");

        for (int i = 0; i < 300; i++) {
            step(1000);
            AMapLocation stationary = sample(0);
            stationary.source = 2;
            if (i % 2 == 0) {
                stationary.extras = new Bundle();
                stationary.extras.putInt(AMapLocation.LBS_REAL_LOCATION_TYPE, 5);
            }
            check(AmapLocationSample.source(stationary) == (i%2==0?5:MapFixTracker.TRUSTED_NETWORK_RECHECK),
                    "high-trust recheck works with or without optional original-source metadata");
            second.emit(stationary);
            map.maintain();
        }
        check(second.starts == 1 && second.destroys == 0, "fresh stationary observations do not restart");
        AMapLocation untrusted = sample(0); untrusted.source = 2; untrusted.trust = 2;
        check(AmapLocationSample.source(untrusted) == 2, "untrusted recheck without origin rejected");
        untrusted.extras = new Bundle(); untrusted.extras.putInt(AMapLocation.LBS_REAL_LOCATION_TYPE, 5);
        untrusted.trust = 2;
        check(AmapLocationSample.source(untrusted) == 2, "low trust recheck rejected");
        untrusted.trust = 1; untrusted.source = 4;
        check(AmapLocationSample.source(untrusted) == 4, "cached result not promoted");

        map.maintain(true);
        check(second.starts == 2, "manual action actually restarts");
        for (int i = 0; i < 10; i++) { step(100); map.maintain(true); }
        check(second.starts == 2, "repeated taps are throttled");
        map.stop();
        check(second.destroys == 1, "last owner releases client");
        check(map.maintain(), "fresh lifecycle starts again");
        AMapLocationClient third = AMapLocationClient.current();
        check(third != second && mapPoints.size() == delivered + 360, "old fix not retained across full stop");
        map.stop();

        AMapLocationClient.failures = 1;
        int constructors = AMapLocationClient.constructors;
        check(!map.maintain(), "constructor failure reported");
        step(1000);
        check(!map.maintain() && AMapLocationClient.constructors == constructors + 1, "no busy retry after failure");
        step(4000);
        check(map.maintain() && AMapLocationClient.constructors == constructors + 2, "constructor failure recovers");
        map.stop();
        System.out.println("LocationFeed: " + checks + " checks passed (Android/SDK doubles; no device claim)");
    }
}
"""

with tempfile.TemporaryDirectory(prefix="motonav-feed-") as directory:
    root = Path(directory)
    for relative, source in stubs.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
    case = root / "LocationFeedTests.java"
    case.write_text(test, encoding="utf-8")
    classes = root / "classes"
    classes.mkdir()
    production = Path("app/src/main/java/com/motonav/companion")
    sources = [str(root / name) for name in stubs]
    sources += [str(production / (name + ".java")) for name in
                ("AmapLocationFeed", "AmapLocationSample", "LocationRecovery", "MapStartupPolicy", "MapFixTracker", "DiagnosticRecord")]
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8",
                    "-source", "17", "-target", "17", "-d", str(classes), *sources, str(case)], check=True)
    subprocess.run(["java", "-cp", str(classes), "com.motonav.companion.LocationFeedTests"], check=True)
