package com.motonav.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Map/search boundary with no Android or network dependencies.
 *
 * <p>The bundled implementation uses fictional local coordinates measured in metres,
 * not latitude/longitude, GPS fixes, road data, or live traffic. A future real provider
 * must define its coordinate conversion before feeding the renderer or BLE transport.
 * Provider availability is independent of whether its results are simulated.</p>
 */
public interface MapProvider {
    String id();
    String name();
    boolean isAvailable();
    boolean isSimulation();
    String status();

    /**
     * Returns immutable matches. An empty query requests suggestions; null is invalid.
     * An unavailable provider throws rather than silently substituting demo results.
     */
    List<Place> search(String query);

    /**
     * Resolves a supported destination ID. The bundled demo supports only fixed route #1.
     * Unknown destinations throw IllegalArgumentException; an unavailable provider
     * throws IllegalStateException. No arbitrary route can be sent through BLE v1.
     */
    Route routeTo(String placeId);

    /** A point in the provider's documented coordinate space. */
    record Point(double x, double y) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("Point coordinates must be finite");
            }
        }
    }

    /** An immutable display/search result. IDs belong to the selected provider. */
    record Place(String id, String name, String subtitle, double x, double y) {
        public Place {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("Place ID is required");
            }
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Place name is required");
            }
            Objects.requireNonNull(subtitle, "subtitle");
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("Place coordinates must be finite");
            }
        }
    }

    /**
     * Immutable route geometry, travel distance and estimated duration.
     * In the demo, duration is simulation time at 12 m/s, not a real road ETA.
     */
    record Route(int routeId, Place origin, Place destination, int distanceMeters,
                 int durationSeconds, List<Point> points) {
        public Route {
            if (routeId <= 0 || distanceMeters <= 0 || durationSeconds <= 0) {
                throw new IllegalArgumentException("Route ID, distance and duration must be positive");
            }
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(points, "points");
            if (points.size() < 2) {
                throw new IllegalArgumentException("A route needs at least two points");
            }
            List<Point> copy = new ArrayList<>(points.size());
            for (Point point : points) {
                copy.add(Objects.requireNonNull(point, "route point"));
            }
            // List.copyOf requires a newer Android API; keep this usable on API 26.
            points = Collections.unmodifiableList(copy);
        }
    }
}
