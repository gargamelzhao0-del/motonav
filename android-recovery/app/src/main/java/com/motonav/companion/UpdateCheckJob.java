package com.motonav.companion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Checks the release manifest and notifies. Downloads and installation stay in UpdateActivity. */
public final class UpdateCheckJob extends JobService {
    static final String CHECK_FROM_NOTIFICATION = "com.motonav.recovery.CHECK_UPDATE";
    private static final String CHANNEL = "app_update";
    private static final int JOB_ID = 6081;
    private static final int NOTIFICATION_ID = 6082;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UpdateTransport.Cancellation current;

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("update_reminders", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return preferences(context).getBoolean("enabled", false);
    }

    static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static boolean enable(Context context) {
        if (!canNotify(context)) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "MotoNav 版本更新", NotificationManager.IMPORTANCE_DEFAULT));
        if (!canNotify(context) || !schedule(context)) return false;
        preferences(context).edit().putBoolean("enabled", true).apply();
        return true;
    }

    static void disable(Context context) {
        preferences(context).edit().putBoolean("enabled", false).apply();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    static void restoreSchedule(Context context) {
        if (!enabled(context) || !canNotify(context)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null && scheduler.getPendingJob(JOB_ID) == null) schedule(context);
    }

    private static boolean schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return false;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(1))
                .setPersisted(true)
                .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!enabled(this) || !canNotify(this)) return false;
        UpdateTransport.Cancellation token = new UpdateTransport.Cancellation();
        current = token;
        worker.execute(() -> {
            UpdatePolicy.Release found;
            try {
                found = new UpdateRepository(this).fetchRelease(token);
            } catch (Exception e) {
                // Unpublished, current, offline, or invalid releases are retried at the next check.
                found = null;
            }
            UpdatePolicy.Release release = found;
            main.post(() -> {
                if (current != token || token.isCancelled()) return;
                current = null;
                try {
                    if (release != null) notifyIfNew(release);
                } finally {
                    jobFinished(params, false);
                }
            });
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        cancelCheck();
        return false;
    }

    @Override public void onDestroy() {
        cancelCheck();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void cancelCheck() {
        if (current == null) return;
        current.cancel();
        current = null;
    }

    private void notifyIfNew(UpdatePolicy.Release release) {
        SharedPreferences prefs = preferences(this);
        if (!enabled(this) || !canNotify(this)
                || release.versionCode <= prefs.getLong("last_notified_version", 0)) return;
        Intent open = new Intent(this, UpdateActivity.class).putExtra(CHECK_FROM_NOTIFICATION, true);
        PendingIntent tap = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("MotoNav 有新版本")
                .setContentText(release.versionName + " 已发布，点击查看更新。")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)) return;
        try {
            manager.notify(NOTIFICATION_ID, notification);
            prefs.edit().putLong("last_notified_version", release.versionCode).apply();
        } catch (SecurityException ignored) {
            // The user can revoke notification permission while the check is running.
        }
    }
}
