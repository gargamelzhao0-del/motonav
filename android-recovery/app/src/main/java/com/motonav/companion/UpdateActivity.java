package com.motonav.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ProgressBar;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground, explicit check -> download -> verify -> Android installer. Never auto-installs. */
public final class UpdateActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 6083;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UpdateRepository repository;
    private UpdateTransport.Cancellation operation;
    private UpdatePolicy.Release release;
    private File ready;
    private TextView status;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button check, download, install, cancel, reminders;
    private boolean foreground, destroyed, returningFromSettings, checkFromNotification;
    private AlertDialog permissionDialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        repository = new UpdateRepository(this);
        checkFromNotification = state == null && getIntent().getBooleanExtra(UpdateCheckJob.CHECK_FROM_NOTIFICATION, false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        page.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        if (android.os.Build.VERSION.SDK_INT >= 30) scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        text(page, "应用更新", 24);
        text(page, "当前版本 " + BuildConfig.VERSION_NAME, 16);
        text(page, "可手动检查，或开启新版提醒。开启后，Android 会在联网时定期查看 MotoNav 的 GitHub 发布，发现新版会发送通知。下载和安装需由你确认。", 15);
        text(page, "检查只访问更新发布源。临时签名测试包与正式签名不同时，无法覆盖安装。", 15);
        status = text(page, "尚未联网检查。", 16);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        page.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));
        progressText = text(page, "", 15);
        hideProgress();
        check = button(page, "检查更新", this::checkForUpdate);
        reminders = button(page, "开启新版提醒", this::toggleReminders);
        download = button(page, "下载并验证", this::downloadUpdate);
        install = button(page, "交给系统安装", this::requestInstall);
        cancel = button(page, "取消当前操作", () -> cancelOperation("已取消。未安装任何更新。"));
        button(page, "返回地图", this::finish);
        setContentView(scroll);
        refreshButtons();
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, 10, 0, 10); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String value, Runnable action) {
        Button view = new Button(this); view.setText(value); view.setAllCaps(false);
        view.setOnClickListener(v -> action.run()); parent.addView(view); return view;
    }
    private void refreshButtons() {
        if (check == null) return;
        boolean idle = foreground && operation == null && !destroyed;
        check.setEnabled(idle);
        download.setEnabled(idle && release != null && ready == null);
        install.setEnabled(idle && ready != null);
        cancel.setEnabled(foreground && operation != null);
        reminders.setEnabled(idle);
        boolean enabled = UpdateCheckJob.enabled(this);
        reminders.setText(enabled ? "关闭新版提醒" : "开启新版提醒");
        reminders.setContentDescription(enabled && !UpdateCheckJob.canNotify(this)
                ? "新版提醒已开启，系统通知权限已关闭" : reminders.getText());
    }

    private void toggleReminders() {
        if (UpdateCheckJob.enabled(this)) {
            UpdateCheckJob.disable(this);
            status.setText("已关闭新版提醒。仍可手动检查。");
        } else if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        } else {
            enableReminders();
        }
        refreshButtons();
    }

    private void enableReminders() {
        status.setText(UpdateCheckJob.enable(this)
                ? "新版提醒已开启。系统约每小时检查一次，实际时间由 Android 调度。"
                : "无法开启新版提醒。请检查系统通知设置，或使用手动检查。");
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != NOTIFICATION_PERMISSION_REQUEST || destroyed) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            enableReminders();
        } else {
            status.setText("未开启新版提醒。仍可手动检查。");
        }
        refreshButtons();
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (checkFromNotification) {
            checkFromNotification = false;
            checkForUpdate();
        }
    }

    private void checkForUpdate() {
        if (!foreground || operation != null) return;
        discardReady(); release = null;
        runWork("正在检查官方发布清单…", repository::check, found -> {
            release = found;
            status.setText("发现 " + found.versionName + "（" + found.versionCode + "），约 "
                    + ((found.sizeBytes + 1024 * 1024 - 1) / (1024 * 1024)) + " MiB。请自行选择下载。签名将在下载后验证。");
        }, false);
    }
    private void downloadUpdate() {
        if (!foreground || operation != null || release == null || ready != null) return;
        final UpdatePolicy.Release selected = release;
        runWork("正在连接下载服务…请停留在此页。", token -> repository.download(selected, token,
                new UpdateRepository.Progress() {
                    @Override public void transferred(long bytes, long total) { downloadProgress(token, bytes, total, false); }
                    @Override public void verifying() { downloadProgress(token, selected.sizeBytes, selected.sizeBytes, true); }
                }), file -> {
            ready = file;
            progressBar.setVisibility(View.VISIBLE); progressBar.setIndeterminate(false); progressBar.setProgress(100);
            progressText.setVisibility(View.VISIBLE); progressText.setText("下载与校验完成 · 等待安装");
            status.setText("安装包已验证，请点击「交给系统安装」，再按系统提示完成更新。");
        }, false);
    }

    private void downloadProgress(UpdateTransport.Cancellation token, long bytes, long total, boolean verifying) {
        main.post(() -> {
            if (destroyed || !foreground || operation != token || token.isCancelled()) return;
            progressBar.setVisibility(View.VISIBLE);
            progressText.setVisibility(View.VISIBLE);
            if (verifying) {
                progressBar.setIndeterminate(true);
                status.setText("下载完成，正在校验安装包…");
                progressText.setText("请稍候，校验完成后可安装。");
                return;
            }
            int percent = total > 0 ? (int) Math.min(100, bytes * 100 / total) : 0;
            progressBar.setIndeterminate(total <= 0); progressBar.setProgress(percent);
            status.setText("正在下载更新…离开本页或点取消会停止。");
            progressText.setText(total > 0 ? String.format(Locale.getDefault(), "已下载 %.1f / %.1f MB · %d%%",
                    bytes / 1_000_000.0, total / 1_000_000.0, percent)
                    : String.format(Locale.getDefault(), "已下载 %.1f MB", bytes / 1_000_000.0));
        });
    }

    private void hideProgress() {
        progressBar.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        progressText.setText("");
    }
    private void requestInstall() {
        if (!foreground || operation != null || ready == null || release == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            if (permissionDialog != null) return;
            permissionDialog = new AlertDialog.Builder(this).setTitle("允许此应用请求安装？")
                    .setMessage("Android 尚未允许本应用安装未知来源的应用。可自行前往系统设置开启；返回后仍须再次点击安装，并在系统安装器确认。也可以取消，更新不会安装。")
                    .setNegativeButton("取消", null).setPositiveButton("打开系统设置", (dialog, which) -> {
                        if (!foreground) return;
                        try {
                            returningFromSettings = true;
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException | SecurityException e) {
                            returningFromSettings = false;
                            status.setText("无法打开安装来源设置。请自行检查系统设置；未发起安装。");
                        }
                    }).create();
            permissionDialog.setOnDismissListener(dialog -> permissionDialog = null);
            permissionDialog.show();
            return;
        }
        final File selectedFile = ready;
        final UpdatePolicy.Release selected = release;
        runWork("正在重新验证安装文件…", token -> {
            repository.verifyForInstall(selectedFile, selected, token);
            return Boolean.TRUE;
        }, valid -> launchInstaller(selectedFile), true);
    }

    private void launchInstaller(File file) {
        if (!foreground || destroyed || ready != file) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText("安装来源权限已变化。未发起安装，请再次点击安装检查。"); return;
        }
        Uri uri = UpdateApkProvider.uri(this, file);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, UpdateApkProvider.MIME);
        intent.setClipData(ClipData.newRawUri("MotoNav update", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
            // Keep the immutable file until normal cache expiry, even if this activity is destroyed.
            // Starting the installer is NOT evidence that the user accepted or installation succeeded.
            ready = null; release = null;
            hideProgress();
            status.setText("已交给 Android 安装器；是否安装由你和系统决定。这里不把打开安装器视为安装成功。");
        } catch (ActivityNotFoundException | SecurityException e) {
            status.setText("系统安装器不可用或拒绝请求。未确认任何安装。");
        }
    }

    private interface Work<T> { T run(UpdateTransport.Cancellation token) throws Exception; }
    private interface Completed<T> { void run(T result); }
    private <T> void runWork(String message, Work<T> task, Completed<T> completed, boolean invalidateOnFailure) {
        if (!foreground || destroyed || operation != null) return;
        UpdateTransport.Cancellation token = new UpdateTransport.Cancellation();
        operation = token;
        progressBar.setVisibility(View.VISIBLE); progressBar.setIndeterminate(true);
        progressText.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText(message); refreshButtons();
        worker.execute(() -> {
            T value = null; Exception failure = null;
            try { value = task.run(token); } catch (Exception e) { failure = e; }
            final T result = value;
            final Exception error = failure;
            main.post(() -> {
                if (destroyed || !foreground || operation != token || token.isCancelled()) {
                    if (result instanceof File) ((File) result).delete();
                    return;
                }
                operation = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                hideProgress();
                if (error != null) {
                    if (invalidateOnFailure) discardReady();
                    status.setText(failureMessage(error));
                } else completed.run(result);
                refreshButtons();
            });
        });
    }
    private static String failureMessage(Exception error) {
        // Never display exception details, URLs, paths, SDK data or diagnostics that could contain secrets.
        String code = error.getMessage();
        if ("not_published".equals(code)) return "官方发布尚未提供更新清单或文件。未下载或安装更新。";
        if ("version_not_newer".equals(code)) return "发布清单未提供更高版本，不允许同版本覆盖或降级。";
        if ("signer_mismatch".equals(code)) return "APK 签名与本机安装不完全一致，已拒绝更新。临时 CI 签名不能直接迁移到稳定签名；不会自动卸载或清除数据。";
        if ("unsupported_android".equals(code)) return "该更新不支持当前 Android 版本，已拒绝更新。";
        if ("hash_mismatch".equals(code)) return "SHA-256 不匹配，已丢弃下载。未发起安装。";
        return "检查或验证未通过（网络、发布配置、文件或设备限制）。未发起安装；请稍后重试。";
    }
    private void cancelOperation(String message) {
        if (operation != null) {
            operation.cancel(); operation = null;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            hideProgress();
            status.setText(message);
        }
        refreshButtons();
    }
    private void discardReady() { if (ready != null) { ready.delete(); ready = null; } }
    @Override protected void onResume() {
        super.onResume(); foreground = true;
        UpdateCheckJob.restoreSchedule(this);
        if (returningFromSettings) {
            returningFromSettings = false;
            status.setText(getPackageManager().canRequestPackageInstalls()
                    ? "已允许请求安装。请再次点击“交给系统安装”；不会自动继续。"
                    : "未获得安装来源权限。未发起安装，可重试或返回。");
        }
        refreshButtons();
    }
    @Override protected void onPause() {
        foreground = false;
        cancelOperation("操作因离开更新页面而取消。请返回后重新操作。");
        if (permissionDialog != null) { permissionDialog.dismiss(); permissionDialog = null; }
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; foreground = false;
        cancelOperation("已停止更新。"); worker.shutdownNow(); discardReady();
        super.onDestroy();
    }
}
