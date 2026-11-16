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
    private int generation,revision;
    private boolean busy,closed;
    private long retryAt;
    private Basemap.Scene scene;
    BasemapLoader(Context context,Listener listener) {
        this.context=context.getApplicationContext();this.listener=listener;
        store=new MapTileStore(new java.io.File(context.getFilesDir(),"visible-map-tiles").toPath(),MapTileStore.TEMPLATE,
                "MotoNav/"+BuildConfig.VERSION_NAME+" (https://github.com/gargamelzhao0-del/motonav)");
    }
    void request(Basemap.Focus focus) {
        if(closed||focus==null)return;
        int requested=focus.route().revision();
        if(requested!=revision){revision=requested;scene=null;retryAt=0;generation++;}
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
                var builder=new LocalMapBuilder(focus.origin(),conversion);
                var gps=Basemap.gpsOrigin(focus.origin(),conversion);
                for(var tile:Basemap.visibleTiles(gps))builder.add(tile,VectorTile.decode(store.get(tile)));
                result=builder.finish(requested);
            } catch(Exception | LinkageError ignored) { /* UI reports availability; never log coordinates. */ }
            Basemap.Scene completed=result;
            main.post(()->{
                busy=false;if(closed||token!=generation)return;
                if(completed==null){retryAt=SystemClock.elapsedRealtime()+30000;listener.unavailable();}
                else {scene=completed;listener.loaded(completed);}
            });
        });
    }
    void clear(){generation++;revision=0;scene=null;retryAt=0;}
    @Override public void close(){closed=true;clear();worker.shutdownNow();}
}
