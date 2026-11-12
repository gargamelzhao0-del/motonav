package com.motonav.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.EditText;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.NaviSetting;
import com.amap.api.navi.SimpleNaviListener;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.enums.PathPlanningStrategy;
import com.amap.api.navi.model.AMapCarInfo;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviPath;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Service-owned real navigation; the map and activity are detachable views. */
final class JourneyController {
    interface Host {
        boolean active(); boolean navigationAllowed(); String key(); Location fix(); long fixNanos();
        boolean beginNavigation(Runnable start); void finished(); void refreshLocation();
    }
    interface Ui {
        void report(String message); void overview(); void icon(android.graphics.Bitmap bitmap);
    }
    private final Context context;
    private Activity activity;
    private AMap map;
    private Ui ui;
    private final Host host;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final JourneyState state=new JourneyState();
    private AMapNavi navi;
    private SimpleNaviListener listener;
    private AlertDialog dialog;
    private Polyline line;
    private Marker destinationMarker;
    private PoiItem destination;
    private Location plannedStart;
    private int searchEpoch;
    private boolean destroyed, gpsWeak;
    private long startDeadlineMs;
    private Runnable pendingLocation;
    private final List<LatLng> routePoints = new ArrayList<>();
    private String message = "选择目的地，然后规划摩托路线。";
    private android.graphics.Bitmap turnBitmap;
    private NavigationFrame frame = NavigationFrame.status(NavigationFrame.STOPPED);
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!active() || state.phase() != JourneyState.Phase.NAVIGATING) return;
            guidanceReady(false);
            if (state.phase() == JourneyState.Phase.NAVIGATING) handler.postDelayed(this, 2000);
        }
    };
    JourneyController(Context context, Host host) { this.context = context.getApplicationContext(); this.host = host; }
    void attach(Activity activity, AMap map, Ui ui) {
        detach();
        this.activity = activity;
        this.map = map;
        this.ui = ui;
        if (destination != null) destinationMarker = map.addMarker(new MarkerOptions().position(destinationPoint()).title(destination.getTitle()));
        drawRoute(false);
        cameraFollow(state.phase() == JourneyState.Phase.NAVIGATING);
        ui.report(message);
        ui.icon(turnBitmap);
    }
    void detach() {
        if (state.phase() == JourneyState.Phase.LOCATING || state.phase() == JourneyState.Phase.PLANNING
                || state.phase() == JourneyState.Phase.READY) stop("已取消尚未开始的导航，目的地已保留。");
        ++searchEpoch;
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (line != null) { line.remove(); line = null; }
        if (destinationMarker != null) { destinationMarker.remove(); destinationMarker = null; }
        ui = null; map = null; activity = null;
    }
    NavigationFrame frame() { return frame.at(SystemClock.elapsedRealtime()); }
    boolean canRequestStart() { return destination != null && state.phase() == JourneyState.Phase.IDLE; }
    String actionLabel() {
        return switch (state.phase()) {
            case IDLE -> "开始导航";
            case LOCATING, PLANNING, READY -> "取消启动";
            case STARTING, NAVIGATING -> "结束导航";
        };
    }
    void refreshGuidance() {
        if (state.phase() == JourneyState.Phase.NAVIGATING) guidanceReady(false);
    }
    void end() { stop("导航已结束。"); }
    private void report(String value) { message = value; if (ui != null) ui.report(value); }
    private void icon(android.graphics.Bitmap value) { turnBitmap = value; if (ui != null) ui.icon(value); }
    private boolean active(){return !destroyed&&host.active();}
    private boolean usableFix() { return trustedFixTime() > 0; }

    private long trustedFixTime() {
        Location fix = host.fix();
        if (fix == null || !JourneyState.coordinate(fix.getLatitude(), fix.getLongitude())) return 0;
        long time = host.fixNanos();
        return JourneyState.usableFix(
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                fix.isFromMockProvider(), fix.hasAccuracy() ? fix.getAccuracy() : Float.NaN,
                SystemClock.elapsedRealtimeNanos(), time) ? time : 0;
    }

    private boolean guidanceReady(boolean newInstruction) {
        if (!host.navigationAllowed()) {
            stop("精确位置权限或系统定位已关闭，导航已结束。");
            return false;
        }
        JourneyState.Guidance guidance = state.guidance(SystemClock.elapsedRealtime(),
                gpsWeak ? 0 : trustedFixTime(), newInstruction);
        if (newInstruction || frame.mode() == NavigationFrame.LIVE) Diagnostics.record("navigation_state", "state", guidance.name(), "gps_weak", gpsWeak, "fix_age_ms", DiagnosticRecord.age(SystemClock.elapsedRealtimeNanos(), trustedFixTime()));
        if (guidance == JourneyState.Guidance.LIVE) return true;
        frame = NavigationFrame.status(NavigationFrame.WAITING);
        icon(null);
        report("定位或导航指令暂时中断。已保留路线，正在等待恢复。");
        return false;
    }
    private void show(AlertDialog next){if(dialog!=null)dialog.dismiss();dialog=next;next.setOnDismissListener(d->{if(dialog==next)dialog=null;});next.show();}
    void searchDialog(){
        if(!active() || activity == null)return;
        if(state.phase()==JourneyState.Phase.NAVIGATING||state.phase()==JourneyState.Phase.STARTING){report("请先结束导航并停车，再更换目的地。");return;}
        if (state.phase() != JourneyState.Phase.IDLE) stop("已取消当前启动请求，请选择新的目的地。");
        EditText input=new EditText(activity);input.setHint("城市 + 地点名称（发送给高德搜索）");input.setSingleLine(true);input.setSaveEnabled(false);
        show(new AlertDialog.Builder(activity).setTitle("搜索真实目的地").setView(input)
            .setNegativeButton("取消",null).setPositiveButton("搜索",(d,w)->search(input.getText().toString().trim())).create());
    }
    private void search(String query){
        if(!active() || activity == null)return;
        if(query.length()<2||query.length()>80){report("请输入2–80字的城市和地点名称。");return;}
        Diagnostics.record("route_action", "action", "search", "count", query.length());
        final int token=++searchEpoch;
        final boolean[] settled={false};
        try{
            ServiceSettings.updatePrivacyShow(context,true,true);ServiceSettings.updatePrivacyAgree(context,true);
            ServiceSettings.getInstance().setApiKey(host.key());ServiceSettings.getInstance().setProtocol(ServiceSettings.HTTPS);
            ServiceSettings.getInstance().setConnectionTimeOut(10000);ServiceSettings.getInstance().setSoTimeOut(10000);
            PoiSearch.Query request=new PoiSearch.Query(query,"","");request.setPageSize(8);request.setPageNum(0);
            PoiSearch search=new PoiSearch(context,request);
            search.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener(){
                public void onPoiItemSearched(PoiItem item,int code){}
                public void onPoiSearched(PoiResult result,int code){
                    if(!active()||activity==null||token!=searchEpoch)return;
                    settled[0]=true;
                    Diagnostics.record("search_result", "error", code, "count", result == null || result.getPois() == null ? 0 : result.getPois().size());
                    if(code!=1000||result==null){report("搜索失败（"+code+"），请检查高德Key、服务权限及网络。未生成目的地。");return;}
                    List<PoiItem> valid=new ArrayList<>();
                    if(result.getPois()!=null)for(PoiItem item:result.getPois())if(item!=null&&item.getLatLonPoint()!=null&&JourneyState.coordinate(item.getLatLonPoint().getLatitude(),item.getLatLonPoint().getLongitude()))valid.add(item);
                    if(valid.isEmpty()){report("没有找到匹配地点，请补充城市或更具体的名称。");return;}
                    String[] labels=new String[valid.size()];for(int i=0;i<labels.length;i++)labels[i]=valid.get(i).getTitle()+"\n"+valid.get(i).getSnippet();
                    show(new AlertDialog.Builder(activity).setTitle("选择目的地 · 请核对地址").setItems(labels,(d,w)->{
                        if(!active()||activity==null||token!=searchEpoch)return;
                        stopEngine();clearRoute();destination=valid.get(w);++searchEpoch;
                        LatLng at=destinationPoint();if(destinationMarker!=null)destinationMarker.remove();
                        destinationMarker=map.addMarker(new MarkerOptions().position(at).title(destination.getTitle()));
                        cameraFollow(false);map.animateCamera(CameraUpdateFactory.newLatLngZoom(at,15));
                        report("目的地："+destination.getTitle()+"\n点击「开始导航」，将自动获取当前位置并规划摩托车路线。");
                    }).setNegativeButton("取消",null).create());
                }
            });
            search.searchPOIAsyn();report("正在搜索真实地点…");
            handler.postDelayed(()->{if(active()&&token==searchEpoch&&!settled[0]){++searchEpoch;report("搜索超时，请重试；迟到结果将被忽略。");}},15000);
        }catch(Exception|LinkageError e){++searchEpoch;report("搜索初始化失败，请检查服务配置。");}
    }
    private LatLng destinationPoint(){return new LatLng(destination.getLatLonPoint().getLatitude(),destination.getLatLonPoint().getLongitude());}
    private void awaitLocation(long token, boolean routeReady) {
        if (!active() || activity == null || state.epoch() != token) return;
        if (routeReady ? state.phase() != JourneyState.Phase.READY : !state.locating(token)) return;
        if (SystemClock.elapsedRealtime() >= startDeadlineMs) {
            stop("暂未获得可用的当前位置，启动已停止。目的地已保留，请检查定位信号后再点开始。");
            return;
        }
        if (!host.navigationAllowed()) {
            stop("请开启精确位置权限和系统定位，再点击开始导航。目的地已保留。");
            return;
        }
        if (usableFix()) {
            pendingLocation = null;
            if (routeReady) startReady(); else plan(token);
            return;
        }
        report("正在获取当前位置…\n定位恢复后会自动" + (routeReady ? "启动导航" : "算路并启动导航") + "，可点击「取消启动」。");
        pendingLocation = () -> awaitLocation(token, routeReady);
        handler.postDelayed(pendingLocation, 1000);
    }

    private void plan(long token){
        Location origin = host.fix();
        if(!active() || origin == null || !usableFix() || destination == null) {
            if (active() && destination != null && state.locating(token)) {
                pendingLocation = () -> awaitLocation(token, false);
                handler.postDelayed(pendingLocation, 1000);
            }
            return;
        }
        if (!state.plan(token, true)) return;
        report("正在规划摩托车路线…\n路线就绪后自动开始导航。");
        Diagnostics.record("route_action", "action", "plan_submit", "fix_age_ms", DiagnosticRecord.age(SystemClock.elapsedRealtimeNanos(), origin.getElapsedRealtimeNanos()));
        ++searchEpoch;
        plannedStart=new Location(origin);final LatLng target=destinationPoint();
        try{
            NaviSetting.updatePrivacyShow(context,true,true);NaviSetting.updatePrivacyAgree(context,true);
            AMapNavi.setApiKey(context,host.key());
            navi=AMapNavi.getInstance(context);
            final AMapNavi engine=navi;
            engine.stopSpeak();engine.setUseInnerVoice(false,false);
            final boolean[] submittedOnce={false};
            listener=new SimpleNaviListener(){
                @Override public void onInitNaviSuccess(){
                    if(!validPlan(token,engine)||submittedOnce[0])return;submittedOnce[0]=true;
                    AMapCarInfo car=new AMapCarInfo();car.setCarType("11");engine.setCarInfo(car);
                    boolean submitted=engine.calculateDriveRoute(Collections.singletonList(new NaviLatLng(plannedStart.getLatitude(),plannedStart.getLongitude())),
                        Collections.singletonList(new NaviLatLng(target.latitude,target.longitude)),Collections.emptyList(),PathPlanningStrategy.MOTOR_MULTIPLE_DEFAULT);
                    Diagnostics.record("route_action", "action", "sdk_request", "accepted", submitted);
                    if(!submitted)stop("摩托算路请求未提交，请检查配置后重试。");
                }
                @Override public void onInitNaviFailure(){if(validPlan(token,engine))stop("导航SDK初始化失败，未开始导航。");}
                @Override public void onCalculateRouteFailure(AMapCalcRouteResult result){if(validPlan(token,engine)){Diagnostics.record("route_result", "state", "failed", "error", result==null?-1:result.getErrorCode());stop(JourneyState.routeError(result==null?-1:result.getErrorCode()));}}
                @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result){
                    if(!validPlan(token,engine))return;
                    Diagnostics.record("route_result", "state", "sdk_success");
                    int[] ids=result==null?null:result.getRouteid();Map<Integer,AMapNaviPath> paths=engine.getNaviPaths();
                    if(ids==null||ids.length==0||paths==null||paths.get(ids[0])==null){stop("高德未返回有效摩托路线。");return;}
                    AMapNaviPath path=paths.get(ids[0]);List<NaviLatLng> coords=path.getCoordList();
                    if(coords==null||coords.size()<2||path.getAllLength()<=0){stop("路线几何或距离无效，不能开始导航。");return;}
                    try{
                        if (!engine.selectRouteId(ids[0])) { stop("高德路线选择失败。"); return; }
                        routePoints.clear();
                        for (NaviLatLng point : coords) {
                            if (point == null || !JourneyState.coordinate(point.getLatitude(), point.getLongitude())) { stop("路线坐标无效。"); return; }
                            routePoints.add(new LatLng(point.getLatitude(), point.getLongitude()));
                        }
                        drawRoute(true);
                        if(state.ready(token,SystemClock.elapsedRealtime())) {
                            report("摩托车路线已就绪，正在启动导航…");
                            if (!usableFix()) host.refreshLocation();
                            awaitLocation(token, true);
                        }
                    }catch(RuntimeException e){stop("路线显示失败，未开始导航。");}
                }
                @Override public void onStartNavi(int type){
                    if(!active()||engine!=navi||type!=NaviType.GPS)return;
                    if (state.epoch() == token && state.phase() == JourneyState.Phase.STARTING
                            && SystemClock.elapsedRealtime() >= startDeadlineMs) { stop("启动导航超时，请再次点击开始。目的地已保留。"); return; }
                    if (!state.started(token)) return;
                    frame = NavigationFrame.status(NavigationFrame.WAITING);cameraFollow(true);state.startGuidance(SystemClock.elapsedRealtime());handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,2000);report("GPS导航已启动，可锁屏。等待实时指令。");
                }
                @Override public void onNaviInfoUpdate(NaviInfo info){
                    if (!active() || engine != navi || !state.active(token)
                            || state.phase() != JourneyState.Phase.NAVIGATING
                            || info == null || info.getNaviType() != NaviType.GPS) return;
                    if (!guidanceReady(true)) return;
                    Location fix = host.fix();
                    int speed = fix != null && fix.hasSpeed() && Float.isFinite(fix.getSpeed())
                            ? Math.round(fix.getSpeed() * 3.6f) : -1;
                    frame = new NavigationFrame(NavigationFrame.LIVE, info.getIconType(),
                            info.getCurStepRetainDistance(), info.getPathRetainDistance(),
                            info.getPathRetainTime(), speed, host.fixNanos() / 1_000_000L,
                            SystemClock.elapsedRealtime()).at(SystemClock.elapsedRealtime());
                    if (frame.mode() != NavigationFrame.LIVE) { icon(null); report("等待有效的导航指令。"); return; }
                    icon(info.getIconBitmap());
                    report(JourneyState.maneuver(info.getIconType())+" · 剩余"+Math.max(0,info.getPathRetainDistance())+"米 / "+Math.max(0,info.getPathRetainTime()/60)+"分钟\n下一路段："+safeName(info.getNextRoadName())+" · 距当前段末"+Math.max(0,info.getCurStepRetainDistance())+"米");
                }
                @Override public void onStopNavi(){if(active()&&engine==navi&&state.active(token))stop("SDK已结束导航。");}
                @Override public void onArriveDestination(){if(active()&&engine==navi&&state.active(token))stop("已到达目的地。", NavigationFrame.ARRIVED);}
                @Override public void onReCalculateRouteForYaw(){if(active()&&engine==navi&&state.active(token))stop("检测到偏航，导航已停止；请停车后手动重新规划。");}
                @Override public void onReCalculateRouteForTrafficJam(){if(active()&&engine==navi&&state.active(token))stop("路线需要更新，导航已停止；请停车后重新规划。");}
                @Override public void onGpsSignalWeak(boolean weak) {
                    if (!active() || engine != navi || !state.active(token)) return;
                    gpsWeak = weak;
                    Diagnostics.record("navigation_state", "state", "gps_signal", "gps_weak", weak);
                    if (weak && state.phase() == JourneyState.Phase.NAVIGATING) guidanceReady(false);
                }
            };
            report("正在规划摩托车路线…");
            engine.addAMapNaviListener(listener);
            handler.postDelayed(()->{if(validPlan(token,engine))stop("算路超时，目的地已保留。请检查网络或服务授权后再次点击开始。");},
                    Math.max(1, Math.min(30000, startDeadlineMs - SystemClock.elapsedRealtime())));
        }catch(Exception|LinkageError e){stop("导航SDK初始化失败，请核对新包名/签名、Key和SDK配置。");}
    }
    private boolean validPlan(long token,AMapNavi engine){return active()&&engine==navi&&state.planning(token);}
    private static String safeName(String name){return name==null||name.trim().isEmpty()?"等待道路信息":name;}
    void startOrStop(){
        Diagnostics.record("route_action", "action", "start_or_stop", "phase", state.phase().name(), "eligible", usableFix());
        if(!active() || activity == null)return;
        if(state.phase()!=JourneyState.Phase.IDLE){stop("已取消启动/结束导航。目的地已保留。");return;}
        if (destination == null) { report("请输入并选择目的地，再点击开始导航。"); searchDialog(); return; }
        ++searchEpoch;if(dialog!=null){dialog.dismiss();dialog=null;}
        stopEngine(); clearRoute();
        long token = state.locate(true);
        if (token < 0) return;
        startDeadlineMs = SystemClock.elapsedRealtime() + 60000;
        Diagnostics.record("route_action", "action", "start_requested", "phase", state.phase().name(), "destination", true);
        if (!usableFix()) host.refreshLocation();
        awaitLocation(token, false);
    }

    private void startReady() {
        if (!active() || activity == null || navi == null || plannedStart == null || state.phase() != JourneyState.Phase.READY) return;
        Location current = host.fix();
        if (current == null || !usableFix()) {
            host.refreshLocation();
            awaitLocation(state.epoch(), true);
            return;
        }
        double moved = plannedStart.distanceTo(current);
        if (!state.start(true, SystemClock.elapsedRealtime(), moved)) {
            stop("起点位置已变化，请再次点击开始导航。目的地已保留。");
            return;
        }
        report("正在启动摩托车导航…");
        final long token=state.epoch();
        boolean accepted = host.beginNavigation(() -> {
            if (!active() || state.epoch() != token || state.phase() != JourneyState.Phase.STARTING) return;
            if (SystemClock.elapsedRealtime() >= startDeadlineMs) { stop("启动导航超时，请再次点击开始。"); return; }
            if (!host.navigationAllowed() || !usableFix()) { stop("启动导航前定位已失效，请等待新定位。"); return; }
            try {
                if (!navi.startNavi(NaviType.GPS)) { stop("GPS导航启动请求被拒绝。"); return; }
                handler.postDelayed(() -> {
                    if (active() && state.epoch() == token && state.phase() == JourneyState.Phase.STARTING)
                        stop("GPS导航未成功启动，请重新规划后重试。");
                }, Math.max(1, Math.min(10000, startDeadlineMs - SystemClock.elapsedRealtime())));
            } catch (RuntimeException | LinkageError e) { stop("GPS导航启动失败。"); }
        });
        if (!accepted) stop("后台导航启动失败，请检查精确定位权限后重试。");
    }
    private void cameraFollow(boolean follow) {
        if (map != null) map.setMyLocationStyle(new MyLocationStyle().myLocationType(
                follow ? MyLocationStyle.LOCATION_TYPE_FOLLOW : MyLocationStyle.LOCATION_TYPE_FOLLOW_NO_CENTER).interval(2000));
    }
    private void drawRoute(boolean overview) {
        if (map == null || routePoints.size() < 2) return;
        if (line != null) line.remove();
        line = map.addPolyline(new PolylineOptions().addAll(routePoints).width(15).color(0xffb4ff65));
        if (overview && ui != null) {
            ui.overview(); cameraFollow(false);
            LatLngBounds.Builder bounds = new LatLngBounds.Builder();
            for (LatLng point : routePoints) bounds.include(point);
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80));
        }
    }
    private void clearRoute() {
        if (line != null) { line.remove(); line = null; }
        routePoints.clear(); plannedStart = null;
    }
    private void stopEngine() {
        state.cancel(); gpsWeak = false; icon(null); handler.removeCallbacks(watchdog);
        if (pendingLocation != null) handler.removeCallbacks(pendingLocation);
        pendingLocation = null;
        frame = NavigationFrame.status(NavigationFrame.STOPPED);
        AMapNavi old = navi; SimpleNaviListener oldListener = listener; navi = null; listener = null;
        if (old != null) {
            try { old.removeAMapNaviListener(oldListener); } catch (RuntimeException ignored) {}
            try { old.stopNavi(); old.stopSpeak(); old.stopGPS(); } catch (RuntimeException ignored) {}
            try { AMapNavi.destroy(); } catch (RuntimeException ignored) {}
        }
    }
    private void stop(String message) { stop(message, NavigationFrame.STOPPED); }
    private void stop(String message, int terminalMode) {
        Diagnostics.record("navigation_state", "state", "stopped", "phase", state.phase().name(), "count", terminalMode);
        stopEngine(); clearRoute(); frame = NavigationFrame.status(terminalMode); report(message);
        host.finished();
    }
    void close() {
        detach(); destroyed = true; stopEngine(); clearRoute(); destination = null;
        handler.removeCallbacksAndMessages(null);
    }
}
