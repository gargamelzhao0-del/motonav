package com.motonav.companion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;

/** User-controlled remote session; this screen never starts location or reads map credentials. */
public final class DiagnosticsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView state, result, mapState;
    private EditText link;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { AppUi.setText(state,Diagnostics.status()); AppUi.setText(mapState,Diagnostics.mapStatus());main.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page=AppUi.page(this,"问题诊断","遇到定位或导航异常时，记录一次复现过程。");
        ScrollView scroll=AppUi.scroll(this,page);
        LinearLayout summary=AppUi.card(this);state=AppUi.text(this,Diagnostics.status(),15,AppUi.ACCENT);summary.addView(state);AppUi.add(page,summary,18);
        LinearLayout mapCard=AppUi.card(this);mapCard.addView(AppUi.text(this,"圆屏底图完整性",17,AppUi.TEXT));
        mapState=AppUi.text(this,Diagnostics.mapStatus(),13,AppUi.MUTED);mapState.setTextIsSelectable(true);mapCard.addView(mapState);
        AppUi.add(mapCard,AppUi.button(this,"复制底图统计",false,this::copyMapStatus),10);AppUi.add(page,mapCard,12);
        text(page, "仅记录定位质量、权限和错误等诊断信息，不含经纬度、目的地、车牌或高德 Key。", 13);
        text(page, "手机连接圆屏，圆屏用 USB 接电脑，即可转发关键状态。默认持续 1 小时，可随时停止。", 13);
        android.widget.Switch extended = new android.widget.Switch(this); extended.setText("本次诊断持续 4 小时");extended.setTextColor(AppUi.TEXT);extended.setMinHeight(AppUi.dp(this,56));page.addView(extended);
        result = text(page, "默认关闭。蓝牙诊断无需填写公网连接；完整记录仍保留在手机。", 13);
        result.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(page, "开始蓝牙实时诊断", () -> {
            Diagnostics.startBluetooth(extended.isChecked());
            result.setText("已开启。请返回真实地图，连接圆屏并复现问题。圆屏需支持蓝牙诊断，且通过 USB 连接电脑。");
        });
        text(page, "以下连接框仅用于公网诊断，可留空。", 13);
        link = new EditText(this); AppUi.field(link,"本次诊断连接（含连接凭证）"); link.setSingleLine(true);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        link.setSaveEnabled(false); link.setSaveFromParentEnabled(false);
        link.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        AppUi.add(page,link,14);
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
                Diagnostics.start(endpoint, extended.isChecked()); link.getText().clear();link.setError(null);
                result.setText("已开始。请返回地图复现问题；看到异常时可回来标记。");
            } catch (IllegalArgumentException error) { link.setError("请粘贴完整的本次诊断连接");link.requestFocus();result.setText("连接格式无效，请粘贴完整的本次诊断连接。"); }
        });
        button(page, "仅在手机记录", () -> { Diagnostics.start(null, extended.isChecked()); result.setText("本地诊断已开始，稍后可以导出。"); });
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
        TextView details=AppUi.text(this,"蓝牙诊断每秒最多发送一份定位质量、指令年龄、实际导航发送状态和回执摘要。导航数据优先；断连或拥堵时保留最新状态，不补发旧摘要。圆屏接收后通过 USB 输出，手机显示的接收数量不代表电脑已保存。完整日志保留在手机，可导出。公网模式仍使用有效的 HTTPS 诊断连接；进程退出后需手动重新开启。",13,AppUi.MUTED);
        details.setVisibility(android.view.View.GONE);
        Button explanation=AppUi.button(this,"查看记录与上传说明",false,()->{});
        explanation.setOnClickListener(view->{boolean show=details.getVisibility()!=android.view.View.VISIBLE;details.setVisibility(show?android.view.View.VISIBLE:android.view.View.GONE);explanation.setText(show?"收起记录与上传说明":"查看记录与上传说明");});
        AppUi.add(page,explanation,16);AppUi.add(page,details,10);
        setContentView(scroll);
    }
    private void copyMapStatus() {
        ClipboardManager clipboard=getSystemService(ClipboardManager.class);
        if(clipboard==null){result.setText("剪贴板不可用，可长按底图统计文字选择复制，或截屏发送。");return;}
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("MotoNav 底图统计",
                "MotoNav "+BuildConfig.VERSION_NAME+"\n"+Diagnostics.mapStatus()));
            result.setText("底图统计已复制，可粘贴发送。");
            android.widget.Toast.makeText(this,"底图统计已复制",android.widget.Toast.LENGTH_SHORT).show();
        } catch(SecurityException error) { result.setText("系统不允许访问剪贴板，可截屏发送底图统计。"); }
    }
    private TextView text(LinearLayout page, String value, int size) {
        TextView text=AppUi.text(this,value,size,AppUi.MUTED);AppUi.add(page,text,12);return text;
    }
    private void button(LinearLayout page, String title, Runnable action) {
        Button button=AppUi.button(this,title,title.equals("开始蓝牙实时诊断"),action);AppUi.add(page,button,10);
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
