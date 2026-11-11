package com.motonav.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Explicit offline fiction, backed only by the simulator's fixed route #1. */
public final class DemoMapProvider implements MapProvider {
    public static final String DESTINATION_ID = "neon-wharf";
    public static final String ORIGIN_ID = "old-town-garage";

    private static final String[] DESTINATION_ALIASES = {
        "霓虹码头", "neon wharf", "neon-wharf", "neonwharf", "码头", "wharf", "dock"
    };
    private static final Route DEMO_ROUTE = createRoute();
    private static final List<Place> DESTINATIONS =
            Collections.singletonList(DEMO_ROUTE.destination());

    @Override public String id() { return "demo"; }
    @Override public String name() { return "离线演示 / Offline Demo"; }
    @Override public boolean isAvailable() { return true; }
    @Override public boolean isSimulation() { return true; }
    @Override public String status() {
        return "虚构地图 · 非真实导航 · 无 GPS / 路况 · 仅固定路线 #1（1.8 km）";
    }

    @Override public List<Place> search(String query) {
        if (query == null) throw new IllegalArgumentException("Search query is required");
        String normalized = normalize(query);
        if (normalized.isEmpty()) return DESTINATIONS;
        for (String alias : DESTINATION_ALIASES) {
            if (alias.contains(normalized)) return DESTINATIONS;
        }
        return Collections.emptyList();
    }

    @Override public Route routeTo(String placeId) {
        if (!DESTINATION_ID.equals(placeId)) {
            throw new IllegalArgumentException(
                    "演示模式仅支持霓虹码头的固定路线 #1；不能规划任意地点或真实道路。"
                    + " Demo supports only destination ID: " + DESTINATION_ID);
        }
        return DEMO_ROUTE;
    }

    private static Route createRoute() {
        List<Point> points = new ArrayList<>(Simulation.ROUTE.length);
        for (double[] point : Simulation.ROUTE) {
            points.add(new Point(point[0], point[1]));
        }
        Point first = points.get(0);
        Point last = points.get(points.size() - 1);
        Place origin = new Place(ORIGIN_ID, "旧城车库 / Old Town Garage",
                "虚构起点 · 本地模拟坐标，非 GPS", first.x(), first.y());
        Place destination = new Place(DESTINATION_ID, "霓虹码头 / Neon Wharf",
                "虚构目的地 · 固定演示路线 #1 · 1.8 km", last.x(), last.y());
        return new Route(1, origin, destination, Simulation.TOTAL_MM / 1000,
                Simulation.TOTAL_MM / Simulation.SPEED_MMPS, points);
    }

    private static String normalize(String value) {
        StringBuilder normalized = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                pendingSpace = normalized.length() > 0;
            } else {
                if (pendingSpace) normalized.append(' ');
                normalized.append(character);
                pendingSpace = false;
            }
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }
}
