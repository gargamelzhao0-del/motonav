package com.motonav.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.amap.api.location.AMapLocation;
import com.amap.api.maps.AMap;
import java.security.SecureRandom;

/** Holds the navigation session across activity pauses and recreation. */
public final class NavigationService extends Service implements JourneyController.Host, BleLink.Listener {
    private static final String START = "com.motonav.companion.START_NAVIGATION";
    private static final String STOP = "com.motonav.companion.STOP_NAVIGATION";
    private static final String CHANNEL = "navigation";
    private static final int NOTIFICATION = 300;
    static volatile boolean navigating;

    interface View extends JourneyController.Ui {
        Location fix(); long fixNanos(); void refreshLocation(); void bleStatus(String message); void found(BluetoothDevice device);
    }
    public final class LocalBinder extends Binder { NavigationService service() { return NavigationService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MapFixTracker<Location> fixes = new MapFixTracker<>();
    private final AmapKeySession keys = new AmapKeySession();
    private SavedMapConfig saved;
    private JourneyController journey;
    private AmapLocationFeed feed;
    private BleLink link;
    private View view;
    private BluetoothDevice selectedDevice;
    private PowerManager.WakeLock wakeLock;
    private Runnable pendingStart;
    private boolean destroyed, stopping, reconnecting;
    private int retries;
    private String bleMessage = "圆屏尚未连接";
    private String notificationText = "";
    private final Runnable reconnect = () -> {
        reconnecting = false;
        if (navigating && !stopping && canReconnect() && !link.sessionOpen()) link.connect(selectedDevice);
    };
    private final Runnable stopDeadline = this::shutdownForeground;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!navigating) return;
            if (!stopping) {
                if (!navigationAllowed()) { journey.end(); return; }
                feed.maintain();
                Diagnostics.fixState("navigation", fixes, precisePermission(), locationEnabled());
                journey.refreshGuidance();
            }
            publish();
            if (navigating) handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        Diagnostics.record("lifecycle", "owner", "navigation", "state", "create");
        saved = new SavedMapConfig(this);
        journey = new JourneyController(this, this);
        feed = new AmapLocationFeed(this, this::receiveLocation);
        link = new BleLink(getApplicationContext(), this);
        link.configureNavigation(new SecureRandom().nextInt(Integer.MAX_VALUE - 1) + 1);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "正在导航", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    void attach(Activity activity, AMap map, View next) {
        if (!saved.consent()) return;
        try { keys.replace(saved.load()); } catch (Exception e) { next.report("无法读取地图配置，请重新保存 Key。"); return; }
        view = next;
        journey.attach(activity, map, next);
        next.bleStatus(bleMessage);
    }

    void detach(View previous) {
        if (view != previous) return;
        journey.detach();
        view = null;
        link.stopScan();
    }

    JourneyController journey() { return journey; }
    void scan() { selectedDevice = null; handler.removeCallbacks(reconnect); reconnecting = false; link.scan(); }
    void connect(BluetoothDevice device) {
        handler.removeCallbacks(reconnect); reconnecting = false; retries = 0;
        selectedDevice = device;
        link.connect(device);
    }
    void disconnectScreen() {
        selectedDevice = null;
        handler.removeCallbacks(reconnect); reconnecting = false;
        link.disconnect(); status("已断开圆屏");
    }

    private boolean precisePermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationEnabled() {
        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null) return false;
        try {
            return Build.VERSION.SDK_INT >= 28 ? manager.isLocationEnabled()
                    : manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException e) { return false; }
    }

    private boolean bluetoothAllowed() {
        return Build.VERSION.SDK_INT < 31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED);
    }

    @SuppressLint("MissingPermission")
    private boolean canReconnect() {
        if (selectedDevice == null || !bluetoothAllowed()) return false;
        try { return selectedDevice.getBondState() == BluetoothDevice.BOND_BONDED; }
        catch (SecurityException e) { return false; }
    }

    @Override public boolean active() { return !destroyed && saved.consent() && keys.configured(); }
    @Override public boolean navigationAllowed() { return active() && precisePermission() && locationEnabled(); }
    @Override public String key() { return keys.sdkValue(); }
    @Override public boolean voiceEnabled() { return getSharedPreferences("navigation_ui", MODE_PRIVATE).getBoolean("voice_enabled", true); }
    @Override public void refreshLocation() { if (view != null) view.refreshLocation(); }
    @Override public Location fix() {
        if (!navigating) return view == null ? null : view.fix();
        MapFixTracker.Fix<Location> fix = fixes.navigation(SystemClock.elapsedRealtimeNanos(), precisePermission(), locationEnabled());
        return fix == null ? null : new Location(fix.point());
    }
    @Override public long fixNanos() {
        if (!navigating) return view == null ? 0 : view.fixNanos();
        MapFixTracker.Fix<Location> fix = fixes.navigation(SystemClock.elapsedRealtimeNanos(), precisePermission(), locationEnabled());
        return fix == null ? 0 : fix.timeNanos();
    }

    @Override public boolean beginNavigation(Runnable start) {
        if (stopping) shutdownForeground();
        if (view == null || !navigationAllowed() || pendingStart != null || navigating) return false;
        Location initial = fix();
        long initialTime = fixNanos();
        fixes.clear();
        if (initial != null && initialTime > 0)
            fixes.offer(new Location(initial), initialTime, initial.getAccuracy(), MapFixTracker.SYSTEM_NETWORK, true);
        pendingStart = start;
        try {
            startForegroundService(new Intent(this, NavigationService.class).setAction(START));
            return true;
        } catch (RuntimeException e) { pendingStart = null; fixes.clear(); return false; }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { journey.end(); return START_NOT_STICKY; }
        Runnable start = pendingStart;
        pendingStart = null;
        if (intent == null || !START.equals(intent.getAction()) || start == null || !navigationAllowed()) {
            if (!navigating) { journey.end(); stopSelf(); }
            return START_NOT_STICKY;
        }
        try {
            Notification notification = notification("等待实时导航指令");
            if (Build.VERSION.SDK_INT >= 29) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                if (bluetoothAllowed()) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                startForeground(NOTIFICATION, notification, types);
            } else startForeground(NOTIFICATION, notification);
            navigating = true; stopping = false; retries = 0;
            Diagnostics.record("navigation_state", "state", "foreground_started");
            acquireWakeLock();
            feed.maintain();
            start.run();
            if (selectedDevice != null && !link.sessionOpen()) reconnect.run();
            handler.removeCallbacks(tick); handler.post(tick);
        } catch (RuntimeException | LinkageError e) {
            journey.end(); shutdownForeground();
            if (view != null) view.report("后台导航启动失败，请检查系统权限后重试。");
        }
        // A killed process has no trustworthy in-memory route to resume automatically.
        return START_NOT_STICKY;
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoNav:navigation");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, AmapActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, NavigationService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_motonav)
                .setContentTitle("MotoNav 正在导航").setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_NAVIGATION)
                .addAction(new Notification.Action.Builder(null, "结束导航", stop).build()).build();
    }

    private void publish() {
        NavigationFrame frame = journey.frame();
        if (link.ready() && !link.busy()) link.sendNavigation(frame, journey.deviceMap(), stopping ? this::shutdownForeground : null);
        if (!navigating || stopping) return;
        String text = frame.mode() == NavigationFrame.LIVE ? JourneyState.maneuver(frame.icon()) + " · " + frame.turnMeters() + " 米" : "等待可信定位和导航指令";
        if (!text.equals(notificationText)) {
            notificationText = text;
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
        }
    }

    private void receiveLocation(AMapLocation location) {
        if (!navigating || stopping || !navigationAllowed() || location == null || location.getErrorCode() != 0) {
            Diagnostics.record("location_decision", "owner", "navigation", "reason", "inactive_permission_or_sdk_error"); return;
        }
        if (!MapStartupPolicy.validFix(location.getLatitude(), location.getLongitude(), location.getAccuracy(), location.isFromMockProvider())) return;
        long time = MapStartupPolicy.fixTime(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(), location.getElapsedRealtimeNanos(), location.getTime());
        if (time == 0) { Diagnostics.record("location_decision", "owner", "navigation", "reason", "measurement_expired_or_invalid_time"); return; }
        Location fix = new Location(location.getProvider());
        fix.setLatitude(location.getLatitude()); fix.setLongitude(location.getLongitude());
        fix.setAccuracy(location.getAccuracy()); fix.setTime(location.getTime()); fix.setElapsedRealtimeNanos(time);
        if (location.hasSpeed()) fix.setSpeed(location.getSpeed());
        if (location.hasBearing()) fix.setBearing(location.getBearing());
        int source = AmapLocationSample.source(location);
        boolean accepted = fixes.offer(fix, time, location.getAccuracy(), source, precisePermission());
        Diagnostics.record("location_decision", "owner", "navigation", "accepted", accepted, "source", source,
                "reason", accepted ? "accepted" : "source_or_order_rejected");
    }

    @Override public void finished() {
        pendingStart = null;
        feed.stop(); fixes.clear();
        if (!navigating) { publish(); return; }
        stopping = true;
        handler.removeCallbacks(reconnect); reconnecting = false;
        handler.removeCallbacks(stopDeadline); handler.postDelayed(stopDeadline, 3000);
        if (!link.ready()) shutdownForeground(); else publish();
    }

    private void shutdownForeground() {
        if (navigating) Diagnostics.record("navigation_state", "state", "foreground_stopped");
        navigating = false; stopping = false; pendingStart = null;
        handler.removeCallbacks(tick); handler.removeCallbacks(stopDeadline); handler.removeCallbacks(reconnect);
        reconnecting = false;
        feed.stop(); fixes.clear(); link.disconnect();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null; notificationText = "";
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    void revoke() { journey.end(); shutdownForeground(); keys.clear(); }

    @Override public void status(String message) { bleMessage = message; if (view != null) view.bleStatus(message); }
    @Override public void found(BluetoothDevice device) { if (view != null) view.found(device); }
    @Override public void ready() {
        handler.removeCallbacks(reconnect); reconnecting = false; retries = 0;
        Diagnostics.record("ble_state", "state", "ready");
        status(link.routeMaps() ? "圆屏已连接 · 支持完整圆屏路线地图" : "圆屏已连接 · 箭头导航（路线地图需要升级固件）"); publish();
    }
    @Override public void acknowledged(int sequence) { }
    @Override public void disconnected() {
        if (destroyed) return;
        Diagnostics.record("ble_state", "state", "disconnected");
        status("圆屏已断开");
        if (!navigating || stopping || !canReconnect() || reconnecting) return;
        reconnecting = true;
        handler.postDelayed(reconnect, Math.min(15000, 2000L << Math.min(retries++, 3)));
    }

    @Override public void onDestroy() {
        destroyed = true;
        journey.close(); shutdownForeground(); keys.clear(); view = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
