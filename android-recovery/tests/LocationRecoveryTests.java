import com.motonav.companion.LocationRecovery;
import static com.motonav.companion.LocationRecovery.Action.*;

public final class LocationRecoveryTests {
    static int checks;
    static void check(boolean value) { ++checks; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        LocationRecovery recovery = new LocationRecovery();
        check(recovery.next(1000, false, false) == CREATE);
        recovery.attempted(1000);
        check(recovery.next(5999, false, false) == NONE);
        check(recovery.next(6000, false, false) == CREATE);
        check(recovery.next(30999, true, false) == NONE);
        check(recovery.next(31000, true, false) == RESTART);
        recovery.attempted(31000);
        check(recovery.next(90999, true, false) == NONE);
        check(recovery.next(91000, true, false) == CREATE);
        recovery.attempted(91000);
        check(recovery.next(210999, true, false) == NONE);
        check(recovery.next(211000, true, false) == CREATE);
        recovery.attempted(211000);
        recovery.measured(212000, 211_000_000_000L);
        // Replayed or older measurements cannot keep a dead stream healthy.
        recovery.measured(220000, 211_000_000_000L);
        recovery.measured(221000, 210_000_000_000L);
        check(recovery.progressAgeMillis(222000) == 10000);
        check(recovery.next(231999, true, false) == NONE);
        check(recovery.next(232000, true, false) == RESTART);
        recovery.attempted(232000);
        check(recovery.next(232100, true, true) == NONE);
        check(recovery.next(237000, true, true) == RESTART);
        recovery.clear();
        check(recovery.next(240000, false, false) == CREATE);
        check(recovery.progressAgeMillis(240000) == -1);
        System.out.println("LocationRecovery: " + checks + " checks passed");
    }
}
