package com.motonav.companion;

/** A snapshot of real SDK guidance. Ages always refer to the original observations. */
public record NavigationFrame(int mode, int icon, int turnMeters, int remainingMeters,
        int remainingSeconds, int speedKmh, long fixTimeMs, long instructionTimeMs) {
    public static final int WAITING = 1, LIVE = 2, ARRIVED = 3, STOPPED = 4;
    public static final int MAX_AGE_MS = 15_000;

    public static NavigationFrame status(int mode) {
        if (mode != WAITING && mode != ARRIVED && mode != STOPPED) throw new IllegalArgumentException("mode");
        return new NavigationFrame(mode, 0, -1, -1, -1, -1, 0, 0);
    }

    public boolean liveAt(long nowMs) {
        return mode == LIVE && validValues() && age(nowMs, fixTimeMs) <= MAX_AGE_MS
                && age(nowMs, instructionTimeMs) <= MAX_AGE_MS;
    }

    private boolean validValues() {
        return icon >= 0 && icon <= 255 && turnMeters >= 0 && turnMeters <= 50_000_000
                && remainingMeters >= 0 && remainingMeters <= 50_000_000
                && remainingSeconds >= 0 && remainingSeconds <= 31_536_000
                && speedKmh >= -1 && speedKmh <= 400;
    }

    public static long age(long nowMs, long observedMs) {
        if (observedMs <= 0 || nowMs < observedMs) return MAX_AGE_MS + 1;
        return Math.min(MAX_AGE_MS + 1, nowMs - observedMs);
    }

    public NavigationFrame at(long nowMs) {
        if (mode != LIVE) return status(mode);
        return liveAt(nowMs) ? this : status(WAITING);
    }
}
