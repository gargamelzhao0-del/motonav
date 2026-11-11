import com.motonav.companion.DemoMapProvider;
import com.motonav.companion.MapProvider;
import com.motonav.companion.Protocol;
import com.motonav.companion.Simulation;
import com.motonav.companion.UnavailableMapProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Standalone tests against the actual providers; run with a JDK, no Android SDK. */
public final class ProviderTests {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + ": " + message);
    }

    private static void equal(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 0.000001, message);
    }

    private static <T extends Throwable> T rejects(Class<T> type, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    public static void main(String[] args) {
        MapProvider demo = new DemoMapProvider();
        check(demo.id().equals("demo"), "stable demo provider ID");
        check(!demo.name().isEmpty(), "demo display name");
        check(demo.isAvailable(), "offline demo available");
        check(demo.isSimulation(), "simulation explicitly identified");
        check(demo.status().contains("虚构") && demo.status().contains("非真实导航"),
                "disclosure distinguishes fiction from navigation");

        String[] queries = {"", " ", "\t\n", "\u3000", "霓虹码头", "霓虹", "码头",
                "Neon Wharf", "  NEON   WHARF \n", "NEON\u00a0WHARF", "neon-wharf",
                "neonwharf", "WhArF", "dock"};
        for (String query : queries) {
            List<MapProvider.Place> result = demo.search(query);
            check(result.size() == 1, "one supported match for " + query);
            check(result.get(0).id().equals(DemoMapProvider.DESTINATION_ID),
                    "aliases resolve to same destination");
            rejects(UnsupportedOperationException.class, () -> result.add(result.get(0)));
        }
        for (String query : new String[] {"北京", "天安门", "机场", "not a place", "旧城车库",
                "old town garage", "neon wharf nowhere", "<script>"}) {
            check(demo.search(query).isEmpty(), "unsupported place must not return fake route");
        }
        rejects(IllegalArgumentException.class, () -> demo.search(null));
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(demo.search("NEON WHARF").size() == 1, "search independent of default locale");
        } finally {
            Locale.setDefault(previousLocale);
        }

        MapProvider.Route route = demo.routeTo(DemoMapProvider.DESTINATION_ID);
        check(route.routeId() == 1, "only firmware route ID 1");
        check(route.origin().id().equals(DemoMapProvider.ORIGIN_ID), "fictional origin ID");
        check(route.origin().name().contains("旧城车库"), "fictional origin name");
        check(route.destination().name().contains("霓虹码头"), "fictional destination name");
        check(route.destination().name().contains("Neon Wharf"), "English destination alias");
        check(route.destination().equals(demo.search("").get(0)), "search and route agree");
        check(route.distanceMeters() == 1800, "route length 1800m");
        check(route.durationSeconds() == 150, "simulation duration 150 seconds");
        check(route.points().size() == 8, "eight fixed points");
        double length = 0;
        for (int i = 0; i < route.points().size(); i++) {
            MapProvider.Point point = route.points().get(i);
            equal(point.x(), Simulation.ROUTE[i][0], "x matches simulator");
            equal(point.y(), Simulation.ROUTE[i][1], "y matches simulator");
            if (i > 0) {
                MapProvider.Point previous = route.points().get(i - 1);
                length += Math.hypot(point.x() - previous.x(), point.y() - previous.y());
            }
        }
        equal(length, route.distanceMeters(), "geometry agrees with distance");
        equal(route.origin().x(), route.points().get(0).x(), "origin x");
        equal(route.origin().y(), route.points().get(0).y(), "origin y");
        equal(route.destination().x(), route.points().get(7).x(), "destination x");
        equal(route.destination().y(), route.points().get(7).y(), "destination y");
        check(new Protocol().route().wire().equals("R,1,1," + route.routeId() + "\n"),
                "provider route ID agrees with unchanged BLE serializer");
        rejects(UnsupportedOperationException.class, () -> route.points().clear());
        rejects(UnsupportedOperationException.class,
                () -> route.points().set(0, new MapProvider.Point(99, 99)));
        for (String unsupported : new String[] {null, "", DemoMapProvider.ORIGIN_ID, "2",
                "1", "霓虹码头", "天安门", "NEON-WHARF", "neon-wharf "}) {
            rejects(IllegalArgumentException.class, () -> demo.routeTo(unsupported));
        }

        List<MapProvider.Point> mutable = new ArrayList<>(route.points());
        MapProvider.Route copy = new MapProvider.Route(1, route.origin(), route.destination(),
                1800, 150, mutable);
        mutable.clear();
        check(copy.points().size() == 8, "route constructor takes defensive copy");
        rejects(UnsupportedOperationException.class, () -> copy.points().remove(0));
        rejects(IllegalArgumentException.class, () -> new MapProvider.Point(Double.NaN, 0));
        rejects(IllegalArgumentException.class, () -> new MapProvider.Point(0, Double.POSITIVE_INFINITY));
        rejects(IllegalArgumentException.class, () -> new MapProvider.Place("", "x", "", 0, 0));
        rejects(IllegalArgumentException.class, () -> new MapProvider.Place("x", " ", "", 0, 0));
        rejects(IllegalArgumentException.class,
                () -> new MapProvider.Place("x", "x", "", Double.NaN, 0));
        rejects(NullPointerException.class, () -> new MapProvider.Place("x", "x", null, 0, 0));
        rejects(IllegalArgumentException.class, () -> new MapProvider.Route(0, route.origin(),
                route.destination(), 1800, 150, route.points()));
        rejects(IllegalArgumentException.class, () -> new MapProvider.Route(1, route.origin(),
                route.destination(), 0, 150, route.points()));
        rejects(IllegalArgumentException.class, () -> new MapProvider.Route(1, route.origin(),
                route.destination(), 1800, 0, route.points()));
        rejects(IllegalArgumentException.class, () -> new MapProvider.Route(1, route.origin(),
                route.destination(), 1800, 150, new ArrayList<>()));
        rejects(NullPointerException.class, () -> new MapProvider.Route(1, route.origin(),
                route.destination(), 1800, 150, Arrays.asList(route.points().get(0), null)));

        MapProvider amap = new UnavailableMapProvider();
        check(amap.id().equals("amap"), "stable official provider ID");
        check(amap.name().contains("高德"), "official provider identity");
        check(!amap.isAvailable(), "official integration disabled");
        check(!amap.isSimulation(), "official placeholder never claims to be demo");
        for (String requirement : new String[] {"Key", "签名", "SDK", "隐私", "用户同意", "定位"}) {
            check(amap.status().contains(requirement), "missing requirement disclosed: " + requirement);
        }
        for (String query : new String[] {null, "", "霓虹码头", "北京"}) {
            IllegalStateException error = rejects(IllegalStateException.class, () -> amap.search(query));
            check(error.getMessage().contains("尚未接入"), "search fails with reason");
        }
        for (String id : new String[] {null, "", DemoMapProvider.DESTINATION_ID, "1"}) {
            IllegalStateException error = rejects(IllegalStateException.class, () -> amap.routeTo(id));
            check(error.getMessage().contains("不会返回模拟结果"), "route never silently uses demo");
        }
        check(new DemoMapProvider().routeTo(DemoMapProvider.DESTINATION_ID).equals(route),
                "new demo provider is deterministic");
        System.out.println("PASS: " + checks + " provider checks (fictional search, fixed route #1, "
                + "immutable data, BLE compatibility, official-provider fail-closed behavior)");
    }
}
