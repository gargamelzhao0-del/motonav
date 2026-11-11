package com.motonav.companion;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;

/** User-controlled remote session; this screen never starts location or reads map credentials. */
public final class DiagnosticsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView state, result;
    private EditText link;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { state.setText(Diagnostics.status()); main.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(page);
        if (android.os.Build.VERSION.SDK_INT >= 30) scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        text(page, "定位诊断 / 实时日志", 23);
        text(page, "记录定位来源、精度、数据年龄、权限、省电状态、算路阶段和错误码。不会记录经纬度、目的地、车牌或高德 Key。", 15);
        text(page, "粘贴本次诊断连接后，点击开始。日志将经 HTTPS 和 Cloudflare 临时通道发送到调试电脑。返回地图后继续记录，可随时停止；每次最长 60 分钟，退出进程后需重新开启。", 15);
        link = new EditText(this); link.setHint("本次诊断连接（含连接凭证）"); link.setSingleLine(true);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        link.setSaveEnabled(false); link.setSaveFromParentEnabled(false);
        link.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        page.addView(link);
        button(page, "粘贴诊断连接", () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (text != null && text.length() <= 1024) link.setText(text);
            }
        });
        button(page, "开始实时诊断", () -> {
            try {
                DiagnosticEndpoint endpoint = DiagnosticEndpoint.parse(link.getText().toString());
                Diagnostics.start(endpoint); link.getText().clear();
                result.setText("已开始。请返回地图复现问题；看到异常时可回来标记。");
            } catch (IllegalArgumentException error) { result.setText("连接格式无效，请粘贴完整的本次诊断连接。"); }
        });
        button(page, "仅在手机记录", () -> { Diagnostics.start(null); result.setText("本地诊断已开始，稍后可以导出。"); });
        button(page, "标记：刚刚出现问题", () -> {
            if (Diagnostics.active()) { Diagnostics.record("user_mark", "reason", "problem_observed"); result.setText("已记录问题标记。"); }
            else result.setText("请先开始诊断。");
        });
        button(page, "停止诊断与上传", () -> { Diagnostics.stop(); result.setText("已停止。现有本地记录仍可导出。"); });
        button(page, "导出最近诊断文件", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-ndjson").putExtra(Intent.EXTRA_TITLE, "MotoNav-diagnostics.jsonl");
            try { startActivityForResult(intent, 6084); }
            catch (android.content.ActivityNotFoundException error) { result.setText("系统文件保存器不可用。"); }
        });
        button(page, "返回地图复现问题", this::finish);
        state = text(page, "", 15); result = text(page, "默认不记录、不上传。只在本次主动开启后生效。", 14);
        setContentView(scroll);
    }
    private TextView text(LinearLayout page, String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size);
        text.setPadding(0, 10, 0, 10); page.addView(text); return text;
    }
    private void button(LinearLayout page, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(view -> action.run()); page.addView(button);
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != 6084 || code != RESULT_OK || data == null || data.getData() == null) return;
        try {
            OutputStream output = getContentResolver().openOutputStream(data.getData(), "w");
            if (output == null) throw new java.io.IOException();
            Diagnostics.export(output, success -> {
                if (!isFinishing() && !isDestroyed()) result.setText(success ? "诊断文件已导出。" : "导出失败，请重试。");
            });
        } catch (Exception error) { result.setText("无法写入所选文件。"); }
    }
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
