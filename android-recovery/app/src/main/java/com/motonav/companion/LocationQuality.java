package com.motonav.companion;

/**
 * Pure, deterministic quality checks for one location snapshot.
 *
 * <p>GOOD means only that this snapshot passes the supplied age, permission and
 * reported-accuracy checks and is not flagged as mock. It does not establish a safe
 * navigation position, map matching, a valid road/route, or trust in the provider.
 * Real fixes must remain separate from synthetic routes and BLE demo positions.</p>
 */
public final class LocationQuality {
    /** No meaningful nonnegative age can be calculated for no fix or a future fix. */
    public static final long UNKNOWN_AGE_MILLIS = -1L;

    private LocationQuality() {}

    public enum Status {
        NO_FIX,
        INVALID_TIME,
        MOCK,
        STALE,
        APPROXIMATE,
        LOW_ACCURACY,
        /** Snapshot quality only. Never a declaration that navigation is safe or ready. */
        GOOD
    }

    /**
     * The highest-priority quality status and monotonic age in milliseconds.
     * Age is UNKNOWN_AGE_MILLIS for NO_FIX and INVALID_TIME, otherwise nonnegative.
     */
    public record Assessment(Status status, long ageMillis) {
        public Assessment {
            if (status == null) {
                throw new IllegalArgumentException("Quality status is required");
            }
            boolean unknownAge = status == Status.NO_FIX || status == Status.INVALID_TIME;
            if (unknownAge ? ageMillis != UNKNOWN_AGE_MILLIS : ageMillis < 0) {
                throw new IllegalArgumentException("Age does not match quality status");
            }
        }
    }

    /**
     * Evaluates in this order: no fix, future monotonic time, mock, stale,
     * approximate permission, low reported accuracy, then GOOD.
     *
     * <p>The current time and age threshold must be nonnegative; the accuracy
     * threshold must be finite and nonnegative. Invalid parameters are rejected
     * even when fix is null. Age and accuracy thresholds are inclusive: only values
     * strictly greater than the maximum fail. The current precise-permission value
     * is evaluated on every call, including when reusing an earlier snapshot.</p>
     *
     * <p>Use the same boot's elapsed-realtime clock for nowElapsedMs and the fix.
     * capturedWallMillis is deliberately ignored because wall clocks can change.</p>
     */
    public static Assessment evaluate(LocationFix fix, long nowElapsedMs,
                                      boolean precisePermission, long maximumAgeMs,
                                      double maximumAccuracyMeters) {
        if (nowElapsedMs < 0 || maximumAgeMs < 0) {
            throw new IllegalArgumentException("Current elapsed time and maximum age must be nonnegative");
        }
        if (!Double.isFinite(maximumAccuracyMeters) || maximumAccuracyMeters < 0) {
            throw new IllegalArgumentException("Maximum accuracy must be finite and nonnegative");
        }
        if (fix == null) {
            return new Assessment(Status.NO_FIX, UNKNOWN_AGE_MILLIS);
        }
        if (fix.elapsedRealtimeMillis() > nowElapsedMs) {
            return new Assessment(Status.INVALID_TIME, UNKNOWN_AGE_MILLIS);
        }
        // Both values are nonnegative and ordered, so subtraction cannot overflow.
        long ageMillis = nowElapsedMs - fix.elapsedRealtimeMillis();
        Status status;
        if (fix.mock()) {
            status = Status.MOCK;
        } else if (ageMillis > maximumAgeMs) {
            status = Status.STALE;
        } else if (!precisePermission) {
            status = Status.APPROXIMATE;
        } else if (fix.accuracyMeters() > maximumAccuracyMeters) {
            status = Status.LOW_ACCURACY;
        } else {
            status = Status.GOOD;
        }
        return new Assessment(status, ageMillis);
    }
}
