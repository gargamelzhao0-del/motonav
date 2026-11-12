import com.motonav.companion.MapStartupPolicy;
import com.motonav.companion.MapFixTracker;
public class MapFixTests {
    static int count;
    static void check(boolean value){count++;if(!value)throw new AssertionError("check "+count);}
    public static void main(String[] args){
        check(MapStartupPolicy.validFix(31.2,121.5,8,false));
        check(!MapStartupPolicy.validFix(0,0,8,false));
        check(!MapStartupPolicy.validFix(31,121,8,true));
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,181,-181})check(!MapStartupPolicy.validFix(31,bad,8,false));
        for(double bad:new double[]{Double.NaN,91,-91})check(!MapStartupPolicy.validFix(bad,121,8,false));
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,0,-1})check(!MapStartupPolicy.validFix(31,121,bad,false));
        check(MapStartupPolicy.locationError(32).contains("SHA1"));check(MapStartupPolicy.locationError(7).contains("SHA1"));
        check(MapStartupPolicy.locationError(4).contains("网络"));check(MapStartupPolicy.locationError(12).contains("12"));
        for(boolean permission:new boolean[]{false,true})for(boolean enabled:new boolean[]{false,true}){
            check(MapStartupPolicy.usableSnapshot(permission,enabled,20000000000L,19000000000L)==(permission&&enabled));
            check(!MapStartupPolicy.usableSnapshot(permission,enabled,20000000000L,1));
        }
        // A 550 m observation belongs on the map but must neither replace nor refresh a usable GPS fix.
        MapFixTracker<String> tracker = new MapFixTracker<>();
        long start = 20_000_000_000L, second = 1_000_000_000L;
        check(tracker.offer("gps", start, 12, MapFixTracker.SATELLITE, true));
        check(tracker.offer("cell", start + second, 550, MapFixTracker.CELL, true));
        check(tracker.latest(start + 2 * second).point().equals("cell"));
        check(tracker.navigation(start + 2 * second, true, true).point().equals("gps"));
        check(tracker.navigation(start + 2 * second, false, true) == null);
        check(tracker.navigation(start + 2 * second, true, false) == null);
        check(tracker.navigation(start + 15 * second + 1, true, true) == null);
        check(tracker.latest(start + 15 * second + 1).point().equals("cell"));
        check(!tracker.offer("late gps", start - second, 5, MapFixTracker.SATELLITE, true));
        check(!tracker.offer("duplicate gps", start, 5, MapFixTracker.SATELLITE, true));
        for (int cached : new int[]{0, 2, 4, 8, 9, 99}) {
            check(!tracker.offer("cache", start + 16 * second, 5, cached, true));
        }
        check(tracker.offer("new wifi", start + 17 * second, 35, MapFixTracker.WIFI, true));
        check(tracker.navigation(start + 17 * second, true, true).point().equals("new wifi"));
        check(tracker.offer("approximate", start + 18 * second, 10, MapFixTracker.APPROXIMATE, true));
        check(tracker.navigation(start + 18 * second, true, true).point().equals("new wifi"));
        check(tracker.offer("wifi with coarse permission", start + 19 * second, 15, MapFixTracker.WIFI, false));
        check(tracker.navigation(start + 19 * second, true, true).point().equals("new wifi"));
        tracker.clear();
        check(tracker.latest(start + 19 * second) == null);
        check(tracker.navigation(start + 19 * second, true, true) == null);

        // A fresh, highly trusted stationary recheck retains its original source.
        check(MapFixTracker.resolveSource(2, MapFixTracker.WIFI, true) == MapFixTracker.WIFI);
        check(MapFixTracker.resolveSource(2, MapFixTracker.WIFI, false) == 2);
        for (int unknown : new int[]{0, 2, 4, 8, 9, 99}) check(MapFixTracker.resolveSource(2, unknown, true) == 2);
        check(MapFixTracker.resolveSource(4, MapFixTracker.WIFI, true) == 4);
        check(tracker.offer("stationary wifi", start, 25, MapFixTracker.resolveSource(2, MapFixTracker.WIFI, true), true));
        check(tracker.navigation(start + second, true, true) != null);
        check(tracker.navigation(start + 16 * second, true, true) == null);
        check(!tracker.offer("duplicate recheck", start, 25, MapFixTracker.resolveSource(2, MapFixTracker.WIFI, true), true));
        tracker.clear();
        check(tracker.offer("stationary cell", start, 10, MapFixTracker.resolveSource(2, MapFixTracker.CELL, true), true));
        check(tracker.navigation(start, true, true) == null);
        check(!tracker.offer("unknown stationary origin", start + second, 10, MapFixTracker.resolveSource(2, 0, true), true));
        System.out.println("MapFix: "+count+" checks passed");
    }
}
