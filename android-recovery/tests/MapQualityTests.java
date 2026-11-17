package com.motonav.companion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MapQualityTests {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static DeviceRoute.Point p(int x,int y){return new DeviceRoute.Point(x,y);}
    private static VectorTile.Feature road(int offset){
        return new VectorTile.Feature("streets","residential",2,4096,List.of(List.of(
            new VectorTile.Point(2048+offset,2048),new VectorTile.Point(2048+offset,1800))));
    }
    private static List<Basemap.Primitive> fan(int count){
        List<Basemap.Primitive> result=new ArrayList<>();
        for(int i=0;i<count;i++){
            double a=i*2*Math.PI/count,b=(i+1)*2*Math.PI/count;
            result.add(new Basemap.Primitive(3,p(0,100),p((int)Math.round(160*Math.cos(a)),100+(int)Math.round(160*Math.sin(a))),
                p((int)Math.round(160*Math.cos(b)),100+(int)Math.round(160*Math.sin(b)))));
        }
        return result;
    }
    public static void main(String[] args)throws Exception{
        var tile=new Basemap.Tile(14,8186,5448);var neighbor=new Basemap.Tile(14,8187,5448);
        var origin=Basemap.coordinate(tile,4096,2048,2048);
        List<Basemap.Tile> tiles=List.of(tile,neighbor);List<MapRegionLoader.Result> progress=new ArrayList<>();
        AtomicInteger calls=new AtomicInteger();
        var partial=MapRegionLoader.load(tiles,new LocalMapBuilder(origin,c->c),1,384,t->{
            calls.incrementAndGet();if(t.equals(tile))throw new IOException("test outage");
            return new MapRegionLoader.TileData(new VectorTile.Decoded(List.of(road(-4096)),Map.of("streets",1,"addresses",5)),false);
        },progress::add,()->false);
        check(calls.get()==2&&!partial.scene().items().isEmpty(),"first tile failure cannot suppress valid neighbor");
        check(!partial.scene().complete()&&partial.quality().tilesLoaded()==1&&partial.quality().tilesFailed()==1,"partial coverage is explicit");
        check(partial.quality().sourceFeatures()==6&&partial.quality().ignoredFeatures()==5,"unsupported provider layers remain visible in counts");
        check(progress.size()==2,"progress includes failed-tile outcome");
        var complete=MapRegionLoader.load(tiles,new LocalMapBuilder(origin,c->c),1,384,t->
            new MapRegionLoader.TileData(new VectorTile.Decoded(List.of(road(t.equals(tile)?0:-4096)),Map.of("streets",1)),false),null,()->false);
        check(complete.scene().complete()&&complete.quality().tilesLoaded()==2,"recovered tiles complete the region");
        check(complete.scene().items().size()==1&&complete.quality().geometryDuplicates()==1,"buffered duplicate roads consume one primitive");
        var stale=MapRegionLoader.load(List.of(tile),new LocalMapBuilder(origin,c->c),1,384,t->
            new MapRegionLoader.TileData(new VectorTile.Decoded(List.of(road(0)),Map.of("streets",1)),true),null,()->false);
        check(!stale.scene().complete()&&stale.quality().tilesStale()==1&&!stale.scene().items().isEmpty(),"stale useful geometry remains explicitly incomplete");
        var failed=MapRegionLoader.load(tiles,new LocalMapBuilder(origin,c->c),1,384,t->{throw new IOException();},null,()->false);
        check(failed.quality().tilesFailed()==2&&failed.quality().tilesLoaded()==0&&!failed.scene().complete(),"all failures are not a completed empty map");
        AtomicInteger fetch=new AtomicInteger();List<MapRegionLoader.Result> late=new ArrayList<>();boolean cancelled=false;
        try{MapRegionLoader.load(tiles,new LocalMapBuilder(origin,c->c),1,384,t->{fetch.incrementAndGet();return new MapRegionLoader.TileData(new VectorTile.Decoded(List.of(),Map.of()),false);},late::add,()->fetch.get()>0);}
        catch(InterruptedException expected){cancelled=true;}
        check(cancelled&&fetch.get()==1&&late.isEmpty(),"cancelled origin never publishes a late tile");

        var selector=new MapDetailSelector(new MapViewport(0,0,0,false,0).detailView());
        var complex=fan(120);selector.add(complex);
        check(selector.finish(384).size()==120,"whole 120-triangle building survives the old 96-triangle cutoff");
        var reversed=new ArrayList<Basemap.Primitive>();for(var x:complex)reversed.add(new Basemap.Primitive(x.kind(),x.c(),x.b(),x.a()));
        Collections.reverse(reversed);selector.add(reversed);
        check(selector.finish(384).size()==120&&selector.duplicateItems()==120,"opposite winding and order do not consume another building budget");
        selector.add(fan(400));check(selector.oversizedGroups()==1,"objects larger than wire capacity are counted, not silently claimed complete");
        var sea=new LocalMapBuilder(origin,c->c);sea.add(tile,List.of(new VectorTile.Feature("ocean","",3,4096,List.of(List.of(
            new VectorTile.Point(1900,1900),new VectorTile.Point(2200,1900),new VectorTile.Point(2200,2200),new VectorTile.Point(1900,2200))))));
        check(sea.finish(1).items().stream().anyMatch(x->x.kind()==4),"ocean layer is retained as actual water geometry");
        var paths=new LocalMapBuilder(origin,c->c);
        paths.add(tile,List.of(new VectorTile.Feature("streets","footway",2,4096,road(0).paths())));
        check(paths.finish(1).items().size()==1,"real footpaths retained as secondary map context");
        paths.add(tile,List.of(new VectorTile.Feature("streets","rail",2,4096,road(0).paths())));
        check(paths.quality(1,1,0,0,paths.finish(1)).ignoredFeatures()==1,"railway is counted as unsupported rather than fabricated as a road");

        var labels=new LocalMapLabels(0,0);
        var cafeA=new VectorTile.Feature("pois","cafe",1,4096,List.of(),"咖啡甲");
        var cafeB=new VectorTile.Feature("pois","cafe",1,4096,List.of(),"咖啡乙");
        labels.add(cafeA,List.of(p(10,100)));labels.add(cafeB,List.of(p(15,100)));
        check(labels.finish().size()==2,"nearby distinct names are not conflated");
        for(int i=0;i<600;i++)labels.add(cafeA,List.of(p(11,100)));
        check(labels.finish().size()==2&&labels.duplicateCount()==600,"deduplication precedes bounded candidate eviction");
        labels.add(new VectorTile.Feature("street_labels_points","pedestrian",1,4096,List.of(),"中心广场"),List.of(p(50,60)));
        check(labels.finish().stream().anyMatch(x->x.kind()==0&&x.name().equals("中心广场")),"provider point road labels are retained");
        var visible=new LocalMapLabels(new MapViewport(0,0,0,false,0).detailView());
        visible.add(cafeA,List.of(p(600,0)));visible.add(cafeB,List.of(p(50,120)));
        check(visible.finish().size()==1&&visible.finish().get(0).name().equals("咖啡乙"),"offscreen POIs do not displace visible names");
        var profile=new MapViewport(0,0,0,true,36).detailView();
        check(Math.abs(profile.pitch-Math.toRadians(35))<1e-12&&profile.anchor==298,"detail ranking matches installed 35-degree firmware");
        check(Math.abs(profile.metersPerPixel-(1.02+26.0/70*.65))<1e-12,"matching speed scale");
        var east=new MapViewport(0,0,900,true,30);
        var focus=new Basemap.Focus(new DeviceRoute.Frame(1,List.of(p(0,0),p(0,100)),0,0,-1),origin,1,5000,600);
        check(Math.abs(MapViewport.of(focus,NavigationFrame.status(1),east).heading-Math.PI/2)<1e-12,"unknown bearing retains previous coverage direction");
        var stopped=new NavigationFrame(2,3,120,4000,360,0,100000,100000);
        check(Math.abs(MapViewport.of(focus,stopped,east).heading-Math.PI/2)<1e-12,"standstill coverage follows the held device direction");

        byte[] valid=BasemapTests.tile(BasemapTests.bytes(9,0,0,18,20,0,0,20));
        Path cache=Files.createTempDirectory("motonav-map-quality-");
        Path cached=cache.resolve(tile.key().replace('/','_')+".mvt");
        try{
            AtomicInteger downloads=new AtomicInteger();
            var store=new MapTileStore(cache,MapTileStore.TEMPLATE,"MotoNav test",t->{downloads.incrementAndGet();return valid;});
            check(!store.read(tile).stale()&&!store.read(tile).stale()&&downloads.get()==1,"valid fresh tile reused without download");
            long old=System.currentTimeMillis()-8L*24*60*60*1000;Files.setLastModifiedTime(cached,FileTime.fromMillis(old));
            var offline=new MapTileStore(cache,MapTileStore.TEMPLATE,"MotoNav test",t->{throw new IOException("offline");});
            check(offline.read(tile).stale()&&Arrays.equals(Files.readAllBytes(cached),valid),"offline expiry keeps validated original bytes");
            check(Files.getLastModifiedTime(cached).toMillis()==old,"stale fallback does not renew freshness");
            Files.write(cached,new byte[]{26,10,1});
            check(!store.read(tile).stale()&&downloads.get()==2,"corrupt fresh cache is repaired instead of poisoning a week of requests");
            Files.setLastModifiedTime(cached,FileTime.fromMillis(old));
            var corruptRemote=new MapTileStore(cache,MapTileStore.TEMPLATE,"MotoNav test",t->new byte[]{26,10,1});
            check(corruptRemote.read(tile).stale()&&Arrays.equals(Files.readAllBytes(cached),valid),"malformed replacement cannot erase the previous map");
        }finally{
            Files.deleteIfExists(cached.resolveSibling(cached.getFileName()+".tmp"));Files.deleteIfExists(cached);Files.delete(cache);
        }
        System.out.println("PASS: "+checks+" map source, retention, cache, deduplication and complete-object checks");
    }
}
