package com.motonav.companion;

/** GPS-mode SDK measurements retain their own time; callbacks never renew an old fix. */
public final class NaviFixTracker {
    public record Fix(double latitude, double longitude, float accuracy, float speedKmh,
                      float bearing, long timeNanos, long wallMillis) { }
    private Fix latest;
    public boolean offer(boolean gpsSession, long nowNanos, long wallNow, int source,
                         long measuredWall, double latitude, double longitude,
                         float accuracy, float speedKmh, float bearing) {
        if (!gpsSession || source != 0 || !MapStartupPolicy.validFix(latitude, longitude, accuracy, false)
                || !MapStartupPolicy.navigationAccuracy(accuracy)) return false;
        long measured = MapStartupPolicy.fixTime(nowNanos, wallNow, 0, measuredWall);
        if (measured <= 0 || (latest != null && (measuredWall <= latest.wallMillis() || measured <= latest.timeNanos()))) return false;
        // SDK reports zero both for a stopped vehicle and for unavailable speed.
        float speed = Float.isFinite(speedKmh) && speedKmh > 0 && speedKmh <= 400 ? speedKmh : -1;
        float heading = Float.isFinite(bearing) && bearing >= 0 && bearing < 360 ? bearing : Float.NaN;
        latest = new Fix(latitude, longitude, accuracy, speed, heading, measured, measuredWall);
        return true;
    }
    public Fix current(long nowNanos) {
        return latest != null && MapStartupPolicy.fresh(nowNanos, latest.timeNanos()) ? latest : null;
    }
    public void clear() { latest = null; }
}
