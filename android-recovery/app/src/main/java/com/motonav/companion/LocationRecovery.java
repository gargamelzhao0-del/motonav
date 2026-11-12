package com.motonav.companion;

/** Tracks new measurements, not merely SDK callbacks. All times are monotonic. */
public final class LocationRecovery {
    public enum Action { NONE, CREATE, RESTART }
    private long attemptMs = -1, progressMs = -1, measurementNanos;
    private int retries;

    public Action next(long nowMs, boolean clientPresent, boolean manual) {
        if (attemptMs < 0) return Action.CREATE;
        long sinceAttempt = nowMs - attemptMs;
        // Repeated taps and failed constructors must not create a restart loop.
        if (manual && sinceAttempt >= 5000) return clientPresent ? Action.RESTART : Action.CREATE;
        if (!clientPresent) return sinceAttempt >= Math.min(30000, 5000L << Math.min(retries, 3))
                ? Action.CREATE : Action.NONE;
        long delay = retries == 0 ? (progressMs < 0 ? 30000 : 20000)
                : Math.min(120000, 30000L << Math.min(retries, 2));
        if (nowMs - Math.max(attemptMs, progressMs) < delay) return Action.NONE;
        return retries == 0 ? Action.RESTART : Action.CREATE;
    }

    public void attempted(long nowMs) {
        if (attemptMs >= 0) retries = Math.min(retries + 1, 3);
        attemptMs = nowMs;
    }

    public void measured(long nowMs, long timeNanos) {
        if (timeNanos <= measurementNanos) return;
        measurementNanos = timeNanos;
        progressMs = nowMs;
        retries = 0;
    }

    public long progressAgeMillis(long nowMs) { return progressMs < 0 ? -1 : nowMs - progressMs; }

    public void clear() {
        attemptMs = -1; progressMs = -1; measurementNanos = 0; retries = 0;
    }
}
