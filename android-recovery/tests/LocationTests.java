import com.motonav.companion.LocationFix;
import com.motonav.companion.LocationQuality;
import com.motonav.companion.LocationQuality.Assessment;
import com.motonav.companion.LocationQuality.Status;

/** Standalone deterministic tests: no Android SDK, device, GPS or network required. */
public final class LocationTests {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + ": " + message);
    }

    private static void rejects(Runnable action) {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Check " + checks + ": expected IllegalArgumentException");
    }

    private static LocationFix fix(double accuracy, long elapsed, long wall, boolean mock) {
        return new LocationFix(31.2, 121.5, accuracy, "gps", elapsed, wall, mock);
    }

    private static void assessment(LocationFix fix, long now, boolean precise,
                                   long maxAge, double maxAccuracy, Status status, long age) {
        Assessment result = LocationQuality.evaluate(fix, now, precise, maxAge, maxAccuracy);
        check(result.status() == status, "expected " + status + ", got " + result.status());
        check(result.ageMillis() == age, "expected age " + age + ", got " + result.ageMillis());
    }

    private static void validatesFixes() {
        LocationFix example = fix(4.5, 123, 456, true);
        check(example.latitude() == 31.2, "latitude retained");
        check(example.longitude() == 121.5, "longitude retained");
        check(example.accuracyMeters() == 4.5, "accuracy retained");
        check(example.provider().equals("gps"), "provider retained");
        check(example.elapsedRealtimeMillis() == 123, "elapsed time retained");
        check(example.capturedWallMillis() == 456, "wall time retained");
        check(example.mock(), "mock flag retained");
        check(example.equals(fix(4.5, 123, 456, true)), "record has immutable value equality");
        check(example.hashCode() == fix(4.5, 123, 456, true).hashCode(), "equal value hash code");
        check(!example.equals(fix(4.5, 123, 456, false)), "mock flag participates in equality");
        for (double latitude : new double[] {-90, -0.0, 0, 90}) {
            for (double longitude : new double[] {-180, -0.0, 0, 180}) {
                LocationFix boundary = new LocationFix(latitude, longitude, 0, "network", 0, 0, false);
                check(boundary.latitude() == latitude && boundary.longitude() == longitude,
                        "inclusive geographic boundaries");
            }
        }
        LocationFix maximum = new LocationFix(90, 180, Double.MAX_VALUE, "passive",
                Long.MAX_VALUE, Long.MAX_VALUE, false);
        check(maximum.elapsedRealtimeMillis() == Long.MAX_VALUE, "maximum elapsed accepted");
        check(maximum.capturedWallMillis() == Long.MAX_VALUE, "maximum wall time accepted");
        check(maximum.accuracyMeters() == Double.MAX_VALUE, "finite maximum accuracy accepted");
        check(fix(-0.0, 0, 0, false).accuracyMeters() == 0, "negative zero is nonnegative");
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            rejects(() -> new LocationFix(invalid, 0, 0, "gps", 0, 0, false));
            rejects(() -> new LocationFix(0, invalid, 0, "gps", 0, 0, false));
            rejects(() -> fix(invalid, 0, 0, false));
        }
        for (double invalid : new double[] {Math.nextDown(-90.0), Math.nextUp(90.0), -100, 100}) {
            rejects(() -> new LocationFix(invalid, 0, 0, "gps", 0, 0, false));
        }
        for (double invalid : new double[] {Math.nextDown(-180.0), Math.nextUp(180.0), -200, 200}) {
            rejects(() -> new LocationFix(0, invalid, 0, "gps", 0, 0, false));
        }
        for (double invalid : new double[] {-Double.MIN_VALUE, -1, -Double.MAX_VALUE}) {
            rejects(() -> fix(invalid, 0, 0, false));
        }
        for (String invalid : new String[] {null, "", " ", "\t\n\r"}) {
            rejects(() -> new LocationFix(0, 0, 0, invalid, 0, 0, false));
        }
        for (long invalid : new long[] {-1, Long.MIN_VALUE}) {
            rejects(() -> fix(0, invalid, 0, false));
            rejects(() -> fix(0, 0, invalid, false));
        }
    }

    private static void validatesEvaluationInputs() {
        LocationFix valid = fix(10, 100, 0, false);
        for (LocationFix value : new LocationFix[] {null, valid}) {
            for (long invalid : new long[] {-1, Long.MIN_VALUE}) {
                rejects(() -> LocationQuality.evaluate(value, invalid, true, 1000, 20));
                rejects(() -> LocationQuality.evaluate(value, 100, true, invalid, 20));
            }
            for (double invalid : new double[] {-Double.MIN_VALUE, -1, Double.NaN,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                rejects(() -> LocationQuality.evaluate(value, 100, true, 1000, invalid));
            }
        }
        rejects(() -> new Assessment(null, 0));
        for (Status status : Status.values()) {
            if (status == Status.NO_FIX || status == Status.INVALID_TIME) {
                rejects(() -> new Assessment(status, 0));
                rejects(() -> new Assessment(status, -2));
                check(new Assessment(status, -1).ageMillis() == -1, "unknown age represented");
            } else {
                rejects(() -> new Assessment(status, -1));
                check(new Assessment(status, 0).ageMillis() == 0, "known age may be zero");
                check(new Assessment(status, Long.MAX_VALUE).ageMillis() == Long.MAX_VALUE,
                        "known age may span long range");
            }
        }
    }

    private static void checksStatusAndBoundaries() {
        check(LocationQuality.UNKNOWN_AGE_MILLIS == -1, "unknown-age sentinel is explicit");
        assessment(null, 0, true, 0, 0, Status.NO_FIX, -1);
        assessment(null, Long.MAX_VALUE, false, Long.MAX_VALUE, Double.MAX_VALUE, Status.NO_FIX, -1);
        assessment(fix(0, 1, 0, false), 0, true, 0, 0, Status.INVALID_TIME, -1);
        assessment(fix(0, Long.MAX_VALUE, 0, false), 0, true, 0, 0, Status.INVALID_TIME, -1);
        assessment(fix(0, Long.MAX_VALUE, 0, false), Long.MAX_VALUE - 1,
                true, Long.MAX_VALUE, 0, Status.INVALID_TIME, -1);
        assessment(fix(0, 0, 0, false), 0, true, 0, 0, Status.GOOD, 0);
        assessment(fix(0, 0, 0, false), 1, true, 0, 0, Status.STALE, 1);
        assessment(fix(20, 100, 0, false), 1099, true, 1000, 20, Status.GOOD, 999);
        assessment(fix(20, 100, 0, false), 1100, true, 1000, 20, Status.GOOD, 1000);
        assessment(fix(20, 100, 0, false), 1101, true, 1000, 20, Status.STALE, 1001);
        assessment(fix(Math.nextDown(20.0), 0, 0, false), 0, true, 0, 20, Status.GOOD, 0);
        assessment(fix(Math.nextUp(20.0), 0, 0, false), 0, true, 0, 20, Status.LOW_ACCURACY, 0);
        assessment(fix(Double.MIN_VALUE, 0, 0, false), 0, true, 0, 0, Status.LOW_ACCURACY, 0);
        assessment(fix(-0.0, 0, 0, false), 0, true, 0, -0.0, Status.GOOD, 0);
        assessment(fix(Double.MAX_VALUE, 0, 0, false), Long.MAX_VALUE,
                true, Long.MAX_VALUE, Double.MAX_VALUE, Status.GOOD, Long.MAX_VALUE);
        assessment(fix(0, 0, 0, false), Long.MAX_VALUE,
                true, Long.MAX_VALUE - 1, 0, Status.STALE, Long.MAX_VALUE);
        assessment(fix(0, Long.MAX_VALUE, 0, false), Long.MAX_VALUE,
                true, 0, 0, Status.GOOD, 0);
        assessment(fix(0, Long.MAX_VALUE - 1, 0, false), Long.MAX_VALUE,
                true, 1, 0, Status.GOOD, 1);
        assessment(fix(0, 10, 0, true), 20, true, 100, 20, Status.MOCK, 10);
        assessment(fix(0, 10, 0, false), 20, false, 100, 20, Status.APPROXIMATE, 10);
        assessment(fix(30, 10, 0, false), 20, true, 100, 20, Status.LOW_ACCURACY, 10);
    }

    private static void checksPrecedenceAndPermissionChanges() {
        // Exercise every combination of mock, stale, precise permission, and accuracy.
        for (boolean mock : new boolean[] {false, true}) {
            for (boolean stale : new boolean[] {false, true}) {
                for (boolean precise : new boolean[] {false, true}) {
                    for (boolean inaccurate : new boolean[] {false, true}) {
                        LocationFix value = fix(inaccurate ? 21 : 20, 100, 0, mock);
                        long now = stale ? 1101 : 1100;
                        Status expected = mock ? Status.MOCK : stale ? Status.STALE
                                : !precise ? Status.APPROXIMATE : inaccurate ? Status.LOW_ACCURACY
                                : Status.GOOD;
                        assessment(value, now, precise, 1000, 20, expected, now - 100);
                        // Future monotonic time always wins, even with multiple other failures.
                        assessment(value, 99, precise, 0, 0, Status.INVALID_TIME, -1);
                    }
                }
            }
        }
        LocationFix reused = fix(5, 100, 10_000, false);
        assessment(reused, 150, true, 100, 20, Status.GOOD, 50);
        assessment(reused, 150, false, 100, 20, Status.APPROXIMATE, 50);
        assessment(reused, 150, true, 100, 20, Status.GOOD, 50);
        assessment(reused, 201, true, 100, 20, Status.STALE, 101);
        check(reused.equals(fix(5, 100, 10_000, false)), "evaluating never mutates snapshot");

        for (long wall : new long[] {0, 1, 1_700_000_000_000L, Long.MAX_VALUE}) {
            assessment(fix(5, 100, wall, false), 150, true, 100, 20, Status.GOOD, 50);
            assessment(fix(5, 100, wall, false), 201, true, 100, 20, Status.STALE, 101);
        }
        // Provider labels cannot upgrade approximate permission or bypass mock status.
        assessment(new LocationFix(0, 0, 0, "gps", 0, 0, false), 0,
                false, 0, 0, Status.APPROXIMATE, 0);
        assessment(new LocationFix(0, 0, 0, "network", 0, 0, false), 0,
                true, 0, 0, Status.GOOD, 0);
        assessment(new LocationFix(0, 0, 0, "trusted-provider", 0, 0, true), 0,
                true, 0, 0, Status.MOCK, 0);
    }

    public static void main(String[] args) {
        validatesFixes();
        validatesEvaluationInputs();
        checksStatusAndBoundaries();
        checksPrecedenceAndPermissionChanges();
        System.out.println("PASS: " + checks + " location checks (immutable snapshots, invalid inputs, "
                + "monotonic time, inclusive thresholds, status precedence, permission changes; "
                + "synthetic test data only, not GPS/device verification)");
    }
}
