package com.motonav.companion;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.File;
import java.util.concurrent.*;

/** User-started foreground download. The activity may detach without cancelling the transfer. */
public final class UpdateDownloadService extends Service {
    private static final String START = "download", PAUSE = "pause", CHANNEL = "update_download";
    private static final int NOTICE = 6085;
    record Snapshot(UpdatePolicy.Release release, File ready, boolean busy, long bytes, boolean verifying, String message) {}
    public final class LocalBinder extends Binder { UpdateDownloadService service() { return UpdateDownloadService.this; } }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LocalBinder binder = new LocalBinder();
    private UpdateRepository repository;
    private UpdatePolicy.Release release;
    private File ready;
    private UpdateTransport.Cancellation operation;
    private long bytes;
    private boolean verifying, destroyed;
    private String message = "";
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate(); repository = new UpdateRepository(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL,"更新下载",NotificationManager.IMPORTANCE_LOW));
        SharedPreferences prefs = getSharedPreferences("download_state",MODE_PRIVATE);
        try {
            String stored = prefs.getString("release",null);
            if (stored != null) {
                release = repository.decodeRelease(stored);
                String name = prefs.getString("ready",null);
                if (UpdatePolicy.sharedFilename(name)) {
                    File candidate = new File(UpdateApkProvider.directory(this),name).getCanonicalFile();
                    if (candidate.isFile() && candidate.getParentFile().equals(UpdateApkProvider.directory(this).getCanonicalFile())) ready = candidate;
                }
                bytes = ready == null ? repository.partialBytes(release) : release.sizeBytes;
                message = ready == null ? "上次下载已暂停，点击下载可从断点继续。" : "安装包已保存，安装前将再次校验。";
            }
        } catch (Exception e) { release = null; ready = null; prefs.edit().clear().apply(); }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    Snapshot snapshot() { return new Snapshot(release,ready,operation!=null,bytes,verifying,message); }
    static void begin(Context context, UpdatePolicy.Release release) {
        context.startForegroundService(new Intent(context,UpdateDownloadService.class).setAction(START)
                .putExtra("release",UpdateRepository.encodeRelease(release)));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && PAUSE.equals(intent.getAction())) { pause(); return START_NOT_STICKY; }
        if (operation != null) return START_NOT_STICKY;
        if (intent == null || !START.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try {
            release = repository.decodeRelease(intent.getStringExtra("release")); ready = null;
            bytes = repository.partialBytes(release); verifying = false;
            message = "正在下载更新，可锁屏或返回地图。";
            persist();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE,notification(true),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTICE,notification(true));
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MotoNav:update");
            wakeLock.setReferenceCounted(false); wakeLock.acquire(10 * 60 * 1000L);
        } catch (Exception e) { message = "无法启动后台下载，请检查系统限制后重试。"; finishForeground(); return START_NOT_STICKY; }
        final UpdateTransport.Cancellation token = new UpdateTransport.Cancellation(); operation = token;
        final UpdatePolicy.Release selected = release;
        worker.execute(() -> {
            File result = null; Exception error = null;
            try { result = repository.download(selected, token, new UpdateRepository.Progress() {
                public void transferred(long done,long total) { progress(token,done,false); }
                public void verifying() { progress(token,selected.sizeBytes,true); }
            }); } catch (Exception e) { error = e; }
            File file = result; Exception failure = error;
            main.post(() -> {
                if (destroyed || operation != token || token.isCancelled()) { if (file != null) file.delete(); return; }
                operation = null; verifying = false;
                if (failure == null) { ready = file; bytes = selected.sizeBytes; message = "下载与校验完成，打开更新页面确认安装。"; }
                else { bytes = repository.partialBytes(selected); message = UpdateActivity.failureMessage(failure) + " 已保留可用断点，可继续下载。"; }
                persist(); finishForeground();
                try { getSystemService(NotificationManager.class).notify(NOTICE,notification(false)); } catch (SecurityException ignored) {}
            });
        });
        return START_NOT_STICKY;
    }
    private void progress(UpdateTransport.Cancellation token,long done,boolean checking) {
        main.post(() -> {
            if (destroyed || operation != token || token.isCancelled()) return;
            bytes = done; verifying = checking;
            message = checking ? "下载完成，正在校验安装包…" : "正在下载更新，可锁屏或返回地图。";
            try { getSystemService(NotificationManager.class).notify(NOTICE,notification(true)); } catch (SecurityException ignored) {}
        });
    }
    void pause() {
        if (operation != null) { operation.cancel(); operation = null; }
        verifying = false;
        if (release != null) bytes = repository.partialBytes(release);
        message = "下载已暂停，断点已保留。";
        persist(); finishForeground();
    }
    void forget() {
        if (operation != null) return;
        release = null; ready = null; bytes = 0; verifying = false; message = "";
        getSharedPreferences("download_state",MODE_PRIVATE).edit().clear().apply();
        getSystemService(NotificationManager.class).cancel(NOTICE);
    }
    private void persist() {
        if (release == null) return;
        getSharedPreferences("download_state",MODE_PRIVATE).edit().putString("release",UpdateRepository.encodeRelease(release))
                .putString("ready",ready == null ? null : ready.getName()).apply();
    }
    private Notification notification(boolean busy) {
        PendingIntent open = PendingIntent.getActivity(this,NOTICE,new Intent(this,UpdateActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("MotoNav 应用更新").setContentText(message).setContentIntent(open).setOnlyAlertOnce(true)
                .setOngoing(busy).setAutoCancel(!busy);
        if (busy) {
            builder.setProgress(100,release == null ? 0 : (int)(bytes*100/release.sizeBytes),verifying);
            PendingIntent pause = PendingIntent.getService(this,NOTICE,new Intent(this,UpdateDownloadService.class).setAction(PAUSE),PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,"暂停下载",pause).build());
        }
        return builder.build();
    }
    private void finishForeground() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onTimeout(int startId,int type) { pause(); }
    @Override public void onDestroy() {
        destroyed = true;
        if (operation != null) { operation.cancel(); operation = null; }
        worker.shutdownNow(); main.removeCallbacksAndMessages(null); finishForeground();
        super.onDestroy();
    }
}
