package com.motonav.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import com.amap.api.navi.enums.ReCalculateRouteType;
import com.amap.api.navi.model.AMapCarInfo;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviPath;
import com.amap.api.navi.model.AMapNaviLocation;
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
        boolean active(); boolean navigationAllowed(); boolean voiceEnabled(); String key(); Location fix(); long fixNanos();
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
    private DestinationSearchDialog dialog;
    private String lastSearch="", nextRoad="";
    private Polyline line;
    private Marker destinationMarker;
    private PoiItem destination;
    private Location plannedStart;
    private int searchEpoch;
    private long searchDialogGeneration;
    private boolean destroyed, gpsWeak;
    private long startDeadlineMs;
    private int startReplans;
    private int retryFailures, retryRequestId = -1;
    private long nextRouteRetryMs = -1;
    private boolean voicePlaying;
    private long lastInstructionReceivedMs = -1, lastSdkFixNanos;
    private GuidanceHealth.Reason lastWaitingReason;
    private final NaviFixTracker sdkFixes = new NaviFixTracker();
    private final java.util.LinkedHashSet<Integer> settledRouteRequests = new java.util.LinkedHashSet<>();
    private Runnable pendingLocation;
    private final List<LatLng> routePoints = new ArrayList<>();
    private final DeviceRoute deviceRoute = new DeviceRoute();
    private String message = "普通驾车导航 · 输入目的地后点击开始。";
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
        ++searchEpoch;
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (line != null) { line.remove(); line = null; }
        if (destinationMarker != null) { destinationMarker.remove(); destinationMarker = null; }
        ui = null; map = null; activity = null;
    }
    NavigationFrame frame() { return frame.at(SystemClock.elapsedRealtime()); }
    DeviceRoute.Frame deviceMap() {
        if(frame().mode()!=NavigationFrame.LIVE)return null;
        Location fix=guidanceFix();
        return fix==null?null:deviceRoute.frame(fix.getLatitude(),fix.getLongitude(),fix.hasBearing()?fix.getBearing():Float.NaN);
    }
    boolean canRequestStart() { return destination != null && state.phase() == JourneyState.Phase.IDLE; }
    NavigationUiModel presentation() {
        return NavigationUiModel.create(state.phase(),frame(),destination==null?"":destination.getTitle(),nextRoad,message);
    }
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
        Location fix = guidanceFix();
        return fix == null ? 0 : fix.getElapsedRealtimeNanos();
    }

    /** During GPS navigation the navigation engine is also a first-class location source. */
    Location guidanceFix() {
        if (!active() || !host.navigationAllowed() || gpsWeak) return null;
        NaviFixTracker.Fix sdk = state.phase() == JourneyState.Phase.NAVIGATING
                ? sdkFixes.current(SystemClock.elapsedRealtimeNanos()) : null;
        if (sdk != null) {
            Location fix = new Location("amap_navigation_gps");
            fix.setLatitude(sdk.latitude()); fix.setLongitude(sdk.longitude());
            fix.setAccuracy(sdk.accuracy()); fix.setTime(sdk.wallMillis());
            fix.setElapsedRealtimeNanos(sdk.timeNanos());
            if (sdk.speedKmh() >= 0) fix.setSpeed(sdk.speedKmh()/3.6f);
            if (Float.isFinite(sdk.bearing())) fix.setBearing(sdk.bearing());
            return fix;
        }
        Location fix = host.fix();
        if (fix == null || !JourneyState.coordinate(fix.getLatitude(), fix.getLongitude())) return null;
        long time = host.fixNanos();
        if (!JourneyState.usableFix(
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                fix.isFromMockProvider(), fix.hasAccuracy() ? fix.getAccuracy() : Float.NaN,
                SystemClock.elapsedRealtimeNanos(), time)) return null;
        Location copy = new Location(fix); copy.setElapsedRealtimeNanos(time); return copy;
    }

    private boolean guidanceReady(boolean newInstruction) {
        if (!host.navigationAllowed()) {
            stop("精确位置权限或系统定位已关闭，导航已结束。");
            return false;
        }
        if (state.rerouting()) {
            syncVoice(false);
            if (state.rerouteExpired(SystemClock.elapsedRealtime()) && nextRouteRetryMs < 0)
                scheduleRouteRetry("timeout");
            if (nextRouteRetryMs >= 0 && SystemClock.elapsedRealtime() >= nextRouteRetryMs && usableFix() && !gpsWeak)
                retryRoute();
            return false;
        }
        JourneyState.Guidance guidance = state.guidance(SystemClock.elapsedRealtime(),
                gpsWeak ? 0 : trustedFixTime(), newInstruction);
        if (newInstruction || frame.mode() == NavigationFrame.LIVE) Diagnostics.record("navigation_state", "state", guidance.name(), "gps_weak", gpsWeak, "fix_age_ms", DiagnosticRecord.age(SystemClock.elapsedRealtimeNanos(), trustedFixTime()));
        if (guidance == JourneyState.Guidance.LIVE) { syncVoice(newInstruction || frame.mode() == NavigationFrame.LIVE); return true; }
        syncVoice(false);
        frame = NavigationFrame.status(NavigationFrame.WAITING);
        icon(null);
        GuidanceHealth.Reason reason = GuidanceHealth.reason(SystemClock.elapsedRealtimeNanos(),gpsWeak,
                trustedFixTime(),lastSdkFixNanos,lastInstructionReceivedMs);
        if (reason != lastWaitingReason) Diagnostics.record("navigation_state", "state", "waiting", "reason", reason.name(),
                "fix_age_ms", DiagnosticRecord.age(SystemClock.elapsedRealtimeNanos(),trustedFixTime()),
                "navigation_age_ms", DiagnosticRecord.age(SystemClock.elapsedRealtimeNanos(),lastSdkFixNanos));
        lastWaitingReason = reason;
        report(GuidanceHealth.message(reason));
        return false;
    }
    void searchDialog(){
        if(!active() || activity == null)return;
        if(state.phase()==JourneyState.Phase.NAVIGATING||state.phase()==JourneyState.Phase.STARTING){report("请先结束导航并停车，再更换目的地。");return;}
        if (state.phase() != JourneyState.Phase.IDLE) stop("已取消当前启动请求，请选择新的目的地。");
        final long generation=++searchDialogGeneration;
        ++searchEpoch;
        if(dialog!=null)dialog.dismiss();
        dialog=new DestinationSearchDialog(activity,lastSearch,this::search,()->{
            if(generation==searchDialogGeneration){++searchEpoch;dialog=null;}
        });
        dialog.show();
    }
    private void searchFailure(String text) { if(dialog!=null)dialog.failure(text); }
    private void search(String query){
        if(!active() || activity == null || dialog==null)return;
        if(query.length()<2||query.length()>80){searchFailure("请输入 2–80 字的城市和地点名称。");return;}
        lastSearch=query;dialog.searching();
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
                    handler.post(()->{
                    if(!active()||activity==null||dialog==null||token!=searchEpoch||settled[0])return;
                    settled[0]=true;
                    Diagnostics.record("search_result", "error", code, "count", result == null || result.getPois() == null ? 0 : result.getPois().size());
                    if(code!=1000||result==null){searchFailure("搜索暂未成功（"+code+"）。请检查网络或地图服务配置，再试一次。");return;}
                    List<PoiItem> valid=new ArrayList<>();
                    if(result.getPois()!=null)for(PoiItem item:result.getPois())if(item!=null&&item.getLatLonPoint()!=null&&JourneyState.coordinate(item.getLatLonPoint().getLatitude(),item.getLatLonPoint().getLongitude()))valid.add(item);
                    if(valid.isEmpty()){searchFailure("没有找到匹配地点。试试加上城市、区县或更具体的名称。");return;}
                    String[] labels=new String[valid.size()];for(int i=0;i<labels.length;i++)labels[i]=valid.get(i).getTitle()+"\n"+(valid.get(i).getSnippet()==null?"请在地图上核对位置":valid.get(i).getSnippet());
                    dialog.results(labels,w->{
                        if(!active()||activity==null||map==null||token!=searchEpoch||w<0||w>=valid.size())return;
                        stopEngine();clearRoute();destination=valid.get(w);++searchEpoch;
                        LatLng at=destinationPoint();if(destinationMarker!=null)destinationMarker.remove();
                        destinationMarker=map.addMarker(new MarkerOptions().position(at).title(destination.getTitle()));
                        cameraFollow(false);map.animateCamera(CameraUpdateFactory.newLatLngZoom(at,15));
                        report("目的地："+destination.getTitle()+"\n点击「开始导航」，将自动获取当前位置并规划普通驾车路线。");
                        if(dialog!=null)dialog.dismiss();
                    });
                    });
                }
            });
            search.searchPOIAsyn();
            handler.postDelayed(()->{if(active()&&token==searchEpoch&&!settled[0]){++searchEpoch;searchFailure("搜索用时较长。输入已保留，请检查网络后重新搜索。");}},15000);
        }catch(Exception|LinkageError e){++searchEpoch;searchFailure("暂时无法搜索，请检查地图服务配置后重试。");}
    }
    private LatLng destinationPoint(){return new LatLng(destination.getLatLonPoint().getLatitude(),destination.getLatLonPoint().getLongitude());}
    private void awaitLocation(long token, boolean routeReady) {
        if (!active() || state.epoch() != token) return;
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
        report("正在规划普通驾车路线…\n路线就绪后自动开始导航。");
        Diagnostics.record("route_action", "action", "plan_submit", "reason", "driving", "fix_age_ms", DiagnosticRecord.age(SystemClock.elapsedRealtimeNanos(), origin.getElapsedRealtimeNanos()));
        ++searchEpoch;
        plannedStart=new Location(origin);final LatLng target=destinationPoint();
        try{
            NaviSetting.updatePrivacyShow(context,true,true);NaviSetting.updatePrivacyAgree(context,true);
            AMapNavi.setApiKey(context,host.key());
            navi=AMapNavi.getInstance(context);
            final AMapNavi engine=navi;
            engine.stopSpeak();engine.setUseInnerVoice(false,false);voicePlaying=false;
            final boolean[] submittedOnce={false};
            listener=new SimpleNaviListener(){
                @Override public void onInitNaviSuccess(){
                    if(!validPlan(token,engine)||submittedOnce[0])return;submittedOnce[0]=true;
                    AMapCarInfo car=new AMapCarInfo();car.setCarType("0");engine.setCarInfo(car);
                    boolean submitted=engine.calculateDriveRoute(Collections.singletonList(new NaviLatLng(plannedStart.getLatitude(),plannedStart.getLongitude())),
                        Collections.singletonList(new NaviLatLng(target.latitude,target.longitude)),Collections.emptyList(),PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT);
                    Diagnostics.record("route_action", "action", "sdk_request", "accepted", submitted);
                    if(!submitted)stop("驾车算路请求未提交，请检查配置后重试。");
                }
                @Override public void onInitNaviFailure(){if(validPlan(token,engine))stop("导航SDK初始化失败，未开始导航。");}
                @Override public void onCalculateRouteFailure(AMapCalcRouteResult result){
                    boolean replanning = validReroute(token, engine, result);
                    if (!validPlan(token, engine) && !replanning) return;
                    if (replanning && result != null) settleRouteRequest(result.getRouteRequestId());
                    Diagnostics.record("route_result", "state", replanning ? "reroute_failed" : "failed", "error", result==null?-1:result.getErrorCode());
                    if (replanning && JourneyState.retryableRouteError(result == null ? -1 : result.getErrorCode())) {
                        beginReroute(token, engine, "network", "路线更新暂未成功，保持导航并等待重试…");
                        scheduleRouteRetry("network");
                        return;
                    }
                    stop((replanning ? "路线更新失败，导航已结束。" : "") + JourneyState.routeError(result==null?-1:result.getErrorCode()));
                }
                @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result){
                    if (result == null) {
                        if (validPlan(token,engine)) stop("高德未返回有效驾车路线。");
                        else if (validReroute(token,engine,null)) scheduleRouteRetry("invalid_result");
                        return;
                    }
                    if (validReroute(token, engine, result)) {
                        settleRouteRequest(result.getRouteRequestId());
                        try {
                            // During GPS navigation the SDK owns the active route. selectRouteId is pre-navigation only.
                            if (!replaceRoute(engine.getNaviPath())) { stop("更新后的路线无效，导航已结束。"); return; }
                            if (!state.rerouted(token, SystemClock.elapsedRealtime())) { stop("路线更新超时，请重新开始导航。"); return; }
                            nextRouteRetryMs = -1; retryFailures = 0; retryRequestId = -1;
                            frame = NavigationFrame.status(NavigationFrame.WAITING); icon(null);
                            drawRoute(false);
                            Diagnostics.record("route_result", "state", "reroute_success", "count", result == null ? -1 : result.getCalcRouteType());
                            report("驾车路线已更新，等待新的位置和导航指令。");
                        } catch (RuntimeException e) { stop("路线更新失败，请重新开始导航。"); }
                        return;
                    }
                    if(!validPlan(token,engine))return;
                    Diagnostics.record("route_result", "state", "sdk_success");
                    int[] ids=result==null?null:result.getRouteid();Map<Integer,AMapNaviPath> paths=engine.getNaviPaths();
                    if(ids==null||ids.length==0||paths==null||paths.get(ids[0])==null){stop("高德未返回有效驾车路线。");return;}
                    try{
                        if (!replaceRoute(paths.get(ids[0]))) { stop("路线几何或距离无效，不能开始导航。"); return; }
                        if (!engine.selectRouteId(ids[0])) { stop("高德路线选择失败。"); return; }
                        drawRoute(true);
                        if(state.ready(token,SystemClock.elapsedRealtime())) {
                            report("驾车路线已就绪，正在启动导航…");
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
                            || state.phase() != JourneyState.Phase.NAVIGATING || info == null) return;
                    long now = SystemClock.elapsedRealtime();
                    Diagnostics.record("navigation_state", "state", "sdk_instruction", "count", info.getNaviType(),
                            "accepted", info.getNaviType() == NaviType.GPS, "icon", info.getIconType(),
                            "callback_gap_ms", lastInstructionReceivedMs < 0 ? -1 : now-lastInstructionReceivedMs);
                    if (info.getNaviType() != NaviType.GPS) return;
                    lastInstructionReceivedMs = now;
                    if (!guidanceReady(true)) return;
                    Location fix = guidanceFix();
                    if (fix == null) return;
                    int speed = fix != null && fix.hasSpeed() && Float.isFinite(fix.getSpeed())
                            ? Math.round(fix.getSpeed() * 3.6f) : -1;
                    frame = new NavigationFrame(NavigationFrame.LIVE, info.getIconType(),
                            info.getCurStepRetainDistance(), info.getPathRetainDistance(),
                            info.getPathRetainTime(), speed, fix.getElapsedRealtimeNanos() / 1_000_000L,
                            SystemClock.elapsedRealtime()).at(SystemClock.elapsedRealtime());
                    if (frame.mode() != NavigationFrame.LIVE) { syncVoice(false); icon(null); report("等待有效的导航指令。"); return; }
                    lastWaitingReason = null;
                    nextRoad=info.getNextRoadName();
                    icon(info.getIconBitmap());
                    report(JourneyState.maneuver(info.getIconType())+" · 剩余"+Math.max(0,info.getPathRetainDistance())+"米 / "+Math.max(0,info.getPathRetainTime()/60)+"分钟\n下一路段："+safeName(info.getNextRoadName())+" · 距当前段末"+Math.max(0,info.getCurStepRetainDistance())+"米");
                }
                @Override public void onLocationChange(AMapNaviLocation location) {
                    if (location == null) return;
                    Long measured = location.getTime();
                    NaviLatLng coordinate = location.getCoord();
                    double latitude=coordinate==null?Double.NaN:coordinate.getLatitude(),longitude=coordinate==null?Double.NaN:coordinate.getLongitude();
                    int source=location.getLocationType();
                    float accuracy=location.getAccuracy(),speed=location.getSpeed(),bearing=location.getBearing();
                    boolean matched=location.isMatchNaviPath();
                    Runnable receive=() -> {
                        if (!active() || engine != navi || !state.active(token)) return;
                        boolean accepted = sdkFixes.offer(state.phase() == JourneyState.Phase.NAVIGATING,
                                SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis(),source,
                                measured == null ? 0 : measured,latitude,longitude,accuracy,speed,bearing);
                        NaviFixTracker.Fix current = sdkFixes.current(SystemClock.elapsedRealtimeNanos());
                        lastSdkFixNanos = current == null ? 0 : current.timeNanos();
                        Diagnostics.record("navigation_state", "state", "sdk_position", "source",source,
                                "accuracy_m",accuracy,"route_valid",matched,"accepted",accepted,
                                "measurement_age_ms",DiagnosticRecord.age(SystemClock.elapsedRealtimeNanos(),lastSdkFixNanos));
                    };
                    if(Looper.myLooper()==handler.getLooper())receive.run();else handler.post(receive);
                }
                @Override public void onStopNavi(){if(active()&&engine==navi&&state.active(token))stop("SDK已结束导航。");}
                @Override public void onArriveDestination(){if(active()&&engine==navi&&state.active(token))stop("已到达目的地。", NavigationFrame.ARRIVED);}
                @Override public void onReCalculateRouteForYaw(){sdkReroute(token,engine,"yaw","已偏离路线，正在自动重新规划…");}
                @Override public void onReCalculateRouteForTrafficJam(){sdkReroute(token,engine,"traffic","前方路况变化，正在自动更新路线…");}
                @Override public void onGpsSignalWeak(boolean weak) {
                    if (!active() || engine != navi || !state.active(token)) return;
                    gpsWeak = weak;
                    Diagnostics.record("navigation_state", "state", "gps_signal", "gps_weak", weak);
                    if (weak && state.phase() == JourneyState.Phase.NAVIGATING) guidanceReady(false);
                }
            };
            report("正在规划普通驾车路线…");
            engine.addAMapNaviListener(listener);
            handler.postDelayed(()->{if(validPlan(token,engine))stop("算路超时，目的地已保留。请检查网络或服务授权后再次点击开始。");},
                    Math.max(1, Math.min(30000, startDeadlineMs - SystemClock.elapsedRealtime())));
        }catch(Exception|LinkageError e){stop("导航SDK初始化失败，请核对新包名/签名、Key和SDK配置。");}
    }
    private boolean validPlan(long token,AMapNavi engine){return active()&&engine==navi&&state.planning(token);}
    private boolean validReroute(long token, AMapNavi engine, AMapCalcRouteResult result) {
        if (!active() || engine != navi || !state.active(token) || state.phase() != JourneyState.Phase.NAVIGATING) return false;
        if (result == null) return state.rerouting();
        if (result.getCalcRouteType() == ReCalculateRouteType.ROUTE_TYPE_COMMON || settledRouteRequests.contains(result.getRouteRequestId())) return false;
        if (retryRequestId >= 0 && result.getRouteRequestId() != retryRequestId) {
            settleRouteRequest(result.getRouteRequestId()); return false;
        }
        return true;
    }
    private void settleRouteRequest(int id) {
        if (id <= 0) return;
        settledRouteRequests.add(id);
        if (settledRouteRequests.size() > 64) settledRouteRequests.remove(settledRouteRequests.iterator().next());
    }
    private void sdkReroute(long token, AMapNavi engine, String reason, String text) {
        if (!active() || engine != navi || state.phase() != JourneyState.Phase.NAVIGATING || !state.active(token)) return;
        if (retryRequestId >= 0 || nextRouteRetryMs >= 0) state.retryReroute(token,SystemClock.elapsedRealtime());
        settleRouteRequest(retryRequestId); retryRequestId = -1; nextRouteRetryMs = -1;
        beginReroute(token,engine,reason,text);
    }
    private void beginReroute(long token, AMapNavi engine, String reason, String text) {
        if (!active() || engine != navi || !state.reroute(token, SystemClock.elapsedRealtime())) return;
        frame = NavigationFrame.status(NavigationFrame.WAITING); icon(null);
        syncVoice(false);
        // Hide the outdated line while the SDK recalculates; keep the destination and foreground service.
        if (line != null) { line.remove(); line = null; }
        routePoints.clear();
        Diagnostics.record("route_action", "action", "reroute", "reason", reason);
        report(text);
    }
    private void scheduleRouteRetry(String reason) {
        if (!state.rerouting() || nextRouteRetryMs >= 0) return;
        long delay = Math.min(60000, 5000L << Math.min(retryFailures++, 4));
        nextRouteRetryMs = SystemClock.elapsedRealtime() + delay;
        Diagnostics.record("route_action", "action", "retry_scheduled", "reason", reason, "count", retryFailures);
        report("路线更新暂未成功，" + delay / 1000 + " 秒后在定位可用时自动重试。可随时结束导航。");
    }
    private void retryRoute() {
        if (navi == null || !state.retryReroute(state.epoch(), SystemClock.elapsedRealtime())) return;
        nextRouteRetryMs = -1;
        try {
            retryRequestId = navi.reCalculateRouteReturnRequestId(PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT);
            if (retryRequestId < 0) { scheduleRouteRetry("request_rejected"); return; }
            Diagnostics.record("route_action", "action", "reroute_retry", "count", retryFailures);
            report("正在重新获取驾车路线…");
        } catch (RuntimeException e) { retryRequestId = -1; scheduleRouteRetry("request_failed"); }
    }
    private void syncVoice(boolean live) {
        boolean enabled = live && host.voiceEnabled();
        if (navi == null || voicePlaying == enabled) return;
        try {
            navi.setUseInnerVoice(enabled, false);
            if (enabled) navi.startSpeak(); else navi.stopSpeak();
            voicePlaying = enabled;
        } catch (RuntimeException | LinkageError e) {
            Diagnostics.record("navigation_state", "state", "voice_unavailable", "error_class", e.getClass().getSimpleName());
        }
    }
    private boolean replaceRoute(AMapNaviPath path) {
        if (path == null || path.getAllLength() <= 0) return false;
        List<NaviLatLng> coordinates = path.getCoordList();
        if (coordinates == null || coordinates.size() < 2) return false;
        List<LatLng> next = new ArrayList<>();
        for (NaviLatLng point : coordinates) {
            if (point == null || !JourneyState.coordinate(point.getLatitude(), point.getLongitude())) return false;
            next.add(new LatLng(point.getLatitude(), point.getLongitude()));
        }
        routePoints.clear(); routePoints.addAll(next);
        List<DeviceRoute.Coordinate> deviceCoordinates=new ArrayList<>();
        for(LatLng point:next)deviceCoordinates.add(new DeviceRoute.Coordinate(point.latitude,point.longitude));
        deviceRoute.replace(deviceCoordinates);
        return true;
    }
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
        startReplans = 0;
        Diagnostics.record("route_action", "action", "start_requested", "phase", state.phase().name(), "destination", true);
        if (!host.navigationAllowed()) { stop("请开启精确位置权限和系统定位，再点击开始导航。"); return; }
        boolean accepted = host.beginNavigation(() -> {
            if (!active() || !state.locating(token)) return;
            if (!usableFix()) host.refreshLocation();
            awaitLocation(token, false);
        });
        if (!accepted) stop("后台导航启动失败，请检查精确定位权限后重试。");
    }

    private void startReady() {
        if (!active() || navi == null || plannedStart == null || state.phase() != JourneyState.Phase.READY) return;
        Location current = host.fix();
        if (current == null || !usableFix()) {
            host.refreshLocation();
            awaitLocation(state.epoch(), true);
            return;
        }
        double moved = plannedStart.distanceTo(current);
        if (!state.start(true, SystemClock.elapsedRealtime(), moved)) {
            replanStart(moved);
            return;
        }
        report("正在启动普通驾车导航…");
        final long token=state.epoch();
        startEngine(token);
    }
    private void replanStart(double moved) {
        if (!Double.isFinite(moved) || moved < 0 || startReplans >= 2 || SystemClock.elapsedRealtime() >= startDeadlineMs) {
            stop("当前位置持续变化或启动超时，目的地已保留。请定位稳定后再点开始。"); return;
        }
        ++startReplans;
        Diagnostics.record("route_action", "action", "origin_changed", "moved_m", moved, "count", startReplans);
        stopEngine(); clearRoute();
        long next = state.locate(true);
        if (next > 0) awaitLocation(next, false);
    }
    private void startEngine(long token) {
        if (!active() || state.epoch() != token || state.phase() != JourneyState.Phase.STARTING) return;
        if (SystemClock.elapsedRealtime() >= startDeadlineMs) { stop("启动导航超时，请再次点击开始。目的地已保留。"); return; }
        if (!host.navigationAllowed()) { stop("精确位置权限或系统定位已关闭，未启动导航。"); return; }
        if (!usableFix()) {
            // The foreground service already owns continuous location. Keep the route while it recovers.
            report("正在等待当前位置恢复，恢复后自动启动导航…");
            pendingLocation = () -> startEngine(token);
            handler.postDelayed(pendingLocation, 1000);
            return;
        }
        pendingLocation = null;
        Location current = host.fix();
        double moved = current == null || plannedStart == null ? Double.NaN : plannedStart.distanceTo(current);
        if (!state.startPositionValid(SystemClock.elapsedRealtime(), moved)) { replanStart(moved); return; }
        try {
            if (!navi.startNavi(NaviType.GPS)) { stop("GPS导航启动请求被拒绝。"); return; }
            handler.postDelayed(() -> {
                if (active() && state.epoch() == token && state.phase() == JourneyState.Phase.STARTING)
                    stop("GPS导航未成功启动，请重新规划后重试。");
            }, Math.max(1, Math.min(10000, startDeadlineMs - SystemClock.elapsedRealtime())));
        } catch (RuntimeException | LinkageError e) { stop("GPS导航启动失败。"); }
    }
    private void cameraFollow(boolean follow) {
        if (map != null) map.setMyLocationStyle(new MyLocationStyle().myLocationType(
                follow ? MyLocationStyle.LOCATION_TYPE_FOLLOW : MyLocationStyle.LOCATION_TYPE_FOLLOW_NO_CENTER).interval(2000));
    }
    private void drawRoute(boolean overview) {
        if (map == null || routePoints.size() < 2) return;
        if (line != null) line.remove();
        line = map.addPolyline(new PolylineOptions().addAll(routePoints).width(15).color(0xffb66dff));
        if (overview && ui != null) {
            ui.overview(); cameraFollow(false);
            LatLngBounds.Builder bounds = new LatLngBounds.Builder();
            for (LatLng point : routePoints) bounds.include(point);
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80));
        }
    }
    private void clearRoute() {
        deviceRoute.clear();
        if (line != null) { line.remove(); line = null; }
        routePoints.clear(); plannedStart = null;
    }
    private void stopEngine() {
        nextRoad="";
        sdkFixes.clear();
        lastInstructionReceivedMs = -1; lastSdkFixNanos = 0; lastWaitingReason = null;
        nextRouteRetryMs = -1; retryRequestId = -1; retryFailures = 0; voicePlaying = false; settledRouteRequests.clear();
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
