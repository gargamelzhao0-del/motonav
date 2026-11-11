package com.motonav.companion;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import com.amap.api.location.AMapLocation;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Explicit, expiring diagnosis sessions. No coordinates, SDK messages, keys or stable device IDs. */
final class Diagnostics {
    private static final Object LOCK = new Object(), FILE_LOCK = new Object();
    private static final long SESSION_MS = 60 * 60 * 1000L;
    private static final int FILE_BYTES = 512 * 1024, QUEUE_LIMIT = 512;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(256), task -> new Thread(task, "motonav-diagnostic-file"),
        new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(
        task -> new Thread(task, "motonav-diagnostic-upload"));
    private static final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private record Entry(long sequence, String line) {}
    private static Context context;
    private static File directory;
    private static boolean enabled;
    private static volatile boolean storageFailed;
    private static String session = "", transportState = "未连接";
    private static long started, sequence, lastDelivered, nextAttempt;
    private static int dropped, failures;
    private static DiagnosticEndpoint endpoint;
    private static HttpsURLConnection connection;
    private static ScheduledFuture<?> pump;
    private static final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            synchronized (LOCK) { if (!enabled) return; }
            if (SystemClock.elapsedRealtime() - started >= SESSION_MS) { stop(); return; }
            environment();
            main.postDelayed(this, 5000);
        }
    };

    static void initialize(Context value) {
        synchronized (LOCK) {
            if (context != null) return;
            context = value.getApplicationContext();
            directory = new File(context.getFilesDir(), "diagnostics");
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            int line = -1;
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("com.motonav.")) { line = frame.getLineNumber(); break; }
            }
            emit(true, "java_crash", "error_class", error.getClass().getSimpleName(), "line", line);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void start(DiagnosticEndpoint destination) {
        stop();
        synchronized (LOCK) {
            session = UUID.randomUUID().toString().replace("-", "");
            sequence = 0; started = SystemClock.elapsedRealtime(); lastDelivered = 0;
            dropped = 0; failures = 0; nextAttempt = 0; queue.clear(); endpoint = destination;
            transportState = destination == null ? "仅本地记录" : "正在连接";
            enabled = true;
        }
        record("session_start", "version", BuildConfig.VERSION_NAME, "version_code", BuildConfig.VERSION_CODE,
            "api", Build.VERSION.SDK_INT, "manufacturer", Build.MANUFACTURER, "model", Build.MODEL);
        synchronized (LOCK) {
            if (destination != null) pump = sender.scheduleWithFixedDelay(Diagnostics::upload, 0, 2, TimeUnit.SECONDS);
        }
        main.post(heartbeat);
    }

    static void stop() {
        record("session_stop", "reason", "user_or_expiry");
        synchronized (LOCK) {
            enabled = false; endpoint = null; queue.clear();
            if (pump != null) { pump.cancel(false); pump = null; }
            if (connection != null) connection.disconnect();
            connection = null;
        }
        main.removeCallbacks(heartbeat);
    }
    static boolean active() { synchronized (LOCK) { return enabled; } }
    static void record(String event, Object... fields) { emit(false, event, fields); }
    private static void emit(boolean immediate, String event, Object... fields) {
        String line;
        synchronized (LOCK) {
            if (!enabled || SystemClock.elapsedRealtime() - started >= SESSION_MS) return;
            try { line = DiagnosticRecord.line(session, ++sequence, SystemClock.elapsedRealtime() - started, event, fields); }
            catch (RuntimeException ignored) { return; }
            if (endpoint != null) {
                if (queue.size() >= QUEUE_LIMIT) { queue.removeFirst(); dropped++; }
                queue.addLast(new Entry(sequence, line));
            }
        }
        Log.i("MotoNavDiag", line);
        if (immediate) append(line);
        else try { writer.execute(() -> append(line)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { storageFailed = true; }
    }
    private static void append(String line) {
        synchronized (FILE_LOCK) {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException();
                File current = new File(directory, "current.jsonl"), previous = new File(directory, "previous.jsonl");
                byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                if (current.length() + bytes.length > FILE_BYTES) {
                    if (previous.exists() && !previous.delete()) throw new java.io.IOException();
                    if (!current.renameTo(previous)) throw new java.io.IOException();
                }
                try (FileOutputStream output = new FileOutputStream(current, true)) { output.write(bytes); }
            } catch (Exception ignored) { storageFailed = true; }
        }
    }
    static void export(OutputStream output, java.util.function.Consumer<Boolean> completed) {
        try { writer.execute(() -> {
            boolean success = false;
            try (OutputStream destination = output) {
                synchronized (FILE_LOCK) {
                    for (String name : new String[]{"previous.jsonl", "current.jsonl"}) {
                        File file = new File(directory, name);
                        if (!file.isFile()) continue;
                        try (InputStream input = new FileInputStream(file)) {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = input.read(buffer)) != -1) destination.write(buffer, 0, count);
                        }
                    }
                }
                success = true;
            } catch (Exception ignored) { }
            final boolean result = success; main.post(() -> completed.accept(result));
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) {
            try { output.close(); } catch (java.io.IOException ignoredClose) { }
            main.post(() -> completed.accept(false));
        }
    }
    static String status() {
        synchronized (LOCK) {
            String state = enabled ? "诊断记录中 · 剩余 " + Math.max(0, (SESSION_MS - (SystemClock.elapsedRealtime() - started)) / 60000) + " 分钟"
                : "诊断已停止；现有本地记录仍可导出";
            if (enabled) state += "\n" + transportState + " · 待发送 " + queue.size() + " 条 · 丢弃 " + dropped + " 条";
            if (enabled && lastDelivered > 0) state += "\n最近上传确认：" + (SystemClock.elapsedRealtime() - lastDelivered) / 1000 + " 秒前";
            if (enabled && endpoint != null) state += "\n接收端：" + endpoint.host;
            return state + (storageFailed ? "\n本地写入失败，请保留远程接收记录" : "");
        }
    }

    private static void upload() {
        DiagnosticEndpoint target; String currentSession; List<Entry> batch = new ArrayList<>();
        synchronized (LOCK) {
            if (!enabled || endpoint == null || queue.isEmpty() || SystemClock.elapsedRealtime() < nextAttempt) return;
            if (SystemClock.elapsedRealtime() - started >= SESSION_MS) { main.post(Diagnostics::stop); return; }
            target = endpoint; currentSession = session;
            int size = 0;
            for (Entry entry : queue) {
                int bytes = entry.line().getBytes(StandardCharsets.UTF_8).length + 1;
                if (batch.size() == 24 || size + bytes > 32768) break;
                batch.add(entry); size += bytes;
            }
        }
        if (batch.isEmpty()) return;
        HttpsURLConnection request = null; int responseCode = -1;
        try {
            StringBuilder body = new StringBuilder(); for (Entry item : batch) body.append(item.line()).append('\n');
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            request = (HttpsURLConnection) new URL(target.uploadUrl).openConnection();
            request.setRequestMethod("POST"); request.setDoOutput(true); request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(5000); request.setReadTimeout(5000); request.setUseCaches(false);
            request.setFixedLengthStreamingMode(bytes.length);
            request.setRequestProperty("Content-Type", "application/x-ndjson");
            request.setRequestProperty("Authorization", "Bearer " + target.token);
            synchronized (LOCK) {
                if (!enabled || endpoint != target || !session.equals(currentSession)) return;
                connection = request;
            }
            try (OutputStream output = request.getOutputStream()) { output.write(bytes); }
            responseCode = request.getResponseCode();
            if (responseCode != 200) throw new java.io.IOException();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (InputStream input = request.getInputStream()) {
                byte[] chunk = new byte[256]; int count;
                while ((count = input.read(chunk)) != -1) {
                    if (result.size() + count > 512) throw new java.io.IOException();
                    result.write(chunk, 0, count);
                }
            }
            long last = batch.get(batch.size() - 1).sequence();
            if (new JSONObject(result.toString("UTF-8")).getLong("last_seq") != last) throw new java.io.IOException();
            synchronized (LOCK) {
                if (!enabled || endpoint != target || !session.equals(currentSession)) return;
                while (!queue.isEmpty() && queue.peekFirst().sequence() <= last) queue.removeFirst();
                failures = 0; nextAttempt = 0; lastDelivered = SystemClock.elapsedRealtime(); transportState = "远程接收已确认";
            }
        } catch (Exception ignored) {
            synchronized (LOCK) {
                if (enabled && endpoint == target && session.equals(currentSession)) {
                    if (responseCode >= 400 && responseCode < 500 && responseCode != 429) {
                        endpoint = null; queue.clear(); transportState = "连接已失效（HTTP " + responseCode + "），请重新连接；本地仍记录";
                    } else {
                        nextAttempt = SystemClock.elapsedRealtime() + Math.min(60000, 2000L << Math.min(failures++, 5));
                        transportState = "上传未确认，等待重试";
                    }
                }
            }
        } finally {
            if (request != null) request.disconnect();
            synchronized (LOCK) { if (connection == request) connection = null; }
        }
    }

    private static void environment() {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            LocationManager location = context.getSystemService(LocationManager.class);
            ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = network == null ? null : network.getNetworkCapabilities(network.getActiveNetwork());
            boolean locationOn = location != null && (Build.VERSION.SDK_INT >= 28 ? location.isLocationEnabled()
                : location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            record("environment", "fine", context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                "coarse", context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                "system_location", locationOn, "network", caps != null,
                "wifi", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                "cellular", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                "vpn", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                "battery_saver", power != null && power.isPowerSaveMode(), "idle", power != null && power.isDeviceIdleMode(),
                "background_exempt", power != null && power.isIgnoringBatteryOptimizations(context.getPackageName()),
                "navigating", NavigationService.navigating, "dropped", dropped);
        } catch (RuntimeException ignored) { }
    }

    static void location(String owner, AMapLocation value, long gap, int generation) {
        if (!active()) return;
        if (value == null) { record("location_callback", "owner", owner, "reason", "null", "callback_gap_ms", gap, "generation", generation); return; }
        long now = SystemClock.elapsedRealtimeNanos();
        record("location_callback", "owner", owner, "error", value.getErrorCode(), "source", value.getLocationType(),
            "accuracy_m", value.getAccuracy(), "elapsed_age_ms", DiagnosticRecord.age(now, value.getElapsedRealtimeNanos()),
            "wall_age_ms", value.getTime() > 0 ? System.currentTimeMillis() - value.getTime() : -1,
            "callback_gap_ms", gap, "generation", generation, "mock", value.isFromMockProvider(),
            "satellites", value.getSatellites(), "gps_status", value.getGpsAccuracyStatus(), "trusted_level", value.getTrustedLevel());
    }
    static void fixState(String owner, MapFixTracker<?> fixes, boolean fine, boolean enabled) {
        if (!active()) return;
        long now = SystemClock.elapsedRealtimeNanos();
        record("location_state", "owner", owner, "reason", fixes.navigationReason(now, fine, enabled),
            "observation_age_ms", fixes.observationAgeMillis(now), "navigation_age_ms", fixes.navigationAgeMillis(now));
    }
}
