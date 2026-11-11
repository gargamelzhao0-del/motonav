package com.motonav.companion;

/**
 * An immutable geographic location snapshot, independent of Android APIs.
 *
 * <p>Latitude and longitude are geographic degrees, never the fictional metre-based
 * coordinates used by the demo route. Accuracy is the provider's reported horizontal
 * uncertainty in metres, not a guarantee of position. A snapshot does not identify
 * a road, route, direction of travel, or navigation instruction.</p>
 *
 * <p>Elapsed time must use the same boot's monotonic clock as the evaluator. Wall
 * time is retained for display only and must never determine freshness. This model
 * neither requests location permission nor starts tracking, stores, or sends fixes.</p>
 */
public record LocationFix(double latitude, double longitude, double accuracyMeters,
                          String provider, long elapsedRealtimeMillis,
                          long capturedWallMillis, boolean mock) {
    public LocationFix {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be finite and between -90 and 90");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be finite and between -180 and 180");
        }
        if (!Double.isFinite(accuracyMeters) || accuracyMeters < 0) {
            throw new IllegalArgumentException("Accuracy must be finite and nonnegative");
        }
        if (provider == null || provider.trim().isEmpty()) {
            throw new IllegalArgumentException("Location provider is required");
        }
        if (elapsedRealtimeMillis < 0 || capturedWallMillis < 0) {
            throw new IllegalArgumentException("Location timestamps must be nonnegative");
        }
    }
}
