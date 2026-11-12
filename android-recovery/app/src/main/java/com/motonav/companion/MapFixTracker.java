package com.motonav.companion;

/** Keeps the latest observation separate from a recent position suitable for navigation. */
public final class MapFixTracker<T> {
    public static final int SATELLITE = 1;
    public static final int WIFI = 5;
    public static final int CELL = 6;
    public static final int APPROXIMATE = 11;
    public static final int SYSTEM_NETWORK = 12;

    public record Fix<T>(T point, long timeNanos, float accuracy, int source) {}

    private Fix<T> latest;
    private Fix<T> navigation;

    public static int resolveSource(int source, int original, boolean highlyTrusted) {
        // SAME_REQ (2) is also returned after a stationary sensor/network recheck.
        // Cache-only, unknown-origin and low-trust results remain rejected. Measurement
        // age is independently checked by fixTime; this never changes a timestamp.
        return source == 2 && highlyTrusted && isLiveSource(original) ? original : source;
    }

    public static boolean isLiveSource(int source) {
        return switch (source) {
            case SATELLITE, WIFI, CELL, APPROXIMATE, SYSTEM_NETWORK -> true;
            default -> false;
        };
    }

    public boolean offer(T point, long timeNanos, float accuracy, int source, boolean precisePermission) {
        if (point == null || timeNanos <= 0 || !Float.isFinite(accuracy) || accuracy <= 0
                || !isLiveSource(source)) return false;
        boolean newerObservation = latest == null || timeNanos > latest.timeNanos();
        boolean newerNavigationFix = precisePermission && source != CELL && source != APPROXIMATE
                && MapStartupPolicy.navigationAccuracy(accuracy)
                && (navigation == null || timeNanos > navigation.timeNanos());
        if (!newerObservation && !newerNavigationFix) return false;
        Fix<T> fix = new Fix<>(point, timeNanos, accuracy, source);
        if (newerObservation) latest = fix;
        if (newerNavigationFix) navigation = fix;
        return true;
    }

    public Fix<T> latest(long nowNanos) {
        return fresh(latest, nowNanos);
    }

    public Fix<T> navigation(long nowNanos, boolean precisePermission, boolean systemEnabled) {
        return navigation != null && MapStartupPolicy.usableSnapshot(
                precisePermission, systemEnabled, nowNanos, navigation.timeNanos()) ? navigation : null;
    }

    private Fix<T> fresh(Fix<T> fix, long nowNanos) {
        return fix != null && MapStartupPolicy.fresh(nowNanos, fix.timeNanos()) ? fix : null;
    }

    public void clear() {
        latest = null;
        navigation = null;
    }

    public long observationAgeMillis(long nowNanos) {
        return latest == null ? -1 : DiagnosticRecord.age(nowNanos, latest.timeNanos());
    }
    public long navigationAgeMillis(long nowNanos) {
        return navigation == null ? -1 : DiagnosticRecord.age(nowNanos, navigation.timeNanos());
    }
    public String navigationReason(long nowNanos, boolean precisePermission, boolean systemEnabled) {
        if (!precisePermission) return "precise_permission_missing";
        if (!systemEnabled) return "system_location_off";
        if (navigation != null) return MapStartupPolicy.fresh(nowNanos, navigation.timeNanos()) ? "usable" : "navigation_fix_expired";
        if (latest == null) return "no_accepted_fix";
        if (latest.source() == CELL || latest.source() == APPROXIMATE) return "source_not_for_navigation";
        return MapStartupPolicy.navigationAccuracy(latest.accuracy()) ? "awaiting_precise_fix" : "accuracy_over_100m";
    }
}
