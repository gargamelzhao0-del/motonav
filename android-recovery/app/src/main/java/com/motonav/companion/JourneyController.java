package com.motonav.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
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

/** Phone-only, explicit-start GPS navigation experiment. Never feeds fictional data or BLE. */
final class JourneyController {
    interface Host {
        boolean active(); boolean navigationAllowed(); String key(); Location fix(); long fixNanos(); void report(String message); void overview(); void icon(android.graphics.Bitmap bitmap);
    }
    private final Activity activity;
    private final AMap map;
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
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!active() || state.phase() != JourneyState.Phase.NAVIGATING) return;
            guidanceReady(false);
            if (state.phase() == JourneyState.Phase.NAVIGATING) handler.postDelayed(this, 2000);
        }
    };
    JourneyController(Activity activity,AMap map,Host host){this.activity=activity;this.map=map;this.host=host;}
    private boolean active(){return !destroyed&&host.active();}
    private boolean usableFix() { return trustedFixTime() > 0; }

    private long trustedFixTime() {
        Location fix = host.fix();
        if (fix == null || !JourneyState.coordinate(fix.getLatitude(), fix.getLongitude())) return 0;
        long time = host.fixNanos();
        return JourneyState.usableFix(
                activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
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
        if (guidance == JourneyState.Guidance.LIVE) return true;
        if (guidance == JourneyState.Guidance.EXPIRED) {
            stop("定位或导航指令持续中断，导航已结束。定位恢复后可重新规划。");
        } else {
            host.icon(null);
            host.report("定位或导航指令暂时中断。已保留路线，正在等待恢复。");
        }
        return false;
    }
    private void show(AlertDialog next){if(dialog!=null)dialog.dismiss();dialog=next;next.setOnDismissListener(d->{if(dialog==next)dialog=null;});next.show();}
    void searchDialog(){
        if(!active())return;
        if(state.phase()==JourneyState.Phase.NAVIGATING||state.phase()==JourneyState.Phase.STARTING){host.report("请先结束导航并停车，再更换目的地。");return;}
        EditText input=new EditText(activity);input.setHint("城市 + 地点名称（发送给高德搜索）");input.setSingleLine(true);input.setSaveEnabled(false);
        show(new AlertDialog.Builder(activity).setTitle("搜索真实目的地").setView(input)
            .setNegativeButton("取消",null).setPositiveButton("搜索",(d,w)->search(input.getText().toString().trim())).create());
    }
    private void search(String query){
        if(!active())return;
        if(query.length()<2||query.length()>80){host.report("请输入2–80字的城市和地点名称。");return;}
        final int token=++searchEpoch;
        final boolean[] settled={false};
        try{
            ServiceSettings.updatePrivacyShow(activity,true,true);ServiceSettings.updatePrivacyAgree(activity,true);
            ServiceSettings.getInstance().setApiKey(host.key());ServiceSettings.getInstance().setProtocol(ServiceSettings.HTTPS);
            ServiceSettings.getInstance().setConnectionTimeOut(10000);ServiceSettings.getInstance().setSoTimeOut(10000);
            PoiSearch.Query request=new PoiSearch.Query(query,"","");request.setPageSize(8);request.setPageNum(0);
            PoiSearch search=new PoiSearch(activity,request);
            search.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener(){
                public void onPoiItemSearched(PoiItem item,int code){}
                public void onPoiSearched(PoiResult result,int code){
                    if(!active()||token!=searchEpoch)return;
                    settled[0]=true;
                    if(code!=1000||result==null){host.report("搜索失败（"+code+"），请检查高德Key、服务权限及网络。未生成目的地。");return;}
                    List<PoiItem> valid=new ArrayList<>();
                    if(result.getPois()!=null)for(PoiItem item:result.getPois())if(item!=null&&item.getLatLonPoint()!=null&&JourneyState.coordinate(item.getLatLonPoint().getLatitude(),item.getLatLonPoint().getLongitude()))valid.add(item);
                    if(valid.isEmpty()){host.report("没有找到匹配地点，请补充城市或更具体的名称。");return;}
                    String[] labels=new String[valid.size()];for(int i=0;i<labels.length;i++)labels[i]=valid.get(i).getTitle()+"\n"+valid.get(i).getSnippet();
                    show(new AlertDialog.Builder(activity).setTitle("选择目的地 · 请核对地址").setItems(labels,(d,w)->{
                        if(!active()||token!=searchEpoch)return;
                        stopEngine();clearRoute();destination=valid.get(w);++searchEpoch;
                        LatLng at=destinationPoint();if(destinationMarker!=null)destinationMarker.remove();
                        destinationMarker=map.addMarker(new MarkerOptions().position(at).title(destination.getTitle()));
                        cameraFollow(false);map.animateCamera(CameraUpdateFactory.newLatLngZoom(at,15));
                        host.report("目的地："+destination.getTitle()+"。地图/搜索成功不代表摩托授权，请点摩托算路。");
                    }).setNegativeButton("取消",null).create());
                }
            });
            search.searchPOIAsyn();host.report("正在搜索真实地点…");
            handler.postDelayed(()->{if(active()&&token==searchEpoch&&!settled[0]){++searchEpoch;host.report("搜索超时，请重试；迟到结果将被忽略。");}},15000);
        }catch(Exception|LinkageError e){++searchEpoch;host.report("搜索初始化失败，请检查服务配置。");}
    }
    private LatLng destinationPoint(){return new LatLng(destination.getLatLonPoint().getLatitude(),destination.getLatLonPoint().getLongitude());}
    void planDialog(){
        if(!active())return;
        if(destination==null){host.report("请先搜索并选择真实目的地。");return;}
        if(!usableFix()){host.report("算路需要未过期的精确定位（精度≤100米），请检查定位权限、开关和室外信号。");return;}
        if(state.phase()==JourneyState.Phase.PLANNING||state.phase()==JourneyState.Phase.NAVIGATING||state.phase()==JourneyState.Phase.STARTING){host.report("当前正在算路或导航，请先取消/结束。");return;}
        LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);
        EditText plate=new EditText(activity);plate.setHint("真实车牌（用于限行计算，不保存在本App）");plate.setSingleLine(true);plate.setSaveEnabled(false);fields.addView(plate);
        EditText cc=new EditText(activity);cc.setHint("实际发动机排量 cc");cc.setInputType(InputType.TYPE_CLASS_NUMBER);cc.setSaveEnabled(false);fields.addView(cc);
        show(new AlertDialog.Builder(activity).setTitle("摩托车算路 · 受控测试").setMessage("将当前坐标、目的地、车牌与排量发给高德，用于摩托路线。仅在停车状态测试，不能作为骑行安全依据。")
            .setView(fields).setNegativeButton("取消",null).setPositiveButton("提交摩托算路",(d,w)->{
                String number=plate.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);int displacement=JourneyState.displacement(cc.getText().toString());
                if(!number.matches("[\\p{IsHan}A-Z0-9]{5,12}")||displacement==0){host.report("请填写真实完整车牌与1–10000cc排量，不使用示例车辆数据。");return;}
                plan(number,displacement);
            }).create());
    }
    private void plan(String plate,int cc){
        if(!active()||!usableFix()||destination==null)return;
        ++searchEpoch;stopEngine();clearRoute();
        final long token=state.begin(true,true);if(token<0)return;
        plannedStart=new Location(host.fix());final LatLng target=destinationPoint();
        try{
            NaviSetting.updatePrivacyShow(activity,true,true);NaviSetting.updatePrivacyAgree(activity,true);
            AMapNavi.setApiKey(activity,host.key());
            navi=AMapNavi.getInstance(activity);
            final AMapNavi engine=navi;
            engine.stopSpeak();engine.setUseInnerVoice(false,false);
            final boolean[] submittedOnce={false};
            listener=new SimpleNaviListener(){
                @Override public void onInitNaviSuccess(){
                    if(!validPlan(token,engine)||submittedOnce[0])return;submittedOnce[0]=true;
                    AMapCarInfo car=new AMapCarInfo();car.setCarType("11");car.setCarNumber(plate);car.setMotorcycleCC(cc);engine.setCarInfo(car);
                    boolean submitted=engine.calculateDriveRoute(Collections.singletonList(new NaviLatLng(plannedStart.getLatitude(),plannedStart.getLongitude())),
                        Collections.singletonList(new NaviLatLng(target.latitude,target.longitude)),Collections.emptyList(),PathPlanningStrategy.MOTOR_MULTIPLE_DEFAULT);
                    if(!submitted)stop("摩托算路请求未提交，请检查配置后重试。");
                }
                @Override public void onInitNaviFailure(){if(validPlan(token,engine))stop("导航SDK初始化失败，未开始导航。");}
                @Override public void onCalculateRouteFailure(AMapCalcRouteResult result){if(validPlan(token,engine))stop(JourneyState.routeError(result==null?-1:result.getErrorCode()));}
                @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result){
                    if(!validPlan(token,engine))return;
                    int[] ids=result==null?null:result.getRouteid();Map<Integer,AMapNaviPath> paths=engine.getNaviPaths();
                    if(ids==null||ids.length==0||paths==null||paths.get(ids[0])==null){stop("高德未返回有效摩托路线。");return;}
                    AMapNaviPath path=paths.get(ids[0]);List<NaviLatLng> coords=path.getCoordList();
                    if(coords==null||coords.size()<2||path.getAllLength()<=0){stop("路线几何或距离无效，不能开始导航。");return;}
                    try{
                        if(!engine.selectRouteId(ids[0])){stop("高德路线选择失败。");return;}PolylineOptions shape=new PolylineOptions().width(15).color(0xffb4ff65);LatLngBounds.Builder bounds=new LatLngBounds.Builder();
                        for(NaviLatLng point:coords){if(point==null||!JourneyState.coordinate(point.getLatitude(),point.getLongitude())){stop("路线坐标无效。");return;}LatLng at=new LatLng(point.getLatitude(),point.getLongitude());shape.add(at);bounds.include(at);}
                        line=map.addPolyline(shape);host.overview();cameraFollow(false);map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),80));
                        if(state.ready(token,SystemClock.elapsedRealtime()))host.report("摩托路线已返回："+path.getAllLength()+"米 / 约"+Math.max(1,path.getAllTime()/60)+"分钟。请核对路线，再点开始；不是实机安全验收。");
                    }catch(RuntimeException e){stop("路线显示失败，未开始导航。");}
                }
                @Override public void onStartNavi(int type){
                    if(!active()||engine!=navi||type!=NaviType.GPS||!state.started(token))return;
                    cameraFollow(true);state.startGuidance(SystemClock.elapsedRealtime());handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,2000);host.report("前台GPS导航测试已启动，等待实时指令。无语音/后台导航，不用于实际骑行。");
                }
                @Override public void onNaviInfoUpdate(NaviInfo info){
                    if (!active() || engine != navi || !state.active(token)
                            || state.phase() != JourneyState.Phase.NAVIGATING
                            || info == null || info.getNaviType() != NaviType.GPS) return;
                    if (!guidanceReady(true)) return;
                    host.icon(info.getIconBitmap());
                    host.report(JourneyState.maneuver(info.getIconType())+" · 前台GPS测试 · 剩余"+Math.max(0,info.getPathRetainDistance())+"米 / "+Math.max(0,info.getPathRetainTime()/60)+"分钟\n下一路段："+safeName(info.getNextRoadName())+" · 距当前段末"+Math.max(0,info.getCurStepRetainDistance())+"米");
                }
                @Override public void onStopNavi(){if(active()&&engine==navi&&state.active(token))stop("SDK已结束导航。");}
                @Override public void onArriveDestination(){if(active()&&engine==navi&&state.active(token))stop("高德报告已到达目的地，导航已结束。");}
                @Override public void onReCalculateRouteForYaw(){if(active()&&engine==navi&&state.active(token))stop("检测到偏航，导航已停止；请停车后手动重新规划。");}
                @Override public void onReCalculateRouteForTrafficJam(){if(active()&&engine==navi&&state.active(token))stop("路线需要更新，导航已停止；请停车后重新规划。");}
                @Override public void onGpsSignalWeak(boolean weak) {
                    if (!active() || engine != navi || !state.active(token)) return;
                    gpsWeak = weak;
                    if (weak && state.phase() == JourneyState.Phase.NAVIGATING) guidanceReady(false);
                }
            };
            engine.addAMapNaviListener(listener);
            host.report("正在向高德请求摩托路线（车型11，策略2001）…");
            handler.postDelayed(()->{if(validPlan(token,engine))stop("算路超时，已取消本次流程。请检查网络或服务授权后重试。");},30000);
        }catch(Exception|LinkageError e){stop("导航SDK初始化失败，请核对新包名/签名、Key和SDK配置。");}
    }
    private boolean validPlan(long token,AMapNavi engine){return active()&&engine==navi&&state.planning(token);}
    private static String safeName(String name){return name==null||name.trim().isEmpty()?"等待道路信息":name;}
    void startOrStop(){
        if(!active())return;
        if(state.phase()==JourneyState.Phase.NAVIGATING||state.phase()==JourneyState.Phase.STARTING||state.phase()==JourneyState.Phase.PLANNING){stop("已取消算路/结束导航。");return;}
        if(navi==null||plannedStart==null||state.phase()!=JourneyState.Phase.READY){host.report("请先完成摩托算路。");return;}
        ++searchEpoch;if(dialog!=null){dialog.dismiss();dialog=null;}
        Location current = host.fix();
        if (current == null || !usableFix()) {
            host.report("正在等待可用定位。已保留路线预览，定位恢复后可再次开始。");
            return;
        }
        double moved = plannedStart.distanceTo(current);
        if (!state.start(true, SystemClock.elapsedRealtime(), moved)) {
            stop("路线已过期或起点变化，请重新规划。");
            return;
        }
        final long token=state.epoch();
        try{
            if(!navi.startNavi(NaviType.GPS)){stop("GPS导航启动请求被拒绝，未进入导航。");return;}
            handler.postDelayed(()->{if(active()&&state.epoch()==token&&state.phase()==JourneyState.Phase.STARTING)stop("GPS导航未成功启动，请重新规划后重试。");},10000);
        }catch(RuntimeException|LinkageError e){stop("GPS导航启动失败，未进入导航状态。");}
    }
    private void cameraFollow(boolean follow){map.setMyLocationStyle(new MyLocationStyle().myLocationType(follow?MyLocationStyle.LOCATION_TYPE_FOLLOW:MyLocationStyle.LOCATION_TYPE_FOLLOW_NO_CENTER).interval(2000));}
    private void clearRoute(){if(line!=null){line.remove();line=null;}plannedStart=null;}
    private void stopEngine(){
        state.cancel();gpsWeak=false;host.icon(null);handler.removeCallbacks(watchdog);
        AMapNavi old=navi;SimpleNaviListener oldListener=listener;navi=null;listener=null;
        if(old!=null){
            try{old.removeAMapNaviListener(oldListener);}catch(RuntimeException ignored){}
            try{old.stopNavi();old.stopSpeak();old.stopGPS();}catch(RuntimeException ignored){}
            try{AMapNavi.destroy();}catch(RuntimeException ignored){}
        }
    }
    private void stop(String message){stopEngine();clearRoute();if(active())host.report(message);}
    void pause(){++searchEpoch;if(dialog!=null){dialog.dismiss();dialog=null;}stopEngine();clearRoute();host.report("已离开前台，算路/导航已停止；返回后请重新规划。");}
    void close(){pause();destroyed=true;handler.removeCallbacksAndMessages(null);if(destinationMarker!=null)destinationMarker.remove();destination=null;
        try{ServiceSettings.updatePrivacyAgree(activity,false);ServiceSettings.getInstance().setApiKey("");NaviSetting.updatePrivacyAgree(activity,false);AMapNavi.setApiKey(activity,"");}catch(RuntimeException ignored){}
    }
}
