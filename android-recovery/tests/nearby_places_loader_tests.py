"""Exercise the real optional SDK worker with controlled stalls; no network or map key."""
from pathlib import Path
import os
import subprocess
import tempfile

stubs = {
    'android/content/Context.java': 'package android.content; public class Context {public Context getApplicationContext(){return this;}}',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}',
    'android/os/Handler.java': '''package android.os; public class Handler {
        static final java.util.concurrent.BlockingQueue<Runnable> queue=new java.util.concurrent.LinkedBlockingQueue<>();
        public Handler(Looper l){} public boolean post(Runnable r){queue.add(r);return true;}
        public static void complete() throws Exception {var r=queue.poll(3,java.util.concurrent.TimeUnit.SECONDS);if(r==null)throw new AssertionError("Worker did not complete");r.run();}
    }''',
    'com/amap/api/services/core/LatLonPoint.java': '''package com.amap.api.services.core; public record LatLonPoint(double latitude,double longitude) {
        public double getLatitude(){return latitude;}public double getLongitude(){return longitude;}}''',
    'com/amap/api/services/core/AMapException.java': 'package com.amap.api.services.core; public class AMapException extends Exception {public int getErrorCode(){return 10001;}}',
    'com/amap/api/services/core/ServiceSettings.java': '''package com.amap.api.services.core; public class ServiceSettings {
        public static final int HTTPS=2;public static int configurations;public static Thread caller;
        public static ServiceSettings getInstance(){return new ServiceSettings();}
        public static void updatePrivacyShow(android.content.Context c,boolean x,boolean y){if(Thread.currentThread()!=caller)throw new AssertionError("SDK configuration must precede worker dispatch");configurations++;}
        public static void updatePrivacyAgree(android.content.Context c,boolean b){}
        public void setApiKey(String s){}public void setProtocol(int p){}public void setConnectionTimeOut(int n){}public void setSoTimeOut(int n){}
    }''',
    'com/amap/api/services/poisearch/PoiSearchV2.java': '''package com.amap.api.services.poisearch;
    import com.amap.api.services.core.*;import java.util.*;import java.util.concurrent.*;
    public class PoiSearchV2 {
        public static volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);
        public static volatile int calls,mode;
        final Query query;SearchBound bound;
        public static class Query {String types;int size,page;public Query(String text,String types,String city){this.types=types;if(!text.isEmpty()||!city.isEmpty())throw new AssertionError();}public void setPageSize(int n){size=n;}public void setPageNum(int n){page=n;}}
        public record SearchBound(LatLonPoint center,int radius,boolean sort){}
        public record Item(String id,LatLonPoint point) {public String getPoiId(){return id;}public String getTitle(){return "测试小区"+id;}public String getTypeCode(){return "120302";}public LatLonPoint getLatLonPoint(){return point;}}
        public record Result(ArrayList<Item> pois) {public ArrayList<Item> getPois(){return pois;}}
        public PoiSearchV2(android.content.Context c,Query q){query=q;}public void setBound(SearchBound b){bound=b;}
        public Result searchPOI() throws AMapException {
            if(Thread.currentThread()==ServiceSettings.caller)throw new AssertionError("Network search ran on UI thread");
            if(query.size!=25||query.page!=0||bound.radius()!=1000||!bound.sort())throw new AssertionError("Unbounded search");
            calls++;entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(mode==1||mode==2&&query.types.startsWith("14"))throw new AMapException();
            return new Result(new ArrayList<>(List.of(new Item(query.types,bound.center()))));
        }
    }'''
}
harness = '''package com.motonav.companion;
import android.os.*;import com.amap.api.services.core.*;import com.amap.api.services.poisearch.*;import java.util.*;import java.util.concurrent.*;
public class NearbyPlacesLoaderTests {
    static int checks,changes,ok,failed,error;static String phase;
    static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    static NearbyPlacesLoader loader(){return new NearbyPlacesLoader(new android.content.Context(),new NearbyPlacesLoader.Listener(){
        public void changed(){changes++;}public void status(String p,int n,int o,int f,int e){phase=p;ok=o;failed=f;error=e;}});}
    public static void main(String[] args)throws Exception {
        ServiceSettings.caller=Thread.currentThread();var origin=new DeviceRoute.Coordinate(30.54,104.1);
        var route=new DeviceRoute.Frame(1,List.of(new DeviceRoute.Point(0,0),new DeviceRoute.Point(0,400)),0,0,0);
        var focus=new Basemap.Focus(route,origin,1,5000,600);String key="0123456789abcdef0123456789abcdef";
        var loader=loader();PoiSearchV2.release=new CountDownLatch(1);loader.request(focus,key);
        check(PoiSearchV2.entered.await(3,TimeUnit.SECONDS),"Request runs asynchronously");
        loader.request(focus,key);check(ServiceSettings.configurations==1,"Heartbeat cannot start a parallel batch");
        loader.clear();PoiSearchV2.release.countDown();Handler.complete();
        check(changes==0&&loader.places(focus).isEmpty()&&PoiSearchV2.calls==1,"STOP cancels remaining calls and rejects delayed results");
        SystemClock.now=60000;PoiSearchV2.mode=0;loader.request(focus,key);Handler.complete();
        check(changes==1&&ok==3&&failed==0&&loader.places(focus).size()==3,"All three bounded categories merge independently of map tiles");
        loader.request(focus,key);check(PoiSearchV2.calls==4,"Static heartbeat does not refetch names");
        SystemClock.now=360000;PoiSearchV2.mode=1;loader.request(focus,key);Handler.complete();
        check(phase.equals("error")&&failed==3&&error==10001&&loader.places(focus).size()==3,"Real SDK error code and existing names survive a failed refresh");
        SystemClock.now=420000;PoiSearchV2.mode=2;loader.request(focus,key);Handler.complete();
        check(phase.equals("partial")&&ok==2&&failed==1,"One failed category does not discard successful categories");
        int before=changes,configs=ServiceSettings.configurations;
        SystemClock.now=480000;PoiSearchV2.entered=new CountDownLatch(1);PoiSearchV2.release=new CountDownLatch(1);
        loader.request(focus,key);check(PoiSearchV2.entered.await(3,TimeUnit.SECONDS),"Second stalled lookup starts");loader.close();
        PoiSearchV2.release.countDown();Handler.complete();loader.request(focus,key);
        check(changes==before&&ServiceSettings.configurations==configs+1&&loader.places(focus).isEmpty(),"Close rejects late callbacks and forbids subsequent SDK reconfiguration");
        System.out.println("PASS: "+checks+" real nearby SDK worker, cancellation, throttling, partial error and lifecycle checks");
    }
}'''
root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='motonav-places-') as directory:
    output = Path(directory)
    for name, source in stubs.items():
        path = output/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')
    (output/'NearbyPlacesLoaderTests.java').write_text(harness, encoding='utf-8')
    names = ['NearbyPlace', 'NearbyPlacesState', 'NearbyPlacesLoader', 'AmapAccess', 'DeviceRoute', 'Basemap', 'NavigationDetails', 'NavigationFrame', 'MapViewport']
    sources = [root/'app/src/main/java/com/motonav/companion'/(name+'.java') for name in names]
    java = str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '--release', '17', '-d', str(output), *map(str, output.rglob('*.java')), *map(str, sources)], check=True)
    subprocess.run([java, '-cp', str(output), 'com.motonav.companion.NearbyPlacesLoaderTests'], check=True)
