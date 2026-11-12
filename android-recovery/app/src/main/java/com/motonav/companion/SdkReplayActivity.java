package com.motonav.companion;

import android.app.Activity;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;
import com.amap.api.navi.*;
import com.amap.api.navi.enums.*;
import com.amap.api.navi.model.*;
import java.util.Collections;

/** Real SDK simulation using a public fixture route. Never enters NavigationService or the BLE channel. */
public final class SdkReplayActivity extends Activity {
    private enum Phase { IDLE, INITIALIZING, PLANNING, STARTING, RUNNING }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AmapKeySession keys = new AmapKeySession();
    private AMapNavi engine;
    private SimpleNaviListener listener;
    private TextView status, counters, guidance;
    private Button start, stop;
    private Phase phase = Phase.IDLE;
    private int generation, locations, instructions;
    private long deadlineMs, lastInfoMs = -1;
    private boolean foreground;
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!foreground || phase == Phase.IDLE) return;
            long now = SystemClock.elapsedRealtime();
            if (now >= deadlineMs) { finishTest("模拟测试超时：请查看算路结果及两类回调计数。",false); return; }
            if (phase == Phase.RUNNING && lastInfoMs >= 0 && now-lastInfoMs > 15_000)
                status.setText("模拟导航指令已超过 15 秒未更新，继续观察到测试结束。此状态不依赖手机 GPS。");
            main.postDelayed(this,1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(28,28,28,28);
        ScrollView scroll = new ScrollView(this); scroll.addView(page); setContentView(scroll);
        label(page,"高德 SDK 模拟导航测试",23);
        label(page,"仅测试：使用北京公开测试路线，不使用当前位置。\n验证真实高德算路、模拟位置和转弯指令回调。测试期间保持本页打开，最长 90 秒。",16);
        label(page,"模拟结果不会作为真实导航，也不会发送给圆屏；本测试不验证手机 GPS 精度。使用本机已保存的 Android Key，消耗正常算路调用。",14);
        status = label(page,"准备就绪。请先结束真实导航，再点击开始测试。",17);
        counters = label(page,"模拟位置回调 0 次 · 导航指令 0 次",16);
        guidance = label(page,"等待真实 SDK 返回模拟指令",17);
        start = new Button(this); start.setText("开始 SDK 模拟测试"); start.setOnClickListener(v -> startTest()); page.addView(start);
        stop = new Button(this); stop.setText("停止测试"); stop.setEnabled(false); stop.setOnClickListener(v -> finishTest("测试已停止。",false)); page.addView(stop);
        Button back = new Button(this); back.setText("返回地图"); back.setOnClickListener(v -> finish()); page.addView(back);
    }
    private TextView label(LinearLayout page,String value,int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setPadding(0,10,0,10); page.addView(text); return text;
    }
    void startTest() {
        if (!foreground || phase != Phase.IDLE) return;
        if (NavigationService.navigating) { status.setText("请先结束真实导航，再运行模拟测试。"); return; }
        SavedMapConfig saved = new SavedMapConfig(this);
        try {
            if (!saved.consent() || !keys.replace(saved.load())) { status.setText("请先返回地图设置，保存 Android Key 并同意高德服务政策。"); return; }
        } catch (Exception e) { status.setText("无法读取本机地图配置，请返回地图设置检查。"); return; }
        locations = 0; instructions = 0; lastInfoMs = -1; renderCounts();
        phase = Phase.INITIALIZING; final int attempt = ++generation;
        deadlineMs = SystemClock.elapsedRealtime()+90_000;
        start.setEnabled(false); stop.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("正在初始化真实高德导航 SDK…"); guidance.setText("固定测试路线 · 非当前位置");
        Diagnostics.record("route_action","action","sdk_replay_start","reason","public_fixture");
        try {
            NaviSetting.updatePrivacyShow(this,true,true); NaviSetting.updatePrivacyAgree(this,true);
            AMapNavi.setApiKey(getApplicationContext(),keys.sdkValue());
            engine = AMapNavi.getInstance(getApplicationContext());
            final AMapNavi selected = engine;
            selected.setUseInnerVoice(false,false);
            listener = new SimpleNaviListener() {
                private void deliver(Runnable action) { main.post(() -> {
                    if (!foreground || generation != attempt || engine != selected) return;
                    try { action.run(); }
                    catch (RuntimeException | LinkageError e) {
                        Diagnostics.record("navigation_state","state","sdk_replay_exception","error_class",e.getClass().getSimpleName());
                        finishTest("模拟测试回调处理失败，请开启诊断记录错误。",false);
                    }
                }); }
                @Override public void onInitNaviSuccess() { deliver(() -> {
                    if (phase != Phase.INITIALIZING) return;
                    phase = Phase.PLANNING; status.setText("正在计算北京公开测试路线…");
                    AMapCarInfo car = new AMapCarInfo(); car.setCarType("0"); selected.setCarInfo(car);
                    boolean accepted = selected.calculateDriveRoute(
                        Collections.singletonList(new NaviLatLng(39.989614,116.481763)),
                        Collections.singletonList(new NaviLatLng(39.983456,116.315495)),
                        Collections.emptyList(),PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT);
                    if (!accepted) finishTest("SDK 未接受模拟测试的算路请求。",false);
                }); }
                @Override public void onInitNaviFailure() { deliver(() -> finishTest("高德导航 SDK 初始化失败。",false)); }
                @Override public void onCalculateRouteFailure(AMapCalcRouteResult result) { deliver(() -> {
                    int code = result == null ? -1 : result.getErrorCode();
                    Diagnostics.record("route_result","state","sdk_replay_failed","error",code);
                    finishTest("模拟测试："+JourneyState.routeError(code),false);
                }); }
                @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result) { deliver(() -> {
                    if (phase != Phase.PLANNING) return;
                    int[] routes = result == null ? null : result.getRouteid();
                    if (routes == null || routes.length == 0 || !selected.selectRouteId(routes[0])) { finishTest("模拟测试未获得有效路线。",false); return; }
                    selected.setEmulatorNaviSpeed(60);
                    phase = Phase.STARTING;
                    status.setText("算路成功，正在启动 SDK 模拟导航…");
                    if (!selected.startNavi(NaviType.EMULATOR)) finishTest("SDK 拒绝启动模拟导航。",false);
                }); }
                @Override public void onStartNavi(int type) { deliver(() -> {
                    if (type != NaviType.EMULATOR) { finishTest("SDK 返回了非模拟模式，测试已停止。",false); return; }
                    phase = Phase.RUNNING; status.setText("SDK 模拟导航已启动，等待位置和指令回调…");
                }); }
                @Override public void onLocationChange(AMapNaviLocation location) { deliver(() -> {
                    if (phase != Phase.RUNNING || location == null) return;
                    locations++; renderCounts(); maybeComplete();
                }); }
                @Override public void onNaviInfoUpdate(NaviInfo info) { deliver(() -> {
                    if (phase != Phase.RUNNING || info == null) return;
                    if (info.getNaviType() != NaviType.EMULATOR) { finishTest("指令类型不是模拟导航，测试已停止。",false); return; }
                    instructions++; lastInfoMs = SystemClock.elapsedRealtime(); renderCounts();
                    guidance.setText("模拟指令："+JourneyState.maneuver(info.getIconType())+"\n距离下一段 "+info.getCurStepRetainDistance()+" 米 · 剩余 "+info.getPathRetainDistance()+" 米");
                    Diagnostics.record("navigation_state","state","sdk_replay_instruction","count",instructions,"icon",info.getIconType());
                    maybeComplete();
                }); }
                @Override public void onArriveDestination() { deliver(() -> finishTest("SDK 模拟路线已结束。",locations>0 && instructions>0)); }
            };
            selected.addAMapNaviListener(listener); main.post(watch);
        } catch (Exception | LinkageError e) {
            Diagnostics.record("navigation_state","state","sdk_replay_exception","error_class",e.getClass().getSimpleName());
            finishTest("模拟测试初始化或调用失败，请开启诊断记录错误。",false);
        }
    }
    private void renderCounts() { counters.setText("模拟位置回调 "+locations+" 次 · 导航指令 "+instructions+" 次"); }
    private void maybeComplete() {
        if (locations >= 5 && instructions >= 5)
            finishTest("SDK 模拟测试通过：算路、模拟位置和导航指令均有返回。手机 GPS 与真实定位仍需单独测试。",true);
    }
    private void finishTest(String message,boolean passed) {
        if (phase != Phase.IDLE) Diagnostics.record("navigation_state","state","sdk_replay_end","accepted",passed,"count",instructions);
        releaseEngine(); status.setText(message);
    }
    private void releaseEngine() {
        ++generation; phase = Phase.IDLE; main.removeCallbacksAndMessages(null);
        AMapNavi old = engine; SimpleNaviListener oldListener = listener; engine = null; listener = null;
        if (old != null) {
            try { if (oldListener != null) old.removeAMapNaviListener(oldListener); old.stopSpeak(); old.stopNavi(); }
            catch (RuntimeException | LinkageError ignored) { }
            try { AMapNavi.destroy(); } catch (RuntimeException | LinkageError ignored) { }
        }
        keys.clear(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        start.setEnabled(true); stop.setEnabled(false);
    }
    @Override protected void onResume() { super.onResume(); foreground = true; }
    @Override protected void onPause() {
        foreground = false;
        if (phase != Phase.IDLE) finishTest("已离开测试页面，模拟测试已停止。返回后可重新开始。",false);
        super.onPause();
    }
    @Override protected void onDestroy() { releaseEngine(); super.onDestroy(); }
}
