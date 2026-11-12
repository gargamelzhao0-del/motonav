"""Run the real controller against deterministic SDK/UI doubles. This is not device validation."""
from pathlib import Path
import subprocess
import tempfile

stubs = {
    "android/Manifest.java": 'package android; public class Manifest { public static class permission { public static final String ACCESS_FINE_LOCATION="fine"; } }',
    "android/content/Context.java": 'package android.content; public class Context { public Context getApplicationContext(){return this;} public int checkSelfPermission(String p){return 0;} }',
    "android/content/pm/PackageManager.java": 'package android.content.pm; public class PackageManager {public static final int PERMISSION_GRANTED=0;}',
    "android/content/DialogInterface.java": '''package android.content; public interface DialogInterface { interface OnClickListener {void onClick(DialogInterface d,int which);} interface OnDismissListener {void onDismiss(DialogInterface d);} }''',
    "android/app/Activity.java": 'package android.app; public class Activity extends android.content.Context {}',
    "android/app/AlertDialog.java": '''package android.app;
public class AlertDialog implements android.content.DialogInterface {
 public void show(){} public void dismiss(){} public void setOnDismissListener(OnDismissListener l){}
 public static class Builder {
  public Builder(Activity a){} public Builder setTitle(String s){return this;} public Builder setView(Object o){return this;}
  public Builder setNegativeButton(String s,OnClickListener l){return this;} public Builder setPositiveButton(String s,OnClickListener l){return this;}
  public Builder setItems(String[] s,OnClickListener l){return this;} public AlertDialog create(){return new AlertDialog();}
 }
}''',
    "android/graphics/Bitmap.java": 'package android.graphics; public class Bitmap {}',
    "android/widget/EditText.java": 'package android.widget; public class EditText {public EditText(android.app.Activity a){} public void setHint(String s){} public void setSingleLine(boolean b){} public void setSaveEnabled(boolean b){} public String getText(){return "";}}',
    "android/os/Looper.java": 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    "android/os/SystemClock.java": 'package android.os; public class SystemClock {public static long now=100000; public static long elapsedRealtime(){return now;} public static long elapsedRealtimeNanos(){return now*1000000;}}',
    "android/os/Handler.java": '''package android.os;
public class Handler {
 record Task(Handler owner,Runnable run,long at,long seq){}
 static final java.util.List<Task> tasks=new java.util.ArrayList<>(); static long sequence;
 public Handler(Looper l){} public void postDelayed(Runnable r,long delay){tasks.add(new Task(this,r,SystemClock.now+delay,sequence++));}
 public void removeCallbacks(Runnable r){tasks.removeIf(t->t.owner==this&&t.run==r);}
 public void removeCallbacksAndMessages(Object o){tasks.removeIf(t->t.owner==this);}
 public static void reset(){tasks.clear();SystemClock.now=100000;}
 public static void advance(long ms){
  long end=SystemClock.now+ms;
  while(true){Task next=tasks.stream().filter(t->t.at<=end).min(java.util.Comparator.comparingLong(Task::at).thenComparingLong(Task::seq)).orElse(null);
   if(next==null)break;tasks.remove(next);SystemClock.now=next.at;next.run.run();}
  SystemClock.now=end;
 }
}''',
    "android/location/Location.java": '''package android.location;
public class Location {
 private double lat,lon; private float accuracy; private long time;
 public Location(String p){} public Location(Location l){lat=l.lat;lon=l.lon;accuracy=l.accuracy;time=l.time;}
 public double getLatitude(){return lat;} public double getLongitude(){return lon;} public void setLatitude(double d){lat=d;} public void setLongitude(double d){lon=d;}
 public boolean hasAccuracy(){return true;} public float getAccuracy(){return accuracy;} public void setAccuracy(float f){accuracy=f;}
 public boolean isFromMockProvider(){return false;} public long getElapsedRealtimeNanos(){return time;} public void setElapsedRealtimeNanos(long t){time=t;}
 public boolean hasSpeed(){return false;} public float getSpeed(){return 0;} public float distanceTo(Location l){return (float)(Math.hypot(l.lat-lat,l.lon-lon)*111000);}
}''',
    "com/amap/api/maps/AMap.java": '''package com.amap.api.maps; import com.amap.api.maps.model.*;
public class AMap {public Polyline line; public Polyline addPolyline(PolylineOptions p){line=new Polyline(p);return line;} public Marker addMarker(MarkerOptions p){return new Marker();} public void setMyLocationStyle(MyLocationStyle s){} public void animateCamera(Object u){} }''',
    "com/amap/api/maps/CameraUpdateFactory.java": 'package com.amap.api.maps; public class CameraUpdateFactory {public static Object newLatLngZoom(Object p,int z){return p;} public static Object newLatLngBounds(Object p,int z){return p;}}',
    "com/amap/api/maps/model/LatLng.java": 'package com.amap.api.maps.model; public class LatLng {public final double latitude,longitude;public LatLng(double a,double b){latitude=a;longitude=b;}}',
    "com/amap/api/maps/model/LatLngBounds.java": 'package com.amap.api.maps.model; public class LatLngBounds {public static class Builder {public void include(LatLng p){} public Object build(){return this;}}}',
    "com/amap/api/maps/model/Marker.java": 'package com.amap.api.maps.model; public class Marker {public void remove(){}}',
    "com/amap/api/maps/model/MarkerOptions.java": 'package com.amap.api.maps.model; public class MarkerOptions {public MarkerOptions position(LatLng p){return this;}public MarkerOptions title(String s){return this;}}',
    "com/amap/api/maps/model/MyLocationStyle.java": 'package com.amap.api.maps.model; public class MyLocationStyle {public static final int LOCATION_TYPE_FOLLOW=1,LOCATION_TYPE_FOLLOW_NO_CENTER=2; public MyLocationStyle myLocationType(int i){return this;}public MyLocationStyle interval(int i){return this;}}',
    "com/amap/api/maps/model/Polyline.java": 'package com.amap.api.maps.model; public class Polyline {public final PolylineOptions options;public boolean removed; public Polyline(PolylineOptions p){options=p;}public void remove(){removed=true;}}',
    "com/amap/api/maps/model/PolylineOptions.java": 'package com.amap.api.maps.model; public class PolylineOptions {public java.util.List<LatLng> points;public PolylineOptions addAll(java.util.List<LatLng> p){points=new java.util.ArrayList<>(p);return this;}public PolylineOptions width(int i){return this;}public PolylineOptions color(int i){return this;}}',
    "com/amap/api/navi/NaviSetting.java": 'package com.amap.api.navi; public class NaviSetting {public static void updatePrivacyShow(Object c,boolean a,boolean b){}public static void updatePrivacyAgree(Object c,boolean b){}}',
    "com/amap/api/navi/enums/NaviType.java": 'package com.amap.api.navi.enums; public class NaviType {public static final int GPS=1;}',
    "com/amap/api/navi/enums/PathPlanningStrategy.java": 'package com.amap.api.navi.enums; public class PathPlanningStrategy {public static final int DRIVING_MULTIPLE_ROUTES_DEFAULT=10;}',
    "com/amap/api/navi/enums/ReCalculateRouteType.java": 'package com.amap.api.navi.enums; public class ReCalculateRouteType {public static final int ROUTE_TYPE_COMMON=0;}',
    "com/amap/api/navi/model/AMapCarInfo.java": 'package com.amap.api.navi.model; public class AMapCarInfo {public String type;public void setCarType(String t){type=t;}}',
    "com/amap/api/navi/model/NaviLatLng.java": 'package com.amap.api.navi.model; public class NaviLatLng {private final double lat,lon;public NaviLatLng(double a,double b){lat=a;lon=b;}public double getLatitude(){return lat;}public double getLongitude(){return lon;}}',
    "com/amap/api/navi/model/AMapCalcRouteResult.java": 'package com.amap.api.navi.model; public class AMapCalcRouteResult {public int type,error,requestId;public int[] ids={1};public AMapCalcRouteResult(int t){type=t;requestId=t==0?1:t+10;}public int getRouteRequestId(){return requestId;}public int getCalcRouteType(){return type;}public int getErrorCode(){return error;}public int[] getRouteid(){return ids;}}',
    "com/amap/api/navi/model/AMapNaviPath.java": '''package com.amap.api.navi.model; public class AMapNaviPath {public java.util.List<NaviLatLng> points=java.util.List.of(new NaviLatLng(30,120),new NaviLatLng(30.01,120.01)); public int length=1000;public java.util.List<NaviLatLng> getCoordList(){return points;}public int getAllLength(){return length;}}''',
    "com/amap/api/navi/model/NaviInfo.java": '''package com.amap.api.navi.model; public class NaviInfo {public int getNaviType(){return 1;}public int getIconType(){return 3;}public int getCurStepRetainDistance(){return 60;}public int getPathRetainDistance(){return 800;}public int getPathRetainTime(){return 120;}public String getNextRoadName(){return "road";}public android.graphics.Bitmap getIconBitmap(){return null;}}''',
    "com/amap/api/navi/SimpleNaviListener.java": '''package com.amap.api.navi; import com.amap.api.navi.model.*;
public class SimpleNaviListener {public void onInitNaviSuccess(){}public void onInitNaviFailure(){}public void onCalculateRouteFailure(AMapCalcRouteResult r){}public void onCalculateRouteSuccess(AMapCalcRouteResult r){}public void onStartNavi(int t){}public void onNaviInfoUpdate(NaviInfo i){}public void onStopNavi(){}public void onArriveDestination(){}public void onReCalculateRouteForYaw(){}public void onReCalculateRouteForTrafficJam(){}public void onGpsSignalWeak(boolean w){}}
''',
    "com/amap/api/navi/AMapNavi.java": '''package com.amap.api.navi; import com.amap.api.navi.model.*; import java.util.*;
public class AMapNavi {
 public static AMapNavi latest; public static int instances;
 public SimpleNaviListener listener; public AMapCarInfo car; public int strategy,starts,stops,selections,recalculations; public boolean voice,speaking; public NaviLatLng from;
 public AMapNaviPath path=new AMapNaviPath(); public Map<Integer,AMapNaviPath> paths=new HashMap<>();
 public AMapNavi(){paths.put(1,path);} public static AMapNavi getInstance(Object c){instances++;return latest=new AMapNavi();}public static void setApiKey(Object c,String k){} public static void destroy(){}
 public void stopSpeak(){speaking=false;}public void startSpeak(){speaking=true;}public void setUseInnerVoice(boolean a,boolean b){voice=a;}public void setCarInfo(AMapCarInfo c){car=c;}
 public int reCalculateRouteReturnRequestId(int strategy){return 100+(++recalculations);}
 public boolean calculateDriveRoute(List<NaviLatLng> f,List<NaviLatLng> t,List<NaviLatLng> w,int s){strategy=s;from=f.get(0);return true;}
 public Map<Integer,AMapNaviPath> getNaviPaths(){return paths;}public AMapNaviPath getNaviPath(){return path;}
 public boolean selectRouteId(int i){selections++;return true;}public boolean startNavi(int t){starts++;listener.onStartNavi(t);return true;}
 public void addAMapNaviListener(SimpleNaviListener l){listener=l;l.onInitNaviSuccess();}public void removeAMapNaviListener(SimpleNaviListener l){}
 public void stopNavi(){stops++;}public void stopGPS(){}
}''',
    "com/amap/api/services/core/LatLonPoint.java": 'package com.amap.api.services.core; public class LatLonPoint {public double getLatitude(){return 30.01;}public double getLongitude(){return 120.01;}}',
    "com/amap/api/services/core/PoiItem.java": 'package com.amap.api.services.core; public class PoiItem {public LatLonPoint getLatLonPoint(){return new LatLonPoint();}public String getTitle(){return "destination";}public String getSnippet(){return "address";}}',
    "com/amap/api/services/core/ServiceSettings.java": 'package com.amap.api.services.core; public class ServiceSettings {public static final int HTTPS=2;public static void updatePrivacyShow(Object c,boolean a,boolean b){}public static void updatePrivacyAgree(Object c,boolean b){}public static ServiceSettings getInstance(){return new ServiceSettings();}public void setApiKey(String s){}public void setProtocol(int i){}public void setConnectionTimeOut(int i){}public void setSoTimeOut(int i){}}',
    "com/amap/api/services/poisearch/PoiResult.java": 'package com.amap.api.services.poisearch; public class PoiResult {public java.util.List<com.amap.api.services.core.PoiItem> getPois(){return java.util.List.of();}}',
    "com/amap/api/services/poisearch/PoiSearch.java": '''package com.amap.api.services.poisearch; public class PoiSearch {
 public PoiSearch(Object c,Query q){}public void searchPOIAsyn(){}public void setOnPoiSearchListener(OnPoiSearchListener l){}
 public interface OnPoiSearchListener {void onPoiItemSearched(com.amap.api.services.core.PoiItem p,int c);void onPoiSearched(PoiResult r,int c);}
 public static class Query {public Query(String a,String b,String c){}public void setPageSize(int i){}public void setPageNum(int i){}}
}''',
    "com/motonav/companion/Diagnostics.java": 'package com.motonav.companion; public class Diagnostics {public static void record(String e,Object... f){DiagnosticRecord.line("0".repeat(32),1,1,e,f);}}',
}

with tempfile.TemporaryDirectory(prefix="motonav-journey-") as directory:
    root = Path(directory)
    for relative, source in stubs.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
    production = Path("app/src/main/java/com/motonav/companion")
    sources = [str(root / name) for name in stubs]
    sources += [str(production / (name + ".java")) for name in
                ("JourneyController", "JourneyState", "MapStartupPolicy", "NavigationFrame", "DiagnosticRecord")]
    classes = root / "classes"
    classes.mkdir()
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8",
                    "-source", "17", "-target", "17", "-d", str(classes), *sources,
                    "tests/JourneyControllerTests.java"], check=True)
    subprocess.run(["java", "-cp", str(classes), "com.motonav.companion.JourneyControllerTests"], check=True)
