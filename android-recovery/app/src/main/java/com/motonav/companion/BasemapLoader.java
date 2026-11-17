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
    interface Listener { void loaded(Basemap.Scene scene); void quality(MapQuality quality); void unavailable(); }
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
    private int loadedTileWatermark;
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
        var viewport=MapViewport.of(focus,navigation,requested==revision?camera:null);
        long now=SystemClock.elapsedRealtime();
        boolean moved=progressive&&viewport.refreshFrom(camera)&&(camera==null||now-cameraRequestedMs>=1500);
        if(requested!=revision||moved){
            revision=requested;camera=viewport;cameraRequestedMs=now;
            scene=null;loadedTileWatermark=0;retryAt=0;generation++;
        }
        if(scene!=null&&scene.complete())return;
        if(busy||SystemClock.elapsedRealtime()<retryAt)return;
        busy=true;int token=generation;
        worker.execute(()->{
            MapRegionLoader.Result result=null;
            try {
                CoordinateConverter converter=new CoordinateConverter(context).from(CoordinateConverter.CoordType.GPS);
                Basemap.Coordinates conversion=p->{
                    LatLng value=converter.coord(new LatLng(p.latitude(),p.longitude())).convert();
                    if(value==null)throw new IllegalArgumentException("coordinate conversion failed");
                    return new DeviceRoute.Coordinate(value.latitude,value.longitude);
                };
                var builder=new LocalMapBuilder(focus.origin(),conversion,viewport);
                var gps=Basemap.gpsOrigin(focus.origin(),conversion);
                result=MapRegionLoader.load(Basemap.visibleTiles(gps,viewport),builder,requested,progressive?Basemap.MAX_ITEMS:192,
                    tile->{var read=store.read(tile);return new MapRegionLoader.TileData(VectorTile.decodeDetailed(read.bytes()),read.stale());},
                    partial->{if(progressive)main.post(()->accept(token,partial));},()->closed||token!=generation);
            } catch(Exception | LinkageError ignored) { /* UI reports availability; never log coordinates. */ }
            MapRegionLoader.Result completed=result;
            main.post(()->{
                busy=false;if(closed||token!=generation)return;
                if(completed==null||!completed.quality().downloaded())retryAt=SystemClock.elapsedRealtime()+30000;
                if(completed!=null){
                    // Legacy receivers cannot replace a same-origin map.
                    if(progressive||completed.quality().downloaded())accept(token,completed);
                    listener.quality(completed.quality());
                }
                if(completed==null||completed.quality().tilesLoaded()==0)listener.unavailable();
            });
        });
    }
    private void accept(int token,MapRegionLoader.Result result){
        if(closed||token!=generation||result.quality().tilesLoaded()<loadedTileWatermark)return;
        var next=result.scene();
        if(result.quality().tilesLoaded()==0)return;
        loadedTileWatermark=result.quality().tilesLoaded();listener.quality(result.quality());
        // Retrying a missing neighbor must not repeatedly replace a rich map
        // with its first cached tile or retransmit an unchanged scene.
        if(scene!=null&&scene.complete()==next.complete()&&scene.items().equals(next.items())&&scene.labels().equals(next.labels()))return;
        scene=new Basemap.Scene(next.revision(),++update,next.complete(),next.items(),next.labels());
        listener.loaded(scene);
    }
    void clear(){generation++;revision=0;scene=null;camera=null;loadedTileWatermark=0;retryAt=0;}
    @Override public void close(){closed=true;clear();worker.shutdownNow();}
}
