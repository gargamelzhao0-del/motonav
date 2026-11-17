package com.motonav.companion;

import java.nio.file.*;
import java.util.*;

public final class MapSurfaceTests {
    private static int checks;
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static VectorTile.Feature area(String layer,String kind,int x,int y,int width,int height){
        return new VectorTile.Feature(layer,kind,3,4096,List.of(List.of(
            new VectorTile.Point(x,y),new VectorTile.Point(x+width,y),
            new VectorTile.Point(x+width,y+height),new VectorTile.Point(x,y+height))));
    }
    public static void main(String[] args)throws Exception {
        var tile=new Basemap.Tile(14,8186,5448);
        var origin=Basemap.coordinate(tile,4096,2048,2048);
        var view=new MapViewport(0,0,0,false,0);
        var water=new VectorTile.Feature("water_lines","stream",2,4096,List.of(List.of(
            new VectorTile.Point(1850,1975),new VectorTile.Point(2240,1975))));
        var road=new VectorTile.Feature("streets","residential",2,4096,List.of(List.of(
            new VectorTile.Point(2048,2200),new VectorTile.Point(2048,1800))));
        var source=List.of(area("land","residential",1800,1800,500,500),
            area("sites","school",1900,1900,300,300),water,road);
        var legacy=new LocalMapBuilder(origin,c->c,view);legacy.add(tile,source);
        var extended=new LocalMapBuilder(origin,c->c,view,true);extended.add(tile,source);
        var scene=extended.finish(1);
        check(legacy.finish(1).items().stream().allMatch(p->p.kind()<=5),"Legacy builds exclude unnegotiated categories");
        check(legacy.quality(1,1,0,0,legacy.finish(1)).ignoredFeatures()==3,"Legacy omissions remain visible in acquisition counts");
        for(int kind:new int[]{0,6,7,8})check(scene.items().stream().anyMatch(p->p.kind()==kind),"Received real category survives conversion: "+kind);
        check(extended.quality(1,1,0,0,scene).ignoredFeatures()==0,"Supported surfaces are no longer reported as discarded");
        check(scene.items().stream().filter(p->p.kind()==8).allMatch(p->p.c().equals(new DeviceRoute.Point(0,0))),"Waterway remains a line, not an invented riverbank polygon");
        boolean malformed=false;
        try{new Basemap.Primitive(8,new DeviceRoute.Point(0,0),new DeviceRoute.Point(1,1),new DeviceRoute.Point(2,2));}
        catch(IllegalArgumentException expected){malformed=true;}
        check(malformed,"All line categories reject nonzero triangle tails");
        var dense=new LocalMapBuilder(origin,c->c,view,true);
        for(int i=0;i<450;i++)dense.add(tile,List.of(area("sites","construction",1800+i%30*15,1700+i/30*15,12,12)));
        var bounded=dense.finish(1);check(bounded.items().size()<=384,"Added area layers keep the existing radio capacity");
        check(bounded.items().size()%2==0,"Dense sites retain complete rectangle footprints");
        if(args.length>0){
            var protocol=new RouteProtocol(123);var rows=new ArrayList<String>();
            var points=List.of(new DeviceRoute.Point(0,-100),new DeviceRoute.Point(0,300));
            var route=new DeviceRoute.Frame(1,points,0,0,0);
            scene=new Basemap.Scene(1,1,true,scene.items(),List.of(new Basemap.Label(7,new DeviceRoute.Point(60,80),"测试站点")));
            var commands=List.of(protocol.helloWithSurfaces(),protocol.snapshot(NavigationFrame.status(1),null,100000),
                protocol.focus(new Basemap.Focus(route,origin,1,5000,600)),protocol.basemap(scene,0,true),protocol.labels(scene,0),
                protocol.snapshot(new NavigationFrame(2,3,120,4000,360,36,100000,100000),route,100000),
                protocol.snapshot(NavigationFrame.status(1),null,104002),protocol.snapshot(NavigationFrame.status(4),null,104003));
            for(var command:commands){check(command.bytes().length<=304,"Extended types keep the existing frame bound");rows.add(HexFormat.of().formatHex(command.bytes()));}
            Files.write(Path.of(args[0]),rows);
        }
        System.out.println("PASS: "+checks+" real category, legacy fallback, waterway and bounded surface checks");
    }
}
