package com.motonav.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.poisearch.PoiSearchV2;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Optional SDK name queries never share the tile worker, BLE task or UI thread. */
final class NearbyPlacesLoader implements AutoCloseable {
    interface Listener { void changed(); void status(String phase,int received,int ok,int failed,int error); }
    private static final String[] TYPES={"120000","140000|090000|110000","150000|060100|010100"};
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final NearbyPlacesState state=new NearbyPlacesState();
    private volatile int epoch;
    private volatile boolean closed;
    private boolean busy;
    NearbyPlacesLoader(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    List<NearbyPlace> places(Basemap.Focus focus){return state.visible(NearbyPlace.center(focus),SystemClock.elapsedRealtime());}
    void request(Basemap.Focus focus,String key) {
        var center=NearbyPlace.center(focus);long now=SystemClock.elapsedRealtime();
        if(closed||busy||!AmapAccess.configured(key)||!state.shouldRequest(center,now))return;
        state.started(now);
        // Configure the already-consented SDK on the main thread, so a later
        // privacy withdrawal cannot be overwritten by a delayed worker setup.
        try {
            ServiceSettings.updatePrivacyShow(context,true,true);ServiceSettings.updatePrivacyAgree(context,true);
            ServiceSettings.getInstance().setApiKey(key);ServiceSettings.getInstance().setProtocol(ServiceSettings.HTTPS);
            ServiceSettings.getInstance().setConnectionTimeOut(10000);ServiceSettings.getInstance().setSoTimeOut(10000);
        }catch(RuntimeException|LinkageError failure){listener.status("error",0,0,3,-1);return;}
        busy=true;int token=epoch;listener.status("loading",state.visible(center,now).size(),0,0,0);
        worker.execute(()->{
            List<NearbyPlace> places=new ArrayList<>();int ok=0,failed=0,error=0;
            for(String types:TYPES){
                if(closed||token!=epoch||Thread.currentThread().isInterrupted())break;
                try {
                    var query=new PoiSearchV2.Query("",types,"");query.setPageSize(25);query.setPageNum(0);
                    var search=new PoiSearchV2(context,query);
                    search.setBound(new PoiSearchV2.SearchBound(new LatLonPoint(center.latitude(),center.longitude()),1000,true));
                    var result=search.searchPOI();
                    if(result==null||result.getPois()==null){failed++;error=-2;continue;}
                    ok++;
                    for(var item:result.getPois()){
                        if(places.size()>=75)break;
                        if(item==null||item.getLatLonPoint()==null)continue;
                        try {places.add(new NearbyPlace(item.getPoiId(),item.getTitle(),item.getTypeCode(),
                            new DeviceRoute.Coordinate(item.getLatLonPoint().getLatitude(),item.getLatLonPoint().getLongitude())));}
                        catch(IllegalArgumentException ignored){/* Skip malformed/unnamed results, never fabricate a name. */}
                    }
                }catch(AMapException failure){failed++;error=failure.getErrorCode();}
                catch(RuntimeException|LinkageError failure){failed++;error=-1;}
            }
            final int successes=ok,failures=failed,code=error;
            main.post(()->{
                busy=false;if(closed||token!=epoch)return;
                state.accept(center,places,successes,SystemClock.elapsedRealtime());
                listener.status(failures==0?"ready":successes>0?"partial":"error",state.visible(center,SystemClock.elapsedRealtime()).size(),successes,failures,code);
                listener.changed();
            });
        });
    }
    void clear(){epoch++;state.clear();}
    @Override public void close(){closed=true;clear();worker.shutdownNow();}
}
