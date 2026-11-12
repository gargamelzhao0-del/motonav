package com.motonav.companion;

import android.app.Activity;
import android.location.Location;
import android.os.Handler;
import android.os.SystemClock;
import com.amap.api.maps.AMap;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.SimpleNaviListener;
import com.amap.api.navi.model.*;
import com.amap.api.services.core.PoiItem;
import java.lang.reflect.Field;
import java.util.List;

/** Exercises the production controller with time, UI and SDK boundary doubles. */
public class JourneyControllerTests {
    static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static final class Host implements JourneyController.Host, JourneyController.Ui {
        Location location;
        boolean allowed = true, delayStart;
        int finished, refreshes, begun;
        String message;
        Runnable pendingStart;
        public boolean active() { return true; }
        public boolean navigationAllowed() { return allowed; }
        public String key() { return "0".repeat(32); }
        public Location fix() { return location; }
        public long fixNanos() { return location == null ? 0 : location.getElapsedRealtimeNanos(); }
        public void finished() { finished++; pendingStart = null; }
        public void refreshLocation() { refreshes++; }
        public boolean beginNavigation(Runnable start) {
            begun++;
            if (delayStart) pendingStart = start; else start.run();
            return true;
        }
        public void report(String text) { message = text; }
        public void overview() {}
        public void icon(android.graphics.Bitmap bitmap) {}
        void fixAt(double latitude) {
            location = new Location("gps");
            location.setLatitude(latitude); location.setLongitude(120);
            location.setAccuracy(10); location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }
    }
    static final class Fixture implements AutoCloseable {
        final Host host = new Host();
        final AMap map = new AMap();
        final JourneyController controller;
        Fixture() throws Exception {
            Handler.reset(); AMapNavi.instances = 0; AMapNavi.latest = null;
            host.fixAt(30);
            controller = new JourneyController(new Activity(), host);
            controller.attach(new Activity(), map, host);
            Field destination = JourneyController.class.getDeclaredField("destination");
            destination.setAccessible(true); destination.set(controller, new PoiItem());
        }
        AMapNavi plan() { controller.startOrStop(); return AMapNavi.latest; }
        AMapNavi navigate() {
            AMapNavi engine = plan();
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            check(engine.starts == 1, "one explicit request starts GPS once");
            return engine;
        }
        public void close() { controller.close(); }
    }
    static void drivingAndRerouting() throws Exception {
        try (Fixture f = new Fixture()) {
            AMapNavi engine = f.navigate();
            check(engine.car.type.equals("0") && engine.strategy == 10, "ordinary driving vehicle and strategy");
            engine.listener.onNaviInfoUpdate(new NaviInfo());
            check(f.controller.frame().mode() == NavigationFrame.LIVE, "fresh guidance visible");
            engine.listener.onReCalculateRouteForYaw();
            check(engine.stops == 0 && f.host.finished == 0, "yaw keeps engine and foreground service alive");
            check(f.controller.frame().mode() == NavigationFrame.WAITING && f.map.line.removed, "hide stale instruction and polyline");
            Handler.advance(1000); f.host.fixAt(30.001);
            engine.listener.onNaviInfoUpdate(new NaviInfo());
            check(f.controller.frame().mode() == NavigationFrame.WAITING, "new fix cannot revive old route instruction during reroute");
            // A duplicate initial-route callback must not finish an ongoing SDK replan.
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            engine.listener.onNaviInfoUpdate(new NaviInfo());
            check(f.controller.frame().mode() == NavigationFrame.WAITING, "ignore duplicate initial success");
            engine.path = new AMapNaviPath();
            engine.path.points = List.of(new NaviLatLng(30.001,120),new NaviLatLng(30.02,120.02));
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(1));
            check(engine.selections == 1 && engine.starts == 1, "no route selection or GPS restart while navigating");
            check(f.map.line.options.points.get(1).latitude == 30.02, "draw SDK active path, not old alternatives map");
            engine.listener.onNaviInfoUpdate(new NaviInfo());
            check(f.controller.frame().mode() == NavigationFrame.WAITING, "route completion needs a subsequent position");
            Handler.advance(1000); f.host.fixAt(30.001);
            engine.listener.onNaviInfoUpdate(new NaviInfo());
            check(f.controller.frame().mode() == NavigationFrame.LIVE, "new route guidance resumes automatically");
            f.controller.detach();
            engine.listener.onReCalculateRouteForTrafficJam();
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(2));
            Handler.advance(1000); f.host.fixAt(30.002); engine.listener.onNaviInfoUpdate(new NaviInfo());
            check(f.controller.frame().mode() == NavigationFrame.LIVE && f.host.finished == 0, "traffic reroute works with activity detached");
            f.controller.attach(new Activity(), f.map, f.host);
            f.controller.startOrStop();
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(1));
            engine.listener.onNaviInfoUpdate(new NaviInfo());
            check(f.controller.frame().mode() == NavigationFrame.STOPPED && f.host.finished == 1, "late callbacks cannot resurrect ended navigation");
        }
    }
    static void locationAndStartup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.host.location = null; f.controller.startOrStop();
            check(AMapNavi.latest == null && f.host.refreshes == 1, "wait for location without submitting stale origin");
            Handler.advance(2000); f.host.fixAt(30); Handler.advance(1000);
            AMapNavi old = AMapNavi.latest;
            check(old != null, "new location automatically submits the route");
            f.host.fixAt(30.001); old.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            AMapNavi current = AMapNavi.latest;
            check(current != old && current.from.getLatitude() == 30.001, "origin moved over 60m automatically replans");
            old.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            check(f.host.begun == 0, "old planning callbacks cannot start replacement engine");
            current.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            check(current.starts == 1 && f.host.begun == 1, "replanned route starts without another tap");
        }
        try (Fixture f = new Fixture()) {
            AMapNavi engine = f.plan();
            f.controller.startOrStop();
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            Handler.advance(60000);
            check(engine.starts == 0 && f.controller.canRequestStart(), "cancellation keeps destination and invalidates callbacks/timers");
        }
        try (Fixture f = new Fixture()) {
            f.host.delayStart = true; AMapNavi engine = f.plan();
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            Runnable delayed = f.host.pendingStart;
            f.host.location = null; delayed.run(); Handler.advance(1000);
            check(engine.starts == 0 && f.host.finished == 0, "foreground handoff waits for a new fix without ending navigation");
            f.controller.detach(); f.host.fixAt(30); Handler.advance(1000);
            check(engine.starts == 1, "foreground service can finish starting after activity detaches");
        }
        try (Fixture f = new Fixture()) {
            f.host.delayStart = true; AMapNavi engine = f.plan();
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            Runnable late = f.host.pendingStart;
            f.controller.end(); late.run();
            check(engine.starts == 0, "late service start after explicit end is ignored");
        }
        try (Fixture f = new Fixture()) {
            f.host.location.setAccuracy(550); f.controller.startOrStop(); Handler.advance(60000);
            check(AMapNavi.latest == null && f.controller.canRequestStart(), "coarse 550m origin is never promoted or waited on forever");
        }
        try (Fixture f = new Fixture()) {
            AMapNavi engine = f.plan();
            for (int i = 1; i <= 3; i++) {
                f.host.fixAt(30 + i * 0.001);
                engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
                engine = AMapNavi.latest;
            }
            check(AMapNavi.instances == 3 && f.controller.canRequestStart(), "origin jitter has a bounded number of replans");
        }
    }
    static void failureAndDeadlines() throws Exception {
        try (Fixture f = new Fixture()) {
            AMapNavi engine = f.navigate(); engine.listener.onReCalculateRouteForYaw();
            Handler.advance(20000); engine.listener.onReCalculateRouteForYaw(); Handler.advance(10000);
            check(f.host.finished == 1 && f.controller.frame().mode() == NavigationFrame.STOPPED, "duplicate notices cannot extend reroute deadline");
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(1));
            check(engine.starts == 1 && f.controller.canRequestStart(), "late reroute result remains stopped");
        }
        try (Fixture f = new Fixture()) {
            AMapNavi engine = f.navigate(); engine.listener.onReCalculateRouteForTrafficJam();
            AMapCalcRouteResult failure = new AMapCalcRouteResult(2); failure.error = 2;
            engine.listener.onCalculateRouteFailure(failure);
            check(f.host.message.contains("连接失败") && f.host.finished == 1, "reroute failure reports the actual error and ends stale guidance");
        }
        try (Fixture f = new Fixture()) {
            AMapNavi engine = f.navigate(); engine.listener.onReCalculateRouteForYaw();
            engine.path.points = List.of(new NaviLatLng(Double.NaN,120), new NaviLatLng(30,120));
            engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(1));
            check(f.host.finished == 1 && f.controller.frame().mode() == NavigationFrame.STOPPED, "invalid reroute geometry never becomes live");
        }
        try (Fixture f = new Fixture()) {
            AMapNavi engine = f.plan();
            f.host.allowed = false; engine.listener.onCalculateRouteSuccess(new AMapCalcRouteResult(0));
            check(engine.starts == 0 && f.host.finished == 1, "permission revocation during calculation blocks navigation");
        }
    }
    public static void main(String[] args) throws Exception {
        drivingAndRerouting(); locationAndStartup(); failureAndDeadlines();
        System.out.println("JourneyController: " + checks + " checks passed (SDK/UI doubles; not device validation)");
    }
}
