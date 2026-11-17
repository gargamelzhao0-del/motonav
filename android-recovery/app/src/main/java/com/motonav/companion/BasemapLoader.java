package com.motonav.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.amap.api.maps.CoordinateConverter;
import com.amap.api.maps.model.LatLng;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One visible region at a time. Network and geometry work never block BLE/UI. */
final class BasemapLoader implements AutoCloseable {
    interface Listener { void loaded(Basemap.Scene scene); void unavailable(); }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final MapTileStore store;
    private final Context context;
    private final Listener listener;
    private volatile int generation;
    private int revision,update;
    private MapViewport camera;
    private long cameraRequestedMs;
    private boolean busy;
    private volatile boolean closed;
    private long retryAt;
    private Basemap.Scene scene;
    BasemapLoader(Context context,Listener listener) {
        this.context=context.getApplicationContext();this.listener=listener;
        store=new MapTileStore(new java.io.File(context.getFilesDir(),"visible-map-tiles").toPath(),MapTileStore.TEMPLATE,
                "MotoNav/"+BuildConfig.VERSION_NAME+" (https://github.com/gargamelzhao0-del/motonav)");
    }
    void request(Basemap.Focus focus,boolean progressive,NavigationFrame navigation) {
        if(closed||focus==null)return;
        int requested=focus.route().revision();
        // Finish an in-flight region rather than cancelling it for every
        // speed/GPS-state fluctuation. Origin changes still cancel immediately.
        if(busy&&requested==revision)return;
        var viewport=MapViewport.of(focus,navigation);
        long now=SystemClock.elapsedRealtime();
        boolean moved=progressive&&viewport.refreshFrom(camera)&&(camera==null||now-cameraRequestedMs>=1500);
        if(requested!=revision||moved){
            revision=requested;camera=viewport;cameraRequestedMs=now;
            scene=null;retryAt=0;generation++;
        }
        if(scene!=null)return;
        if(busy||SystemClock.elapsedRealtime()<retryAt)return;
        busy=true;int token=generation;
        worker.execute(()->{
            Basemap.Scene result=null;
            try {
                CoordinateConverter converter=new CoordinateConverter(context).from(CoordinateConverter.CoordType.GPS);
                Basemap.Coordinates conversion=p->{
                    LatLng value=converter.coord(new LatLng(p.latitude(),p.longitude())).convert();
                    if(value==null)throw new IllegalArgumentException("coordinate conversion failed");
                    return new DeviceRoute.Coordinate(value.latitude,value.longitude);
                };
                var builder=new LocalMapBuilder(focus.origin(),conversion,viewport);
                var gps=Basemap.gpsOrigin(focus.origin(),conversion);
                var tiles=Basemap.visibleTiles(gps,viewport);int loaded=0;
                for(var tile:tiles) {
                    if(closed||token!=generation)throw new InterruptedException();
                    builder.add(tile,VectorTile.decode(store.get(tile)));
                    loaded++;
                    if(progressive&&loaded<tiles.size()) {
                        var partial=builder.finish(requested,192);
                        var next=new Basemap.Scene(requested,++update,false,partial.items(),partial.labels());
                        if(!next.items().isEmpty()||!next.labels().isEmpty())main.post(()->{if(!closed&&token==generation)listener.loaded(next);});
                    }
                }
                var complete=builder.finish(requested,progressive?Basemap.MAX_ITEMS:192);
                result=new Basemap.Scene(requested,++update,true,complete.items(),complete.labels());
            } catch(Exception | LinkageError ignored) { /* UI reports availability; never log coordinates. */ }
            Basemap.Scene completed=result;
            main.post(()->{
                busy=false;if(closed||token!=generation)return;
                if(completed==null){retryAt=SystemClock.elapsedRealtime()+30000;listener.unavailable();}
                else {scene=completed;listener.loaded(completed);}
            });
        });
    }
    void clear(){generation++;revision=0;scene=null;camera=null;retryAt=0;}
    @Override public void close(){closed=true;clear();worker.shutdownNow();}
}
