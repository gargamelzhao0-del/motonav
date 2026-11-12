package com.motonav.companion;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.amap.api.location.AMapLocation;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.NaviSetting;
import com.amap.api.navi.SimpleNaviListener;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Android runtime and real SDK classes; online route entitlement is a separate phone test. */
@RunWith(AndroidJUnit4.class)
public class NavigationRuntimeTest {
    @Before public void requireDisposableEmulator() {
        assertTrue("These tests must run only on a disposable Android AVD",
            android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
    }
    static File evidence(String name) {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"emulator-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        return new File(directory,name);
    }
    static void writeEvidence(String name,String content) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(evidence(name))) {
            stream.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
    static void capture(Activity activity,String name) {
        View view = activity.getWindow().getDecorView();
        assertTrue(view.getWidth() > 0 && view.getHeight() > 0);
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream stream = new FileOutputStream(evidence(name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));
        } catch (Exception e) { throw new AssertionError(e); }
        finally { bitmap.recycle(); }
    }
    @Test public void activityLaunchAndRecreationUseActualAndroidLifecycle() {
        try (ActivityScenario<AmapActivity> scenario = ActivityScenario.launch(AmapActivity.class)) {
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> capture(activity,"map-unconfigured.png"));
            scenario.recreate();
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
            assertFalse(NavigationService.navigating);
        }
    }
    @Test public void realLocationObjectsReproduceExpiredMapStreamDespiteNewInstructions() throws Exception {
        long now = SystemClock.elapsedRealtime();
        Location point = new Location("gps");
        point.setLatitude(39.989614); point.setLongitude(116.481763);
        point.setAccuracy(8); point.setTime(System.currentTimeMillis());
        point.setElapsedRealtimeNanos(now*1_000_000L);
        MapFixTracker<Location> fixes = new MapFixTracker<>();
        assertTrue(fixes.offer(point,point.getElapsedRealtimeNanos(),8,MapFixTracker.SATELLITE,true));
        JourneyState state = navigating(now);
        assertEquals(JourneyState.Guidance.LIVE,state.guidance(now,point.getElapsedRealtimeNanos(),true));
        long stalled = now+16_000;
        assertNull(fixes.navigation(stalled*1_000_000L,true,true));
        assertEquals(JourneyState.Guidance.WAITING,state.guidance(stalled,0,true));
        point = new Location(point); point.setElapsedRealtimeNanos((stalled+1)*1_000_000L);
        assertTrue(fixes.offer(point,point.getElapsedRealtimeNanos(),8,MapFixTracker.SATELLITE,true));
        assertEquals(JourneyState.Guidance.LIVE,state.guidance(stalled+1,point.getElapsedRealtimeNanos(),true));
        writeEvidence("guidance-replay.txt","Actual Android Location + production state machine; deterministic timeline.\n"
            +"0ms fresh position + instruction -> LIVE\n16000ms expired map position + new instruction -> WAITING\n"
            +"16001ms new position + new instruction -> LIVE\nThis reproduces a possible cause, not the remote phone's proven cause.\n");
    }
    @Test public void freshLocationsDoNotHideAMissingInstructionStream() {
        long now = SystemClock.elapsedRealtime();
        JourneyState state = navigating(now);
        assertEquals(JourneyState.Guidance.LIVE,state.guidance(now,now*1_000_000L,true));
        assertEquals(JourneyState.Guidance.WAITING,state.guidance(now+16_000,(now+16_000)*1_000_000L,false));
        assertEquals(JourneyState.Guidance.LIVE,state.guidance(now+16_001,(now+16_001)*1_000_000L,true));
    }
    @Test public void actualAmapLocationRetainsMeasurementTimeAndCacheRejection() {
        AMapLocation location = new AMapLocation("gps");
        location.setLatitude(39.989614); location.setLongitude(116.481763); location.setAccuracy(8);
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()); location.setTime(System.currentTimeMillis());
        location.setLocationType(AMapLocation.LOCATION_TYPE_GPS);
        assertEquals(MapFixTracker.SATELLITE,AmapLocationSample.source(location));
        AMapLocation clone = location.clone();
        assertEquals(location.getElapsedRealtimeNanos(),clone.getElapsedRealtimeNanos());
        clone.setLocationType(AMapLocation.LOCATION_TYPE_LAST_LOCATION_CACHE);
        assertFalse(MapFixTracker.isLiveSource(AmapLocationSample.source(clone)));
    }
    @Test public void frameworkGpsReceivesActualEmulatorConsoleFixes() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.content.Context context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_COARSE_LOCATION);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_FINE_LOCATION);
        android.location.LocationManager manager = context.getSystemService(android.location.LocationManager.class);
        CountDownLatch received = new CountDownLatch(2);
        AtomicReference<Location> latest = new AtomicReference<>();
        android.location.LocationListener listener = new android.location.LocationListener() {
            @Override public void onLocationChanged(Location point) {
                if (Math.abs(point.getLatitude()-39.989614)<0.00001 && Math.abs(point.getLongitude()-116.481763)<0.00001) {
                    latest.set(new Location(point)); received.countDown();
                }
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider,int status,android.os.Bundle extras) { }
        };
        // Foreground-only location permission requires an actually resumed Activity.
        // Instrumentation running by itself is not evidence that the app is in the foreground.
        try (ActivityScenario<SdkReplayActivity> scenario = ActivityScenario.launch(SdkReplayActivity.class)) {
            assertTrue("Virtual GPS provider must be enabled",manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER));
            instrumentation.runOnMainSync(() -> manager.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER,0,0,listener,android.os.Looper.getMainLooper()));
            assertTrue("No framework GPS callbacks from the emulator console trace",received.await(45,TimeUnit.SECONDS));
            Location point = latest.get();
            assertEquals("gps",point.getProvider());
            long nowNanos = SystemClock.elapsedRealtimeNanos(), wallNow = System.currentTimeMillis();
            long measured = MapStartupPolicy.fixTime(nowNanos,wallNow,point.getElapsedRealtimeNanos(),point.getTime());
            String timing = "now_nanos="+nowNanos+"; elapsed_nanos="+point.getElapsedRealtimeNanos()
                +"; wall_now="+wallNow+"; wall_fix="+point.getTime()+"; normalized_nanos="+measured;
            writeEvidence("framework-gps.txt","Received at least two real Android GPS provider callbacks from adb emu geo fix.\n"
                +"Public fixture delivered. "+timing+"\nMock flag="+point.isFromMockProvider()
                +"; has_accuracy="+point.hasAccuracy()+"; accuracy="+point.getAccuracy()
                +"m.\nThis validates the virtual GPS pipeline, not physical GNSS or AMap online navigation.\n");
            assertTrue("GPS measurement rejected by production time normalization: "+timing,measured>0);
            assertTrue("GPS provider omitted accuracy",point.hasAccuracy());
        } finally { instrumentation.runOnMainSync(() -> manager.removeUpdates(listener)); }
    }
    @Test public void realSdkInitializationCallbackIsRecordedWithoutAServiceKey() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>();
        AtomicReference<AMapNavi> engine = new AtomicReference<>();
        SimpleNaviListener listener = new SimpleNaviListener() {
            @Override public void onInitNaviSuccess() { outcome.set("init_success"); completed.countDown(); }
            @Override public void onInitNaviFailure() { outcome.set("init_failure"); completed.countDown(); }
        };
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                NaviSetting.updatePrivacyShow(context,true,true); NaviSetting.updatePrivacyAgree(context,true);
                // Deliberately invalid fixture. No user's Key or signing secrets are supplied to emulator CI.
                AMapNavi.setApiKey(context,"00000000000000000000000000000000");
                try {
                    engine.set(AMapNavi.getInstance(context));
                    engine.get().addAMapNaviListener(listener);
                } catch (Exception e) { throw new AssertionError("SDK initialization threw a checked exception",e); }
            });
            assertTrue("Real SDK initialization never called back",completed.await(40,TimeUnit.SECONDS));
            writeEvidence("amap-sdk-runtime.txt","Real AMap SDK callback: "+outcome.get()
                +"\nNo valid Key supplied. No online route or actual GNSS navigation was verified.\n");
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (engine.get() != null) { engine.get().removeAMapNaviListener(listener); AMapNavi.destroy(); }
            });
        }
    }
    @Test public void simulationRequiresExplicitConfigurationAndCannotStartDuringNavigation() {
        try (ActivityScenario<SdkReplayActivity> scenario = ActivityScenario.launch(SdkReplayActivity.class)) {
            scenario.onActivity(activity -> {
                activity.startTest();
                assertFalse(NavigationService.navigating);
                assertTrue(allText(activity.getWindow().getDecorView()).contains("保存 Android Key"));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> capture(activity,"sdk-simulation-unconfigured.png"));
            scenario.recreate();
            scenario.onActivity(activity -> {
                NavigationService.navigating = true;
                try {
                    activity.startTest(); assertTrue(NavigationService.navigating);
                    assertTrue(allText(activity.getWindow().getDecorView()).contains("请先结束真实导航"));
                }
                finally { NavigationService.navigating = false; }
            });
        }
    }
    private static String allText(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof android.widget.TextView) text.append(((android.widget.TextView)view).getText());
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) text.append(allText(group.getChildAt(i)));
        }
        return text.toString();
    }
    @Test public void waitingReasonSeparatesActualSdkAndMapStreams() {
        long now = SystemClock.elapsedRealtime();
        assertEquals(GuidanceHealth.Reason.GPS_WEAK,GuidanceHealth.reason(now,true,now*1_000_000L,0,now));
        assertEquals(GuidanceHealth.Reason.MAP_FIX_STALE_SDK_LIVE,GuidanceHealth.reason(now,false,0,now*1_000_000L,now));
        assertEquals(GuidanceHealth.Reason.INSTRUCTION_MISSING,GuidanceHealth.reason(now,false,now*1_000_000L,0,-1));
    }
    private static JourneyState navigating(long now) {
        JourneyState state = new JourneyState(); long token=state.begin(true,true);
        assertTrue(state.ready(token,now)); assertTrue(state.start(true,now,0)); assertTrue(state.started(token));
        state.startGuidance(now); return state;
    }
}
